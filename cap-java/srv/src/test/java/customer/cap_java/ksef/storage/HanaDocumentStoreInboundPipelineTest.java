package customer.cap_java.ksef.storage;

import static customer.cap_java.ksef.inbound.InboundTestSupport.*;
import static customer.cap_java.ksef.inbound.persistence.InboundPersistence.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.sap.cds.ql.Delete;
import com.sap.cds.ql.Select;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.Application;
import customer.cap_java.ksef.KsefDocumentStore;
import customer.cap_java.ksef.KsefInboundAccess;
import customer.cap_java.ksef.KsefFailure;
import customer.cap_java.ksef.fa3.Fa3SchemaValidator;
import customer.cap_java.ksef.inbound.*;
import customer.cap_java.ksef.inbound.fa3.SecureFa3InvoiceParser;
import customer.cap_java.ksef.inbound.persistence.*;
import customer.cap_java.ksef.inbound.sync.*;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient;

/**
 * KSEF-STOR-04: niezmieniony pipeline INBOUND ({@link PersistentInboundInvoiceSink} + runner/checkpoint)
 * z adapterem {@link HanaKsefDocumentStore} na prywatnej tabeli {@code KsefStoredDocuments} (CAP CQN/H2). Mock wyłącznie SDK KSeF.
 * Nie dowodzi zachowania HANA Cloud LIVE, restartu CF ani aktywacji workera.
 */
@SpringBootTest(classes = Application.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:ksef-stor-04-inbound;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "logging.level.root=WARN" })
@ActiveProfiles("test")
class HanaDocumentStoreInboundPipelineTest {
    static final OffsetDateTime START = OffsetDateTime.parse("2026-11-01T00:00:00Z");
    static final OffsetDateTime HWM = OffsetDateTime.parse("2026-11-09T10:00:00Z");
    static final SyncScope SCOPE = SyncScope.of(CONTEXT, KsefSubjectRole.BUYER);
    static final String STORE = HanaBlobObjectStorageClient.ENTITY;

    @Autowired PersistenceService db;
    @Autowired CdsRuntime runtime;
    InboundPersistence persistence;
    CqnInboundDocumentRepository documents;
    CqnSyncCheckpointRepository checkpoints;
    InvoiceRetrievalService retrieval;
    KSeFClient sdk;
    byte[] xml;

    @BeforeEach void reset() throws Exception {
        persistence = new InboundPersistence(db, runtime);
        documents = new CqnInboundDocumentRepository(persistence, CLOCK);
        checkpoints = new CqnSyncCheckpointRepository(persistence);
        persistence.tx(() -> {
            for (String entity : List.of(GRANTS, CONFLICTS, DOCUMENTS, CHECKPOINTS, LEASES, STORE)) { db.run(Delete.from(entity)); }
            return null;
        });
        sdk = mock(KSeFClient.class);
        retrieval = new InvoiceRetrievalService(inboundClient(sdk), ReadRetryPolicy.noRetry(CLOCK));
        xml = resource("fa3-purchase-synthetic.xml");
        when(sdk.getInvoice(anyString(), anyString())).thenReturn(xml);
        when(sdk.queryInvoiceMetadata(eq(0), anyInt(), any(), any(), any())).thenReturn(response(false, false,
                HWM.toString(), sdkItem(metadataJson(ksefNumber(1), "2026-11-02T09:00:00Z", hashOf(xml)))));
    }

    ContentAddressedDocumentStore root(PersistenceService persistenceService) {
        return HanaKsefDocumentStore.create(persistenceService, runtime, EnumSet.of(KsefEnvironment.TEST),
                HanaKsefDocumentStore.DEFAULT_MAX_DOCUMENT_BYTES, 4L * HanaKsefDocumentStore.DEFAULT_MAX_DOCUMENT_BYTES,
                Duration.ofSeconds(5), CLOCK, d -> { });
    }

    /** Nowa instancja adaptera bez stanu w pamięci (H2: model restartu, nie restart procesu CF). */
    DurableInboundDocumentStore freshStore() { return root(db).forScope(DocumentScope.inbound(KsefEnvironment.TEST)); }

    InvoiceSyncRunner.Status run(KsefDocumentStore storage, CqnSyncCheckpointRepository repository) {
        var sink = new PersistentInboundInvoiceSink(SCOPE, token(), retrieval, documents, storage,
                new SecureFa3InvoiceParser(), new Fa3SchemaValidator());
        return new InvoiceSyncRunner(retrieval, InvoiceSyncWindowPlanner.defaults(CLOCK), repository, CLOCK)
                .runOnce(CONTEXT, KsefSubjectRole.BUYER, token(), START, sink).status();
    }

    List<Map<String, Object>> rows(String entity) {
        return persistence.tx(() -> db.run(Select.from(entity)).list().stream()
                .<Map<String, Object>>map(HashMap<String, Object>::new).toList());
    }

    List<Map<String, Object>> storeRows() {
        return persistence.tx(() -> db.run(Select.from(STORE).columns("environment", "channel", "ownerSegment", "sha256"))
                .list().stream()
                .<Map<String, Object>>map(HashMap<String, Object>::new).toList());
    }

    @Test void syntheticXmlIsStoredInHanaTableVerifiedFinalizedAndReadBack() {
        assertEquals(InvoiceSyncRunner.Status.COMMITTED, run(freshStore(), checkpoints));
        var row = rows(DOCUMENTS).getFirst();
        String sha = KsefInvoiceSnapshotRegistry.sha256Hex(xml);
        assertEquals("ksefdoc:v1:TEST:INBOUND:-:" + sha, row.get("xmlStorageRef"));
        assertEquals("STORED", row.get("ingestionStatus"));
        assertEquals(1, storeRows().size());
        assertEquals(sha, storeRows().getFirst().get("sha256"));
        assertEquals(List.of("TEST", "INBOUND", "-"), List.of(storeRows().getFirst().get("environment"),
                storeRows().getFirst().get("channel"), storeRows().getFirst().get("ownerSegment")));
        assertTrue(new CqnInboundOverviewRepository(persistence).find(SCOPE, 0, 10).getFirst().xmlAvailable());
        assertFalse(new CqnInboundOverviewRepository(persistence).find(SCOPE, 0, 10).getFirst().toString().contains("ksefdoc"));
        assertArrayEquals(xml, freshStore().get((String) row.get("xmlStorageRef")));
        assertEquals(HWM, checkpoints.find(SCOPE).orElseThrow().continuationFrom());
    }

    @Test void crashAfterPutBeforeFinalizationRecoversWithoutSecondRow() {
        var crashed = new AtomicBoolean();
        KsefDocumentStore durable = freshStore();
        KsefDocumentStore crashOnce = new KsefDocumentStore() {
            public String put(byte[] bytes) {
                String reference = durable.put(bytes);
                if (crashed.compareAndSet(false, true)) { throw new SimulatedProcessDeath(); }
                return reference;
            }
            public byte[] get(String reference) { return durable.get(reference); }
        };
        assertThrows(SimulatedProcessDeath.class, () -> run(crashOnce, checkpoints));
        assertTrue(checkpoints.find(SCOPE).isEmpty());
        assertNull(rows(DOCUMENTS).getFirst().get("xmlStorageRef"));
        assertEquals(1, storeRows().size(), "wiersz po put jest bezpiecznym orphan do czasu finalizacji");
        documents = new CqnInboundDocumentRepository(new InboundPersistence(db, runtime), CLOCK);
        assertEquals(InvoiceSyncRunner.Status.COMMITTED, run(freshStore(), new CqnSyncCheckpointRepository(persistence)));
        assertEquals(1, storeRows().size());
        assertEquals(1, rows(GRANTS).size());
        var report = root(db).orphanReport(DocumentScope.inbound(KsefEnvironment.TEST), CapDocumentReferences.collect(db), Duration.ZERO);
        assertTrue(report.orphanReferences().isEmpty());
        assertEquals(1, report.scanned());
    }

    @Test void databaseStorageOutageKeepsCheckpointThenRecovers() {
        PersistenceService down = mock(PersistenceService.class, invocation -> {
            throw new ServiceException(ErrorStatuses.SERVER_ERROR, "hana unavailable");
        });
        var error = assertThrows(IllegalStateException.class, () -> run(root(down).forScope(DocumentScope.inbound(KsefEnvironment.TEST)), checkpoints));
        assertEquals("KSEF_INBOUND_DOCUMENT_STORE_FAILED", error.getMessage());
        assertTrue(checkpoints.find(SCOPE).isEmpty());
        assertEquals("STORAGE_FAILED", rows(DOCUMENTS).getFirst().get("ingestionStatus"));
        assertTrue(rows(GRANTS).isEmpty());
        assertTrue(storeRows().isEmpty());
        assertEquals(InvoiceSyncRunner.Status.COMMITTED, run(freshStore(), checkpoints));
        assertEquals("STORED", rows(DOCUMENTS).getFirst().get("ingestionStatus"));
        assertEquals(1, storeRows().size());
    }

    @Test void oneSharedRowServesSeveralGrantsAndOutboundViewIsDenied() {
        run(freshStore(), checkpoints);
        var seller = new KsefTaxpayerContext(KsefEnvironment.TEST, SELLER_NIP);
        var sellerMetadata = new InvoiceMetadataMapper().map(seller, KsefSubjectRole.SELLER,
                sdkItem(metadataJson(ksefNumber(1), "2026-11-02T09:00:00Z", hashOf(xml))));
        new PersistentInboundInvoiceSink(SyncScope.of(seller, KsefSubjectRole.SELLER),
                InboundAccessToken.issuedFor(seller, FAKE_JWT), retrieval, documents, freshStore(),
                new SecureFa3InvoiceParser(), new Fa3SchemaValidator()).accept(sellerMetadata);
        assertEquals(2, rows(GRANTS).size());
        assertEquals(1, storeRows().size());
        assertTrue(new CqnInboundOverviewRepository(persistence)
                .find(new SyncScope(KsefEnvironment.TEST, OTHER_NIP, KsefSubjectRole.BUYER), 0, 10).isEmpty());
        String reference = (String) rows(DOCUMENTS).getFirst().get("xmlStorageRef");
        var outboundView = root(db).forScope(DocumentScope.outbound(KsefEnvironment.TEST, CONTEXT_NIP));
        assertEquals("KSEF_DOCUMENT_ACCESS_DENIED", assertThrows(KsefFailure.class, () -> outboundView.get(reference)).getMessage());
    }

    /**
     * KSEF-IN-05 (B2) — OFFLINE H2, nie HANA: pełny przebieg {@link InboundSynchronization} (lease + fake auth/SDK)
     * z prawdziwym adapterem CQN {@link HanaKsefDocumentStore}. Dokładne bajty z BOM i CRLF, SHA-256, FA(3) PARSED,
     * metadane i grant (contextNip, BUYER), checkpoint po trwałym zapisie, „restart” (nowe instancje repozytoriów,
     * store i koordynatora) z utraconym checkpointem → idempotentny drugi przebieg bez drugiego GET XML,
     * bez drugiego wiersza i bez UPDATE magazynu.
     */
    @Test void offlineH2SynchronizationKeepsBomCrlfAndIsIdempotentAcrossRestart() throws Exception {
        byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        // CRLF budowane jawnie: końce linii fixture zależą od checkoutu Git (autocrlf na Windows vs LF w CI).
        byte[] crlf = new String(xml, java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\n", "\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] original = new byte[bom.length + crlf.length];
        System.arraycopy(bom, 0, original, 0, bom.length);
        System.arraycopy(crlf, 0, original, bom.length, crlf.length);
        assertTrue(new String(original, java.nio.charset.StandardCharsets.UTF_8).contains("\r\n"), "payload ma CRLF");
        when(sdk.getInvoice(anyString(), anyString())).thenReturn(original);
        when(sdk.queryInvoiceMetadata(eq(0), anyInt(), any(), any(), any())).thenReturn(response(false, false,
                HWM.toString(), sdkItem(metadataJson(ksefNumber(1), "2026-11-02T09:00:00Z", hashOf(original)))));

        var first = synchronization(CLOCK, freshStore()).runOnce();
        assertEquals(InvoiceSyncRunner.Status.COMMITTED, first.orElseThrow().status());
        var row = rows(DOCUMENTS).getFirst();
        String sha = KsefInvoiceSnapshotRegistry.sha256Hex(original);
        assertEquals(sha, row.get("sha256"), "hash z metadanych = SHA-256 oryginalnych bajtów z BOM");
        assertEquals("ksefdoc:v1:TEST:INBOUND:-:" + sha, row.get("xmlStorageRef"));
        assertEquals(List.of("STORED", "PARSED", "VERIFIED"),
                List.of(row.get("ingestionStatus"), row.get("parseStatus"), row.get("integrityStatus")));
        assertEquals((long) original.length, ((Number) row.get("sizeBytes")).longValue());
        assertEquals(List.of(ksefNumber(1), "FZ/2026/11/0001", SELLER_NIP, "TEST"),
                List.of(row.get("ksefNumber"), row.get("invoiceNumber"), row.get("sellerNip"), row.get("environment")));
        assertNotNull(row.get("grossAmount"), "kwota autorytatywna z XML");
        var grant = rows(GRANTS).getFirst();
        assertEquals(List.of(row.get("ID"), CONTEXT_NIP, "BUYER"),
                List.of(grant.get("documentId"), grant.get("contextNip"), grant.get("subjectRole")));
        assertEquals(HWM, checkpoints.find(SCOPE).orElseThrow().continuationFrom());
        Object storedAt = persistence.tx(() -> db.run(Select.from(STORE).columns("storedAt")).single().get("storedAt"));

        // „Restart” po utracie checkpointu: nowe instancje, okno odtwarzane od initialFrom, zegar +1 h (koniec cooldown).
        persistence.tx(() -> db.run(Delete.from(CHECKPOINTS)));
        persistence = new InboundPersistence(db, runtime);
        documents = new CqnInboundDocumentRepository(persistence, CLOCK);
        checkpoints = new CqnSyncCheckpointRepository(persistence);
        var second = synchronization(java.time.Clock.offset(CLOCK, Duration.ofHours(1)), freshStore()).runOnce();
        assertEquals(InvoiceSyncRunner.Status.COMMITTED, second.orElseThrow().status());
        verify(sdk, times(1)).getInvoice(anyString(), anyString());
        assertEquals(1, storeRows().size());
        assertEquals(1, rows(DOCUMENTS).size());
        assertEquals(1, rows(GRANTS).size());
        assertEquals(row.get("xmlStorageRef"), rows(DOCUMENTS).getFirst().get("xmlStorageRef"));
        assertEquals(storedAt, persistence.tx(() -> db.run(Select.from(STORE).columns("storedAt")).single().get("storedAt")),
                "brak UPDATE wiersza magazynu");
        assertArrayEquals(original, freshStore().get((String) row.get("xmlStorageRef")), "odczyt po restarcie: te same bajty");
        // Drugi put tych samych bajtów: ta sama referencja, nadal jeden wiersz (idempotencja adaptera).
        assertEquals(row.get("xmlStorageRef"), freshStore().put(original.clone()));
        assertEquals(1, storeRows().size());
    }

    InboundSynchronization synchronization(java.time.Clock clock, KsefDocumentStore storage) {
        var access = mock(KsefInboundAccess.class);
        var session = mock(KsefInboundAccess.AccessSession.class);
        when(access.open()).thenReturn(session);
        when(session.token()).thenReturn(token());
        return new InboundSynchronization(SCOPE, START, clock, access, retrieval, documents, checkpoints,
                new CqnInboundSyncLeaseRepository(persistence, clock), storage, new Fa3SchemaValidator());
    }

    static final class SimulatedProcessDeath extends Error { }
}
