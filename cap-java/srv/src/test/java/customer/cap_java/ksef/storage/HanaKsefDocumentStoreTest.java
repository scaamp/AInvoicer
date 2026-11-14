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
 * KSEF-STOR-03 na prawdziwym CAP CQN/H2 (ta sama tabela {@code KsefDocuments}, co laboratoryjny BlobService):
 * kontrakt bajtów i SHA-256, idempotencja, współbieżność, scope, uszkodzenia, awarie DB i izolacja od
 * publicznego OData. H2 nie dowodzi zachowania HANA Cloud LIVE ani restartu procesu CF.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:ksef-stor-03;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "logging.level.root=WARN" })
@ActiveProfiles("test")
class HanaKsefDocumentStoreTest {
    static final String ENTITY = HanaBlobObjectStorageClient.ENTITY;
    static final String PRINCIPAL = HanaBlobObjectStorageClient.STORE_PRINCIPAL;
    static final String PREFIX = "ainvoicer-test";
    static final String NIP_A = "1111111111";
    static final String NIP_B = "2222222222";
    /** BOM, CRLF, znaki spoza ASCII: dokładne bajty, bez ponownej serializacji XML. */
    static final byte[] XML = ("﻿<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n<Faktura>"
            + "<P_2>STOR-03/ąęł/€</P_2>\r\n</Faktura>\r\n").getBytes(StandardCharsets.UTF_8);

    @LocalServerPort int port;
    @Autowired PersistenceService db;
    @Autowired CdsRuntime runtime;
    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach void clean() { tx(() -> db.run(Delete.from(ENTITY))); }

    ContentAddressedDocumentStore root(PersistenceService persistence, int limit) {
        return HanaKsefDocumentStore.create(persistence, runtime, PREFIX, EnumSet.of(KsefEnvironment.TEST),
                limit, Clock.systemUTC(), d -> { });
    }

    /** Nowa instancja adaptera = brak współdzielonego stanu w pamięci (H2: nie jest restartem procesu CF). */
    DurableInboundDocumentStore inbound() { return root(db, HanaKsefDocumentStore.DEFAULT_MAX_DOCUMENT_BYTES).forScope(DocumentScope.inbound(KsefEnvironment.TEST)); }

    KsefDocumentStore outbound(String nip) { return root(db, HanaKsefDocumentStore.DEFAULT_MAX_DOCUMENT_BYTES).forScope(DocumentScope.outbound(KsefEnvironment.TEST, nip)); }

    <T> T tx(Supplier<T> work) {
        return runtime.requestContext().systemUser().run((RequestContext ctx) ->
                runtime.changeSetContext().run(cs -> { return work.get(); }));
    }

    List<Map<String, Object>> storeRows() {
        return tx(() -> db.run(Select.from(ENTITY).columns("ID", "fileName", "mimeType", "sha256", "createdBy", "modifiedBy", "modifiedAt", "createdAt")
                .where(d -> d.get("createdBy").eq(PRINCIPAL))).list().stream()
                .<Map<String, Object>>map(HashMap<String, Object>::new).toList());
    }

    static String key(String scopePath, byte[] bytes) {
        return PREFIX + "/v1/test/" + scopePath + "/" + KsefInvoiceSnapshotRegistry.sha256Hex(bytes) + ".xml";
    }

    static String code(Runnable action) { return assertThrows(KsefFailure.class, action::run).getMessage(); }

    // ---------------------------------------------------------------- kontrakt bajtów, SHA-256, idempotencja

    @Test void putAndGetReturnIdenticalBytesWithBackendSha256() {
        String sha = KsefInvoiceSnapshotRegistry.sha256Hex(XML);
        String reference = inbound().put(XML);
        assertEquals("ksefdoc:v1:TEST:INBOUND:-:" + sha, reference);
        byte[] restored = inbound().get(reference);
        assertArrayEquals(XML, restored);
        assertEquals(sha, KsefInvoiceSnapshotRegistry.sha256Hex(restored));
        var row = storeRows().getFirst();
        assertEquals(sha, row.get("sha256"), "SHA-256 liczony przez backend z zapisanych bajtów");
        assertEquals(key("inbound/shared", XML), row.get("fileName"));
        assertEquals(HanaBlobObjectStorageClient.deterministicId(key("inbound/shared", XML)), row.get("ID"));
        assertEquals("application/xml", row.get("mimeType"));
        assertEquals(PRINCIPAL, row.get("modifiedBy"));
    }

    @Test void repeatedPutIsIdempotentAndNeverRewritesTheRow() {
        String first = inbound().put(XML);
        var before = storeRows().getFirst();
        for (int i = 0; i < 3; i++) { assertEquals(first, inbound().put(XML.clone())); }
        assertEquals(1, storeRows().size());
        assertEquals(before.get("modifiedAt"), storeRows().getFirst().get("modifiedAt"), "brak UPDATE przy ponowieniu");
    }

    @Test void concurrentPutsOfSameDocumentProduceOneRowAndOneReference() throws Exception {
        int threads = 8;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);
        try {
            var results = new ArrayList<Future<String>>();
            for (int i = 0; i < threads; i++) {
                var store = inbound();
                results.add(pool.submit(() -> { start.await(); return store.put(XML); }));
            }
            start.countDown();
            String expected = "ksefdoc:v1:TEST:INBOUND:-:" + KsefInvoiceSnapshotRegistry.sha256Hex(XML);
            for (var result : results) { assertEquals(expected, result.get()); }
        } finally { pool.shutdownNow(); }
        assertEquals(1, storeRows().size(), "deterministyczny PK działa jak UNIQUE");
        assertArrayEquals(XML, inbound().get("ksefdoc:v1:TEST:INBOUND:-:" + KsefInvoiceSnapshotRegistry.sha256Hex(XML)));
    }

    @Test void freshAdapterInstanceReadsDocumentsWrittenByAnotherInstance() {
        String reference = HanaKsefDocumentStore.create(db, runtime, PREFIX, EnumSet.of(KsefEnvironment.TEST))
                .forScope(DocumentScope.inbound(KsefEnvironment.TEST)).put(XML);
        assertArrayEquals(XML, HanaKsefDocumentStore.create(db, runtime, PREFIX, EnumSet.of(KsefEnvironment.TEST))
                .forScope(DocumentScope.inbound(KsefEnvironment.TEST)).get(reference));
    }

    // ---------------------------------------------------------------- referencje i scope

    @Test void malformedOrInjectedReferencesAreRejectedWithoutDatabaseAccess() {
        String sha = KsefInvoiceSnapshotRegistry.sha256Hex(XML);
        inbound().put(XML);
        String rowId = (String) storeRows().getFirst().get("ID");
        for (String bad : new String[] { null, "", rowId, sha, sha + ".xml", key("inbound/shared", XML),
                "ksefdoc:v1:TEST:INBOUND:-:../" + sha, "ksefdoc:v1:TEST:INBOUND:-:" + sha.toUpperCase(),
                "ksefdoc:v1:TEST:INBOUND:-:" + sha + "\n", "ksefdoc:v2:TEST:INBOUND:-:" + sha,
                "ksefdoc:v1:TEST:INBOUND:1111111111:" + sha, "ksefdoc:v1:TEST:OUTBOUND:-:" + sha }) {
            assertEquals("KSEF_DOCUMENT_REFERENCE_INVALID", code(() -> inbound().get(bad)), String.valueOf(bad));
        }
        assertEquals("KSEF_DOCUMENT_NOT_FOUND", code(() -> inbound().get("ksefdoc:v1:TEST:INBOUND:-:" + "0".repeat(64))));
    }

    @Test void foreignScopeIsDeniedEvenWithIdenticalHash() {
        String a = outbound(NIP_A).put(XML);
        String in = inbound().put(XML);
        assertNotEquals(a, in, "ten sam hash w innym kanale = osobny obiekt i referencja");
        assertEquals(2, storeRows().size());
        assertEquals("KSEF_DOCUMENT_ACCESS_DENIED", code(() -> outbound(NIP_B).get(a)));
        assertEquals("KSEF_DOCUMENT_ACCESS_DENIED", code(() -> inbound().get(a)));
        assertEquals("KSEF_DOCUMENT_ACCESS_DENIED", code(() -> outbound(NIP_A).get(in)));
        assertEquals("KSEF_DOCUMENT_ACCESS_DENIED", code(() -> inbound().get(a.replace(":TEST:", ":DEMO:"))));
        assertEquals("KSEF_DOCUMENT_SCOPE_FORBIDDEN", code(() -> root(db, 1024).forScope(DocumentScope.inbound(KsefEnvironment.PROD))));
        assertArrayEquals(XML, outbound(NIP_A).get(a));
    }

    @Test void deploymentPrefixIsolatesStores() {
        String reference = inbound().put(XML);
        var other = HanaKsefDocumentStore.create(db, runtime, "ainvoicer-other", EnumSet.of(KsefEnvironment.TEST))
                .forScope(DocumentScope.inbound(KsefEnvironment.TEST));
        assertEquals("KSEF_DOCUMENT_NOT_FOUND", code(() -> other.get(reference)));
    }

    // ---------------------------------------------------------------- integralność i niezmienność

    @Test void corruptedBlobHashColumnOrMediaTypeFailIntegrity() {
        String reference = inbound().put(XML);
        String id = (String) storeRows().getFirst().get("ID");
        tx(() -> db.run(Update.entity(ENTITY).data(Map.of("content", new ByteArrayInputStream("<Faktura>forged</Faktura>".getBytes(StandardCharsets.UTF_8))))
                .where(d -> d.get("ID").eq(id))));
        assertEquals("KSEF_DOCUMENT_INTEGRITY_FAILED", code(() -> inbound().get(reference)));
        assertEquals("KSEF_DOCUMENT_INTEGRITY_FAILED", code(() -> inbound().put(XML)), "put nie nadpisuje uszkodzonego wiersza");

        tx(() -> db.run(Delete.from(ENTITY)));
        String again = inbound().put(XML);
        tx(() -> db.run(Update.entity(ENTITY).data(Map.of("sha256", "0".repeat(64))).where(d -> d.get("createdBy").eq(PRINCIPAL))));
        assertEquals("KSEF_DOCUMENT_INTEGRITY_FAILED", code(() -> inbound().get(again)));

        tx(() -> db.run(Delete.from(ENTITY)));
        String third = inbound().put(XML);
        tx(() -> db.run(Update.entity(ENTITY).data(Map.of("mimeType", "application/pdf")).where(d -> d.get("createdBy").eq(PRINCIPAL))));
        assertEquals("KSEF_DOCUMENT_INTEGRITY_FAILED", code(() -> inbound().get(third)));

        tx(() -> db.run(Delete.from(ENTITY)));
        String empty = inbound().put(XML);
        tx(() -> db.run(Update.entity(ENTITY).data(Map.of("content", new ByteArrayInputStream(new byte[0]))).where(d -> d.get("createdBy").eq(PRINCIPAL))));
        assertEquals("KSEF_DOCUMENT_INTEGRITY_FAILED", code(() -> inbound().get(empty)));
    }

    @Test void differentContentCannotReplaceStoredXml() {
        String reference = inbound().put(XML);
        byte[] other = "<Faktura>other</Faktura>".getBytes(StandardCharsets.UTF_8);
        String second = inbound().put(other);
        assertNotEquals(reference, second, "adres = treść; inna treść nigdy nie trafia pod istniejącą referencję");
        assertArrayEquals(XML, inbound().get(reference));
        assertArrayEquals(other, inbound().get(second));
    }

    // ---------------------------------------------------------------- limity rozmiaru

    @Test void explicitSizePolicyIndependentOfLabLimit() {
        assertEquals(3 * 1024 * 1024, HanaKsefDocumentStore.DEFAULT_MAX_DOCUMENT_BYTES);
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
        assertThrows(IllegalArgumentException.class, () -> root(db, 0));
        assertThrows(IllegalArgumentException.class, () -> root(db, HanaKsefDocumentStore.HARD_MAX_DOCUMENT_BYTES + 1));
    }

    @Test void oversizedStoredRowIsRejectedOnRead() {
        byte[] big = new byte[2048];
        java.util.Arrays.fill(big, (byte) 'b');
        String reference = root(db, 4096).forScope(DocumentScope.inbound(KsefEnvironment.TEST)).put(big);
        assertEquals("KSEF_DOCUMENT_SIZE_INVALID", code(() -> root(db, 1024).forScope(DocumentScope.inbound(KsefEnvironment.TEST)).get(reference)));
    }

    // ---------------------------------------------------------------- awarie DB, rollback, retry

    @Test void databaseOutageFailsRetryablyWithoutLeakingDetails() {
        var calls = new AtomicInteger();
        PersistenceService down = mock(PersistenceService.class, invocation -> {
            calls.incrementAndGet();
            throw new ServiceException(ErrorStatuses.SERVER_ERROR, "SQL SECRET-HOST table CUSTOMER_CAP_JAVA_KSEFDOCUMENTS");
        });
        var failure = assertThrows(KsefFailure.class, () -> root(down, 4096).forScope(DocumentScope.inbound(KsefEnvironment.TEST)).put(XML));
        assertEquals("KSEF_DOCUMENT_STORE_UNAVAILABLE", failure.getMessage());
        assertTrue(failure.retryable());
        assertFalse(failure.toString().contains("SECRET-HOST"));
        assertEquals(3, calls.get(), "ograniczone ponowienia (3 próby INSERT)");
        var readFailure = assertThrows(KsefFailure.class, () -> root(down, 4096).forScope(DocumentScope.inbound(KsefEnvironment.TEST))
                .get("ksefdoc:v1:TEST:INBOUND:-:" + KsefInvoiceSnapshotRegistry.sha256Hex(XML)));
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
        String reference = inbound().put(XML);
        assertEquals(1, storeRows().size());
        assertArrayEquals(XML, inbound().get(reference));
    }

    // ---------------------------------------------------------------- izolacja od laboratoryjnego BlobService

    HttpResponse<byte[]> odata(String user, String password, String method, String path, String mime, byte[] body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/odata/v4/blob/" + path));
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

    @Test void storeRowsAreInvisibleAndImmutableThroughPublicBlobService() throws Exception {
        String reference = inbound().put(XML);
        String id = (String) storeRows().getFirst().get("ID");
        String doc = "Documents(" + id + ")";
        assertEquals(404, operatorA("GET", doc, null, null).statusCode());
        assertEquals(404, operatorA("GET", doc + "/content", null, null).statusCode());
        assertEquals(0, json.readTree(operatorA("GET", "Documents", null, null).body()).get("value").size());
        assertEquals(0, json.readTree(operatorA("GET", "Documents?$filter=startswith(fileName,'" + PREFIX + "')", null, null).body()).get("value").size());
        assertTrue(operatorA("PUT", doc + "/content", "application/xml", "<x/>".getBytes(StandardCharsets.UTF_8)).statusCode() >= 400);
        assertTrue(operatorA("PATCH", doc, "application/json", "{\"mimeType\":\"application/pdf\"}".getBytes(StandardCharsets.UTF_8)).statusCode() >= 400);
        assertTrue(operatorA("DELETE", doc, null, null).statusCode() >= 400);
        assertEquals(403, odata("user-without-ksef-role", "test-user", "GET", doc, null, null).statusCode());
        assertEquals(401, odata(null, null, "GET", doc + "/content", null, null).statusCode());
        String batch = "--b1\r\nContent-Type: application/http\r\nContent-Transfer-Encoding: binary\r\n\r\nGET " + doc
                + "/content HTTP/1.1\r\n\r\n\r\n--b1--\r\n";
        var batchResponse = operatorA("POST", "$batch", "multipart/mixed;boundary=b1", batch.getBytes(StandardCharsets.US_ASCII));
        String batchBody = new String(batchResponse.body(), StandardCharsets.UTF_8);
        assertTrue(batchBody.contains("404"), batchBody);
        assertFalse(batchBody.contains("STOR-03"), "$batch nie omija filtra właściciela");
        assertArrayEquals(XML, inbound().get(reference));
        assertEquals(1, storeRows().size());
    }

    @Test void labCreateCannotForgeStoreKeyOrPrincipal() throws Exception {
        String objectKey = key("inbound/shared", XML);
        var forged = operatorA("POST", "Documents", "application/json", json.writeValueAsBytes(
                Map.of("fileName", objectKey, "mimeType", "application/xml")));
        assertEquals(400, forged.statusCode(), "laboratoryjny handler odrzuca '/' w nazwie");
        var spoofed = operatorA("POST", "Documents", "application/json", json.writeValueAsBytes(
                Map.of("fileName", "spoof.xml", "mimeType", "application/xml", "createdBy", PRINCIPAL)));
        assertEquals(201, spoofed.statusCode());
        assertEquals("operator-a", json.readTree(spoofed.body()).get("createdBy").asText(), "createdBy z payloadu ignorowany");
        assertTrue(storeRows().isEmpty());
    }

    @Test void labRowNamedLikeReferenceIsNeverServedByStore() throws Exception {
        String reference = "ksefdoc:v1:TEST:INBOUND:-:" + KsefInvoiceSnapshotRegistry.sha256Hex(XML);
        var created = operatorA("POST", "Documents", "application/json", json.writeValueAsBytes(
                Map.of("fileName", reference + ".xml", "mimeType", "application/xml")));
        assertEquals(201, created.statusCode());
        String labDoc = "Documents(" + json.readTree(created.body()).get("ID").asText() + ")";
        assertEquals(204, operatorA("PUT", labDoc + "/content", "application/xml", XML).statusCode());
        assertEquals("KSEF_DOCUMENT_NOT_FOUND", code(() -> inbound().get(reference)), "wiersz laboratorium nie jest dokumentem magazynu");
        assertEquals(reference, inbound().put(XML));
        assertEquals(1, storeRows().size(), "put nie uznaje wiersza laboratorium za istniejący dokument");
        assertArrayEquals(XML, operatorA("GET", labDoc + "/content", null, null).body(), "wiersz laboratorium nienaruszony");
    }

    @Test void squattedDeterministicIdDoesNotBlockOrExposeStore() throws Exception {
        String objectKey = key("inbound/shared", XML);
        String squattedId = HanaBlobObjectStorageClient.deterministicId(objectKey);
        var squat = operatorA("POST", "Documents", "application/json", json.writeValueAsBytes(
                Map.of("ID", squattedId, "fileName", "squat.xml", "mimeType", "application/xml")));
        assertEquals(201, squat.statusCode(), "publiczny POST akceptuje ID klienta (dlatego ID nie jest dowodem)");
        String reference = inbound().put(XML);
        assertEquals(reference, inbound().put(XML), "idempotencja także po zajęciu ID");
        assertEquals(1, storeRows().size());
        assertNotEquals(squattedId, storeRows().getFirst().get("ID"));
        assertArrayEquals(XML, inbound().get(reference));
        var labRow = json.readTree(operatorA("GET", "Documents(" + squattedId + ")", null, null).body());
        assertEquals("squat.xml", labRow.get("fileName").asText());
        assertEquals(1, json.readTree(operatorA("GET", "Documents", null, null).body()).get("value").size());
        assertEquals(404, operatorA("GET", "Documents(" + storeRows().getFirst().get("ID") + ")", null, null).statusCode());
    }

    @Test void orphanAuditListsOnlyStoreRowsOfItsPartition() throws Exception {
        String referenced = inbound().put(XML);
        String orphan = inbound().put("<Faktura>orphan</Faktura>".getBytes(StandardCharsets.UTF_8));
        outbound(NIP_A).put(XML);
        operatorA("POST", "Documents", "application/json", json.writeValueAsBytes(Map.of("fileName", "lab.xml", "mimeType", "application/xml")));
        var report = root(db, 4096).orphanReport(DocumentScope.inbound(KsefEnvironment.TEST), Set.of(referenced), java.time.Duration.ZERO);
        assertEquals(2, report.scanned());
        assertEquals(List.of(orphan), report.orphanReferences());
        assertEquals(0, report.unrecognizedKeys());
        assertEquals(4, tx(() -> db.run(Select.from(ENTITY).columns("ID")).list().size()), "audyt niczego nie usuwa");
    }

    /**
     * ZNANA LUKA SCHEMATU (D-1, decyzja właściciela): izolacja od BlobService opiera się wyłącznie na
     * {@code createdBy = $user}. Użytkownik z rolą KsefOperator i nazwą równą principalowi magazynu
     * zobaczyłby dokumenty workerów. Test dokumentuje zachowanie; nie jest akceptacją ryzyka.
     */
    @Test void knownGapPrincipalNameCollisionWouldExposeStoreRowsThroughLabService() {
        inbound().put(XML);
        CqnService blob = runtime.getServiceCatalog().getService(CqnService.class, "BlobService");
        long visibleToOperator = runtime.requestContext()
                .modifyUser(user -> user.setName("operator-a").setIsAuthenticated(true).addRole("KsefOperator"))
                .run((RequestContext ctx) -> blob.run(Select.from("BlobService.Documents")).rowCount());
        long visibleToCollidingName = runtime.requestContext()
                .modifyUser(user -> user.setName(PRINCIPAL).setIsAuthenticated(true).addRole("KsefOperator"))
                .run((RequestContext ctx) -> blob.run(Select.from("BlobService.Documents")).rowCount());
        assertEquals(0, visibleToOperator);
        assertEquals(1, visibleToCollidingName, "luka D-1 nadal istnieje — po zmianie schematu odwrócić asercję");
    }

    @Test void scopedViewSatisfiesDurableInboundContract() {
        assertInstanceOf(DurableInboundDocumentStore.class, inbound());
        assertFalse(inbound().toString().contains(PREFIX));
    }
}
