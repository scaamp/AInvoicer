package customer.cap_java.ksef.fa3;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import customer.cap_java.ksef.fa3.Fa3Invoice.Address;
import customer.cap_java.ksef.fa3.Fa3Invoice.Buyer;
import customer.cap_java.ksef.fa3.Fa3Invoice.Line;
import customer.cap_java.ksef.fa3.Fa3Invoice.Seller;
import customer.cap_java.ksef.fa3.Fa3Invoice.VatRate;
import customer.cap_java.ksef.fa3.Fa3Invoice.VatRateTotal;
import customer.cap_java.sap.SapInvoiceSnapshot;

/**
 * Wyłącznie syntetyczne dane testowe: NIP-y z poprawną sumą kontrolną używane już w testach CAP
 * (1234567802, 9876543210), fikcyjne nazwy i adresy. Brak danych rzeczywistych.
 */
final class Fa3Fixtures {
    static final Instant CREATED_AT = Instant.parse("2026-10-29T10:00:00Z");
    static final String SELLER_NIP = "1234567802";
    static final String BUYER_NIP = "9876543210";

    private Fa3Fixtures() {
    }

    /** Dwie stawki (23% i 5%), polskie znaki i znaki specjalne XML w opisie. */
    static Fa3Invoice validInvoice() {
        return invoice(CREATED_AT, "FV/2026/10/0001", new BigDecimal("3004.49"));
    }

    static Fa3Invoice invoice(Instant createdAt, String number, BigDecimal gross) {
        return new Fa3Invoice(createdAt, "AInvoicer",
                new Seller(SELLER_NIP, "Przedsiębiorstwo Testowe Żółć Sp. z o.o.",
                        new Address("PL", "ul. Źródlana 5", "90-001 Łódź")),
                new Buyer(BUYER_NIP, "Spółdzielnia Syntetyczna „Ćma”",
                        new Address("PL", "ul. Gęślą Jaźń 7/3", "80-001 Gdańsk"), false, false),
                "PLN", LocalDate.parse("2026-10-29"), "Łódź", number, LocalDate.parse("2026-10-28"),
                List.of(
                        new Line("9b0f6a2e-1d0e-4c55-9a51-000000000010", "Usługa wdrożeniowa — etap 1",
                                "szt.", new BigDecimal("2"), new BigDecimal("450.00"), new BigDecimal("900.00"),
                                VatRate.RATE_23),
                        new Line(null, "Pakiet serwisowy R&D <test> „premium”", "szt.", new BigDecimal("3.000"),
                                new BigDecimal("500"), new BigDecimal("1500.00"), VatRate.RATE_23),
                        new Line(null, "Książka: Źdźbło", "szt.", new BigDecimal("1"), new BigDecimal("49.99"),
                                new BigDecimal("49.99"), VatRate.RATE_5)),
                List.of(new VatRateTotal(VatRate.RATE_23, new BigDecimal("2400.00"), new BigDecimal("552.00")),
                        new VatRateTotal(VatRate.RATE_5, new BigDecimal("49.99"), new BigDecimal("2.50"))),
                gross);
    }

    /** Kopia faktury z jedną zmienioną wartością — pozwala testować pojedyncze reguły. */
    static Fa3Invoice withNumber(Fa3Invoice i, String number) {
        return new Fa3Invoice(i.createdAt(), i.systemInfo(), i.seller(), i.buyer(), i.currency(), i.issueDate(),
                i.issuePlace(), number, i.saleDate(), i.lines(), i.vatTotals(), i.grossTotal());
    }

    static Fa3Invoice withCurrency(Fa3Invoice i, String currency) {
        return new Fa3Invoice(i.createdAt(), i.systemInfo(), i.seller(), i.buyer(), currency, i.issueDate(),
                i.issuePlace(), i.invoiceNumber(), i.saleDate(), i.lines(), i.vatTotals(), i.grossTotal());
    }

    static Fa3Invoice withSeller(Fa3Invoice i, Seller seller) {
        return new Fa3Invoice(i.createdAt(), i.systemInfo(), seller, i.buyer(), i.currency(), i.issueDate(),
                i.issuePlace(), i.invoiceNumber(), i.saleDate(), i.lines(), i.vatTotals(), i.grossTotal());
    }

    static Fa3Invoice withTotals(Fa3Invoice i, List<VatRateTotal> totals) {
        return new Fa3Invoice(i.createdAt(), i.systemInfo(), i.seller(), i.buyer(), i.currency(), i.issueDate(),
                i.issuePlace(), i.invoiceNumber(), i.saleDate(), i.lines(), totals, i.grossTotal());
    }

    static Fa3Invoice withLine(Fa3Invoice i, int index, Line line) {
        var lines = new ArrayList<>(i.lines());
        lines.set(index, line);
        return new Fa3Invoice(i.createdAt(), i.systemInfo(), i.seller(), i.buyer(), i.currency(), i.issueDate(),
                i.issuePlace(), i.invoiceNumber(), i.saleDate(), lines, i.vatTotals(), i.grossTotal());
    }

    // ---- SAP: struktura jak ZC_AINV_INV_H/I z H01 (SapInvoiceRemoteAdapter) -----------------------------

    static final UUID SAP_INVOICE = UUID.fromString("5d1c3b9e-7a54-4f0e-9c3a-0000000000b1");
    static final Instant SAP_VERSION = Instant.parse("2026-10-27T08:15:30.1234567Z");

    /**
     * Odwzorowanie seeda H01 „AINV-TEST-SEED-2026-SD-BILL” (ZCL_AINV_TEST_DATA): te same kwoty, pozycje
     * i NIP-y stron. NIP-y seeda (5260002008, 5210003008) nie mają poprawnej sumy kontrolnej.
     */
    static SapInvoiceSnapshot h01SeedSdBill() {
        return sapInvoice("5260002008", "5210003008");
    }

    /** Ta sama faktura SD z syntetycznymi NIP-ami o poprawnej sumie kontrolnej — osobny fixture FA(3). */
    static SapInvoiceSnapshot syntheticSdBill() {
        return sapInvoice(SELLER_NIP, BUYER_NIP);
    }

    static SapInvoiceSnapshot sapInvoice(String sellerNip, String buyerNip) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("InvoiceUUID", SAP_INVOICE.toString());
        header.put("InvoiceNumber", "AINV-TEST-SEED-2026-SD-BILL");
        header.put("OriginProcess", "SD_BILL");
        header.put("Direction", "OUTBOUND");
        header.put("InvoiceKind", "STANDARD");
        header.put("InvoiceStatus", "NEW");
        header.put("CompanyCode", "1000");
        header.put("LocalLastChangedAt", SAP_VERSION);
        header.put("IssueDate", LocalDate.parse("2026-10-07"));
        header.put("PostingDate", LocalDate.parse("2026-10-07"));
        header.put("DueDate", LocalDate.parse("2026-11-06"));
        header.put("SellerNip", sellerNip);
        header.put("SellerName", "AInvoicer Test Seller SD");
        header.put("BuyerNip", buyerNip);
        header.put("BuyerName", "Test Odbiorca Beta Sp z o o");
        header.put("NetAmount", new BigDecimal("2400.00"));
        header.put("TaxAmount", new BigDecimal("552.00"));
        header.put("GrossAmount", new BigDecimal("2952.00"));
        header.put("CurrencyCode", "PLN");
        // Kolejność celowo odwrócona: mapper sortuje po ItemNo.
        return new SapInvoiceSnapshot(header, List.of(
                sapItem("000020", "TEST-SD-02", "Syntetyczny pakiet serwisowy", "3.000", "500.00", "1500.00",
                        "345.00", "1845.00"),
                sapItem("000010", "TEST-SD-01", "Syntetyczna usługa wdrożeniowa", "2.000", "450.00", "900.00",
                        "207.00", "1107.00")),
                List.of());
    }

    static Map<String, Object> sapItem(String no, String material, String description, String qty, String price,
            String net, String tax, String gross) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("ItemUUID", UUID.nameUUIDFromBytes(no.getBytes()).toString());
        item.put("InvoiceUUID", SAP_INVOICE.toString());
        item.put("ItemNo", no);
        item.put("MaterialId", material);
        item.put("Description", description);
        item.put("Quantity", new BigDecimal(qty));
        item.put("UnitCode", "EA");
        item.put("UnitPrice", new BigDecimal(price));
        item.put("NetAmount", new BigDecimal(net));
        item.put("TaxRate", new BigDecimal("23.00"));
        item.put("TaxCategory", "VAT23");
        item.put("TaxAmount", new BigDecimal(tax));
        item.put("GrossAmount", new BigDecimal(gross));
        item.put("CurrencyCode", "PLN");
        item.put("LocalLastChangedAt", SAP_VERSION);
        return item;
    }

    static SapInvoiceFa3Mapper.PartyData partyData() {
        return new SapInvoiceFa3Mapper.PartyData(CREATED_AT,
                new Address("PL", "ul. Testowa 1", "00-001 Warszawa"), null, false, false);
    }
}
