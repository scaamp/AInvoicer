package customer.cap_java.ksef.inbound;

import static customer.cap_java.ksef.inbound.InboundFailure.Category.CORRUPT_RESPONSE;
import static customer.cap_java.ksef.inbound.InboundFailure.Category.INTEGRITY_MISMATCH;
import static customer.cap_java.ksef.inbound.InboundFailure.Category.SCOPE_MISMATCH;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Objects;

import customer.cap_java.ksef.inbound.fa3.Fa3InboundInvoice;

/**
 * Aplikacyjna usługa odczytu INBOUND: przejście stron metadanych jednego zestawu filtrów, pobranie XML
 * z weryfikacją integralności i krzyżowa kontrola treści XML z metadanymi KSeF.
 *
 * <p>Usługa nie utrwala niczego i nie przesuwa checkpointu — robi to {@code sync.InvoiceSyncRunner}
 * dopiero po bezpiecznym przetworzeniu całego okna. Nie ma stanu współdzielonego między wywołaniami.
 *
 * <p><b>Rozróżnienie wolumenów (limity MF):</b> {@link #downloadVerified} (64 req/h na PROD) jest właściwe dla
 * niskiego wolumenu. Dla dużych wolumenów MF zaleca eksport paczek {@code POST /invoices/exports} —
 * poza zakresem KSEF-IN-01 (backlog KSEF-IN-03).
 */
public class InvoiceRetrievalService {
    /** Limit techniczny MF: ≤ 10 000 rekordów na zestaw filtrów. */
    static final int MAX_RECORDS_PER_FILTER = 10_000;

    private final KsefInboundClient client;
    private final ReadRetryPolicy retry;

    public InvoiceRetrievalService(KsefInboundClient client, ReadRetryPolicy retry) {
        this.client = Objects.requireNonNull(client);
        this.retry = Objects.requireNonNull(retry);
    }

    /**
     * Pobiera wszystkie strony zapytania (pageOffset 0, 1, 2…) aż do {@code hasMore=false} albo
     * {@code isTruncated=true}. Każda strona to bezpieczny odczyt z kontrolowanym retry.
     *
     * <p>Kontrole spójności (naruszenie = {@code CORRUPT_RESPONSE}/{@code INTEGRITY_MISMATCH}, wynik odrzucony):
     * HWM stały dla wszystkich stron; rosnąca data sortowania; dla PermanentStorage rekordy nie wcześniejsze niż
     * {@code from}; ograniczona liczba stron; ten sam numer KSeF z innym {@code invoiceHash} to konflikt.
     * Błąd na dowolnej stronie przerywa cały segment — wynik częściowy nie jest zwracany.
     */
    public MetadataSegment fetchSegment(KsefTaxpayerContext context, InboundAccessToken token, MetadataQuery query) {
        int maxPages = (MAX_RECORDS_PER_FILTER + query.pageSize() - 1) / query.pageSize() + 1;
        var unique = new LinkedHashMap<String, KsefInvoiceMetadata>();
        int duplicates = 0;
        OffsetDateTime hwm = null;
        OffsetDateTime lastSort = null;
        for (int page = 0; ; page++) {
            if (page >= maxPages) {
                throw InboundFailure.of(CORRUPT_RESPONSE, "PAGINATION_UNBOUNDED");
            }
            final int pageOffset = page;
            MetadataPage result = retry.execute(() -> client.queryMetadataPage(context, token, query, pageOffset));
            if (page == 0) {
                hwm = result.hwmDate();
            } else if (!Objects.equals(hwm, result.hwmDate())) {
                throw InboundFailure.of(CORRUPT_RESPONSE, "HWM_UNSTABLE");
            }
            for (KsefInvoiceMetadata item : result.invoices()) {
                OffsetDateTime sortKey = sortKey(query.dateType(), item);
                if (sortKey != null) {
                    if (lastSort != null && sortKey.isBefore(lastSort)) {
                        throw InboundFailure.of(CORRUPT_RESPONSE, "ORDER");
                    }
                    if (query.dateType() == MetadataQuery.DateType.PERMANENT_STORAGE && sortKey.isBefore(query.from())) {
                        throw InboundFailure.of(CORRUPT_RESPONSE, "OUT_OF_RANGE");
                    }
                    lastSort = sortKey;
                }
                KsefInvoiceMetadata previous = unique.putIfAbsent(item.ksefNumber(), item);
                if (previous != null) {
                    if (!previous.invoiceHashBase64().equals(item.invoiceHashBase64())) {
                        throw InboundFailure.of(INTEGRITY_MISMATCH, "DUPLICATE_KSEF_NUMBER_HASH");
                    }
                    duplicates++;
                }
            }
            if (result.truncated()) {
                return new MetadataSegment(query, unique.values().stream().toList(), page + 1, duplicates, true, false,
                        hwm, lastSort);
            }
            if (!result.hasMore()) {
                return new MetadataSegment(query, unique.values().stream().toList(), page + 1, duplicates, false, true,
                        hwm, lastSort);
            }
        }
    }

    /**
     * Pobiera XML faktury opisanej metadanymi i weryfikuje SHA-256 dokładnych bajtów z {@code invoiceHash}.
     * Metadane muszą pochodzić z tego samego środowiska i kontekstu NIP — nie można użyć metadanych
     * odczytanych przez inny podmiot, aby pobrać dokument w obcym zakresie.
     */
    public RetrievedInvoice downloadVerified(KsefTaxpayerContext context, InboundAccessToken token,
            KsefInvoiceMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        if (metadata.environment() != context.environment() || !metadata.contextNip().equals(context.nip())) {
            throw InboundFailure.of(SCOPE_MISMATCH, "METADATA_CONTEXT");
        }
        RetrievedInvoice invoice = retry.execute(() -> client.downloadInvoice(context, token, metadata.ksefNumber()));
        if (!invoice.matchesKsefHash(metadata.invoiceHashBase64())) {
            throw InboundFailure.of(INTEGRITY_MISMATCH, "CONTENT_HASH");
        }
        return invoice;
    }

    /**
     * Krzyżowa kontrola sparsowanego FA(3) z metadanymi KSeF: numer faktury, data wystawienia, NIP sprzedawcy,
     * waluta oraz NIP nabywcy (gdy KSeF identyfikuje nabywcę przez Nip). Rozbieżność oznacza, że dokument
     * i metadane nie opisują tej samej faktury — {@code INTEGRITY_MISMATCH}, bez „naprawiania” danych.
     */
    public void crossCheck(KsefInvoiceMetadata metadata, Fa3InboundInvoice invoice) {
        require(metadata.invoiceNumber().equals(invoice.invoiceNumber()), "XML_INVOICE_NUMBER");
        require(metadata.issueDate().equals(invoice.issueDate()), "XML_ISSUE_DATE");
        require(metadata.sellerNip().equals(invoice.seller().identifier()), "XML_SELLER_NIP");
        if (metadata.currency() != null) {
            require(metadata.currency().equals(invoice.currency()), "XML_CURRENCY");
        }
        if ("Nip".equals(metadata.buyerIdentifierType())) {
            require(invoice.buyer().identifierType() == Fa3InboundInvoice.IdentifierType.NIP
                    && metadata.buyerIdentifier().equals(invoice.buyer().identifier()), "XML_BUYER_NIP");
        }
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw InboundFailure.of(INTEGRITY_MISMATCH, detail);
        }
    }

    private static OffsetDateTime sortKey(MetadataQuery.DateType type, KsefInvoiceMetadata item) {
        return switch (type) {
            case PERMANENT_STORAGE -> item.permanentStorageDate();
            case INVOICING -> {
                if (item.invoicingDate() == null) {
                    throw InboundFailure.of(CORRUPT_RESPONSE, "METADATA_INVOICING_DATE");
                }
                yield item.invoicingDate();
            }
            case ISSUE -> null;
        };
    }
}
