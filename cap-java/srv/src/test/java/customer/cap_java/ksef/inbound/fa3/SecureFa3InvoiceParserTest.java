package customer.cap_java.ksef.inbound.fa3;

import static customer.cap_java.ksef.inbound.fa3.Fa3ParseException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import customer.cap_java.ksef.fa3.Fa3Invoice;
import customer.cap_java.ksef.fa3.Fa3InvoiceXmlService;
import customer.cap_java.ksef.fa3.Fa3SchemaValidator;
import customer.cap_java.ksef.fa3.Fa3XmlWriter;
import customer.cap_java.ksef.inbound.InboundTestSupport;
import customer.cap_java.ksef.inbound.InboundTestSupport.Stub;

/** Parser FA(3) INBOUND: dane syntetyczne, XML security i jawne odrzucanie nieobsługiwanych wariantów. */
class SecureFa3InvoiceParserTest {
    private static final String NS = SecureFa3InvoiceParser.FA3_NAMESPACE;
    private static byte[] valid;
    private static String validText;
    private final SecureFa3InvoiceParser parser = new SecureFa3InvoiceParser();

    @BeforeAll
    static void load() {
        valid = InboundTestSupport.resource("fa3-purchase-synthetic.xml");
        validText = new String(valid, StandardCharsets.UTF_8);
    }

    @Test
    void namespaceMatchesOutboundGeneratorAndOfficialXsd() {
        assertEquals(Fa3XmlWriter.NAMESPACE, NS);
    }

    @Test
    void syntheticFixtureIsValidAgainstOfficialXsd() {
        // Dowód, że fixture nie jest „wygodną” strukturą: przechodzi oficjalny XSD FA(3) 1-0E.
        assertEquals(List.of(), new Fa3SchemaValidator().validate(valid));
    }

    @Test
    void parsesSyntheticPurchaseInvoice() {
        Fa3InboundInvoice invoice = parser.parse(valid);

        assertEquals("FA (3)", invoice.systemCode());
        assertEquals("1-0E", invoice.schemaVersion());
        assertEquals("3", invoice.formVariant());
        assertEquals("FZ/2026/11/0001", invoice.invoiceNumber());
        assertEquals(LocalDate.parse("2026-11-02"), invoice.issueDate());
        assertEquals("PLN", invoice.currency());
        assertEquals("VAT", invoice.invoiceKind());
        assertEquals(new Fa3InboundInvoice.Party(Fa3InboundInvoice.IdentifierType.NIP, null,
                InboundTestSupport.SELLER_NIP, "Syntetyczny Sprzedawca & Wspólnicy Sp. z o.o."), invoice.seller());
        assertEquals(Fa3InboundInvoice.IdentifierType.NIP, invoice.buyer().identifierType());
        assertEquals(InboundTestSupport.CONTEXT_NIP, invoice.buyer().identifier());
        assertEquals(new BigDecimal("900.00"), invoice.netAmounts().get("P_13_1"));
        assertEquals(new BigDecimal("150.00"), invoice.netAmounts().get("P_13_2"));
        assertEquals(new BigDecimal("207.00"), invoice.vatAmounts().get("P_14_1"));
        assertEquals(new BigDecimal("12.00"), invoice.vatAmounts().get("P_14_2"));
        assertTrue(invoice.vatAmountsPln().isEmpty());
        assertEquals(new BigDecimal("1269.00"), invoice.grossAmount());
        assertEquals(2, invoice.lines().size());
        var first = invoice.lines().get(0);
        assertEquals(1, first.number());
        assertEquals("syn-line-0001", first.uuid());
        assertEquals("Usługa <wdrożeniowa> & konsultacje", first.name());
        assertEquals(new BigDecimal("2"), first.quantity());
        assertEquals(new BigDecimal("450.00"), first.unitNetPrice());
        assertEquals(new BigDecimal("900.00"), first.netValue());
        assertEquals("23", first.vatRate());
        var second = invoice.lines().get(1);
        assertEquals(new BigDecimal("1.5"), second.quantity());
        assertEquals("8", second.vatRate());
        assertNull(second.uuid());
        assertNull(second.unitGrossPrice());
    }

    @Test
    void parsesXmlProducedAndXsdValidatedByOutboundGenerator() {
        var invoice = new Fa3Invoice(Instant.parse("2026-11-02T07:00:00Z"), "AInvoicer",
                new Fa3Invoice.Seller(InboundTestSupport.SELLER_NIP, "Sprzedawca Syntetyczny",
                        new Fa3Invoice.Address("PL", "ul. Testowa 1", "00-001 Warszawa")),
                new Fa3Invoice.Buyer(InboundTestSupport.CONTEXT_NIP, "Nabywca Syntetyczny",
                        new Fa3Invoice.Address("PL", "ul. Próbna 2", "90-001 Łódź"), false, false),
                "PLN", LocalDate.parse("2026-11-02"), "Warszawa", "FV/SYN/1", LocalDate.parse("2026-11-01"),
                List.of(new Fa3Invoice.Line(null, "Usługa", "szt.", new BigDecimal("2"), new BigDecimal("100.00"),
                        new BigDecimal("200.00"), Fa3Invoice.VatRate.RATE_23)),
                List.of(new Fa3Invoice.VatRateTotal(Fa3Invoice.VatRate.RATE_23, new BigDecimal("200.00"),
                        new BigDecimal("46.00"))),
                new BigDecimal("246.00"));
        byte[] xml = new Fa3InvoiceXmlService().prepare(invoice).xml();

        Fa3InboundInvoice parsed = parser.parse(xml);
        assertEquals("FV/SYN/1", parsed.invoiceNumber());
        assertEquals(new BigDecimal("246.00"), parsed.grossAmount());
        assertEquals(new BigDecimal("46.00"), parsed.vatAmounts().get("P_14_1"));
        assertEquals(1, parsed.lines().size());
    }

    @Test
    void buyerIdentifierAlternativesFollowXsdChoice() {
        var eu = parser.parse(replace("<NIP>1111111111</NIP>", "<KodUE>DE</KodUE><NrVatUE>123456789</NrVatUE>"));
        assertEquals(Fa3InboundInvoice.IdentifierType.VAT_UE, eu.buyer().identifierType());
        assertEquals("DE", eu.buyer().countryCode());
        var none = parser.parse(replace("<NIP>1111111111</NIP>", "<BrakID>1</BrakID>"));
        assertEquals(Fa3InboundInvoice.IdentifierType.NONE, none.buyer().identifierType());
        assertNull(none.buyer().identifier());
        assertCode(INVALID_VALUE, replace("<NIP>1111111111</NIP>", "<NIP>1111111111</NIP><BrakID>1</BrakID>"));
        assertCode(MISSING_REQUIRED, replace("<NIP>1111111111</NIP>", "<KodUE>DE</KodUE>"));
    }

    @Test
    void foreignCurrencyVatInPlnIsKeptSeparately() {
        var invoice = parser.parse(validText.replace("<KodWaluty>PLN</KodWaluty>", "<KodWaluty>EUR</KodWaluty>")
                .replace("<P_14_1>207.00</P_14_1>", "<P_14_1>207.00</P_14_1><P_14_1W>880.12</P_14_1W>")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals("EUR", invoice.currency());
        assertEquals(new BigDecimal("880.12"), invoice.vatAmountsPln().get("P_14_1W"));
        assertEquals(new BigDecimal("207.00"), invoice.vatAmounts().get("P_14_1"));
    }

    // ------------------------------------------------------------------ XML security

    @Test
    void xxeWithLocalFileIsRejectedWithoutReadingTheFile(@TempDir Path dir) throws IOException {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "XXE-SECRET-MARKER-9f3");
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE Faktura [<!ENTITY xxe SYSTEM \""
                + secret.toUri() + "\">]>\n" + validText.substring(validText.indexOf("<Faktura"))
                        .replace("<SystemInfo>Syntetyczny system testowy</SystemInfo>", "<SystemInfo>&xxe;</SystemInfo>");
        var error = assertCode(DOCTYPE_FORBIDDEN, xml);
        assertFalse(error.toString().contains("XXE-SECRET-MARKER"));
    }

    @Test
    void externalDtdAndParameterEntitiesAreNeverFetched() throws IOException {
        try (var stub = new Stub().on("/", (e, call) -> Stub.respond(e, 200, "application/xml-dtd",
                "<!ENTITY x \"pwned\">".getBytes(StandardCharsets.UTF_8), java.util.Map.of()))) {
            String body = validText.substring(validText.indexOf("<Faktura"));
            assertCode(DOCTYPE_FORBIDDEN, "<?xml version=\"1.0\"?><!DOCTYPE Faktura SYSTEM \"" + stub.origin()
                    + "/evil.dtd\">" + body);
            assertCode(DOCTYPE_FORBIDDEN, "<?xml version=\"1.0\"?><!DOCTYPE Faktura [<!ENTITY % p SYSTEM \""
                    + stub.origin() + "/p.dtd\"> %p;]>" + body);
            assertTrue(stub.requests().isEmpty(), "parser nie może wykonać żadnego żądania sieciowego");
        }
    }

    @Test
    void entityExpansionBombIsRejectedQuickly() {
        String bomb = "<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol \"lol\">"
                + "<!ENTITY lol2 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">"
                + "<!ENTITY lol3 \"&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;\">"
                + "<!ENTITY lol4 \"&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;\">"
                + "<!ENTITY lol5 \"&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;\">"
                + "<!ENTITY lol6 \"&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;&lol5;\">"
                + "<!ENTITY lol7 \"&lol6;&lol6;&lol6;&lol6;&lol6;&lol6;&lol6;&lol6;&lol6;&lol6;\">"
                + "<!ENTITY lol8 \"&lol7;&lol7;&lol7;&lol7;&lol7;&lol7;&lol7;&lol7;&lol7;&lol7;\">"
                + "<!ENTITY lol9 \"&lol8;&lol8;&lol8;&lol8;&lol8;&lol8;&lol8;&lol8;&lol8;&lol8;\">]>"
                + "<Faktura xmlns=\"" + NS + "\">&lol9;</Faktura>";
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertCode(DOCTYPE_FORBIDDEN, bomb));
    }

    @Test
    void undeclaredEntityReferenceIsRejected() {
        var error = assertThrows(Fa3ParseException.class, () -> parser.parse(
                replace("Syntetyczny Nabywca S.A.", "&undeclared;")));
        assertTrue(Set.of(ENTITY_FORBIDDEN, MALFORMED_XML).contains(error.code()), error.getMessage());
    }

    @Test
    void inputSizeLimitIsEnforcedBeforeParsing() {
        var small = new SecureFa3InvoiceParser(1024);
        assertEquals(INPUT_TOO_LARGE, assertThrows(Fa3ParseException.class, () -> small.parse(valid)).code());
        assertEquals(INPUT_EMPTY, assertThrows(Fa3ParseException.class, () -> parser.parse(new byte[0])).code());
    }

    @Test
    void depthAndTextLimitsAreEnforced() {
        // Elementy bez prefiksu dziedziczą domyślny namespace FA(3), więc test sprawdza wyłącznie limit głębokości.
        String deep = "<a>".repeat(SecureFa3InvoiceParser.MAX_DEPTH + 5) + "</a>".repeat(SecureFa3InvoiceParser.MAX_DEPTH + 5);
        assertCode(LIMIT_EXCEEDED, replace("<Adnotacje>", "<Adnotacje>" + deep));
        assertCode(LIMIT_EXCEEDED, replace("FZ/2026/11/0001", "X".repeat(SecureFa3InvoiceParser.MAX_TEXT_CHARS + 1)));
    }

    @Test
    void malformedXmlIsRejected() {
        assertCode(MALFORMED_XML, validText.substring(0, validText.length() / 2));
        assertCode(MALFORMED_XML, "not xml at all");
        assertCode(MALFORMED_XML, validText.replace("</Fa>", "</Fx>"));
    }

    @Test
    void nonUtf8EncodingIsRejected() {
        byte[] utf16 = validText.replace("encoding=\"UTF-8\"", "encoding=\"UTF-16\"").getBytes(StandardCharsets.UTF_16);
        assertEquals(UNSUPPORTED_ENCODING, assertThrows(Fa3ParseException.class, () -> parser.parse(utf16)).code());
        byte[] latin = validText.replace("encoding=\"UTF-8\"", "encoding=\"ISO-8859-2\"").getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(UNSUPPORTED_ENCODING, assertThrows(Fa3ParseException.class, () -> parser.parse(latin)).code());
    }

    // ------------------------------------------------------------------ warianty i struktura

    @Test
    void unsupportedSchemasAreExplicitlyRecognised() {
        // FA(2) — inny namespace MF.
        assertCode(UNSUPPORTED_SCHEMA, validText.replace(NS, "http://crd.gov.pl/wzor/2023/06/29/12648/"));
        // Ten sam namespace, ale inna deklaracja formularza.
        assertCode(UNSUPPORTED_SCHEMA, replace("kodSystemowy=\"FA (3)\"", "kodSystemowy=\"FA (2)\""));
        assertCode(UNSUPPORTED_SCHEMA, replace("wersjaSchemy=\"1-0E\"", "wersjaSchemy=\"1-1E\""));
        assertCode(UNSUPPORTED_SCHEMA, replace("<WariantFormularza>3</WariantFormularza>", "<WariantFormularza>2</WariantFormularza>"));
        // Inny dokument (np. UPO) — nie faktura.
        assertCode(UNSUPPORTED_DOCUMENT, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Potwierdzenie xmlns=\"" + NS + "\"/>");
        assertCode(MISSING_REQUIRED, replace("<Naglowek>", "<Podmiot0/><Naglowek>"));
    }

    @Test
    void unexpectedNamespaceInsideDocumentIsRejected() {
        var error = assertCode(UNEXPECTED_NAMESPACE, replace("<P_6>2026-11-01</P_6>",
                "<P_6>2026-11-01</P_6><x:Inject xmlns:x=\"urn:evil\">1</x:Inject>"));
        assertFalse(error.getMessage().contains("evil"));
        assertFalse(error.getMessage().contains("Inject"));
    }

    @Test
    void requiredDuplicatedAndInvalidValuesAreRejected() {
        assertCode(MISSING_REQUIRED, replace("<P_2>FZ/2026/11/0001</P_2>", ""));
        assertCode(MISSING_REQUIRED, replace("<P_15>1269.00</P_15>", ""));
        assertCode(DUPLICATE_ELEMENT, replace("<P_2>FZ/2026/11/0001</P_2>", "<P_2>A</P_2><P_2>B</P_2>"));
        assertCode(DUPLICATE_ELEMENT, replace("<P_13_1>900.00</P_13_1>", "<P_13_1>900.00</P_13_1><P_13_1>1.00</P_13_1>"));
        assertCode(INVALID_VALUE, replace("<P_15>1269.00</P_15>", "<P_15>1.269E3</P_15>"));
        assertCode(INVALID_VALUE, replace("<P_15>1269.00</P_15>", "<P_15>1269.001</P_15>"));
        assertCode(INVALID_VALUE, replace("<P_1>2026-11-02</P_1>", "<P_1>2026-02-30</P_1>"));
        assertCode(INVALID_VALUE, replace("<P_1>2026-11-02</P_1>", "<P_1>2051-01-01</P_1>"));
        assertCode(INVALID_VALUE, replace("<NIP>2222222222</NIP>", "<NIP>0222222222</NIP>"));
        assertCode(INVALID_VALUE, replace("<RodzajFaktury>VAT</RodzajFaktury>", "<RodzajFaktury>XYZ</RodzajFaktury>"));
        assertCode(INVALID_VALUE, replace("<P_8B>2</P_8B>", "<P_8B>2.1234567</P_8B>"));
        assertCode(MISSING_REQUIRED, replace("<NrWierszaFa>2</NrWierszaFa>", ""));
    }

    @Test
    void exceptionMessagesNeverQuoteDocumentContent() {
        var error = assertThrows(Fa3ParseException.class,
                () -> parser.parse(replace("<P_15>1269.00</P_15>", "<P_15>SECRET-VALUE</P_15>")));
        assertFalse(error.getMessage().contains("SECRET-VALUE"));
        assertNull(error.getCause());
    }

    private byte[] replace(String from, String to) {
        assertTrue(validText.contains(from), "fixture must contain " + from);
        return validText.replace(from, to).getBytes(StandardCharsets.UTF_8);
    }

    private Fa3ParseException assertCode(Fa3ParseException.Code expected, String xml) {
        return assertCode(expected, xml.getBytes(StandardCharsets.UTF_8));
    }

    private Fa3ParseException assertCode(Fa3ParseException.Code expected, byte[] xml) {
        var error = assertThrows(Fa3ParseException.class, () -> parser.parse(xml));
        assertEquals(expected, error.code(), error.getMessage());
        return error;
    }
}
