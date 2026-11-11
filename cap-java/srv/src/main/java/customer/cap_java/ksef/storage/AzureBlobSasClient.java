package customer.cap_java.ksef.storage;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;

import customer.cap_java.ksef.storage.ObjectStorageException.Category;

/**
 * Adapter Azure Blob Storage REST dla SAP BTP Object Store (landscape Azure) z container-scoped SAS.
 *
 * <p>Używane operacje (Azure Storage REST, {@code x-ms-version} {@value #API_VERSION}):
 * <ul>
 *   <li>Put Blob, BlockBlob, {@code If-None-Match: *} — tylko utworzenie; istniejący blob → 409/412,
 *       nigdy overwrite. {@code Content-MD5} każe usłudze odrzucić uszkodzony transport (400).</li>
 *   <li>Get Blob — pełny odczyt z limitem rozmiaru i kontrolą {@code Content-MD5} zwróconego przez usługę.</li>
 *   <li>List Blobs (prefix) — wyłącznie audyt orphan.</li>
 * </ul>
 * SAS jest gotowym tokenem z binding: klient niczego nie podpisuje i nie implementuje kryptografii.
 * Szyfrowanie at-rest zapewnia Azure Storage (klucze Microsoft-managed według dokumentacji SAP).
 * Przekierowania są wyłączone, aby SAS nie trafił na inny host; błędy nie zawierają URL ani SAS.
 */
public final class AzureBlobSasClient implements ObjectStorageClient {
    static final String API_VERSION = "2021-08-06";
    static final String META_PREFIX = "x-ms-meta-";
    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9/._-]{0,1000}");
    private static final Pattern META_NAME = Pattern.compile("[a-z][a-z0-9]{0,40}");
    private static final int MAX_LIST_RESPONSE_BYTES = 8 * 1024 * 1024;

    private final URI container;
    private final String sas;
    private final HttpClient http;
    private final java.time.Duration requestTimeout;

    public AzureBlobSasClient(AzureObjectStoreBinding binding, ObjectStorageSettings settings) {
        this.container = validateEndpoint(binding, settings);
        this.sas = binding.sasToken();
        this.requestTimeout = settings.requestTimeout();
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Endpoint wyłącznie HTTPS do dozwolonego hosta; http tylko dla loopback w testach. */
    static URI validateEndpoint(AzureObjectStoreBinding binding, ObjectStorageSettings settings) {
        URI uri = binding.containerUri();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = settings.allowLoopbackHttp() && "http".equals(uri.getScheme())
                && (host.equals("127.0.0.1") || host.equals("localhost"));
        boolean https = "https".equals(uri.getScheme()) && uri.getPort() == -1
                && settings.allowedHostSuffixes().stream().anyMatch(host::endsWith);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        // Ścieżka kontenera: /container lub (emulator) /account/container; ostatni segment = container_name.
        boolean pathOk = path.equals("/" + binding.containerName()) || loopback && path.endsWith("/" + binding.containerName());
        if (!(https || loopback) || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getRawUserInfo() != null || !pathOk) {
            throw new IllegalStateException("KSEF_DOCUMENT_STORE_ENDPOINT_INVALID");
        }
        return uri;
    }

    @Override public CreateOutcome createIfAbsent(String key, byte[] content, Map<String, String> metadata) {
        var builder = HttpRequest.newBuilder(blobUri(key))
                .timeout(requestTimeout)
                .header("x-ms-version", API_VERSION)
                .header("x-ms-blob-type", "BlockBlob")
                .header("If-None-Match", "*")
                .header("Content-Type", "application/xml")
                .header("Content-MD5", Base64.getEncoder().encodeToString(md5(content)))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content));
        metadata.forEach((name, value) -> {
            if (!META_NAME.matcher(name).matches() || !value.matches("[A-Za-z0-9._:-]{1,128}")) {
                throw new ObjectStorageException(Category.REJECTED, "STORAGE_METADATA_INVALID");
            }
            builder.header(META_PREFIX + name, value);
        });
        HttpResponse<byte[]> response = send(builder.build(), 64 * 1024);
        {
            int status = response.statusCode();
            if (status == 201) { return CreateOutcome.CREATED; }
            // 409 BlobAlreadyExists / 412 ConditionNotMet: obiekt istnieje, wywołujący zweryfikuje treść.
            if (status == 409 || status == 412) {
                String code = response.headers().firstValue("x-ms-error-code").orElse("");
                if (status == 412 || code.isEmpty() || "BlobAlreadyExists".equals(code)) {
                    return CreateOutcome.ALREADY_EXISTS;
                }
            }
            throw failure(response, "STORAGE_PUT");
        }
    }

    @Override public Optional<StoredObject> read(String key, int maxBytes) {
        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(blobUri(key))
                .timeout(requestTimeout).header("x-ms-version", API_VERSION).GET().build(), maxBytes);
        {
            int status = response.statusCode();
            if (status == 404) { return Optional.empty(); }
            if (status != 200) { throw failure(response, "STORAGE_GET"); }
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > maxBytes) { throw new ObjectStorageException(Category.TOO_LARGE, "STORAGE_OBJECT_TOO_LARGE"); }
            byte[] content = response.body();
            if (declared >= 0 && declared != content.length) {
                throw new ObjectStorageException(Category.UNAVAILABLE, "STORAGE_GET_TRUNCATED");
            }
            var md5 = response.headers().firstValue("Content-MD5");
            if (md5.isPresent() && !MessageDigest.isEqual(decode(md5.get()), md5(content))) {
                throw new ObjectStorageException(Category.CORRUPTED, "STORAGE_CONTENT_MD5_MISMATCH");
            }
            Map<String, String> metadata = new LinkedHashMap<>();
            response.headers().map().forEach((name, values) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.startsWith(META_PREFIX) && !values.isEmpty()) {
                    metadata.put(lower.substring(META_PREFIX.length()), values.getFirst());
                }
            });
            return Optional.of(new StoredObject(content, metadata));
        }
    }

    @Override public ListPage list(String prefix, String marker, int maxResults) {
        if (prefix == null || !KEY.matcher(prefix).matches() || prefix.contains("..") || maxResults < 1 || maxResults > 5000) {
            throw new ObjectStorageException(Category.REJECTED, "STORAGE_LIST_ARGUMENT_INVALID");
        }
        String query = "restype=container&comp=list&prefix=" + encode(prefix) + "&maxresults=" + maxResults
                + (marker == null || marker.isEmpty() ? "" : "&marker=" + encode(marker));
        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(URI.create(container + "?" + query + "&" + sas))
                .timeout(requestTimeout).header("x-ms-version", API_VERSION).GET().build(), MAX_LIST_RESPONSE_BYTES);
        {
            if (response.statusCode() != 200) { throw failure(response, "STORAGE_LIST"); }
            return parseList(response.body());
        }
    }

    /** Odporny parser odpowiedzi List Blobs: bez DTD/encji zewnętrznych. */
    static ListPage parseList(byte[] xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            var blobs = document.getElementsByTagName("Blob");
            List<ListedObject> objects = new ArrayList<>();
            for (int i = 0; i < blobs.getLength(); i++) {
                var blob = (Element) blobs.item(i);
                String name = child(blob, "Name");
                String length = child(blob, "Content-Length");
                String modified = child(blob, "Last-Modified");
                objects.add(new ListedObject(name, length == null ? -1 : Long.parseLong(length),
                        modified == null ? null : ZonedDateTime.parse(modified, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()));
            }
            var next = document.getElementsByTagName("NextMarker");
            String marker = next.getLength() == 0 ? null : next.item(0).getTextContent();
            return new ListPage(objects, marker == null || marker.isBlank() ? null : marker);
        } catch (ObjectStorageException e) {
            throw e;
        } catch (Exception malformed) {
            throw new ObjectStorageException(Category.REJECTED, "STORAGE_LIST_RESPONSE_INVALID");
        }
    }

    private static String child(Element parent, String name) {
        var nodes = parent.getElementsByTagName(name);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    private URI blobUri(String key) {
        if (key == null || !KEY.matcher(key).matches() || key.contains("..") || key.contains("//")) {
            throw new ObjectStorageException(Category.REJECTED, "STORAGE_KEY_INVALID");
        }
        return URI.create(container + "/" + key + "?" + sas);
    }

    /** Timeout/I/O = UNAVAILABLE; wynik zapisu może być nieznany, ale create-only czyni ponowienie bezpiecznym. */
    private HttpResponse<byte[]> send(HttpRequest request, int maxBytes) {
        var oversized = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            // INT-03: pełna odpowiedź ma deadline i limit bajtów. ofInputStream kończył timeout
            // na nagłówkach, więc powolny body mógł blokować worker bez końca.
            pending = http.sendAsync(request, info -> {
                var delegate = HttpResponse.BodySubscribers.limiting(HttpResponse.BodySubscribers.ofByteArray(), maxBytes);
                return new HttpResponse.BodySubscriber<byte[]>() {
                    private long received;
                    public java.util.concurrent.CompletionStage<byte[]> getBody() { return delegate.getBody(); }
                    public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { delegate.onSubscribe(subscription); }
                    public void onNext(List<java.nio.ByteBuffer> buffers) {
                        for (var buffer : buffers) received += buffer.remaining();
                        if (received > maxBytes) oversized.set(true);
                        delegate.onNext(buffers);
                    }
                    public void onError(Throwable error) { delegate.onError(error); }
                    public void onComplete() { delegate.onComplete(); }
                };
            });
            return pending.get(requestTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new ObjectStorageException(Category.UNAVAILABLE, "STORAGE_TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ObjectStorageException(Category.UNAVAILABLE, "STORAGE_INTERRUPTED");
        } catch (java.util.concurrent.ExecutionException io) {
            if (oversized.get()) { throw new ObjectStorageException(Category.TOO_LARGE, "STORAGE_OBJECT_TOO_LARGE"); }
            throw new ObjectStorageException(Category.UNAVAILABLE, "STORAGE_IO");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    /** Klasyfikacja statusu bez treści odpowiedzi; dopuszczamy tylko kod błędu Azure w bezpiecznym formacie. */
    private static ObjectStorageException failure(HttpResponse<?> response, String operation) {
        int status = response.statusCode();
        String code = response.headers().firstValue("x-ms-error-code").filter(c -> c.matches("[A-Za-z0-9]{1,64}")).orElse("");
        long retryAfter = response.headers().firstValue("Retry-After").filter(v -> v.matches("[0-9]{1,6}"))
                .map(Long::parseLong).orElse(-1L);
        Category category;
        if (status == 401 || status == 403) {
            category = Category.ACCESS_DENIED;
        } else if (status == 413) {
            category = Category.TOO_LARGE;
        } else if (status == 408 || status == 429 || status >= 500 || status == 400 && "Md5Mismatch".equals(code)) {
            category = Category.UNAVAILABLE;
        } else {
            category = Category.REJECTED;
        }
        return new ObjectStorageException(category, operation + "_HTTP_" + status + (code.isEmpty() ? "" : "_" + code), retryAfter);
    }

    private static byte[] decode(String base64) {
        try { return Base64.getDecoder().decode(base64); }
        catch (IllegalArgumentException invalid) { return new byte[0]; }
    }

    /** MD5 wyłącznie jako mechanizm integralności transportu Azure; bezpieczeństwo opiera się na SHA-256. */
    static byte[] md5(byte[] content) {
        try { return MessageDigest.getInstance("MD5").digest(content); }
        catch (Exception unavailable) { throw new IllegalStateException("MD5_UNAVAILABLE"); }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    @Override public String toString() { return "AzureBlobSasClient[sas=***]"; }
}
