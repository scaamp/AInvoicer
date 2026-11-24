package customer.cap_java.ksef.inbound.sync;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import customer.cap_java.ksef.KsefDocumentStore;
import customer.cap_java.ksef.KsefInboundAccess;
import customer.cap_java.ksef.fa3.Fa3SchemaValidator;
import customer.cap_java.ksef.inbound.InvoiceRetrievalService;
import customer.cap_java.ksef.inbound.KsefInboundClient;
import customer.cap_java.ksef.inbound.KsefNumbers;
import customer.cap_java.ksef.inbound.KsefSubjectRole;
import customer.cap_java.ksef.inbound.KsefTaxpayerContext;
import customer.cap_java.ksef.inbound.MetadataPage;
import customer.cap_java.ksef.inbound.MetadataQuery;
import customer.cap_java.ksef.inbound.PersistentInboundInvoiceSink;
import customer.cap_java.ksef.inbound.ReadRetryPolicy;
import customer.cap_java.ksef.inbound.fa3.SecureFa3InvoiceParser;
import customer.cap_java.ksef.inbound.persistence.CqnInboundDocumentRepository;
import customer.cap_java.ksef.inbound.persistence.CqnInboundSyncLeaseRepository;

/**
 * KSEF-IN-05 (B3): minimalny, wewnętrzny import operatorski <b>jednej</b> faktury zakupowej po numerze KSeF.
 *
 * <p>Brak publicznej akcji OData, schedulera i batcha. Bean istnieje wyłącznie przy
 * {@code ainv.ksef.inbound.enabled=true} i {@code ainv.ksef.inbound.manual-import.enabled=true} (oba domyślnie OFF);
 * w chmurze blokuje go bezwarunkowy guard {@code KSEF_INBOUND_CLOUD_ACTIVATION_NOT_READY}.
 *
 * <p>Przebieg (limity wywołań aplikacyjnych; transport SDK/JDK nie egzekwuje budżetu HTTP):
 * <ol>
 *   <li>walidacja numeru (wzorzec + CRC) i okna dat <b>przed</b> auth — błąd = zero HTTP;</li>
 *   <li>trwały lease {@code KsefInboundSyncLeases} tego samego zakresu co synchronizacja (TEST, NIP z konfiguracji,
 *       BUYER): zajęty/cooldown = {@link Outcome#BUSY} bez auth; import i synchronizacja dzielą cooldown kontekstu;</li>
 *   <li>istniejący auth ({@link KsefInboundAccess}, NIP wyłącznie z konfiguracji backendu);</li>
 *   <li>{@code POST /invoices/query/metadata} ×1: filtr {@code ksefNumber}, PermanentStorage, strona 10, offset 0,
 *       bez ponowień; {@code hasMore/isTruncated} lub inny numer = odrzucenie; pusta strona = {@link Outcome#NOT_FOUND};</li>
 *   <li>niezmieniony {@link PersistentInboundInvoiceSink}: GET XML ×1 bez ponowień, SHA-256 z metadanych,
 *       parser/XSD/cross-check, trwały store, finalizacja i grant — trwały status w {@code KsefInboundDocuments}.</li>
 * </ol>
 * Checkpoint synchronizacji nie jest zmieniany. Brak automatycznego retry: każdy błąd kończy lease według tej samej
 * polityki co synchronizacja ({@link InboundSynchronization#finishFailed}); niejednoznaczny auth/redeem = BLOCKED.
 *
 * <p>Okno z segmentu daty {@code RRRRMMDD} jest ograniczeniem operacyjnym. Kompletność wyników przed upływem
 * okna zależy od HWM KSeF, a kontrakt nie gwarantuje, że PermanentStorage mieści się w tym oknie.
 */
public final class InboundManualImport {
    /** Margines strefy czasowej przed datą z numeru KSeF. */
    static final Duration LOOKBACK = Duration.ofDays(1);
    /** Górna granica zapytania względem daty z numeru KSeF. */
    static final Duration LOOKAHEAD = Duration.ofDays(30);
    private static final DateTimeFormatter NUMBER_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    public enum Outcome {
        STORED,
        /** Brak wyniku w sprawdzonym oknie PermanentStorage i kontekście TEST/NIP/BUYER. */
        NOT_FOUND,
        BUSY
    }

    /** Wynik bez XML, referencji magazynu i danych auth; {@code documentId} = deterministyczny UUID stagingu. */
    public record Result(Outcome outcome, String documentId) { }

    private final SyncScope scope;
    private final Clock clock;
    private final KsefInboundAccess access;
    private final KsefInboundClient client;
    private final CqnInboundDocumentRepository documents;
    private final CqnInboundSyncLeaseRepository leases;
    private final KsefDocumentStore store;
    private final Fa3SchemaValidator schema;

    public InboundManualImport(SyncScope scope, Clock clock, KsefInboundAccess access, KsefInboundClient client,
            CqnInboundDocumentRepository documents, CqnInboundSyncLeaseRepository leases, KsefDocumentStore store,
            Fa3SchemaValidator schema) {
        if (Objects.requireNonNull(scope).role() != KsefSubjectRole.BUYER) {
            throw new IllegalArgumentException("KSEF_INBOUND_MANUAL_IMPORT_BUYER_ONLY");
        }
        this.scope = scope;
        this.clock = Objects.requireNonNull(clock);
        this.access = Objects.requireNonNull(access);
        this.client = Objects.requireNonNull(client);
        this.documents = Objects.requireNonNull(documents);
        this.leases = Objects.requireNonNull(leases);
        this.store = Objects.requireNonNull(store);
        this.schema = Objects.requireNonNull(schema);
    }

    /** Jeden jawny import; wywołujący nie może mieć aktywnej transakcji (efekty HTTP poza ChangeSet). */
    public Result importOne(String ksefNumber) {
        if (com.sap.cds.services.changeset.ChangeSetContext.isActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("KSEF_INBOUND_BUSINESS_TRANSACTION_FORBIDDEN");
        }
        if (!KsefNumbers.isValid(ksefNumber)) { throw new IllegalArgumentException("KSEF_NUMBER_INVALID"); }
        MetadataQuery query = query(ksefNumber);
        var claim = leases.claim(scope, Duration.ofMinutes(10));
        if (claim.isEmpty()) { return new Result(Outcome.BUSY, null); }
        try {
            var session = access.open();
            var context = new KsefTaxpayerContext(scope.environment(), scope.contextNip());
            leases.requireOwned(claim.get());
            MetadataPage page = client.queryMetadataPage(context, session.token(), query, 0);
            if (page.hasMore() || page.truncated() || page.invoices().size() > 1) {
                throw new IllegalStateException("KSEF_INBOUND_MANUAL_IMPORT_AMBIGUOUS");
            }
            if (page.invoices().isEmpty()) {
                leases.finish(claim.get(), InboundSynchronization.later(clock, null), false);
                return new Result(Outcome.NOT_FOUND, null);
            }
            var metadata = page.invoices().getFirst();
            if (!ksefNumber.equals(metadata.ksefNumber())) {
                throw new IllegalStateException("KSEF_INBOUND_MANUAL_IMPORT_NUMBER_MISMATCH");
            }
            // Ten sam sink co synchronizacja; GET bez ponowień (jeden przebieg = jedna próba odczytu XML).
            var retrieval = new InvoiceRetrievalService(client, ReadRetryPolicy.noRetry(clock));
            var sink = new PersistentInboundInvoiceSink(scope, session::token, retrieval, documents, store,
                    new SecureFa3InvoiceParser(), schema);
            leases.requireOwned(claim.get());
            sink.accept(metadata);
            leases.finish(claim.get(), InboundSynchronization.later(clock, null), false);
            return new Result(Outcome.STORED, CqnInboundDocumentRepository.documentId(metadata));
        } catch (RuntimeException error) {
            InboundSynchronization.finishFailed(leases, claim.get(), error, clock);
            throw error;
        }
    }

    /** Okno PermanentStorage przycięte do teraz; puste okno kończy import przed auth. */
    MetadataQuery query(String ksefNumber) {
        LocalDate day = LocalDate.parse(ksefNumber.substring(11, 19), NUMBER_DATE);
        OffsetDateTime from = day.atStartOfDay().atOffset(ZoneOffset.UTC).minus(LOOKBACK);
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime to = from.plus(LOOKBACK).plus(LOOKAHEAD);
        if (to.isAfter(now)) { to = now; }
        if (!to.isAfter(from)) { throw new IllegalArgumentException("KSEF_INBOUND_MANUAL_IMPORT_DATE_INVALID"); }
        return MetadataQuery.singleInvoice(scope.role(), ksefNumber, from, to);
    }
}
