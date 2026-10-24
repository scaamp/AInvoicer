package customer.cap_java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class KsefServiceODataIntegrationTest {

	private static final String NIP_A = "1234567802";
	private static final String NIP_B = "9876543210";
	private static final String COLLECTION = "/odata/v4/KsefService/KsefOperations";

	@LocalServerPort
	private int port;

	private final HttpClient httpClient = HttpClient.newHttpClient();

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void operatorCanCreateAndReadOnlyOwnTaxpayerOperations() throws Exception {
		ODataResponse created = create("operator-a", "test-a", operation(NIP_A, "SEND_INVOICE"));
		assertTrue(created.is2xxSuccessful(), () -> "CREATE failed: HTTP " + created.status() + " " + created.getBody());

		ODataResponse visible = request("operator-a", "test-a", HttpMethod.GET, COLLECTION, null);
		assertTrue(visible.is2xxSuccessful());
		JsonNode rows = visible.getBody().path("value");
		assertTrue(containsTaxpayer(rows, NIP_A));
		assertFalse(containsTaxpayer(rows, NIP_B));
	}

	@Test
	void operatorCannotCreateForAnotherTaxpayer() {
		ODataResponse response = create("operator-a", "test-a", operation(NIP_B, "SEND_INVOICE"));
		assertStatus(response, 400);
	}

	@Test
	void anonymousUserCannotAccessService() {
		ODataResponse response = request(null, null, HttpMethod.GET, COLLECTION, null);
		assertStatus(response, 401);
	}

	@Test
	void authenticatedUserWithoutKsefOperatorRoleCannotAccessService() {
		ODataResponse response = request(
			"user-without-ksef-role", "test-user", HttpMethod.GET, COLLECTION, null);
		assertStatus(response, 403);
	}

	@Test
	void operatorWithoutTaxpayerAttributeCannotCreateOrReadOperations() {
		ODataResponse create = create(
			"operator-without-taxpayer-attribute", "test-no-nip", operation(NIP_A, "SEND_INVOICE"));
		assertStatus(create, 400);

		ODataResponse read = request(
			"operator-without-taxpayer-attribute", "test-no-nip", HttpMethod.GET, COLLECTION, null);
		assertTrue(read.is2xxSuccessful());
		assertFalse(containsTaxpayer(read.getBody().path("value"), NIP_A));
		assertFalse(containsTaxpayer(read.getBody().path("value"), NIP_B));
	}

	@Test
	void invalidNipIsRejectedByCreateValidation() {
		ODataResponse response = create("operator-a", "test-a", operation("1234567890", "SEND_INVOICE"));
		assertStatus(response, 400);
	}

	@Test
	void unsupportedOperationTypeIsRejected() {
		ODataResponse response = create("operator-a", "test-a", operation(NIP_A, "DELETE_EVERYTHING"));
		assertStatus(response, 400);
	}

	@Test
	void missingOperationTypeIsRejected() {
		Map<String, Object> data = operation(NIP_A, "SEND_INVOICE");
		data.remove("operationType");
		assertStatus(create("operator-a", "test-a", data), 400);
	}

	@Test
	void blankIdempotencyKeyIsRejected() {
		Map<String, Object> data = operation(NIP_A, "SEND_INVOICE");
		data.put("idempotencyKey", "   ");
		assertStatus(create("operator-a", "test-a", data), 400);
	}

	@Test
	void unsupportedDirectionIsRejected() {
		Map<String, Object> data = operation(NIP_A, "SEND_INVOICE");
		data.put("direction", "SIDEWAYS");
		assertStatus(create("operator-a", "test-a", data), 400);
	}

	@Test
	void createOverwritesClientStatusAndTrimsIdempotencyKey() throws Exception {
		Map<String, Object> data = operation(NIP_A, "SEND_INVOICE");
		data.put("status", "ACCEPTED");
		data.put("idempotencyKey", "  " + data.get("idempotencyKey") + "  ");
		ODataResponse response = create("operator-a", "test-a", data);
		assertTrue(response.is2xxSuccessful(), () -> "CREATE failed: HTTP " + response.status() + " " + response.getBody());

		JsonNode entity = response.getBody();
		assertNotNull(entity);
		assertEquals("NEW", entity.path("status").asText());
		assertFalse(entity.path("idempotencyKey").asText().startsWith(" "));
	}

	@Test
	void updateAndDeleteAreNotGrantedByPublicService() throws Exception {
		ODataResponse created = create("operator-a", "test-a", operation(NIP_A, "SEND_INVOICE"));
		assertTrue(created.is2xxSuccessful());
		String id = created.getBody().path("ID").asText();
		assertFalse(id.isBlank(), "CREATE response must include the generated ID");

		String entityPath = COLLECTION + "(" + id + ")";
		ODataResponse update = request("operator-a", "test-a", HttpMethod.PATCH,
			entityPath, Map.of("sapDocumentRef", "changed-by-client"));
		assertStatus(update, 403);

		ODataResponse delete = request("operator-a", "test-a", HttpMethod.DELETE, entityPath, null);
		assertStatus(delete, 403);
	}

	@Test
	void duplicateTaxpayerAndIdempotencyKeyIsRejectedByDatabaseConstraint() {
		String idempotencyKey = "duplicate-" + UUID.randomUUID();
		Map<String, Object> first = operation(NIP_A, "SEND_INVOICE");
		first.put("idempotencyKey", idempotencyKey);
		assertTrue(create("operator-a", "test-a", first).is2xxSuccessful());

		Map<String, Object> duplicate = operation(NIP_A, "SEND_INVOICE");
		duplicate.put("idempotencyKey", idempotencyKey);
		assertStatus(create("operator-a", "test-a", duplicate), 409);
	}

	@Test
	void operatorBOperationsAreNotVisibleToOperatorA() {
		ODataResponse created = create("operator-b", "test-b", operation(NIP_B, "RECEIVE_INVOICE"));
		assertTrue(created.is2xxSuccessful());

		ODataResponse response = request("operator-a", "test-a", HttpMethod.GET, COLLECTION, null);
		assertTrue(response.is2xxSuccessful());
		assertFalse(containsTaxpayer(response.getBody().path("value"), NIP_B));
	}

	private ODataResponse create(String username, String password, Map<String, Object> data) {
		return request(username, password, HttpMethod.POST, COLLECTION, data);
	}

	private ODataResponse request(
		String username, String password, HttpMethod method, String path, Object body) {
		try {
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url(path)))
				.header("Accept", "application/json");
			if (username != null) {
				String credentials = username + ":" + password;
				builder.header("Authorization", "Basic " + Base64.getEncoder()
					.encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
			}
			String json = body == null ? "" : objectMapper.writeValueAsString(body);
			if (body != null) {
				builder.header("Content-Type", "application/json");
			}
			HttpRequest.BodyPublisher publisher = body == null
				? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(json);
			HttpResponse<String> response = httpClient.send(
				builder.method(method.name(), publisher).build(), HttpResponse.BodyHandlers.ofString());
			JsonNode responseBody = response.body().isBlank() ? null : objectMapper.readTree(response.body());
			return new ODataResponse(response.statusCode(), responseBody);
		} catch (Exception e) {
			throw new IllegalStateException("OData HTTP request failed", e);
		}
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}

	private Map<String, Object> operation(String nip, String operationType) {
		Map<String, Object> operation = new HashMap<>();
		operation.put("taxpayerNip", nip);
		operation.put("direction", "OUTBOUND");
		operation.put("operationType", operationType);
		operation.put("idempotencyKey", UUID.randomUUID().toString());
		return operation;
	}

	private boolean containsTaxpayer(JsonNode rows, String nip) {
		for (JsonNode row : rows) {
			if (nip.equals(row.path("taxpayerNip").asText())) {
				return true;
			}
		}
		return false;
	}

	private void assertStatus(ODataResponse response, int expected) {
		assertEquals(expected, response.status(),
			() -> "Expected HTTP " + expected + " but got " + response.status() + ": " + response.getBody());
	}

	private record ODataResponse(int status, JsonNode body) {
		private boolean is2xxSuccessful() {
			return status >= 200 && status < 300;
		}

		private JsonNode getBody() {
			return body;
		}
	}
}
