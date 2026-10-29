package customer.cap_java.ksef;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.persistence.PersistenceService;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;
import pl.akmf.ksef.sdk.client.model.StatusInfo;
import pl.akmf.ksef.sdk.client.model.session.SessionInvoiceStatusResponse;

/** CAP/H2 są prawdziwe, SDK/auth/magazyn mockowane. Bez wysyłki sieciowej. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "ainv.ksef.enabled=true", "ainv.ksef.nip=1234563218",
        "cds.outbox.services.KsefSubmissionOutbox.startCollector=false",
        "cds.outbox.persistent.scheduler.allTenantsTask.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ksef-worker;DB_CLOSE_DELAY=-1" })
@ActiveProfiles("test")
class KsefSubmissionWorkerTest {
    @Autowired PersistenceService db;
    @Autowired KsefSubmissionWorker worker;
    @MockitoBean KsefAuthentication authentication;
    @MockitoBean KsefOnlineSession session;
    @MockitoBean KsefDocumentStore documents;
    @MockitoBean KsefFa3Documents fa3;
    private UUID id;
    private UUID snapshotId;
    private byte[] xml;
    private String hash;

    @BeforeEach void setup() {
        id = UUID.randomUUID();
        snapshotId = UUID.randomUUID();
        xml = ("<fixture id=\"" + id + "\"/>").getBytes(StandardCharsets.UTF_8);
        hash = KsefInvoiceSnapshotRegistry.sha256Hex(xml);
        when(documents.put(any())).thenReturn("document.xml");
        when(documents.get(anyString())).thenReturn(xml);
        when(authentication.refreshIfNeeded(any())).thenAnswer(a -> a.getArgument(0));
        when(session.open(any(), any())).thenReturn("session-ref");
        when(session.send(anyString(), any(), any(), any())).thenReturn("invoice-ref");
        doReturn(status(200, hash)).when(session).status(anyString(), anyString(), any());
        when(session.sessionStatus(anyString(), any())).thenReturn(200);
        when(session.upoReady(anyString(), any())).thenReturn(true);
        when(session.upo(anyString(), anyString(), any())).thenReturn("<Potwierdzenie/>".getBytes(StandardCharsets.UTF_8));
        worker.tx(() -> {
            String sapId = UUID.randomUUID().toString();
            db.run(Insert.into(KsefSubmissionWorker.OPERATIONS).entry(Map.of("ID", id.toString(),
                    "sapDocumentRef", sapId, "taxpayerNip", "1234563218", "direction", "OUTBOUND",
                    "environment", "TEST", "operationType", "SEND_INVOICE", "status", "NEW", "idempotencyKey", id.toString())));
            db.run(Insert.into(KsefSubmissionWorker.SNAPSHOTS).entry(Map.ofEntries(
                    Map.entry("ID", snapshotId.toString()), Map.entry("invoiceUUID", sapId), Map.entry("taxpayerNip", "1234563218"),
                    Map.entry("direction", "OUTBOUND"), Map.entry("environment", "TEST"), Map.entry("companyCode", "1000"),
                    Map.entry("schemaVersion", "FA(3)"), Map.entry("sapHeaderVersion", "2026-10-29T12:00:00Z"),
                    Map.entry("itemsFingerprint", "a".repeat(64)), Map.entry("xmlSha256", hash), Map.entry("xmlByteLength", xml.length))));
            return null;
        });
    }

    @Test void enqueueIsAtomicIdempotentAndDoesNotCallNetwork() {
        enqueue();
        enqueue();
        assertEquals("PREPARED", worker.status(id).get("state"));
        assertEquals(1, db.run(Select.from("cds.outbox.Messages").where(r -> r.get("target").eq("KsefSubmissionOutbox")))
                .list().stream().filter(r -> r.toString().contains(id.toString())).count());
        verifyNoInteractions(authentication, session);
    }

    @Test void rollbackDoesNotPublishOrPersistSubmission() {
        assertThrows(IllegalStateException.class, () -> worker.tx(() -> {
            worker.enqueue(id, snapshotId, xml);
            throw new IllegalStateException("rollback");
        }));
        assertTrue(db.run(Select.from(KsefSubmissionWorker.SUBMISSIONS).byId(id.toString())).first().isEmpty());
        assertTrue(db.run(Select.from(KsefSubmissionWorker.HISTORY).where(h -> h.get("submissionId").eq(id.toString()))).list().isEmpty());
        verifyNoInteractions(authentication, session);
    }

    @Test void successfulFlowCommitsProofAndNeverSendsTwice() {
        enqueue();
        worker.process(id);
        assertEquals("COMPLETED", worker.status(id).get("state"));
        var operation = db.run(Select.from(KsefSubmissionWorker.OPERATIONS).byId(id.toString())).single();
        assertEquals("ACCEPTED", operation.get("status"));
        assertEquals(hash, operation.get("confirmedXmlSha256"));
        worker.process(id);
        verify(session, times(1)).send(eq("session-ref"), eq(xml), any(), any());
        verify(session, times(1)).close(eq("session-ref"), any());
    }

    @Test void sendTimeoutIsReconciledByHashWithoutAnotherSend() {
        enqueue();
        when(session.send(anyString(), any(), any(), any())).thenThrow(new KsefFailure("KSEF_SEND_IO", true, false, null));
        worker.process(id);
        assertEquals("RECONCILIATION_REQUIRED", worker.status(id).get("state"));
        worker.process(id);
        when(session.findByHash(anyString(), anyString(), any())).thenReturn(status(200, hash));
        worker.reconcile(id);
        assertEquals("POLLING", worker.status(id).get("state"));
        worker.process(id);
        assertEquals("COMPLETED", worker.status(id).get("state"));
        verify(session, times(1)).send(anyString(), any(), any(), any());
    }

    @Test void interruptedSendAfterLeaseExpirationDoesNotRetryEffect() {
        enqueue();
        worker.tx(() -> db.run(Update.entity(KsefSubmissionWorker.SUBMISSIONS).byId(id.toString())
                .data(Map.of("state", "SENDING", "sessionReference", "session-ref", "leaseUntil", Instant.now().minusSeconds(1)))));
        worker.process(id);
        assertEquals("RECONCILIATION_REQUIRED", worker.status(id).get("state"));
        verifyNoInteractions(session, authentication);
    }

    @Test void closeTimeoutPreservesAcceptedAndIsNeverRepeated() {
        enqueue();
        doThrow(new KsefFailure("KSEF_CLOSE_IO", true, false, null)).when(session).close(anyString(), any());
        worker.process(id);
        assertEquals("RECONCILIATION_REQUIRED", worker.status(id).get("state"));
        assertEquals("ACCEPTED", db.run(Select.from(KsefSubmissionWorker.OPERATIONS).byId(id.toString())).single().get("status"));
        when(session.findByHash(anyString(), anyString(), any())).thenReturn(status(200, hash));
        worker.reconcile(id);
        worker.process(id);
        assertEquals("COMPLETED", worker.status(id).get("state"));
        verify(session, times(1)).close(anyString(), any());
        verify(session, times(1)).send(anyString(), any(), any(), any());
    }

    @Test void pendingBusinessStatusDoesNotAcceptOrClose() {
        enqueue();
        when(session.status(anyString(), anyString(), any())).thenReturn(status(150, hash));
        assertThrows(KsefFailure.class, () -> worker.process(id));
        assertEquals("POLLING", worker.status(id).get("state"));
        assertNotNull(worker.status(id).get("retryNotBefore"));
        verify(session, never()).close(anyString(), any());
    }

    @Test void upoPendingKeepsAcceptedAndCannotTriggerAnotherSend() {
        enqueue();
        when(session.upoReady(anyString(), any())).thenReturn(false);
        assertThrows(KsefFailure.class, () -> worker.process(id));
        assertEquals("WAITING_UPO", worker.status(id).get("state"));
        assertEquals("ACCEPTED", db.run(Select.from(KsefSubmissionWorker.OPERATIONS).byId(id.toString())).single().get("status"));
        verify(session, never()).upo(anyString(), anyString(), any());
        verify(session, times(1)).send(anyString(), any(), any(), any());
    }

    @Test void retryAfterDelaysSafeReadsAndExhaustionIsDurable() {
        enqueue();
        Instant notBefore = Instant.now().plusSeconds(120);
        when(session.status(anyString(), anyString(), any())).thenThrow(new KsefFailure("KSEF_STATUS_HTTP_429", false, true, notBefore));
        assertThrows(KsefFailure.class, () -> worker.process(id));
        assertEquals(notBefore, worker.status(id).get("retryNotBefore"));
        assertThrows(KsefFailure.class, () -> worker.process(id));
        verify(session, times(1)).status(anyString(), anyString(), any());
        worker.tx(() -> db.run(Update.entity(KsefSubmissionWorker.SUBMISSIONS).byId(id.toString())
                .data(Map.of("readAttempts", 9, "retryNotBefore", Instant.now().minusSeconds(1)))));
        worker.process(id);
        assertEquals("READ_RETRY_EXHAUSTED", worker.status(id).get("state"));
        verify(session, times(1)).send(anyString(), any(), any(), any());
        assertThrows(KsefFailure.class, () -> worker.resumeReads(id));
        worker.tx(() -> db.run(Update.entity(KsefSubmissionWorker.SUBMISSIONS).byId(id.toString())
                .data(Map.of("retryNotBefore", Instant.now().minusSeconds(1)))));
        worker.resumeReads(id);
        doReturn(status(200, hash)).when(session).status(anyString(), anyString(), any());
        worker.process(id);
        assertEquals("COMPLETED", worker.status(id).get("state"));
        verify(session, times(1)).send(anyString(), any(), any(), any());
    }

    @Test void acceptanceRequiresNumberAndExactHash() {
        enqueue();
        when(session.status(anyString(), anyString(), any())).thenReturn(status(200, "f".repeat(64)));
        worker.process(id);
        assertEquals("RECONCILIATION_REQUIRED", worker.status(id).get("state"));
        assertNotEquals("ACCEPTED", db.run(Select.from(KsefSubmissionWorker.OPERATIONS).byId(id.toString())).single().get("status"));
    }

    @Test void duplicate440IsRejectedAndClosedOnce() {
        enqueue();
        when(session.status(anyString(), anyString(), any())).thenReturn(status(440, hash));
        worker.process(id);
        assertEquals("REJECTED", worker.status(id).get("state"));
        verify(session, times(1)).close(anyString(), any());
        verify(session, never()).upo(anyString(), anyString(), any());
    }

    @Test void tamperedBytesAndScopeAreRejectedBeforeNetwork() {
        assertThrows(KsefFailure.class, () -> worker.tx(() -> worker.enqueue(id, snapshotId, new byte[]{1})));
        enqueue();
        when(documents.get(anyString())).thenReturn(new byte[]{2});
        worker.process(id);
        assertEquals("RECONCILIATION_REQUIRED", worker.status(id).get("state"));
        verifyNoInteractions(session, authentication);
    }

    @Test void networkDoesNotHoldBusinessRowLockAndConcurrentWorkerCannotSend() {
        enqueue();
        when(session.open(any(), any())).thenAnswer(invocation -> {
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> worker.tx(() -> {
                    var committed = db.run(Select.from(KsefSubmissionWorker.SUBMISSIONS).byId(id.toString()).lock()).single();
                    assertEquals("OPENING", committed.get("state"));
                    return null;
                })).get(3, java.util.concurrent.TimeUnit.SECONDS);
                var failure = executor.submit(() -> assertThrows(KsefFailure.class, () -> worker.process(id)))
                        .get(3, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals("KSEF_CLAIM_ACTIVE", failure.getMessage());
            }
            return "session-ref";
        });
        worker.process(id);
        assertEquals("COMPLETED", worker.status(id).get("state"));
        verify(session, times(1)).send(anyString(), any(), any(), any());
    }

    private void enqueue() { worker.tx(() -> worker.enqueue(id, snapshotId, xml)); }

    static SessionInvoiceStatusResponse status(int code, String hash) {
        var response = new SessionInvoiceStatusResponse();
        var status = new StatusInfo();
        status.setCode(code);
        response.setStatus(status);
        response.setReferenceNumber("invoice-ref");
        response.setInvoiceHash(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hash)));
        if (code == 200) { response.setKsefNumber("1234563218-20261029-ABCDEF012345-AB"); }
        return response;
    }
}
