package customer.cap_java.ksef.fa3;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.Map;

import org.junit.jupiter.api.Test;

import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;

/**
 * Przypina dokładne bajty oficjalnych XSD. Źródło: crd.gov.pl (pobrane 2026-10-29); główny schemat
 * bajtowo identyczny z CIRFMF/ksef-api faktury/schemy/FA/schemat_FA(3)_v1-0E.xsd. Zmiana pliku
 * (np. konwersja końców linii przez Git) ma przerwać build, a nie po cichu zmienić walidację.
 */
class Fa3SchemaResourcesTest {
    private static final Map<String, String> OFFICIAL_SHA256 = Map.of(
            "schemat_FA3_v1-0E.xsd", "b646b6b525f51adf1bb2545f111fc8ca6e7aa6dd2f98948f1667d3695c06d958",
            "StrukturyDanych_v10-0E.xsd", "1137ce6e3c11c2b9ef3f05e4e72d6dcd6b4fa94908ea558f2ba15de0259bb2aa",
            "ElementarneTypyDanych_v10-0E.xsd", "8a531cb181d3e298d11b28766655ae91fee2d7851440095932ffc82137ed2be1",
            "KodyKrajow_v10-0E.xsd", "1d41a1b3184188f2d20a51d3afde26204dda182ec5dacf018204dcc9870dc644");

    @Test
    void bundledSchemasAreUnmodifiedOfficialFiles() throws Exception {
        for (var entry : OFFICIAL_SHA256.entrySet()) {
            try (InputStream in = Fa3SchemaValidator.resource(entry.getKey()).openStream()) {
                assertEquals(entry.getValue(), KsefInvoiceSnapshotRegistry.sha256Hex(in.readAllBytes()), entry.getKey());
            }
        }
    }

    @Test
    void onlyKnownLargeMaxOccursAreRelaxedInMemory() throws Exception {
        try (InputStream in = Fa3SchemaValidator.resource(Fa3SchemaValidator.MAIN_XSD).openStream()) {
            String original = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String relaxed = Fa3SchemaValidator.relaxLargeMaxOccurs(original);

            assertTrue(original.contains("<xsd:element name=\"FaWiersz\" minOccurs=\"0\" maxOccurs=\"10000\">"));
            assertTrue(relaxed.contains("<xsd:element name=\"FaWiersz\" minOccurs=\"0\" maxOccurs=\"unbounded\">"));
            assertFalse(relaxed.matches("(?s).*maxOccurs=\"\\d{4,}\".*"));
            // Poza zamianą 12 wartości maxOccurs treść jest identyczna (np. maxOccurs="100" zostaje).
            assertEquals(original.replaceAll("maxOccurs=\"\\d{4,}\"", "maxOccurs=\"unbounded\""), relaxed);
            assertEquals(12, Fa3SchemaValidator.RELAXED_ELEMENTS.size());
        }
    }

    @Test
    void mainSchemaDeclaresFa3NamespaceAndVersion() throws Exception {
        try (InputStream in = Fa3SchemaValidator.resource(Fa3SchemaValidator.MAIN_XSD).openStream()) {
            String xsd = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(xsd.contains("targetNamespace=\"" + Fa3XmlWriter.NAMESPACE + "\""));
            assertTrue(xsd.contains("fixed=\"FA (3)\""));
            assertTrue(xsd.contains("fixed=\"1-0E\""));
        }
    }
}
