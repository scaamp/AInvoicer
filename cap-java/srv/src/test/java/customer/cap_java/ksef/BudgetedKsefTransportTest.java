package customer.cap_java.ksef;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import customer.cap_java.ksef.BudgetedKsefTransport.Limits;
import customer.cap_java.ksef.BudgetedKsefTransport.Stop;

/**
 * BETA-02: twardy budżet fizycznych żądań manual importu — rzeczywisty JDK {@code HttpClient}, rzeczywisty TLS
 * i rzeczywisty SDK MF na loopback. Serwer liczy <b>pełne żądania odebrane na drucie</b>, niezależnie od liczników
 * transportu. Próba kontrolna (bez osłony, domyślna JVM) dowodzi, że scenariusz utraty odpowiedzi rzeczywiście
 * wywołuje ukryty replay JDK; ta sama sytuacja z {@link BudgetedKsefTransport} daje dokładnie jedno żądanie.
 * Brak KSeF, sekretów i flag {@code jdk.httpclient.*}.
 */
class BudgetedKsefTransportTest {
    enum Action { OK, DROP, REDIRECT, SERVER_ERROR }

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), ZoneOffset.UTC);
    static Path keystore;
    static SSLContext serverTls;
    static SSLContext clientTls;

    SSLServerSocket server;
    ExecutorService executor;
    final AtomicInteger wire = new AtomicInteger();
    final Deque<Action> script = new ConcurrentLinkedDeque<>();
    String origin;

    /** Certyfikat wyłącznie testowy z SAN ip:127.0.0.1 — weryfikacja nazwy hosta w JDK pozostaje włączona. */
    @BeforeAll static void tls(@TempDir Path dir) throws Exception {
        keystore = dir.resolve("loopback.p12");
        char[] password = "loopback-only".toCharArray();
        var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        var process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "loopback", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=127.0.0.1", "-ext", "san=ip:127.0.0.1", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", keystore.toString(), "-storepass", new String(password), "-keypass", new String(password))
                .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        var store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) { store.load(in, password); }
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        serverTls = SSLContext.getInstance("TLS");
        serverTls.init(keys.getKeyManagers(), null, null);
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, trust.getTrustManagers(), null);
    }

    @AfterAll static void cleanup() throws IOException { Files.deleteIfExists(keystore); }

    @BeforeEach void start() throws IOException {
        server = (SSLServerSocket) serverTls.getServerSocketFactory().createServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(1500);
        origin = "https://127.0.0.1:" + server.getLocalPort();
        executor = Executors.newSingleThreadExecutor();
        executor.submit(this::serve);
    }

    @AfterEach void stop() throws Exception {
        server.close();
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    /** Jedno żądanie na połączenie; brak akcji w skrypcie = DROP (odpowiedź utracona). */
    private void serve() {
        while (!server.isClosed()) {
            try (var socket = server.accept()) {
                socket.setSoTimeout(3000);
                InputStream input = socket.getInputStream();
                int state = 0;
                while (state < 4) {
                    int value = input.read();
                    if (value < 0) { break; }
                    state = switch (state) {
                        case 0 -> value == 13 ? 1 : 0;
                        case 1 -> value == 10 ? 2 : 0;
                        case 2 -> value == 13 ? 3 : 0;
                        default -> value == 10 ? 4 : 0;
                    };
                }
                if (state < 4) { continue; }
                wire.incrementAndGet();
                Action action = script.isEmpty() ? Action.DROP : script.poll();
                OutputStream out = socket.getOutputStream();
                switch (action) {
                    case OK -> out.write(response("200 OK", "", "{}"));
                    case REDIRECT -> out.write(response("302 Found", "Location: " + origin + "/v2/elsewhere\r\n", ""));
                    case SERVER_ERROR -> out.write(response("500 Internal Server Error", "", ""));
                    case DROP -> { /* zamknięcie bez odpowiedzi */ }
                }
                out.flush();
            } catch (SocketTimeoutException idle) {
                // czekamy na kolejne połączenie
            } catch (IOException closedOrHandshake) {
                if (server.isClosed()) { return; }
            }
        }
    }

    private static byte[] response(String status, String headers, String body) {
        return ("HTTP/1.1 " + status + "\r\n" + headers + "Content-Type: application/json\r\nContent-Length: "
                + body.length() + "\r\nConnection: close\r\n\r\n" + body).getBytes(StandardCharsets.US_ASCII);
    }

    private BudgetedKsefTransport transport(int max, Runnable fence) {
        return new BudgetedKsefTransport(origin, new Limits(max, Duration.ofSeconds(30), Duration.ofSeconds(2)), CLOCK, fence, clientTls);
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(5)).GET().build();
    }

    private void awaitQuietWire() throws InterruptedException { Thread.sleep(400); }

    @Test void controlDefaultJdkClientReplaysLostGetOnTheWire() throws Exception {
        try (var plain = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(2)).sslContext(clientTls).build()) {
            assertThrows(IOException.class, () -> plain.send(get("/v2/invoices/ksef/x"), HttpResponse.BodyHandlers.ofByteArray()));
        }
        awaitQuietWire();
        assertTrue(wire.get() >= 2, "control must demonstrate hidden replay, wire=" + wire.get());
    }

    @Test void lostResponseThroughSdkYieldsExactlyOnePhysicalRequestAndStops() throws Exception {
        var transport = transport(9, () -> { });
        var sdk = KsefSdkConfiguration.createClient(origin, transport);
        assertThrows(RuntimeException.class, () -> sdk.getInvoice("5265877635-20261101-0100A0000000-AA", "synthetic-token"));
        awaitQuietWire();
        assertEquals(1, wire.get(), "physical requests on the wire");
        assertEquals(1, transport.reserved());
        assertEquals(1, transport.connections());
        assertEquals(Stop.REPLAY_BLOCKED, transport.stopReason());
        // Po niejednoznacznym wyniku transport nie wysyła już niczego w tym przebiegu.
        script.add(Action.OK);
        assertThrows(BudgetedKsefTransport.Refused.class, () -> transport.send(get("/v2/x"), HttpResponse.BodyHandlers.ofByteArray()));
        awaitQuietWire();
        assertEquals(1, wire.get());
    }

    @Test void budgetIsEnforcedBeforeIo() throws Exception {
        var transport = transport(2, () -> { });
        script.add(Action.OK);
        script.add(Action.OK);
        script.add(Action.OK);
        assertEquals(200, transport.send(get("/v2/a"), HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        assertEquals(200, transport.send(get("/v2/b"), HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        var refused = assertThrows(BudgetedKsefTransport.Refused.class,
                () -> transport.send(get("/v2/c"), HttpResponse.BodyHandlers.ofByteArray()));
        assertEquals(Stop.BUDGET_EXHAUSTED, refused.reason());
        awaitQuietWire();
        assertEquals(2, wire.get());
        assertEquals(2, transport.connections());
    }

    @Test void lostLeaseStopsBeforeAnyRequest() throws Exception {
        var transport = transport(9, () -> { throw new IllegalStateException("KSEF_INBOUND_LEASE_LOST"); });
        script.add(Action.OK);
        var refused = assertThrows(BudgetedKsefTransport.Refused.class,
                () -> transport.send(get("/v2/a"), HttpResponse.BodyHandlers.ofByteArray()));
        assertEquals(Stop.FENCE_LOST, refused.reason());
        awaitQuietWire();
        assertEquals(0, wire.get());
        assertEquals(0, transport.reserved());
    }

    @Test void expiredRunDeadlineStopsBeforeAnyRequest() throws Exception {
        var moving = new Clock() {
            Instant now = CLOCK.instant();
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now; }
        };
        var transport = new BudgetedKsefTransport(origin, new Limits(9, Duration.ofSeconds(30), Duration.ofSeconds(2)),
                moving, () -> { }, clientTls);
        moving.now = moving.now.plusSeconds(31);
        var refused = assertThrows(BudgetedKsefTransport.Refused.class,
                () -> transport.send(get("/v2/a"), HttpResponse.BodyHandlers.ofByteArray()));
        assertEquals(Stop.DEADLINE, refused.reason());
        assertEquals(0, wire.get());
    }

    @Test void redirectIsNotFollowedAndNon2xxStopsTheRun() throws Exception {
        var transport = transport(9, () -> { });
        script.add(Action.REDIRECT);
        script.add(Action.OK);
        assertEquals(302, transport.send(get("/v2/a"), HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        assertEquals(Stop.HTTP_FAILURE, transport.stopReason());
        assertThrows(BudgetedKsefTransport.Refused.class, () -> transport.send(get("/v2/b"), HttpResponse.BodyHandlers.ofByteArray()));
        awaitQuietWire();
        assertEquals(1, wire.get());
    }

    @Test void serverErrorIsReturnedOnceWithoutRetry() throws Exception {
        var transport = transport(9, () -> { });
        script.add(Action.SERVER_ERROR);
        script.add(Action.OK);
        assertEquals(500, transport.send(get("/v2/a"), HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        awaitQuietWire();
        assertEquals(1, wire.get());
        assertEquals(Stop.HTTP_FAILURE, transport.stopReason());
    }

    @Test void foreignOriginPlainHttpAndOtherMethodsAreRefused() throws Exception {
        var transport = transport(9, () -> { });
        for (HttpRequest request : new HttpRequest[]{
                HttpRequest.newBuilder(URI.create("https://127.0.0.2:" + server.getLocalPort() + "/v2/a")).build(),
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getLocalPort() + "/v2/a")).build()}) {
            var fresh = transport(9, () -> { });
            var refused = assertThrows(BudgetedKsefTransport.Refused.class, () -> fresh.send(request, HttpResponse.BodyHandlers.ofByteArray()));
            assertEquals(Stop.FORBIDDEN_REQUEST, refused.reason());
        }
        var delete = HttpRequest.newBuilder(URI.create(origin + "/v2/a")).DELETE().build();
        assertEquals(Stop.FORBIDDEN_REQUEST, assertThrows(BudgetedKsefTransport.Refused.class,
                () -> transport.send(delete, HttpResponse.BodyHandlers.ofByteArray())).reason());
        var async = transport(9, () -> { });
        assertTrue(async.sendAsync(get("/v2/a"), HttpResponse.BodyHandlers.ofByteArray()).isCompletedExceptionally());
        assertEquals(Stop.ASYNC_FORBIDDEN, async.stopReason());
        assertEquals(0, wire.get());
    }

    /**
     * Fabryka produkcyjna manual importu: auth idzie przez SDK zbudowany na budżetowanym transporcie (żądanie trafia na
     * loopback, nie do wspólnego beana/KSeF), a utrata odpowiedzi auth daje wynik niejednoznaczny (→ lease BLOCKED).
     */
    @Test void manualImportClientsAuthenticateThroughTheBudgetedTransportAndUnknownAuthIsAmbiguous() throws Exception {
        var context = new customer.cap_java.ksef.inbound.KsefTaxpayerContext(
                customer.cap_java.ksef.inbound.KsefEnvironment.TEST, "1234567802");
        var fences = new AtomicInteger();
        var clients = KsefInboundConfiguration.budgetedClients(origin, clientTls,
                new Limits(9, Duration.ofSeconds(30), Duration.ofSeconds(2)), CLOCK, context, "1234567802", "", true);
        var run = clients.open(fences::incrementAndGet);
        var failure = assertThrows(KsefFailure.class, () -> run.access().open());
        assertTrue(failure.ambiguous(), "lost auth response must be ambiguous (BLOCKED), never retried");
        awaitQuietWire();
        assertEquals(1, wire.get(), "challenge sent exactly once");
        assertEquals(1, fences.get(), "lease fence checked before the request");
    }

    @Test void invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Limits(0, Duration.ofSeconds(30), Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> new Limits(10, Duration.ofSeconds(30), Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> new Limits(9, Duration.ofMinutes(6), Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> new BudgetedKsefTransport("http://127.0.0.1:1",
                new Limits(9, Duration.ofSeconds(30), Duration.ofSeconds(2)), CLOCK, () -> { }, clientTls));
        assertThrows(IllegalArgumentException.class, () -> new BudgetedKsefTransport("https://u:p@127.0.0.1:1",
                new Limits(9, Duration.ofSeconds(30), Duration.ofSeconds(2)), CLOCK, () -> { }, clientTls));
    }
}
