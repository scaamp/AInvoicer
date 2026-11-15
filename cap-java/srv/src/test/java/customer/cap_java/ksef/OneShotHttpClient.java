package customer.cap_java.ksef;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Dekorator transportu SDK: allowlista, budżet przed I/O i STOP po pierwszym błędzie. */
final class OneShotHttpClient extends HttpClient {
    private final HttpClient delegate;
    private final URI origin;
    private final KsefInboundOneShot.Settings settings;
    private final Map<String, Integer> counts = new HashMap<>();
    private int requests;
    private int lastStatus;
    private String stopped;
    private Instant retryNotBefore;
    private String invoiceRead = "NOT_CONFIRMED";

    OneShotHttpClient(HttpClient delegate, String origin, KsefInboundOneShot.Settings settings) {
        KsefInboundOneShot.require(delegate.followRedirects() == Redirect.NEVER, "REDIRECTS_FORBIDDEN");
        this.delegate = delegate;
        this.origin = URI.create(origin);
        this.settings = settings;
    }

    /** URI, metoda i limit są sprawdzane przed przekazaniem do klienta JDK; brak body/header w raporcie. */
    private synchronized void reserve(HttpRequest request) {
        KsefInboundOneShot.require(stopped == null, "TRANSPORT_STOPPED");
        URI uri = request.uri();
        boolean sameOrigin = Objects.equals(uri.getScheme(), origin.getScheme())
                && Objects.equals(uri.getHost(), origin.getHost()) && uri.getPort() == origin.getPort()
                && uri.getRawUserInfo() == null && uri.getRawFragment() == null;
        String path = uri.getRawPath();
        String operation = null;
        int cap = 1;
        if (sameOrigin) {
            if (request.method().equals("POST")) {
                if (path.equals("/v2/auth/challenge")) { operation = "challenge"; }
                if (path.equals("/v2/auth/token/redeem")) { operation = "redeem"; }
                if (path.equals(settings.authMethod() == KsefInboundOneShot.AuthMethod.TOKEN
                        ? "/v2/auth/ksef-token" : "/v2/auth/xades-signature")) { operation = "auth"; }
                if (settings.scenario() == KsefInboundOneShot.Scenario.BUYER
                        && path.equals("/v2/invoices/query/metadata")) { operation = "metadata"; }
            } else if (request.method().equals("GET")) {
                if (path.equals("/v2/security/public-key-certificates")
                        && settings.authMethod() == KsefInboundOneShot.AuthMethod.TOKEN) { operation = "certificates"; }
                if (path.matches("/v2/auth/[0-9]{8}-AU-[0-9A-F]{10}-[0-9A-F]{10}-[0-9A-F]{2}")) {
                    operation = "auth-status";
                    cap = 3;
                }
                if (path.startsWith("/v2/invoices/ksef/")) {
                    String number = path.substring("/v2/invoices/ksef/".length());
                    if (customer.cap_java.ksef.inbound.KsefNumbers.isValid(number)
                            && (settings.scenario() == KsefInboundOneShot.Scenario.BUYER || number.equals(settings.number()))) {
                        operation = "xml";
                    }
                }
            }
        }
        if (operation == null || requests >= settings.maxRequests() || counts.getOrDefault(operation, 0) >= cap) {
            stopped = operation == null ? "FORBIDDEN_REQUEST" : "REQUEST_LIMIT";
            throw KsefInboundOneShot.stopped(stopped);
        }
        counts.merge(operation, 1, Integer::sum);
        requests++;
    }

    @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        reserve(request);
        try {
            HttpResponse<T> response = delegate.send(request, handler);
            lastStatus = response.statusCode();
            if (request.uri().getPath().startsWith("/v2/invoices/")) {
                if (lastStatus == 200) { invoiceRead = "CONFIRMED"; }
                if (lastStatus == 403) { invoiceRead = "DENIED"; }
            }
            if (lastStatus < 200 || lastStatus >= 300) {
                stopped = "HTTP_FAILURE";
                if (lastStatus == 429) {
                    retryNotBefore = retryAfter(response.headers());
                }
            }
            return response;
        } catch (IOException | InterruptedException failure) {
            stopped = "IO_UNCERTAIN";
            throw failure;
        }
    }

    private static Instant retryAfter(HttpHeaders headers) {
        String value = headers.firstValue("Retry-After").orElse("");
        try { return Instant.now().plusSeconds(Math.max(0, Long.parseLong(value))); }
        catch (RuntimeException seconds) {
            try { return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
            catch (RuntimeException date) { return Instant.now().plusSeconds(60); }
        }
    }

    int requests() { return requests; }
    int lastStatus() { return lastStatus; }
    String stopReason() { return stopped == null ? "NONE" : stopped; }
    Instant retryNotBefore() { return retryNotBefore; }
    String invoiceRead() { return invoiceRead; }

    // SDK 3.0.29 korzysta z send; nie zezwalamy na alternatywną ścieżkę omijającą rezerwację.
    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) {
        throw KsefInboundOneShot.stopped("ASYNC_FORBIDDEN");
    }
    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h,
            HttpResponse.PushPromiseHandler<T> p) { throw KsefInboundOneShot.stopped("ASYNC_FORBIDDEN"); }
    @Override public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
    @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
    @Override public Redirect followRedirects() { return Redirect.NEVER; }
    @Override public Optional<ProxySelector> proxy() { return delegate.proxy(); }
    @Override public SSLContext sslContext() { return delegate.sslContext(); }
    @Override public SSLParameters sslParameters() { return delegate.sslParameters(); }
    @Override public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
    @Override public Version version() { return delegate.version(); }
    @Override public Optional<Executor> executor() { return delegate.executor(); }
    @Override public void close() { delegate.close(); }
}
