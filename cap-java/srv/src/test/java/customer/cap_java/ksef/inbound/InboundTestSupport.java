package customer.cap_java.ksef.inbound;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import pl.akmf.ksef.sdk.api.DefaultKsefClient;
import pl.akmf.ksef.sdk.api.KsefApiProperties;
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient;
import pl.akmf.ksef.sdk.client.model.KsefApiException;
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceMetadata;
import pl.akmf.ksef.sdk.client.model.invoice.QueryInvoiceMetadataResponse;
import pl.akmf.ksef.sdk.system.circuitbreaker.KsefCircuitBreakerOptions;
import pl.akmf.ksef.sdk.system.circuitbreaker.KsefCircuitBreakerProperties;

/**
 * Wyłącznie syntetyczne dane i lokalne zaślepki KSeF (127.0.0.1). Brak realnych NIP-ów podmiotów,
 * faktur, tokenów i wywołań KSeF. NIP-y mają poprawną sumę kontrolną, ale są wzorcami (1111111111 itd.).
 */
public final class InboundTestSupport {
    public static final String CONTEXT_NIP = "1111111111";
    public static final String SELLER_NIP = "2222222222";
    public static final String OTHER_NIP = "3333333333";
    public static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    public static final KsefTaxpayerContext CONTEXT = new KsefTaxpayerContext(KsefEnvironment.TEST, CONTEXT_NIP);
    public static final String FAKE_JWT = "synthetic.jwt.MARKER-not-a-real-token";
    public static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private InboundTestSupport() {
    }

    public static InboundAccessToken token() {
        return InboundAccessToken.issuedFor(CONTEXT, FAKE_JWT);
    }

    /** Numer KSeF z sumą CRC-8 (wielomian 0x07, init 0x00) policzoną niezależnie od SDK. */
    public static String ksefNumber(String sellerNip, String yyyymmdd, String technical12) {
        String data = sellerNip + "-" + yyyymmdd + "-" + technical12;
        int crc = 0;
        for (byte b : data.getBytes(StandardCharsets.US_ASCII)) {
            crc ^= b & 0xFF;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
            }
        }
        return data + "-" + String.format("%02X", crc);
    }

    public static String ksefNumber(int sequence) {
        return ksefNumber(SELLER_NIP, "20261101", String.format("%012X", sequence));
    }

    /**
     * Klient SDK utworzony dokładnie przez produkcyjną fabrykę {@code KsefSdkConfiguration.createClient}
     * (Redirect.NEVER, timeout 20 s, wyłączony circuit breaker). Metoda jest package-private w pakiecie
     * {@code ksef}; refleksja pozwala testować produkcyjną konfigurację bez zmiany współdzielonej klasy.
     */
    public static KSeFClient productionClient(String origin) {
        try {
            Class<?> configuration = Class.forName("customer.cap_java.ksef.KsefSdkConfiguration");
            Method factory = configuration.getDeclaredMethod("createClient", String.class);
            factory.setAccessible(true);
            return (KSeFClient) factory.invoke(null, origin);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("KsefSdkConfiguration.createClient(String) contract changed", e);
        }
    }

    /** Ta sama konfiguracja co produkcyjna, ale z krótkim timeoutem żądania — wyłącznie do testu timeoutu. */
    public static KSeFClient shortTimeoutClient(String origin, Duration timeout) {
        var properties = new KsefApiProperties() {
            public String getBaseUri() { return origin; }
            public String getSuffixUri() { return "v2"; }
            public String getQrUri() { return "http://127.0.0.1"; }
            public Duration getRequestTimeout() { return timeout; }
            public Map<String, String> getDefaultHeaders() { return Map.of(); }
        };
        var breaker = new KsefCircuitBreakerProperties() {
            public KsefCircuitBreakerOptions getCircuitBreakerOptions() {
                var options = new KsefCircuitBreakerOptions();
                options.setEnabled(false);
                return options;
            }
        };
        return new DefaultKsefClient(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build(), properties, breaker, JSON);
    }

    public static KsefInboundClient inboundClient(KSeFClient sdk) {
        return new KsefInboundClient(sdk, KsefEnvironment.TEST, KsefInboundClient.DEFAULT_MAX_INVOICE_BYTES, CLOCK);
    }

    public static byte[] resource(String name) {
        try (InputStream in = InboundTestSupport.class.getResourceAsStream("/ksef/inbound/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- JSON w kształcie OpenAPI KSeF 2.0

    /** Pozycja {@code InvoiceMetadata} z nazwami pól z oficjalnego OpenAPI (nie z modeli SDK). */
    public static ObjectNode metadataJson(String ksefNumber, String permanentStorage, String invoiceHashBase64) {
        ObjectNode item = JSON.createObjectNode();
        item.put("ksefNumber", ksefNumber);
        item.put("invoiceNumber", "FZ/2026/11/" + ksefNumber.substring(28, 32));
        item.put("issueDate", "2026-11-02");
        item.put("invoicingDate", "2026-11-02T08:00:00.123+01:00");
        item.put("acquisitionDate", "2026-11-02T08:00:05+01:00");
        item.put("permanentStorageDate", permanentStorage);
        item.putObject("seller").put("nip", SELLER_NIP).put("name", "Syntetyczny Sprzedawca Sp. z o.o.");
        ObjectNode buyer = item.putObject("buyer");
        buyer.putObject("identifier").put("type", "Nip").put("value", CONTEXT_NIP);
        buyer.put("name", "Syntetyczny Nabywca S.A.");
        item.put("netAmount", 1000.10);
        item.put("grossAmount", 1230.12);
        item.put("vatAmount", 230.02);
        item.put("currency", "PLN");
        item.put("invoicingMode", "Online");
        item.put("invoiceType", "Vat");
        item.putObject("formCode").put("systemCode", "FA (3)").put("schemaVersion", "1-0E").put("value", "FA");
        item.put("isSelfInvoicing", false);
        item.put("hasAttachment", false);
        item.put("invoiceHash", invoiceHashBase64);
        return item;
    }

    /** SHA-256 pustego ciągu w Base64 — poprawny format {@code invoiceHash} (44 znaki, 32 bajty). */
    public static final String SOME_HASH = "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=";

    // ---------------------------------------------------------------- modele SDK (dla mocków KSeFClient)

    /** Model SDK zdeserializowany z JSON w kształcie OpenAPI — ta sama ścieżka co odpowiedź HTTP. */
    public static InvoiceMetadata sdkItem(ObjectNode json) {
        try {
            return JSON.treeToValue(json, InvoiceMetadata.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static InvoiceMetadata item(int sequence, String permanentStorage) {
        return sdkItem(metadataJson(ksefNumber(sequence), permanentStorage, SOME_HASH));
    }

    public static QueryInvoiceMetadataResponse response(boolean hasMore, boolean truncated, String hwm,
            InvoiceMetadata... items) {
        return new QueryInvoiceMetadataResponse(hasMore, truncated, new ArrayList<>(List.of(items)),
                hwm == null ? null : OffsetDateTime.parse(hwm));
    }

    /** Wyjątek SDK taki, jaki tworzy {@code ExceptionHandler} dla odpowiedzi spoza 2xx. */
    public static KsefApiException apiError(int code, Map<String, String> headers) {
        var map = new HashMap<String, List<String>>();
        headers.forEach((k, v) -> map.put(k, List.of(v)));
        return new KsefApiException(code, "body-with-SECRET", HttpHeaders.of(map, (a, b) -> true), null);
    }

    public static String hashOf(byte[] bytes) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] page(boolean hasMore, boolean truncated, String hwm, List<ObjectNode> items) {
        ObjectNode page = JSON.createObjectNode();
        page.put("hasMore", hasMore);
        page.put("isTruncated", truncated);
        if (hwm != null) {
            page.put("permanentStorageHwmDate", hwm);
        }
        ArrayNode invoices = page.putArray("invoices");
        items.forEach(invoices::add);
        try {
            return JSON.writeValueAsBytes(page);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Body błędu w formacie legacy {@code ExceptionResponse} z kodem MF. */
    public static byte[] legacyError(int exceptionCode, String description) {
        String json = "{\"exception\":{\"exceptionDetailList\":[{\"exceptionCode\":" + exceptionCode
                + ",\"exceptionDescription\":\"" + description + "\",\"details\":[\"SECRET-BODY-DETAIL\"]}],"
                + "\"referenceNumber\":\"20261109-EX-0000000000-0000000000-00\",\"serviceCode\":\"x\"}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- lokalny serwer HTTP

    /** Lokalny serwer zastępujący KSeF; rejestruje żądania. */
    public static final class Stub implements AutoCloseable {
        public record Request(String method, String path, String query, String authorization, String body) {
        }

        @FunctionalInterface
        public interface Handler {
            void handle(HttpExchange exchange, int call) throws IOException;
        }

        private final HttpServer server;
        private final List<Request> requests = new CopyOnWriteArrayList<>();
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        public Stub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.start();
        }

        public Stub on(String path, Handler handler) {
            server.createContext(path, exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders().getFirst("Authorization"),
                        body));
                int call = calls.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
                try {
                    handler.handle(exchange, call);
                } finally {
                    exchange.close();
                }
            });
            return this;
        }

        public String origin() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        public List<Request> requests() {
            return requests;
        }

        @Override
        public void close() {
            server.stop(0);
        }

        public static void respond(HttpExchange exchange, int status, String contentType, byte[] body,
                Map<String, String> headers) throws IOException {
            if (contentType != null) {
                exchange.getResponseHeaders().add("Content-Type", contentType);
            }
            headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            if (body == null || body.length == 0) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
        }

        public static void json(HttpExchange exchange, int status, byte[] body) throws IOException {
            respond(exchange, status, "application/json", body, Map.of());
        }
    }
}
