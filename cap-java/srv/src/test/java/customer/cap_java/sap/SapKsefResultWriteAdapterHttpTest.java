package customer.cap_java.sap;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cloud.sdk.cloudplatform.connectivity.DefaultDestinationLoader;
import com.sap.cloud.sdk.cloudplatform.connectivity.DefaultHttpDestination;
import com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.runtime.CdsRuntime;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import customer.cap_java.sap.SapKsefResultWriteAdapter.Outcome;
import customer.cap_java.sap.SapKsefResultWriteAdapter.WriteFailure;

/** Rzeczywisty klient CAP/Cloud SDK, wyłącznie lokalny serwer HTTP; NIE LIVE SAP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = { "ainv.sap-write.enabled=true",
                "ainv.sap-write.http.read-timeout=PT0.2S",
                "cds.outbox.services.SapResultOutbox.startCollector=false",
                "spring.datasource.url=jdbc:h2:mem:writeback-http;DB_CLOSE_DELAY=-1" })
@ActiveProfiles("test")
class SapKsefResultWriteAdapterHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PATH = "/sap/opu/odata4/sap/zapi_ainv_inv_h_o4/srvd_a2x/sap/zapi_ainv_inv_h_o4/0001/";
    private static final UUID INVOICE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID REQUEST = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant VERSION = Instant.parse("2026-10-28T10:11:12.1234567Z");
    private static final List<String> POSTS = new CopyOnWriteArrayList<>();
    private static HttpServer server;
    private static volatile String responseOutcome;
    private static volatile int responseStatus;
    private static volatile String retryAfter;
    private static volatile long responseDelay;
    private static volatile String errorCode;
    private static final List<String> PATCHES = new CopyOnWriteArrayList<>();
    private static java.util.concurrent.ExecutorService httpThreads;
    @Autowired private SapKsefResultWriteAdapter adapter;
    @Autowired private CdsRuntime runtime;
    @Autowired private PersistenceService db;
    @Autowired private SapKsefResultWriteback writeback;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("AinvoicerH01")
    private com.sap.cds.services.cds.CqnService remote;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", SapKsefResultWriteAdapterHttpTest::handle);
        httpThreads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(httpThreads);
        server.start();
        DestinationAccessor.prependDestinationLoader(new DefaultDestinationLoader().registerDestination(
                DefaultHttpDestination.builder("http://127.0.0.1:" + server.getAddress().getPort())
                        .name("AINV_H01").basicCredentials("fake-writer", "fake-password").build()));
    }

    @AfterAll static void stop() { server.stop(0); httpThreads.shutdownNow(); }
    @BeforeEach void reset() {
        POSTS.clear(); PATCHES.clear(); responseOutcome = "APPLIED"; responseStatus = 200;
        retryAfter = null; responseDelay = 0; errorCode = "TEST_AUTH_DENIED";
    }

    private void unauthorizedUpdate() {
        runtime.requestContext().systemUser().run((com.sap.cds.services.request.RequestContext ctx) ->
                runtime.changeSetContext().run(cs -> { remote.run(com.sap.cds.ql.Update.entity(
                        SapInvoiceRemoteAdapter.HEADERS).byId(INVOICE.toString()).data("SellerName", "Fixture")); }));
    }

    @Test void crudDenialRequiresRemote403AndRecognizedAuthorizationCode() {
        responseStatus = 403;
        SapCrudDenialAssertions.assertAuthorizationDenied(this::unauthorizedUpdate, "TEST_AUTH_DENIED");
        assertEquals(List.of(PATH + "ZC_AINV_INV_H(" + INVOICE + ")"), PATCHES);
    }

    @Test void crudDenialRejects400404500AndSuccessfulUpdate() {
        for (int status : List.of(400, 404, 500, 200, 204)) {
            responseStatus = status;
            assertThrows(AssertionError.class, () -> SapCrudDenialAssertions.assertAuthorizationDenied(
                    this::unauthorizedUpdate, "TEST_AUTH_DENIED"), "HTTP " + status);
        }
        assertEquals(5, PATCHES.size());
    }

    @Test void crudDenialRejectsCsrf403AndConnectionFailure() {
        responseStatus = 403;
        errorCode = "TEST_CSRF_INVALID";
        assertThrows(AssertionError.class, () -> SapCrudDenialAssertions.assertAuthorizationDenied(
                this::unauthorizedUpdate, "TEST_AUTH_DENIED"));
        responseStatus = -1;
        assertThrows(AssertionError.class, () -> SapCrudDenialAssertions.assertAuthorizationDenied(
                this::unauthorizedUpdate, "TEST_AUTH_DENIED"));
        assertEquals(2, PATCHES.size(), "Zerwane połączenie nie może uruchomić ukrytego retry UPDATE");
    }

    @Test void actualSocketTimeoutReleasesClaimAndRetryPreservesRequest() {
        runtime.requestContext().systemUser().run((com.sap.cds.services.request.RequestContext ctx) ->
                runtime.changeSetContext().run(cs -> {
                    WritebackFixtures.seed(db, REQUEST, INVOICE, command().ksefNumber(),
                            WritebackFixtures.XML_HASH, WritebackFixtures.XML_HASH);
                    writeback.enqueue(REQUEST);
                }));
        responseDelay = 700;
        assertThrows(WriteFailure.class, this::processMessage);
        assertEquals("PENDING", db.run(Select.from(SapKsefResultWriteback.WRITES).byId(REQUEST.toString()))
                .single().get("state"));
        assertEquals(1, POSTS.size(), "Brak automatycznych retry transportu");
        responseDelay = 0;
        responseOutcome = "ALREADY_APPLIED";
        processMessage();
        assertEquals("SUCCEEDED", db.run(Select.from(SapKsefResultWriteback.WRITES).byId(REQUEST.toString()))
                .single().get("state"));
        assertEquals(2, POSTS.size());
        assertEquals(POSTS.getFirst(), POSTS.getLast());
    }

    private void processMessage() {
        runtime.requestContext().systemUser().run((com.sap.cds.services.request.RequestContext ctx) ->
                runtime.changeSetContext().run(cs -> {
                    var message = com.sap.cds.services.outbox.OutboxMessage.create();
                    message.setParams(java.util.Map.of("writeId", REQUEST.toString()));
                    var event = com.sap.cds.services.outbox.OutboxMessageEventContext.create("applyConfirmedResult");
                    event.setIsInbound(true); event.setMessage(message);
                    writeback.process(event);
                }));
    }

    static SapKsefResultCommand command() {
        return new SapKsefResultCommand(INVOICE, REQUEST, "1234567802-TEST-123", "1234567802", "TST1", VERSION);
    }

    @Test void sendsCollectionBoundActionCsrfAndFullPrecisionVersion() throws Exception {
        var result = adapter.apply(command());
        assertEquals(Outcome.APPLIED, result.outcome());
        assertEquals(1, POSTS.size());
        JsonNode payload = JSON.readTree(POSTS.getFirst());
        assertEquals(INVOICE.toString(), payload.get("InvoiceUUID").asText());
        assertEquals(REQUEST.toString(), payload.get("WriteRequestId").asText());
        assertEquals(VERSION, Instant.parse(payload.get("ExpectedLocalLastChangedAt").asText()));
        assertEquals("TEST", payload.get("Environment").asText());
    }

    @Test void identicalRetrySendsIdenticalPayload() {
        adapter.apply(command());
        responseOutcome = "ALREADY_APPLIED";
        assertEquals(Outcome.ALREADY_APPLIED, adapter.apply(command()).outcome());
        assertEquals(POSTS.getFirst(), POSTS.getLast());
    }

    @Test void interpretsEveryDomainConflictEvenOnHttp200() {
        for (String outcome : List.of("VERSION_CONFLICT", "IDEMPOTENCY_CONFLICT", "RESULT_CONFLICT")) {
            responseOutcome = outcome;
            assertEquals(Outcome.valueOf(outcome), adapter.apply(command()).outcome());
        }
    }

    @Test void rejectsUnknownOutcome() {
        responseOutcome = "SOME_NEW_STATUS";
        WriteFailure failure = assertThrows(WriteFailure.class, () -> adapter.apply(command()));
        assertEquals("SAP_INVALID_RESULT", failure.getMessage());
        assertFalse(failure.retryable());
    }

    @Test void authenticationRefusalIsTerminalAndRedacted() {
        responseStatus = 403;
        WriteFailure failure = assertThrows(WriteFailure.class, () -> adapter.apply(command()));
        assertFalse(failure.retryable(), failure.getMessage());
        assertFalse(failure.getMessage().contains("DO_NOT_PERSIST"));
        assertNull(failure.getCause());
    }

    @Test void serviceUnavailableIsRetryable() {
        responseStatus = 503;
        assertTrue(assertThrows(WriteFailure.class, () -> adapter.apply(command())).retryable());
    }

    @Test void retryAfterStopsAutomaticRetriesAndPreservesDeadline() {
        responseStatus = 429;
        retryAfter = "120";
        Instant notBefore = Instant.now().plusSeconds(119);
        var failure = assertThrows(WriteFailure.class, () -> adapter.apply(command()));
        assertFalse(failure.retryable());
        assertNotNull(failure.retryNotBefore());
        assertTrue(failure.retryNotBefore().isAfter(notBefore));
    }

    @Test void retryAfterHttpDateAlsoDefersServiceUnavailable() {
        responseStatus = 503;
        retryAfter = "Thu, 29 Oct 2026 12:00:00 GMT";
        var failure = assertThrows(WriteFailure.class, () -> adapter.apply(command()));
        assertFalse(failure.retryable());
        assertEquals(Instant.parse("2026-10-29T12:00:00Z"), failure.retryNotBefore());
    }

    @Test void remoteRefusalDoesNotRollBackWorkersLocalTransaction() {
        responseStatus = 403;
        String historyId = UUID.randomUUID().toString();
        runtime.changeSetContext().run(changeSet -> {
            assertThrows(WriteFailure.class, () -> adapter.apply(command()));
            assertFalse(changeSet.isMarkedForCancel());
            db.run(Insert.into(SapKsefResultWriteback.HISTORY).entry(java.util.Map.of(
                    "ID", historyId, "writeId", REQUEST.toString(), "occurredAt", VERSION, "outcome", "SAP_HTTP_403")));
        });
        assertTrue(db.run(Select.from(SapKsefResultWriteback.HISTORY).byId(historyId)).first().isPresent());
    }

    @Test void disabledTransportDoesNotContactDestination() {
        var disabled = new SapKsefResultWriteAdapter(null, null, false);
        assertEquals("SAP_WRITE_DISABLED", assertThrows(WriteFailure.class,
                () -> disabled.apply(command())).getMessage());
        assertTrue(POSTS.isEmpty());
    }

    @Test void rejectsVersionMorePreciseThanSapBeforeHttp() {
        assertThrows(IllegalArgumentException.class, () -> new SapKsefResultCommand(
                INVOICE, REQUEST, "N", "1234567802", "TST1", VERSION.plusNanos(1)));
        assertTrue(POSTS.isEmpty());
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        boolean patch = "PATCH".equals(method);
        if (!"POST".equals(method) && !patch) {
            exchange.getResponseHeaders().add("X-CSRF-Token", "fake-csrf");
            exchange.getResponseHeaders().add("Set-Cookie", "testsession=1; Path=/");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (patch) { PATCHES.add(exchange.getRequestURI().getPath()); } else { POSTS.add(body); }
        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString(
                "fake-writer:fake-password".getBytes(StandardCharsets.UTF_8));
        String expectedPath = PATH + "ZC_AINV_INV_H/" + SapInvoiceRemoteAdapter.MODEL + ".ApplyKsefResult";
        boolean contractValid = (patch ? (PATH + "ZC_AINV_INV_H(" + INVOICE + ")")
                .equals(exchange.getRequestURI().getPath()) : expectedPath.equals(exchange.getRequestURI().getPath()))
                && expectedAuth.equals(exchange.getRequestHeaders().getFirst("Authorization"))
                && "fake-csrf".equals(exchange.getRequestHeaders().getFirst("X-CSRF-Token"));
        int status = contractValid ? responseStatus : 400;
        if (status == -1) { exchange.close(); return; }
        if (responseDelay > 0) {
            try { Thread.sleep(responseDelay); } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt(); exchange.close(); return;
            }
        }
        if (status == 204) { exchange.sendResponseHeaders(204, -1); exchange.close(); return; }
        String response = status == 200 ? patch ? "{}" : "{\"Outcome\":\"" + responseOutcome
                + "\",\"InvoiceUUID\":\"" + INVOICE + "\",\"KsefNumber\":\"1234567802-TEST-123\","
                + "\"KsefEnvironment\":\"TEST\",\"KsefWriteRequestId\":\"" + REQUEST
                + "\",\"KsefRecordedAt\":\"2026-10-28T10:12:00.1234567Z\"}"
                : "{\"error\":{\"code\":\"" + errorCode + "\",\"message\":\"DO_NOT_PERSIST_TEST_MARKER\"}}";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (retryAfter != null) {
            exchange.getResponseHeaders().add("Retry-After", retryAfter);
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
