package customer.cap_java.ksef.fa3;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.w3c.dom.ls.LSInput;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

/**
 * Walidacja XML oficjalnym XSD FA(3) MF (wersja schematu 1-0E, namespace z {@link Fa3XmlWriter#NAMESPACE}).
 * Schemat i trzy pliki bazowe CRD leżą bajt w bajt w {@code ksef/fa3/xsd/}; absolutne
 * {@code schemaLocation} z oryginałów są mapowane na classpath, więc walidacja nie korzysta z sieci.
 * Każdy inny import, zewnętrzna encja i DOCTYPE są odrzucane (ochrona XXE/SSRF).
 *
 * <p>XSD sprawdza strukturę, kolejność i liczność elementów, namespace, słowniki (waluta, stawka, kraj),
 * wzorce kwot/NIP/dat oraz długości tekstów. <b>Nie</b> sprawdza arytmetyki (wiersze, sumy stawek, P_15),
 * sumy kontrolnej NIP ani reguł prawnych — to zadanie {@link Fa3DomainValidator}.
 *
 * <p><b>Odstępstwo od oryginału (świadome):</b> Xerces z JDK rozwija każdą grupę {@code maxOccurs=N} na N kopii
 * w automacie DFA; dla FA(3) ({@code FaWiersz} 10000, {@code DaneFaKorygowanej} 50000 itd.) kompilacja modelu
 * treści praktycznie się nie kończy, a z secure processing jest odrzucana (limit 5000). Dlatego przed kompilacją,
 * wyłącznie w pamięci, górne limity {@code maxOccurs >= }{@value #RELAX_FROM} zamieniamy na {@code unbounded}
 * ({@link #RELAXED_ELEMENTS}). Plik na dysku pozostaje oryginalny (SHA-256 przypięty testem). Walidator nie
 * egzekwuje więc tych górnych limitów; limit wierszy egzekwuje {@link Fa3DomainValidator}.
 *
 * <p>Skompilowany {@link Schema} jest bezpieczny wątkowo; {@link Validator} tworzony per wywołanie.
 */
public class Fa3SchemaValidator {
    static final String XSD_DIR = "/ksef/fa3/xsd/";
    static final String MAIN_XSD = "schemat_FA3_v1-0E.xsd";
    static final int RELAX_FROM = 1000;
    /** Elementy, których górny limit jest rozluźniany; inna lista oznacza zmianę schematu i przerywa start. */
    static final List<String> RELAXED_ELEMENTS = List.of("WZ", "NowySrodekTransportu", "DaneFaKorygowanej",
            "DodatkowyOpis", "FaWiersz", "NrPartiiTowaru", "ZamowienieWiersz", "BlokDanych", "MetaDane", "Tabela",
            "TMetaDane", "Wiersz");
    private static final Pattern LARGE_MAX_OCCURS = Pattern.compile(
            "<xsd:element name=\"([A-Za-z0-9_]+)\"([^>]*?) maxOccurs=\"(\\d{4,})\"");
    private static final String CRD_BASE = "http://crd.gov.pl/xml/schematy/dziedzinowe/mf/2022/01/05/eD/DefinicjeTypy/";
    private static final Map<String, String> OFFICIAL_LOCATIONS = Map.of(
            CRD_BASE + "StrukturyDanych_v10-0E.xsd", "StrukturyDanych_v10-0E.xsd",
            CRD_BASE + "ElementarneTypyDanych_v10-0E.xsd", "ElementarneTypyDanych_v10-0E.xsd",
            CRD_BASE + "KodyKrajow_v10-0E.xsd", "KodyKrajow_v10-0E.xsd");

    private final Schema schema;

    public Fa3SchemaValidator() {
        this.schema = compile();
    }

    /** Zwraca wszystkie błędy XSD (kod XSD_INVALID, ścieżka „linia:kolumna”); pusta lista = zgodność z XSD. */
    public List<Fa3Violation> validate(byte[] xml) {
        var violations = new ArrayList<Fa3Violation>();
        if (xml == null || xml.length == 0) {
            violations.add(new Fa3Violation("REQUIRED", "xml", null));
            return violations;
        }
        Validator validator = schema.newValidator();
        try {
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            validator.setErrorHandler(new Collector(violations));
            // Własny parser: DOCTYPE (a więc i encje) jest odrzucany jeszcze przed walidacją.
            // Fabryka SAX nie gwarantuje bezpieczeństwa wątkowego — tworzona per wywołanie.
            XMLReader reader = parsers().newSAXParser().getXMLReader();
            validator.validate(new SAXSource(reader, new InputSource(new ByteArrayInputStream(xml))));
        } catch (SAXParseException alreadyCollected) {
            // Błąd krytyczny (np. niepoprawny XML, DOCTYPE) został już dodany przez Collector.
        } catch (SAXException | IOException | ParserConfigurationException e) {
            violations.add(new Fa3Violation("XSD_INVALID", "document", e.getMessage()));
        }
        return violations;
    }

    public void requireValid(byte[] xml) {
        var violations = validate(xml);
        if (!violations.isEmpty()) {
            throw new Fa3ValidationException(violations);
        }
    }

    private static Schema compile() {
        try {
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setResourceResolver((type, namespace, publicId, systemId, baseUri) -> {
                String local = OFFICIAL_LOCATIONS.get(systemId);
                if (local == null) {
                    throw new IllegalStateException("FA3_XSD_UNEXPECTED_IMPORT: " + systemId);
                }
                return new ClasspathInput(publicId, systemId, local);
            });
            URL main = resource(MAIN_XSD);
            String relaxed;
            try (InputStream in = main.openStream()) {
                relaxed = relaxLargeMaxOccurs(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            return factory.newSchema(new StreamSource(new StringReader(relaxed), main.toExternalForm()));
        } catch (SAXException | IOException e) {
            throw new IllegalStateException("FA3_XSD_LOAD_FAILED", e);
        }
    }

    /** Zamienia {@code maxOccurs >= RELAX_FROM} na unbounded; sprawdza, że dotyczy to dokładnie znanych elementów. */
    static String relaxLargeMaxOccurs(String xsd) {
        var relaxed = new ArrayList<String>();
        Matcher m = LARGE_MAX_OCCURS.matcher(xsd);
        var out = new StringBuilder();
        while (m.find()) {
            if (Integer.parseInt(m.group(3)) < RELAX_FROM) {
                continue;
            }
            relaxed.add(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(
                    "<xsd:element name=\"" + m.group(1) + "\"" + m.group(2) + " maxOccurs=\"unbounded\""));
        }
        m.appendTail(out);
        if (!relaxed.equals(RELAXED_ELEMENTS)) {
            throw new IllegalStateException("FA3_XSD_UNEXPECTED_MAX_OCCURS: " + relaxed);
        }
        return out.toString();
    }

    private static SAXParserFactory parsers() {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            return factory;
        } catch (SAXException | ParserConfigurationException e) {
            throw new IllegalStateException("FA3_XML_PARSER_CONFIG_FAILED", e);
        }
    }

    static URL resource(String name) {
        URL url = Fa3SchemaValidator.class.getResource(XSD_DIR + name);
        if (url == null) {
            throw new IllegalStateException("FA3_XSD_MISSING: " + name);
        }
        return url;
    }

    private record Collector(List<Fa3Violation> violations) implements ErrorHandler {
        @Override
        public void warning(SAXParseException e) {
            // Ostrzeżenia nie unieważniają dokumentu.
        }

        @Override
        public void error(SAXParseException e) {
            violations.add(new Fa3Violation("XSD_INVALID", e.getLineNumber() + ":" + e.getColumnNumber(),
                    e.getMessage()));
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXParseException {
            error(e);
            throw e;
        }
    }

    /** LSInput wskazujący lokalną kopię oficjalnego pliku; systemId zostaje oryginalny (rozwiązywanie względne). */
    private static final class ClasspathInput implements LSInput {
        private final String publicId;
        private final String systemId;
        private final String localName;

        ClasspathInput(String publicId, String systemId, String localName) {
            this.publicId = publicId;
            this.systemId = systemId;
            this.localName = localName;
        }

        @Override
        public InputStream getByteStream() {
            try {
                return resource(localName).openStream();
            } catch (IOException e) {
                throw new IllegalStateException("FA3_XSD_LOAD_FAILED: " + localName, e);
            }
        }

        @Override
        public String getSystemId() {
            return systemId;
        }

        @Override
        public String getPublicId() {
            return publicId;
        }

        @Override
        public Reader getCharacterStream() {
            return null;
        }

        @Override
        public String getStringData() {
            return null;
        }

        @Override
        public String getBaseURI() {
            return null;
        }

        @Override
        public String getEncoding() {
            return null;
        }

        @Override
        public boolean getCertifiedText() {
            return false;
        }

        @Override
        public void setCharacterStream(Reader characterStream) {
        }

        @Override
        public void setByteStream(InputStream byteStream) {
        }

        @Override
        public void setStringData(String stringData) {
        }

        @Override
        public void setSystemId(String systemId) {
        }

        @Override
        public void setPublicId(String publicId) {
        }

        @Override
        public void setBaseURI(String baseURI) {
        }

        @Override
        public void setEncoding(String encoding) {
        }

        @Override
        public void setCertifiedText(boolean certifiedText) {
        }
    }
}
