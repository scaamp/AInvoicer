package customer.cap_java.ksef.inbound.fa3;

import static customer.cap_java.ksef.inbound.fa3.Fa3ParseException.Code.*;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import customer.cap_java.ksef.inbound.fa3.Fa3InboundInvoice.IdentifierType;
import customer.cap_java.ksef.inbound.fa3.Fa3InboundInvoice.Line;
import customer.cap_java.ksef.inbound.fa3.Fa3InboundInvoice.Party;

/**
 * Bezpieczny, strumieniowy odczyt faktury FA(3) otrzymanej z KSeF (schemat MF 1-0E).
 *
 * <p><b>Bezpieczeństwo XML</b> (StAX z JDK, {@link XMLInputFactory#newDefaultFactory()} — niezależnie od
 * implementacji StAX na classpath):
 * <ul>
 *   <li>{@code SUPPORT_DTD=false}, {@code IS_SUPPORTING_EXTERNAL_ENTITIES=false},
 *       {@code ACCESS_EXTERNAL_DTD=""}, resolver odrzucający każde zewnętrzne odwołanie;</li>
 *   <li>każde zdarzenie DTD → {@code DOCTYPE_FORBIDDEN}, każde odwołanie do encji niepredefiniowanej →
 *       {@code ENTITY_FORBIDDEN} (XXE, external DTD, billion laughs);</li>
 *   <li>limit bajtów wejścia, głębokości, liczby elementów, długości tekstu i liczby wierszy;</li>
 *   <li>tylko UTF-8; każdy element musi być w namespace FA(3) ({@value #FA3_NAMESPACE});</li>
 *   <li>wyjątki nie zawierają treści dokumentu; parser niczego nie loguje.</li>
 * </ul>
 *
 * <p><b>Zakres:</b> to nie jest pełna walidacja XSD. Parser rozpoznaje wariant dokumentu (inny namespace,
 * inny {@code KodFormularza} → jawny {@code UNSUPPORTED_SCHEMA}, nie „najlepsze dopasowanie”), wymusza
 * obecność, jednokrotność i typ odczytywanych pól według XSD FA(3) i ignoruje pozostałe gałęzie (adresy,
 * płatności, załączniki). Pełną zgodność ze schematem można dodatkowo sprawdzić istniejącym
 * {@code ksef.fa3.Fa3SchemaValidator} (KSEF-IN-02). Obiekt jest bezstanowy i bezpieczny wątkowo.
 */
public final class SecureFa3InvoiceParser {
    /** Namespace FA(3) z oficjalnego XSD MF (ten sam co {@code ksef.fa3.Fa3XmlWriter.NAMESPACE}). */
    public static final String FA3_NAMESPACE = "http://crd.gov.pl/wzor/2025/06/25/13775/";
    /** Limit MF dla faktury z załącznikiem (3 MB). */
    public static final int DEFAULT_MAX_BYTES = 3 * 1024 * 1024;
    static final int HARD_MAX_BYTES = 16 * 1024 * 1024;
    static final int MAX_DEPTH = 32;
    static final int MAX_ELEMENTS = 400_000;
    /** Najdłuższy typ tekstowy XSD (TTekstowy) ma 3500 znaków; zapas na białe znaki. */
    static final int MAX_TEXT_CHARS = 8_192;
    /** {@code FaWiersz maxOccurs="10000"} w XSD FA(3). */
    static final int MAX_LINES = 10_000;

    private static final String ROOT = "Faktura";
    private static final String HEADER = "Faktura/Naglowek";
    private static final String FORM_CODE = "Faktura/Naglowek/KodFormularza";
    private static final String FORM_VARIANT = "Faktura/Naglowek/WariantFormularza";
    private static final String SELLER = "Faktura/Podmiot1/DaneIdentyfikacyjne/";
    private static final String BUYER = "Faktura/Podmiot2/DaneIdentyfikacyjne/";
    private static final String FA = "Faktura/Fa/";
    private static final String LINE = "Faktura/Fa/FaWiersz";
    private static final String LINE_PREFIX = LINE + "/";

    private static final Set<String> TRACKED = Set.of(
            FORM_CODE, FORM_VARIANT,
            SELLER + "NIP", SELLER + "Nazwa",
            BUYER + "NIP", BUYER + "KodUE", BUYER + "NrVatUE", BUYER + "KodKraju", BUYER + "NrID",
            BUYER + "BrakID", BUYER + "Nazwa",
            FA + "KodWaluty", FA + "P_1", FA + "P_2", FA + "P_15", FA + "RodzajFaktury");
    private static final Set<String> LINE_FIELDS = Set.of("NrWierszaFa", "UU_ID", "P_7", "P_8A", "P_8B", "P_9A",
            "P_9B", "P_11", "P_11A", "P_11Vat", "P_12");
    private static final Pattern NET_FIELD = Pattern.compile("P_13_(1|2|3|4|5|6_1|6_2|6_3|7|8|9|10|11)");
    private static final Pattern VAT_FIELD = Pattern.compile("P_14_[1-5]");
    private static final Pattern VAT_PLN_FIELD = Pattern.compile("P_14_[1-4]W");
    private static final Set<String> INVOICE_KINDS = Set.of("VAT", "KOR", "ZAL", "ROZ", "UPR", "KOR_ZAL", "KOR_ROZ");

    // Wzorce typów prostych z XSD FA(3) 1-0E / ElementarneTypyDanych v10-0E.
    private static final Pattern NIP = Pattern.compile("[1-9]((\\d[1-9])|([1-9]\\d))\\d{7}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern T_KWOTOWY = Pattern.compile("-?([1-9]\\d{0,15}|0)(\\.\\d{1,2})?");
    private static final Pattern T_KWOTOWY2 = Pattern.compile("-?([1-9]\\d{0,13}|0)(\\.\\d{1,8})?");
    private static final Pattern T_ILOSCI = Pattern.compile("-?([1-9]\\d{0,15}|0)(\\.\\d{1,6})?");
    private static final Pattern T_NATURALNY = Pattern.compile("\\d{1,14}");
    private static final LocalDate MIN_DATE = LocalDate.of(2006, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(2050, 1, 1);

    private final int maxBytes;

    public SecureFa3InvoiceParser() {
        this(DEFAULT_MAX_BYTES);
    }

    public SecureFa3InvoiceParser(int maxBytes) {
        if (maxBytes <= 0 || maxBytes > HARD_MAX_BYTES) {
            throw new IllegalArgumentException("FA3_PARSER_MAX_BYTES_INVALID");
        }
        this.maxBytes = maxBytes;
    }

    /**
     * Parsuje dokładne bajty XML. Wejście nie jest modyfikowane ani przechowywane.
     *
     * @throws Fa3ParseException dla każdego dokumentu, który nie jest bezpiecznym, poprawnym FA(3) 1-0E
     */
    public Fa3InboundInvoice parse(byte[] xml) {
        if (xml == null || xml.length == 0) {
            throw new Fa3ParseException(INPUT_EMPTY, null);
        }
        if (xml.length > maxBytes) {
            throw new Fa3ParseException(INPUT_TOO_LARGE, null);
        }
        XMLStreamReader reader = null;
        try {
            reader = factory().createXMLStreamReader(new ByteArrayInputStream(xml));
            return new Session(reader).run();
        } catch (XMLStreamException e) {
            // Komunikat StAX może cytować wejście — nie przekazujemy go dalej.
            throw new Fa3ParseException(MALFORMED_XML, null);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // Zamknięcie czytnika na tablicy bajtów nie ma zasobów zewnętrznych.
                }
            }
        }
    }

    /** Fabryka per wywołanie: specyfikacja StAX nie gwarantuje bezpieczeństwa wątkowego fabryki. */
    private static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);
        factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("EXTERNAL_RESOLUTION_FORBIDDEN");
        });
        return factory;
    }

    /** Stan jednego parsowania; nie jest współdzielony między wątkami. */
    private static final class Session {
        private final XMLStreamReader reader;
        private final Deque<String> paths = new ArrayDeque<>();
        private final Deque<StringBuilder> texts = new ArrayDeque<>();
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, BigDecimal> net = new LinkedHashMap<>();
        private final Map<String, BigDecimal> vat = new LinkedHashMap<>();
        private final Map<String, BigDecimal> vatPln = new LinkedHashMap<>();
        private final List<Line> lines = new ArrayList<>();
        private Map<String, String> currentLine;
        private String systemCode;
        private String schemaVersion;
        private boolean rootSeen;
        private boolean headerVerified;
        private int elements;

        Session(XMLStreamReader reader) {
            this.reader = reader;
        }

        Fa3InboundInvoice run() throws XMLStreamException {
            verifyEncoding();
            while (reader.hasNext()) {
                switch (reader.next()) {
                    case XMLStreamConstants.DTD -> throw new Fa3ParseException(DOCTYPE_FORBIDDEN, null);
                    case XMLStreamConstants.ENTITY_REFERENCE, XMLStreamConstants.ENTITY_DECLARATION,
                            XMLStreamConstants.NOTATION_DECLARATION -> throw new Fa3ParseException(ENTITY_FORBIDDEN, null);
                    case XMLStreamConstants.START_ELEMENT -> start();
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> text();
                    case XMLStreamConstants.END_ELEMENT -> end();
                    default -> {
                        // Komentarze i instrukcje przetwarzania nie wpływają na dane faktury.
                    }
                }
            }
            if (!rootSeen) {
                throw new Fa3ParseException(MALFORMED_XML, null);
            }
            return build();
        }

        private void verifyEncoding() {
            String declared = reader.getCharacterEncodingScheme();
            String detected = reader.getEncoding();
            if ((declared != null && !isUtf8(declared)) || (detected != null && !isUtf8(detected))) {
                throw new Fa3ParseException(UNSUPPORTED_ENCODING, null);
            }
        }

        private static boolean isUtf8(String name) {
            return "UTF-8".equalsIgnoreCase(name) || "UTF8".equalsIgnoreCase(name);
        }

        private void start() {
            if (++elements > MAX_ELEMENTS) {
                throw new Fa3ParseException(LIMIT_EXCEEDED, "elements");
            }
            String local = reader.getLocalName();
            String namespace = reader.getNamespaceURI();
            String parent = paths.peek();
            if (parent == null) {
                if (rootSeen) {
                    throw new Fa3ParseException(MALFORMED_XML, null);
                }
                rootSeen = true;
                if (!ROOT.equals(local)) {
                    throw new Fa3ParseException(UNSUPPORTED_DOCUMENT, null);
                }
                if (!FA3_NAMESPACE.equals(namespace)) {
                    throw new Fa3ParseException(UNSUPPORTED_SCHEMA, null);
                }
            } else if (!FA3_NAMESPACE.equals(namespace)) {
                // Ścieżka rodzica, nie nazwa obcego elementu: komunikat nie cytuje danych wejścia.
                throw new Fa3ParseException(UNEXPECTED_NAMESPACE, parent);
            }
            String path = parent == null ? local : parent + "/" + local;
            if (paths.size() >= MAX_DEPTH) {
                throw new Fa3ParseException(LIMIT_EXCEEDED, "depth");
            }
            if (!headerVerified && parent != null && parent.equals(ROOT) && !"Naglowek".equals(local)) {
                // Naglowek jest pierwszym elementem sekwencji FA(3); bez niego nie znamy wariantu dokumentu.
                throw new Fa3ParseException(MISSING_REQUIRED, HEADER);
            }
            paths.push(path);
            texts.push(captured(path) ? new StringBuilder() : NOT_CAPTURED);
            if (FORM_CODE.equals(path)) {
                systemCode = reader.getAttributeValue(null, "kodSystemowy");
                schemaVersion = reader.getAttributeValue(null, "wersjaSchemy");
            } else if (LINE.equals(path)) {
                if (lines.size() >= MAX_LINES) {
                    throw new Fa3ParseException(LIMIT_EXCEEDED, LINE);
                }
                currentLine = new HashMap<>();
            }
        }

        private static final StringBuilder NOT_CAPTURED = new StringBuilder(0);

        private boolean captured(String path) {
            if (TRACKED.contains(path)) {
                return true;
            }
            if (path.startsWith(LINE_PREFIX)) {
                return LINE_FIELDS.contains(path.substring(LINE_PREFIX.length()));
            }
            if (path.startsWith(FA) && path.indexOf('/', FA.length()) < 0) {
                String local = path.substring(FA.length());
                return NET_FIELD.matcher(local).matches() || VAT_FIELD.matcher(local).matches()
                        || VAT_PLN_FIELD.matcher(local).matches();
            }
            return false;
        }

        private void text() {
            StringBuilder target = texts.peek();
            if (target == null || target == NOT_CAPTURED) {
                return;
            }
            if (target.length() + reader.getTextLength() > MAX_TEXT_CHARS) {
                throw new Fa3ParseException(LIMIT_EXCEEDED, paths.peek());
            }
            target.append(reader.getTextCharacters(), reader.getTextStart(), reader.getTextLength());
        }

        private void end() {
            String path = paths.pop();
            StringBuilder buffer = texts.pop();
            if (HEADER.equals(path)) {
                verifyHeader();
                return;
            }
            if (LINE.equals(path)) {
                lines.add(line(currentLine));
                currentLine = null;
                return;
            }
            if (buffer == NOT_CAPTURED) {
                return;
            }
            String value = buffer.toString();
            if (path.startsWith(LINE_PREFIX)) {
                putOnce(currentLine, path.substring(LINE_PREFIX.length()), value, path);
                return;
            }
            if (path.startsWith(FA) && path.indexOf('/', FA.length()) < 0) {
                String local = path.substring(FA.length());
                if (NET_FIELD.matcher(local).matches()) {
                    putAmountOnce(net, local, value, path);
                    return;
                }
                if (VAT_PLN_FIELD.matcher(local).matches()) {
                    putAmountOnce(vatPln, local, value, path);
                    return;
                }
                if (VAT_FIELD.matcher(local).matches()) {
                    putAmountOnce(vat, local, value, path);
                    return;
                }
            }
            putOnce(values, path, value, path);
        }

        /** Odrzuca każdy wariant inny niż FA(3) 1-0E, zanim parser odczyta dane biznesowe. */
        private void verifyHeader() {
            String form = values.get(FORM_CODE);
            String variant = values.get(FORM_VARIANT);
            if (form == null) {
                throw new Fa3ParseException(MISSING_REQUIRED, FORM_CODE);
            }
            if (!"FA (3)".equals(systemCode) || !"1-0E".equals(schemaVersion) || !"FA".equals(form.strip())
                    || variant == null || !"3".equals(variant.strip())) {
                throw new Fa3ParseException(UNSUPPORTED_SCHEMA, FORM_CODE);
            }
            headerVerified = true;
        }

        private Fa3InboundInvoice build() {
            if (!headerVerified) {
                throw new Fa3ParseException(MISSING_REQUIRED, HEADER);
            }
            Party seller = new Party(IdentifierType.NIP, null,
                    match(required(SELLER + "NIP"), NIP, SELLER + "NIP"), token(required(SELLER + "Nazwa")));
            Party buyer = buyer();
            String currency = match(required(FA + "KodWaluty"), CURRENCY, FA + "KodWaluty");
            LocalDate issueDate = date(required(FA + "P_1"), FA + "P_1");
            String number = token(required(FA + "P_2"));
            if (number.isEmpty() || number.length() > 256) {
                throw new Fa3ParseException(INVALID_VALUE, FA + "P_2");
            }
            BigDecimal gross = decimal(required(FA + "P_15"), T_KWOTOWY, FA + "P_15");
            String kind = required(FA + "RodzajFaktury").strip();
            if (!INVOICE_KINDS.contains(kind)) {
                throw new Fa3ParseException(INVALID_VALUE, FA + "RodzajFaktury");
            }
            return new Fa3InboundInvoice(systemCode, schemaVersion, "3", number, issueDate, currency, kind,
                    seller, buyer, net, vat, vatPln, gross, lines);
        }

        /** Dokładnie jedna alternatywa identyfikacji nabywcy z {@code xsd:choice} TPodmiot2. */
        private Party buyer() {
            String nip = values.get(BUYER + "NIP");
            String euCode = values.get(BUYER + "KodUE");
            String euVat = values.get(BUYER + "NrVatUE");
            String country = values.get(BUYER + "KodKraju");
            String otherId = values.get(BUYER + "NrID");
            String noId = values.get(BUYER + "BrakID");
            String name = values.containsKey(BUYER + "Nazwa") ? token(values.get(BUYER + "Nazwa")) : null;
            int alternatives = (nip != null ? 1 : 0) + (euCode != null || euVat != null ? 1 : 0)
                    + (country != null || otherId != null ? 1 : 0) + (noId != null ? 1 : 0);
            if (alternatives == 0) {
                throw new Fa3ParseException(MISSING_REQUIRED, BUYER + "NIP");
            }
            if (alternatives > 1) {
                throw new Fa3ParseException(INVALID_VALUE, BUYER);
            }
            if (nip != null) {
                return new Party(IdentifierType.NIP, null, match(nip, NIP, BUYER + "NIP"), name);
            }
            if (euCode != null || euVat != null) {
                if (euCode == null || euVat == null) {
                    throw new Fa3ParseException(MISSING_REQUIRED, BUYER + (euCode == null ? "KodUE" : "NrVatUE"));
                }
                return new Party(IdentifierType.VAT_UE, match(euCode, COUNTRY, BUYER + "KodUE"),
                        nonBlank(euVat, BUYER + "NrVatUE"), name);
            }
            if (otherId != null || country != null) {
                if (otherId == null) {
                    throw new Fa3ParseException(MISSING_REQUIRED, BUYER + "NrID");
                }
                return new Party(IdentifierType.OTHER, country == null ? null : match(country, COUNTRY, BUYER + "KodKraju"),
                        nonBlank(otherId, BUYER + "NrID"), name);
            }
            if (!"1".equals(noId.strip())) {
                throw new Fa3ParseException(INVALID_VALUE, BUYER + "BrakID");
            }
            return new Party(IdentifierType.NONE, null, null, name);
        }

        private Line line(Map<String, String> fields) {
            String path = LINE_PREFIX;
            String number = fields.get("NrWierszaFa");
            if (number == null) {
                throw new Fa3ParseException(MISSING_REQUIRED, path + "NrWierszaFa");
            }
            return new Line(
                    Long.parseLong(match(number, T_NATURALNY, path + "NrWierszaFa")),
                    optionalToken(fields.get("UU_ID")),
                    optionalToken(fields.get("P_7")),
                    optionalToken(fields.get("P_8A")),
                    optionalDecimal(fields.get("P_8B"), T_ILOSCI, path + "P_8B"),
                    optionalDecimal(fields.get("P_9A"), T_KWOTOWY2, path + "P_9A"),
                    optionalDecimal(fields.get("P_9B"), T_KWOTOWY2, path + "P_9B"),
                    optionalDecimal(fields.get("P_11"), T_KWOTOWY, path + "P_11"),
                    optionalDecimal(fields.get("P_11A"), T_KWOTOWY, path + "P_11A"),
                    optionalDecimal(fields.get("P_11Vat"), T_KWOTOWY, path + "P_11Vat"),
                    optionalToken(fields.get("P_12")));
        }

        private void putAmountOnce(Map<String, BigDecimal> target, String key, String value, String path) {
            if (target.containsKey(key)) {
                throw new Fa3ParseException(DUPLICATE_ELEMENT, path);
            }
            target.put(key, decimal(value, T_KWOTOWY, path));
        }

        private static void putOnce(Map<String, String> target, String key, String value, String path) {
            if (target.putIfAbsent(key, value) != null) {
                throw new Fa3ParseException(DUPLICATE_ELEMENT, path);
            }
        }

        private String required(String path) {
            String value = values.get(path);
            if (value == null || value.isBlank()) {
                throw new Fa3ParseException(MISSING_REQUIRED, path);
            }
            return value;
        }

        private static String match(String value, Pattern pattern, String path) {
            String trimmed = value.strip();
            if (!pattern.matcher(trimmed).matches()) {
                throw new Fa3ParseException(INVALID_VALUE, path);
            }
            return trimmed;
        }

        private static String nonBlank(String value, String path) {
            String token = token(value);
            if (token.isEmpty()) {
                throw new Fa3ParseException(INVALID_VALUE, path);
            }
            return token;
        }

        private static BigDecimal decimal(String value, Pattern pattern, String path) {
            // Wzorzec XSD ogranicza liczbę cyfr i wyklucza wykładnik, więc konstrukcja BigDecimal jest tania.
            return new BigDecimal(match(value, pattern, path));
        }

        private static BigDecimal optionalDecimal(String value, Pattern pattern, String path) {
            return value == null ? null : decimal(value, pattern, path);
        }

        private static LocalDate date(String value, String path) {
            String trimmed = match(value, DATE, path);
            try {
                LocalDate date = LocalDate.parse(trimmed);
                if (date.isBefore(MIN_DATE) || date.isAfter(MAX_DATE)) {
                    throw new Fa3ParseException(INVALID_VALUE, path);
                }
                return date;
            } catch (DateTimeParseException e) {
                throw new Fa3ParseException(INVALID_VALUE, path);
            }
        }

        /** {@code xsd:token}: zwinięcie białych znaków. */
        private static String token(String value) {
            return value.strip().replaceAll("\\s+", " ");
        }

        private static String optionalToken(String value) {
            if (value == null) {
                return null;
            }
            String token = token(value);
            return token.isEmpty() ? null : token;
        }
    }
}
