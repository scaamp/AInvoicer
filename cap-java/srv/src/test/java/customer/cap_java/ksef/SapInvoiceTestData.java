package customer.cap_java.ksef;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import customer.cap_java.sap.SapInvoiceSnapshot;

/** Syntetyczna faktura w kształcie RAP ZC_AINV_INV_H/I (jak SapInvoiceRemoteAdapter), z poprawnymi NIP. */
final class SapInvoiceTestData {
    static final String SELLER_NIP = "1234567802";
    static final String BUYER_NIP = "9876543210";
    static final Instant VERSION = Instant.parse("2026-10-27T08:15:30.1234567Z");

    private SapInvoiceTestData() { }

    static SapInvoiceSnapshot outbound(UUID invoiceId) {
        return invoice(invoiceId, header -> { });
    }

    static SapInvoiceSnapshot invoice(UUID invoiceId, java.util.function.Consumer<Map<String, Object>> change) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("InvoiceUUID", invoiceId.toString());
        header.put("InvoiceNumber", "AINV-CAP05-" + invoiceId.toString().substring(0, 8));
        header.put("OriginProcess", "SD_BILL");
        header.put("Direction", "OUTBOUND");
        header.put("InvoiceKind", "STANDARD");
        header.put("InvoiceStatus", "NEW");
        header.put("CompanyCode", "1000");
        header.put("LocalLastChangedAt", VERSION);
        header.put("KsefNumber", null);
        header.put("KsefEnvironment", null);
        header.put("IssueDate", LocalDate.parse("2026-10-07"));
        header.put("SellerNip", SELLER_NIP);
        header.put("SellerName", "AInvoicer Test Seller SD");
        header.put("BuyerNip", BUYER_NIP);
        header.put("BuyerName", "Test Odbiorca Beta Sp z o o");
        header.put("NetAmount", new BigDecimal("900.00"));
        header.put("TaxAmount", new BigDecimal("207.00"));
        header.put("GrossAmount", new BigDecimal("1107.00"));
        header.put("CurrencyCode", "PLN");
        change.accept(header);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("ItemUUID", UUID.nameUUIDFromBytes(invoiceId.toString().getBytes()).toString());
        item.put("InvoiceUUID", invoiceId.toString());
        item.put("ItemNo", "000010");
        item.put("MaterialId", "TEST-SD-01");
        item.put("Description", "Syntetyczna usługa wdrożeniowa");
        item.put("Quantity", new BigDecimal("2.000"));
        item.put("UnitCode", "EA");
        item.put("UnitPrice", new BigDecimal("450.00"));
        item.put("NetAmount", new BigDecimal("900.00"));
        item.put("TaxRate", new BigDecimal("23.00"));
        item.put("TaxCategory", "VAT23");
        item.put("TaxAmount", new BigDecimal("207.00"));
        item.put("GrossAmount", new BigDecimal("1107.00"));
        item.put("CurrencyCode", "PLN");
        item.put("LocalLastChangedAt", VERSION);
        return new SapInvoiceSnapshot(header, List.of(item), List.of());
    }
}
