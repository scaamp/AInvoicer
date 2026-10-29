package customer.cap_java.ksef.fa3;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import customer.cap_java.ksef.fa3.Fa3Invoice.VatRate;
import customer.cap_java.sap.SapInvoiceSnapshot;

/** B5: SapInvoiceSnapshot (struktura RAP H01, dane syntetyczne) → Fa3Invoice → FA(3). Bez wywołań SAP. */
class SapInvoiceFa3MapperTest {
    private static final Fa3InvoiceXmlService SERVICE = new Fa3InvoiceXmlService();
    private final SapInvoiceFa3Mapper mapper = new SapInvoiceFa3Mapper();

    @Test
    void syntheticOutboundSapInvoiceProducesValidFa3() throws Exception {
        Fa3Invoice invoice = mapper.map(Fa3Fixtures.syntheticSdBill(), Fa3Fixtures.partyData());
        Fa3Document doc = SERVICE.prepare(invoice);

        Document dom = Fa3InvoiceXmlServiceTest.parse(doc.xml());
        assertEquals("AINV-TEST-SEED-2026-SD-BILL", Fa3InvoiceXmlServiceTest.text(dom, "P_2"));
        assertEquals("2026-10-07", Fa3InvoiceXmlServiceTest.text(dom, "P_1"));
        assertEquals("2400.00", Fa3InvoiceXmlServiceTest.text(dom, "P_13_1"));
        assertEquals("552.00", Fa3InvoiceXmlServiceTest.text(dom, "P_14_1"));
        assertEquals("2952.00", Fa3InvoiceXmlServiceTest.text(dom, "P_15"));
        assertEquals("szt.", Fa3InvoiceXmlServiceTest.text(dom, "P_8A"));
        // Pozycje w kolejności ItemNo, UU_ID = ItemUUID z SAP; PostingDate nie trafia do P_6.
        assertEquals("Syntetyczna usługa wdrożeniowa", Fa3InvoiceXmlServiceTest.text(dom, "P_7"));
        assertEquals(invoice.lines().get(0).lineId(), Fa3InvoiceXmlServiceTest.text(dom, "UU_ID"));
        assertEquals(0, dom.getElementsByTagNameNS(Fa3XmlWriter.NAMESPACE, "P_6").getLength());
        assertEquals(List.of(new Fa3Invoice.VatRateTotal(VatRate.RATE_23, new BigDecimal("2400.00"),
                new BigDecimal("552.00"))), invoice.vatTotals());
    }

    @Test
    void h01SeedNipsFailChecksumSoSeedNeedsSyntheticFixture() {
        // Mapowanie struktury się udaje, ale NIP-y seeda H01 nie mają poprawnej sumy kontrolnej.
        Fa3Invoice invoice = mapper.map(Fa3Fixtures.h01SeedSdBill(), Fa3Fixtures.partyData());

        var e = assertThrows(Fa3ValidationException.class, () -> SERVICE.prepare(invoice));
        assertEquals(List.of(new Fa3Violation("NIP_INVALID", "seller.nip", null),
                new Fa3Violation("NIP_INVALID", "buyer.nip", null)), e.violations());
    }

    @Test
    void missingPartyDataIsReportedNotDefaulted() {
        Fa3Invoice invoice = mapper.map(Fa3Fixtures.syntheticSdBill(),
                new SapInvoiceFa3Mapper.PartyData(Fa3Fixtures.CREATED_AT, null, null, null, null));

        var codes = assertThrows(Fa3ValidationException.class, () -> SERVICE.prepare(invoice)).violations();
        assertTrue(codes.contains(new Fa3Violation("REQUIRED", "seller.address", null)));
        assertTrue(codes.contains(new Fa3Violation("REQUIRED", "buyer.jstSubordinateUnit", null)));
        assertTrue(codes.contains(new Fa3Violation("REQUIRED", "buyer.vatGroupMember", null)));
    }

    @Test
    void unsupportedSapValuesAreMappingErrors() {
        var inbound = withHeader("Direction", "INBOUND");
        assertMapping(inbound, "MAPPING_UNSUPPORTED", "SAP.Header.Direction");

        var eur = withHeader("CurrencyCode", "EUR");
        assertMapping(eur, "MAPPING_UNSUPPORTED", "SAP.Header.CurrencyCode");

        var kg = withItem("UnitCode", "KG");
        assertMapping(kg, "MAPPING_UNSUPPORTED", "SAP.Item[000010].UnitCode");

        var exempt = withItem("TaxCategory", "ZW");
        assertMapping(exempt, "MAPPING_UNSUPPORTED", "SAP.Item[000010].TaxRate");
    }

    @Test
    void sapHeaderTotalsMustMatchItems() {
        var e = assertThrows(Fa3ValidationException.class,
                () -> mapper.map(withHeader("TaxAmount", new BigDecimal("553.00")), Fa3Fixtures.partyData()));
        assertEquals(List.of(new Fa3Violation("SAP_HEADER_TOTAL_MISMATCH", "SAP.Header.TaxAmount",
                "header 553.00, items 552.00")), e.violations());
    }

    private void assertMapping(SapInvoiceSnapshot sap, String code, String path) {
        var e = assertThrows(Fa3ValidationException.class, () -> mapper.map(sap, Fa3Fixtures.partyData()));
        assertTrue(e.violations().stream().anyMatch(v -> v.code().equals(code) && v.path().equals(path)),
                e.violations().toString());
    }

    private static SapInvoiceSnapshot withHeader(String field, Object value) {
        var sap = Fa3Fixtures.syntheticSdBill();
        Map<String, Object> header = new LinkedHashMap<>(sap.header());
        header.put(field, value);
        return new SapInvoiceSnapshot(header, sap.items(), sap.references());
    }

    /** Zmienia pole pozycji 000010 (w fixture druga na liście). */
    private static SapInvoiceSnapshot withItem(String field, Object value) {
        var sap = Fa3Fixtures.syntheticSdBill();
        var items = sap.items().stream().map(item -> {
            Map<String, Object> copy = new LinkedHashMap<>(item);
            if ("000010".equals(item.get("ItemNo"))) {
                copy.put(field, value);
            }
            return copy;
        }).toList();
        return new SapInvoiceSnapshot(sap.header(), items, sap.references());
    }
}
