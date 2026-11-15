package customer.cap_java.ksef;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.UserInfo;

import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;
import customer.cap_java.sap.SapInvoiceRemoteAdapter;
import customer.cap_java.sap.SapKsefResultWriteAdapter;

/** Rzeczywista lokalna H2: SELECT harnessu widzi blokady i nie tworzy rekordów; wszystkie transporty/tło OFF. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "ainv.ksef.enabled=false", "ainv.sap-write.enabled=false", "ainv.sap-write.sweeper.enabled=false",
        "ainv.ksef.inbound.enabled=false", "ainv.ksef.inbound.scheduler.enabled=false",
        "ainv.ksef.party-data.sellers.1234567802.country-code=PL",
        "ainv.ksef.party-data.sellers.1234567802.line1=ul. Syntetyczna 1",
        "ainv.ksef.party-data.buyers.9876543210.jst-subordinate-unit=false",
        "ainv.ksef.party-data.buyers.9876543210.vat-group-member=false",
        "cds.outbox.services.KsefSubmissionOutbox.startCollector=false",
        "cds.outbox.services.SapResultOutbox.startCollector=false",
        "cds.outbox.persistent.scheduler.allTenantsTask.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:sap-fa3-preflight;DB_CLOSE_DELAY=-1" })
@ActiveProfiles("test")
class SapFa3PreflightPersistenceTest {
    @Autowired PersistenceService db;
    @Autowired KsefPartyDataProvider parties;
    @Autowired KsefFa3Documents documents;
    @MockitoBean SapInvoiceRemoteAdapter sap;
    @MockitoBean KsefOnlineSession session;
    @MockitoBean KsefAuthentication authentication;
    @MockitoBean KsefDocumentStore store;
    @MockitoBean SapKsefResultWriteAdapter sapWrite;
    private SapFa3Preflight harness;
    private SapFa3Preflight.ApprovedInvoice approved;
    private UserInfo user;

    @BeforeEach void setup() {
        UUID id = UUID.randomUUID();
        var snapshot = SapInvoiceTestData.outbound(id);
        when(sap.readInvoice(id)).thenReturn(Optional.of(snapshot));
        approved = new SapFa3Preflight.ApprovedInvoice(id, SapInvoiceTestData.SELLER_NIP, "1000",
                KsefInvoiceSnapshotRegistry.headerVersion(snapshot.header()),
                KsefInvoiceSnapshotRegistry.itemsFingerprint(snapshot.items()));
        user = UserInfo.create().setIsAuthenticated(true).addRole("KsefOperator")
                .setAttributeValues("taxpayerNips", List.of(SapInvoiceTestData.SELLER_NIP))
                .setAttributeValues("companyCodes", List.of("1000"));
        harness = new SapFa3Preflight(sap, db, parties, documents);
    }

    @Test void dryRunLeavesEveryBusinessAndOutboxTableUnchanged() {
        var before = rows();
        assertTrue(harness.run(approved, user).document().byteLength() > 0);
        assertEquals(before, rows());
        verify(sap, times(2)).readInvoice(approved.id());
        verifyNoMoreInteractions(sap);
        verifyNoInteractions(authentication, session, store, sapWrite);
    }

    @Test void actualReservationBlocksWithoutSapReadOrNewRecords() {
        // Wyłącznie fixture w lokalnej pamięciowej H2; bez operacji/workera i bez SAP WRITE.
        db.run(Insert.into(KsefSendRequestHandler.RESERVATIONS).entry(Map.of(
                "invoiceUUID", approved.id().toString(), "environment", "TEST", "taxpayerNip", approved.sellerNip(),
                "companyCode", "1000", "operationId", UUID.randomUUID().toString(), "attempt", 1)));
        assertBlocked("PREFLIGHT_RESERVED");
    }

    @Test void actualLegacySendBlocksWithoutReservation() {
        // Bierny rekord historyczny REJECTED: preflight pierwszego SEND nie interpretuje go jako prawa do retry.
        db.run(Insert.into(KsefSubmissionWorker.OPERATIONS).entry(Map.of(
                "ID", UUID.randomUUID().toString(), "sapDocumentRef", approved.id().toString(),
                "taxpayerNip", approved.sellerNip(), "companyCode", "1000", "environment", "TEST",
                "direction", "OUTBOUND", "operationType", "SEND_INVOICE", "status", "REJECTED",
                "idempotencyKey", UUID.randomUUID().toString())));
        assertBlocked("PREFLIGHT_PREVIOUS_SEND");
    }

    private void assertBlocked(String code) {
        var before = rows();
        assertEquals(code, assertThrows(IllegalStateException.class, () -> harness.run(approved, user)).getMessage());
        assertEquals(before, rows());
        verifyNoInteractions(sap, authentication, session, store, sapWrite);
    }

    private Map<String, Long> rows() {
        var counts = new LinkedHashMap<String, Long>();
        for (var entity : List.of(KsefSubmissionWorker.OPERATIONS, KsefSendRequestHandler.RESERVATIONS,
                KsefSubmissionWorker.SNAPSHOTS, KsefSubmissionWorker.SUBMISSIONS,
                "customer.cap_java.SapResultWrites", "cds.outbox.Messages")) {
            counts.put(entity, db.run(Select.from(entity)).rowCount());
        }
        return counts;
    }
}
