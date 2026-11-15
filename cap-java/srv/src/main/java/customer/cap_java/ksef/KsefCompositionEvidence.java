package customer.cap_java.ksef;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import customer.cap_java.ksef.fa3.SapInvoiceFa3Mapper.PartyData;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;
import customer.cap_java.sap.SapInvoiceSnapshot;

/** Pełny odcisk obserwacji READ, nie wersja atomowa SAP ani zatwierdzenie do SEND. Nie zmienia starego fingerprintu DB. */
public final class KsefCompositionEvidence {
    private KsefCompositionEvidence() { }

    /** Obejmuje wszystkie odczytane pola i PartyData; createdAt jest czasem generowania, a nie wersją konfiguracji. */
    public static String fingerprint(SapInvoiceSnapshot sap, PartyData party) {
        String value = "SAP-OBSERVATION-v1" + row(sap.header()) + rows(sap.items()) + rows(sap.references())
                + address(party.sellerAddress()) + address(party.buyerAddress())
                + token(party.buyerJstSubordinateUnit()) + token(party.buyerVatGroupMember());
        return KsefInvoiceSnapshotRegistry.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String address(customer.cap_java.ksef.fa3.Fa3Invoice.Address address) {
        return address == null ? token(null) : token(address.countryCode()) + token(address.line1()) + token(address.line2());
    }

    private static String rows(List<Map<String, Object>> rows) {
        return token(rows.stream().map(KsefCompositionEvidence::row).sorted().map(KsefCompositionEvidence::token)
                .collect(java.util.stream.Collectors.joining()));
    }

    private static String row(Map<String, Object> row) {
        var result = new StringBuilder();
        new TreeMap<>(row).forEach((key, value) -> result.append(token(key)).append(token(value)));
        return token(result.toString());
    }

    private static String token(Object value) {
        if (value == null) { return "N;"; }
        String text = value instanceof BigDecimal d ? d.stripTrailingZeros().toPlainString() : value.toString();
        return text.length() + ":" + text;
    }
}
