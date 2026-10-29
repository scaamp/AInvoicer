package customer.cap_java.ksef.fa3;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import customer.cap_java.ksef.fa3.Fa3Invoice.Line;
import customer.cap_java.ksef.fa3.Fa3Invoice.Seller;
import customer.cap_java.ksef.fa3.Fa3Invoice.VatRate;
import customer.cap_java.ksef.fa3.Fa3Invoice.VatRateTotal;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;

/**
 * Generator + walidacja FA(3) na oficjalnym XSD (offline, bez KSeF). Testy rozdzielają, co sprawdza XSD
 * (struktura, namespace, formaty), a co wyłącznie walidacja domenowa (arytmetyka, suma kontrolna NIP).
 */
class Fa3InvoiceXmlServiceTest {
    // Schemat kompilowany raz na klasę testową (jak singleton Spring w aplikacji).
    private static final Fa3InvoiceXmlService SERVICE = new Fa3InvoiceXmlService();
    private static final Fa3SchemaValidator XSD = new Fa3SchemaValidator();

    // ---- poprawny dokument -------------------------------------------------------------------------

    @Test
    void validSalesInvoicePassesOfficialXsd() throws Exception {
        Fa3Document doc = SERVICE.prepare(Fa3Fixtures.validInvoice());

        assertEquals(List.of(), XSD.validate(doc.xml()));
        Document dom = parse(doc.xml());
        assertEquals(Fa3XmlWriter.NAMESPACE, dom.getDocumentElement().getNamespaceURI());
        assertEquals("Faktura", dom.getDocumentElement().getLocalName());
        assertEquals("FA (3)", dom.getElementsByTagNameNS(Fa3XmlWriter.NAMESPACE, "KodFormularza").item(0)
                .getAttributes().getNamedItem("kodSystemowy").getNodeValue());
        assertEquals("2400.00", text(dom, "P_13_1"));
        assertEquals("552.00", text(dom, "P_14_1"));
        assertEquals("49.99", text(dom, "P_13_3"));
        assertEquals("2.50", text(dom, "P_14_3"));
        assertEquals("3004.49", text(dom, "P_15"));
        assertEquals("VAT", text(dom, "RodzajFaktury"));
        assertEquals(3, dom.getElementsByTagNameNS(Fa3XmlWriter.NAMESPACE, "FaWiersz").getLength());
        // Kanoniczny zapis liczb: ilość bez zbędnych zer, cena z min. 2 miejscami.
        assertEquals("3", dom.getElementsByTagNameNS(Fa3XmlWriter.NAMESPACE, "P_8B").item(1).getTextContent());
        assertEquals("500.00", dom.getElementsByTagNameNS(Fa3XmlWriter.NAMESPACE, "P_9A").item(1).getTextContent());
    }

    @Test
    void polishCharactersAndXmlSpecialCharactersRoundTripAsUtf8() throws Exception {
        byte[] xml = SERVICE.prepare(Fa3Fixtures.validInvoice()).xml();
        String head = new String(xml, 0, 38, StandardCharsets.UTF_8);

        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>", head);
        assertNotEquals((byte) 0xEF, xml[0], "bez BOM");
        // „Łódź” zapisane jako surowe bajty UTF-8, nie jako encje znakowe.
        assertTrue(contains(xml, "90-001 Łódź".getBytes(StandardCharsets.UTF_8)));
        assertFalse(new String(xml, StandardCharsets.UTF_8).contains("&#"));
        Document dom = parse(xml);
        assertEquals("Przedsiębiorstwo Testowe Żółć Sp. z o.o.", text(dom, "Nazwa"));
        assertEquals("Pakiet serwisowy R&D <test> „premium”",
                dom.getElementsByTagNameNS(Fa3XmlWriter.NAMESPACE, "P_7").item(1).getTextContent());
    }

    // ---- determinizm i SHA-256 ----------------------------------------------------------------------

    @Test
    void identicalInputProducesIdenticalBytes() {
        Fa3Document first = SERVICE.prepare(Fa3Fixtures.validInvoice());
        Fa3Document second = new Fa3InvoiceXmlService().prepare(Fa3Fixtures.validInvoice());

        assertArrayEquals(first.xml(), second.xml());
        assertEquals(first.sha256Hex(), second.sha256Hex());
        assertEquals(first, second);
        Fa3Document otherTime = SERVICE.prepare(Fa3Fixtures.invoice(Instant.parse("2026-10-29T10:00:01Z"),
                "FV/2026/10/0001", new BigDecimal("3004.49")));
        assertNotEquals(first.sha256Hex(), otherTime.sha256Hex());
    }

    @Test
    void sha256CoversExactXmlBytes() throws Exception {
        Fa3Document doc = SERVICE.prepare(Fa3Fixtures.validInvoice());
        byte[] xml = doc.xml();

        String independent = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(xml));
        assertEquals(independent, doc.sha256Hex());
        assertEquals(KsefInvoiceSnapshotRegistry.sha256Hex(xml), doc.sha256Hex());
        assertEquals(xml.length, doc.byteLength());
        assertTrue(doc.sha256Hex().matches("[0-9a-f]{64}"));
        // Zmiana jednego bajtu (np. dodatkowa spacja) zmienia skrót, choć XML pozostaje poprawny.
        byte[] changed = (new String(xml, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8);
        assertEquals(List.of(), XSD.validate(changed));
        assertNotEquals(doc.sha256Hex(), KsefInvoiceSnapshotRegistry.sha256Hex(changed));
        // Dokument jest niemutowalny: modyfikacja zwróconej kopii nie zmienia bajtów ani skrótu.
        xml[0] = 'X';
        assertEquals(independent, KsefInvoiceSnapshotRegistry.sha256Hex(doc.xml()));
    }

    /**
     * Przypina dokładne bajty fixture: każda zmiana serializacji (kolejność, wcięcia, format liczb) zmienia
     * skrót i wymaga świadomej aktualizacji tej wartości oraz dokumentacji. Próbka trafia do target/ do wglądu.
     */
    @Test
    void goldenSampleHashIsStable() throws Exception {
        Fa3Document doc = SERVICE.prepare(Fa3Fixtures.validInvoice());
        java.nio.file.Files.write(java.nio.file.Path.of("target", "fa3-sample.xml"), doc.xml());

        assertEquals(GOLDEN_SHA256, doc.sha256Hex());
        assertEquals(GOLDEN_BYTES, doc.byteLength());
    }

    static final String GOLDEN_SHA256 = "24fc8a1e26805ce08c32aed376fa7ef801d59c80e19af1718c551ab6af339afd";
    static final int GOLDEN_BYTES = 2463;

    @Test
    void verifyKeepsBytesAndHashUnchanged() {
        Fa3Document prepared = SERVICE.prepare(Fa3Fixtures.validInvoice());
        Fa3Document verified = SERVICE.verify(prepared.xml());

        assertArrayEquals(prepared.xml(), verified.xml());
        assertEquals(prepared.sha256Hex(), verified.sha256Hex());
        assertEquals("FA(3)", verified.schemaVersion());
    }

    // ---- błędy wykrywane przez XSD -------------------------------------------------------------------

    @Test
    void xsdRejectsMissingMandatoryElement() {
        String xml = validXml().replaceFirst("\\s*<P_2>[^<]*</P_2>", "");

        var violations = XSD.validate(utf8(xml));
        assertFalse(violations.isEmpty());
        assertEquals("XSD_INVALID", violations.get(0).code());
        assertTrue(violations.get(0).detail().contains("cvc-complex-type.2.4"), violations.toString());
    }

    @Test
    void xsdRejectsWrongNamespace() {
        // Namespace FA(2) — starszy wzór nie może przejść jako FA(3).
        String xml = validXml().replace(Fa3XmlWriter.NAMESPACE, "http://crd.gov.pl/wzor/2023/06/29/12648/");

        var violations = XSD.validate(utf8(xml));
        assertEquals("XSD_INVALID", violations.get(0).code());
        assertTrue(violations.get(0).detail().contains("cvc-elt.1"), violations.toString());
    }

    @Test
    void xsdRejectsMissingNamespace() {
        String xml = validXml().replace(" xmlns=\"" + Fa3XmlWriter.NAMESPACE + "\"", "");

        assertFalse(XSD.validate(utf8(xml)).isEmpty());
    }

    @Test
    void xsdRejectsInvalidDates() {
        assertXsdInvalid(validXml().replace("<P_1>2026-10-29</P_1>", "<P_1>2026-13-45</P_1>"));
        assertXsdInvalid(validXml().replace("<P_1>2026-10-29</P_1>", "<P_1>29.10.2026</P_1>"));
        // DataWytworzeniaFa przed 2025-09-01 jest wykluczona przez XSD (domena tego nie sprawdza).
        var e = assertThrows(Fa3ValidationException.class, () -> SERVICE.prepare(Fa3Fixtures.invoice(
                Instant.parse("2025-08-31T23:59:59Z"), "FV/1", new BigDecimal("3004.49"))));
        assertTrue(e.has("XSD_INVALID"));
    }

    @Test
    void xsdRejectsInvalidAmountFormats() {
        assertXsdInvalid(validXml().replace("<P_15>3004.49</P_15>", "<P_15>3004,49</P_15>"));
        assertXsdInvalid(validXml().replace("<P_15>3004.49</P_15>", "<P_15>3004.499</P_15>"));
        assertXsdInvalid(validXml().replace("<P_15>3004.49</P_15>", "<P_15>1E3</P_15>"));
        assertXsdInvalid(validXml().replace("<P_12>23</P_12>", "<P_12>24</P_12>"));
        assertXsdInvalid(validXml().replace("<KodWaluty>PLN</KodWaluty>", "<KodWaluty>ZZZ</KodWaluty>"));
    }

    @Test
    void xsdRejectsDoctypeAndExternalEntities() {
        String xml = validXml().replace("<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE Faktura [<!ENTITY x SYSTEM \"http://127.0.0.1:9/x\">]>");

        var violations = XSD.validate(utf8(xml));
        assertEquals("XSD_INVALID", violations.get(0).code());
        assertTrue(violations.get(0).detail().contains("DOCTYPE"), violations.toString());
    }

    @Test
    void xsdRejectsNonXmlInput() {
        assertXsdInvalid("to nie jest XML");
        assertEquals("REQUIRED", XSD.validate(new byte[0]).get(0).code());
        assertThrows(Fa3ValidationException.class, () -> SERVICE.verify(utf8("<Faktura/>")));
    }

    // ---- granice XSD: rzeczy, których schemat NIE sprawdza ------------------------------------------

    @Test
    void xsdDoesNotCheckArithmeticOrNipChecksum() {
        // Te dokumenty są zgodne z XSD, choć biznesowo błędne — dlatego istnieje Fa3DomainValidator.
        assertEquals(List.of(), XSD.validate(utf8(validXml().replace("<P_15>3004.49</P_15>", "<P_15>9999.99</P_15>"))));
        assertEquals(List.of(), XSD.validate(utf8(validXml().replace("<P_14_1>552.00</P_14_1>", "<P_14_1>1.00</P_14_1>"))));
        assertEquals(List.of(), XSD.validate(utf8(validXml().replace(Fa3Fixtures.SELLER_NIP, "5260002008"))));
    }

    // ---- walidacja domenowa (przed wygenerowaniem XML) ----------------------------------------------

    @Test
    void domainRejectsMissingMandatoryField() {
        var e = assertThrows(Fa3ValidationException.class,
                () -> SERVICE.prepare(Fa3Fixtures.withNumber(Fa3Fixtures.validInvoice(), null)));
        assertEquals(List.of(new Fa3Violation("REQUIRED", "invoiceNumber", null)), e.violations());

        var noAddress = Fa3Fixtures.withSeller(Fa3Fixtures.validInvoice(),
                new Seller(Fa3Fixtures.SELLER_NIP, "Sprzedawca", null));
        assertTrue(assertThrows(Fa3ValidationException.class, () -> SERVICE.prepare(noAddress)).violations()
                .contains(new Fa3Violation("REQUIRED", "seller.address", null)));
    }

    @Test
    void domainRejectsGrossTotalMismatch() {
        var e = assertThrows(Fa3ValidationException.class, () -> SERVICE.prepare(Fa3Fixtures.invoice(
                Fa3Fixtures.CREATED_AT, "FV/1", new BigDecimal("3004.50"))));

        assertEquals(1, e.violations().size());
        assertEquals("GROSS_MISMATCH", e.violations().get(0).code());
        assertEquals("expected 3004.49, got 3004.50", e.violations().get(0).detail());
    }

    @Test
    void domainRejectsVatRateTotalsInconsistentWithLines() {
        var base = Fa3Fixtures.validInvoice();
        // Netto stawki niezgodne z sumą wierszy oraz VAT niezgodny z 23% od netto.
        var wrongNet = Fa3Fixtures.withTotals(base, List.of(
                new VatRateTotal(VatRate.RATE_23, new BigDecimal("2300.00"), new BigDecimal("529.00")),
                new VatRateTotal(VatRate.RATE_5, new BigDecimal("49.99"), new BigDecimal("2.50"))));
        assertTrue(domainCodes(wrongNet).contains("VAT_NET_MISMATCH"));

        var wrongVat = Fa3Fixtures.withTotals(base, List.of(
                new VatRateTotal(VatRate.RATE_23, new BigDecimal("2400.00"), new BigDecimal("551.99")),
                new VatRateTotal(VatRate.RATE_5, new BigDecimal("49.99"), new BigDecimal("2.50"))));
        assertTrue(domainCodes(wrongVat).contains("VAT_AMOUNT_MISMATCH"));

        var missingRate = Fa3Fixtures.withTotals(base, List.of(
                new VatRateTotal(VatRate.RATE_23, new BigDecimal("2400.00"), new BigDecimal("552.00"))));
        assertTrue(domainCodes(missingRate).contains("VAT_TOTAL_MISSING"));
    }

    @Test
    void domainRejectsLineNetMismatchAndInvalidAmounts() {
        var base = Fa3Fixtures.validInvoice();
        var wrongNet = Fa3Fixtures.withLine(base, 0, new Line(null, "Usługa", "szt.", new BigDecimal("2"),
                new BigDecimal("450.00"), new BigDecimal("901.00"), VatRate.RATE_23));
        assertTrue(domainCodes(wrongNet).contains("LINE_NET_MISMATCH"));

        var threeDecimals = Fa3Fixtures.withLine(base, 0, new Line(null, "Usługa", "szt.", new BigDecimal("2"),
                new BigDecimal("450.00"), new BigDecimal("900.001"), VatRate.RATE_23));
        assertTrue(domainCodes(threeDecimals).contains("AMOUNT_SCALE"));

        var negative = Fa3Fixtures.withLine(base, 0, new Line(null, "Usługa", "szt.", new BigDecimal("-2"),
                new BigDecimal("450.00"), new BigDecimal("900.00"), VatRate.RATE_23));
        assertTrue(domainCodes(negative).contains("AMOUNT_NEGATIVE"));
    }

    @Test
    void domainRejectsInvalidNipChecksumAndUnsupportedScope() {
        var badNip = Fa3Fixtures.withSeller(Fa3Fixtures.validInvoice(), new Seller("5260002008", "Sprzedawca",
                Fa3Fixtures.validInvoice().seller().address()));
        assertEquals(List.of("NIP_INVALID"), domainCodes(badNip));
        assertEquals(List.of("CURRENCY_UNSUPPORTED"), domainCodes(Fa3Fixtures.withCurrency(Fa3Fixtures.validInvoice(), "EUR")));
    }

    @Test
    void domainRejectsTextThatXsdTokenWouldSilentlyChange() {
        assertEquals(List.of("TEXT_NOT_NORMALIZED"),
                domainCodes(Fa3Fixtures.withNumber(Fa3Fixtures.validInvoice(), "FV/1  /2026")));
        assertEquals(List.of("TEXT_NOT_NORMALIZED"),
                domainCodes(Fa3Fixtures.withNumber(Fa3Fixtures.validInvoice(), " FV/1")));
        assertEquals(List.of("TEXT_CONTROL_CHARACTER"),
                domainCodes(Fa3Fixtures.withNumber(Fa3Fixtures.validInvoice(), "FV\n1")));
    }

    @Test
    void validatorAcceptsBoundaryValues() {
        assertTrue(Fa3DomainValidator.isValidNip("1234567802"));
        assertFalse(Fa3DomainValidator.isValidNip("1234567803"));
        assertFalse(Fa3DomainValidator.isValidNip("123456780"));
        assertEquals(List.of(), new Fa3DomainValidator().validate(Fa3Fixtures.validInvoice()));
    }

    // ---- pomocnicze -----------------------------------------------------------------------------------

    private static List<String> domainCodes(Fa3Invoice invoice) {
        return new Fa3DomainValidator().validate(invoice).stream().map(Fa3Violation::code).toList();
    }

    private static void assertXsdInvalid(String xml) {
        var violations = XSD.validate(utf8(xml));
        assertFalse(violations.isEmpty(), "expected XSD error for: " + xml.substring(0, Math.min(60, xml.length())));
        assertTrue(violations.stream().allMatch(v -> v.code().equals("XSD_INVALID")));
    }

    private static String validXml() {
        return new String(SERVICE.prepare(Fa3Fixtures.validInvoice()).xml(), StandardCharsets.UTF_8);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static Document parse(byte[] xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    static String text(Document dom, String localName) {
        return dom.getElementsByTagNameNS(Fa3XmlWriter.NAMESPACE, localName).item(0).getTextContent();
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
