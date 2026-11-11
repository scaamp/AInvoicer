package customer.cap_java.ksef;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.persistence.PersistenceService;

import customer.cap_java.ksef.inbound.KsefEnvironment;
import customer.cap_java.ksef.storage.AzureBlobSasClient;
import customer.cap_java.ksef.storage.AzureObjectStoreBinding;
import customer.cap_java.ksef.storage.ContentAddressedDocumentStore;
import customer.cap_java.ksef.storage.DocumentScope;
import customer.cap_java.ksef.storage.FakeAzureBlobServer;
import customer.cap_java.ksef.storage.ObjectStorageSettings;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;

/**
 * Worker OUTBOUND bez zmian kodu, z adapterem trwałego storage w miejscu mocka magazynu:
 * snapshot → put (scope TEST/OUTBOUND/NIP) → outbox → get + SHA-256 przed send → UPO put.
 * CAP/H2/outbox prawdziwe; SDK/auth/sesja KSeF mockowane, brak ruchu do KSeF.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "ainv.ksef.enabled=true", "ainv.ksef.nip=1234563218",
        "cds.outbox.services.KsefSubmissionOutbox.startCollector=false",
        "cds.outbox.persistent.scheduler.allTenantsTask.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ksef-durable-outbound;DB_CLOSE_DELAY=-1" })
@ActiveProfiles("test")
class KsefDurableStorageOutboundTest {
    static final String NIP = "1234563218";
    static final FakeAzureBlobServer FAKE = start();
    static final byte[] UPO = "<Potwierdzenie>synthetic</Potwierdzenie>".getBytes(StandardCharsets.UTF_8);

    @TestConfiguration
    static class DurableStoreConfiguration {
        /** Zastępuje lokalny magazyn wyłącznie w tym teście; produkcyjny wiring CF pozostaje bez zmian (INT-03). */
        @Bean @Primary KsefDocumentStore durableOutboundStore() {
            var settings = ObjectStorageSettings.defaults("ainvoicer-test", EnumSet.of(KsefEnvironment.TEST))
                    .withLoopbackHttpForTests(Duration.ofSeconds(2));
            var binding = AzureObjectStoreBinding.fromVcapServices(FAKE.vcapServices(null), null);
            return new ContentAddressedDocumentStore(new AzureBlobSasClient(binding, settings), settings,
                    binding.access(), Clock.systemUTC(), d -> { }).forScope(DocumentScope.outbound(KsefEnvironment.TEST, NIP));
        }
    }

    @Autowired PersistenceService db;
    @Autowired KsefSubmissionWorker worker;
    @Autowired KsefDocumentStore documents;
    @MockitoBean KsefAuthentication authentication;
    @MockitoBean KsefOnlineSession session;
    @MockitoBean KsefFa3Documents fa3;
    UUID id;
    UUID snapshotId;
    byte[] xml;
    String hash;

    static FakeAzureBlobServer start() {
        try { return new FakeAzureBlobServer(); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @AfterAll static void stop() { FAKE.close(); }

    @BeforeEach void setup() {
        FAKE.blobs.clear();
        FAKE.created.set(0);
        FAKE.conflicts.set(0);
        FAKE.failNextRequests.set(0);
        id = UUID.randomUUID();
        snapshotId = UUID.randomUUID();
        xml = ("<Faktura id=\"" + id + "\"/>").getBytes(StandardCharsets.UTF_8);
        hash = KsefInvoiceSnapshotRegistry.sha256Hex(xml);
        when(authentication.refreshIfNeeded(any())).thenAnswer(a -> a.getArgument(0));
        when(session.open(any(), any())).thenReturn("session-ref");
        when(session.send(anyString(), any(), any(), any())).thenReturn("invoice-ref");
        doReturn(KsefSubmissionWorkerTest.status(200, hash)).when(session).status(anyString(), anyString(), any());
        when(session.sessionStatus(anyString(), any())).thenReturn(200);
        when(session.upoReady(anyString(), any())).thenReturn(true);
        when(session.upo(anyString(), anyString(), any())).thenReturn(UPO);
        worker.tx(() -> {
            String sapId = UUID.randomUUID().toString();
            db.run(Insert.into(KsefSubmissionWorker.OPERATIONS).entry(Map.of("ID", id.toString(),
                    "sapDocumentRef", sapId, "taxpayerNip", NIP, "direction", "OUTBOUND",
                    "environment", "TEST", "operationType", "SEND_INVOICE", "status", "NEW", "idempotencyKey", id.toString())));
            db.run(Insert.into(KsefSubmissionWorker.SNAPSHOTS).entry(Map.ofEntries(
                    Map.entry("ID", snapshotId.toString()), Map.entry("invoiceUUID", sapId), Map.entry("taxpayerNip", NIP),
                    Map.entry("direction", "OUTBOUND"), Map.entry("environment", "TEST"), Map.entry("companyCode", "1000"),
                    Map.entry("schemaVersion", "FA(3)"), Map.entry("sapHeaderVersion", "2026-10-29T12:00:00Z"),
                    Map.entry("itemsFingerprint", "a".repeat(64)), Map.entry("xmlSha256", hash), Map.entry("xmlByteLength", xml.length))));
            return null;
        });
    }

    void enqueue() { worker.tx(() -> worker.enqueue(id, snapshotId, xml)); }

    @Test void snapshotAndUpoAreDurableExactBytesUnderTaxpayerScope() {
        enqueue();
        var row = worker.status(id);
        assertEquals("ksefdoc:v1:TEST:OUTBOUND:" + NIP + ":" + hash, row.get("xmlStorageRef"));
        worker.process(id);
        row = worker.status(id);
        assertEquals("COMPLETED", row.get("state"));
        verify(session, times(1)).send(eq("session-ref"), eq(xml), any(), any());
        String upoReference = (String) row.get("upoStorageRef");
        assertTrue(upoReference.startsWith("ksefdoc:v1:TEST:OUTBOUND:" + NIP + ":"));
        assertArrayEquals(UPO, documents.get(upoReference));
        assertArrayEquals(xml, documents.get((String) row.get("xmlStorageRef")));
        assertEquals(2, FAKE.created.get());
    }

    @Test void rollbackAfterStoragePutLeavesOnlyIdempotentOrphanAndRetryConverges() {
        assertThrows(IllegalStateException.class, () -> worker.tx(() -> {
            worker.enqueue(id, snapshotId, xml);
            throw new IllegalStateException("rollback-after-put");
        }));
        assertTrue(db.run(Select.from(KsefSubmissionWorker.SUBMISSIONS).byId(id.toString())).first().isEmpty());
        assertEquals(1, FAKE.blobs.size());
        enqueue();
        assertEquals("PREPARED", worker.status(id).get("state"));
        assertEquals(1, FAKE.created.get());
        assertEquals(1, FAKE.conflicts.get());
        verifyNoInteractions(authentication, session);
    }

    @Test void corruptedStoredXmlIsNeverSentAndRequiresReconciliation() {
        enqueue();
        FAKE.corrupt(FAKE.keyOf((String) worker.status(id).get("xmlStorageRef"), "ainvoicer-test"));
        worker.process(id);
        var row = worker.status(id);
        assertEquals("RECONCILIATION_REQUIRED", row.get("state"));
        assertEquals("KSEF_DOCUMENT_INTEGRITY_FAILED", row.get("lastCode"));
        verify(session, never()).send(anyString(), any(), any(), any());
        verifyNoInteractions(authentication);
    }

    @Test void storageOutageBeforeSendIsScheduledReadRetryWithoutKsefEffects() {
        enqueue();
        FAKE.failNextRequests.set(100);
        assertThrows(KsefFailure.class, () -> worker.process(id));
        var row = worker.status(id);
        assertEquals("PREPARED", row.get("state"));
        assertEquals("KSEF_DOCUMENT_STORE_UNAVAILABLE", row.get("lastCode"));
        assertEquals(1, ((Number) row.get("readAttempts")).intValue());
        verifyNoInteractions(authentication, session);
        FAKE.failNextRequests.set(0);
        worker.tx(() -> {
            var due = new HashMap<String, Object>();
            due.put("retryNotBefore", null);
            db.run(Update.entity(KsefSubmissionWorker.SUBMISSIONS).byId(id.toString()).data(due));
            return null;
        });
        worker.process(id);
        assertEquals("COMPLETED", worker.status(id).get("state"));
        verify(session, times(1)).send(eq("session-ref"), eq(xml), any(), any());
    }
}
