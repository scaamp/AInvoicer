package customer.cap_java.ksef;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.persistence.PersistenceService;

import customer.cap_java.sap.SapInvoiceRemoteAdapter;
import customer.cap_java.sap.SapKsefResultWriteAdapter;

/** Polityka z rzeczywistego application.yaml: bez override retry; transporty SAP/KSeF są mockami. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ainv.ksef.enabled=true", "ainv.ksef.nip=1234567802",
        "ainv.ksef.party-data.sellers.1234567802.country-code=PL",
        "ainv.ksef.party-data.sellers.1234567802.line1=ul. Testowa 1",
        "ainv.ksef.party-data.sellers.1234567802.line2=00-001 Warszawa",
        "ainv.ksef.party-data.buyers.9876543210.jst-subordinate-unit=false",
        "ainv.ksef.party-data.buyers.9876543210.vat-group-member=false",
        "cds.outbox.services.KsefSubmissionOutbox.startCollector=false",
        "cds.outbox.services.SapResultOutbox.startCollector=false",
        "cds.outbox.persistent.scheduler.allTenantsTask.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ksef-default-policy;DB_CLOSE_DELAY=-1" })
@ActiveProfiles("test")
class KsefSendDefaultPolicyTest {
    @LocalServerPort int port;
    @Autowired Environment environment;
    @Autowired PersistenceService db;
    @Autowired KsefSubmissionWorker worker;
    @MockitoBean SapInvoiceRemoteAdapter sap;
    @MockitoBean KsefAuthentication authentication;
    @MockitoBean KsefOnlineSession session;
    @MockitoBean KsefDocumentStore store;
    @MockitoBean SapKsefResultWriteAdapter sapWrite;

    @Test void defaultConfigurationBlocksResendAfterConfirmedRejection() throws Exception {
        assertFalse(environment.getRequiredProperty("ainv.ksef.send.retry-after-rejection", Boolean.class));
        UUID invoice = UUID.randomUUID();
        when(sap.readInvoice(invoice)).thenReturn(Optional.of(SapInvoiceTestData.outbound(invoice)));
        when(store.put(any())).thenReturn("document.xml");
        var first = send(invoice);
        assertEquals(200, first.statusCode(), first.body());
        String operation = new ObjectMapper().readTree(first.body()).get("operationId").asText();
        worker.tx(() -> {
            db.run(Update.entity(KsefSubmissionWorker.OPERATIONS).byId(operation).data("status", "REJECTED"));
            db.run(Update.entity(KsefSubmissionWorker.SUBMISSIONS).byId(operation).data("state", "REJECTED"));
            return null;
        });

        var retry = send(invoice);
        assertEquals(409, retry.statusCode(), retry.body());
        assertTrue(retry.body().contains("KSEF_RESEND_NOT_ALLOWED"), retry.body());
        assertEquals(1L, worker.tx(() -> db.run(Select.from(KsefSubmissionWorker.OPERATIONS)
                .where(o -> o.get("sapDocumentRef").eq(invoice.toString()))).rowCount()));
        verifyNoInteractions(authentication, session, sapWrite);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 11, Integer.MAX_VALUE})
    void invalidAttemptBudgetFailsBeforeServingRequests(int maxAttempts) {
        assertThrows(IllegalArgumentException.class, () ->
                new KsefSendRequestHandler(null, null, null, null, null, null, true, maxAttempts));
    }

    private HttpResponse<String> send(UUID invoice) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/odata/v4/KsefService/requestSend"))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        "operator-a:test-a".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(
                        Map.of("invoiceUUID", invoice.toString())))).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
