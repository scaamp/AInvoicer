package customer.cap_java.ksef;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.outbox.OutboxMessage;
import com.sap.cds.services.outbox.OutboxMessageEventContext;

import customer.cap_java.sap.SapInvoiceRemoteAdapter;
import customer.cap_java.sap.SapInvoiceSnapshot;
import customer.cap_java.sap.SapKsefResultWriteAdapter;
import customer.cap_java.sap.SapKsefResultWriteAdapter.Outcome;
import customer.cap_java.sap.SapKsefResultWriteAdapter.WriteResult;
import customer.cap_java.sap.SapKsefResultWriteback;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;
import pl.akmf.ksef.sdk.client.model.StatusInfo;
import pl.akmf.ksef.sdk.client.model.session.SessionInvoiceStatusResponse;

/**
 * CAP-05 Etap 3: requestSend przez prawdziwe OData V4 + Spring Security (mock users) + H2 + generator B.
 * RAP Remote Service i transport KSeF SDK są mockami — żaden test nie wywołuje KSeF ani SAP.
 * Collector outbox jest wyłączony, więc worker nie przetwarza zleceń, chyba że test wywoła process().
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ainv.ksef.enabled=true", "ainv.ksef.nip=1234567802",
        "ainv.ksef.send.retry-after-rejection=true", "ainv.ksef.send.max-attempts=2",
        "ainv.ksef.party-data.sellers.1234567802.country-code=PL",
        "ainv.ksef.party-data.sellers.1234567802.line1=ul. Testowa 1",
        "ainv.ksef.party-data.sellers.1234567802.line2=00-001 Warszawa",
        "ainv.ksef.party-data.buyers.9876543210.jst-subordinate-unit=false",
        "ainv.ksef.party-data.buyers.9876543210.vat-group-member=false",
        "cds.outbox.services.KsefSubmissionOutbox.startCollector=false",
        "cds.outbox.services.SapResultOutbox.startCollector=false",
        "cds.outbox.persistent.scheduler.allTenantsTask.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ksef-request-send;DB_CLOSE_DELAY=-1" })
@ActiveProfiles("test")
class KsefSendRequestTest {
    private static final String SERVICE = "/odata/v4/KsefService/";
    @LocalServerPort int port;
    @Autowired PersistenceService db;
    @Autowired KsefSubmissionWorker worker;
    @Autowired SapKsefResultWriteback writeback;
    @MockitoBean SapInvoiceRemoteAdapter sap;
    @MockitoBean KsefAuthentication authentication;
    @MockitoBean KsefOnlineSession session;
    @MockitoBean KsefDocumentStore store;
    @MockitoBean SapKsefResultWriteAdapter sapWrite;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private UUID invoice;

    @BeforeEach void setup() {
        invoice = UUID.randomUUID();
        rap(SapInvoiceTestData.outbound(invoice));
        when(store.put(any())).thenReturn("document.xml");
    }

    @Test void queuesOneOperationWithServerSideDataAndNeverCallsKsef() throws Exception {
        var response = send("operator-a", "test-a", invoice);
        assertEquals(200, response.status(), response.body()::toString);
        assertEquals("QUEUED", response.body().get("status").asText());
        assertEquals(1, response.body().get("attempt").asInt());
        UUID operationId = UUID.fromString(response.body().get("operationId").asText());
        var operation = worker.tx(() -> db.run(Select.from(KsefSubmissionWorker.OPERATIONS).byId(operationId.toString())).single());
        assertEquals("QUEUED", operation.get("status"));
        assertEquals(invoice.toString(), operation.get("sapDocumentRef"));
        assertEquals("1234567802", operation.get("taxpayerNip"));
        assertEquals("SEND:" + invoice + ":TEST:1", operation.get("idempotencyKey"));
        var snapshot = worker.tx(() -> db.run(Select.from(KsefSubmissionWorker.SNAPSHOTS).byId(operation.get("snapshot_ID"))).single());
        assertEquals("SAP_RAP", snapshot.get("source"));
        assertEquals("PREPARED", worker.status(operationId).get("state"));
        assertEquals(snapshot.get("xmlSha256"), worker.status(operationId).get("xmlSha256"));
        verifyNoInteractions(authentication, session);
    }

    @Test void concurrentRequestsCreateExactlyOneAttempt() throws Exception {
        var barrier = new CyclicBarrier(2);
        when(sap.readInvoice(invoice)).thenAnswer(call -> {
            barrier.await(5, TimeUnit.SECONDS);
            return Optional.of(SapInvoiceTestData.outbound(invoice));
        });
        List<Integer> statuses = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Response> first = executor.submit(() -> send("operator-a", "test-a", invoice));
            Future<Response> second = executor.submit(() -> send("operator-peer", "test-peer", invoice));
            statuses.add(first.get(30, TimeUnit.SECONDS).status());
            statuses.add(second.get(30, TimeUnit.SECONDS).status());
        }
        statuses.sort(Integer::compareTo);
        assertEquals(List.of(200, 409), statuses);
        var operations = sendOperations();
        assertEquals(1, operations.size());
        assertEquals(1, count(KsefSendRequestHandler.RESERVATIONS));
        assertEquals("PREPARED", worker.status(UUID.fromString((String) operations.get(0).get("ID"))).get("state"));
        verifyNoInteractions(session);
    }

    @Test void secondRequestAfterHttpTimeoutOrNewTimestampIsRejected() throws Exception {
        assertEquals(200, send("operator-a", "test-a", invoice).status());
        // Nowy DataWytworzeniaFa da inne bajty XML, ale rezerwacja jest per faktura i środowisko.
        var again = send("operator-a", "test-a", invoice);
        assertEquals(409, again.status());
        assertEquals("KSEF_SEND_IN_PROGRESS", code(again));
        assertEquals(1, sendOperations().size());
        assertEquals(1, count(KsefSubmissionWorker.SNAPSHOTS, invoice));
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = { "APPLIED", "ALREADY_APPLIED", "VERSION_CONFLICT" })
    void requestThroughAcceptanceUpoAndSapWritebackUsesExactGeneratedBytes(Outcome outcome) throws Exception {
        // Pełny lokalny przepływ: OData/Security -> RAP mock -> realny FA(3)/H2/outbox -> SDK mock -> SAP mock.
        var stored = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        when(store.put(any())).thenAnswer(call -> { stored.set(call.getArgument(0)); return "document.xml"; });
        when(store.get(anyString())).thenAnswer(call -> stored.get());
        when(authentication.refreshIfNeeded(any())).thenAnswer(call -> call.getArgument(0));
        when(session.open(any(), any())).thenReturn("session-ref");
        when(session.send(anyString(), any(), any(), any())).thenReturn("invoice-ref");
        String number = "1234567802-20261029-" + invoice.toString().replace("-", "").substring(0, 12).toUpperCase() + "-AB";
        when(session.status(anyString(), anyString(), any())).thenAnswer(call -> {
            var result = new SessionInvoiceStatusResponse();
            var status = new StatusInfo(); status.setCode(200); result.setStatus(status);
            result.setReferenceNumber("invoice-ref"); result.setKsefNumber(number);
            result.setInvoiceHash(Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(
                    KsefInvoiceSnapshotRegistry.sha256Hex(stored.get()))));
            return result;
        });
        when(session.sessionStatus(anyString(), any())).thenReturn(200);
        when(session.upoReady(anyString(), any())).thenReturn(true);
        when(session.upo(anyString(), anyString(), any())).thenReturn("<Potwierdzenie/>".getBytes(StandardCharsets.UTF_8));
        UUID operationId = sendOk();
        byte[] exactXml = stored.get().clone();
        worker.process(operationId);
        assertEquals("COMPLETED", worker.status(operationId).get("state"));
        assertNotNull(worker.status(operationId).get("upoStorageRef"));
        verify(session).send(eq("session-ref"), eq(exactXml), any(), any());
        var pending = get("operator-a", "test-a", "InvoiceIntegrationStatus?$filter=invoiceUUID eq '" + invoice + "'")
                .body().get("value").get(0);
        assertEquals("ACCEPTED", pending.get("integrationStatus").asText());
        assertEquals("PENDING", pending.get("writebackState").asText());
        assertTrue(pending.get("upoAvailable").asBoolean());
        when(sapWrite.apply(any())).thenReturn(outcome == Outcome.VERSION_CONFLICT
                ? new WriteResult(outcome, invoice, null, null, null, null)
                : new WriteResult(outcome, invoice, number, "TEST", Instant.now(), operationId));
        worker.tx(() -> {
            var context = OutboxMessageEventContext.create("applyConfirmedResult");
            context.setIsInbound(true);
            var message = OutboxMessage.create(); message.setParams(Map.of("writeId", operationId.toString()));
            context.setMessage(message); writeback.process(context);
            return null;
        });
        var finished = get("operator-a", "test-a", "InvoiceIntegrationStatus?$filter=invoiceUUID eq '" + invoice + "'")
                .body().get("value").get(0);
        assertEquals("ACCEPTED", finished.get("integrationStatus").asText());
        assertEquals(number, finished.get("ksefNumber").asText());
        assertEquals(outcome == Outcome.VERSION_CONFLICT ? "RECONCILIATION_REQUIRED" : "SUCCEEDED",
                finished.get("writebackState").asText());
        assertEquals(409, send("operator-peer", "test-peer", invoice).status());
        verify(session, times(1)).send(anyString(), any(), any(), any());
    }

    @Test void rapWithConfirmedKsefNumberIsConflictWithoutWrites() throws Exception {
        rap(SapInvoiceTestData.invoice(invoice, h -> {
            h.put("KsefNumber", "1234567802-20261029-ABCDEF012345-AB");
            h.put("KsefEnvironment", "TEST");
        }));
        var response = send("operator-a", "test-a", invoice);
        assertEquals(409, response.status());
        assertEquals("KSEF_ALREADY_RECORDED_IN_SAP", code(response));
        assertTrue(sendOperations().isEmpty());
        assertEquals(0, count(KsefSendRequestHandler.RESERVATIONS));
    }

    @Test void capAcceptedBeforeSapWritebackBlocksResend() throws Exception {
        UUID operationId = sendOk();
        setOperationStatus(operationId, "ACCEPTED");
        var response = send("operator-a", "test-a", invoice);
        assertEquals(409, response.status());
        assertEquals("KSEF_ALREADY_ACCEPTED", code(response));
    }

    @Test void legacyNewFailedAndUnknownSendsWithoutReservationBlockNewAttempt() throws Exception {
        UUID legacyId = UUID.randomUUID();
        worker.tx(() -> db.run(Insert.into(KsefSubmissionWorker.OPERATIONS).entry(Map.of(
                "ID", legacyId.toString(), "sapDocumentRef", invoice.toString(), "taxpayerNip", "1234567802",
                "direction", "OUTBOUND", "environment", "TEST", "operationType", "SEND_INVOICE",
                "status", "NEW", "idempotencyKey", legacyId.toString()))));
        for (String state : List.of("NEW", "FAILED", "UNKNOWN", "READ_RETRY_EXHAUSTED", "ACCEPTED", "REJECTED")) {
            setOperationStatus(legacyId, state);
            assertEquals(409, send("operator-a", "test-a", invoice).status(), state);
        }
        assertEquals(0, count(KsefSendRequestHandler.RESERVATIONS));
        verifyNoInteractions(session);
    }

    @Test void unknownStateNeverUnlocksAnotherSend() throws Exception {
        UUID operationId = sendOk();
        setOperationStatus(operationId, "RECONCILIATION_REQUIRED");
        var response = send("operator-a", "test-a", invoice);
        assertEquals(409, response.status());
        assertEquals("KSEF_RECONCILIATION_REQUIRED", code(response));
        assertEquals(1, sendOperations().size());
    }

    @Test void crashAfterReservationRecoversWithoutSecondSend() throws Exception {
        UUID operationId = sendOk();
        // Proces padł po utrwaleniu etapu SENDING; odpowiedź KSeF nieznana.
        worker.tx(() -> db.run(Update.entity(KsefSubmissionWorker.SUBMISSIONS).byId(operationId.toString())
                .data(Map.of("state", "SENDING", "sessionReference", "session-ref", "leaseUntil", Instant.now().minusSeconds(1)))));
        worker.process(operationId);
        assertEquals("RECONCILIATION_REQUIRED", worker.status(operationId).get("state"));
        var response = send("operator-a", "test-a", invoice);
        assertEquals(409, response.status());
        assertEquals("KSEF_RECONCILIATION_REQUIRED", code(response));
        verify(session, never()).send(anyString(), any(), any(), any());
    }

    @Test void failureInsideTransactionRollsBackReservationSoRetryIsClean() throws Exception {
        when(store.put(any())).thenThrow(new IllegalStateException("storage unavailable"));
        var failed = send("operator-a", "test-a", invoice);
        assertTrue(failed.status() >= 500, () -> "HTTP " + failed.status());
        assertEquals(0, count(KsefSendRequestHandler.RESERVATIONS));
        assertTrue(sendOperations().isEmpty());
        reset(store);
        when(store.put(any())).thenReturn("document.xml");
        assertEquals(200, send("operator-a", "test-a", invoice).status());
    }

    @Test void confirmedRejectionAllowsControlledRetryButNotDuplicateSignal() throws Exception {
        UUID first = sendOk();
        // INVOICE_REJECTED (sesja jeszcze nie zamknięta) to nie jest końcowe odrzucenie.
        setOperationStatus(first, "REJECTED");
        setSubmissionState(first, "INVOICE_REJECTED");
        assertEquals("KSEF_SEND_IN_PROGRESS", code(send("operator-a", "test-a", invoice)));
        setSubmissionState(first, "REJECTED");
        var retry = send("operator-a", "test-a", invoice);
        assertEquals(200, retry.status(), retry.body()::toString);
        assertEquals(2, retry.body().get("attempt").asInt());
        UUID second = UUID.fromString(retry.body().get("operationId").asText());
        // Limit prób (max-attempts=2) zatrzymuje kolejną wysyłkę.
        setOperationStatus(second, "REJECTED");
        setSubmissionState(second, "REJECTED");
        assertEquals("KSEF_RESEND_LIMIT_REACHED", code(send("operator-a", "test-a", invoice)));
    }

    @Test void possibleDuplicateRejectionRequiresReconciliation() throws Exception {
        UUID first = sendOk();
        setOperationStatus(first, "REJECTED");
        setSubmissionState(first, "REJECTED");
        worker.tx(() -> db.run(Insert.into(KsefSubmissionWorker.HISTORY).entry(Map.of("ID", UUID.randomUUID().toString(),
                "submissionId", first.toString(), "occurredAt", Instant.now(), "state", "INVOICE_REJECTED",
                "code", "KSEF_INVOICE_REJECTED_440"))));
        var response = send("operator-a", "test-a", invoice);
        assertEquals(409, response.status());
        assertEquals("KSEF_POSSIBLE_DUPLICATE_RECONCILIATION_REQUIRED", code(response));
    }

    @Test void foreignTaxpayerAndCompanyCodeAreForbidden() throws Exception {
        var otherNip = send("operator-b", "test-b", invoice);
        assertEquals(403, otherNip.status());
        assertEquals("KSEF_TAXPAYER_NOT_AUTHORIZED", code(otherNip));
        var otherCompany = send("operator-other-company", "test-cc", invoice);
        assertEquals(403, otherCompany.status());
        assertEquals("KSEF_COMPANY_CODE_NOT_AUTHORIZED", code(otherCompany));
        assertEquals(403, send("user-without-ksef-role", "test-user", invoice).status());
        assertEquals(401, send(null, null, invoice).status());
        assertTrue(sendOperations().isEmpty());
    }

    @Test void browserSuppliedContextIsIgnoredAndInputIsValidated() throws Exception {
        // Dodatkowe pola payloadu (NIP, środowisko, klucz) nie są częścią kontraktu akcji.
        var forged = post("operator-a", "test-a", "requestSend", Map.of("invoiceUUID", invoice.toString(),
                "taxpayerNip", "9876543210", "environment", "PROD", "idempotencyKey", "x"));
        assertEquals(400, forged.status());
        assertEquals(400, post("operator-a", "test-a", "requestSend", Map.of("invoiceUUID", "not-a-uuid")).status());
        when(sap.readInvoice(any())).thenReturn(Optional.empty());
        assertEquals(404, send("operator-a", "test-a", UUID.randomUUID()).status());
        assertTrue(sendOperations().isEmpty());
    }

    @Test void inboundMissingPartyDataAndInvalidFa3AreUnprocessable() throws Exception {
        rap(SapInvoiceTestData.invoice(invoice, h -> h.put("Direction", "INBOUND")));
        assertEquals("KSEF_OUTBOUND_ONLY", code(send("operator-a", "test-a", invoice)));
        rap(SapInvoiceTestData.invoice(invoice, h -> h.put("BuyerNip", "5260250274")));
        assertEquals("KSEF_PARTY_DATA_MISSING", code(send("operator-a", "test-a", invoice)));
        rap(SapInvoiceTestData.invoice(invoice, h -> h.put("GrossAmount", new java.math.BigDecimal("1.00"))));
        var invalid = send("operator-a", "test-a", invoice);
        assertEquals(422, invalid.status());
        assertEquals("KSEF_FA3_INVALID", code(invalid));
        assertEquals(0, count(KsefSendRequestHandler.RESERVATIONS));
    }

    @Test void remoteServiceExceptionNeverExposesDestinationOrSapResponse() throws Exception {
        when(sap.readInvoice(invoice)).thenThrow(new com.sap.cds.services.ServiceException(
                com.sap.cds.services.ErrorStatuses.BAD_GATEWAY, "private SAP response and credential location"));
        var failed = send("operator-a", "test-a", invoice);
        assertEquals(502, failed.status());
        assertEquals("SAP_INVOICE_READ_FAILED", code(failed));
        assertFalse(failed.body().toString().contains("private SAP"));
        assertTrue(sendOperations().isEmpty());
    }

    @Test void rawCreateOfSendOperationIsRejected() throws Exception {
        var response = post("operator-a", "test-a", "KsefOperations", Map.of("taxpayerNip", "1234567802",
                "direction", "OUTBOUND", "operationType", "SEND_INVOICE", "idempotencyKey", UUID.randomUUID().toString(),
                "sapDocumentRef", invoice.toString()));
        assertEquals(400, response.status());
        assertTrue(sendOperations().isEmpty());
    }

    @Test void integrationStatusIsReadOnlyScopedAndWithoutSapOrSecretData() throws Exception {
        UUID operationId = sendOk();
        var visible = get("operator-a", "test-a", "InvoiceIntegrationStatus?$filter=invoiceUUID eq '" + invoice + "'");
        assertEquals(200, visible.status(), visible.body()::toString);
        var row = visible.body().get("value").get(0);
        assertEquals(operationId.toString(), row.get("operationId").asText());
        assertEquals("QUEUED", row.get("integrationStatus").asText());
        assertEquals("PREPARED", row.get("submissionState").asText());
        assertFalse(row.get("upoAvailable").asBoolean());
        assertTrue(row.get("writebackState").isNull());
        var foreign = get("operator-b", "test-b", "InvoiceIntegrationStatus?$filter=invoiceUUID eq '" + invoice + "'");
        assertEquals(0, foreign.body().get("value").size());
        // Ten sam NIP, lecz inna spółka: obie publiczne ścieżki odczytu muszą odmawiać.
        var otherCompany = get("operator-other-company", "test-cc", "InvoiceIntegrationStatus?$filter=invoiceUUID eq '" + invoice + "'");
        assertEquals(0, otherCompany.body().get("value").size());
        var legacyRead = get("operator-other-company", "test-cc", "KsefOperations?$filter=sapDocumentRef eq '" + invoice + "'");
        assertEquals(0, legacyRead.body().get("value").size());
        var metadata = rawGet("operator-a", "test-a", "$metadata");
        for (String hidden : List.of("xmlStorageRef", "upoStorageRef", "claimToken", "confirmedXmlSha256", "snapshot_ID",
                "KsefSubmissions", "SapResultWrites", "KsefSendReservations", "GrossAmount", "SellerName")) {
            assertFalse(metadata.contains(hidden), hidden);
        }
        assertFalse(post("operator-a", "test-a", "InvoiceIntegrationStatus", Map.of("invoiceUUID", invoice.toString()))
                .status() < 300);
    }

    // ---- pomocnicze --------------------------------------------------------------------------------

    private void rap(SapInvoiceSnapshot snapshot) {
        when(sap.readInvoice(UUID.fromString((String) snapshot.header().get("InvoiceUUID")))).thenReturn(Optional.of(snapshot));
    }

    private UUID sendOk() throws Exception {
        var response = send("operator-a", "test-a", invoice);
        assertEquals(200, response.status(), response.body()::toString);
        return UUID.fromString(response.body().get("operationId").asText());
    }

    private void setOperationStatus(UUID id, String status) {
        worker.tx(() -> db.run(Update.entity(KsefSubmissionWorker.OPERATIONS).byId(id.toString()).data(Map.of("status", status))));
    }

    private void setSubmissionState(UUID id, String state) {
        worker.tx(() -> db.run(Update.entity(KsefSubmissionWorker.SUBMISSIONS).byId(id.toString()).data(Map.of("state", state))));
    }

    private List<Map<String, Object>> sendOperations() {
        return worker.tx(() -> db.run(Select.from(KsefSubmissionWorker.OPERATIONS).where(o -> o.get("sapDocumentRef")
                .eq(invoice.toString()).and(o.get("operationType").eq("SEND_INVOICE")))).list().stream()
                .map(r -> (Map<String, Object>) r).toList());
    }

    private long count(String entity) {
        return worker.tx(() -> db.run(Select.from(entity).where(r -> r.get("invoiceUUID").eq(invoice.toString()))).rowCount());
    }

    private long count(String entity, UUID invoiceId) {
        return worker.tx(() -> db.run(Select.from(entity).where(r -> r.get("invoiceUUID").eq(invoiceId.toString()))).rowCount());
    }

    private Response send(String user, String password, UUID invoiceId) {
        return post(user, password, "requestSend", Map.of("invoiceUUID", invoiceId.toString()));
    }

    private static String code(Response response) {
        JsonNode error = response.body() == null ? null : response.body().get("error");
        return error == null ? null : error.get("message").asText();
    }

    private Response post(String user, String password, String path, Object body) {
        try {
            return exchange(user, password, HttpRequest.newBuilder(URI.create("http://localhost:" + port + SERVICE + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Response get(String user, String password, String path) {
        return exchange(user, password, HttpRequest.newBuilder(URI.create("http://localhost:" + port + SERVICE
                + path.replace(" ", "%20"))).GET());
    }

    private String rawGet(String user, String password, String path) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + SERVICE + path)).GET();
        auth(builder, user, password);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private Response exchange(String user, String password, HttpRequest.Builder builder) {
        try {
            builder.header("Accept", "application/json");
            auth(builder, user, password);
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body() == null || response.body().isBlank() ? null : json.readTree(response.body());
            return new Response(response.statusCode(), body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void auth(HttpRequest.Builder builder, String user, String password) {
        if (user != null) {
            builder.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
        }
    }

    record Response(int status, JsonNode body) { }
}
