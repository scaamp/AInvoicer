package customer.cap_java.ksef;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import com.sap.cds.Result;
import com.sap.cds.Row;
import com.sap.cds.ql.cqn.CqnSelect;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.UserInfo;

import customer.cap_java.ksef.fa3.Fa3InvoiceXmlService;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;
import customer.cap_java.sap.SapInvoiceRemoteAdapter;
import customer.cap_java.sap.SapInvoiceSnapshot;

/** Offline: rzeczywisty adapter CQN → PartyDataProvider → generator/domena/XSD; tylko RemoteService i DB są mockami. */
class SapFa3PreflightTest {
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-00000000001a");
    private static final Fa3InvoiceXmlService XML = new Fa3InvoiceXmlService();
    private static final KsefFa3Documents DOCUMENTS = new KsefFa3Documents(XML);
    private CqnService remote;
    private PersistenceService db;
    private SapInvoiceSnapshot fixture;
    private SapFa3Preflight harness;
    private UserInfo user;
    private MockEnvironment environment;

    @BeforeEach void setup() {
        remote = mock(CqnService.class);
        db = mock(PersistenceService.class);
        fixture = SapInvoiceTestData.invoice(ID, h -> h.put("InvoiceNumber", "AINV-E2E-01A-OFFLINE"));
        user = UserInfo.create().setName("offline-operator").setIsAuthenticated(true).addRole("KsefOperator")
                .setAttributeValues("taxpayerNips", List.of(SapInvoiceTestData.SELLER_NIP))
                .setAttributeValues("companyCodes", List.of("1000"));
        environment = new MockEnvironment()
                .withProperty("ainv.ksef.enabled", "false")
                .withProperty("ainv.sap-write.enabled", "false")
                .withProperty("ainv.ksef.party-data.sellers.1234567802.country-code", "PL")
                .withProperty("ainv.ksef.party-data.sellers.1234567802.line1", "ul. Syntetyczna 1")
                .withProperty("ainv.ksef.party-data.sellers.1234567802.line2", "00-001 Warszawa")
                .withProperty("ainv.ksef.party-data.buyers.9876543210.jst-subordinate-unit", "false")
                .withProperty("ainv.ksef.party-data.buyers.9876543210.vat-group-member", "false");
        harness = harness();
        doReturn(result(List.of())).when(db).run(any(CqnSelect.class));
        remoteReturns(fixture, fixture);
    }

    @Test void generatesValidatedBytesWithTransportOffAndNoSideEffects() {
        var before = fixture;
        var evidence = harness.run(approval(), user);
        var document = evidence.document();
        assertEquals("false", environment.getProperty("ainv.ksef.enabled"));
        assertEquals("false", environment.getProperty("ainv.sap-write.enabled"));
        assertEquals(KsefInvoiceSnapshotRegistry.sha256Hex(document.xml()), document.sha256Hex());
        assertEquals(document.xml().length, document.byteLength());
        assertEquals(document.sha256Hex(), XML.verify(document.xml()).sha256Hex());
        String xml = new String(document.xml(), StandardCharsets.UTF_8);
        assertTrue(xml.contains("<P_13_1>900.00</P_13_1>"));
        assertTrue(xml.contains("<P_14_1>207.00</P_14_1>"));
        assertTrue(xml.contains("<P_15>1107.00</P_15>"));
        assertEquals(before, fixture);
        assertEquals(SapInvoiceTestData.VERSION.toString(), evidence.headerVersion());
        // Każde wywołanie zależności musi być SELECT. INSERT/UPDATE/DELETE i inne API nie przejdą tej asercji.
        verify(remote, times(6)).run(any(CqnSelect.class));
        verify(db, times(4)).run(any(CqnSelect.class));
        verifyNoMoreInteractions(remote, db);
        // Tylko metadane syntetycznego fixture; XML pozostaje w pamięci, bez pliku nawet w target/.
        System.out.println("E2E-01A OFFLINE FIXTURE: XSD=PASS DOMAIN=PASS bytes=" + document.byteLength()
                + " sha256=" + document.sha256Hex());
    }

    @Test void missingInvoiceStopsWithoutWrites() {
        doReturn(result(List.of())).when(remote).run(any(CqnSelect.class));
        fails("PREFLIGHT_NOT_FOUND");
        verify(remote).run(any(CqnSelect.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SellerNip", "CompanyCode", "KsefNumber", "InvoiceStatus", "LocalLastChangedAt"})
    void rejectsForeignContextRecordedInvoiceWrongStatusAndChangedHeader(String field) {
        Map<String, String> values = Map.of("SellerNip", "1111111111", "CompanyCode", "2000",
                "KsefNumber", "TEST-ALREADY-RECORDED", "InvoiceStatus", "CANCELLED",
                "LocalLastChangedAt", "2026-11-15T10:00:00.1234567Z");
        var altered = header(h -> h.put(field, values.get(field)));
        remoteReturns(altered, altered);
        String expected = Map.of("SellerNip", "PREFLIGHT_FOREIGN_NIP", "CompanyCode", "PREFLIGHT_FOREIGN_COMPANY",
                "KsefNumber", "PREFLIGHT_ALREADY_RECORDED", "InvoiceStatus", "PREFLIGHT_BUSINESS_STATUS",
                "LocalLastChangedAt", "PREFLIGHT_VERSION_CHANGED").get(field);
        fails(expected);
    }

    @Test void rejectsUnsupportedVat() {
        remoteReturns(item(i -> { i.put("TaxRate", new BigDecimal("0.00")); i.put("TaxCategory", "ZW"); }), fixture);
        assertThrows(KsefFailure.class, () -> harness.run(approval(), user));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NetAmount", "TaxAmount", "GrossAmount"})
    void rejectsIncorrectHeaderTotals(String field) {
        remoteReturns(header(h -> h.put(field, new BigDecimal("0.01"))), fixture);
        assertThrows(KsefFailure.class, () -> harness.run(approval(), user));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GrossAmount", "CurrencyCode", "InvoiceUUID"})
    void rejectsInconsistentItemData(String field) {
        remoteReturns(item(i -> i.put(field, Map.of("GrossAmount", "1.00", "CurrencyCode", "EUR",
                "InvoiceUUID", "00000000-0000-4000-8000-000000000099").get(field))), fixture);
        fails(Map.of("GrossAmount", "PREFLIGHT_ITEM_GROSS", "CurrencyCode", "PREFLIGHT_ITEM_CURRENCY",
                "InvoiceUUID", "PREFLIGHT_ITEM_SCOPE").get(field));
    }

    @Test void missingPartyDataStopsBeforeGeneration() {
        environment = new MockEnvironment();
        harness = harness();
        fails("PREFLIGHT_PARTY_DATA_MISSING");
    }

    @Test void missingExplicitBuyerFlagsAreRejected() {
        environment.setProperty("ainv.ksef.party-data.buyers.9876543210.vat-group-member", "");
        harness = harness();
        assertThrows(KsefFailure.class, () -> harness.run(approval(), user));
    }

    @Test void invalidXsdBytesAreRejected() {
        var valid = harness.run(approval(), user).document();
        byte[] invalid = new String(valid.xml(), StandardCharsets.UTF_8)
                .replace("<KodWaluty>PLN</KodWaluty>", "<KodWaluty>INVALID</KodWaluty>").getBytes(StandardCharsets.UTF_8);
        assertThrows(KsefFailure.class, () -> DOCUMENTS.verify(invalid));
    }

    @Test void existingReservationBlocksBeforeSapRead() {
        doReturn(result(List.of(Map.of("operationId", ID.toString())))).when(db).run(any(CqnSelect.class));
        fails("PREFLIGHT_RESERVED");
        verifyNoInteractions(remote);
    }

    @Test void anyPreviousSendBlocksEvenWithoutReservation() {
        doReturn(result(List.of()), result(List.of(Map.of("ID", ID.toString())))).when(db).run(any(CqnSelect.class));
        fails("PREFLIGHT_PREVIOUS_SEND");
        verifyNoInteractions(remote);
    }

    @Test void detectsReservationAppearingDuringDryRun() {
        doReturn(result(List.of()), result(List.of()),
                result(List.of(Map.of("operationId", ID.toString())))).when(db).run(any(CqnSelect.class));
        fails("PREFLIGHT_RESERVED");
    }

    @Test void changedItemVersionIsRejected() {
        remoteReturns(item(i -> i.put("LocalLastChangedAt", Instant.parse("2026-11-15T10:00:00Z"))), fixture);
        fails("PREFLIGHT_VERSION_CHANGED");
    }

    @Test void documentChangingDuringReadIsRejected() {
        remoteReturns(fixture, header(h -> h.put("LocalLastChangedAt", Instant.parse("2026-11-15T10:00:00Z"))));
        fails("PREFLIGHT_VERSION_CHANGED_DURING_READ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "no-role", "foreign-nip", "foreign-company", "ambiguous"})
    void missingRightsStopBeforeAnyRead(String variant) {
        var denied = user.copy();
        switch (variant) {
            case "anonymous" -> denied.setIsAuthenticated(false);
            case "no-role" -> denied = UserInfo.create().setIsAuthenticated(true);
            case "foreign-nip" -> denied.setAttributeValues("taxpayerNips", List.of("1111111111"));
            case "foreign-company" -> denied.setAttributeValues("companyCodes", List.of("2000"));
            case "ambiguous" -> {
                denied.setAttributeValues("taxpayerNips", List.of(SapInvoiceTestData.SELLER_NIP, "1111111111"));
                denied.setAttributeValues("companyCodes", List.of("1000", "2000"));
            }
            default -> fail("unknown test variant");
        }
        var finalDenied = denied;
        assertThrows(IllegalStateException.class, () -> harness.run(approval(), finalDenied));
        verifyNoInteractions(remote, db);
    }

    private SapFa3Preflight harness() {
        return new SapFa3Preflight(new SapInvoiceRemoteAdapter(remote, null), db,
                new KsefPartyDataProvider(environment, Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC)), DOCUMENTS);
    }

    private SapFa3Preflight.ApprovedInvoice approval() {
        return new SapFa3Preflight.ApprovedInvoice(ID, SapInvoiceTestData.SELLER_NIP, "1000",
                KsefInvoiceSnapshotRegistry.headerVersion(fixture.header()),
                KsefInvoiceSnapshotRegistry.itemsFingerprint(fixture.items()));
    }

    private void fails(String code) {
        assertEquals(code, assertThrows(IllegalStateException.class, () -> harness.run(approval(), user)).getMessage());
    }

    private SapInvoiceSnapshot header(Consumer<Map<String, Object>> change) {
        var h = new LinkedHashMap<>(fixture.header());
        change.accept(h);
        return new SapInvoiceSnapshot(h, fixture.items(), fixture.references());
    }

    private SapInvoiceSnapshot item(Consumer<Map<String, Object>> change) {
        var i = new LinkedHashMap<>(fixture.items().getFirst());
        change.accept(i);
        return new SapInvoiceSnapshot(fixture.header(), List.of(i), fixture.references());
    }

    private void remoteReturns(SapInvoiceSnapshot first, SapInvoiceSnapshot second) {
        doReturn(result(List.of(first.header())), result(first.items()), result(first.references()),
                result(List.of(second.header())), result(second.items()), result(second.references()))
                .when(remote).run(any(CqnSelect.class));
    }

    private static Result result(List<Map<String, Object>> rows) {
        var result = mock(Result.class);
        List<Row> data = new ArrayList<>();
        rows.forEach(r -> {
            var row = mock(Row.class);
            when(row.size()).thenReturn(r.size());
            when(row.entrySet()).thenReturn(r.entrySet());
            when(row.get(any())).thenAnswer(call -> r.get(call.getArgument(0)));
            data.add(row);
        });
        when(result.list()).thenReturn(data);
        when(result.first()).thenReturn(data.stream().findFirst());
        return result;
    }
}
