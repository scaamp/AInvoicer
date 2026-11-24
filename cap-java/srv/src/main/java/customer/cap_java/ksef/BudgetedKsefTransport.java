package customer.cap_java.ksef;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyManagementException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLContextSpi;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BETA-02 (manual import INBOUND, TEST): transport dla SDK MF z <b>twardym budżetem fizycznych żądań HTTP</b>
 * na jeden zatwierdzony przebieg. Wstrzykiwany przez istniejący punkt {@code DefaultKsefClient(HttpClient, …)};
 * SDK i jego serializacja pozostają bez zmian. Dotyczy wyłącznie klienta manual importu — OUTBOUND i synchronizacja
 * nadal używają wspólnego beana {@code KSeFClient} (brak globalnych flag JVM).
 *
 * <p>Dlaczego nie wystarcza licznik wokół {@code send()}: {@code jdk.internal.net.http.MultiExchange} może po utracie
 * odpowiedzi ponowić idempotentny GET na <i>nowym połączeniu</i> wewnątrz tego samego {@code send()}
 * ({@code OneShotWireRetryTest}). Flagi {@code jdk.httpclient.*} działają tylko dla całej JVM.
 *
 * <p>Mechanizm (bez flag JVM i bez prywatnych API JDK):
 * <ol>
 *   <li>przed każdym żądaniem: STOP po wcześniejszym błędzie, allowlista origin (wyłącznie {@code https}, ten sam host
 *       i port, bez userinfo/fragmentu), limit liczby żądań, termin przebiegu i fencing lease ({@code fence});</li>
 *   <li>każde dopuszczone żądanie wykonuje <b>świeży</b> {@code HttpClient} (pusta pula, HTTP/1.1, bez redirectów,
 *       bez proxy/Authenticator), więc pierwsza próba zawsze otwiera nowe połączenie TLS;</li>
 *   <li>{@code SSLContext} tego klienta wydaje dokładnie <b>jeden</b> {@code SSLEngine}. Ukryty replay lub ponowny
 *       connect wymaga nowego połączenia TLS — drugi silnik jest odmówiony, zanim jakikolwiek bajt żądania opuści JVM;</li>
 *   <li>timeout żądania = min(timeout SDK, pozostały czas przebiegu); connect timeout z {@link Limits}.</li>
 * </ol>
 * Stąd: liczba fizycznych żądań ≤ liczba połączeń TLS ≤ {@link Limits#maxRequests()}. Każdy błąd I/O, odmowa lub
 * odpowiedź spoza 2xx zatrzymuje transport do końca przebiegu (brak automatycznych ponowień). Wynik niejednoznaczny
 * (I/O po wysłaniu) klasyfikuje wywołujący ({@code KsefFailure}); dla auth oznacza to BLOCKED lease.
 *
 * <p>Ograniczenia (świadome): obsługiwane są wyłącznie {@code send} z ciałem odczytanym przed powrotem
 * (SDK 3.0.29 używa {@code BodyHandlers.ofByteArray}); {@code sendAsync} jest odrzucane. Do logów trafia tylko kod
 * zatrzymania i liczniki — nigdy URI, nagłówki, body, token ani NIP.
 */
public final class BudgetedKsefTransport extends HttpClient {
    private static final Logger log = LoggerFactory.getLogger(BudgetedKsefTransport.class);
    /** Górna granica z analizy KSEF-IN-05: certyfikaty, challenge, auth, status ≤3, redeem, metadata, GET. */
    public static final int MAX_REQUESTS_CEILING = 9;

    /** Limity jednego przebiegu; walidowane przy tworzeniu, aby błędna konfiguracja nie dopuściła HTTP. */
    public record Limits(int maxRequests, Duration maxDuration, Duration connectTimeout) {
        public Limits {
            if (maxRequests < 1 || maxRequests > MAX_REQUESTS_CEILING || maxDuration == null || connectTimeout == null
                    || maxDuration.compareTo(Duration.ofSeconds(1)) < 0 || maxDuration.compareTo(Duration.ofMinutes(5)) > 0
                    || connectTimeout.isNegative() || connectTimeout.isZero() || connectTimeout.compareTo(maxDuration) > 0) {
                throw new IllegalArgumentException("KSEF_TRANSPORT_LIMITS_INVALID");
            }
        }
    }

    /** Powód zatrzymania; {@code NONE} = transport nadal przyjmuje żądania w ramach budżetu. */
    public enum Stop {
        NONE, BUDGET_EXHAUSTED, DEADLINE, FENCE_LOST, FORBIDDEN_REQUEST, ASYNC_FORBIDDEN,
        HTTP_FAILURE, IO_UNCERTAIN, REPLAY_BLOCKED
    }

    /** Odmowa przed I/O: żądanie NIE zostało wysłane. SDK opakuje ją w swój wyjątek I/O. */
    public static final class Refused extends IOException {
        private final Stop reason;
        Refused(Stop reason) { super("KSEF_TRANSPORT_REFUSED_" + reason); this.reason = reason; }
        public Stop reason() { return reason; }
    }

    private final URI origin;
    private final Limits limits;
    private final Clock clock;
    private final Runnable fence;
    private final SSLContext tls;
    private final Instant deadline;
    private int reserved;
    private int connections;
    private Stop stopped = Stop.NONE;

    /**
     * @param origin wyłącznie {@code https://host[:port]} (np. {@link KsefSdkConfiguration#TEST_ORIGIN})
     * @param fence  kontrola właściciela lease przed każdym żądaniem; wyjątek = {@link Stop#FENCE_LOST}
     * @param tls    bazowy kontekst TLS (produkcyjnie {@code SSLContext.getDefault()})
     */
    public BudgetedKsefTransport(String origin, Limits limits, Clock clock, Runnable fence, SSLContext tls) {
        this.origin = URI.create(Objects.requireNonNull(origin));
        if (!"https".equals(this.origin.getScheme()) || this.origin.getHost() == null || this.origin.getRawUserInfo() != null
                || (this.origin.getRawPath() != null && !this.origin.getRawPath().isEmpty())) {
            throw new IllegalArgumentException("KSEF_TRANSPORT_ORIGIN_INVALID");
        }
        this.limits = Objects.requireNonNull(limits);
        this.clock = Objects.requireNonNull(clock);
        this.fence = Objects.requireNonNull(fence);
        this.tls = Objects.requireNonNull(tls);
        this.deadline = clock.instant().plus(limits.maxDuration());
    }

    /** Jedno żądanie naraz; synchronizacja obejmuje I/O, bo przebieg manual importu jest sekwencyjny. */
    @Override
    public synchronized <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        Duration timeout = reserve(request);
        var guard = new SingleConnectionTls(tls);
        HttpRequest bounded = HttpRequest.newBuilder(request, (name, value) -> true).timeout(timeout).build();
        try (HttpClient wire = HttpClient.newBuilder().version(Version.HTTP_1_1).followRedirects(Redirect.NEVER)
                .connectTimeout(limits.connectTimeout()).sslContext(guard.context()).build()) {
            HttpResponse<T> response;
            try {
                response = wire.send(bounded, handler);
            } finally {
                connections += guard.engines();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                stop(Stop.HTTP_FAILURE);
            }
            return response;
        } catch (InterruptedException interrupted) {
            stop(guard.replayBlocked() ? Stop.REPLAY_BLOCKED : Stop.IO_UNCERTAIN);
            throw interrupted;
        } catch (IOException | RuntimeException failure) {
            stop(guard.replayBlocked() ? Stop.REPLAY_BLOCKED : Stop.IO_UNCERTAIN);
            // Bez komunikatu/przyczyny JDK (może zawierać host lub URI); wynik żądania jest nieznany.
            throw new IOException("KSEF_TRANSPORT_" + stopped);
        }
    }

    /** Rezerwacja przed I/O; zwraca timeout żądania ograniczony terminem przebiegu. */
    private Duration reserve(HttpRequest request) throws Refused {
        if (stopped != Stop.NONE) { throw new Refused(stopped); }
        URI uri = request.uri();
        boolean sameOrigin = "https".equals(uri.getScheme()) && Objects.equals(uri.getHost(), origin.getHost())
                && uri.getPort() == origin.getPort() && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                && ("GET".equals(request.method()) || "POST".equals(request.method()));
        if (!sameOrigin) { throw refuse(Stop.FORBIDDEN_REQUEST); }
        if (reserved >= limits.maxRequests()) { throw refuse(Stop.BUDGET_EXHAUSTED); }
        Duration remaining = Duration.between(clock.instant(), deadline);
        if (remaining.compareTo(Duration.ofMillis(1)) < 0) { throw refuse(Stop.DEADLINE); }
        try {
            fence.run();
        } catch (RuntimeException lost) {
            throw refuse(Stop.FENCE_LOST);
        }
        reserved++;
        Duration requested = request.timeout().orElse(remaining);
        return requested.compareTo(remaining) < 0 ? requested : remaining;
    }

    private Refused refuse(Stop reason) {
        stop(reason);
        return new Refused(reason);
    }

    private void stop(Stop reason) {
        if (stopped == Stop.NONE) {
            stopped = reason;
            log.warn("KSEF_MANUAL_IMPORT_TRANSPORT_STOP reason={} reserved={} connections={} max={}",
                    reason, reserved, connections, limits.maxRequests());
        }
    }

    /** Liczba dopuszczonych żądań (rezerwacji) w przebiegu. */
    public synchronized int reserved() { return reserved; }
    /** Liczba utworzonych połączeń TLS = górna granica fizycznych żądań. */
    public synchronized int connections() { return connections; }
    public synchronized Stop stopReason() { return stopped; }

    @Override public String toString() {
        return "BudgetedKsefTransport[reserved=" + reserved() + ", stop=" + stopReason() + "]";
    }

    // Alternatywne ścieżki omijające rezerwację są zabronione; SDK 3.0.29 używa wyłącznie send.
    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) {
        return asyncForbidden();
    }
    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h,
            HttpResponse.PushPromiseHandler<T> p) {
        return asyncForbidden();
    }
    private synchronized <T> CompletableFuture<HttpResponse<T>> asyncForbidden() {
        return CompletableFuture.failedFuture(refuse(Stop.ASYNC_FORBIDDEN));
    }
    @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
    @Override public Optional<Duration> connectTimeout() { return Optional.of(limits.connectTimeout()); }
    @Override public Redirect followRedirects() { return Redirect.NEVER; }
    @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
    @Override public SSLContext sslContext() { return tls; }
    @Override public SSLParameters sslParameters() { return tls.getDefaultSSLParameters(); }
    @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
    @Override public Version version() { return Version.HTTP_1_1; }
    @Override public Optional<Executor> executor() { return Optional.empty(); }

    /**
     * Kontekst TLS wydający najwyżej jeden {@link SSLEngine}: JDK tworzy silnik przy każdym nowym połączeniu
     * ({@code AbstractAsyncSSLConnection}), więc drugi silnik = próba replay/reconnect i zostaje odrzucony przed I/O.
     */
    static final class SingleConnectionTls extends SSLContextSpi {
        private final SSLContext base;
        private final AtomicInteger engines = new AtomicInteger();
        private final AtomicBoolean replayBlocked = new AtomicBoolean();

        SingleConnectionTls(SSLContext base) { this.base = base; }

        SSLContext context() {
            return new SSLContext(this, base.getProvider(), base.getProtocol()) { };
        }

        int engines() { return Math.min(engines.get(), 1); }
        boolean replayBlocked() { return replayBlocked.get(); }

        private SSLEngine permit(java.util.function.Supplier<SSLEngine> create) {
            if (engines.incrementAndGet() > 1) {
                replayBlocked.set(true);
                throw new IllegalStateException("KSEF_TRANSPORT_SECOND_CONNECTION_REFUSED");
            }
            return create.get();
        }

        @Override protected SSLEngine engineCreateSSLEngine() { return permit(base::createSSLEngine); }
        @Override protected SSLEngine engineCreateSSLEngine(String host, int port) {
            return permit(() -> base.createSSLEngine(host, port));
        }
        @Override protected void engineInit(KeyManager[] km, TrustManager[] tm, SecureRandom random)
                throws KeyManagementException {
            throw new KeyManagementException("KSEF_TRANSPORT_TLS_IMMUTABLE");
        }
        // Gniazda blokujące nie są używane przez java.net.http; odmowa zamyka obejście licznika silników.
        @Override protected SSLSocketFactory engineGetSocketFactory() { throw new UnsupportedOperationException(); }
        @Override protected SSLServerSocketFactory engineGetServerSocketFactory() { throw new UnsupportedOperationException(); }
        @Override protected SSLSessionContext engineGetServerSessionContext() { return base.getServerSessionContext(); }
        @Override protected SSLSessionContext engineGetClientSessionContext() { return base.getClientSessionContext(); }
        @Override protected SSLParameters engineGetDefaultSSLParameters() { return base.getDefaultSSLParameters(); }
        @Override protected SSLParameters engineGetSupportedSSLParameters() { return base.getSupportedSSLParameters(); }
    }
}
