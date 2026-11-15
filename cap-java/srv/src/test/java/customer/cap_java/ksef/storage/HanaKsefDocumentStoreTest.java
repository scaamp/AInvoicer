package customer.cap_java.ksef.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cds.ql.Delete;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.ql.cqn.CqnInsert;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.RequestContext;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.Application;
import customer.cap_java.ksef.KsefDocumentStore;
import customer.cap_java.ksef.KsefFailure;
import customer.cap_java.ksef.inbound.DurableInboundDocumentStore;
import customer.cap_java.ksef.inbound.KsefEnvironment;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;

/**
 * KSEF-STOR-04 na prawdziwym CAP CQN/H2: prywatna tabela {@code KsefStoredDocuments} ze złożonym PK.
 * Kontrakt bajtów i SHA-256, idempotencja, współbieżność, scope, uszkodzenia, awarie DB, budżet pamięci
 * oraz strukturalna izolacja od publicznego OData i laboratorium BLOB-LAB-01.
 * H2 nie dowodzi zachowania HANA Cloud LIVE ani restartu procesu CF.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:ksef-stor-04;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "logging.level.root=WARN" })
@ActiveProfiles("test")
class HanaKsefDocumentStoreTest {
    static final String ENTITY = HanaBlobObjectStorageClient.ENTITY;
    static final String LAB = "customer.cap_java.KsefDocuments";
    static final String NIP_A = "1111111111";
    static final String NIP_B = "2222222222";
    /** BOM, CRLF, znaki spoza ASCII: dokładne bajty, bez ponownej serializacji XML. */
    static final byte[] XML = ("﻿<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n<Faktura>"
            + "<P_2>STOR-04/ąęł/€</P_2>\r\n</Faktura>\r\n").getBytes(StandardCharsets.UTF_8);
    static final String SHA = KsefInvoiceSnapshotRegistry.sha256Hex(XML);
    static final String INBOUND_REF = "ksefdoc:v1:TEST:INBOUND:-:" + SHA;

    @LocalServerPort int port;
    @Autowired PersistenceService db;
    @Autowired CdsRuntime runtime;
    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach void clean() { tx(() -> { db.run(Delete.from(ENTITY)); return db.run(Delete.from(LAB)); }); }

    ContentAddressedDocumentStore root(PersistenceService persistence, int limit) {
        return HanaKsefDocumentStore.create(persistence, runtime, EnumSet.of(KsefEnvironment.TEST, KsefEnvironment.DEMO),
                limit, 4L * limit, Duration.ofSeconds(5), Clock.systemUTC(), d -> { });
    }

    /** Nowa instancja adaptera = brak współdzielonego stanu w pamięci (H2: nie jest restartem procesu CF). */
    DurableInboundDocumentStore inbound() { return inbound(KsefEnvironment.TEST); }

    DurableInboundDocumentStore inbound(KsefEnvironment environment) {
        return root(db, HanaKsefDocumentStore.DEFAULT_MAX_DOCUMENT_BYTES).forScope(DocumentScope.inbound(environment));
    }

    KsefDocumentStore outbound(String nip) {
        return root(db, HanaKsefDocumentStore.DEFAULT_MAX_DOCUMENT_BYTES).forScope(DocumentScope.outbound(KsefEnvironment.TEST, nip));
    }

    <T> T tx(Supplier<T> work) {
        return runtime.requestContext().systemUser().run((RequestContext ctx) ->
                runtime.changeSetContext().run(cs -> { return work.get(); }));
    }

    List<Map<String, Object>> storeRows() {
        return tx(() -> db.run(Select.from(ENTITY).columns("environment", "channel", "ownerSegment", "sha256",
                "mimeType", "sizeBytes", "storedAt")).list().stream().<Map<String, Object>>map(HashMap<String, Object>::new).toList());
    }

    static Map<String, Object> pk(String environment, String channel, String owner, String sha) {
        return Map.of("environment", environment, "channel", channel, "ownerSegment", owner, "sha256", sha);
    }

    void tamper(Map<String, Object> data) {
        tx(() -> db.run(Update.entity(ENTITY).data(data).matching(pk("TEST", "INBOUND", "-", SHA))));
    }

    static String code(Runnable action) { return assertThrows(KsefFailure.class, action::run).getMessage(); }

    // ---------------------------------------------------------------- kontrakt bajtów, SHA-256, idempotencja

    @Test void putAndGetReturnIdenticalBytesUnderBackendComputedCompositeKey() {
        String reference = inbound().put(XML);
        assertEquals(INBOUND_REF, reference, "format referencji ksefdoc:v1 bez zmian względem STOR-01/03");
        byte[] restored = inbound().get(reference);
        assertArrayEquals(XML, restored);
        assertEquals(SHA, KsefInvoiceSnapshotRegistry.sha256Hex(restored));
        var row = storeRows().getFirst();
        assertEquals(Map.of("environment", "TEST", "channel", "INBOUND", "ownerSegment", "-", "sha256", SHA),
                Map.of("environment", row.get("environment"), "channel", row.get("channel"),
                        "ownerSegment", row.get("ownerSegment"), "sha256", row.get("sha256")));
        assertEquals("application/xml", row.get("mimeType"));
        assertEquals((long) XML.length, ((Number) row.get("sizeBytes")).longValue(), "rozmiar liczony przez backend");
        assertNotNull(row.get("storedAt"));
    }

    @Test void repeatedPutIsIdempotentAndNeverRewritesTheRow() {
        String first = inbound().put(XML);
        Object storedAt = storeRows().getFirst().get("storedAt");
        for (int i = 0; i < 3; i++) { assertEquals(first, inbound().put(XML.clone())); }
        assertEquals(1, storeRows().size());
        assertEquals(storedAt, storeRows().getFirst().get("storedAt"), "brak UPDATE przy ponowieniu");
    }

    @Test void eightConcurrentPutsOfSameDocumentProduceOneRowAndOneReference() throws Exception {
        int threads = 8;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);
        try {
            var results = new ArrayList<Future<String>>();
            for (int i = 0; i < threads; i++) {
                var store = inbound(); // osobne instancje = osobne „procesy” bez wspólnego stanu w pamięci
                results.add(pool.submit(() -> { start.await(); return store.put(XML); }));
            }
            start.countDown();
            for (var result : results) { assertEquals(INBOUND_REF, result.get(30, TimeUnit.SECONDS)); }
        } finally { pool.shutdownNow(); }
        assertEquals(1, storeRows().size(), "złożony PK działa jak fizyczne UNIQUE");
        assertArrayEquals(XML, inbound().get(INBOUND_REF));
    }

    @Test void physicalPrimaryKeyRejectsSecondRowForTheSameAddress() {
        inbound().put(XML);
        var duplicate = assertThrows(ServiceException.class, () -> tx(() -> db.run(Insert.into(ENTITY).entry(Map.of(
                "environment", "TEST", "channel", "INBOUND", "ownerSegment", "-", "sha256", SHA,
                "mimeType", "application/xml", "sizeBytes", 5L, "storedAt", java.time.Instant.now(),
                "content", new ByteArrayInputStream("<x/>".getBytes(StandardCharsets.UTF_8)))))));
        assertEquals("409006", duplicate.getErrorStatus().getCodeString(), "naruszenie PK — bez losowego ID jako obejścia");
        assertEquals(1, storeRows().size());
        assertArrayEquals(XML, inbound().get(INBOUND_REF), "oryginał nienaruszony");
    }

    @Test void sameShaInDifferentScopesIsStoredSeparatelyAndNeverShared() {
        String in = inbound().put(XML);
        String a = outbound(NIP_A).put(XML);
        String b = outbound(NIP_B).put(XML);
        String demo = inbound(KsefEnvironment.DEMO).put(XML);
        assertEquals(4, Set.of(in, a, b, demo).size(), "ten sam hash w innym scope = osobna referencja");
        assertEquals(4, storeRows().size());
        assertTrue(storeRows().stream().allMatch(row -> SHA.equals(row.get("sha256"))));
        assertEquals(Set.of("-", NIP_A, NIP_B), Set.copyOf(storeRows().stream().map(r -> r.get("ownerSegment")).toList()));
        assertArrayEquals(XML, outbound(NIP_A).get(a));
        assertArrayEquals(XML, inbound(KsefEnvironment.DEMO).get(demo));
    }

    @Test void freshAdapterInstanceReadsDocumentsWrittenByAnotherInstance() {
        String reference = HanaKsefDocumentStore.create(db, runtime, EnumSet.of(KsefEnvironment.TEST))
                .forScope(DocumentScope.inbound(KsefEnvironment.TEST)).put(XML);
        assertArrayEquals(XML, HanaKsefDocumentStore.create(db, runtime, EnumSet.of(KsefEnvironment.TEST))
                .forScope(DocumentScope.inbound(KsefEnvironment.TEST)).get(reference));
    }

    // ---------------------------------------------------------------- referencje, scope, zgadywanie hash

    @Test void malformedOrInjectedReferencesAreRejected() {
        inbound().put(XML);
        String objectKey = "hana-ksef/v1/test/inbound/shared/" + SHA + ".xml";
        for (String bad : new String[] { null, "", SHA, SHA + ".xml", objectKey, "TEST:INBOUND:-:" + SHA,
                "ksefdoc:v1:TEST:INBOUND:-:../" + SHA, "ksefdoc:v1:TEST:INBOUND:-:" + SHA.toUpperCase(),
                "ksefdoc:v1:TEST:INBOUND:-:" + SHA + "\n", "ksefdoc:v2:TEST:INBOUND:-:" + SHA,
                "ksefdoc:v1:test:INBOUND:-:" + SHA, "ksefdoc:v1:TEST:INBOUND:shared:" + SHA,
                "ksefdoc:v1:TEST:INBOUND:1111111111:" + SHA, "ksefdoc:v1:TEST:OUTBOUND:-:" + SHA,
                "ksefdoc:v1:TEST:INBOUND:-:" + SHA + "' OR '1'='1" }) {
            assertEquals("KSEF_DOCUMENT_REFERENCE_INVALID", code(() -> inbound().get(bad)), String.valueOf(bad));
        }
    }

    @Test void foreignScopeIsDeniedEvenWithIdenticalHash() {
        String a = outbound(NIP_A).put(XML);
        String in = inbound().put(XML);
        assertEquals("KSEF_DOCUMENT_ACCESS_DENIED", code(() -> outbound(NIP_B).get(a)), "NIP OUTBOUND jest częścią zakresu");
        assertEquals("KSEF_DOCUMENT_ACCESS_DENIED", code(() -> inbound().get(a)));
        assertEquals("KSEF_DOCUMENT_ACCESS_DENIED", code(() -> outbound(NIP_A).get(in)));
        assertEquals("KSEF_DOCUMENT_ACCESS_DENIED", code(() -> inbound().get(in.replace(":TEST:", ":DEMO:"))));
        assertEquals("KSEF_DOCUMENT_ACCESS_DENIED", code(() -> inbound().get(in.replace(":TEST:", ":PROD:"))));
        assertEquals("KSEF_DOCUMENT_SCOPE_FORBIDDEN", code(() -> root(db, 1024).forScope(DocumentScope.inbound(KsefEnvironment.PROD))),
                "wdrożenie bez PROD nie otworzy widoku PROD");
        assertArrayEquals(XML, outbound(NIP_A).get(a));
    }

    @Test void guessingHashesNeverRevealForeignDocuments() {
        String a = outbound(NIP_A).put(XML);
        // Znajomość dokładnego SHA-256 cudzego dokumentu nie daje dostępu: scope widoku jest z zaufanej konfiguracji.
        assertEquals("KSEF_DOCUMENT_ACCESS_DENIED", code(() -> outbound(NIP_B).get(a)));
        assertEquals("KSEF_DOCUMENT_NOT_FOUND", code(() -> outbound(NIP_B).get("ksefdoc:v1:TEST:OUTBOUND:" + NIP_B + ":" + SHA)),
                "ten sam hash we własnym scope nie wskazuje na cudzy wiersz");
        for (int i = 0; i < 16; i++) {
            String guess = KsefInvoiceSnapshotRegistry.sha256Hex(("guess-" + i).getBytes(StandardCharsets.UTF_8));
            assertEquals("KSEF_DOCUMENT_NOT_FOUND", code(() -> inbound().get("ksefdoc:v1:TEST:INBOUND:-:" + guess)));
        }
        assertEquals(1, storeRows().size());
    }

    // ---------------------------------------------------------------- integralność i niezmienność

    @Test void corruptedContentSizeOrMediaTypeFailIntegrityAndPutNeverOverwrites() {
        inbound().put(XML);
        byte[] forged = "<Faktura>forged-same-length</Faktura>".getBytes(StandardCharsets.UTF_8);
        tamper(Map.of("content", new ByteArrayInputStream(forged), "sizeBytes", (long) forged.length));
        assertEquals("KSEF_DOCUMENT_INTEGRITY_FAILED", code(() -> inbound().get(INBOUND_REF)));
        assertEquals("KSEF_DOCUMENT_INTEGRITY_FAILED", code(() -> inbound().put(XML)), "put nie nadpisuje uszkodzonego wiersza");

        clean();
        inbound().put(XML);
        tamper(Map.of("content", new ByteArrayInputStream("<x/>".getBytes(StandardCharsets.UTF_8)))); // sizeBytes bez zmian
        assertEquals("KSEF_DOCUMENT_INTEGRITY_FAILED", code(() -> inbound().get(INBOUND_REF)));

        clean();
        inbound().put(XML);
        tamper(Map.of("sizeBytes", (long) XML.length + 1));
        assertEquals("KSEF_DOCUMENT_INTEGRITY_FAILED", code(() -> inbound().get(INBOUND_REF)));
        assertEquals("KSEF_DOCUMENT_INTEGRITY_FAILED", code(() -> inbound().put(XML)));

        clean();
        inbound().put(XML);
        tamper(Map.of("mimeType", "application/pdf"));
        assertEquals("KSEF_DOCUMENT_INTEGRITY_FAILED", code(() -> inbound().get(INBOUND_REF)));

        clean();
        inbound().put(XML);
        tamper(Map.of("sizeBytes", 0L));
        assertEquals("KSEF_DOCUMENT_INTEGRITY_FAILED", code(() -> inbound().get(INBOUND_REF)));
    }

    @Test void forgedRowUnderForeignHashKeyIsDetected() {
        inbound().put(XML);
        String other = "0".repeat(64);
        // Wiersz wstawiony z pominięciem adaptera (np. administrator DB): PK z hashem niezgodnym z bajtami.
        tx(() -> db.run(Insert.into(ENTITY).entry(Map.of("environment", "TEST", "channel", "INBOUND", "ownerSegment", "-",
                "sha256", other, "mimeType", "application/xml", "sizeBytes", (long) XML.length,
                "storedAt", java.time.Instant.now(), "content", new ByteArrayInputStream(XML)))));
        assertEquals("KSEF_DOCUMENT_INTEGRITY_FAILED", code(() -> inbound().get("ksefdoc:v1:TEST:INBOUND:-:" + other)));
        assertArrayEquals(XML, inbound().get(INBOUND_REF), "oryginał pod własnym adresem nienaruszony");
    }

    @Test void differentContentCannotReplaceStoredXml() {
        String reference = inbound().put(XML);
        byte[] other = "<Faktura>other</Faktura>".getBytes(StandardCharsets.UTF_8);
        String second = inbound().put(other);
        assertNotEquals(reference, second, "adres = treść; inna treść nigdy nie trafia pod istniejącą referencję");
        assertArrayEquals(XML, inbound().get(reference));
        assertArrayEquals(other, inbound().get(second));
    }

    @Test void clientRejectsKeysThatDoNotMatchContentOrScopeRules() {
        var client = new HanaBlobObjectStorageClient(db, runtime, 4096, 4096, Duration.ZERO, Clock.systemUTC());
        String otherSha = "a".repeat(64);
        var mismatch = assertThrows(ObjectStorageException.class, () -> client.createIfAbsent(
                "hana-ksef/v1/test/inbound/shared/" + otherSha + ".xml", XML, Map.of()));
        assertEquals("HANA_DOCUMENT_KEY_CONTENT_MISMATCH", mismatch.getMessage());
        for (String key : new String[] { "hana-ksef/v1/test/inbound/" + NIP_A + "/" + SHA + ".xml",
                "hana-ksef/v1/test/outbound/shared/" + SHA + ".xml", "ainvoicer-dev/v1/test/inbound/shared/" + SHA + ".xml",
                "hana-ksef/v1/TEST/inbound/shared/" + SHA + ".xml", "hana-ksef/v1/test/inbound/shared/" + SHA + ".pdf",
                "hana-ksef/v1/test/inbound/shared/../" + SHA + ".xml" }) {
            var error = assertThrows(ObjectStorageException.class, () -> client.createIfAbsent(key, XML, Map.of()), key);
            assertEquals(ObjectStorageException.Category.REJECTED, error.category());
            assertThrows(ObjectStorageException.class, () -> client.read(key, 4096), key);
        }
        assertThrows(ObjectStorageException.class, () -> client.list("hana-ksef/v1/", null, 10), "tylko pełna partycja");
        assertTrue(storeRows().isEmpty());
    }

    // ---------------------------------------------------------------- limity rozmiaru i pamięć

    @Test void explicitSizePolicyIndependentOfLabLimit() {
        assertEquals(3 * 1024 * 1024, HanaKsefDocumentStore.DEFAULT_MAX_DOCUMENT_BYTES);
        assertEquals(16 * 1024 * 1024, HanaKsefDocumentStore.HARD_MAX_DOCUMENT_BYTES);
        byte[] twoMiB = new byte[2 * 1024 * 1024]; // > laboratoryjny 1 MiB, < limit adaptera
        java.util.Arrays.fill(twoMiB, (byte) 'x');
        assertArrayEquals(twoMiB, inbound().get(inbound().put(twoMiB)));
        byte[] atLimit = new byte[HanaKsefDocumentStore.DEFAULT_MAX_DOCUMENT_BYTES];
        atLimit[0] = 1;
        assertArrayEquals(atLimit, inbound().get(inbound().put(atLimit)));
        assertEquals("KSEF_DOCUMENT_SIZE_INVALID", code(() -> inbound().put(new byte[HanaKsefDocumentStore.DEFAULT_MAX_DOCUMENT_BYTES + 1])));
        assertEquals("KSEF_DOCUMENT_SIZE_INVALID", code(() -> inbound().put(new byte[0])));
        assertEquals("KSEF_DOCUMENT_SIZE_INVALID", code(() -> inbound().put(null)));
        assertEquals(2, storeRows().size());
    }

    @Test void hardMaximumIsExplicitAndTested() {
        int hard = HanaKsefDocumentStore.HARD_MAX_DOCUMENT_BYTES;
        var store = HanaKsefDocumentStore.create(db, runtime, EnumSet.of(KsefEnvironment.TEST), hard)
                .forScope(DocumentScope.inbound(KsefEnvironment.TEST));
        byte[] atHard = new byte[hard];
        atHard[hard - 1] = 7;
        assertArrayEquals(atHard, store.get(store.put(atHard)), "16 MiB mieści się w tabeli i budżecie pamięci");
        assertEquals("KSEF_DOCUMENT_SIZE_INVALID", code(() -> store.put(new byte[hard + 1])));
        assertThrows(IllegalArgumentException.class, () -> HanaKsefDocumentStore.create(db, runtime, EnumSet.of(KsefEnvironment.TEST), hard + 1));
        assertThrows(IllegalArgumentException.class, () -> HanaKsefDocumentStore.create(db, runtime, EnumSet.of(KsefEnvironment.TEST), 0));
        assertThrows(IllegalArgumentException.class, () -> HanaKsefDocumentStore.create(db, runtime, EnumSet.of(KsefEnvironment.TEST),
                1024, 1023, Duration.ZERO, Clock.systemUTC(), d -> { }), "budżet pamięci < limit dokumentu");
        assertThrows(IllegalArgumentException.class, () -> HanaKsefDocumentStore.create(db, runtime, EnumSet.of(KsefEnvironment.TEST),
                1024, HanaKsefDocumentStore.HARD_MAX_IN_FLIGHT_BYTES + 1, Duration.ZERO, Clock.systemUTC(), d -> { }));
    }

    @Test void oversizedStoredRowIsRejectedOnReadBeforeLoadingBlob() {
        byte[] big = new byte[2048];
        java.util.Arrays.fill(big, (byte) 'b');
        String reference = root(db, 4096).forScope(DocumentScope.inbound(KsefEnvironment.TEST)).put(big);
        assertEquals("KSEF_DOCUMENT_SIZE_INVALID", code(() -> root(db, 1024).forScope(DocumentScope.inbound(KsefEnvironment.TEST)).get(reference)));
    }

    @Test void memoryBudgetBoundsConcurrentDocumentsAndFailsRetryably() throws Exception {
        var insideInsert = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blockFirst = new AtomicBoolean(true);
        PersistenceService slow = mock(PersistenceService.class, invocation -> {
            if ("run".equals(invocation.getMethod().getName()) && invocation.getArgument(0) instanceof CqnInsert
                    && blockFirst.compareAndSet(true, false)) {
                insideInsert.countDown();
                assertTrue(release.await(30, TimeUnit.SECONDS));
            }
            try {
                return invocation.getMethod().invoke(db, invocation.getRawArguments());
            } catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
        });
        // Budżet = dokładnie jeden dokument 4 KiB; drugi równoległy zapis nie dostanie pamięci w 50 ms.
        var store = HanaKsefDocumentStore.create(slow, runtime, EnumSet.of(KsefEnvironment.TEST), 4096, 4096,
                Duration.ofMillis(50), Clock.systemUTC(), d -> { }).forScope(DocumentScope.inbound(KsefEnvironment.TEST));
        byte[] first = new byte[4096];
        first[0] = 1;
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> blocked = pool.submit(() -> store.put(first));
            assertTrue(insideInsert.await(30, TimeUnit.SECONDS));
            var busy = assertThrows(KsefFailure.class, () -> store.put(XML));
            assertEquals("KSEF_DOCUMENT_STORE_UNAVAILABLE", busy.getMessage());
            assertTrue(busy.retryable(), "brak pamięci = chwilowy błąd, bez zapisu");
            release.countDown();
            assertArrayEquals(first, store.get(blocked.get(30, TimeUnit.SECONDS)));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertArrayEquals(XML, store.get(store.put(XML)), "po zwolnieniu budżetu zapis działa");
        assertEquals(2, storeRows().size());
    }

    // ---------------------------------------------------------------- awarie DB, rollback, retry

    @Test void databaseOutageFailsRetryablyWithoutLeakingDetails() {
        var calls = new AtomicInteger();
        PersistenceService down = mock(PersistenceService.class, invocation -> {
            calls.incrementAndGet();
            throw new ServiceException(ErrorStatuses.SERVER_ERROR, "SQL SECRET-HOST table CUSTOMER_CAP_JAVA_KSEFSTOREDDOCUMENTS");
        });
        var failure = assertThrows(KsefFailure.class, () -> root(down, 4096).forScope(DocumentScope.inbound(KsefEnvironment.TEST)).put(XML));
        assertEquals("KSEF_DOCUMENT_STORE_UNAVAILABLE", failure.getMessage());
        assertTrue(failure.retryable());
        assertFalse(failure.toString().contains("SECRET-HOST"));
        assertFalse(failure.toString().contains("KSEFSTOREDDOCUMENTS"));
        assertEquals(3, calls.get(), "ograniczone ponowienia (3 próby INSERT)");
        var readFailure = assertThrows(KsefFailure.class, () -> root(down, 4096).forScope(DocumentScope.inbound(KsefEnvironment.TEST)).get(INBOUND_REF));
        assertTrue(readFailure.retryable());
        assertTrue(storeRows().isEmpty());
    }

    @Test void transientFailureIsRetriedAndLeavesSingleRow() {
        var failures = new AtomicInteger(1);
        PersistenceService flaky = mock(PersistenceService.class, invocation -> {
            if ("run".equals(invocation.getMethod().getName()) && failures.getAndDecrement() > 0) {
                throw new ServiceException(ErrorStatuses.SERVER_ERROR, "connection reset");
            }
            try {
                return invocation.getMethod().invoke(db, invocation.getRawArguments());
            } catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
        });
        String reference = root(flaky, 4096).forScope(DocumentScope.inbound(KsefEnvironment.TEST)).put(XML);
        assertArrayEquals(XML, inbound().get(reference));
        assertEquals(1, storeRows().size());
    }

    @Test void callerRollbackLeavesDurableOrphanAndRetryReusesIt() {
        assertThrows(IllegalStateException.class, () -> tx(() -> {
            inbound().put(XML);
            throw new IllegalStateException("caller rollback after put");
        }));
        assertEquals(1, storeRows().size(), "zapis magazynu ma własny ChangeSet — jak PUT w Object Store");
        assertEquals(INBOUND_REF, inbound().put(XML));
        assertEquals(1, storeRows().size());
        assertArrayEquals(XML, inbound().get(INBOUND_REF));
    }

    @Test void orphanAuditListsOnlyItsPartitionWithPagingAndDeletesNothing() throws Exception {
        String referenced = inbound().put(XML);
        var orphans = new ArrayList<String>();
        for (int i = 0; i < 3; i++) { orphans.add(inbound().put(("<Faktura>orphan-" + i + "</Faktura>").getBytes(StandardCharsets.UTF_8))); }
        outbound(NIP_A).put(XML);
        inbound(KsefEnvironment.DEMO).put(XML);
        labCreate("lab.xml");
        var report = root(db, 4096).orphanReport(DocumentScope.inbound(KsefEnvironment.TEST), Set.of(referenced), Duration.ZERO);
        assertEquals(4, report.scanned());
        assertEquals(Set.copyOf(orphans), Set.copyOf(report.orphanReferences()));
        assertEquals(0, report.unrecognizedKeys());
        var grace = root(db, 4096).orphanReport(DocumentScope.inbound(KsefEnvironment.TEST), Set.of(referenced), Duration.ofHours(1));
        assertEquals(4, grace.withinGracePeriod(), "storedAt chroni świeże zapisy przed raportem");
        var client = new HanaBlobObjectStorageClient(db, runtime, 4096, 4096, Duration.ZERO, Clock.systemUTC());
        var page = client.list("hana-ksef/v1/test/inbound/shared/", null, 2);
        assertEquals(2, page.objects().size());
        var rest = client.list("hana-ksef/v1/test/inbound/shared/", page.nextMarker(), 10);
        assertEquals(2, rest.objects().size());
        assertNull(rest.nextMarker());
        assertTrue(rest.objects().stream().allMatch(o -> o.size() > 0));
        assertEquals(6, storeRows().size(), "audyt niczego nie usuwa");
    }

    // ---------------------------------------------------------------- izolacja od OData i laboratorium BLOB-LAB-01

    HttpResponse<byte[]> odata(String user, String password, String method, String path, String mime, byte[] body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/odata/v4/" + path));
        if (user != null) {
            builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
        }
        if (mime != null) { builder.header("Content-Type", mime); }
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    HttpResponse<byte[]> operatorA(String method, String path, String mime, byte[] body) throws Exception {
        return odata("operator-a", "test-a", method, path, mime, body);
    }

    String labCreate(String name) throws Exception {
        var created = operatorA("POST", "blob/Documents", "application/json", json.writeValueAsBytes(Map.of("fileName", name, "mimeType", "application/xml")));
        assertEquals(201, created.statusCode());
        return "blob/Documents(" + json.readTree(created.body()).get("ID").asText() + ")";
    }

    @Test void workerDocumentsAreInvisibleThroughBlobServiceAndHaveNoODataPath() throws Exception {
        inbound().put(XML);
        outbound(NIP_A).put(XML);
        assertEquals(0, json.readTree(operatorA("GET", "blob/Documents", null, null).body()).get("value").size());
        assertEquals(0, json.readTree(operatorA("GET", "blob/Documents?$filter=sha256%20eq%20'" + SHA + "'", null, null).body()).get("value").size());
        for (String service : List.of("blob/", "KsefService/")) {
            var metadata = new String(operatorA("GET", service + "$metadata", null, null).body(), StandardCharsets.UTF_8);
            assertTrue(metadata.contains("EntityContainer"), service);
            assertFalse(metadata.contains("StoredDocuments"), "brak encji/zbioru w $metadata " + service);
            assertFalse(metadata.contains("ownerSegment"), "brak fizycznych kolumn klucza w $metadata " + service);
            for (String path : List.of("KsefStoredDocuments", "StoredDocuments",
                    "KsefStoredDocuments(environment='TEST',channel='INBOUND',ownerSegment='-',sha256='" + SHA + "')")) {
                assertEquals(404, operatorA("GET", service + path, null, null).statusCode(), service + path);
                assertTrue(operatorA("POST", service + path, "application/json", "{}".getBytes(StandardCharsets.UTF_8)).statusCode() >= 400);
                assertTrue(operatorA("DELETE", service + path, null, null).statusCode() >= 400);
            }
        }
        String batch = "--b1\r\nContent-Type: application/http\r\nContent-Transfer-Encoding: binary\r\n\r\nGET KsefStoredDocuments HTTP/1.1\r\n\r\n\r\n--b1--\r\n";
        String batchBody = new String(operatorA("POST", "blob/$batch", "multipart/mixed;boundary=b1", batch.getBytes(StandardCharsets.US_ASCII)).body(), StandardCharsets.UTF_8);
        assertFalse(batchBody.contains("STOR-04"), "$batch nie otwiera ścieżki do tabeli");
        assertEquals(2, storeRows().size());
    }

    /**
     * D-1 ZAMKNIĘTE STRUKTURALNIE (odwrócona asercja z KSEF-STOR-03 {@code knownGapPrincipalNameCollision…}):
     * BlobService jest projekcją wyłącznie tabeli LAB, więc żadna nazwa principala — także dawny principal
     * magazynu STOR-03, {@code system-internal} czy kontekst uprzywilejowany — nie zobaczy dokumentów workera.
     */
    @Test void noPrincipalNameOrPrivilegedContextSeesWorkerRowsThroughBlobService() {
        inbound().put(XML);
        CqnService blob = runtime.getServiceCatalog().getService(CqnService.class, "BlobService");
        for (String name : List.of("operator-a", "urn:ainvoicer:ksef-document-store", "system-internal", "privileged", "anonymous")) {
            long visible = runtime.requestContext()
                    .modifyUser(user -> user.setName(name).setIsAuthenticated(true).addRole("KsefOperator"))
                    .run((RequestContext ctx) -> blob.run(Select.from("BlobService.Documents")).rowCount());
            assertEquals(0, visible, name);
        }
        long privileged = runtime.requestContext().privilegedUser()
                .run((RequestContext ctx) -> blob.run(Select.from("BlobService.Documents")).rowCount());
        assertEquals(0, privileged, "nawet kontekst uprzywilejowany: projekcja nie obejmuje tabeli workera");
    }

    @Test void createdBySpoofingThroughLabCannotCreateOrClaimWorkerDocuments() throws Exception {
        for (String principal : List.of("urn:ainvoicer:ksef-document-store", "system-internal")) {
            var spoofed = operatorA("POST", "blob/Documents", "application/json", json.writeValueAsBytes(Map.of(
                    "fileName", "spoof.xml", "mimeType", "application/xml", "createdBy", principal, "sha256", SHA)));
            assertEquals(201, spoofed.statusCode());
            assertEquals("operator-a", json.readTree(spoofed.body()).get("createdBy").asText(), "createdBy z payloadu ignorowany");
        }
        assertTrue(storeRows().isEmpty(), "LAB nie ma ścieżki zapisu do tabeli workera");
        assertEquals("KSEF_DOCUMENT_NOT_FOUND", code(() -> inbound().get(INBOUND_REF)));
    }

    @Test void labDocumentWithIdenticalBytesIsNeverServedByStoreAndLabStillWorks() throws Exception {
        String labDoc = labCreate(INBOUND_REF.replace(':', '_') + ".xml");
        byte[] labXml = "<invoice><number>LAB-001</number></invoice>".getBytes(StandardCharsets.UTF_8);
        String labRef = "ksefdoc:v1:TEST:INBOUND:-:" + KsefInvoiceSnapshotRegistry.sha256Hex(labXml);
        assertEquals(204, operatorA("PUT", labDoc + "/content", "application/xml", labXml).statusCode());
        assertEquals("KSEF_DOCUMENT_NOT_FOUND", code(() -> inbound().get(labRef)), "wiersz LAB nie jest dokumentem magazynu");
        assertEquals(labRef, inbound().put(labXml));
        assertEquals(1, storeRows().size(), "put nie uznaje wiersza LAB za istniejący dokument");
        var download = operatorA("GET", labDoc + "/content", null, null);
        assertEquals(200, download.statusCode());
        assertArrayEquals(labXml, download.body(), "laboratorium BLOB-LAB-01 działa bez zmian");
        assertEquals(1, tx(() -> db.run(Select.from(LAB).columns("ID")).list().size()));
    }

    @Test void noServiceEntityProjectsThePrivateTable() {
        var model = runtime.getCdsModel();
        assertTrue(model.findEntity(ENTITY).isPresent(), "tabela jest w modelu persystencji");
        var projections = model.entities()
                .filter(entity -> !entity.getQualifiedName().equals(ENTITY))
                .filter(entity -> entity.getQualifiedName().contains("StoredDocuments")
                        || entity.query().map(query -> query.toJson().contains("KsefStoredDocuments")).orElse(false))
                .map(entity -> entity.getQualifiedName()).toList();
        assertEquals(List.of(), projections, "żadna projekcja/widok nie korzysta z prywatnej tabeli");
        assertTrue(model.services().noneMatch(service -> service.entities()
                .anyMatch(entity -> entity.getQualifiedName().contains("StoredDocuments"))));
    }

    @Test void scopedViewSatisfiesDurableInboundContract() {
        assertInstanceOf(DurableInboundDocumentStore.class, inbound());
        assertFalse(inbound().toString().contains(HanaBlobObjectStorageClient.KEY_NAMESPACE));
    }
}
