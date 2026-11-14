package customer.cap_java;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Regresja HTTP OData na H2: media, właściciel, walidacja i rollback po odrzuconym PUT. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BlobServiceODataTest {
    @LocalServerPort int port;
    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();
    final byte[] xml = "<invoice><number>TEST-001</number></invoice>".getBytes(StandardCharsets.UTF_8);

    HttpResponse<byte[]> request(String user, String method, String path, String mime, byte[] body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/odata/v4/blob/" + path));
        if (user != null) {
            String password = user.equals("operator-b") ? "test-b" : "test-a";
            b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
        }
        if (mime != null) b.header("Content-Type", mime);
        return http.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    String create(String name, String mime) throws Exception {
        var r = request("operator-a", "POST", "Documents", "application/json", json.writeValueAsBytes(Map.of("fileName", name, "mimeType", mime)));
        assertEquals(201, r.statusCode(), new String(r.body(), StandardCharsets.UTF_8));
        return "Documents(" + json.readTree(r.body()).get("ID").asText() + ")";
    }
    @Test void xmlRoundTripHashHeadersAndOwnerIsolation() throws Exception {
        String path = create("TEST-001.xml", "application/xml");
        assertEquals(204, request("operator-a", "PUT", path + "/content", "application/xml", xml).statusCode());
        var download = request("operator-a", "GET", path + "/content", null, null);
        assertEquals(200, download.statusCode());
        assertArrayEquals(xml, download.body());
        assertTrue(download.headers().firstValue("content-disposition").orElse("").contains("TEST-001.xml"));
        assertTrue(download.headers().firstValue("content-type").orElse("").contains("application/xml"));
        var metadata = json.readTree(request("operator-a", "GET", path, null, null).body());
        assertEquals("operator-a", metadata.get("createdBy").asText());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(xml)), metadata.get("sha256").asText());
        assertEquals(404, request("operator-b", "GET", path, null, null).statusCode());
        assertTrue(request("operator-b", "PUT", path + "/content", "application/xml", xml).statusCode() >= 400);
        assertEquals(401, request(null, "GET", path + "/content", null, null).statusCode());
        assertEquals(401, request(null, "GET", "$metadata", null, null).statusCode());
    }
    @Test void rejectedUpdatesPreserveFileAndHash() throws Exception {
        String path = create("safe.xml", "application/xml");
        assertEquals(204, request("operator-a", "PUT", path + "/content", "application/xml", xml).statusCode());
        for (byte[] bad : List.of(new byte[0], new byte[1024 * 1024 + 1], "<broken>".getBytes(StandardCharsets.UTF_8),
                "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///forbidden'>]><x>&e;</x>".getBytes(StandardCharsets.UTF_8))) {
            assertEquals(400, request("operator-a", "PUT", path + "/content", "application/xml", bad).statusCode());
        }
        assertEquals(400, request("operator-a", "PATCH", path, "application/json", "{\"fileName\":\"renamed.xml\"}".getBytes(StandardCharsets.UTF_8)).statusCode());
        assertTrue(request("operator-a", "PUT", path + "/content", "text/plain", xml).statusCode() >= 400);
        assertArrayEquals(xml, request("operator-a", "GET", path + "/content", null, null).body());
    }
    @Test void invalidMetadataAndPdfSignature() throws Exception {
        for (String name : List.of("", "../test.xml", "test\r\n.xml", "bad.txt")) {
            assertEquals(400, request("operator-a", "POST", "Documents", "application/json", json.writeValueAsBytes(Map.of("fileName", name, "mimeType", "application/xml"))).statusCode());
        }
        String path = create("test.pdf", "application/pdf");
        assertEquals(400, request("operator-a", "PUT", path + "/content", "application/pdf", xml).statusCode());
        assertEquals(204, request("operator-a", "PUT", path + "/content", "application/pdf", "%PDF-1.4\nsynthetic lab fixture\n%%EOF".getBytes(StandardCharsets.US_ASCII)).statusCode());
    }
}
