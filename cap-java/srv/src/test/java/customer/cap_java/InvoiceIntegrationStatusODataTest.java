package customer.cap_java;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cds.ql.Insert;
import com.sap.cds.services.persistence.PersistenceService;

/** HTTP READ na H2 i lokalnych mock users; bez requestSend, transportu SAP i KSeF. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.h2.console.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:integration-status-read;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles({"default", "test"})
class InvoiceIntegrationStatusODataTest {
    private static final String SERVICE = "/odata/v4/KsefService/";
    @LocalServerPort int port;
    @Autowired PersistenceService db;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private String invoice;
    private String ownOperation;
    private String foreignOperation;

    /** Trzy zakresy mają ten sam UUID dokumentu: sam filtr UUID nie może omijać autoryzacji. */
    @BeforeEach void seedReadOnlyHistory() {
        invoice = UUID.randomUUID().toString();
        ownOperation = insert("1234567802", "1000");
        foreignOperation = insert("9876543210", "2000");
        insert("1234567802", "2000");
    }

    @ParameterizedTest
    @CsvSource({"InvoiceIntegrationStatus,invoiceUUID", "KsefOperations,sapDocumentRef"})
    void scopedReadAndCountNeverIncludeForeignRows(String entity, String ref) throws Exception {
        var response = read("operator-a", "test-a", entity + "?$count=true&$filter=" + ref
            + "%20eq%20'" + invoice + "'");
        assertEquals(200, response.statusCode());
        assertEquals("4.0", response.headers().firstValue("OData-Version").orElseThrow());
        var body = json.readTree(response.body());
        assertEquals(1, body.path("@odata.count").asInt(), body::toString);
        assertEquals(1, body.path("value").size());
        var row = body.path("value").get(0);
        assertEquals("1234567802", row.path("taxpayerNip").asText());
        assertEquals("1000", row.path("companyCode").asText());
        assertEquals(ownOperation, row.path(entity.equals("KsefOperations") ? "ID" : "operationId").asText());
    }

    @ParameterizedTest
    @CsvSource({"operator-without-taxpayer-attribute,test-no-nip",
        "operator-without-company-attribute,test-no-cc", "operator-malformed-scope,test-malformed"})
    void missingOrMalformedScopeReturnsNoStatus(String user, String password) throws Exception {
        var response = read(user, password, "InvoiceIntegrationStatus?$count=true&$filter=invoiceUUID%20eq%20'" + invoice + "'");
        assertEquals(200, response.statusCode());
        var body = json.readTree(response.body());
        assertEquals(0, body.path("value").size());
        assertEquals(0, body.path("@odata.count").asInt());
    }

    @Test void statusRequiresAuthenticationAndOperatorRole() throws Exception {
        assertEquals(401, read(null, null, "InvoiceIntegrationStatus").statusCode());
        assertEquals(403, read("user-without-ksef-role", "test-user", "InvoiceIntegrationStatus").statusCode());
    }

    @Test void foreignKeyAndUnknownInvoiceDoNotLeakStatus() throws Exception {
        assertEquals(404, read("operator-a", "test-a", "InvoiceIntegrationStatus(" + foreignOperation + ")").statusCode());
        var response = read("operator-a", "test-a", "InvoiceIntegrationStatus?$count=true&$filter=invoiceUUID%20eq%20'"
            + UUID.randomUUID() + "'");
        assertEquals(200, response.statusCode());
        assertEquals(0, json.readTree(response.body()).path("value").size());
    }

    @Test void metadataWorksWithH2ConsoleDisabledAndKeepsInternalDataPrivate() throws Exception {
        var response = read("operator-a", "test-a", "$metadata");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("InvoiceIntegrationStatus"));
        for (String hidden : new String[]{"SapResultWrites", "upoStorageRef", "xmlStorageRef", "ApplyKsefResult"}) {
            assertFalse(response.body().contains(hidden), hidden);
        }
    }

    private String insert(String nip, String company) {
        String id = UUID.randomUUID().toString();
        db.run(Insert.into("customer.cap_java.KsefOperations").entry(Map.of(
            "ID", id, "sapDocumentRef", invoice, "taxpayerNip", nip, "companyCode", company,
            "direction", "OUTBOUND", "environment", "TEST", "operationType", "CHECK_STATUS",
            "status", "NEW", "idempotencyKey", UUID.randomUUID().toString())));
        return id;
    }

    private HttpResponse<String> read(String user, String password, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + SERVICE + path))
            .header("OData-MaxVersion", "4.0")
            .header("Accept", path.equals("$metadata") ? "application/xml" : "application/json");
        if (user != null) {
            request.header("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
        }
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
