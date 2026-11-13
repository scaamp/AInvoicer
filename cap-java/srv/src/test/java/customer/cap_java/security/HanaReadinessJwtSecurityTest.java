package customer.cap_java.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.services.outbox.OutboxService;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.runtime.CdsRuntime;
import com.sap.cloud.security.config.Service;
import com.sap.cloud.security.json.DefaultJsonObject;
import com.sap.cloud.security.test.JwtGenerator;
import com.sap.cloud.security.test.SecurityTest;

import customer.cap_java.sap.SapInvoiceRemoteAdapter;

/**
 * CAP-07E — OFFLINE kontrakt JWT dla zestawu flag faktycznie wdrożonego wariantu HANA readiness
 * (MTA 0.7.3: {@code identity-smoke=false}, {@code hana-readiness=true}, KSeF/INBOUND/WRITE/sweeper OFF,
 * outbox OFF, storage off). {@link XsuaaJwtODataContractTest} działa wyłącznie w trybie identity-smoke,
 * w którym {@link IdentitySmokeReadOnlyHandler} odrzuca zapisy — LIVE tego handlera nie ma.
 *
 * <p>Ograniczenia dowodu: baza to lokalna H2, nie HANA; profil {@code cf} nie jest aktywny, bo
 * {@link CloudPersistenceGuard} poprawnie odrzuca H2 bez trybu smoke. Ustawienia bezpieczeństwa profilu
 * {@code cf} (mock OFF, index/Fiori preview OFF) są podane jawnie. Walidację tokenu wykonuje produkcyjny
 * stos SAP (cds-feature-identity + java-security) z dekoratorem {@link DedicatedTenantPolicy}; podpis
 * pochodzi z testowego klucza i lokalnego JWKS. Nie jest to dowód LIVE XSUAA, ról ani principal H01.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "cds.security.mock.enabled=false",
    "cds.index-page.enabled=false",
    "cds.odata-v4.fiori-preview.enabled=false",
    "ainv.deployment.hana-readiness=true",
    "ainv.deployment.identity-smoke=false",
    "ainv.ksef.enabled=false",
    "ainv.ksef.inbound.enabled=false",
    "ainv.sap-write.enabled=false",
    "ainv.sap-write.sweeper.enabled=false",
    "ainv.ksef.storage.provider=off",
    "cds.outbox.persistent.enabled=false",
    "cds.outbox.inMemory.enabled=false"
})
class HanaReadinessJwtSecurityTest {
    private static final String ENV_PATH_PROPERTY = "cds.environment.local.defaultEnvPath";
    private static final String TENANT = "offline-hana-tenant";
    private static final String NIP_A = "1234567802";
    private static final String NIP_B = "9876543210";
    private static SecurityTest security;
    private static Path defaultEnv;

    @LocalServerPort int port;
    @Autowired PersistenceService db;
    @Autowired ApplicationContext context;
    @Autowired CdsRuntime runtime;
    /** CAP-07F: atrapa adaptera RAP — test liczy wywołania SAP i nigdy nie łączy się z H01. */
    @MockitoBean SapInvoiceRemoteAdapter sap;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private String invoice;
    private String ownOperation;
    private String foreignOperation;

    @BeforeAll static void startLocalIdentityHarness() throws Exception {
        security = new SecurityTest(Service.XSUAA);
        security.setup();
        defaultEnv = Files.createTempFile("ainv-cap07e-default-env", ".json");
        Files.writeString(defaultEnv, """
            {"VCAP_SERVICES": {"xsuaa": [{"name": "ainvoicer-auth-offline-test", "label": "xsuaa",
              "plan": "application", "tags": ["xsuaa"],
              "credentials": {"clientid": "%s", "xsappname": "%s", "uaadomain": "localhost",
                "url": "%s", "identityzone": "offline-hana", "tenantid": "%s", "tenantmode": "dedicated"}}]}}
            """.formatted(SecurityTest.DEFAULT_CLIENT_ID, SecurityTest.DEFAULT_APP_ID,
                security.getWireMockServer().baseUrl(), TENANT));
        System.setProperty(ENV_PATH_PROPERTY, defaultEnv.toString());
    }

    @AfterAll static void stopLocalIdentityHarness() throws Exception {
        System.clearProperty(ENV_PATH_PROPERTY);
        security.tearDown();
        Files.deleteIfExists(defaultEnv);
    }

    @BeforeEach void seed() {
        InboundFixtures.seed(db);
        invoice = UUID.randomUUID().toString();
        ownOperation = operation(NIP_A, "1000");
        foreignOperation = operation(NIP_B, "2000");
        operation(NIP_A, "2000");
        for (var row : List.of(Map.of("ID", invoice, "taxpayerNip", NIP_A, "companyCode", "1000", "direction", "OUTBOUND"),
                Map.of("ID", foreignOperation, "taxpayerNip", NIP_B, "companyCode", "2000", "direction", "OUTBOUND"))) {
            db.run(Insert.into("customer.cap_java.KsefInvoices").entry(row));
        }
    }

    /** Ten sam skład co LIVE: brak handlera smoke, brak outbox i aktywny guard readiness. */
    @Test void runtimeMatchesDeployedHanaReadinessComposition() {
        assertTrue(context.getBeansOfType(IdentitySmokeReadOnlyHandler.class).isEmpty(),
            "LIVE HANA readiness nie ma handlera identity-smoke; test musi to odzwierciedlać");
        assertNotNull(context.getBean(CloudHanaReadinessGuard.class));
        assertEquals(0, runtime.getServiceCatalog().getServices(OutboxService.class).count());
    }

    /** C07 po INT-03: obcy tenant przy zaufanym podpisie i własnym audience — 401 przed odczytem i zapisem. */
    @Test void foreignTenantIsRejectedOnEveryReadAndWriteSurface() throws Exception {
        String token = operator(NIP_A, "1000").withClaimValue("zid", "foreign-tenant")
            .withClaimValue("app_tid", "foreign-tenant").createToken().getTokenValue();
        long before = rows("customer.cap_java.KsefOperations");
        for (String path : new String[]{"", "$metadata", "KsefOperations", "KsefOperations/$count", "KsefInvoices?$count=true",
                "InvoiceIntegrationStatus?$filter=" + enc("taxpayerNip eq '" + NIP_B + "' or taxpayerNip eq '" + NIP_A + "'"),
                "InboundInvoiceOverview", "KsefOperations(" + ownOperation + ")"}) {
            assertEquals(401, get(token, path).statusCode(), path);
        }
        for (String path : new String[]{"requestSend", "KsefOperations", "$batch"}) {
            assertEquals(401, post(token, path, createBody(NIP_A)).statusCode(), path);
        }
        assertEquals(before, rows("customer.cap_java.KsefOperations"));
    }

    @Test void authenticatedUserWithoutOperatorRoleCannotReadOrWrite() throws Exception {
        String token = trustedTenant().withClaimValue("user_name", "no-role")
            .withClaimValue("xs.user.attributes", attributes(new String[]{NIP_A}, new String[]{"1000"}))
            .createToken().getTokenValue();
        for (String path : new String[]{"KsefOperations", "KsefInvoices", "InvoiceIntegrationStatus/$count", "InboundInvoiceOverview"}) {
            assertEquals(403, get(token, path).statusCode(), path);
        }
        long before = rows("customer.cap_java.KsefOperations");
        assertEquals(403, post(token, "KsefOperations", createBody(NIP_A)).statusCode());
        assertEquals(403, post(token, "requestSend", "{\"invoiceUUID\":\"" + invoice + "\"}").statusCode());
        assertEquals(before, rows("customer.cap_java.KsefOperations"));
    }

    /** Lista, count, klucz, OR i $expand nie rozszerzają zakresu NIP/CompanyCode operatora. */
    @Test void operatorScopeHoldsForListCountKeyOrFilterAndExpand() throws Exception {
        String token = operator(NIP_A, "1000").createToken().getTokenValue();
        var widened = read(token, "KsefOperations?$count=true&$filter="
            + enc("taxpayerNip eq '" + NIP_B + "' or companyCode eq '2000' or sapDocumentRef eq '" + invoice + "'"));
        assertEquals(Set.of(NIP_A + "/1000"), pairs(widened));
        assertEquals(1, widened.path("@odata.count").asInt());
        assertEquals("0", get(token, "InvoiceIntegrationStatus/$count?$filter=" + enc("operationId eq " + foreignOperation)).body());
        assertEquals(404, get(token, "KsefOperations(" + foreignOperation + ")").statusCode());
        assertEquals(404, get(token, "InvoiceIntegrationStatus(" + foreignOperation + ")").statusCode());
        assertEquals(404, get(token, "KsefInvoices(" + foreignOperation + ")").statusCode());
        assertEquals(200, get(token, "KsefOperations(" + ownOperation + ")").statusCode());
        assertEquals(400, get(token, "KsefOperations?$expand=invoice").statusCode());
        for (String entity : new String[]{"KsefSubmissions", "SapResultWrites", "KsefSendReservations", "KsefInvoiceSnapshots",
                "KsefInboundDocuments", "KsefInboundAccessGrants"}) {
            assertEquals(404, get(token, entity).statusCode(), entity);
        }
    }

    /**
     * KSeF OFF: requestSend nie może utworzyć rezerwacji, operacji ani zlecenia — także bez handlera smoke.
     * CAP-07F (E-3): odmowa 409 następuje przed odczytem SAP (0 wywołań adaptera RAP), więc kod błędu
     * dowodzi dojścia do guardu transportu, a nie awarii Destination.
     */
    @Test void requestSendWithKsefDisabledLeavesNoReservationOrOperation() throws Exception {
        String token = operator(NIP_A, "1000").createToken().getTokenValue();
        long reservations = rows("customer.cap_java.KsefSendReservations");
        long operations = rows("customer.cap_java.KsefOperations");
        var response = post(token, "requestSend", "{\"invoiceUUID\":\"" + invoice + "\"}");
        assertEquals(409, response.statusCode(), response::body);
        assertTrue(response.body().contains("KSEF_CONTEXT_NOT_CONFIGURED"), response::body);
        assertFalse(response.body().contains("QUEUED"));
        assertFalse(response.body().matches("(?s).*(java\\.lang\\.|stackTrace|https?://).*"), "bezpieczny błąd bez URL/stosu");
        verifyNoInteractions(sap);
        assertEquals(reservations, rows("customer.cap_java.KsefSendReservations"));
        assertEquals(operations, rows("customer.cap_java.KsefOperations"));
    }

    /**
     * CAP-07F (E-2) — zamyka CAP-07E-R1 (wcześniej charakteryzowane jako znane ryzyko: 201 dla własnego
     * CHECK_STATUS). W składzie HANA readiness projekcja KsefOperations jest {@code @readonly} z jedynym grantem
     * READ: własny i obcy zakres, każdy typ, sfałszowany status i CREATE w $batch → 403; anonim → 401.
     * Żaden wiersz nie powstaje, a odczyt własnej operacji działa dalej.
     */
    @Test void rawOperationCreateIsRejectedForEveryScopeTypeAndBatch() throws Exception {
        String token = operator(NIP_A, "1000").createToken().getTokenValue();
        long before = rows("customer.cap_java.KsefOperations");
        for (String type : new String[]{"CHECK_STATUS", "RECEIVE_INVOICE", "SEND_INVOICE"}) {
            for (String nip : new String[]{NIP_A, NIP_B}) {
                var response = post(token, "KsefOperations", createBody(nip).replace("CHECK_STATUS", type));
                assertEquals(403, response.statusCode(), nip + "/" + type);
                assertFalse(response.body().matches("(?s).*(java\\.lang\\.|stackTrace|https?://).*"), response::body);
            }
        }
        var forged = post(token, "KsefOperations", createBody(NIP_A).replace("}", ",\"status\":\"ACCEPTED\",\"environment\":\"PROD\"}"));
        assertEquals(403, forged.statusCode(), forged::body);

        var batch = batchCreate(token, createBody(NIP_A));
        assertEquals(200, batch.statusCode(), batch::body);
        assertTrue(batch.body().contains("403 Forbidden"), batch::body);
        assertFalse(batch.body().contains("201 Created"), batch::body);

        var anonymous = http.send(HttpRequest.newBuilder(uri("KsefOperations")).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(createBody(NIP_A))).build(), HttpResponse.BodyHandlers.ofString());
        // Bez tokenu Spring Security odrzuca POST przed CAP (401 albo 403 zależnie od kolejności filtrów/CSRF —
        // istniejące zachowanie, jak w XsuaaJwtODataContractTest); nigdy nie dochodzi do zapisu.
        assertTrue(anonymous.statusCode() == 401 || anonymous.statusCode() == 403, "anonymous POST: " + anonymous.statusCode());

        assertEquals(before, rows("customer.cap_java.KsefOperations"));
        assertEquals(200, get(token, "KsefOperations(" + ownOperation + ")").statusCode());
        assertEquals("1", get(token, "KsefOperations/$count?$filter=" + enc("sapDocumentRef eq '" + invoice + "'")).body());
    }

    // ---- Pomocnicze --------------------------------------------------------------------------------

    private JwtGenerator trustedTenant() {
        return security.getPreconfiguredJwtGenerator().withClaimValue("zid", TENANT).withClaimValue("app_tid", TENANT);
    }

    private JwtGenerator operator(String nip, String company) {
        return trustedTenant().withClaimValue("user_name", "jwt-operator").withLocalScopes("KsefOperator")
            .withClaimValue("xs.user.attributes", attributes(new String[]{nip}, new String[]{company}));
    }

    private static DefaultJsonObject attributes(String[] nips, String[] companies) {
        try {
            return new DefaultJsonObject(new ObjectMapper().writeValueAsString(Map.of("taxpayerNips", nips, "companyCodes", companies)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String createBody(String nip) {
        return """
            {"sapDocumentRef":"%s","taxpayerNip":"%s","companyCode":"1000","direction":"OUTBOUND",\
            "operationType":"CHECK_STATUS","idempotencyKey":"%s"}""".formatted(invoice, nip, UUID.randomUUID());
    }

    private String operation(String nip, String company) {
        String id = UUID.randomUUID().toString();
        db.run(Insert.into("customer.cap_java.KsefOperations").entry(Map.of(
            "ID", id, "sapDocumentRef", invoice == null ? "" : invoice, "taxpayerNip", nip, "companyCode", company,
            "direction", "OUTBOUND", "environment", "TEST", "operationType", "CHECK_STATUS",
            "status", "NEW", "idempotencyKey", UUID.randomUUID().toString())));
        return id;
    }

    private long rows(String entity) {
        return db.run(Select.from(entity)).rowCount();
    }

    private Set<String> pairs(JsonNode body) {
        Set<String> result = new HashSet<>();
        body.path("value").forEach(r -> result.add(r.path("taxpayerNip").asText() + "/" + r.path("companyCode").asText()));
        return result;
    }

    private static String enc(String text) {
        return java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + "/odata/v4/KsefService/" + path);
    }

    private HttpResponse<String> get(String token, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token)
            .header("OData-MaxVersion", "4.0").header("Accept", path.equals("$metadata") ? "application/xml" : "application/json")
            .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String token, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json").header("OData-MaxVersion", "4.0")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** CREATE KsefOperations wewnątrz changesetu $batch (OData V4 multipart). */
    private HttpResponse<String> batchCreate(String token, String entity) throws Exception {
        String batch = "batch_cap07f";
        String changeset = "changeset_cap07f";
        String body = "--" + batch + "\r\nContent-Type: multipart/mixed;boundary=" + changeset + "\r\n\r\n"
            + "--" + changeset + "\r\nContent-Type: application/http\r\nContent-Transfer-Encoding: binary\r\nContent-ID: 1\r\n\r\n"
            + "POST KsefOperations HTTP/1.1\r\nContent-Type: application/json\r\nAccept: application/json\r\n\r\n"
            + entity + "\r\n--" + changeset + "--\r\n--" + batch + "--\r\n";
        return http.send(HttpRequest.newBuilder(uri("$batch")).header("Authorization", "Bearer " + token)
            .header("Content-Type", "multipart/mixed;boundary=" + batch).header("OData-MaxVersion", "4.0")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode read(String token, String path) throws Exception {
        var response = get(token, path);
        assertEquals(200, response.statusCode(), response::body);
        return json.readTree(response.body());
    }
}
