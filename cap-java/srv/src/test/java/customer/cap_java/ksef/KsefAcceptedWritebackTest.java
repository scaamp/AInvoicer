package customer.cap_java.ksef;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.services.outbox.OutboxMessage;
import com.sap.cds.services.outbox.OutboxMessageEventContext;
import com.sap.cds.services.persistence.PersistenceService;

import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;
import customer.cap_java.sap.SapInvoiceRemoteAdapter;
import customer.cap_java.sap.SapKsefResultWriteAdapter;
import customer.cap_java.sap.SapKsefResultWriteAdapter.Outcome;
import customer.cap_java.sap.SapKsefResultWriteAdapter.WriteFailure;
import customer.cap_java.sap.SapKsefResultWriteAdapter.WriteResult;
import customer.cap_java.sap.SapKsefResultWriteRecovery;
import customer.cap_java.sap.SapKsefResultWriteback;
import pl.akmf.ksef.sdk.client.model.StatusInfo;
import pl.akmf.ksef.sdk.client.model.session.SessionInvoiceStatusResponse;

/**
 * CAP-05 Etap 2 i 7: KSeF ACCEPTED → zlecenie SAP writeback, sweeper, VERSION_CONFLICT.
 * Prawdziwe CAP/H2, worker A, outbox i writeback; mockowane SDK KSeF, magazyn i adapter HTTP SAP.
 * Żadnego wywołania KSeF ani HTTP WRITE do H01.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "ainv.ksef.enabled=true", "ainv.ksef.nip=1234563218",
        "cds.outbox.services.KsefSubmissionOutbox.startCollector=false",
        "cds.outbox.services.SapResultOutbox.startCollector=false",
        "cds.outbox.persistent.scheduler.allTenantsTask.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ksef-accepted-writeback;DB_CLOSE_DELAY=-1" })
@ActiveProfiles("test")
class KsefAcceptedWritebackTest {
    private static final String WRITES = "customer.cap_java.SapResultWrites";
    private static final Instant VERSION = Instant.parse("2026-10-27T08:15:30.1234567Z");
    @Autowired PersistenceService db;
    @Autowired KsefSubmissionWorker worker;
    @Autowired KsefAcceptedWriteback accepted;
    @Autowired SapKsefResultWriteRecovery recovery;
    @MockitoSpyBean SapKsefResultWriteback writeback;
    @MockitoBean KsefAuthentication authentication;
    @MockitoBean KsefOnlineSession session;
    @MockitoBean KsefDocumentStore documents;
    @MockitoBean KsefFa3Documents fa3;
    @MockitoBean SapKsefResultWriteAdapter adapter;
    @MockitoBean SapInvoiceRemoteAdapter sap;
    private UUID id;
    private UUID invoice;
    private byte[] xml;
    private String hash;
    private String ksefNumber;

    @BeforeEach void setup() {
        id = UUID.randomUUID();
        invoice = UUID.randomUUID();
        xml = ("<fixture id=\"" + id + "\"/>").getBytes(StandardCharsets.UTF_8);
        hash = KsefInvoiceSnapshotRegistry.sha256Hex(xml);
        // Numer unikalny per test: SapResultWrites ma UNIQUE (environment, ksefNumber).
        ksefNumber = "1234563218-20261029-" + id.toString().replace("-", "").substring(0, 12).toUpperCase() + "-AB";
        when(documents.put(any())).thenReturn("document.xml");
        when(documents.get(anyString())).thenReturn(xml);
        when(authentication.refreshIfNeeded(any())).thenAnswer(a -> a.getArgument(0));
        when(session.open(any(), any())).thenReturn("session-ref");
        when(session.send(anyString(), any(), any(), any())).thenReturn("invoice-ref");
        doReturn(accepted()).when(session).status(anyString(), anyString(), any());
        when(session.sessionStatus(anyString(), any())).thenReturn(200);
        when(session.upoReady(anyString(), any())).thenReturn(true);
        when(session.upo(anyString(), anyString(), any())).thenReturn("<Potwierdzenie/>".getBytes(StandardCharsets.UTF_8));
    }

    @Test void acceptedCreatesExactlyOneWritebackAndRepeatIsNoop() {
        prepare("SAP_RAP");
        worker.process(id);
        assertEquals("COMPLETED", worker.status(id).get("state"));
        var write = write().orElseThrow();
        assertEquals("PENDING", write.get("state"));
        assertEquals(invoice.toString(), write.get("invoiceUUID"));
        assertEquals(ksefNumber, write.get("ksefNumber"));
        assertEquals(VERSION.toString(), write.get("expectedVersion"));
        assertEquals(KsefAcceptedWriteback.Outcome.QUEUED, accepted.request(id));
        assertEquals(0, accepted.sweep());
        assertEquals(1, writeOutboxMessages());
        verify(session, times(1)).send(anyString(), any(), any(), any());
        verifyNoInteractions(adapter);
    }

    @Test void acceptedCommittedBeforeEnqueueIsRecoveredBySweeperAfterRestart() {
        // Stan po commit ACCEPTED i awarii procesu przed utworzeniem zlecenia (brak SapResultWrites).
        seedAcceptedWithoutWrite("SAP_RAP");
        assertTrue(write().isEmpty());
        assertEquals(1, accepted.sweep());
        assertEquals("PENDING", write().orElseThrow().get("state"));
        assertEquals(0, accepted.sweep());
        assertEquals(1, writeOutboxMessages());
        verifyNoInteractions(session, authentication, adapter);
    }

    @Test void enqueueFailureKeepsAcceptanceAndUpoAndSweeperRepairsLater() {
        prepare("SAP_RAP");
        doThrow(new IllegalStateException("db connection reset")).when(writeback).enqueue(id);
        worker.process(id);
        // Przyjęcie i UPO są niezależne od błędu zlecenia SAP.
        assertEquals("COMPLETED", worker.status(id).get("state"));
        assertNotNull(worker.status(id).get("upoStorageRef"));
        var operation = operation();
        assertEquals("ACCEPTED", operation.get("status"));
        assertEquals(ksefNumber, operation.get("ksefNumber"));
        assertTrue(write().isEmpty());
        assertEquals(1, history(KsefAcceptedWriteback.CODE_FAILED + "UNEXPECTED"));
        doCallRealMethod().when(writeback).enqueue(id);
        assertEquals(1, accepted.sweep());
        assertEquals("PENDING", write().orElseThrow().get("state"));
        verify(session, times(1)).send(anyString(), any(), any(), any());
    }

    @Test void syntheticFixtureWithoutRapLinkNeverGetsWriteback() {
        prepare("UNVERIFIED");
        worker.process(id);
        assertEquals("COMPLETED", worker.status(id).get("state"));
        assertEquals("ACCEPTED", operation().get("status"));
        assertTrue(write().isEmpty());
        assertEquals(0, accepted.sweep());
        assertEquals(KsefAcceptedWriteback.Outcome.NOT_ELIGIBLE, accepted.request(id));
        assertEquals(1, history(KsefAcceptedWriteback.CODE_NOT_ELIGIBLE));
        // Bezpośrednie enqueue też odmawia (obrona w głębi).
        assertEquals("RAP_LINK_UNVERIFIED", assertThrows(IllegalStateException.class,
                () -> worker.tx(() -> writeback.enqueue(id))).getMessage());
    }

    @Test void sapTimeoutAnd503AreRetriedOnlyOnSap() {
        prepare("SAP_RAP");
        worker.process(id);
        when(adapter.apply(any())).thenThrow(new WriteFailure("SAP_TRANSPORT_TIMEOUT", true))
                .thenThrow(new WriteFailure("SAP_HTTP_503", true))
                .thenReturn(new WriteResult(Outcome.APPLIED, invoice, ksefNumber, "TEST", Instant.now(), id));
        assertFalse(deliverWrite());
        assertEquals("PENDING", write().orElseThrow().get("state"));
        assertFalse(deliverWrite());
        assertTrue(deliverWrite());
        var write = write().orElseThrow();
        assertEquals("SUCCEEDED", write.get("state"));
        assertEquals(3, write.get("attempts"));
        assertEquals("ACCEPTED", operation().get("status"));
        verify(adapter, times(3)).apply(argThat(c -> c.requestId().equals(id) && c.ksefNumber().equals(ksefNumber)));
        verify(session, times(1)).send(anyString(), any(), any(), any());
    }

    @Test void sapUnavailableKeepsKsefNumberAndCompletedUpo() {
        prepare("SAP_RAP");
        worker.process(id);
        when(adapter.apply(any())).thenThrow(new WriteFailure("SAP_HTTP_503", true));
        for (int i = 0; i < 4; i++) {
            assertFalse(deliverWrite());
        }
        // Piąta próba wyczerpuje budżet: zlecenie zatrzymane, wiadomość zakończona bez dalszego retry.
        assertTrue(deliverWrite());
        assertEquals("RETRY_EXHAUSTED", write().orElseThrow().get("state"));
        assertEquals("ACCEPTED", operation().get("status"));
        assertEquals(ksefNumber, operation().get("ksefNumber"));
        assertEquals("COMPLETED", worker.status(id).get("state"));
        verify(session, times(1)).send(anyString(), any(), any(), any());
    }

    @Test void versionConflictNeverResendsAndIsDiagnosedReadOnly() {
        prepare("SAP_RAP");
        worker.process(id);
        when(adapter.apply(any())).thenReturn(new WriteResult(Outcome.VERSION_CONFLICT, invoice, null, null, null, null));
        assertTrue(deliverWrite());
        var write = write().orElseThrow();
        assertEquals("RECONCILIATION_REQUIRED", write.get("state"));
        assertEquals("VERSION_CONFLICT", write.get("outcome"));
        var status = recovery.status(id);
        assertEquals(SapKsefResultWriteRecovery.FailureKind.BUSINESS_CONFLICT, status.failureKind());
        assertFalse(status.resumable());
        assertEquals("RESUME_NOT_ALLOWED", assertThrows(IllegalStateException.class,
                () -> recovery.resume(id, "OPERATOR")).getMessage());
        // SAP zmienił nagłówek po przygotowaniu XML (np. InvoiceStatus) — diagnostyka to pokazuje.
        when(sap.readInvoice(invoice)).thenReturn(Optional.of(SapInvoiceTestData.invoice(invoice,
                h -> h.put("LocalLastChangedAt", VERSION.plusSeconds(60)))));
        var diagnosis = recovery.diagnose(id);
        assertTrue(diagnosis.sapInvoiceFound());
        assertFalse(diagnosis.sapHasKsefNumber());
        assertEquals(KsefInvoiceSnapshotRegistry.Drift.HEADER_CHANGED, diagnosis.drift());
        // Diagnostyka nie zmienia zlecenia, nie wznawia i nie dotyka KSeF.
        assertEquals(write.get("revision"), write().orElseThrow().get("revision"));
        assertEquals("ACCEPTED", operation().get("status"));
        verify(adapter, times(1)).apply(any());
        verify(session, times(1)).send(anyString(), any(), any(), any());
    }

    // ---- pomocnicze --------------------------------------------------------------------------------

    /** Operacja NEW + migawka o danym pochodzeniu + zlecenie KSeF (PREPARED) w jednej transakcji. */
    private void prepare(String source) {
        UUID snapshot = seed(source, "NEW");
        worker.tx(() -> worker.enqueue(id, snapshot, xml));
    }

    private void seedAcceptedWithoutWrite(String source) {
        UUID snapshot = seed(source, "ACCEPTED");
        assertNotNull(snapshot);
    }

    private UUID seed(String source, String status) {
        UUID snapshot = UUID.randomUUID();
        worker.tx(() -> {
            db.run(Insert.into(KsefSubmissionWorker.SNAPSHOTS).entry(Map.ofEntries(
                    Map.entry("ID", snapshot.toString()), Map.entry("invoiceUUID", invoice.toString()),
                    Map.entry("taxpayerNip", "1234563218"), Map.entry("direction", "OUTBOUND"),
                    Map.entry("environment", "TEST"), Map.entry("companyCode", "1000"),
                    Map.entry("schemaVersion", "FA(3)"), Map.entry("sapHeaderVersion", VERSION.toString()),
                    Map.entry("itemsFingerprint", KsefInvoiceSnapshotRegistry.itemsFingerprint(
                            SapInvoiceTestData.outbound(invoice).items())),
                    Map.entry("xmlSha256", hash), Map.entry("xmlByteLength", xml.length), Map.entry("source", source))));
            var op = new LinkedHashMap<String, Object>(Map.of("ID", id.toString(), "sapDocumentRef", invoice.toString(),
                    "taxpayerNip", "1234563218", "direction", "OUTBOUND", "environment", "TEST",
                    "operationType", "SEND_INVOICE", "status", status, "idempotencyKey", id.toString()));
            if ("ACCEPTED".equals(status)) {
                op.put("snapshot_ID", snapshot.toString());
                op.put("ksefNumber", ksefNumber);
                op.put("confirmedXmlSha256", hash);
            }
            db.run(Insert.into(KsefSubmissionWorker.OPERATIONS).entry(op));
            return null;
        });
        return snapshot;
    }

    /** Jedna dostawa wiadomości writebacku; false = wyjątek do ponowienia przez outbox. */
    private boolean deliverWrite() {
        try {
            return worker.tx(() -> {
                var context = OutboxMessageEventContext.create("applyConfirmedResult");
                context.setIsInbound(true);
                var message = OutboxMessage.create();
                message.setParams(Map.of("writeId", id.toString()));
                context.setMessage(message);
                writeback.process(context);
                return context.isCompleted();
            });
        } catch (WriteFailure retry) {
            return false;
        }
    }

    private Optional<Map<String, Object>> write() {
        return worker.tx(() -> db.run(Select.from(WRITES).byId(id.toString())).first()
                .map(r -> (Map<String, Object>) new LinkedHashMap<String, Object>(r)));
    }

    private Map<String, Object> operation() {
        return worker.tx(() -> new LinkedHashMap<String, Object>(
                db.run(Select.from(KsefSubmissionWorker.OPERATIONS).byId(id.toString())).single()));
    }

    private long history(String code) {
        return worker.tx(() -> db.run(Select.from(KsefSubmissionWorker.HISTORY).where(h -> h.get("submissionId")
                .eq(id.toString()).and(h.get("code").eq(code)))).rowCount());
    }

    private long writeOutboxMessages() {
        return worker.tx(() -> db.run(Select.from("cds.outbox.Messages").where(r -> r.get("target").eq("SapResultOutbox")))
                .list().stream().filter(r -> r.toString().contains(id.toString())).count());
    }

    private SessionInvoiceStatusResponse accepted() {
        var response = new SessionInvoiceStatusResponse();
        var status = new StatusInfo();
        status.setCode(200);
        response.setStatus(status);
        response.setReferenceNumber("invoice-ref");
        response.setInvoiceHash(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hash)));
        response.setKsefNumber(ksefNumber);
        return response;
    }
}
