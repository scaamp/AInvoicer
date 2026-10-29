package customer.cap_java.ksef;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import org.springframework.stereotype.Component;
import customer.cap_java.ksef.fa3.Fa3Document;
import customer.cap_java.ksef.fa3.Fa3InvoiceXmlService;
import customer.cap_java.ksef.fa3.Fa3XmlWriter;
import customer.cap_java.ksef.fa3.SapInvoiceFa3Mapper;
import customer.cap_java.sap.SapInvoiceSnapshot;

/** Granica integracji z komponentem B: dokładne bajty, XSD i przywrócone duże limity liczności. */
@Component
public class KsefFa3Documents {
    // Oryginalne maxOccurs 12 elementów rozluźnionych w pamięci przez walidator B (FA(3) 1-0E).
    private static final Map<String, Integer> LIMITS = Map.ofEntries(
            Map.entry("WZ", 1000), Map.entry("NowySrodekTransportu", 10000),
            Map.entry("DaneFaKorygowanej", 50000), Map.entry("DodatkowyOpis", 10000),
            Map.entry("FaWiersz", 10000), Map.entry("NrPartiiTowaru", 1000),
            Map.entry("ZamowienieWiersz", 10000), Map.entry("BlokDanych", 1000),
            Map.entry("MetaDane", 1000), Map.entry("Tabela", 1000), Map.entry("TMetaDane", 1000), Map.entry("Wiersz", 1000));
    private final Fa3InvoiceXmlService fa3;

    public KsefFa3Documents(Fa3InvoiceXmlService fa3) { this.fa3 = fa3; }

    /** Generator B odpowiada za mapowanie, arytmetykę i XSD; wynik nie jest ponownie serializowany. */
    public Fa3Document prepare(SapInvoiceSnapshot sap, SapInvoiceFa3Mapper.PartyData party) {
        try {
            var document = fa3.prepare(new SapInvoiceFa3Mapper().map(sap, party));
            requireMaxOccurs(document.xml());
            return document;
        } catch (KsefFailure safe) { throw safe; }
        catch (Exception e) { throw KsefFailure.invalid("KSEF_FA3_PREPARATION_INVALID"); }
    }

    /** Waliduje bajty przed enqueue i ponownie po odczycie z magazynu, zanim worker wykona HTTP. */
    public void verify(byte[] xml) {
        if (xml == null || xml.length == 0 || xml.length > 10 * 1024 * 1024) {
            throw KsefFailure.invalid("KSEF_FA3_SIZE_INVALID");
        }
        try { fa3.verify(xml); requireMaxOccurs(xml); }
        catch (KsefFailure safe) { throw safe; }
        catch (Exception e) { throw KsefFailure.invalid("KSEF_FA3_XSD_INVALID"); }
    }

    /** Strumieniowy licznik rodzeństwa przywraca oryginalne maxOccurs bez rozwijania DFA Xerces. */
    static void requireMaxOccurs(byte[] xml) {
        try {
            var factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            var reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            try {
                var parents = new ArrayDeque<Map<String, Integer>>();
                parents.push(new HashMap<>());
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                        throw KsefFailure.invalid("KSEF_FA3_DTD_FORBIDDEN");
                    }
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String name = reader.getLocalName();
                        Integer limit = LIMITS.get(name);
                        if (limit != null && Fa3XmlWriter.NAMESPACE.equals(reader.getNamespaceURI())
                                && parents.peek().merge(name, 1, Integer::sum) > limit) {
                            throw KsefFailure.invalid("KSEF_FA3_MAX_OCCURS_" + name);
                        }
                        if (parents.size() > 128) { throw KsefFailure.invalid("KSEF_FA3_DEPTH_INVALID"); }
                        parents.push(new HashMap<>());
                    } else if (event == XMLStreamConstants.END_ELEMENT) { parents.pop(); }
                }
            } finally { reader.close(); }
        } catch (KsefFailure safe) { throw safe; }
        catch (Exception e) { throw KsefFailure.invalid("KSEF_FA3_XML_INVALID"); }
    }
}
