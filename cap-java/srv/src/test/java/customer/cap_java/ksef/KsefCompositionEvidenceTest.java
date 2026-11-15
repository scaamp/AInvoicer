package customer.cap_java.ksef;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import customer.cap_java.ksef.fa3.Fa3Invoice.Address;
import customer.cap_java.ksef.fa3.SapInvoiceFa3Mapper.PartyData;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;
import customer.cap_java.sap.SapInvoiceSnapshot;

/** Odcisk pełnej obserwacji wykrywa również zmiany bez aktualizacji timestampu dzieci. */
class KsefCompositionEvidenceTest {
    private final SapInvoiceSnapshot sap = SapInvoiceTestData.outbound(UUID.randomUUID());
    private final PartyData party = new PartyData(Instant.parse("2026-11-15T10:00:00Z"), new Address("PL", "Testowa 1", null), null, false, false);

    @ParameterizedTest
    @ValueSource(strings = {"Description", "InvoiceUUID", "ItemUUID", "ItemNo", "MaterialId", "Quantity", "UnitCode", "UnitPrice",
            "NetAmount", "TaxRate", "TaxCategory", "TaxAmount", "GrossAmount", "CurrencyCode", "LocalLastChangedAt"})
    void everyItemFieldChangesObservation(String field) {
        var item = new LinkedHashMap<>(sap.items().get(0)); item.put(field, "changed");
        var changed = new SapInvoiceSnapshot(sap.header(), List.of(item), sap.references());
        assertNotEquals(hash(sap, party), hash(changed, party));
        if (!field.equals("ItemUUID") && !field.equals("LocalLastChangedAt")) {
            assertEquals(KsefInvoiceSnapshotRegistry.itemsFingerprint(sap.items()),
                    KsefInvoiceSnapshotRegistry.itemsFingerprint(changed.items()), "Legacy fingerprint nie dowodzi równości wartości");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"InvoiceStatus", "InvoiceNumber", "SellerName", "BuyerName", "CompanyCode", "KsefNumber", "LocalLastChangedAt"})
    void headerFieldsChangeObservationWithoutDependingOnEtag(String field) {
        var header = new LinkedHashMap<>(sap.header()); header.put(field, "changed");
        assertNotEquals(hash(sap, party), hash(new SapInvoiceSnapshot(header, sap.items(), sap.references()), party));
    }

    @Test void referencesAdditionRemovalAndChangesAreCovered() {
        var ref = Map.<String, Object>of("RefUUID", UUID.randomUUID().toString(), "SourceDocNo", "one");
        var a = new SapInvoiceSnapshot(sap.header(), sap.items(), List.of(ref));
        var b = new SapInvoiceSnapshot(sap.header(), sap.items(), List.of(Map.of("RefUUID", ref.get("RefUUID"), "SourceDocNo", "two")));
        assertNotEquals(hash(sap, party), hash(a, party));
        assertNotEquals(hash(a, party), hash(b, party));
        assertNotEquals(hash(sap, party), hash(new SapInvoiceSnapshot(sap.header(), List.of(), List.of()), party));
    }

    @Test void partyValuesChangeObservationButGenerationTimeDoesNotApproveNewBytes() {
        assertEquals(hash(sap, party), hash(sap, new PartyData(party.createdAt().plusSeconds(1), party.sellerAddress(), null, false, false)));
        assertNotEquals(hash(sap, party), hash(sap, new PartyData(party.createdAt(), new Address("PL", "Testowa 2", null), null, false, false)));
        assertNotEquals(hash(sap, party), hash(sap, new PartyData(party.createdAt(), party.sellerAddress(), new Address("PL", "Buyer 1", null), false, false)));
        assertNotEquals(hash(sap, party), hash(sap, new PartyData(party.createdAt(), party.sellerAddress(), null, true, false)));
        assertNotEquals(hash(sap, party), hash(sap, new PartyData(party.createdAt(), party.sellerAddress(), null, false, true)));
    }

    @Test void rowAndMapOrderDoNotChangeObservationAndDuplicatesRemainVisible() {
        var other = new LinkedHashMap<>(sap.items().get(0)); other.put("ItemUUID", UUID.randomUUID().toString());
        var a = new SapInvoiceSnapshot(sap.header(), List.of(sap.items().get(0), other), List.of());
        var b = new SapInvoiceSnapshot(new java.util.TreeMap<>(sap.header()), List.of(other, sap.items().get(0)), List.of());
        assertEquals(hash(a, party), hash(b, party));
        assertNotEquals(hash(sap, party), hash(new SapInvoiceSnapshot(sap.header(), List.of(sap.items().get(0), sap.items().get(0)), List.of()), party));
    }

    private String hash(SapInvoiceSnapshot invoice, PartyData data) { return KsefCompositionEvidence.fingerprint(invoice, data); }
}
