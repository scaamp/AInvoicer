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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cds.ql.Insert;
import com.sap.cds.services.persistence.PersistenceService;
import cds.gen.customer.cap_java.KsefOperations;
import cds.gen.customer.cap_java.KsefOperations_;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class KsefServiceODataIntegrationTest {

	private static final String NIP_A = "1234567802";
	private static final String NIP_B = "9876543210";
	private static final String COLLECTION = "/odata/v4/KsefService/KsefOperations";
	private static final String INVOICES = "/odata/v4/KsefService/KsefInvoices";

	@LocalServerPort
	private int port;

	private final HttpClient httpClient = HttpClient.newHttpClient();

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Autowired
	private PersistenceService persistence;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void authorizedOperatorReadsOnlyOwnInvoicesAndStorageReferencesStayPrivate() throws Exception {
		String invoiceA = persistInvoice(NIP_A, "INV-A-" + UUID.randomUUID());
		String invoiceB = persistInvoice(NIP_B, "INV-B-" + UUID.randomUUID());

		ODataResponse response = request("operator-a", "test-a", HttpMethod.GET, INVOICES, null);
		assertTrue(response.is2xxSuccessful(), () -> "READ failed: HTTP " + response.status());
		assertTrue(containsId(response.getBody().path("value"), invoiceA));
		assertFalse(containsId(response.getBody().path("value"), invoiceB));
		assertFalse(response.getBody().toString().contains("xmlStorageRef"));
		assertFalse(response.getBody().toString().contains("upoStorageRef"));
	}

	@Test
	void invoicesRequireOperatorRoleAndAuthentication() {
		assertStatus(request(null, null, HttpMethod.GET, INVOICES, null), 401);
		assertStatus(request("user-without-ksef-role", "test-user", HttpMethod.GET, INVOICES, null), 403);
	}

	@Test
	void metadataDoesNotExposeStorageReferencesOrInvoiceNavigation() throws Exception {
		String credentials = Base64.getEncoder().encodeToString("operator-a:test-a".getBytes(StandardCharsets.UTF_8));
		HttpRequest request = HttpRequest.newBuilder(URI.create(url("/odata/v4/KsefService/$metadata")))
			.header("Authorization", "Basic " + credentials)
			.header("Accept", "application/xml")
			.GET().build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("Name=\"KsefInvoices\""));
		assertTrue(response.body().contains("Name=\"businessStatus\""));
		assertFalse(response.body().contains("xmlStorageRef"));
		assertFalse(response.body().contains("upoStorageRef"));
		assertFalse(response.body().contains("NavigationProperty Name=\"invoice\""));
		assertFalse(response.body().contains("invoice_ID"));
		assertFalse(response.body().contains("SapResultWrites"));
		assertFalse(response.body().contains("SapResultWriteHistory"));
		assertFalse(response.body().contains("ApplyKsefResult"));
		// Migawka dokumentu, skrót potwierdzony przez KSeF i stany operacyjne writebacku pozostają wewnętrzne.
		assertFalse(response.body().contains("KsefInvoiceSnapshots"));
		assertFalse(response.body().contains("confirmedXmlSha256"));
		assertFalse(response.body().contains("snapshot_ID"));
		assertFalse(response.body().contains("claimToken"));
		assertFalse(response.body().contains("RETRY_EXHAUSTED"));
	}

	@Test
	void invoicesCannotBeCreatedUpdatedOrDeletedThroughPublicOData() throws Exception {
		String id = persistInvoice(NIP_A, "READONLY-" + UUID.randomUUID());
		Map<String, Object> invoice = new HashMap<>();
		invoice.put("taxpayerNip", NIP_A);
		invoice.put("direction", "OUTBOUND");
		invoice.put("invoiceNumber", "CLIENT-CREATE");
		invoice.put("businessStatus", "ACCEPTED");
		assertFalse(create("operator-a", "test-a", INVOICES, invoice).is2xxSuccessful());
		assertFalse(request("operator-a", "test-a", HttpMethod.PATCH,
			INVOICES + "(" + id + ")", Map.of("businessStatus", "ACCEPTED", "ksefNumber", "forged")).is2xxSuccessful());
		assertFalse(request("operator-a", "test-a", HttpMethod.DELETE, INVOICES + "(" + id + ")", null).is2xxSuccessful());
	}

	@Test
	void operationAssociationWorksInternallyButIsNotExposedForExpandOrClientBinding() throws Exception {
		String invoiceId = persistInvoice(NIP_A, "LINKED-" + UUID.randomUUID());
		String operationId = persistLinkedOperation(NIP_A, invoiceId);
		String storedInvoiceId = jdbcTemplate.queryForObject(
			"SELECT invoice_ID FROM customer_cap_java_KsefOperations WHERE ID = ?", String.class, operationId);
		String storedInvoiceNumber = jdbcTemplate.queryForObject(
			"SELECT invoiceNumber FROM customer_cap_java_KsefInvoices WHERE ID = ?", String.class, storedInvoiceId);
		assertEquals(invoiceId, storedInvoiceId);
		assertTrue(storedInvoiceNumber.startsWith("LINKED-"));

		ODataResponse expand = request("operator-a", "test-a", HttpMethod.GET,
			COLLECTION + "?$expand=invoice", null);
		assertFalse(expand.is2xxSuccessful(), "Hidden association must not be expandable over public OData");

		Map<String, Object> payload = operation(NIP_A, "CHECK_STATUS");
		payload.put("invoice_ID", invoiceId);
		assertFalse(create("operator-a", "test-a", COLLECTION, payload).is2xxSuccessful(),
			"Public client must not set the hidden invoice foreign key");
	}

	@Test
	void operatorCanCreateAndReadOnlyOwnTaxpayerOperations() throws Exception {
		ODataResponse created = create("operator-a", "test-a", operation(NIP_A, "CHECK_STATUS"));
		assertTrue(created.is2xxSuccessful(), () -> "CREATE failed: HTTP " + created.status() + " " + created.getBody());

		ODataResponse visible = request("operator-a", "test-a", HttpMethod.GET, COLLECTION, null);
		assertTrue(visible.is2xxSuccessful());
		JsonNode rows = visible.getBody().path("value");
		assertTrue(containsTaxpayer(rows, NIP_A));
		assertFalse(containsTaxpayer(rows, NIP_B));
	}

	@Test
	void operatorCannotCreateForAnotherTaxpayer() {
		ODataResponse response = create("operator-a", "test-a", operation(NIP_B, "CHECK_STATUS"));
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
			"operator-without-taxpayer-attribute", "test-no-nip", operation(NIP_A, "CHECK_STATUS"));
		assertStatus(create, 400);

		ODataResponse read = request(
			"operator-without-taxpayer-attribute", "test-no-nip", HttpMethod.GET, COLLECTION, null);
		assertTrue(read.is2xxSuccessful());
		assertFalse(containsTaxpayer(read.getBody().path("value"), NIP_A));
		assertFalse(containsTaxpayer(read.getBody().path("value"), NIP_B));
	}

	@Test
	void invalidNipIsRejectedByCreateValidation() {
		ODataResponse response = create("operator-a", "test-a", operation("1234567890", "CHECK_STATUS"));
		assertStatus(response, 400);
	}

	@Test
	void unsupportedOperationTypeIsRejected() {
		ODataResponse response = create("operator-a", "test-a", operation(NIP_A, "DELETE_EVERYTHING"));
		assertStatus(response, 400);
	}

	@Test
	void missingOperationTypeIsRejected() {
		Map<String, Object> data = operation(NIP_A, "CHECK_STATUS");
		data.remove("operationType");
		assertStatus(create("operator-a", "test-a", data), 400);
	}

	@Test
	void blankIdempotencyKeyIsRejected() {
		Map<String, Object> data = operation(NIP_A, "CHECK_STATUS");
		data.put("idempotencyKey", "   ");
		assertStatus(create("operator-a", "test-a", data), 400);
	}

	@Test
	void unsupportedDirectionIsRejected() {
		Map<String, Object> data = operation(NIP_A, "CHECK_STATUS");
		data.put("direction", "SIDEWAYS");
		assertStatus(create("operator-a", "test-a", data), 400);
	}

	@Test
	void createOverwritesClientStatusAndTrimsIdempotencyKey() throws Exception {
		Map<String, Object> data = operation(NIP_A, "CHECK_STATUS");
		data.put("status", "ACCEPTED");
		data.put("environment", "PROD");
		data.put("idempotencyKey", "  " + data.get("idempotencyKey") + "  ");
		ODataResponse response = create("operator-a", "test-a", data);
		assertTrue(response.is2xxSuccessful(), () -> "CREATE failed: HTTP " + response.status() + " " + response.getBody());

		JsonNode entity = response.getBody();
		assertNotNull(entity);
		assertEquals("NEW", entity.path("status").asText());
		assertEquals("TEST", entity.path("environment").asText());
		assertFalse(entity.path("idempotencyKey").asText().startsWith(" "));
	}

	@Test
	void updateAndDeleteAreNotGrantedByPublicService() throws Exception {
		ODataResponse created = create("operator-a", "test-a", operation(NIP_A, "CHECK_STATUS"));
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
		Map<String, Object> first = operation(NIP_A, "CHECK_STATUS");
		first.put("idempotencyKey", idempotencyKey);
		assertTrue(create("operator-a", "test-a", first).is2xxSuccessful());

		Map<String, Object> duplicate = operation(NIP_A, "CHECK_STATUS");
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

	private ODataResponse create(String username, String password, String path, Map<String, Object> data) {
		return request(username, password, HttpMethod.POST, path, data);
	}

	private String persistInvoice(String nip, String invoiceNumber) {
		String id = UUID.randomUUID().toString();
		Map<String, Object> invoice = new HashMap<>();
		invoice.put("ID", UUID.fromString(id));
		invoice.put("taxpayerNip", nip);
		invoice.put("companyCode", NIP_A.equals(nip) ? "1000" : "2000");
		invoice.put("direction", "OUTBOUND");
		invoice.put("sapDocumentRef", "SAP-" + invoiceNumber);
		invoice.put("invoiceNumber", invoiceNumber);
		invoice.put("issueDate", "2026-10-24");
		invoice.put("sellerNip", nip);
		invoice.put("buyerNip", "9876543210");
		invoice.put("xmlStorageRef", "private://xml/" + id);
		invoice.put("upoStorageRef", "private://upo/" + id);
		persistence.run(Insert.into("customer.cap_java.KsefInvoices").entry(invoice));
		return id;
	}

	private String persistLinkedOperation(String nip, String invoiceId) {
		String id = UUID.randomUUID().toString();
		KsefOperations operation = KsefOperations.create();
		operation.setId(id);
		operation.setTaxpayerNip(nip);
		operation.setCompanyCode(NIP_A.equals(nip) ? "1000" : "2000");
		operation.setDirection("OUTBOUND");
		operation.setOperationType("SEND_INVOICE");
		operation.setIdempotencyKey(UUID.randomUUID().toString());
		operation.setInvoice(Map.of("ID", invoiceId));
		persistence.run(Insert.into(KsefOperations_.class).entry(operation));
		return id;
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
		operation.put("companyCode", NIP_A.equals(nip) ? "1000" : "2000");
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

	private boolean containsId(JsonNode rows, String id) {
		for (JsonNode row : rows) {
			if (id.equalsIgnoreCase(row.path("ID").asText())) {
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
