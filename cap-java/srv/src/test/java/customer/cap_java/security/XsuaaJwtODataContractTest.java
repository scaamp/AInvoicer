package customer.cap_java.security;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
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
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cds.ql.Insert;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cloud.security.config.Service;
import com.sap.cloud.security.json.DefaultJsonObject;
import com.sap.cloud.security.test.JwtGenerator;
import com.sap.cloud.security.test.SecurityTest;

/**
 * OFFLINE JWT CONTRACT (CAP-07) — NIE jest to dowód LIVE XSUAA ani logowania SAP BTP.
 *
 * <p>Profil {@code cf} (mock auth OFF) + syntetyczny binding XSUAA z pliku default-env (CAP
 * {@code cds.environment.local.defaultEnvPath}). Tokeny podpisuje testowy klucz biblioteki
 * {@code java-security-test}; JWKS serwuje lokalny WireMock. Walidację wykonuje produkcyjny stos:
 * cds-feature-identity + resourceserver-security-spring-boot-starter. Jedyna różnica testowa to
 * zarejestrowana przez bibliotekę testową fabryka JKU zaufana wyłącznie dla localhost.
 *
 * <p>Baza H2 w profilu cf jest dopuszczona tylko w trybie {@code ainv.deployment.identity-smoke}; ten
 * sam test dowodzi więc, że w takim trybie uprawniony operator nie utworzy historii procesu KSeF.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    // Dokładnie konfiguracja smoke CF: bez własnego DataSource i wyłączenia auto-konfiguracji CAP.
    "spring.sql.init.platform=h2",
    "ainv.sap-write.sweeper.enabled=false",
    // CAP-07.1: na chmurze H2 jest dozwolona wyłącznie w jawnym trybie identity-smoke (CloudPersistenceGuard).
    "ainv.deployment.identity-smoke=true"
})
@ActiveProfiles("cf")
class XsuaaJwtODataContractTest {
    private static final String ENV_PATH_PROPERTY = "cds.environment.local.defaultEnvPath";
    private static final String NIP_A = "1234567802";
    private static final String NIP_B = "9876543210";
    private static SecurityTest security;
    private static Path defaultEnv;

    @LocalServerPort int port;
    @Autowired PersistenceService db;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private String invoice;
    private String foreignOperation;

    /** Przed startem kontekstu: lokalny JWKS i binding XSUAA widoczny dla CAP już w EnvironmentPostProcessor. */
    @BeforeAll static void startLocalIdentityHarness() throws Exception {
        security = new SecurityTest(Service.XSUAA);
        security.setup();
        String uaaUrl = security.getWireMockServer().baseUrl();
        defaultEnv = Files.createTempFile("ainv-cap07-default-env", ".json");
        Files.writeString(defaultEnv, """
            {"VCAP_SERVICES": {"xsuaa": [{"name": "ainvoicer-auth-offline-test", "label": "xsuaa",
              "plan": "application", "tags": ["xsuaa"],
              "credentials": {"clientid": "%s", "xsappname": "%s", "uaadomain": "localhost",
                "url": "%s", "identityzone": "offline-test", "tenantid": "offline-test-tenant"}}]}}
            """.formatted(SecurityTest.DEFAULT_CLIENT_ID, SecurityTest.DEFAULT_APP_ID, uaaUrl));
        System.setProperty(ENV_PATH_PROPERTY, defaultEnv.toString());
    }

    @AfterAll static void stopLocalIdentityHarness() throws Exception {
        System.clearProperty(ENV_PATH_PROPERTY);
        security.tearDown();
        Files.deleteIfExists(defaultEnv);
    }

    @BeforeEach void seed() {
        invoice = UUID.randomUUID().toString();
        operation(NIP_A, "1000");
        foreignOperation = operation(NIP_B, "2000");
        operation(NIP_A, "2000");
    }

    // ---- Uwierzytelnienie: 401 bez wiarygodnego tokenu -------------------------------------------

    @Test void missingMalformedOrMockCredentialsAreRejected() throws Exception {
        assertEquals(401, get(null, "InvoiceIntegrationStatus").statusCode());
        assertEquals(401, get(null, "$metadata").statusCode());
        assertEquals(401, get("Bearer not-a-jwt", "InvoiceIntegrationStatus").statusCode());
        assertEquals(401, get("Basic " + Base64.getEncoder().encodeToString("privileged:"
            .getBytes(StandardCharsets.UTF_8)), "$metadata").statusCode());
        // Lokalne konta mock nie istnieją na profilu cf (także te z application-test.yaml).
        assertEquals(401, get("Basic " + Base64.getEncoder().encodeToString("operator-a:test-a"
            .getBytes(StandardCharsets.UTF_8)), "InvoiceIntegrationStatus").statusCode());
    }

    /** CAP-07B LIVE: strona startowa CAP pod "/" była anonimowo dostępna na CF i wypisywała kontrakt usługi. */
    @Test void cloudProfileExposesNoAnonymousIndexPage() throws Exception {
        for (String path : new String[] {"/", "/$fiori-preview/", "/$fiori-preview/KsefService/KsefOperations",
                "/h2-console/", "/actuator/health", "/actuator/env", "/swagger-ui/", "/v3/api-docs"}) {
            var response = http.send(HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(Set.of(401, 403, 404).contains(response.statusCode()), path + ": " + response.statusCode());
            assertFalse(response.body().contains("KsefService"), path);
        }
    }

    @Test void expiredTokenIsRejected() throws Exception {
        String token = operator(NIP_A, "1000").withExpiration(Instant.now().minusSeconds(3600)).createToken().getTokenValue();
        assertEquals(401, get("Bearer " + token, "InvoiceIntegrationStatus").statusCode());
    }

    @Test void foreignSignatureIsRejected() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String token = operator(NIP_A, "1000").withPrivateKey(generator.generateKeyPair().getPrivate())
            .createToken().getTokenValue();
        assertEquals(401, get("Bearer " + token, "InvoiceIntegrationStatus").statusCode());
    }

    @Test void untrustedKeySetUrlIsRejected() throws Exception {
        // Produkcyjnie (java-security 4.1.2, XsuaaJwtSignatureValidator) nagłówek jku jest ignorowany: klucze
        // pochodzą z <uaadomain z bindingu>/token_keys?zid=<app_tid>. Tutaj odrzucenie wymusza testowa fabryka
        // JKU (zaufana tylko dla localhost) — test dowodzi, że obcy zestaw kluczy nie jest akceptowany.
        String token = operator(NIP_A, "1000").withHeaderParameter("jku", "https://keys.example.invalid/token_keys")
            .createToken().getTokenValue();
        assertEquals(401, get("Bearer " + token, "InvoiceIntegrationStatus").statusCode());
    }

    /**
     * Kontrakt SAP java-security 4.1.2 dla XSUAA (potwierdzony w źródle {@code JwtValidatorBuilder}:
     * {@code JwtIssuerValidator} jest dodawany wyłącznie dla IAS): claim {@code iss} NIE jest walidowany.
     * Łańcuch XSUAA = ważność czasowa + podpis kluczem z JWKS {@code uaadomain} bindingu (kid, zid z tokenu)
     * + audience (clientid/xsappname bindingu). Bez klucza prywatnego strefy XSUAA nie da się podmienić iss;
     * audience blokuje tokeny innych aplikacji. Test utrwala to zachowanie — zmiana biblioteki wymaga
     * ponownej oceny (ADR-0006, CAP-07.1).
     */
    @Test void xsuaaTrustAnchorIsBoundKeySetNotIssuerClaim() throws Exception {
        String foreignIss = "https://foreign-issuer.example.invalid/oauth/token";
        String signedByBoundKey = operator(NIP_A, "1000").withClaimValue("iss", foreignIss).createToken().getTokenValue();
        assertEquals(200, get("Bearer " + signedByBoundKey, "InvoiceIntegrationStatus").statusCode());
        String foreignKeySet = operator(NIP_A, "1000").withClaimValue("iss", foreignIss)
            .withHeaderParameter("jku", "https://foreign-issuer.example.invalid/token_keys").createToken().getTokenValue();
        assertEquals(401, get("Bearer " + foreignKeySet, "InvoiceIntegrationStatus").statusCode());
    }

    @Test void tokenForAnotherApplicationIsRejected() throws Exception {
        String token = security.getPreconfiguredJwtGenerator()
            .withClaimValue("client_id", "sb-foreign-app!t9")
            .withClaimValues("aud", "sb-foreign-app!t9")
            .withClaimValue("azp", "sb-foreign-app!t9")
            .withScopes("foreign-app!t9.KsefOperator")
            .withClaimValue("xs.user.attributes", attributes(new String[]{NIP_A}, new String[]{"1000"}))
            .createToken().getTokenValue();
        assertEquals(401, get("Bearer " + token, "InvoiceIntegrationStatus").statusCode());
    }

    // ---- Autoryzacja: rola z tokenu i zakres z xs.user.attributes ----------------------------------

    @Test void authenticatedUserWithoutOperatorScopeIsForbidden() throws Exception {
        String token = security.getPreconfiguredJwtGenerator().withClaimValue("user_name", "jwt-no-role")
            .withClaimValue("xs.user.attributes", attributes(new String[]{NIP_A}, new String[]{"1000"}))
            .createToken().getTokenValue();
        assertEquals(403, get("Bearer " + token, "InvoiceIntegrationStatus").statusCode());
        var send = http.send(HttpRequest.newBuilder(uri("requestSend"))
            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"invoiceUUID\":\"" + invoice + "\"}")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(403, send.statusCode());
    }

    @Test void operatorReadsOnlyOwnPairAndCannotProbeForeignKey() throws Exception {
        String token = operator(NIP_A, "1000").createToken().getTokenValue();
        var body = read(token, "InvoiceIntegrationStatus?$count=true&$filter=invoiceUUID%20eq%20'" + invoice + "'");
        assertEquals(Set.of(NIP_A + "/1000"), pairs(body));
        assertEquals(1, body.path("@odata.count").asInt());
        assertEquals(404, get("Bearer " + token, "InvoiceIntegrationStatus(" + foreignOperation + ")").statusCode());
        assertEquals(200, get("Bearer " + token, "$metadata").statusCode());
    }

    @Test void operatorWithoutScopeAttributesSeesNothing() throws Exception {
        for (var token : new String[]{
                operatorBase().createToken().getTokenValue(),
                operatorBase().withClaimValue("xs.user.attributes", attributes(new String[]{NIP_A}, new String[0]))
                    .createToken().getTokenValue(),
                operatorBase().withClaimValue("xs.user.attributes", attributes(new String[0], new String[]{"1000"}))
                    .createToken().getTokenValue()}) {
            var body = read(token, "InvoiceIntegrationStatus?$count=true&$filter=invoiceUUID%20eq%20'" + invoice + "'");
            assertEquals(0, body.path("value").size(), body::toString);
            assertEquals(0, body.path("@odata.count").asInt());
        }
    }

    @Test void foreignNipOrCompanyAndCrossPairsSeeNothing() throws Exception {
        var foreignNip = read(operator("5260002008", "1000").createToken().getTokenValue(),
            "KsefOperations?$filter=sapDocumentRef%20eq%20'" + invoice + "'");
        assertEquals(0, foreignNip.path("value").size());
        var foreignCompany = read(operator(NIP_A, "3000").createToken().getTokenValue(),
            "KsefOperations?$filter=sapDocumentRef%20eq%20'" + invoice + "'");
        assertEquals(0, foreignCompany.path("value").size());
        // Dwie role (A/1000, B/2000) scalone przez XSUAA w listy: fail closed, nawet bez własnych par.
        String crossed = operatorBase().withClaimValue("xs.user.attributes",
            attributes(new String[]{NIP_A, NIP_B}, new String[]{"1000", "2000"})).createToken().getTokenValue();
        var cross = read(crossed, "InvoiceIntegrationStatus?$count=true&$filter=invoiceUUID%20eq%20'" + invoice + "'");
        assertEquals(0, cross.path("value").size());
        assertEquals(0, cross.path("@odata.count").asInt());
    }

    // ---- CAP-07.1: tryb identity-smoke nie zapisuje historii (bezpośredni request do CAP) ----------

    @Test void identitySmokeRejectsSendAndOperationWritesForAuthorizedOperator() throws Exception {
        String token = operator(NIP_A, "1000").createToken().getTokenValue();
        var send = post(token, "requestSend", "{\"invoiceUUID\":\"" + invoice + "\"}");
        assertEquals(409, send.statusCode(), send::body);
        assertTrue(send.body().contains("AINV_IDENTITY_SMOKE_READ_ONLY"), send::body);
        var create = post(token, "KsefOperations", """
            {"sapDocumentRef":"%s","taxpayerNip":"%s","companyCode":"1000","direction":"OUTBOUND",
             "operationType":"CHECK_STATUS","idempotencyKey":"%s"}""".formatted(invoice, NIP_A, UUID.randomUUID()));
        assertEquals(409, create.statusCode(), create::body);
        // Bez tokenu nadal odmowa (Spring Security: 401 albo 403 dla POST bez CSRF/tokenu) — tryb nie osłabia
        // uwierzytelnienia i nie dochodzi do handlera.
        int anonymous = http.send(HttpRequest.newBuilder(uri("requestSend")).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
        assertTrue(anonymous == 401 || anonymous == 403, "anonymous POST: " + anonymous);
        var own = read(token, "KsefOperations?$filter=sapDocumentRef%20eq%20'" + invoice + "'");
        assertEquals(1, own.path("value").size(), "brak nowych operacji poza danymi testu");
    }

    private HttpResponse<String> post(String token, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json").header("OData-MaxVersion", "4.0")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    // ---- Pomocnicze -----------------------------------------------------------------------------

    private JwtGenerator operatorBase() {
        return security.getPreconfiguredJwtGenerator().withClaimValue("user_name", "jwt-operator")
            .withLocalScopes("KsefOperator");
    }

    private JwtGenerator operator(String nip, String company) {
        return operatorBase().withClaimValue("xs.user.attributes", attributes(new String[]{nip}, new String[]{company}));
    }

    private static DefaultJsonObject attributes(String[] nips, String[] companies) {
        try {
            return new DefaultJsonObject(new ObjectMapper().writeValueAsString(
                Map.of("taxpayerNips", nips, "companyCodes", companies)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String operation(String nip, String company) {
        String id = UUID.randomUUID().toString();
        db.run(Insert.into("customer.cap_java.KsefOperations").entry(Map.of(
            "ID", id, "sapDocumentRef", invoice, "taxpayerNip", nip, "companyCode", company,
            "direction", "OUTBOUND", "environment", "TEST", "operationType", "CHECK_STATUS",
            "status", "NEW", "idempotencyKey", UUID.randomUUID().toString())));
        return id;
    }

    private Set<String> pairs(JsonNode body) {
        Set<String> result = new HashSet<>();
        body.path("value").forEach(row -> result.add(row.path("taxpayerNip").asText() + "/" + row.path("companyCode").asText()));
        return result;
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + "/odata/v4/KsefService/" + path);
    }

    private HttpResponse<String> get(String authorization, String path) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).header("OData-MaxVersion", "4.0")
            .header("Accept", path.equals("$metadata") ? "application/xml" : "application/json");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode read(String token, String path) throws Exception {
        var response = get("Bearer " + token, path);
        assertEquals(200, response.statusCode(), response::body);
        return json.readTree(response.body());
    }
}
