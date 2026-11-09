package customer.cap_java.ksef.inbound;

import static customer.cap_java.ksef.inbound.InboundFailure.Category.CORRUPT_RESPONSE;
import static customer.cap_java.ksef.inbound.InboundFailure.Category.SCOPE_MISMATCH;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.regex.Pattern;

import pl.akmf.ksef.sdk.client.model.invoice.IdentifierType;
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceMetadata;

/**
 * Mapuje {@code InvoiceMetadata} z SDK MF na {@link KsefInvoiceMetadata}.
 *
 * <p>Nie zgaduje brakujących pól: wymagane do tożsamości, synchronizacji i weryfikacji pola
 * (numer KSeF, numer faktury, data wystawienia, PermanentStorage, NIP sprzedawcy, typ identyfikatora
 * nabywcy, formCode, invoiceType, invoiceHash) muszą być obecne i poprawne, inaczej cała strona jest
 * odrzucana jako {@code CORRUPT_RESPONSE}. Pozostałe pola mapuje 1:1 albo zostawia {@code null}.
 *
 * <p>Kontrola zakresu ({@code SCOPE_MISMATCH}) — odpowiedź nie może dotyczyć innego podmiotu niż kontekst:
 * <ul>
 *   <li>SELLER (Subject1): NIP sprzedawcy musi być równy NIP kontekstu;</li>
 *   <li>BUYER (Subject2): jeśli identyfikator nabywcy jest typu Nip, musi być równy NIP kontekstu
 *       (inne typy, np. VatUe, są przyjmowane — KSeF filtruje po kontekście);</li>
 *   <li>THIRD_PARTY/AUTHORIZED: brak kontroli stron w tym sprincie (udokumentowane ograniczenie).</li>
 * </ul>
 */
public final class InvoiceMetadataMapper {
    private static final Pattern NIP = KsefTaxpayerContext.NIP_PATTERN;
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    public KsefInvoiceMetadata map(KsefTaxpayerContext context, KsefSubjectRole role, InvoiceMetadata source) {
        if (source == null) {
            throw corrupt("NULL_ITEM");
        }
        String ksefNumber = source.getKsefNumber();
        if (!KsefNumbers.isValid(ksefNumber)) {
            throw corrupt("KSEF_NUMBER");
        }
        String invoiceNumber = source.getInvoiceNumber();
        if (invoiceNumber == null || invoiceNumber.isBlank() || invoiceNumber.length() > 256) {
            throw corrupt("INVOICE_NUMBER");
        }
        if (source.getIssueDate() == null) {
            throw corrupt("ISSUE_DATE");
        }
        if (source.getPermanentStorageDate() == null) {
            throw corrupt("PERMANENT_STORAGE_DATE");
        }
        var seller = source.getSeller();
        if (seller == null || seller.getNip() == null || !NIP.matcher(seller.getNip()).matches()) {
            throw corrupt("SELLER_NIP");
        }
        var buyer = source.getBuyer();
        if (buyer == null || buyer.getIdentifier() == null || buyer.getIdentifier().getType() == null) {
            throw corrupt("BUYER_IDENTIFIER");
        }
        IdentifierType buyerType = buyer.getIdentifier().getType();
        String buyerValue = buyer.getIdentifier().getValue();
        if (buyerType == IdentifierType.NIP && (buyerValue == null || !NIP.matcher(buyerValue).matches())) {
            throw corrupt("BUYER_NIP");
        }
        if (buyerType != IdentifierType.NONE && (buyerValue == null || buyerValue.isBlank())) {
            throw corrupt("BUYER_IDENTIFIER_VALUE");
        }
        var form = source.getFormCode();
        if (form == null || blank(form.getSystemCode()) || blank(form.getSchemaVersion()) || blank(form.getValue())) {
            throw corrupt("FORM_CODE");
        }
        if (source.getInvoiceType() == null) {
            throw corrupt("INVOICE_TYPE");
        }
        String hash = source.getInvoiceHash();
        if (!isSha256Base64(hash)) {
            throw corrupt("INVOICE_HASH");
        }
        String currency = source.getCurrency();
        if (currency != null && !CURRENCY.matcher(currency).matches()) {
            throw corrupt("CURRENCY");
        }

        // Odpowiedź musi dotyczyć podmiotu kontekstu w deklarowanej roli; inaczej nie ufamy całej stronie.
        if (role == KsefSubjectRole.SELLER && !context.nip().equals(seller.getNip())) {
            throw new InboundFailure(SCOPE_MISMATCH, 0, null, null, "SELLER_NOT_CONTEXT");
        }
        if (role == KsefSubjectRole.BUYER && buyerType == IdentifierType.NIP && !context.nip().equals(buyerValue)) {
            throw new InboundFailure(SCOPE_MISMATCH, 0, null, null, "BUYER_NOT_CONTEXT");
        }

        return new KsefInvoiceMetadata(
                context.environment(), context.nip(), role,
                ksefNumber, invoiceNumber, source.getIssueDate(),
                utc(source.getInvoicingDate()), utc(source.getAcquisitionDate()), utc(source.getPermanentStorageDate()),
                seller.getNip(), seller.getName(),
                buyerType.getValue(), buyerType == IdentifierType.NONE ? null : buyerValue, buyer.getName(),
                decimal(source.getNetAmount()), decimal(source.getGrossAmount()), decimal(source.getVatAmount()),
                currency,
                source.getInvoiceType().getValue(),
                new KsefInvoiceMetadata.FormCode(form.getSystemCode(), form.getSchemaVersion(), form.getValue()),
                hash, source.getHasAttachment(), source.getIsSelfInvoicing(),
                source.getInvoicingMode() == null ? null : source.getInvoicingMode().getValue());
    }

    /** Double z SDK → BigDecimal przez najkrótszy zapis dziesiętny; NaN/∞ to błąd kontraktu. */
    private static BigDecimal decimal(Double value) {
        if (value == null) {
            return null;
        }
        if (value.isNaN() || value.isInfinite()) {
            throw corrupt("AMOUNT");
        }
        return BigDecimal.valueOf(value);
    }

    private static OffsetDateTime utc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    static boolean isSha256Base64(String value) {
        if (value == null || value.length() != 44) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(value).length == 32;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static InboundFailure corrupt(String detail) {
        return new InboundFailure(CORRUPT_RESPONSE, 0, null, null, "METADATA_" + detail);
    }
}
