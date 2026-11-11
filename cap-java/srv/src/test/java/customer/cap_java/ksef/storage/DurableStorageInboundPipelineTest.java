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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.sap.cds.ql.Delete;
import com.sap.cds.ql.Select;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.Application;
import customer.cap_java.ksef.KsefDocumentStore;
import customer.cap_java.ksef.KsefFailure;
import customer.cap_java.ksef.fa3.Fa3SchemaValidator;
import customer.cap_java.ksef.inbound.*;
import customer.cap_java.ksef.inbound.fa3.SecureFa3InvoiceParser;
import customer.cap_java.ksef.inbound.persistence.*;
import customer.cap_java.ksef.inbound.sync.*;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient;

/**
 * Pipeline INBOUND na prawdziwym CAP CQN/H2 z adapterem trwałego storage przez HTTP do emulatora Azure Blob:
 * XML → SHA-256 → PUT create-only → odczyt kontrolny → finalizacja CAP → retry/restart → GET → SHA-256.
 * Mock wyłącznie SDK KSeF. Nie dowodzi zachowania HANA Cloud ani usługi SAP Object Store LIVE.
 */
@SpringBootTest(classes = Application.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:durable-storage-inbound;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "logging.level.root=WARN" })
@ActiveProfiles("test")
class DurableStorageInboundPipelineTest {
    static final OffsetDateTime START = OffsetDateTime.parse("2026-11-01T00:00:00Z");
    static final OffsetDateTime HWM = OffsetDateTime.parse("2026-11-09T10:00:00Z");
    static final SyncScope SCOPE = SyncScope.of(CONTEXT, KsefSubjectRole.BUYER);
    static final String PREFIX = "ainvoicer-test";

    @Autowired PersistenceService db;
    @Autowired CdsRuntime runtime;
    InboundPersistence persistence;
    CqnInboundDocumentRepository documents;
    CqnSyncCheckpointRepository checkpoints;
    KSeFClient sdk;
    InvoiceRetrievalService retrieval;
    FakeAzureBlobServer fake;
    ObjectStorageSettings settings;
    byte[] xml;

    @BeforeEach void reset() throws Exception {
        persistence = new InboundPersistence(db, runtime);
        documents = new CqnInboundDocumentRepository(persistence, CLOCK);
        checkpoints = new CqnSyncCheckpointRepository(persistence);
        persistence.tx(() -> {
            for (String entity : List.of(GRANTS, CONFLICTS, DOCUMENTS, CHECKPOINTS, LEASES)) { db.run(Delete.from(entity)); }
            return null;
        });
        sdk = mock(KSeFClient.class);
        retrieval = new InvoiceRetrievalService(inboundClient(sdk), ReadRetryPolicy.noRetry(CLOCK));
        xml = resource("fa3-purchase-synthetic.xml");
        when(sdk.getInvoice(anyString(), anyString())).thenReturn(xml);
        when(sdk.queryInvoiceMetadata(eq(0), anyInt(), any(), any(), any())).thenReturn(response(false, false,
                HWM.toString(), sdkItem(metadataJson(ksefNumber(1), "2026-11-02T09:00:00Z", hashOf(xml)))));
        fake = new FakeAzureBlobServer();
        fake.now = NOW.minus(Duration.ofHours(2)); // obiekty starsze niż karencja audytu orphan
        settings = ObjectStorageSettings.defaults(PREFIX, EnumSet.of(KsefEnvironment.TEST))
                .withLoopbackHttpForTests(Duration.ofSeconds(2));
    }

    @AfterEach void stop() { fake.close(); }

    /** Nowa instancja = nowy proces/instancja CF: wyłącznie VCAP_SERVICES, bez współdzielonego stanu. */
    KsefDocumentStore freshStore() {
        var binding = AzureObjectStoreBinding.fromVcapServices(fake.vcapServices(null), null);
        return new ContentAddressedDocumentStore(new AzureBlobSasClient(binding, settings), settings, binding.access(),
                CLOCK, d -> { }).forScope(DocumentScope.inbound(KsefEnvironment.TEST));
    }

    PersistentInboundInvoiceSink sink(KsefDocumentStore storage) {
        return new PersistentInboundInvoiceSink(SCOPE, token(), retrieval, documents, storage,
                new SecureFa3InvoiceParser(), new Fa3SchemaValidator());
    }

    InvoiceSyncRunner.Status run(KsefDocumentStore storage, CqnSyncCheckpointRepository repository) {
        return new InvoiceSyncRunner(retrieval, InvoiceSyncWindowPlanner.defaults(CLOCK), repository, CLOCK)
                .runOnce(CONTEXT, KsefSubjectRole.BUYER, token(), START, sink(storage)).status();
    }

    List<Map<String, Object>> rows(String entity) {
        return persistence.tx(() -> db.run(Select.from(entity)).list().stream()
                .<Map<String, Object>>map(HashMap<String, Object>::new).toList());
    }

    @Test void syntheticXmlIsStoredVerifiedFinalizedAndReadBackAfterRestart() {
        assertEquals(InvoiceSyncRunner.Status.COMMITTED, run(freshStore(), checkpoints));
        var row = rows(DOCUMENTS).getFirst();
        String sha = KsefInvoiceSnapshotRegistry.sha256Hex(xml);
        assertEquals(sha, row.get("sha256"));
        assertEquals("ksefdoc:v1:TEST:INBOUND:-:" + sha, row.get("xmlStorageRef"));
        assertEquals("STORED", row.get("ingestionStatus"));
        assertEquals(1, fake.created.get());
        assertTrue(new CqnInboundOverviewRepository(persistence).find(SCOPE, 0, 10).getFirst().xmlAvailable());
        assertFalse(new CqnInboundOverviewRepository(persistence).find(SCOPE, 0, 10).getFirst().toString().contains("ksefdoc"));
        byte[] restored = freshStore().get((String) row.get("xmlStorageRef"));
        assertArrayEquals(xml, restored);
        assertEquals(row.get("sha256"), KsefInvoiceSnapshotRegistry.sha256Hex(restored));
        assertTrue(fake.log.stream().noneMatch(l -> l.headers().containsKey("authorization")),
                "brak JWT/Authorization w ruchu do storage");
    }

    @Test void crashAfterStoragePutBeforeDatabaseFinalizationRecoversWithoutSecondObject() {
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
        assertEquals("PENDING", rows(DOCUMENTS).getFirst().get("ingestionStatus"));
        assertNull(rows(DOCUMENTS).getFirst().get("xmlStorageRef"));
        assertEquals(1, fake.blobs.size(), "obiekt po put jest bezpiecznym orphan do czasu finalizacji");
        // "Restart": nowe repozytoria CAP i nowa instancja storage.
        documents = new CqnInboundDocumentRepository(new InboundPersistence(db, runtime), CLOCK);
        assertEquals(InvoiceSyncRunner.Status.COMMITTED, run(freshStore(), new CqnSyncCheckpointRepository(persistence)));
        assertEquals(1, fake.created.get());
        assertTrue(fake.conflicts.get() >= 1, "ponowny put trafił w istniejący obiekt create-only");
        assertEquals(1, rows(DOCUMENTS).size());
        assertEquals(1, rows(GRANTS).size());
        assertArrayEquals(xml, freshStore().get((String) rows(DOCUMENTS).getFirst().get("xmlStorageRef")));
        var report = new ContentAddressedDocumentStore(new AzureBlobSasClient(
                AzureObjectStoreBinding.fromVcapServices(fake.vcapServices(null), null), settings), settings,
                AzureObjectStoreBinding.Access.READ_WRITE, CLOCK, d -> { })
                .orphanReport(DocumentScope.inbound(KsefEnvironment.TEST), CapDocumentReferences.collect(db), Duration.ofHours(1));
        assertTrue(report.orphanReferences().isEmpty(), "po recovery obiekt jest referencjonowany przez CAP");
        assertEquals(1, report.scanned());
        assertEquals(0, report.withinGracePeriod(), "obiekt oceniony, nie pominięty karencją");
    }

    @Test void orphanFromAbandonedFinalizationIsReportedButNotDeleted() {
        var orphanStore = freshStore();
        String orphan = orphanStore.put("<Faktura>abandoned</Faktura>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(InvoiceSyncRunner.Status.COMMITTED, run(freshStore(), checkpoints));
        var report = new ContentAddressedDocumentStore(new AzureBlobSasClient(
                AzureObjectStoreBinding.fromVcapServices(fake.vcapServices(null), null), settings), settings,
                AzureObjectStoreBinding.Access.READ_WRITE, CLOCK, d -> { })
                .orphanReport(DocumentScope.inbound(KsefEnvironment.TEST), CapDocumentReferences.collect(db), Duration.ofHours(1));
        assertEquals(List.of(orphan), report.orphanReferences());
        assertEquals(2, fake.blobs.size());
    }

    @Test void storageOutageKeepsCheckpointAndRecordsFailureThenRecovers() {
        fake.failNextRequests.set(100);
        var error = assertThrows(IllegalStateException.class, () -> run(freshStore(), checkpoints));
        assertEquals("KSEF_INBOUND_DOCUMENT_STORE_FAILED", error.getMessage());
        assertFalse(error.toString().contains(FakeAzureBlobServer.SIGNATURE));
        assertTrue(checkpoints.find(SCOPE).isEmpty());
        assertEquals("STORAGE_FAILED", rows(DOCUMENTS).getFirst().get("ingestionStatus"));
        assertTrue(rows(GRANTS).isEmpty());
        fake.failNextRequests.set(0);
        assertEquals(InvoiceSyncRunner.Status.COMMITTED, run(freshStore(), checkpoints));
        assertEquals("STORED", rows(DOCUMENTS).getFirst().get("ingestionStatus"));
        assertEquals(HWM, checkpoints.find(SCOPE).orElseThrow().continuationFrom());
    }

    @Test void revokedStorageCredentialsFailClosedWithoutCheckpoint() {
        fake.revoked = true;
        assertThrows(IllegalStateException.class, () -> run(freshStore(), checkpoints));
        assertTrue(checkpoints.find(SCOPE).isEmpty());
        assertTrue(rows(GRANTS).isEmpty());
        assertEquals(0, fake.blobs.size());
    }

    @Test void tamperedObjectAtContentAddressBlocksFinalizationAndLaterReads() {
        String sha = KsefInvoiceSnapshotRegistry.sha256Hex(xml);
        fake.replace(PREFIX + "/v1/test/inbound/shared/" + sha + ".xml",
                "<Faktura>forged</Faktura>".getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of());
        assertThrows(IllegalStateException.class, () -> run(freshStore(), checkpoints));
        assertTrue(checkpoints.find(SCOPE).isEmpty());
        assertNull(rows(DOCUMENTS).getFirst().get("xmlStorageRef"));
        var failure = assertThrows(KsefFailure.class, () -> freshStore().get("ksefdoc:v1:TEST:INBOUND:-:" + sha));
        assertEquals("KSEF_DOCUMENT_INTEGRITY_FAILED", failure.getMessage());
    }

    @Test void oneSharedObjectServesSeveralGrantsWhileGrantsRemainTheOnlyAccessRight() {
        run(freshStore(), checkpoints);
        var seller = new KsefTaxpayerContext(KsefEnvironment.TEST, SELLER_NIP);
        var sellerMetadata = new InvoiceMetadataMapper().map(seller, KsefSubjectRole.SELLER,
                sdkItem(metadataJson(ksefNumber(1), "2026-11-02T09:00:00Z", hashOf(xml))));
        new PersistentInboundInvoiceSink(SyncScope.of(seller, KsefSubjectRole.SELLER),
                InboundAccessToken.issuedFor(seller, FAKE_JWT), retrieval, documents, freshStore(),
                new SecureFa3InvoiceParser(), new Fa3SchemaValidator()).accept(sellerMetadata);
        assertEquals(2, rows(GRANTS).size());
        assertEquals(1, fake.blobs.size());
        assertEquals(1, fake.created.get());
        var overview = new CqnInboundOverviewRepository(persistence);
        assertEquals(1, overview.find(SyncScope.of(seller, KsefSubjectRole.SELLER), 0, 10).size());
        assertTrue(overview.find(new SyncScope(KsefEnvironment.TEST, OTHER_NIP, KsefSubjectRole.BUYER), 0, 10).isEmpty());
        String reference = (String) rows(DOCUMENTS).getFirst().get("xmlStorageRef");
        var outboundView = new ContentAddressedDocumentStore(new AzureBlobSasClient(
                AzureObjectStoreBinding.fromVcapServices(fake.vcapServices(null), null), settings), settings,
                AzureObjectStoreBinding.Access.READ_WRITE, CLOCK, d -> { })
                .forScope(DocumentScope.outbound(KsefEnvironment.TEST, CONTEXT_NIP));
        assertEquals("KSEF_DOCUMENT_ACCESS_DENIED", assertThrows(KsefFailure.class, () -> outboundView.get(reference)).getMessage());
    }

    @Test void scopedStoreSatisfiesExistingDurableContractUsedByInboundConfiguration() {
        assertInstanceOf(DurableInboundDocumentStore.class, freshStore());
    }

    static final class SimulatedProcessDeath extends Error { }
}
