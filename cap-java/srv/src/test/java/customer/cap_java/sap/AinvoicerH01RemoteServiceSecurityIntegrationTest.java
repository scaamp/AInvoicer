package customer.cap_java.sap;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

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
			+ "/odata/v4/AinvoicerH01/$metadata"))
			.header("Accept", "application/xml")
			.GET().build();
		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(request, HttpResponse.BodyHandlers.ofString());

		assertTrue(response.statusCode() == HttpStatus.UNAUTHORIZED.value()
			|| response.statusCode() == HttpStatus.FORBIDDEN.value(),
			() -> "External SAP contract must not be publicly accessible; got HTTP "
				+ response.statusCode());
	}
}
