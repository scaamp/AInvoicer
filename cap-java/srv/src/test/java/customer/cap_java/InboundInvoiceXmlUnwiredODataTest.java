package customer.cap_java;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * KSEF-IN-05: flaga pobrania włączona, ale magazyn INBOUND niepodłączony — kontrolowane 503 bez szczegółów
 * (brak NPE, brak fallbacku na {@code LocalTestDocumentStore}/dysk). H2 offline.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:ksef-in-05-unwired;DB_CLOSE_DELAY=-1",
        "ainv.ksef.inbound.xml-download.enabled=true" })
@ActiveProfiles("test")
class InboundInvoiceXmlUnwiredODataTest {
    @LocalServerPort int port;
    final HttpClient http = HttpClient.newHttpClient();

    @Test void downloadWithoutInboundStoreIsControlledServiceUnavailable() throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/odata/v4/KsefService/InboundInvoiceXml(20000000-0000-4000-8000-000000000001)/content"))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("operator-a:test-a".getBytes(StandardCharsets.UTF_8)))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(503, response.statusCode(), response::body);
        assertTrue(response.body().contains("INBOUND_XML_UNAVAILABLE"), response::body);
        for (String unsafe : new String[]{"NullPointer", "java.lang.", "stackTrace", "ksefdoc", "jdbc:"}) {
            assertFalse(response.body().contains(unsafe), unsafe);
        }
    }
}
