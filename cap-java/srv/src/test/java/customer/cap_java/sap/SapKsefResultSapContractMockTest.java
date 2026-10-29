package customer.cap_java.sap;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cds.ql.Select;
import com.sap.cds.services.outbox.OutboxMessage;
import com.sap.cds.services.outbox.OutboxMessageEventContext;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.RequestContext;
import com.sap.cds.services.runtime.CdsRuntime;
import com.sap.cloud.sdk.cloudplatform.connectivity.DefaultDestinationLoader;
import com.sap.cloud.sdk.cloudplatform.connectivity.DefaultHttpDestination;
import com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import customer.cap_java.sap.SapKsefResultWriteAdapter.WriteFailure;

/**
 * CAP-04D, część MOCK: pełna ścieżka CAP (worker → RemoteService → HTTP) przeciwko stanowemu
 * symulatorowi kontraktu ADR-0002. Symulator odtwarza tabelę wyników akcji, więc test sprawdza
 * wyłącznie, jak CAP interpretuje odpowiedzi i zachowuje się przy współbieżności i utracie odpowiedzi.
 * To NIE jest dowód zachowania SAP: trwały commit, ETag, blokady RAP i prawa principala są
 * weryfikowane wyłącznie przez {@link SapKsefResultWriteLiveWriteTest} (LIVE, domyślnie wyłączony).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = { "ainv.sap-write.enabled=true",
                "cds.outbox.services.SapResultOutbox.startCollector=false",
                "cds.outbox.persistent.scheduler.allTenantsTask.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:writeback-contract-mock;DB_CLOSE_DELAY=-1" })
@ActiveProfiles("test")
class SapKsefResultSapContractMockTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACTION_PATH = "/sap/opu/odata4/sap/zapi_ainv_inv_h_o4/srvd_a2x/sap/zapi_ainv_inv_h_o4/0001/"
            + "ZC_AINV_INV_H/" + SapInvoiceRemoteAdapter.MODEL + ".ApplyKsefResult";
    private static final Map<UUID, SimulatedInvoice> SAP = new ConcurrentHashMap<>();
    private static final List<String> REQUEST_LOG = new CopyOnWriteArrayList<>();
    private static final List<String> POST_BODIES = new CopyOnWriteArrayList<>();
    private static volatile CyclicBarrier arrivalBarrier;
    private static volatile boolean dropResponseAfterApply;
    private static HttpServer server;

    @Autowired CdsRuntime runtime;
    @Autowired PersistenceService db;
    @Autowired SapKsefResultWriteback writeback;
    @Autowired SapKsefResultWriteRecovery recovery;
    private UUID id;
    private UUID invoice;

    /** Minimalny model nagłówka SAP wymaganego do odtworzenia tabeli wyników ADR-0002. */
    static final class SimulatedInvoice {
        final String nip;
        final String company;
        final String direction;
        Instant version;
        String ksefNumber;
        String environment;
        String writeRequestId;

        SimulatedInvoice(String nip, String company, String direction, Instant version) {
            this.nip = nip; this.company = company; this.direction = direction; this.version = version;
        }
    }

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", SapKsefResultSapContractMockTest::handle);
        server.start();
        DestinationAccessor.prependDestinationLoader(new DefaultDestinationLoader().registerDestination(
                DefaultHttpDestination.builder("http://127.0.0.1:" + server.getAddress().getPort())
                        .name("AINV_H01").basicCredentials("fake-writer", "fake-password").build()));
    }

    @AfterAll static void stop() { server.stop(0); }

    @BeforeEach void setup() {
        REQUEST_LOG.clear();
        POST_BODIES.clear();
        arrivalBarrier = null;
        dropResponseAfterApply = false;
        id = UUID.randomUUID();
        invoice = UUID.randomUUID();
        SAP.put(invoice, new SimulatedInvoice("1234567802", "TST1", "OUTBOUND", WritebackFixtures.VERSION));
        queue(id, invoice);
    }

    private void queue(UUID operation, UUID forInvoice) {
        tx(() -> {
            WritebackFixtures.seed(db, operation, forInvoice);
            return writeback.enqueue(operation);
        });
    }

    private <T> T tx(Supplier<T> work) {
        return runtime.requestContext().systemUser().run((RequestContext ctx) ->
                runtime.changeSetContext().run(cs -> { return work.get(); }));
    }

    // 1. pierwsze zastosowanie wyniku
    @Test void firstApplyIsAppliedAndRecordedByRequestId() {
        process(id);
        var row = row(id);
        assertEquals("SUCCEEDED", row.get("state"));
        assertEquals("APPLIED", row.get("outcome"));
        assertEquals(id.toString(), row.get("sapRecordedRequestId"));
        assertEquals(1, POST_BODIES.size());
        assertEquals(id.toString(), SAP.get(invoice).writeRequestId);
    }

    // 3 + 11 + 12. utrata odpowiedzi po skutecznym zapisie → identyczne ponowienie → ALREADY_APPLIED, bez KSeF
    @Test void lostResponseAfterAppliedWriteIsReconciledWithoutTouchingKsefOperation() {
        var operationBefore = db.run(Select.from(WritebackFixtures.OPS).byId(id.toString())).single();
        long operationsBefore = db.run(Select.from(WritebackFixtures.OPS)).list().size();
        dropResponseAfterApply = true;
        assertThrows(WriteFailure.class, () -> process(id));
        assertEquals("PENDING", row(id).get("state"));
        // Zerwane połączenie CAP 5.2 raportuje jako błąd serwera/transportu; oba kody są ponawialne i niejednoznaczne.
        assertTrue(java.util.Set.of("SAP_TRANSPORT_UNKNOWN", "SAP_HTTP_500").contains((String) row(id).get("outcome")));
        assertNotNull(SAP.get(invoice).ksefNumber, "SAP zapisał wynik, mimo że CAP nie dostał odpowiedzi");
        dropResponseAfterApply = false;

        process(id);
        assertEquals("SUCCEEDED", row(id).get("state"));
        assertEquals("ALREADY_APPLIED", row(id).get("outcome"));
        assertEquals(2, POST_BODIES.size());
        assertEquals(POST_BODIES.getFirst(), POST_BODIES.getLast(), "ten sam payload i WriteRequestId");
        // Rekoncyliacja SAP nie zmienia operacji KSeF ani nie tworzy nowych (brak ponownej wysyłki).
        assertEquals(operationBefore, db.run(Select.from(WritebackFixtures.OPS).byId(id.toString())).single());
        assertEquals(operationsBefore, db.run(Select.from(WritebackFixtures.OPS)).list().size());
    }

    // 5. VERSION_CONFLICT
    @Test void staleVersionRequiresReconciliationAndIsNeverRetried() {
        SAP.get(invoice).version = WritebackFixtures.VERSION.plusNanos(100);
        process(id);
        assertEquals("RECONCILIATION_REQUIRED", row(id).get("state"));
        assertEquals("VERSION_CONFLICT", row(id).get("outcome"));
        process(id);
        assertEquals(1, POST_BODIES.size());
    }

    // 6. IDEMPOTENCY_CONFLICT: ten sam request ID z innym wynikiem
    @Test void sameRequestIdWithDifferentResultIsIdempotencyConflict() {
        var sap = SAP.get(invoice);
        sap.ksefNumber = "1234567802-OTHER-1";
        sap.environment = "TEST";
        sap.writeRequestId = id.toString();
        process(id);
        assertEquals("IDEMPOTENCY_CONFLICT", row(id).get("outcome"));
        assertEquals("RECONCILIATION_REQUIRED", row(id).get("state"));
    }

    // 7. RESULT_CONFLICT: faktura ma już inny wynik
    @Test void alreadyHavingAnotherResultIsResultConflict() {
        var sap = SAP.get(invoice);
        sap.ksefNumber = "1234567802-OTHER-2";
        sap.environment = "TEST";
        sap.writeRequestId = UUID.randomUUID().toString();
        process(id);
        assertEquals("RESULT_CONFLICT", row(id).get("outcome"));
        assertEquals("RECONCILIATION_REQUIRED", row(id).get("state"));
        assertEquals("1234567802-OTHER-2", SAP.get(invoice).ksefNumber, "wynik SAP nie jest nadpisywany");
    }

    // 8. dwa równoległe wywołania HTTP dla tej samej faktury (różne operacje i numery)
    @Test void twoConcurrentHttpCallsForTheSameInvoiceYieldOneWinner() throws Exception {
        UUID second = UUID.randomUUID();
        UUID secondInvoiceCopy = invoice;
        tx(() -> {
            WritebackFixtures.seed(db, second, secondInvoiceCopy, "1234567802-TEST-RACE", "9".repeat(64),
                    "9".repeat(64));
            return writeback.enqueue(second);
        });
        arrivalBarrier = new CyclicBarrier(2);   // oba żądania muszą dotrzeć do SAP, zanim którekolwiek zostanie wykonane
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> process(id));
            var b = pool.submit(() -> process(second));
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        }
        assertEquals(2, POST_BODIES.size());
        var states = List.of((String) row(id).get("state"), (String) row(second).get("state"));
        assertEquals(1, states.stream().filter("SUCCEEDED"::equals).count(), states.toString());
        assertEquals(1, states.stream().filter("RECONCILIATION_REQUIRED"::equals).count(), states.toString());
        var loser = "SUCCEEDED".equals(row(id).get("state")) ? row(second) : row(id);
        assertEquals("RESULT_CONFLICT", loser.get("outcome"));
    }

    // 9. odmowa przy niewłaściwym NIP lub CompanyCode → 403, bez zapisu; po usunięciu przyczyny możliwe wznowienie
    @Test void wrongTaxpayerOrCompanyScopeIsRefusedBySapAndCanBeResumedAfterFix() {
        SAP.put(invoice, new SimulatedInvoice("9876543210", "TST1", "OUTBOUND", WritebackFixtures.VERSION));
        process(id);
        assertEquals("RECONCILIATION_REQUIRED", row(id).get("state"));
        assertEquals("SAP_HTTP_403", row(id).get("outcome"));
        assertNull(SAP.get(invoice).ksefNumber);
        // Odmowa SAP jest awarią uprawnień/zakresu, nie konfliktem biznesowym: wznowienie jest dozwolone.
        assertEquals(SapKsefResultWriteRecovery.FailureKind.SAP_AVAILABILITY, recovery.status(id).failureKind());
        assertTrue(recovery.status(id).resumable());
        SAP.put(invoice, new SimulatedInvoice("1234567802", "TST1", "OUTBOUND", WritebackFixtures.VERSION));
        recovery.resume(id, "SCOPE_FIXED");
        process(id);
        assertEquals("SUCCEEDED", row(id).get("state"));
    }

    // 10 (strona CAP). CAP nie używa zwykłego CRUD: wyłącznie CSRF GET i POST akcji
    @Test void capNeverUsesStandardCrudWithTheWriteAdapter() {
        process(id);
        assertTrue(REQUEST_LOG.stream().allMatch(r -> r.startsWith("GET ") || r.startsWith("HEAD ") || r.equals("POST " + ACTION_PATH)),
                REQUEST_LOG.toString());
        assertTrue(REQUEST_LOG.stream().noneMatch(r -> r.startsWith("PATCH") || r.startsWith("PUT")
                || r.startsWith("DELETE")));
    }

    private void process(UUID writeId) {
        tx(() -> {
            var context = OutboxMessageEventContext.create("applyConfirmedResult");
            context.setIsInbound(true);
            var message = OutboxMessage.create();
            message.setParams(Map.of("writeId", writeId.toString()));
            context.setMessage(message);
            writeback.process(context);
            assertTrue(context.isCompleted());
            return null;
        });
    }

    private Map<String, Object> row(UUID writeId) {
        return db.run(Select.from(SapKsefResultWriteback.WRITES).byId(writeId.toString())).single();
    }

    /** Symulator kontraktu ADR-0002; akcje na jednej fakturze są szeregowane jak blokada root RAP. */
    private static void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        REQUEST_LOG.add(method + ("POST".equals(method) ? " " + exchange.getRequestURI().getPath() : " /"));
        if (!"POST".equals(method)) {
            exchange.getResponseHeaders().add("X-CSRF-Token", "fake-csrf");
            exchange.getResponseHeaders().add("Set-Cookie", "testsession=1; Path=/");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        POST_BODIES.add(body);
        try {
            var barrier = arrivalBarrier;
            if (barrier != null) {
                barrier.await(20, TimeUnit.SECONDS);
            }
            JsonNode in = JSON.readTree(body);
            UUID invoiceId = UUID.fromString(in.get("InvoiceUUID").asText());
            var inv = SAP.get(invoiceId);
            if (inv == null) {
                respond(exchange, 404, "{\"error\":{\"code\":\"NOT_FOUND\"}}");
                return;
            }
            String result;
            synchronized (inv) {
                if (!"OUTBOUND".equals(inv.direction) || !inv.nip.equals(in.get("TaxpayerNip").asText())
                        || !inv.company.equals(in.get("CompanyCode").asText())) {
                    respond(exchange, 403, "{\"error\":{\"code\":\"NOT_AUTHORIZED\"}}");
                    return;
                }
                String number = in.get("KsefNumber").asText();
                String env = in.get("Environment").asText();
                String requestId = in.get("WriteRequestId").asText();
                Instant expected = Instant.parse(in.get("ExpectedLocalLastChangedAt").asText());
                String outcome;
                if (inv.ksefNumber != null) {
                    if (requestId.equals(inv.writeRequestId) && (!number.equals(inv.ksefNumber) || !env.equals(inv.environment))) {
                        outcome = "IDEMPOTENCY_CONFLICT";
                    } else if (number.equals(inv.ksefNumber) && env.equals(inv.environment)) {
                        outcome = "ALREADY_APPLIED";
                    } else {
                        outcome = "RESULT_CONFLICT";
                    }
                } else if (!expected.equals(inv.version)) {
                    outcome = "VERSION_CONFLICT";
                } else {
                    inv.ksefNumber = number;
                    inv.environment = env;
                    inv.writeRequestId = requestId;
                    inv.version = inv.version.plusNanos(100);   // zapis zmienia ETag; no-op nie
                    outcome = "APPLIED";
                }
                // Brak zapisanego wyniku = pola null (jak w nieuzupełnionym nagłówku), nie puste łańcuchy.
                result = "{\"Outcome\":\"" + outcome + "\",\"InvoiceUUID\":\"" + invoiceId + "\",\"KsefNumber\":"
                        + quoted(inv.ksefNumber) + ",\"KsefEnvironment\":" + quoted(inv.environment)
                        + ",\"KsefWriteRequestId\":" + quoted(inv.writeRequestId) + ",\"KsefRecordedAt\":"
                        + (inv.ksefNumber == null ? "null" : "\"2026-10-29T08:00:00.1234567Z\"") + "}";
            }
            if (dropResponseAfterApply) {
                exchange.close();   // zapis wykonany, odpowiedź utracona (timeout/rozłączenie)
                return;
            }
            respond(exchange, 200, result);
        } catch (Exception failure) {
            exchange.close();
        }
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
