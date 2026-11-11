package customer.cap_java.ksef.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import customer.cap_java.ksef.KsefFailure;
import customer.cap_java.ksef.inbound.KsefEnvironment;

/**
 * Opcjonalny test zgodności protokołu z oficjalnym emulatorem Microsoft Azurite (lokalnie, loopback).
 * Pomijany bez zmiennych środowiskowych: {@code AINV_AZURITE_CONTAINER_URI} (http://127.0.0.1:port/devstoreaccount1/container)
 * i {@code AINV_AZURITE_SAS} (service SAS sr=c, bez uprawnienia delete). Procedura: docs/guides/KSEF_DURABLE_STORAGE.md.
 * Azurite to emulator — nie dowodzi zachowania SAP BTP Object Store ani Azure Storage LIVE.
 */
@EnabledIfEnvironmentVariable(named = "AINV_AZURITE_CONTAINER_URI", matches = "http://127\\.0\\.0\\.1:[0-9]+/devstoreaccount1/[a-z0-9-]+")
class AzuriteCompatibilityTest {
    static final DocumentScope SCOPE = DocumentScope.outbound(KsefEnvironment.TEST, "1234563218");
    String containerUri;
    String containerName;
    String sas;
    ObjectStorageSettings settings;
    AzureBlobSasClient client;
    ContentAddressedDocumentStore store;
    final HttpClient raw = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeEach void setUp() {
        containerUri = System.getenv("AINV_AZURITE_CONTAINER_URI");
        containerName = containerUri.substring(containerUri.lastIndexOf('/') + 1);
        sas = System.getenv("AINV_AZURITE_SAS");
        settings = ObjectStorageSettings.defaults("azurite-" + UUID.randomUUID().toString().substring(0, 8),
                EnumSet.of(KsefEnvironment.TEST)).withLoopbackHttpForTests(Duration.ofSeconds(10));
        var binding = new AzureObjectStoreBinding(URI.create(containerUri), containerName, sas,
                AzureObjectStoreBinding.Access.READ_WRITE);
        client = new AzureBlobSasClient(binding, settings);
        store = new ContentAddressedDocumentStore(client, settings, binding.access(), Clock.systemUTC(), d -> { });
    }

    byte[] document(String marker) {
        return ("<Faktura><P_2>" + marker + "</P_2></Faktura>").getBytes(StandardCharsets.UTF_8);
    }

    String key(String reference) { return DocumentReference.parse(reference).objectKey(settings.keyPrefix()); }

    @Test void createOnlyPutIsIdempotentAndReadReturnsExactBytesWithMetadata() {
        byte[] xml = document("AZURITE-1");
        String key = DocumentReference.parse("ksefdoc:v1:TEST:OUTBOUND:1234563218:"
                + customer.cap_java.sap.KsefInvoiceSnapshotRegistry.sha256Hex(xml)).objectKey(settings.keyPrefix());
        assertEquals(ObjectStorageClient.CreateOutcome.CREATED, client.createIfAbsent(key, xml, Map.of("ainvsha256", "x")));
        assertEquals(ObjectStorageClient.CreateOutcome.ALREADY_EXISTS,
                client.createIfAbsent(key, document("OTHER"), Map.of("ainvsha256", "y")));
        var stored = client.read(key, 1024).orElseThrow();
        assertArrayEquals(xml, stored.content(), "409 nie nadpisał obiektu");
        assertEquals("x", stored.metadata().get("ainvsha256"));
        assertTrue(client.read(key + ".missing", 1024).isEmpty());
        var store2 = store.forScope(SCOPE);
        String reference = store2.put(document("AZURITE-2"));
        assertEquals(reference, store2.put(document("AZURITE-2")));
        assertArrayEquals(document("AZURITE-2"), store2.get(reference));
    }

    @Test void serviceRejectsWrongContentMd5WithDocumentedCode() throws Exception {
        var response = raw.send(HttpRequest.newBuilder(URI.create(containerUri + "/" + settings.keyPrefix() + "/md5.xml?" + sas))
                .header("x-ms-version", AzureBlobSasClient.API_VERSION).header("x-ms-blob-type", "BlockBlob")
                .header("Content-MD5", Base64.getEncoder().encodeToString(AzureBlobSasClient.md5(document("A"))))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(document("B"))).build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(400, response.statusCode());
        assertEquals("Md5Mismatch", response.headers().firstValue("x-ms-error-code").orElse(""));
    }

    @Test void thirdPartyOverwriteIsDetectedOnReadAndPut() throws Exception {
        var documents = store.forScope(SCOPE);
        String reference = documents.put(document("AZURITE-3"));
        var overwrite = raw.send(HttpRequest.newBuilder(URI.create(containerUri + "/" + key(reference) + "?" + sas))
                .header("x-ms-version", AzureBlobSasClient.API_VERSION).header("x-ms-blob-type", "BlockBlob")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(document("FORGED"))).build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(201, overwrite.statusCode());
        assertEquals("KSEF_DOCUMENT_INTEGRITY_FAILED", assertThrows(KsefFailure.class, () -> documents.get(reference)).getMessage());
        assertEquals("KSEF_DOCUMENT_INTEGRITY_FAILED",
                assertThrows(KsefFailure.class, () -> documents.put(document("AZURITE-3"))).getMessage());
    }

    @Test void invalidSignatureIsAccessDenied() {
        String forged = sas.replaceAll("sig=[^&]+", "sig=AAAA" + "A".repeat(40) + "%3D");
        var binding = new AzureObjectStoreBinding(URI.create(containerUri), containerName, forged,
                AzureObjectStoreBinding.Access.READ_WRITE);
        var denied = new ContentAddressedDocumentStore(new AzureBlobSasClient(binding, settings), settings,
                binding.access(), Clock.systemUTC(), d -> { }).forScope(SCOPE);
        assertEquals("KSEF_DOCUMENT_ACCESS_DENIED", assertThrows(KsefFailure.class, () -> denied.put(document("X"))).getMessage());
    }

    @Test void listingIsPagedWithServiceMarkers() {
        var documents = store.forScope(SCOPE);
        for (int i = 0; i < 5; i++) { documents.put(document("LIST-" + i)); }
        String prefix = DocumentReference.partitionPrefix(settings.keyPrefix(), SCOPE);
        int total = 0;
        String marker = null;
        int pages = 0;
        do {
            var page = client.list(prefix, marker, 2);
            total += page.objects().size();
            page.objects().forEach(o -> { assertNotNull(o.lastModified()); assertTrue(o.size() > 0); });
            marker = page.nextMarker();
            pages++;
        } while (marker != null);
        assertEquals(5, total);
        assertEquals(3, pages);
        var report = store.orphanReport(SCOPE, java.util.Set.of(), Duration.ZERO);
        assertEquals(5, report.scanned());
    }

    @Test void concurrentCreateOfSameContentProducesOneReference() throws Exception {
        var documents = store.forScope(SCOPE);
        byte[] xml = document("CONCURRENT");
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) { tasks.add(() -> documents.put(xml)); }
            var references = new HashSet<String>();
            for (var future : pool.invokeAll(tasks)) { references.add(future.get()); }
            assertEquals(1, references.size());
            assertArrayEquals(xml, documents.get(references.iterator().next()));
        } finally {
            pool.shutdownNow();
        }
    }
}
