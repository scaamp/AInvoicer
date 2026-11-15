package customer.cap_java.ksef;

import static org.junit.jupiter.api.Assertions.*;
import static customer.cap_java.ksef.inbound.InboundTestSupport.*;

import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** ORCH-08: rzeczywisty SDK/JDK i zerwane HTTP; licznik serwera wykrywa retry pod dekoratorem. */
public class OneShotWireRetryTest {
    @Test void approvedJvmFlagsPreventHiddenWireRetry() throws Exception { probe(true); }
    @Test void defaultJvmDemonstratesWhyDecoratorAloneIsInsufficient() throws Exception { probe(false); }

    private void probe(boolean protectedJvm) throws Exception {
        Path args = Files.createTempFile("orch08-java-", ".args");
        Path output = Files.createTempFile("orch08-wire-", ".log");
        try {
            var lines = new ArrayList<String>();
            if (protectedJvm) {
                lines.add("-Djdk.httpclient.disableRetryConnect=true");
                lines.add("-Djdk.httpclient.redirects.retrylimit=1");
            }
            lines.add("-cp");
            lines.add("\"" + System.getProperty("java.class.path").replace('\\', '/') + "\"");
            lines.add(getClass().getName());
            lines.add(Boolean.toString(protectedJvm));
            Files.write(args, lines);
            var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "@" + args).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try {
                assertTrue(child.waitFor(20, TimeUnit.SECONDS), "loopback child exceeded timeout");
                assertEquals(0, child.exitValue(), Files.readString(output));
                assertTrue(Files.readString(output).contains("WIRE_RETRY_PROBE_PASS"));
            } finally { if (child.isAlive()) { child.destroyForcibly(); } }
        } finally { Files.deleteIfExists(args); Files.deleteIfExists(output); }
    }

    /** Oddzielna JVM: właściwości JDK muszą obowiązywać przed inicjalizacją HttpClient. Tylko loopback. */
    public static void main(String[] args) throws Exception {
        boolean protectedJvm = Boolean.parseBoolean(args[0]);
        var observed = new AtomicInteger();
        try (var server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
                var executor = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(1500);
            Future<?> listener = executor.submit(() -> {
                try {
                    while (true) {
                        try (var socket = server.accept()) {
                            socket.setSoTimeout(3000);
                            var input = socket.getInputStream();
                            int state = 0;
                            while (state < 4) {
                                int value = input.read();
                                if (value < 0) { throw new java.io.EOFException(); }
                                state = switch (state) {
                                    case 0 -> value == 13 ? 1 : 0;
                                    case 1 -> value == 10 ? 2 : 0;
                                    case 2 -> value == 13 ? 3 : 0;
                                    default -> value == 10 ? 4 : 0;
                                };
                            }
                            observed.incrementAndGet(); // pełne żądanie odebrane, odpowiedź celowo utracona
                        }
                    }
                } catch (SocketTimeoutException finished) { /* brak dalszych żądań */ }
                catch (Exception failure) { throw new RuntimeException(failure); }
            });
            String origin = "http://127.0.0.1:" + server.getLocalPort();
            var settings = new KsefInboundOneShot.Settings(CONTEXT, KsefInboundOneShot.Scenario.NUMBER,
                    KsefInboundOneShot.AuthMethod.XADES, ksefNumber(1), null, 1);
            try (var transport = new OneShotHttpClient(HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2))
                    .followRedirects(HttpClient.Redirect.NEVER).build(), origin, settings)) {
                try {
                    KsefSdkConfiguration.createClient(origin, transport).getInvoice(settings.number(), "synthetic-token");
                    throw new AssertionError("unexpected HTTP success");
                } catch (RuntimeException expected) { /* SDK propaguje utraconą odpowiedź */ }
                listener.get(8, TimeUnit.SECONDS);
                if (transport.requests() != 1 || (protectedJvm ? observed.get() != 1 : observed.get() < 2)) {
                    throw new AssertionError("reserved=" + transport.requests() + " wire=" + observed.get());
                }
                System.out.println("WIRE_RETRY_PROBE_PASS protected=" + protectedJvm
                        + " reserved=" + transport.requests() + " wire=" + observed.get());
            }
        }
    }
}
