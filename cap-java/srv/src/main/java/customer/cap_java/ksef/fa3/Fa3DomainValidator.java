package customer.cap_java.ksef.fa3;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import customer.cap_java.ksef.fa3.Fa3Invoice.Address;
import customer.cap_java.ksef.fa3.Fa3Invoice.Line;
import customer.cap_java.ksef.fa3.Fa3Invoice.VatRate;
import customer.cap_java.ksef.fa3.Fa3Invoice.VatRateTotal;

/**
 * Reguły domenowe, których XSD FA(3) nie sprawdza: arytmetyka wierszy i sum, suma kontrolna NIP,
 * zakres obsługiwanego scenariusza (krajowa faktura VAT w PLN) i postać tekstu zgodna z xsd:token.
 * Długości, formaty dat, wzorce kwot i słowniki pozostają po stronie {@link Fa3SchemaValidator}.
 * Zwraca wszystkie naruszenia naraz; pusta lista oznacza wyłącznie zgodność z tymi regułami.
 */
public class Fa3DomainValidator {
    static final int MAX_LINES = 10_000;
    private static final int[] NIP_WEIGHTS = { 6, 5, 7, 2, 3, 4, 5, 6, 7 };
    // xsd:token zwija białe znaki; tekst, który parser by zmienił, odrzucamy, aby KSeF widział to samo co my.
    private static final Pattern TOKEN_NORMALIZED = Pattern.compile("[^\\s](?:[^\\s]| (?! ))*[^\\s]|[^\\s]");
    private static final Pattern XML_FORBIDDEN = Pattern.compile("[\\x00-\\x1F\\x7F\\uFFFE\\uFFFF]");

    public List<Fa3Violation> validate(Fa3Invoice invoice) {
        var v = new ArrayList<Fa3Violation>();
        if (invoice == null) {
            v.add(new Fa3Violation("REQUIRED", "invoice", null));
            return v;
        }
        required(v, "createdAt", invoice.createdAt());
        optionalText(v, "systemInfo", invoice.systemInfo());
        validateSeller(v, invoice.seller());
        validateBuyer(v, invoice.buyer());
        if (required(v, "currency", invoice.currency()) && !"PLN".equals(invoice.currency())) {
            // Faktura w walucie obcej wymaga m.in. P_14_xW i kursu — poza zakresem minimalnego komponentu.
            v.add(new Fa3Violation("CURRENCY_UNSUPPORTED", "currency", invoice.currency()));
        }
        required(v, "issueDate", invoice.issueDate());
        optionalText(v, "issuePlace", invoice.issuePlace());
        requiredText(v, "invoiceNumber", invoice.invoiceNumber());
        if (invoice.saleDate() != null && invoice.issueDate() != null
                && invoice.saleDate().equals(invoice.issueDate())) {
            // P_6 podaje się, gdy data dostawy różni się od daty wystawienia; równa data to niejednoznaczne dane.
            v.add(new Fa3Violation("SALE_DATE_EQUALS_ISSUE_DATE", "saleDate", null));
        }
        Map<VatRate, BigDecimal> lineNetByRate = validateLines(v, invoice.lines());
        validateTotals(v, invoice, lineNetByRate);
        return v;
    }

    /** Wariant rzucający wyjątek — używany przez {@link Fa3InvoiceXmlService}. */
    public void requireValid(Fa3Invoice invoice) {
        var violations = validate(invoice);
        if (!violations.isEmpty()) {
            throw new Fa3ValidationException(violations);
        }
    }

    /** Format NIP (10 cyfr) i suma kontrolna wag 6,5,7,2,3,4,5,6,7 mod 11. */
    public static boolean isValidNip(String nip) {
        if (nip == null || !nip.matches("[0-9]{10}")) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < NIP_WEIGHTS.length; i++) {
            sum += Character.digit(nip.charAt(i), 10) * NIP_WEIGHTS[i];
        }
        int checksum = sum % 11;
        return checksum != 10 && checksum == Character.digit(nip.charAt(9), 10);
    }

    private void validateSeller(List<Fa3Violation> v, Fa3Invoice.Seller seller) {
        if (!required(v, "seller", seller)) {
            return;
        }
        nip(v, "seller.nip", seller.nip());
        requiredText(v, "seller.name", seller.name());
        if (required(v, "seller.address", seller.address())) {
            address(v, "seller.address", seller.address());
        }
    }

    private void validateBuyer(List<Fa3Violation> v, Fa3Invoice.Buyer buyer) {
        if (!required(v, "buyer", buyer)) {
            return;
        }
        nip(v, "buyer.nip", buyer.nip());
        requiredText(v, "buyer.name", buyer.name());
        if (buyer.address() != null) {
            address(v, "buyer.address", buyer.address());
        }
        required(v, "buyer.jstSubordinateUnit", buyer.jstSubordinateUnit());
        required(v, "buyer.vatGroupMember", buyer.vatGroupMember());
    }

    private void address(List<Fa3Violation> v, String path, Address address) {
        requiredText(v, path + ".countryCode", address.countryCode());
        requiredText(v, path + ".line1", address.line1());
        optionalText(v, path + ".line2", address.line2());
    }

    private Map<VatRate, BigDecimal> validateLines(List<Fa3Violation> v, List<Line> lines) {
        Map<VatRate, BigDecimal> netByRate = new EnumMap<>(VatRate.class);
        if (lines == null || lines.isEmpty()) {
            v.add(new Fa3Violation("LINES_EMPTY", "lines", null));
            return netByRate;
        }
        if (lines.size() > MAX_LINES) {
            // maxOccurs FaWiersz z XSD; walidator XSD go nie egzekwuje (zob. Fa3SchemaValidator).
            v.add(new Fa3Violation("LINES_TOO_MANY", "lines", "max " + MAX_LINES));
            return netByRate;
        }
        for (int i = 0; i < lines.size(); i++) {
            String p = "lines[" + i + "]";
            Line line = lines.get(i);
            if (!required(v, p, line)) {
                continue;
            }
            optionalText(v, p + ".lineId", line.lineId());
            requiredText(v, p + ".description", line.description());
            requiredText(v, p + ".unit", line.unit());
            boolean quantity = positive(v, p + ".quantity", line.quantity(), 6);
            boolean price = nonNegative(v, p + ".unitNetPrice", line.unitNetPrice(), 8);
            boolean net = nonNegative(v, p + ".netAmount", line.netAmount(), 2);
            required(v, p + ".rate", line.rate());
            if (quantity && price && net) {
                BigDecimal expected = line.quantity().multiply(line.unitNetPrice()).setScale(2, RoundingMode.HALF_UP);
                if (expected.compareTo(line.netAmount()) != 0) {
                    v.add(new Fa3Violation("LINE_NET_MISMATCH", p + ".netAmount",
                            "expected " + expected.toPlainString() + ", got " + line.netAmount().toPlainString()));
                }
            }
            if (net && line.rate() != null) {
                netByRate.merge(line.rate(), line.netAmount(), BigDecimal::add);
            }
        }
        return netByRate;
    }

    private void validateTotals(List<Fa3Violation> v, Fa3Invoice invoice, Map<VatRate, BigDecimal> lineNetByRate) {
        List<VatRateTotal> totals = invoice.vatTotals();
        if (totals == null || totals.isEmpty()) {
            v.add(new Fa3Violation("VAT_TOTALS_EMPTY", "vatTotals", null));
            return;
        }
        Map<VatRate, VatRateTotal> byRate = new EnumMap<>(VatRate.class);
        BigDecimal gross = BigDecimal.ZERO;
        boolean grossComputable = true;
        for (int i = 0; i < totals.size(); i++) {
            String p = "vatTotals[" + i + "]";
            VatRateTotal total = totals.get(i);
            if (!required(v, p, total) || !required(v, p + ".rate", total.rate())) {
                grossComputable = false;
                continue;
            }
            if (byRate.putIfAbsent(total.rate(), total) != null) {
                v.add(new Fa3Violation("VAT_TOTAL_DUPLICATE", p + ".rate", total.rate().xmlCode()));
                continue;
            }
            boolean net = nonNegative(v, p + ".netAmount", total.netAmount(), 2);
            boolean vat = nonNegative(v, p + ".vatAmount", total.vatAmount(), 2);
            if (!net || !vat) {
                grossComputable = false;
                continue;
            }
            BigDecimal linesNet = lineNetByRate.getOrDefault(total.rate(), BigDecimal.ZERO);
            if (linesNet.compareTo(total.netAmount()) != 0) {
                v.add(new Fa3Violation("VAT_NET_MISMATCH", p + ".netAmount",
                        "lines sum " + linesNet.toPlainString() + ", declared " + total.netAmount().toPlainString()));
            }
            // Podatek liczony od sumy netto stawki, zaokrąglony do grosza (pół grosza w górę).
            BigDecimal expectedVat = total.netAmount().multiply(total.rate().percent())
                    .divide(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
            if (expectedVat.compareTo(total.vatAmount()) != 0) {
                v.add(new Fa3Violation("VAT_AMOUNT_MISMATCH", p + ".vatAmount",
                        "expected " + expectedVat.toPlainString() + ", got " + total.vatAmount().toPlainString()));
            }
            gross = gross.add(total.netAmount()).add(total.vatAmount());
        }
        for (VatRate rate : lineNetByRate.keySet()) {
            if (!byRate.containsKey(rate)) {
                v.add(new Fa3Violation("VAT_TOTAL_MISSING", "vatTotals", rate.xmlCode()));
            }
        }
        if (nonNegative(v, "grossTotal", invoice.grossTotal(), 2) && grossComputable
                && gross.compareTo(invoice.grossTotal()) != 0) {
            v.add(new Fa3Violation("GROSS_MISMATCH", "grossTotal",
                    "expected " + gross.toPlainString() + ", got " + invoice.grossTotal().toPlainString()));
        }
    }

    private static void nip(List<Fa3Violation> v, String path, String nip) {
        if (required(v, path, nip) && !isValidNip(nip)) {
            v.add(new Fa3Violation("NIP_INVALID", path, null));
        }
    }

    private static boolean required(List<Fa3Violation> v, String path, Object value) {
        if (value == null) {
            v.add(new Fa3Violation("REQUIRED", path, null));
            return false;
        }
        return true;
    }

    private static void requiredText(List<Fa3Violation> v, String path, String value) {
        if (value == null || value.isBlank()) {
            v.add(new Fa3Violation("REQUIRED", path, null));
            return;
        }
        optionalText(v, path, value);
    }

    private static void optionalText(List<Fa3Violation> v, String path, String value) {
        if (value == null) {
            return;
        }
        if (XML_FORBIDDEN.matcher(value).find()) {
            v.add(new Fa3Violation("TEXT_CONTROL_CHARACTER", path, null));
        } else if (!TOKEN_NORMALIZED.matcher(value).matches()) {
            v.add(new Fa3Violation("TEXT_NOT_NORMALIZED", path, "leading/trailing or repeated whitespace"));
        }
    }

    private static boolean positive(List<Fa3Violation> v, String path, BigDecimal value, int maxScale) {
        if (!nonNegative(v, path, value, maxScale)) {
            return false;
        }
        if (value.signum() == 0) {
            v.add(new Fa3Violation("AMOUNT_NOT_POSITIVE", path, null));
            return false;
        }
        return true;
    }

    private static boolean nonNegative(List<Fa3Violation> v, String path, BigDecimal value, int maxScale) {
        if (!required(v, path, value)) {
            return false;
        }
        if (value.signum() < 0) {
            v.add(new Fa3Violation("AMOUNT_NEGATIVE", path, value.toPlainString()));
            return false;
        }
        if (value.stripTrailingZeros().scale() > maxScale) {
            v.add(new Fa3Violation("AMOUNT_SCALE", path, "max " + maxScale + " decimal places"));
            return false;
        }
        return true;
    }
}
