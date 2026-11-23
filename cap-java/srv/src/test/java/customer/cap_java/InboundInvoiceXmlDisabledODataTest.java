package customer.cap_java;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import customer.cap_java.ksef.storage.InboundDocumentStorage;

/**
 * KSEF-IN-05: konfiguracja domyślna — magazyn INBOUND HANA i pobranie XML są wyłączone.
 * Encja jest w {@code $metadata} (kontrakt dla UI), ale każdy odczyt kończy się kontrolowanym 404 (H2 offline).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InboundInvoiceXmlDisabledODataTest {
    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    final HttpClient http = HttpClient.newHttpClient();

    @Test void defaultConfigurationRegistersNoInboundStoreAndRefusesDownload() throws Exception {
        assertTrue(context.getBeansOfType(InboundDocumentStorage.class).isEmpty());
        var response = get("operator-a", "test-a", "InboundInvoiceXml(" + UUID.randomUUID() + ")/content");
        assertEquals(404, response.statusCode(), response::body);
        assertTrue(response.body().contains("INBOUND_XML_NOT_FOUND"), response::body);
        assertEquals(401, get(null, null, "InboundInvoiceXml(" + UUID.randomUUID() + ")/content").statusCode());
        assertEquals(403, get("user-without-ksef-role", "test-user", "InboundInvoiceXml(" + UUID.randomUUID() + ")/content").statusCode());
        assertTrue(get("operator-a", "test-a", "$metadata").body().contains("InboundInvoiceXml"));
    }

    HttpResponse<String> get(String user, String password, String path) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/odata/v4/KsefService/" + path));
        if (user != null) {
            builder.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
        }
        return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
