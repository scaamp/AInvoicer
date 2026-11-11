package customer.cap_java.ksef.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.sun.net.httpserver.HttpServer;

import customer.cap_java.ksef.KsefDocumentStore;
import customer.cap_java.ksef.KsefFailure;
import customer.cap_java.ksef.inbound.KsefEnvironment;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;

/** Prawdziwy klient HTTP + lokalny emulator protokołu Azure Blob. Bez credentiali BTP i bez sieci zewnętrznej. */
class ContentAddressedDocumentStoreTest {
    static final Instant NOW = Instant.parse("2026-11-11T09:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final String NIP_A = "1234563218";
    static final String NIP_B = "5260002008";
    static final DocumentScope OUT_A = DocumentScope.outbound(KsefEnvironment.TEST, NIP_A);
    static final DocumentScope OUT_B = DocumentScope.outbound(KsefEnvironment.TEST, NIP_B);
    static final DocumentScope IN_TEST = DocumentScope.inbound(KsefEnvironment.TEST);

    FakeAzureBlobServer fake;
    List<Duration> sleeps;
    ObjectStorageSettings settings;
    byte[] xml;

    @BeforeEach void start() throws IOException {
        fake = new FakeAzureBlobServer();
        sleeps = new CopyOnWriteArrayList<>();
        settings = ObjectStorageSettings.defaults("ainvoicer-test", EnumSet.of(KsefEnvironment.TEST))
                .withLoopbackHttpForTests(Duration.ofSeconds(2));
        xml = "<Faktura xmlns=\"http://crd.gov.pl/wzor/2025/06/25/13775/\"><P_2>FV/STOR/1</P_2></Faktura>"
                .getBytes(StandardCharsets.UTF_8);
    }

    @AfterEach void stop() { fake.close(); }

    ContentAddressedDocumentStore store(ObjectStorageSettings custom, String role) {
        var binding = AzureObjectStoreBinding.fromVcapServices(fake.vcapServices(role), null);
        return new ContentAddressedDocumentStore(new AzureBlobSasClient(binding, custom), custom, binding.access(),
                CLOCK, sleeps::add);
    }

    ContentAddressedDocumentStore store() { return store(settings, null); }

    static KsefFailure failure(String code, Executable action) {
        var error = assertThrows(KsefFailure.class, action);
        assertEquals(code, error.getMessage());
        assertNull(error.getCause());
        assertFalse(error.toString().contains(FakeAzureBlobServer.SIGNATURE));
        assertFalse(error.toString().contains("sig="));
        return error;
    }

    @Test void putIsIdempotentCreateOnlyAndGetReturnsExactBytes() {
        KsefDocumentStore documents = store().forScope(OUT_A);
        String first = documents.put(xml);
        String second = documents.put(xml.clone());
        assertEquals(first, second);
        assertEquals("ksefdoc:v1:TEST:OUTBOUND:" + NIP_A + ":" + KsefInvoiceSnapshotRegistry.sha256Hex(xml), first);
        assertTrue(first.length() <= 500);
        assertArrayEquals(xml, documents.get(first));
        assertEquals(1, fake.created.get());
        assertEquals(1, fake.conflicts.get());
        assertEquals(1, fake.blobs.size());
        var put = fake.log.stream().filter(l -> l.method().equals("PUT")).findFirst().orElseThrow();
        assertEquals("*", put.headers().get("if-none-match"));
        assertEquals("BlockBlob", put.headers().get("x-ms-blob-type"));
        assertNotNull(put.headers().get("content-md5"));
        assertEquals(KsefInvoiceSnapshotRegistry.sha256Hex(xml), put.headers().get("x-ms-meta-ainvsha256"));
        assertEquals("/" + FakeAzureBlobServer.ACCOUNT + "/" + FakeAzureBlobServer.CONTAINER
                + "/ainvoicer-test/v1/test/outbound/" + NIP_A + "/" + KsefInvoiceSnapshotRegistry.sha256Hex(xml) + ".xml",
                put.path());
        assertTrue(fake.log.stream().allMatch(l -> l.method().equals("PUT") || l.method().equals("GET")),
                "adapter nie wykonuje DELETE ani innych operacji");
    }

    @Test void callerMutationAfterPutDoesNotChangeStoredDocument() {
        KsefDocumentStore documents = store().forScope(OUT_A);
        byte[] input = xml.clone();
        String reference = documents.put(input);
        input[0] = 'X';
        byte[] read = documents.get(reference);
        read[1] = 'Y';
        assertArrayEquals(xml, documents.get(reference));
    }

    @Test void identicalHashInAnotherTaxpayerOrChannelIsSeparateObjectWithoutCrossAccess() {
        var root = store();
        String a = root.forScope(OUT_A).put(xml);
        String b = root.forScope(OUT_B).put(xml);
        String inbound = root.forScope(IN_TEST).put(xml);
        assertEquals(3, Set.of(a, b, inbound).size());
        assertEquals(3, fake.blobs.size());
        long requests = fake.log.size();
        failure("KSEF_DOCUMENT_ACCESS_DENIED", () -> root.forScope(OUT_B).get(a));
        failure("KSEF_DOCUMENT_ACCESS_DENIED", () -> root.forScope(IN_TEST).get(a));
        failure("KSEF_DOCUMENT_ACCESS_DENIED", () -> root.forScope(OUT_A).get(inbound));
        assertEquals(requests, fake.log.size(), "obca referencja odrzucona przed wywołaniem storage");
        assertTrue(inbound.startsWith("ksefdoc:v1:TEST:INBOUND:-:"));
    }

    @Test void environmentsAndDeploymentPrefixesAreIsolated() {
        failure("KSEF_DOCUMENT_SCOPE_FORBIDDEN", () -> store().forScope(DocumentScope.outbound(KsefEnvironment.PROD, NIP_A)));
        var production = new ObjectStorageSettings("ainvoicer-prd", EnumSet.of(KsefEnvironment.TEST),
                settings.maxDocumentBytes(), settings.connectTimeout(), settings.requestTimeout(), 3,
                settings.allowedHostSuffixes(), true);
        String reference = store(production, null).forScope(OUT_A).put(xml);
        failure("KSEF_DOCUMENT_NOT_FOUND", () -> store().forScope(OUT_A).get(reference));
        var demoRef = reference.replace(":TEST:", ":DEMO:");
        failure("KSEF_DOCUMENT_ACCESS_DENIED", () -> store().forScope(OUT_A).get(demoRef));
    }

    @Test void malformedAndTraversalReferencesAreRejectedWithoutNetwork() {
        KsefDocumentStore documents = store().forScope(OUT_A);
        String sha = KsefInvoiceSnapshotRegistry.sha256Hex(xml);
        for (String bad : new String[] { null, "", sha + ".xml", "../" + sha, "ksefdoc:v1:TEST:OUTBOUND:" + NIP_A + ":../../x",
                "ksefdoc:v1:TEST:OUTBOUND:" + NIP_A + ":" + sha.toUpperCase(), "ksefdoc:v1:TEST:INBOUND:" + NIP_A + ":" + sha,
                "ksefdoc:v1:TEST:OUTBOUND:-:" + sha, "ksefdoc:v1:test:OUTBOUND:" + NIP_A + ":" + sha,
                "ksefdoc:v1:TEST:OUTBOUND:" + NIP_A + ":" + sha + "/../" + sha, "ksefdoc:v1:TEST:OUTBOUND:" + NIP_A + ":" + sha + "\n",
                "ksefdoc:v2:TEST:OUTBOUND:" + NIP_A + ":" + sha, "x".repeat(10_000) }) {
            failure("KSEF_DOCUMENT_REFERENCE_INVALID", () -> documents.get(bad));
        }
        assertEquals(0, fake.log.size());
    }

    @Test void unknownHashIsNotFoundAndObjectReplacedByThirdPartyFailsIntegrityWithoutOverwrite() {
        var documents = store().forScope(OUT_A);
        String reference = documents.put(xml);
        String key = fake.keyOf(reference, "ainvoicer-test");
        failure("KSEF_DOCUMENT_NOT_FOUND", () -> documents.get(reference.substring(0, reference.length() - 1)
                + (reference.endsWith("0") ? "1" : "0")));
        byte[] forged = "<Faktura>forged</Faktura>".getBytes(StandardCharsets.UTF_8);
        fake.replace(key, forged, Map.of("ainvsha256", KsefInvoiceSnapshotRegistry.sha256Hex(xml), "ainvlength", "25"));
        failure("KSEF_DOCUMENT_INTEGRITY_FAILED", () -> documents.get(reference));
        failure("KSEF_DOCUMENT_INTEGRITY_FAILED", () -> documents.put(xml));
        assertArrayEquals(forged, fake.blobs.get(key).content(), "adapter nigdy nie nadpisuje istniejącego obiektu");
        assertEquals(1, fake.created.get());
    }

    @Test void corruptedBytesAndTamperedMetadataAreDetected() {
        var documents = store().forScope(OUT_A);
        String reference = documents.put(xml);
        String key = fake.keyOf(reference, "ainvoicer-test");
        fake.corrupt(key);
        failure("KSEF_DOCUMENT_INTEGRITY_FAILED", () -> documents.get(reference));
        fake.replace(key, xml, Map.of("ainvsha256", "0".repeat(64)));
        failure("KSEF_DOCUMENT_INTEGRITY_FAILED", () -> documents.get(reference));
        fake.replace(key, xml, Map.of("ainvlength", "1"));
        failure("KSEF_DOCUMENT_INTEGRITY_FAILED", () -> documents.get(reference));
        fake.replace(key, xml, Map.of());
        assertArrayEquals(xml, documents.get(reference), "treść zgodna z SHA-256 jest autorytatywna");
    }

    @Test void timeoutAfterDurableCommitIsRetriedSafelyAsIdempotentCreate() {
        var fast = settings.withLoopbackHttpForTests(Duration.ofMillis(300));
        fake.stallMillis = 1200;
        fake.commitThenStallNextPuts.set(1);
        String reference = store(fast, null).forScope(OUT_A).put(xml);
        assertEquals(1, fake.created.get());
        assertEquals(1, fake.conflicts.get());
        assertEquals(1, sleeps.size());
        assertArrayEquals(xml, store().forScope(OUT_A).get(reference));
    }

    @Test void exhaustedTimeoutIsRetryableAndLaterPutConvergesToSameReference() {
        var single = settings.withLoopbackHttpForTests(Duration.ofMillis(300)).withMaxAttempts(1);
        fake.stallMillis = 1200;
        fake.commitThenStallNextPuts.set(1);
        var error = failure("KSEF_DOCUMENT_STORE_UNAVAILABLE", () -> store(single, null).forScope(OUT_A).put(xml));
        assertTrue(error.retryable());
        assertFalse(error.ambiguous(), "content-addressed create-only: retry nie powoduje drugiego efektu");
        // Nowa instancja = restart procesu; ten sam obiekt, ta sama referencja.
        String reference = store().forScope(OUT_A).put(xml);
        assertEquals(1, fake.created.get());
        assertArrayEquals(xml, store().forScope(OUT_A).get(reference));
    }

    @Test void transientUnavailabilityIsRetriedWithBoundedBackoff() {
        fake.failNextRequests.set(2);
        String reference = store().forScope(OUT_A).put(xml);
        assertEquals(2, sleeps.size());
        assertTrue(sleeps.get(1).compareTo(sleeps.get(0)) > 0);
        fake.failNextRequests.set(10);
        fake.failureStatus = 500;
        var error = failure("KSEF_DOCUMENT_STORE_UNAVAILABLE", () -> store().forScope(OUT_A).get(reference));
        assertTrue(error.retryable());
        assertNull(error.retryNotBefore());
    }

    @Test void timeoutIncludesBodyAfterHeadersHaveAlreadyArrived() {
        String reference = store().forScope(OUT_A).put(xml);
        fake.stallGetBody = true;
        fake.stallMillis = 1500;
        var fast = settings.withLoopbackHttpForTests(Duration.ofMillis(250)).withMaxAttempts(1);
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            var error = failure("KSEF_DOCUMENT_STORE_UNAVAILABLE", () -> store(fast, null).forScope(OUT_A).get(reference));
            assertTrue(error.retryable());
        });
    }

    @Test void longRetryAfterIsReturnedToCallerInsteadOfBlocking() {
        fake.failNextRequests.set(1);
        fake.failureStatus = 503;
        fake.retryAfter = "30";
        sleeps.clear();
        var error = failure("KSEF_DOCUMENT_STORE_UNAVAILABLE", () -> store().forScope(OUT_A).put(xml));
        assertEquals(NOW.plusSeconds(30), error.retryNotBefore());
        assertTrue(sleeps.isEmpty());
    }

    @Test void unreachableStorageIsUnavailable() throws Exception {
        int port;
        try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        var binding = new AzureObjectStoreBinding(java.net.URI.create("http://127.0.0.1:" + port + "/acc/"
                + FakeAzureBlobServer.CONTAINER), FakeAzureBlobServer.CONTAINER, FakeAzureBlobServer.SAS,
                AzureObjectStoreBinding.Access.READ_WRITE);
        var documents = new ContentAddressedDocumentStore(new AzureBlobSasClient(binding, settings), settings,
                binding.access(), CLOCK, sleeps::add).forScope(OUT_A);
        assertTrue(failure("KSEF_DOCUMENT_STORE_UNAVAILABLE", () -> documents.put(xml)).retryable());
        assertEquals(2, sleeps.size());
    }

    @Test void revokedOrInsufficientPermissionsFailClosedWithoutRetry() {
        String reference = store().forScope(OUT_A).put(xml);
        fake.revoked = true;
        assertFalse(failure("KSEF_DOCUMENT_ACCESS_DENIED", () -> store().forScope(OUT_A).get(reference)).retryable());
        assertTrue(sleeps.isEmpty());
        fake.revoked = false;
        fake.readOnly = true;
        failure("KSEF_DOCUMENT_ACCESS_DENIED", () -> store().forScope(OUT_A).put("<other/>".getBytes(StandardCharsets.UTF_8)));
        long before = fake.log.size();
        var readOnly = store(settings, "read").forScope(OUT_A);
        failure("KSEF_DOCUMENT_STORE_READ_ONLY", () -> readOnly.put(xml));
        assertEquals(before, fake.log.size());
        assertArrayEquals(xml, readOnly.get(reference));
    }

    @Test void sizeLimitsApplyToWritesAndReads() {
        var documents = store().forScope(OUT_A);
        failure("KSEF_DOCUMENT_SIZE_INVALID", () -> documents.put(new byte[0]));
        failure("KSEF_DOCUMENT_SIZE_INVALID", () -> documents.put(null));
        String reference = documents.put(xml);
        var small = store(settings.withMaxDocumentBytes(xml.length - 1), null).forScope(OUT_A);
        failure("KSEF_DOCUMENT_SIZE_INVALID", () -> small.put(xml));
        failure("KSEF_DOCUMENT_SIZE_INVALID", () -> small.get(reference));
    }

    @Test void concurrentPutsOfSameBytesCreateOneObjectAndOneReference() throws Exception {
        var root = store();
        int threads = 12;
        var barrier = new CyclicBarrier(threads);
        var pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> { barrier.await(10, TimeUnit.SECONDS); return root.forScope(OUT_A).put(xml); });
            }
            var references = new java.util.HashSet<String>();
            for (var future : pool.invokeAll(tasks)) { references.add(future.get()); }
            assertEquals(1, references.size());
            assertEquals(1, fake.created.get());
            assertEquals(threads - 1, fake.conflicts.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void redirectsAreNeverFollowedSoSasCannotLeak() throws Exception {
        var target = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var hits = new AtomicInteger();
        target.createContext("/", exchange -> { hits.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        target.start();
        var redirect = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        redirect.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + target.getAddress().getPort() + "/steal");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        redirect.start();
        try {
            var binding = new AzureObjectStoreBinding(java.net.URI.create("http://127.0.0.1:" + redirect.getAddress().getPort()
                    + "/acc/" + FakeAzureBlobServer.CONTAINER), FakeAzureBlobServer.CONTAINER, FakeAzureBlobServer.SAS,
                    AzureObjectStoreBinding.Access.READ_WRITE);
            var documents = new ContentAddressedDocumentStore(new AzureBlobSasClient(binding, settings), settings,
                    binding.access(), CLOCK, sleeps::add).forScope(OUT_A);
            failure("KSEF_DOCUMENT_STORE_REJECTED", () -> documents.put(xml));
            assertEquals(0, hits.get());
        } finally {
            redirect.stop(0);
            target.stop(0);
        }
    }

    @Test void endpointMustBeHttpsToAzureBlobHostOrExplicitTestLoopback() {
        var production = ObjectStorageSettings.defaults("ainvoicer-test", EnumSet.of(KsefEnvironment.TEST));
        String c = FakeAzureBlobServer.CONTAINER;
        String sas = FakeAzureBlobServer.SAS;
        for (String uri : new String[] { fake.containerUri(), "http://acc.blob.core.windows.net/" + c,
                "https://attacker.example/" + c, "https://acc.blob.core.windows.net.attacker.example/" + c,
                "https://acc.blob.core.windows.net:8443/" + c, "https://user@acc.blob.core.windows.net/" + c,
                "https://acc.blob.core.windows.net/" + c + "?x=1", "https://acc.blob.core.windows.net/other" }) {
            var binding = new AzureObjectStoreBinding(java.net.URI.create(uri), c, sas, AzureObjectStoreBinding.Access.READ_WRITE);
            var error = assertThrows(IllegalStateException.class, () -> new AzureBlobSasClient(binding, production), uri);
            assertEquals("KSEF_DOCUMENT_STORE_ENDPOINT_INVALID", error.getMessage());
        }
        for (String uri : new String[] { "https://acc.blob.core.windows.net/" + c, "https://acc.z17.blob.storage.azure.net/" + c }) {
            assertDoesNotThrow(() -> new AzureBlobSasClient(new AzureObjectStoreBinding(java.net.URI.create(uri), c, sas,
                    AzureObjectStoreBinding.Access.READ_WRITE), production));
        }
        var loopbackOnly = settings; // allowLoopbackHttp dotyczy wyłącznie 127.0.0.1/localhost
        var remoteHttp = new AzureObjectStoreBinding(java.net.URI.create("http://10.0.0.1/acc/" + c), c, sas,
                AzureObjectStoreBinding.Access.READ_WRITE);
        assertThrows(IllegalStateException.class, () -> new AzureBlobSasClient(remoteHttp, loopbackOnly));
    }

    @Test void orphanReportListsUnreferencedObjectsOutsideGracePeriodAndNeverDeletes() {
        var root = store();
        var documents = root.forScope(OUT_A);
        fake.now = NOW.minus(Duration.ofHours(2));
        String referenced = documents.put(xml);
        String orphan = documents.put("<orphan/>".getBytes(StandardCharsets.UTF_8));
        fake.now = NOW.minus(Duration.ofMinutes(1));
        documents.put("<in-flight/>".getBytes(StandardCharsets.UTF_8));
        root.forScope(OUT_B).put("<other-partition/>".getBytes(StandardCharsets.UTF_8));
        fake.replace("ainvoicer-test/v1/test/outbound/" + NIP_A + "/stray.bin", new byte[] { 1 }, Map.of());
        var report = root.orphanReport(OUT_A, Set.of(referenced), Duration.ofMinutes(30));
        assertEquals(4, report.scanned());
        assertEquals(List.of(orphan), report.orphanReferences());
        assertEquals(1, report.withinGracePeriod());
        assertEquals(1, report.unrecognizedKeys());
        assertEquals(5, fake.blobs.size());
    }

    @Test void listingIsPagedAndParserRejectsDoctype() {
        var root = store();
        for (int i = 0; i < 5; i++) { root.forScope(OUT_A).put(("<d" + i + "/>").getBytes(StandardCharsets.UTF_8)); }
        var binding = AzureObjectStoreBinding.fromVcapServices(fake.vcapServices(null), null);
        var client = new AzureBlobSasClient(binding, settings);
        var prefix = DocumentReference.partitionPrefix("ainvoicer-test", OUT_A);
        var first = client.list(prefix, null, 2);
        assertEquals(2, first.objects().size());
        assertNotNull(first.nextMarker());
        int total = first.objects().size();
        for (var page = client.list(prefix, first.nextMarker(), 2); ; page = client.list(prefix, page.nextMarker(), 2)) {
            total += page.objects().size();
            if (page.nextMarker() == null) { break; }
        }
        assertEquals(5, total);
        byte[] evil = "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><EnumerationResults><Blobs><Blob><Name>&e;</Name></Blob></Blobs></EnumerationResults>"
                .getBytes(StandardCharsets.UTF_8);
        var error = assertThrows(ObjectStorageException.class, () -> AzureBlobSasClient.parseList(evil));
        assertEquals(ObjectStorageException.Category.REJECTED, error.category());
    }

    @Test void diagnosticStringsNeverContainSas() {
        var binding = AzureObjectStoreBinding.fromVcapServices(fake.vcapServices(null), null);
        var client = new AzureBlobSasClient(binding, settings);
        var documents = store().forScope(OUT_A);
        for (Object value : List.of(binding, client, documents)) {
            assertFalse(value.toString().contains(FakeAzureBlobServer.SIGNATURE), value.getClass().getSimpleName());
            assertFalse(value.toString().contains("127.0.0.1"), value.getClass().getSimpleName());
        }
    }
}
