package customer.cap_java;

import static customer.cap_java.ksef.inbound.InboundTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cds.ql.Delete;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.ksef.fa3.Fa3SchemaValidator;
import customer.cap_java.ksef.inbound.InvoiceRetrievalService;
import customer.cap_java.ksef.inbound.KsefSubjectRole;
import customer.cap_java.ksef.inbound.PersistentInboundInvoiceSink;
import customer.cap_java.ksef.inbound.ReadRetryPolicy;
import customer.cap_java.ksef.inbound.fa3.SecureFa3InvoiceParser;
import customer.cap_java.ksef.inbound.persistence.CqnInboundDocumentRepository;
import customer.cap_java.ksef.inbound.persistence.CqnSyncCheckpointRepository;
import customer.cap_java.ksef.inbound.persistence.InboundPersistence;
import customer.cap_java.ksef.inbound.sync.InvoiceSyncRunner;
import customer.cap_java.ksef.inbound.sync.InvoiceSyncWindowPlanner;
import customer.cap_java.ksef.inbound.sync.SyncScope;
import customer.cap_java.ksef.storage.InboundDocumentStorage;
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient;

/**
 * BETA-01 (orkiestrator): jeden łańcuch OFFLINE bez ręcznego zasilania tabel —
 * syntetyczny XML z atrapy SDK KSeF → {@link PersistentInboundInvoiceSink} (SHA-256, FA(3), XSD, cross-check)
 * → podłączony bean {@link InboundDocumentStorage} (adapter {@code HanaKsefDocumentStore} na <b>H2</b>)
 * → staging + grant + checkpoint → lokalny CAP HTTP: {@code InboundInvoiceOverview} i
 * {@code InboundInvoiceXml(<id>)/content}. Dowodzi połączenia komponentów; NIE dowodzi HANA Cloud, XSUAA, CF ani KSeF.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:beta-01-pipeline-odata;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "logging.level.root=WARN",
        "ainv.ksef.inbound.storage.hana.enabled=true",
        "ainv.ksef.inbound.storage.hana.allow-local-h2=true",
        "ainv.ksef.inbound.xml-download.enabled=true",
        // Lista zastępuje użytkowników z application-test.yaml: nabywca z kontekstu pipeline i obcy podatnik.
        "cds.security.mock.users[0].name=buyer-context",
        "cds.security.mock.users[0].password=test-buyer",
        "cds.security.mock.users[0].roles[0]=KsefOperator",
        "cds.security.mock.users[0].attributes.taxpayerNips[0]=" + CONTEXT_NIP,
        "cds.security.mock.users[0].attributes.companyCodes[0]=1000",
        "cds.security.mock.users[1].name=other-taxpayer",
        "cds.security.mock.users[1].password=test-other",
        "cds.security.mock.users[1].roles[0]=KsefOperator",
        "cds.security.mock.users[1].attributes.taxpayerNips[0]=" + OTHER_NIP,
        "cds.security.mock.users[1].attributes.companyCodes[0]=2000" })
@ActiveProfiles("test")
class InboundPipelineToODataTest {
    static final OffsetDateTime START = OffsetDateTime.parse("2026-11-01T00:00:00Z");
    static final OffsetDateTime HWM = OffsetDateTime.parse("2026-11-09T10:00:00Z");
    static final SyncScope SCOPE = SyncScope.of(CONTEXT, KsefSubjectRole.BUYER);

    @LocalServerPort int port;
    @Autowired PersistenceService db;
    @Autowired CdsRuntime runtime;
    @Autowired InboundDocumentStorage storage;
    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();
    InboundPersistence persistence;
    KSeFClient sdk;
    byte[] xml;

    @BeforeEach void reset() throws Exception {
        persistence = new InboundPersistence(db, runtime);
        persistence.tx(() -> {
            for (String entity : List.of(InboundPersistence.GRANTS, InboundPersistence.CONFLICTS,
                    InboundPersistence.DOCUMENTS, InboundPersistence.CHECKPOINTS, InboundPersistence.LEASES,
                    "customer.cap_java.KsefStoredDocuments")) {
                db.run(Delete.from(entity));
            }
            return null;
        });
        sdk = mock(KSeFClient.class);
        xml = resource("fa3-purchase-synthetic.xml");
        when(sdk.getInvoice(anyString(), anyString())).thenReturn(xml);
        when(sdk.queryInvoiceMetadata(eq(0), anyInt(), any(), any(), any())).thenReturn(response(false, false,
                HWM.toString(), sdkItem(metadataJson(ksefNumber(1), "2026-11-02T09:00:00Z", hashOf(xml)))));
    }

    /** Jeden przebieg runnera z sinkiem na magazynie z kontekstu Spring (nie z fabryki testowej). */
    InvoiceSyncRunner.Status runPipeline() {
        var retrieval = new InvoiceRetrievalService(inboundClient(sdk), ReadRetryPolicy.noRetry(CLOCK));
        var documents = new CqnInboundDocumentRepository(persistence, CLOCK);
        var sink = new PersistentInboundInvoiceSink(SCOPE, token(), retrieval, documents, storage.store(),
                new SecureFa3InvoiceParser(), new Fa3SchemaValidator());
        return new InvoiceSyncRunner(retrieval, InvoiceSyncWindowPlanner.defaults(CLOCK),
                new CqnSyncCheckpointRepository(persistence), CLOCK)
                .runOnce(CONTEXT, KsefSubjectRole.BUYER, token(), START, sink).status();
    }

    @Test void syntheticXmlFlowsFromPipelineThroughStorageToOverviewAndByteExactDownload() throws Exception {
        assertEquals(InvoiceSyncRunner.Status.COMMITTED, runPipeline());

        // Overview: nabywca widzi dokument z xmlAvailable=true; brak referencji magazynu i hash w odpowiedzi.
        var overview = get("buyer-context", "test-buyer", "InboundInvoiceOverview");
        assertEquals(200, overview.statusCode(), () -> text(overview));
        JsonNode rows = json.readTree(overview.body()).path("value");
        assertEquals(1, rows.size());
        JsonNode row = rows.get(0);
        assertEquals(ksefNumber(1), row.path("ksefNumber").asText());
        assertEquals("STORED", row.path("ingestionStatus").asText());
        assertEquals("VERIFIED", row.path("integrityStatus").asText());
        assertEquals("PARSED", row.path("parseStatus").asText());
        assertTrue(row.path("xmlAvailable").asBoolean());
        assertFalse(text(overview).contains("ksefdoc"));
        String id = row.path("id").asText();

        // Download: dokładnie te bajty, które zwróciła atrapa KSeF, z nagłówkami attachment/no-store.
        var download = get("buyer-context", "test-buyer", "InboundInvoiceXml(" + id + ")/content");
        assertEquals(200, download.statusCode(), () -> text(download));
        assertArrayEquals(xml, download.body());
        assertTrue(download.headers().firstValue("Content-Type").orElse("").startsWith("application/xml"));
        assertTrue(download.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment"));
        assertEquals("no-store", download.headers().firstValue("Cache-Control").orElse(""));

        // Izolacja: obcy podatnik nie widzi wiersza i dostaje 404 nieodróżnialne od nieistniejącego dokumentu.
        var foreignList = get("other-taxpayer", "test-other", "InboundInvoiceOverview");
        assertEquals(200, foreignList.statusCode());
        assertEquals(0, json.readTree(foreignList.body()).path("value").size());
        assertEquals(404, get("other-taxpayer", "test-other", "InboundInvoiceXml(" + id + ")/content").statusCode());

        // Najgorszy przypadek restartu: utrata checkpointu i odtworzenie tego samego okna nowymi instancjami
        // sinka/repozytoriów. Deduplikacja: brak drugiego GET, brak duplikatu wiersza i BLOB, ten sam download.
        persistence.tx(() -> db.run(Delete.from(InboundPersistence.CHECKPOINTS)));
        assertEquals(InvoiceSyncRunner.Status.COMMITTED, runPipeline());
        verify(sdk, times(1)).getInvoice(anyString(), anyString());
        assertEquals(1, json.readTree(get("buyer-context", "test-buyer", "InboundInvoiceOverview").body())
                .path("value").size());
        assertArrayEquals(xml, get("buyer-context", "test-buyer", "InboundInvoiceXml(" + id + ")/content").body());
    }

    HttpResponse<byte[]> get(String user, String password, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/odata/v4/KsefService/" + path))
                .header("OData-MaxVersion", "4.0")
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)))
                .GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    static String text(HttpResponse<byte[]> response) { return new String(response.body(), StandardCharsets.UTF_8); }
}
