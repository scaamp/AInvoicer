package customer.cap_java;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cds.ql.Delete;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.persistence.PersistenceService;

import customer.cap_java.ksef.KsefDocumentStore;
import customer.cap_java.ksef.LocalTestDocumentStore;
import customer.cap_java.ksef.storage.InboundDocumentStorage;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;

/**
 * KSEF-IN-05 (B4/B5) — lokalny CAP HTTP (OData V4) na <b>H2 offline</b>, użytkownicy mock z atrybutami
 * {@code taxpayerNips}/{@code companyCodes} jak XSUAA ({@code application-test.yaml}).
 *
 * <p>Prawdziwy magazyn {@code HanaKsefDocumentStore} (adapter CQN na tabeli {@code KsefStoredDocuments}) jest
 * podłączony przez {@code ainv.ksef.inbound.storage.hana.enabled} + jawne {@code allow-local-h2}; to NIE jest dowód
 * HANA Cloud, XSUAA LIVE ani CF. CSRF egzekwuje AppRouter — tu sprawdzamy, że mutacje są odrzucane przez sam CAP.
 * Wyłącznie syntetyczne dane; brak HTTP do KSeF.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:ksef-in-05-download;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "ainv.ksef.inbound.storage.hana.enabled=true",
        "ainv.ksef.inbound.storage.hana.allow-local-h2=true",
        "ainv.ksef.inbound.xml-download.enabled=true" })
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class InboundInvoiceXmlODataTest {
    static final String NIP_A = "1234567802";
    static final String NIP_B = "9876543210";
    static final String OWN = "20000000-0000-4000-8000-000000000001";
    static final String SHARED = "20000000-0000-4000-8000-000000000002";
    static final String FOREIGN = "20000000-0000-4000-8000-000000000003";
    static final String SELLER_ONLY = "20000000-0000-4000-8000-000000000004";
    static final String DEMO = "20000000-0000-4000-8000-000000000005";
    static final String PENDING = "20000000-0000-4000-8000-000000000006";
    static final String CONFLICT = "20000000-0000-4000-8000-000000000007";
    static final String QUARANTINED = "20000000-0000-4000-8000-000000000008";
    static final String HASH_MISMATCH = "20000000-0000-4000-8000-000000000009";
    static final String MISSING_BLOB = "20000000-0000-4000-8000-00000000000a";
    static final String BROKEN_REF = "20000000-0000-4000-8000-00000000000b";
    static final String NO_GRANT = "20000000-0000-4000-8000-00000000000c";
    static final String STORED_DOCUMENTS = "customer.cap_java.KsefStoredDocuments";
    static final String DOCUMENTS = "customer.cap_java.KsefInboundDocuments";
    static final String GRANTS = "customer.cap_java.KsefInboundAccessGrants";
    /** BOM + CRLF + znaki spoza ASCII + znacznik XSS: pobranie ma zwrócić dokładnie te bajty. */
    static final String MARKER = "IN05-XML-BODY-MARKER";
    static final byte[] XML = ("﻿<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n<Faktura><P_2>" + MARKER
            + "/ąęł/€</P_2>\r\n<Nazwa>&lt;script&gt;alert(1)&lt;/script&gt;</Nazwa></Faktura>\r\n")
            .getBytes(StandardCharsets.UTF_8);
    static final byte[] XML_B = ("<?xml version=\"1.0\"?><Faktura><P_2>" + MARKER + "-B</P_2></Faktura>")
            .getBytes(StandardCharsets.UTF_8);
    static final String XSS = "<script>alert('in05')</script>";

    @LocalServerPort int port;
    @Autowired PersistenceService db;
    @Autowired InboundDocumentStorage storage;
    @Autowired ApplicationContext context;
    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();
    String reference;

    @BeforeEach void seed() {
        db.run(Delete.from(GRANTS));
        db.run(Delete.from(DOCUMENTS));
        db.run(Delete.from(STORED_DOCUMENTS));
        reference = storage.store().put(XML);
        String referenceB = storage.store().put(XML_B);
        document(OWN, reference, XML, "TEST", "STORED", "PARSED", "VERIFIED");
        grant(OWN, NIP_A, "BUYER");
        grant(OWN, NIP_B, "SELLER"); // grant SELLER nie daje B prawa BUYER do pobrania.
        document(SHARED, reference, XML, "TEST", "STORED", "PARSED", "VERIFIED");
        grant(SHARED, NIP_A, "BUYER");
        grant(SHARED, NIP_B, "BUYER");
        document(FOREIGN, referenceB, XML_B, "TEST", "STORED", "PARSED", "VERIFIED");
        grant(FOREIGN, NIP_B, "BUYER");
        document(SELLER_ONLY, reference, XML, "TEST", "STORED", "PARSED", "VERIFIED");
        grant(SELLER_ONLY, NIP_A, "SELLER");
        document(DEMO, reference, XML, "DEMO", "STORED", "PARSED", "VERIFIED");
        grant(DEMO, NIP_A, "BUYER");
        document(PENDING, null, XML, "TEST", "PENDING", "NOT_PARSED", "PENDING");
        grant(PENDING, NIP_A, "BUYER");
        document(CONFLICT, reference, XML, "TEST", "STORED", "PARSED", "CONFLICT");
        grant(CONFLICT, NIP_A, "BUYER");
        document(QUARANTINED, reference, XML, "TEST", "QUARANTINED", "INVALID", "VERIFIED");
        grant(QUARANTINED, NIP_A, "BUYER");
        document(HASH_MISMATCH, reference, XML_B, "TEST", "STORED", "PARSED", "VERIFIED");
        grant(HASH_MISMATCH, NIP_A, "BUYER");
        document(MISSING_BLOB, "ksefdoc:v1:TEST:INBOUND:-:" + "ab".repeat(32), XML, "TEST", "STORED", "PARSED", "VERIFIED");
        grant(MISSING_BLOB, NIP_A, "BUYER");
        document(BROKEN_REF, "test-fixture-no-xml-provider", XML, "TEST", "STORED", "PARSED", "VERIFIED");
        grant(BROKEN_REF, NIP_A, "BUYER");
        document(NO_GRANT, reference, XML, "TEST", "STORED", "PARSED", "VERIFIED");
    }

    // ---- B1: wiring ---------------------------------------------------------------------------------------

    @Test void enabledStorageRegistersExactlyOneInboundStoreAndOutboundKeepsSharedStore() {
        assertEquals(1, context.getBeansOfType(InboundDocumentStorage.class).size());
        var shared = context.getBeansOfType(KsefDocumentStore.class);
        assertEquals(1, shared.size(), shared::toString);
        assertInstanceOf(LocalTestDocumentStore.class, shared.values().iterator().next());
        assertEquals("ScopedDocumentStore[TEST/INBOUND]", storage.store().toString());
    }

    // ---- B4: pobranie -------------------------------------------------------------------------------------

    @Test void ownerDownloadsExactOriginalBytesWithAttachmentAndNoStoreHeaders() throws Exception {
        var response = get("operator-a", xml(OWN));
        assertEquals(200, response.statusCode(), () -> text(response));
        assertArrayEquals(XML, response.body(), "BOM, CRLF i bajty UTF-8 bez zmian");
        assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("application/xml"));
        String disposition = response.headers().firstValue("content-disposition").orElse("");
        assertTrue(disposition.startsWith("attachment"), disposition);
        assertTrue(disposition.contains("filename=\"" + ksefNumber(OWN) + ".xml\""), disposition);
        assertEquals("no-store", response.headers().firstValue("cache-control").orElse(""));
        assertEquals("nosniff", response.headers().firstValue("x-content-type-options").orElse(""));
        assertFalse(response.headers().map().toString().contains("ksefdoc"), "referencja magazynu nie trafia do nagłówków");
    }

    /** CompanyCode jest kontekstem sesji (V1), nie własnością dokumentu INBOUND: ten sam NIP z inną spółką widzi dokument. */
    @Test void companyCodeIsSessionContextNotDocumentOwnership() throws Exception {
        var otherCompany = get("operator-other-company", xml(OWN));
        assertEquals(200, otherCompany.statusCode());
        assertArrayEquals(XML, otherCompany.body());
        assertEquals(200, get("operator-peer", xml(OWN)).statusCode());
        String list = "InboundInvoiceOverview?$top=100";
        var approved = ids(get("operator-a", list));
        assertTrue(approved.contains(OWN));
        assertEquals(approved, ids(get("operator-other-company", list)));
        assertEquals(404, get("operator-other-company", xml(FOREIGN)).statusCode());
    }

    @Test void tenantBIsolatedFromTenantADocumentsWithIndistinguishable404() throws Exception {
        var foreign = get("operator-b", xml(OWN));
        var missing = get("operator-b", xml(UUID.randomUUID().toString()));
        assertEquals(404, foreign.statusCode());
        assertEquals(404, missing.statusCode());
        assertEquals(text(missing), text(foreign), "obcy i nieistniejący dokument są nierozróżnialne");
        assertArrayEquals(XML, get("operator-b", xml(SHARED)).body());
        assertArrayEquals(XML_B, get("operator-b", xml(FOREIGN)).body());
        assertEquals(404, get("operator-a", xml(FOREIGN)).statusCode());
    }

    @Test void authenticationAndRoleAreEnforcedBeforeHandler() throws Exception {
        assertEquals(401, get(null, xml(OWN)).statusCode());
        assertEquals(401, request("operator-a", "wrong-password", "GET", xml(OWN), null, null).statusCode());
        var forbidden = get("user-without-ksef-role", xml(OWN));
        assertEquals(403, forbidden.statusCode());
        assertSafe(forbidden);
    }

    @Test void missingMalformedOrAmbiguousScopeFailsClosed() throws Exception {
        for (String user : List.of("operator-without-taxpayer-attribute", "operator-without-company-attribute",
                "operator-cross-scope", "operator-one-nip-two-companies", "operator-malformed-scope")) {
            assertEquals(404, get(user, xml(OWN)).statusCode(), user);
        }
    }

    @Test void onlyBuyerGrantInTestEnvironmentForStoredVerifiedRows() throws Exception {
        for (String id : List.of(SELLER_ONLY, DEMO, PENDING, CONFLICT, QUARANTINED, NO_GRANT)) {
            var response = get("operator-a", xml(id));
            assertEquals(404, response.statusCode(), id);
            assertSafe(response);
        }
    }

    @Test void guessedDocumentIdsAllLookIdentical() throws Exception {
        String expected = null;
        for (int i = 0; i < 20; i++) {
            var response = get("operator-a", xml(UUID.randomUUID().toString()));
            assertEquals(404, response.statusCode());
            expected = expected == null ? text(response) : expected;
            assertEquals(expected, text(response));
        }
    }

    @Test void storageReferenceOrHashCannotBeUsedAsKeyOrFilter() throws Exception {
        String sha = KsefInvoiceSnapshotRegistry.sha256Hex(XML);
        for (String path : List.of("InboundInvoiceXml('" + reference + "')/content", "InboundInvoiceXml(" + sha + ")/content",
                "InboundInvoiceXml(id=" + OWN + ",sha256='" + sha + "')/content",
                "InboundInvoiceXml?$filter=" + enc("sha256 eq '" + sha + "'"),
                "InboundInvoiceOverview?$filter=" + enc("sha256 eq '" + sha + "'"),
                "InboundInvoiceOverview?$filter=" + enc("xmlStorageRef eq '" + reference + "'"),
                "InboundInvoiceOverview?$select=xmlStorageRef", "KsefStoredDocuments", "StoredDocuments")) {
            var response = get("operator-a", path);
            assertTrue(Set.of(400, 404).contains(response.statusCode()), path + ": " + response.statusCode());
            // Błąd parsera może powtórzyć nazwę pola z żądania klienta; nie może zwrócić treści ani stosu.
            for (String secret : new String[]{MARKER, "stackTrace", "java.lang.", "jdbc:", "KSEF_DOCUMENT_"}) {
                assertFalse(text(response).contains(secret), path + ": " + secret);
            }
        }
    }

    @Test void collectionCountFiltersAndEntityReadsAreRejected() throws Exception {
        for (String path : List.of("InboundInvoiceXml", "InboundInvoiceXml/$count", "InboundInvoiceXml?$top=1",
                "InboundInvoiceXml?$filter=" + enc("id eq " + OWN), "InboundInvoiceXml(" + OWN + ")",
                "InboundInvoiceXml(" + OWN + ")?$select=fileName", "InboundInvoiceXml(" + OWN + ")/fileName",
                "InboundInvoiceXml(" + OWN + ")?$expand=*")) {
            var response = get("operator-a", path);
            assertTrue(Set.of(400, 403, 404).contains(response.statusCode()), path + ": " + response.statusCode());
            assertFalse(text(response).contains(MARKER), path);
        }
    }

    @Test void mutationsAreRejectedByCapWithoutChangingStoredBytes() throws Exception {
        String sha = KsefInvoiceSnapshotRegistry.sha256Hex(XML);
        for (String[] call : new String[][]{{"POST", "InboundInvoiceXml"}, {"PATCH", "InboundInvoiceXml(" + OWN + ")"},
                {"PUT", "InboundInvoiceXml(" + OWN + ")"}, {"DELETE", "InboundInvoiceXml(" + OWN + ")"},
                {"PUT", xml(OWN)}, {"DELETE", xml(OWN)}}) {
            String contentType = call[1].endsWith("/content") ? "application/xml" : "application/json";
            byte[] body = call[0].equals("DELETE") ? null
                    : call[1].endsWith("/content") ? XML_B : ("{\"id\":\"" + OWN + "\",\"fileName\":\"x.xml\"}").getBytes(StandardCharsets.UTF_8);
            var response = request("operator-a", "test-a", call[0], call[1], contentType, body);
            assertTrue(Set.of(400, 403, 405).contains(response.statusCode()), call[0] + " " + call[1] + ": " + response.statusCode());
        }
        assertArrayEquals(XML, get("operator-a", xml(OWN)).body());
        assertEquals(sha, db.run(Select.from(STORED_DOCUMENTS).columns("sha256")
                .where(d -> d.get("sha256").eq(sha))).single().get("sha256"));
    }

    /** $batch z GET treści: odmowa w części batch, bez treści faktury w multipart. */
    @Test void batchCannotCarryXmlContent() throws Exception {
        String boundary = "batch_in05";
        String payload = "--" + boundary + "\r\nContent-Type: application/http\r\nContent-Transfer-Encoding: binary\r\n\r\n"
                + "GET " + xml(OWN) + " HTTP/1.1\r\n\r\n\r\n--" + boundary + "--\r\n";
        var response = http.send(HttpRequest.newBuilder(uri("$batch")).header("Authorization", basic("operator-a", "test-a"))
                .header("Content-Type", "multipart/mixed;boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
        assertFalse(response.body().contains(MARKER), response::body);
        assertEquals(200, response.statusCode(), response::body);
        assertTrue(response.body().contains("403") && response.body().contains("INBOUND_XML_DIRECT_GET_ONLY"), response::body);
    }

    @Test void batchOverviewKeyReadStillEnforcesBuyerGrant() throws Exception {
        String boundary = "batch_in05_read";
        String payload = "--" + boundary + "\r\nContent-Type: application/http\r\nContent-Transfer-Encoding: binary\r\n\r\n"
                + "GET InboundInvoiceOverview(" + OWN + ") HTTP/1.1\r\nAccept: application/json\r\n\r\n\r\n--" + boundary + "--\r\n";
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        String contentType = "multipart/mixed;boundary=" + boundary;
        var own = request("operator-a", "test-a", "POST", "$batch", contentType, body);
        var foreign = request("operator-b", "test-b", "POST", "$batch", contentType, body);
        assertEquals(200, own.statusCode());
        assertTrue(text(own).contains("HTTP/1.1 200") && text(own).contains(ksefNumber(OWN)));
        assertEquals(200, foreign.statusCode());
        assertTrue(text(foreign).contains("HTTP/1.1 404"));
        assertFalse(text(foreign).contains(ksefNumber(OWN)));
        assertSafe(foreign);
    }

    @Test void missingZeroOrIncorrectStagingSizeFailsWithIntactBlob(CapturedOutput output) throws Exception {
        for (Long size : new Long[] {null, 0L, -1L, XML.length - 1L, XML.length + 1L}) {
            var data = new HashMap<String, Object>();
            data.put("sizeBytes", size);
            db.run(Update.entity(DOCUMENTS).data(data).byId(OWN));
            var response = get("operator-a", xml(OWN));
            assertEquals(500, response.statusCode(), String.valueOf(size));
            assertTrue(text(response).contains("INBOUND_XML_UNAVAILABLE"));
            assertFalse(text(response).contains(KsefInvoiceSnapshotRegistry.sha256Hex(XML)));
            assertSafe(response);
        }
        assertArrayEquals(XML, storage.store().get(reference));
        assertFalse(output.getAll().contains(MARKER));
        assertFalse(output.getAll().contains(reference));
        assertFalse(output.getAll().contains(KsefInvoiceSnapshotRegistry.sha256Hex(XML)));
    }

    @Test void stagingSizeRequiresInteger64AndNonemptyXml() {
        assertTrue(InboundInvoiceXmlHandler.hasExpectedSize(2L, 2));
        assertFalse(InboundInvoiceXmlHandler.hasExpectedSize("2", 2));
        assertFalse(InboundInvoiceXmlHandler.hasExpectedSize(2.5, 2));
        assertFalse(InboundInvoiceXmlHandler.hasExpectedSize(0L, 0));
    }

    @Test void storageOrIntegrityFailuresAreGeneric5xxWithoutDetails(CapturedOutput output) throws Exception {
        String sha = KsefInvoiceSnapshotRegistry.sha256Hex(XML);
        for (String id : List.of(HASH_MISMATCH, MISSING_BLOB, BROKEN_REF)) {
            var response = get("operator-a", xml(id));
            assertEquals(500, response.statusCode(), id);
            assertSafe(response);
        }
        // Podmiana BLOB poza aplikacją (np. administrator DB): wykrycie, nie zwrot cudzych bajtów.
        db.run(Update.entity(STORED_DOCUMENTS).data(Map.of("content", XML_B)).where(d -> d.get("sha256").eq(sha)));
        var tampered = get("operator-a", xml(OWN));
        assertEquals(500, tampered.statusCode());
        assertSafe(tampered);
        assertFalse(output.getAll().contains(MARKER));
        assertFalse(output.getAll().contains(sha));
        assertFalse(output.getAll().contains("ksefdoc:v1"));
    }

    @Test void logsContainNeitherXmlNorCredentialsNorReferences(CapturedOutput output) throws Exception {
        assertEquals(200, get("operator-a", xml(OWN)).statusCode());
        assertEquals(404, get("operator-b", xml(OWN)).statusCode());
        assertEquals(401, request("operator-a", "wrong-password", "GET", xml(OWN), null, null).statusCode());
        String logs = output.getAll();
        for (String secret : new String[]{MARKER, reference, KsefInvoiceSnapshotRegistry.sha256Hex(XML), "wrong-password", basic("operator-a", "test-a").substring(6), "<Faktura>"}) {
            assertFalse(logs.contains(secret), "log leak: " + secret.substring(0, Math.min(8, secret.length())));
        }
    }

    // ---- B5: overview (ten sam model zakresu) ------------------------------------------------------------

    @Test void overviewUsesSameScopeAndReturnsXssPayloadAsInertJsonData() throws Exception {
        db.run(Update.entity(DOCUMENTS).data(Map.of("sellerName", XSS, "invoiceNumber", XSS)).byId(OWN));
        var response = get("operator-a", "InboundInvoiceOverview(" + OWN + ")");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("application/json"));
        JsonNode body = json.readTree(response.body());
        assertEquals(XSS, body.path("sellerName").asText(), "dane bez zmian; render/escaping należy do UI5");
        assertEquals(XSS, body.path("invoiceNumber").asText());
        assertTrue(body.path("xmlAvailable").asBoolean());
        assertFalse(text(response).contains("xmlStorageRef") || text(response).contains("ksefdoc"));
        assertEquals(Set.of(OWN, SHARED, PENDING, CONFLICT, QUARANTINED, HASH_MISMATCH, MISSING_BLOB, BROKEN_REF),
                ids(get("operator-a", "InboundInvoiceOverview?$top=100")));
        assertEquals(Set.of(SHARED, FOREIGN), ids(get("operator-b", "InboundInvoiceOverview?$top=100")));
        assertEquals(404, get("operator-b", "InboundInvoiceOverview(" + OWN + ")").statusCode());
        assertEquals(403, get("user-without-ksef-role", "InboundInvoiceOverview").statusCode());
        assertEquals(401, get(null, "InboundInvoiceOverview").statusCode());
        for (String user : List.of("operator-cross-scope", "operator-one-nip-two-companies", "operator-malformed-scope")) {
            assertEquals(Set.of(), ids(get(user, "InboundInvoiceOverview")), user);
        }
    }

    @Test void overviewMutationsAndBatchWritesAreRejected() throws Exception {
        for (String method : List.of("POST", "PATCH", "DELETE")) {
            String path = method.equals("POST") ? "InboundInvoiceOverview" : "InboundInvoiceOverview(" + OWN + ")";
            var response = request("operator-a", "test-a", method, path, "application/json",
                    method.equals("DELETE") ? null : "{\"invoiceNumber\":\"x\"}".getBytes(StandardCharsets.UTF_8));
            assertTrue(Set.of(403, 405).contains(response.statusCode()), method + ": " + response.statusCode());
        }
        String boundary = "batch_in05w";
        String payload = "--" + boundary + "\r\nContent-Type: multipart/mixed;boundary=cs\r\n\r\n--cs\r\n"
                + "Content-Type: application/http\r\nContent-Transfer-Encoding: binary\r\nContent-ID: 1\r\n\r\n"
                + "DELETE InboundInvoiceOverview(" + OWN + ") HTTP/1.1\r\n\r\n\r\n--cs--\r\n--" + boundary + "--\r\n";
        http.send(HttpRequest.newBuilder(uri("$batch")).header("Authorization", basic("operator-a", "test-a"))
                .header("Content-Type", "multipart/mixed;boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(1, db.run(Select.from(DOCUMENTS).where(d -> d.get("ID").eq(OWN))).rowCount());
    }

    @Test void metadataPublishesOnlyKeyFileNameAndMediaContent() throws Exception {
        String metadata = text(get("operator-a", "$metadata"));
        assertTrue(metadata.contains("EntityType Name=\"InboundInvoiceXml\""), metadata);
        for (String secret : new String[]{"xmlStorageRef", "sha256", "KsefStoredDocuments", "ownerSegment", "KsefInboundDocuments"}) {
            assertFalse(metadata.contains(secret), secret);
        }
    }

    // ---- Pomocnicze ---------------------------------------------------------------------------------------

    void document(String id, String ref, byte[] bytes, String env, String ingestion, String parse, String integrity) {
        var row = new HashMap<String, Object>();
        row.put("ID", id);
        row.put("environment", env);
        row.put("ksefNumber", NIP_A + "-20261123-" + id.substring(24).toUpperCase() + "-AA");
        row.put("invoiceNumber", "FV/IN05/" + id.substring(30));
        row.put("issueDate", "2026-11-20");
        row.put("acquisitionDate", Instant.parse("2026-11-21T10:00:00Z"));
        row.put("sellerNip", NIP_A);
        row.put("sellerName", "Synthetic IN-05 seller");
        row.put("buyerIdentifierType", "Nip");
        row.put("buyerIdentifierValue", NIP_A); // widoczność wyłącznie z grantu, nie z tego pola
        row.put("currency", "PLN");
        row.put("formSystemCode", "FA (3)");
        row.put("sha256", KsefInvoiceSnapshotRegistry.sha256Hex(bytes));
        row.put("sizeBytes", (long) bytes.length);
        row.put("xmlStorageRef", ref);
        row.put("ingestionStatus", ingestion);
        row.put("parseStatus", parse);
        row.put("integrityStatus", integrity);
        db.run(Insert.into(DOCUMENTS).entry(row));
    }

    void grant(String id, String nip, String role) {
        db.run(Insert.into(GRANTS).entry(Map.of("ID", UUID.randomUUID().toString(), "documentId", id,
                "contextNip", nip, "subjectRole", role, "firstSeenAt", Instant.now())));
    }

    static String ksefNumber(String id) { return NIP_A + "-20261123-" + id.substring(24).toUpperCase() + "-AA"; }
    static String xml(String id) { return "InboundInvoiceXml(" + id + ")/content"; }
    static String enc(String text) { return java.net.URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20"); }
    static String text(HttpResponse<byte[]> response) { return new String(response.body(), StandardCharsets.UTF_8); }

    static final Map<String, String> PASSWORDS = Map.ofEntries(Map.entry("operator-a", "test-a"),
            Map.entry("operator-b", "test-b"), Map.entry("operator-other-company", "test-cc"),
            Map.entry("operator-peer", "test-peer"), Map.entry("user-without-ksef-role", "test-user"),
            Map.entry("operator-without-taxpayer-attribute", "test-no-nip"),
            Map.entry("operator-without-company-attribute", "test-no-cc"), Map.entry("operator-cross-scope", "test-cross"),
            Map.entry("operator-one-nip-two-companies", "test-one-nip"), Map.entry("operator-malformed-scope", "test-malformed"));

    static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/odata/v4/KsefService/" + path); }

    HttpResponse<byte[]> get(String user, String path) throws Exception {
        return request(user, user == null ? null : PASSWORDS.get(user), "GET", path, null, null);
    }

    HttpResponse<byte[]> request(String user, String password, String method, String path, String contentType, byte[] body)
            throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).header("OData-MaxVersion", "4.0");
        if (user != null) { builder.header("Authorization", basic(user, password)); }
        if (contentType != null) { builder.header("Content-Type", contentType); }
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    Set<String> ids(HttpResponse<byte[]> response) throws Exception {
        assertEquals(200, response.statusCode(), () -> text(response));
        var result = new java.util.HashSet<String>();
        json.readTree(response.body()).path("value").forEach(r -> result.add(r.path("id").asText()));
        return result;
    }

    void assertSafe(HttpResponse<byte[]> response) {
        String body = text(response);
        for (String secret : new String[]{MARKER, "ksefdoc", "xmlStorageRef", "sha256", "stackTrace", "java.lang.",
                "jdbc:", "KSEF_DOCUMENT_", reference}) {
            assertFalse(body.contains(secret), "unsafe body: " + secret);
        }
    }
}
