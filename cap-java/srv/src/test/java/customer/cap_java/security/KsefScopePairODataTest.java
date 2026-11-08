package customer.cap_java.security;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cds.ql.Insert;
import com.sap.cds.services.persistence.PersistenceService;

/**
 * LOCAL MOCK: macierz par NIP/CompanyCode przez pełny stos CAP OData + @restrict + UserInfoProvider.
 * Seed: A/1000, B/2000 (pary przyznane rolom) oraz A/2000 (para krzyżowa, nieprzyznana).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.h2.console.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:ksef-scope-pairs;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles({"default", "test"})
class KsefScopePairODataTest {
    private static final String NIP_A = "1234567802";
    private static final String NIP_B = "9876543210";
    @LocalServerPort int port;
    @Autowired PersistenceService db;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private String invoice;

    @BeforeEach void seed() {
        invoice = UUID.randomUUID().toString();
        operation(NIP_A, "1000");
        operation(NIP_B, "2000");
        operation(NIP_A, "2000");
        for (var pair : new String[][]{{NIP_A, "1000"}, {NIP_B, "2000"}, {NIP_A, "2000"}}) {
            db.run(Insert.into("customer.cap_java.KsefInvoices").entry(Map.of("ID", UUID.randomUUID().toString(),
                "taxpayerNip", pair[0], "companyCode", pair[1], "direction", "OUTBOUND", "sapDocumentRef", invoice)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"InvoiceIntegrationStatus?$filter=invoiceUUID%20eq%20'{invoice}'&$count=true",
        "KsefOperations?$filter=sapDocumentRef%20eq%20'{invoice}'&$count=true",
        "KsefInvoices?$filter=sapDocumentRef%20eq%20'{invoice}'&$count=true"})
    void crossPairUserSeesNothingNotEvenOwnPairs(String query) throws Exception {
        JsonNode body = read("operator-cross-scope", "test-cross", query.replace("{invoice}", invoice));
        assertEquals(0, body.path("value").size(), body::toString);
        assertEquals(0, body.path("@odata.count").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"InvoiceIntegrationStatus?$filter=invoiceUUID%20eq%20'{invoice}'&$count=true",
        "KsefOperations?$filter=sapDocumentRef%20eq%20'{invoice}'&$count=true",
        "KsefInvoices?$filter=sapDocumentRef%20eq%20'{invoice}'&$count=true"})
    void singleNipWithTwoCompaniesSeesExactlyGrantedPairs(String query) throws Exception {
        JsonNode body = read("operator-one-nip-two-companies", "test-one-nip", query.replace("{invoice}", invoice));
        assertEquals(Set.of(NIP_A + "/1000", NIP_A + "/2000"), pairs(body));
        assertEquals(2, body.path("@odata.count").asInt());
    }

    @Test void singlePairUserNeverSeesCrossPair() throws Exception {
        JsonNode body = read("operator-a", "test-a",
            "InvoiceIntegrationStatus?$filter=invoiceUUID%20eq%20'" + invoice + "'&$count=true");
        assertEquals(Set.of(NIP_A + "/1000"), pairs(body));
    }

    private Set<String> pairs(JsonNode body) {
        Set<String> result = new HashSet<>();
        body.path("value").forEach(row -> result.add(row.path("taxpayerNip").asText() + "/" + row.path("companyCode").asText()));
        return result;
    }

    private void operation(String nip, String company) {
        db.run(Insert.into("customer.cap_java.KsefOperations").entry(Map.of(
            "ID", UUID.randomUUID().toString(), "sapDocumentRef", invoice, "taxpayerNip", nip, "companyCode", company,
            "direction", "OUTBOUND", "environment", "TEST", "operationType", "CHECK_STATUS",
            "status", "NEW", "idempotencyKey", UUID.randomUUID().toString())));
    }

    private JsonNode read(String user, String password, String path) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/odata/v4/KsefService/" + path))
            .header("OData-MaxVersion", "4.0").header("Accept", "application/json")
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + password)
                .getBytes(StandardCharsets.UTF_8))).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response::body);
        return json.readTree(response.body());
    }
}
