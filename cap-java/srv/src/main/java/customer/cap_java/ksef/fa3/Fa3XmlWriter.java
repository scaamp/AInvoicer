package customer.cap_java.ksef.fa3;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import customer.cap_java.ksef.fa3.Fa3Invoice.Address;
import customer.cap_java.ksef.fa3.Fa3Invoice.Line;
import customer.cap_java.ksef.fa3.Fa3Invoice.VatRateTotal;

/**
 * Serializuje {@link Fa3Invoice} do XML FA(3) (namespace {@value #NAMESPACE}) przez standardowy StAX JDK.
 * Wynik jest deterministyczny: stała kolejność elementów zgodna z sekwencją XSD, stałe wcięcia 2 spacje,
 * separator linii LF, UTF-8 bez BOM, kwoty w zapisie dziesiętnym bez wykładnika. Nie używa zegara ani
 * losowości — DataWytworzeniaFa pochodzi z danych wejściowych. Nie waliduje danych; wywołuj przez
 * {@link Fa3InvoiceXmlService}, który wcześniej uruchamia walidację domenową, a potem XSD.
 */
public class Fa3XmlWriter {
    public static final String NAMESPACE = "http://crd.gov.pl/wzor/2025/06/25/13775/";
    private static final String INDENT = "  ";
    // StAX JDK zapisuje deklarację z apostrofami; stałą, konwencjonalną postać zapisujemy wprost jako bajty.
    private static final byte[] DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            .getBytes(StandardCharsets.UTF_8);

    public byte[] write(Fa3Invoice invoice) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(DECLARATION);
        try {
            // Fabryka per wywołanie: specyfikacja StAX nie gwarantuje jej bezpieczeństwa wątkowego.
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(out, StandardCharsets.UTF_8.name());
            new Session(xml).document(invoice);
            xml.flush();
            xml.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("FA3_XML_WRITE_FAILED", e);
        }
        return out.toByteArray();
    }

    /** Stan jednej serializacji (głębokość wcięcia); nie jest współdzielony między wątkami. */
    private static final class Session {
        private final XMLStreamWriter xml;
        private int depth;

        Session(XMLStreamWriter xml) {
            this.xml = xml;
        }

        void document(Fa3Invoice inv) throws XMLStreamException {
            xml.setDefaultNamespace(NAMESPACE);
            xml.writeStartElement(NAMESPACE, "Faktura");
            xml.writeDefaultNamespace(NAMESPACE);
            depth++;

            open("Naglowek");
            newline();
            xml.writeStartElement(NAMESPACE, "KodFormularza");
            xml.writeAttribute("kodSystemowy", "FA (3)");
            xml.writeAttribute("wersjaSchemy", "1-0E");
            xml.writeCharacters("FA");
            xml.writeEndElement();
            leaf("WariantFormularza", "3");
            leaf("DataWytworzeniaFa", DateTimeFormatter.ISO_INSTANT.format(inv.createdAt()));
            leaf("SystemInfo", inv.systemInfo());
            close();

            open("Podmiot1");
            open("DaneIdentyfikacyjne");
            leaf("NIP", inv.seller().nip());
            leaf("Nazwa", inv.seller().name());
            close();
            address(inv.seller().address());
            close();

            open("Podmiot2");
            open("DaneIdentyfikacyjne");
            leaf("NIP", inv.buyer().nip());
            leaf("Nazwa", inv.buyer().name());
            close();
            address(inv.buyer().address());
            // JST/GV: 1 = tak, 2 = nie.
            leaf("JST", yesNo(inv.buyer().jstSubordinateUnit()));
            leaf("GV", yesNo(inv.buyer().vatGroupMember()));
            close();

            fa(inv);

            depth--;
            newline();
            xml.writeEndElement();
            xml.writeCharacters("\n");
        }

        private void fa(Fa3Invoice inv) throws XMLStreamException {
            open("Fa");
            leaf("KodWaluty", inv.currency());
            leaf("P_1", inv.issueDate().toString());
            leaf("P_1M", inv.issuePlace());
            leaf("P_2", inv.invoiceNumber());
            leaf("P_6", inv.saleDate() == null ? null : inv.saleDate().toString());
            // Sekwencje P_13_x/P_14_x muszą wystąpić w kolejności grup XSD, niezależnie od kolejności wejścia.
            List<VatRateTotal> totals = inv.vatTotals().stream()
                    .sorted(Comparator.comparingInt(t -> t.rate().group())).toList();
            for (VatRateTotal total : totals) {
                leaf("P_13_" + total.rate().group(), amount(total.netAmount()));
                leaf("P_14_" + total.rate().group(), amount(total.vatAmount()));
            }
            leaf("P_15", amount(inv.grossTotal()));

            // Adnotacje dla zwykłej sprzedaży krajowej: bez metody kasowej, samofakturowania, odwrotnego
            // obciążenia, MPP, zwolnienia, nowych środków transportu, procedury uproszczonej i marży.
            open("Adnotacje");
            leaf("P_16", "2");
            leaf("P_17", "2");
            leaf("P_18", "2");
            leaf("P_18A", "2");
            open("Zwolnienie");
            leaf("P_19N", "1");
            close();
            open("NoweSrodkiTransportu");
            leaf("P_22N", "1");
            close();
            leaf("P_23", "2");
            open("PMarzy");
            leaf("P_PMarzyN", "1");
            close();
            close();

            leaf("RodzajFaktury", "VAT");
            List<Line> lines = inv.lines();
            for (int i = 0; i < lines.size(); i++) {
                Line line = lines.get(i);
                open("FaWiersz");
                leaf("NrWierszaFa", Integer.toString(i + 1));
                leaf("UU_ID", line.lineId());
                leaf("P_7", line.description());
                leaf("P_8A", line.unit());
                leaf("P_8B", decimal(line.quantity(), 0));
                leaf("P_9A", decimal(line.unitNetPrice(), 2));
                leaf("P_11", amount(line.netAmount()));
                leaf("P_12", line.rate().xmlCode());
                close();
            }
            close();
        }

        private void address(Address address) throws XMLStreamException {
            if (address == null) {
                return;
            }
            open("Adres");
            leaf("KodKraju", address.countryCode());
            leaf("AdresL1", address.line1());
            leaf("AdresL2", address.line2());
            close();
        }

        private void open(String name) throws XMLStreamException {
            newline();
            xml.writeStartElement(NAMESPACE, name);
            depth++;
        }

        private void close() throws XMLStreamException {
            depth--;
            newline();
            xml.writeEndElement();
        }

        /** Element tekstowy; null pomija element opcjonalny. Znaki specjalne XML escapuje StAX. */
        private void leaf(String name, String value) throws XMLStreamException {
            if (value == null) {
                return;
            }
            newline();
            xml.writeStartElement(NAMESPACE, name);
            xml.writeCharacters(value);
            xml.writeEndElement();
        }

        private void newline() throws XMLStreamException {
            xml.writeCharacters("\n" + INDENT.repeat(depth));
        }

        private static String yesNo(Boolean value) {
            return Boolean.TRUE.equals(value) ? "1" : "2";
        }

        /** TKwotowy: zawsze dokładnie 2 miejsca po przecinku; walidator wyklucza konieczność zaokrąglenia. */
        private static String amount(BigDecimal value) {
            return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        }

        /** Kanoniczny zapis: bez zbędnych zer, lecz co najmniej {@code minScale} miejsc (np. 2 → „2”, 500 → „500.00”). */
        private static String decimal(BigDecimal value, int minScale) {
            BigDecimal stripped = value.stripTrailingZeros();
            return stripped.setScale(Math.max(minScale, Math.max(0, stripped.scale())), RoundingMode.UNNECESSARY)
                    .toPlainString();
        }
    }
}
