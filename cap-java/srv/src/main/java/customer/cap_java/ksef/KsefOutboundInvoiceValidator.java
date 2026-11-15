package customer.cap_java.ksef;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;
import customer.cap_java.sap.SapInvoiceSnapshot;

/** Kontrole SAP pomijane przez mapper FA(3); bez zapisu, korekt kwot ani wywołań HTTP. */
public final class KsefOutboundInvoiceValidator {
    private KsefOutboundInvoiceValidator() { }

    /** NEW/STANDARD/PLN i pełne dzieci muszą być poprawne przed rezerwacją. Domena/XSD sprawdzane osobno. */
    public static void validate(UUID expectedId, SapInvoiceSnapshot sap) {
        var h = sap.header();
        require(expectedId.equals(uuid(h.get("InvoiceUUID"))), "KSEF_INVOICE_UUID_MISMATCH");
        require("NEW".equals(h.get("InvoiceStatus")), "KSEF_INVOICE_STATUS_INVALID");
        require("OUTBOUND".equals(h.get("Direction")) && "STANDARD".equals(h.get("InvoiceKind")), "KSEF_VARIANT_UNSUPPORTED");
        require("PLN".equals(h.get("CurrencyCode")), "KSEF_CURRENCY_UNSUPPORTED");
        for (String field : new String[] { "KsefNumber", "KsefEnvironment", "KsefRecordedAt", "KsefWriteRequestId" }) {
            require(h.get(field) == null || h.get(field).toString().isBlank(), "KSEF_SAP_RESULT_PRESENT");
        }
        try { KsefInvoiceSnapshotRegistry.headerVersion(h); }
        catch (RuntimeException invalid) { throw KsefFailure.invalid("KSEF_HEADER_VERSION_MISSING"); }
        require(!sap.items().isEmpty() && sap.items().size() <= 10000, "KSEF_ITEMS_INVALID");
        var ids = new HashSet<UUID>();
        var numbers = new HashSet<Integer>();
        BigDecimal net = BigDecimal.ZERO, vat = BigDecimal.ZERO, gross = BigDecimal.ZERO;
        for (var item : sap.items()) {
            require(expectedId.equals(uuid(item.get("InvoiceUUID"))), "KSEF_ITEM_SCOPE_INVALID");
            require(ids.add(uuid(item.get("ItemUUID"))), "KSEF_ITEM_DUPLICATE");
            try {
                String number = String.valueOf(item.get("ItemNo"));
                require(number.matches("[0-9]{1,6}") && Integer.parseInt(number) > 0
                        && numbers.add(Integer.parseInt(number)), "KSEF_ITEM_NUMBER_INVALID");
                Instant.parse(String.valueOf(item.get("LocalLastChangedAt")));
            } catch (KsefFailure safe) { throw safe; }
            catch (RuntimeException invalid) { throw KsefFailure.invalid("KSEF_ITEM_VERSION_OR_NUMBER_INVALID"); }
            require("PLN".equals(item.get("CurrencyCode")), "KSEF_ITEM_CURRENCY_INVALID");
            require(java.util.Set.of("EA", "PC").contains(String.valueOf(item.get("UnitCode"))), "KSEF_ITEM_UNIT_UNSUPPORTED");
            BigDecimal rate = amount(item, "TaxRate", 2);
            require(java.util.Set.of("5", "8", "23").contains(rate.stripTrailingZeros().toPlainString())
                    && ("VAT" + rate.stripTrailingZeros().toPlainString()).equals(item.get("TaxCategory")), "KSEF_ITEM_VAT_UNSUPPORTED");
            BigDecimal quantity = amount(item, "Quantity", 6), price = amount(item, "UnitPrice", 8);
            BigDecimal n = amount(item, "NetAmount", 2), t = amount(item, "TaxAmount", 2), g = amount(item, "GrossAmount", 2);
            // Jawna polityka: HALF_UP do 2 miejsc, bez zmiany źródłowych kwot. VAT per pozycja i per stawka muszą być zgodne.
            require(quantity.signum() > 0 && quantity.multiply(price).setScale(2, RoundingMode.HALF_UP).compareTo(n) == 0,
                    "KSEF_ITEM_NET_INVALID");
            require(n.multiply(rate).movePointLeft(2).setScale(2, RoundingMode.HALF_UP).compareTo(t) == 0,
                    "KSEF_ITEM_TAX_INVALID");
            require(n.add(t).compareTo(g) == 0, "KSEF_ITEM_GROSS_INVALID");
            net = net.add(n); vat = vat.add(t); gross = gross.add(g);
        }
        require(net.compareTo(amount(h, "NetAmount", 2)) == 0 && vat.compareTo(amount(h, "TaxAmount", 2)) == 0
                && gross.compareTo(amount(h, "GrossAmount", 2)) == 0, "KSEF_HEADER_TOTALS_INVALID");
        var refs = new HashSet<UUID>();
        for (var ref : sap.references()) {
            require(expectedId.equals(uuid(ref.get("InvoiceUUID"))) && refs.add(uuid(ref.get("RefUUID"))), "KSEF_REFERENCE_SCOPE_INVALID");
        }
    }

    private static UUID uuid(Object value) {
        try {
            String text = String.valueOf(value);
            UUID id = UUID.fromString(text);
            require(id.toString().equalsIgnoreCase(text) && !id.equals(new UUID(0, 0)), "KSEF_UUID_INVALID");
            return id;
        } catch (IllegalArgumentException invalid) { throw KsefFailure.invalid("KSEF_UUID_INVALID"); }
    }

    private static BigDecimal amount(Map<String, Object> row, String field, int scale) {
        try {
            BigDecimal value = new BigDecimal(String.valueOf(row.get(field)));
            require(value.signum() >= 0 && value.scale() <= scale, "KSEF_AMOUNT_INVALID");
            return value;
        } catch (NumberFormatException invalid) { throw KsefFailure.invalid("KSEF_AMOUNT_INVALID"); }
    }

    private static void require(boolean valid, String code) {
        if (!valid) { throw KsefFailure.invalid(code); }
    }
}
