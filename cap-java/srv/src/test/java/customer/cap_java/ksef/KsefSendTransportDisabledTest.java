package customer.cap_java.ksef;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cds.ql.Select;
import com.sap.cds.services.persistence.PersistenceService;

import customer.cap_java.sap.SapInvoiceRemoteAdapter;

/**
 * CAP-07F (E-3): przy wyłączonym transporcie KSeF {@code requestSend} odmawia PRZED odczytem SAP.
 *
 * <p>Wcześniej kolejność była: UUID → SAP READ (communication user) → 404/403 → guard transportu (409), więc
 * uprawniony operator mógł przy KSeF OFF wywołać techniczny odczyt H01 i odróżnić brakującą fakturę od obcej.
 * Test sprawdza faktyczną liczbę wywołań adaptera RAP (atrapa), identyczną odpowiedź dla własnej, obcej
 * i brakującej faktury oraz brak zapisów (operacje, rezerwacje, migawki, zlecenia, outbox). Bez SAP H01 i KSeF.
 * NIP jest skonfigurowany celowo: o odmowie decyduje sama flaga {@code ainv.ksef.enabled=false}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ainv.ksef.enabled=false", "ainv.ksef.nip=1234567802",
        "cds.outbox.services.KsefSubmissionOutbox.startCollector=false",
        "cds.outbox.services.SapResultOutbox.startCollector=false",
        "cds.outbox.persistent.scheduler.allTenantsTask.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ksef-send-disabled;DB_CLOSE_DELAY=-1" })
@ActiveProfiles("test")
class KsefSendTransportDisabledTest {
    private static final String SERVICE = "/odata/v4/KsefService/";
    private static final String[] WRITE_TARGETS = { KsefSubmissionWorker.OPERATIONS, KsefSendRequestHandler.RESERVATIONS,
            KsefSubmissionWorker.SNAPSHOTS, KsefSubmissionWorker.SUBMISSIONS, "cds.outbox.Messages" };

    @LocalServerPort int port;
    @Autowired PersistenceService db;
    @MockitoBean SapInvoiceRemoteAdapter sap;
    @MockitoBean KsefAuthentication authentication;
    @MockitoBean KsefOnlineSession session;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private UUID existing;

    @BeforeEach void setup() {
        existing = UUID.randomUUID();
        // Gdyby handler odczytał SAP, dostałby poprawną fakturę NIP 1234567802/1000 (zakres operator-a).
        when(sap.readInvoice(existing)).thenReturn(Optional.of(SapInvoiceTestData.outbound(existing)));
        when(sap.readInvoice(argThat(id -> !existing.equals(id)))).thenReturn(Optional.empty());
    }

    @Test void ownForeignAndMissingInvoicesGetIdenticalRefusalWithoutSapReadOrWrites() throws Exception {
        Map<String, Long> before = rows();

        Response own = send("operator-a", "test-a", existing.toString());
        Response foreignNip = send("operator-b", "test-b", existing.toString());
        Response foreignCompany = send("operator-other-company", "test-cc", existing.toString());
        Response missing = send("operator-a", "test-a", UUID.randomUUID().toString());

        assertEquals(409, own.status(), own::toString);
        assertEquals("KSEF_CONTEXT_NOT_CONFIGURED", code(own));
        for (Response other : new Response[] { foreignNip, foreignCompany, missing }) {
            assertEquals(own, other, "Odpowiedź nie może rozróżniać własnej, obcej i brakującej faktury");
        }
        assertFalse(own.raw().matches("(?s).*(java\\.lang\\.|stackTrace|https?://|1234567802|QUEUED).*"), own::raw);

        verifyNoInteractions(sap, authentication, session);
        assertEquals(before, rows());
    }

    @Test void malformedUuidIsRejectedByInputValidationWithoutSapReadOrWrites() throws Exception {
        Map<String, Long> before = rows();
        for (String invalid : new String[] { "not-a-uuid", "", "00000000-0000-0000-0000-00000000000Z" }) {
            Response response = send("operator-a", "test-a", invalid);
            assertEquals(400, response.status(), invalid + " → " + response);
            assertFalse(response.raw().matches("(?s).*(java\\.lang\\.|stackTrace|https?://).*"), response::raw);
        }
        verifyNoInteractions(sap);
        assertEquals(before, rows());
    }

    @Test void authenticationAndRoleAreStillCheckedFirst() throws Exception {
        Map<String, Long> before = rows();
        assertEquals(401, send(null, null, existing.toString()).status());
        assertEquals(403, send("user-without-ksef-role", "test-user", existing.toString()).status());
        verifyNoInteractions(sap);
        assertEquals(before, rows());
    }

    // ---- pomocnicze --------------------------------------------------------------------------------

    private Map<String, Long> rows() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String entity : WRITE_TARGETS) {
            counts.put(entity, db.run(Select.from(entity)).rowCount());
        }
        return counts;
    }

    private Response send(String user, String password, String invoiceUuid) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + SERVICE + "requestSend"))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("invoiceUUID", invoiceUuid))));
        if (user != null) {
            builder.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
        }
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode body = response.body() == null || response.body().isBlank() ? null : json.readTree(response.body());
        return new Response(response.statusCode(), body, response.body() == null ? "" : response.body());
    }

    private static String code(Response response) {
        JsonNode error = response.body() == null ? null : response.body().get("error");
        return error == null ? null : error.path("message").asText();
    }

    /** Porównanie status + JSON (bez surowego tekstu, który nie wpływa na semantykę). */
    record Response(int status, JsonNode body, String raw) {
        @Override public boolean equals(Object other) {
            return other instanceof Response that && status == that.status && java.util.Objects.equals(body, that.body);
        }

        @Override public int hashCode() {
            return java.util.Objects.hash(status, body);
        }
    }
}
