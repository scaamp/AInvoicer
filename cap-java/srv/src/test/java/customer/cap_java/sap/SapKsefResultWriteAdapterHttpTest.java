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
    @Autowired private SapKsefResultWriteAdapter adapter;
    @Autowired private CdsRuntime runtime;
    @Autowired private PersistenceService db;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", SapKsefResultWriteAdapterHttpTest::handle);
        server.start();
        DestinationAccessor.prependDestinationLoader(new DefaultDestinationLoader().registerDestination(
                DefaultHttpDestination.builder("http://127.0.0.1:" + server.getAddress().getPort())
                        .name("AINV_H01").basicCredentials("fake-writer", "fake-password").build()));
    }

    @AfterAll static void stop() { server.stop(0); }
    @BeforeEach void reset() { POSTS.clear(); responseOutcome = "APPLIED"; responseStatus = 200; retryAfter = null; }

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
        if (!"POST".equals(method)) {
            exchange.getResponseHeaders().add("X-CSRF-Token", "fake-csrf");
            exchange.getResponseHeaders().add("Set-Cookie", "testsession=1; Path=/");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        POSTS.add(body);
        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString(
                "fake-writer:fake-password".getBytes(StandardCharsets.UTF_8));
        String expectedPath = PATH + "ZC_AINV_INV_H/" + SapInvoiceRemoteAdapter.MODEL + ".ApplyKsefResult";
        boolean contractValid = expectedPath.equals(exchange.getRequestURI().getPath())
                && expectedAuth.equals(exchange.getRequestHeaders().getFirst("Authorization"))
                && "fake-csrf".equals(exchange.getRequestHeaders().getFirst("X-CSRF-Token"));
        int status = contractValid ? responseStatus : 400;
        String response = status == 200 ? "{\"Outcome\":\"" + responseOutcome
                + "\",\"InvoiceUUID\":\"" + INVOICE + "\",\"KsefNumber\":\"1234567802-TEST-123\","
                + "\"KsefEnvironment\":\"TEST\",\"KsefWriteRequestId\":\"" + REQUEST
                + "\",\"KsefRecordedAt\":\"2026-10-28T10:12:00.1234567Z\"}"
                : "{\"error\":{\"code\":\"TEST\",\"message\":\"DO_NOT_PERSIST_TEST_MARKER\"}}";
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
