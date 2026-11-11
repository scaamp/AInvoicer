package customer.cap_java.ksef.storage;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Lokalny emulator podzbioru Azure Blob REST używanego przez {@link AzureBlobSasClient}:
 * Put Blob (BlockBlob, If-None-Match:*, Content-MD5 → 400 Md5Mismatch, 409 BlobAlreadyExists),
 * Get Blob (Content-MD5, x-ms-meta-*), List Blobs (prefix, maxresults, marker) i kontrola SAS {@code sig}.
 * Semantyka według dokumentacji Microsoft; to NIE jest dowód zgodności z usługą SAP Object Store LIVE.
 */
public final class FakeAzureBlobServer implements AutoCloseable {
    public static final String ACCOUNT = "devstoreaccount1";
    public static final String CONTAINER = "ainv-test-zrs";
    public static final String SIGNATURE = "c3ludGhldGljLW5vdC1hLXJlYWwtc2Fz";
    public static final String SAS = "sv=2021-08-06&spr=https&si=synthetic-policy&sr=c&sig=" + SIGNATURE;

    public record Blob(byte[] content, byte[] md5, Map<String, String> metadata, Instant lastModified) { }
    record Logged(String method, String path, String query, Map<String, String> headers) { }

    public final Map<String, Blob> blobs = new ConcurrentHashMap<>();
    final List<Logged> log = new CopyOnWriteArrayList<>();
    public final AtomicInteger created = new AtomicInteger();
    public final AtomicInteger conflicts = new AtomicInteger();
    public final AtomicInteger failNextRequests = new AtomicInteger();
    volatile int failureStatus = 503;
    volatile String retryAfter;
    final AtomicInteger commitThenStallNextPuts = new AtomicInteger();
    volatile long stallMillis = 1500;
    volatile boolean readOnly;
    public volatile boolean revoked;
    volatile Instant now = Instant.parse("2026-11-11T08:00:00Z");
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(16);

    public FakeAzureBlobServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    int port() { return server.getAddress().getPort(); }

    String containerUri() { return "http://127.0.0.1:" + port() + "/" + ACCOUNT + "/" + CONTAINER; }

    /** Credentiale w formacie SAP Object Store (Azure) dla VCAP_SERVICES. */
    public String vcapServices(String role) {
        return "{\"objectstore\":[{\"label\":\"objectstore\",\"name\":\"ainv-docs\",\"plan\":\"standard\","
                + "\"tags\":[\"blobStore\",\"objectStore\"],\"credentials\":{\"account_name\":\"" + ACCOUNT
                + "\",\"container_name\":\"" + CONTAINER + "\",\"container_uri\":\"" + containerUri()
                + "\",\"sas_token\":\"" + SAS + "\",\"region\":\"westeurope\""
                + (role == null ? "" : ",\"role\":\"" + role + "\"") + "}}]}";
    }

    public String keyOf(String reference, String prefix) {
        return DocumentReference.parse(reference).objectKey(prefix);
    }

    /** Podmiana przez stronę trzecią z uprawnieniem zapisu (poprawne MD5 nowej treści). */
    void replace(String key, byte[] content, Map<String, String> metadata) {
        blobs.put(key, new Blob(content.clone(), md5(content), Map.copyOf(metadata), now));
    }

    /** Uszkodzenie bajtów "na dysku": MD5 z chwili zapisu nie pasuje do treści. */
    public void corrupt(String key) {
        Blob blob = blobs.get(key);
        byte[] damaged = blob.content().clone();
        damaged[damaged.length / 2] ^= 0x20;
        blobs.put(key, new Blob(damaged, blob.md5(), blob.metadata(), blob.lastModified()));
    }

    long count(String method) { return log.stream().filter(l -> l.method().equals(method)).count(); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String rawPath = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.getFirst()));
            log.add(new Logged(exchange.getRequestMethod(), rawPath, query, Map.copyOf(headers)));
            byte[] body = exchange.getRequestBody().readAllBytes();
            Map<String, String> params = params(query);
            if (revoked || !SIGNATURE.equals(params.get("sig"))) {
                respond(exchange, 403, "AuthenticationFailed", new byte[0], Map.of());
                return;
            }
            if (!AzureBlobSasClient.API_VERSION.equals(headers.get("x-ms-version"))) {
                respond(exchange, 400, "MissingRequiredHeader", new byte[0], Map.of());
                return;
            }
            if (failNextRequests.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                respond(exchange, failureStatus, failureStatus == 503 ? "ServerBusy" : "InternalError", new byte[0],
                        retryAfter == null ? Map.of() : Map.of("Retry-After", retryAfter));
                return;
            }
            String base = "/" + ACCOUNT + "/" + CONTAINER;
            if (rawPath.equals(base) && "list".equals(params.get("comp"))) {
                list(exchange, params);
                return;
            }
            if (!rawPath.startsWith(base + "/")) {
                respond(exchange, 404, "ContainerNotFound", new byte[0], Map.of());
                return;
            }
            String key = rawPath.substring(base.length() + 1);
            switch (exchange.getRequestMethod()) {
                case "PUT" -> put(exchange, key, headers, body);
                case "GET" -> get(exchange, key);
                default -> respond(exchange, 405, "UnsupportedHttpVerb", new byte[0], Map.of());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void put(HttpExchange exchange, String key, Map<String, String> headers, byte[] body)
            throws IOException, InterruptedException {
        if (readOnly) {
            respond(exchange, 403, "AuthorizationPermissionMismatch", new byte[0], Map.of());
            return;
        }
        if (!"BlockBlob".equals(headers.get("x-ms-blob-type"))) {
            respond(exchange, 400, "MissingRequiredHeader", new byte[0], Map.of());
            return;
        }
        String md5 = headers.get("content-md5");
        if (md5 != null && !MessageDigest.isEqual(Base64.getDecoder().decode(md5), md5(body))) {
            respond(exchange, 400, "Md5Mismatch", new byte[0], Map.of());
            return;
        }
        Map<String, String> metadata = new TreeMap<>();
        headers.forEach((k, v) -> { if (k.startsWith("x-ms-meta-")) { metadata.put(k.substring(10), v); } });
        Blob blob = new Blob(body, md5(body), Map.copyOf(metadata), now);
        boolean createOnly = "*".equals(headers.get("if-none-match"));
        Blob previous = createOnly ? blobs.putIfAbsent(key, blob) : blobs.put(key, blob);
        if (createOnly && previous != null) {
            conflicts.incrementAndGet();
            respond(exchange, 409, "BlobAlreadyExists", new byte[0], Map.of());
            return;
        }
        created.incrementAndGet();
        if (commitThenStallNextPuts.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            Thread.sleep(stallMillis); // zapis jest już trwały, klient dostaje timeout
        }
        respond(exchange, 201, null, new byte[0], Map.of("Content-MD5", Base64.getEncoder().encodeToString(blob.md5())));
    }

    private void get(HttpExchange exchange, String key) throws IOException {
        Blob blob = blobs.get(key);
        if (blob == null) {
            respond(exchange, 404, "BlobNotFound", new byte[0], Map.of());
            return;
        }
        Map<String, String> extra = new HashMap<>();
        extra.put("Content-MD5", Base64.getEncoder().encodeToString(blob.md5()));
        blob.metadata().forEach((k, v) -> extra.put("x-ms-meta-" + k, v));
        respond(exchange, 200, null, blob.content(), extra);
    }

    private void list(HttpExchange exchange, Map<String, String> params) throws IOException {
        String prefix = params.getOrDefault("prefix", "");
        String marker = params.get("marker");
        int max = Integer.parseInt(params.getOrDefault("maxresults", "5000"));
        List<String> keys = new ArrayList<>(new TreeMap<>(blobs).keySet().stream()
                .filter(k -> k.startsWith(prefix) && (marker == null || k.compareTo(marker) >= 0)).toList());
        var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><EnumerationResults><Blobs>");
        for (String key : keys.subList(0, Math.min(max, keys.size()))) {
            Blob blob = blobs.get(key);
            xml.append("<Blob><Name>").append(key).append("</Name><Properties><Last-Modified>")
                    .append(DateTimeFormatter.RFC_1123_DATE_TIME.format(blob.lastModified().atZone(ZoneOffset.UTC)))
                    .append("</Last-Modified><Content-Length>").append(blob.content().length)
                    .append("</Content-Length></Properties></Blob>");
        }
        xml.append("</Blobs><NextMarker>").append(keys.size() > max ? keys.get(max) : "").append("</NextMarker></EnumerationResults>");
        respond(exchange, 200, null, xml.toString().getBytes(StandardCharsets.UTF_8), Map.of("Content-Type", "application/xml"));
    }

    private static void respond(HttpExchange exchange, int status, String errorCode, byte[] body, Map<String, String> headers)
            throws IOException {
        headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        if (errorCode != null) { exchange.getResponseHeaders().add("x-ms-error-code", errorCode); }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) { exchange.getResponseBody().write(body); }
    }

    private static Map<String, String> params(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null) { return result; }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                result.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    static byte[] md5(byte[] content) { return AzureBlobSasClient.md5(content); }

    @Override public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
