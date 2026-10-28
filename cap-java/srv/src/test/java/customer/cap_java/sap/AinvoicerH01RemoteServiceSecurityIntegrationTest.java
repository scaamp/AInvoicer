package customer.cap_java.sap;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

/** Zapobiega udostępnieniu zewnętrznego modelu SAP jako publicznego API CAP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AinvoicerH01RemoteServiceSecurityIntegrationTest {

	@LocalServerPort
	private int port;

	@Value("${server.servlet.context-path:}")
	private String contextPath;

	@Test
	void remoteContractEndpointRequiresInternalUser() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + contextPath
			+ "/odata/v4/" + SapInvoiceRemoteAdapter.MODEL + "/$metadata"))
			.header("Accept", "application/xml")
			.GET().build();
		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(request, HttpResponse.BodyHandlers.ofString());

		assertTrue(response.statusCode() == HttpStatus.UNAUTHORIZED.value()
			|| response.statusCode() == HttpStatus.FORBIDDEN.value(),
			() -> "External SAP contract must not be publicly accessible; got HTTP "
				+ response.statusCode());
	}

	@Test
	void importedModelRetainsInternalUserAndBusinessUserCannotAccessIt() throws Exception {
		try (var csn = getClass().getResourceAsStream("/edmx/csn.json")) {
			var service = new ObjectMapper().readTree(csn).path("definitions").path(SapInvoiceRemoteAdapter.MODEL);
			assertEquals("internal-user", service.path("@requires").asText());
		}
		String credentials = Base64.getEncoder().encodeToString("operator-a:test-a".getBytes(StandardCharsets.UTF_8));
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + contextPath
			+ "/odata/v4/" + SapInvoiceRemoteAdapter.MODEL + "/$metadata"))
			.header("Authorization", "Basic " + credentials).GET().build();
		int status = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
		assertTrue(status == 403 || status == 404, "Business user must not access the external SAP contract");
	}
}
