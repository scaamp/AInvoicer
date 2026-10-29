package customer.cap_java.ksef;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import customer.cap_java.ksef.fa3.Fa3XmlWriter;

class KsefFa3LimitsTest {
    @Test void originalUpperBoundIsRestoredPerParent() {
        String opening = "<Faktura xmlns=\"" + Fa3XmlWriter.NAMESPACE + "\">";
        String allowed = "<WZ>x</WZ>".repeat(1000);
        assertDoesNotThrow(() -> KsefFa3Documents.requireMaxOccurs((opening + allowed + "</Faktura>").getBytes(StandardCharsets.UTF_8)));
        var failure = assertThrows(KsefFailure.class, () -> KsefFa3Documents.requireMaxOccurs(
                (opening + allowed + "<WZ>x</WZ></Faktura>").getBytes(StandardCharsets.UTF_8)));
        assertEquals("KSEF_FA3_MAX_OCCURS_WZ", failure.getMessage());
        assertDoesNotThrow(() -> KsefFa3Documents.requireMaxOccurs((opening + "<a>" + allowed + "</a><b>" + allowed + "</b></Faktura>")
                .getBytes(StandardCharsets.UTF_8)));
    }

    @Test void allRelaxedBoundsMatchPinnedOfficialSchema() throws Exception {
        var parser = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        parser.setNamespaceAware(true);
        try (var in = getClass().getResourceAsStream("/ksef/fa3/xsd/schemat_FA3_v1-0E.xsd")) {
            var elements = parser.newDocumentBuilder().parse(in).getElementsByTagNameNS("http://www.w3.org/2001/XMLSchema", "element");
            int checked = 0;
            for (int i = 0; i < elements.getLength(); i++) {
                var element = (org.w3c.dom.Element) elements.item(i);
                String max = element.getAttribute("maxOccurs");
                if (!max.matches("[0-9]+") || Integer.parseInt(max) < 1000) { continue; }
                String name = element.getAttribute("name");
                String document = "<root xmlns=\"" + Fa3XmlWriter.NAMESPACE + "\">"
                        + ("<" + name + "/>").repeat(Integer.parseInt(max) + 1) + "</root>";
                assertEquals("KSEF_FA3_MAX_OCCURS_" + name, assertThrows(KsefFailure.class,
                        () -> KsefFa3Documents.requireMaxOccurs(document.getBytes(StandardCharsets.UTF_8))).getMessage());
                checked++;
            }
            assertEquals(12, checked);
        }
    }
}
