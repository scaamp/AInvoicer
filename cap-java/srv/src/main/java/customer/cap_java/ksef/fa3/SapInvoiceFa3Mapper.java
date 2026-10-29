package customer.cap_java.ksef.fa3;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import customer.cap_java.ksef.fa3.Fa3Invoice.Address;
import customer.cap_java.ksef.fa3.Fa3Invoice.Line;
import customer.cap_java.ksef.fa3.Fa3Invoice.VatRate;
import customer.cap_java.ksef.fa3.Fa3Invoice.VatRateTotal;
import customer.cap_java.sap.SapInvoiceSnapshot;

/**
 * Adapter {@link SapInvoiceSnapshot} (RAP ZC_AINV_INV_H/I) → {@link Fa3Invoice} dla syntetycznych faktur
 * OUTBOUND z H01. Model SAP nie zawiera wszystkich danych FA(3): adresy stron, flagi JST/GV i znacznik
 * czasu wytworzenia dostarcza wywołujący w {@link PartyData}. Brak lub nieobsługiwana wartość SAP daje
 * naruszenie MAPPING_* — mapper niczego nie zgaduje ani nie uzupełnia domyślnie.
 *
 * <p>Wynik nie jest jeszcze zwalidowany: reguły domenowe (np. suma kontrolna NIP) i XSD sprawdza
 * {@link Fa3InvoiceXmlService#prepare(Fa3Invoice)}. Nie zmienia danych SAP.
 */
public class SapInvoiceFa3Mapper {
    /** Jednostki SAP → P_8A. Inne kody wymagają świadomego rozszerzenia mapy. */
    private static final Map<String, String> UNITS = Map.of("EA", "szt.", "PC", "szt.");
    private static final String SYSTEM_INFO = "AInvoicer";

    /**
     * Dane spoza modelu SAP wymagane przez FA(3).
     *
     * @param createdAt DataWytworzeniaFa (np. chwila przygotowania XML przez worker)
     * @param sellerAddress obowiązkowy adres Podmiot1
     * @param buyerAddress opcjonalny adres Podmiot2
     */
    public record PartyData(Instant createdAt, Address sellerAddress, Address buyerAddress,
            Boolean buyerJstSubordinateUnit, Boolean buyerVatGroupMember) {
    }

    public Fa3Invoice map(SapInvoiceSnapshot sap, PartyData party) {
        var v = new ArrayList<Fa3Violation>();
        Map<String, Object> h = sap.header();
        expect(v, h, "Direction", "OUTBOUND");
        // InvoiceKind STANDARD → RodzajFaktury VAT; korekty/zaliczki wymagają innej struktury FA(3).
        expect(v, h, "InvoiceKind", "STANDARD");
        expect(v, h, "CurrencyCode", "PLN");

        List<Map<String, Object>> items = sap.items().stream()
                .sorted(Comparator.comparing(item -> String.valueOf(item.get("ItemNo")))).toList();
        if (items.isEmpty()) {
            v.add(new Fa3Violation("MAPPING_MISSING", "SAP.Items", null));
        }
        var lines = new ArrayList<Line>();
        Map<VatRate, BigDecimal[]> sums = new EnumMap<>(VatRate.class);
        for (Map<String, Object> item : items) {
            String p = "SAP.Item[" + item.get("ItemNo") + "]";
            VatRate rate = rate(v, p, item);
            String unit = UNITS.get(String.valueOf(item.get("UnitCode")));
            if (unit == null) {
                v.add(new Fa3Violation("MAPPING_UNSUPPORTED", p + ".UnitCode", String.valueOf(item.get("UnitCode"))));
            }
            BigDecimal net = decimal(v, p + ".NetAmount", item.get("NetAmount"));
            BigDecimal tax = decimal(v, p + ".TaxAmount", item.get("TaxAmount"));
            lines.add(new Line(text(item.get("ItemUUID")), text(item.get("Description")), unit,
                    decimal(v, p + ".Quantity", item.get("Quantity")),
                    decimal(v, p + ".UnitPrice", item.get("UnitPrice")), net, rate));
            if (rate != null && net != null && tax != null) {
                BigDecimal[] sum = sums.computeIfAbsent(rate, r -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO });
                sum[0] = sum[0].add(net);
                sum[1] = sum[1].add(tax);
            }
        }
        // Podsumowanie stawki z kwot SAP pozycji; zgodność z regułą zaokrąglenia sprawdzi Fa3DomainValidator.
        List<VatRateTotal> totals = sums.entrySet().stream()
                .map(e -> new VatRateTotal(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
        BigDecimal gross = decimal(v, "SAP.Header.GrossAmount", h.get("GrossAmount"));
        checkHeaderTotal(v, "NetAmount", h.get("NetAmount"), totals.stream().map(VatRateTotal::netAmount));
        checkHeaderTotal(v, "TaxAmount", h.get("TaxAmount"), totals.stream().map(VatRateTotal::vatAmount));

        if (!v.isEmpty()) {
            throw new Fa3ValidationException(v);
        }
        return new Fa3Invoice(party.createdAt(), SYSTEM_INFO,
                new Fa3Invoice.Seller(text(h.get("SellerNip")), text(h.get("SellerName")), party.sellerAddress()),
                new Fa3Invoice.Buyer(text(h.get("BuyerNip")), text(h.get("BuyerName")), party.buyerAddress(),
                        party.buyerJstSubordinateUnit(), party.buyerVatGroupMember()),
                "PLN", date(h.get("IssueDate")), null, text(h.get("InvoiceNumber")),
                // SAP nie ma daty dostawy (P_6); PostingDate to data księgowania, nie zamiennik.
                null, lines, totals, gross);
    }

    private static VatRate rate(List<Fa3Violation> v, String path, Map<String, Object> item) {
        BigDecimal percent = decimal(v, path + ".TaxRate", item.get("TaxRate"));
        if (percent == null) {
            return null;
        }
        for (VatRate rate : VatRate.values()) {
            // TaxCategory musi potwierdzać zwykłą stawkę (np. VAT23); zwolnienia i odwrotne obciążenie są poza zakresem.
            if (rate.percent().compareTo(percent) == 0 && ("VAT" + rate.xmlCode()).equals(item.get("TaxCategory"))) {
                return rate;
            }
        }
        v.add(new Fa3Violation("MAPPING_UNSUPPORTED", path + ".TaxRate",
                percent.toPlainString() + "/" + item.get("TaxCategory")));
        return null;
    }

    private static void checkHeaderTotal(List<Fa3Violation> v, String field, Object headerValue,
            Stream<BigDecimal> parts) {
        BigDecimal header = decimal(v, "SAP.Header." + field, headerValue);
        BigDecimal sum = parts.reduce(BigDecimal.ZERO, BigDecimal::add);
        if (header != null && header.compareTo(sum) != 0) {
            v.add(new Fa3Violation("SAP_HEADER_TOTAL_MISMATCH", "SAP.Header." + field,
                    "header " + header.toPlainString() + ", items " + sum.toPlainString()));
        }
    }

    private static void expect(List<Fa3Violation> v, Map<String, Object> header, String field, String expected) {
        if (!expected.equals(header.get(field))) {
            v.add(new Fa3Violation("MAPPING_UNSUPPORTED", "SAP.Header." + field, String.valueOf(header.get(field))));
        }
    }

    private static BigDecimal decimal(List<Fa3Violation> v, String path, Object value) {
        if (value == null) {
            v.add(new Fa3Violation("MAPPING_MISSING", path, null));
            return null;
        }
        return value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static LocalDate date(Object value) {
        return value == null ? null : value instanceof LocalDate d ? d : LocalDate.parse(value.toString());
    }
}
