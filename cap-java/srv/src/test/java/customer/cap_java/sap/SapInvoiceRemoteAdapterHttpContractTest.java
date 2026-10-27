package customer.cap_java.sap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.sap.cloud.sdk.cloudplatform.connectivity.DefaultDestinationLoader;
import com.sap.cloud.sdk.cloudplatform.connectivity.DefaultHttpDestination;
import com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Test lokalny (NIE LIVE): prawdziwy CAP RemoteService + SAP Cloud SDK Destination, ale zamiast SAP
 * działa lokalny serwer HTTP. Sprawdza składanie URL i nagłówek Basic Authentication.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SapInvoiceRemoteAdapterHttpContractTest {

	private static final String SERVICE_PATH = "/sap/opu/odata4/sap/zapi_ainv_inv_h_o4/srvd_a2x/sap/zapi_ainv_inv_h_o4/0001/";
	private static final String USER = "fake-user";
	private static final String PASSWORD = "fake-password";

	private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
	private static final List<String> AUTH_HEADERS = new CopyOnWriteArrayList<>();
	private static HttpServer server;

	@Autowired
	private SapInvoiceRemoteAdapter adapter;

	@BeforeAll
	static void startFakeSap() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", SapInvoiceRemoteAdapterHttpContractTest::handle);
		server.start();
		String url = "http://localhost:" + server.getAddress().getPort();
		DestinationAccessor.prependDestinationLoader(new DefaultDestinationLoader().registerDestination(
			DefaultHttpDestination.builder(url).name("AINV_H01").basicCredentials(USER, PASSWORD).build()));
	}

	@AfterAll
	static void stopFakeSap() {
		server.stop(0);
	}

	@Test
	void buildsServiceUrlAndSendsBasicAuthentication() {
		List<SapInvoiceSnapshot> invoices = adapter.readInvoices();
		System.out.println("FAKE-SAP requests: " + REQUESTS);

		assertEquals(1, invoices.size());
		assertEquals("56b09c12-4ee5-1fd1-b4ac-56570f4d14c8", invoices.get(0).header().get("InvoiceUUID").toString());
		assertEquals(1, invoices.get(0).items().size());
		assertEquals(1, invoices.get(0).references().size());

		assertTrue(REQUESTS.size() >= 3, () -> "unexpected requests: " + REQUESTS);
		assertTrue(REQUESTS.get(0).startsWith("GET " + SERVICE_PATH + "ZC_AINV_INV_H"), () -> REQUESTS.toString());
		assertTrue(REQUESTS.stream().anyMatch(r -> r.startsWith("GET " + SERVICE_PATH + "ZC_AINV_INV_I")));
		assertTrue(REQUESTS.stream().anyMatch(r -> r.startsWith("GET " + SERVICE_PATH + "ZC_AINV_INV_REF")));
		String expected = "Basic " + Base64.getEncoder()
			.encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
		assertTrue(AUTH_HEADERS.stream().allMatch(expected::equals), "every request must carry Basic auth");
	}

	private static void handle(HttpExchange exchange) throws IOException {
		REQUESTS.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
		AUTH_HEADERS.add(exchange.getRequestHeaders().getFirst("Authorization"));
		String path = exchange.getRequestURI().getPath();
		String uuid = "56b09c12-4ee5-1fd1-b4ac-56570f4d14c8";
		String body;
		if (path.endsWith("ZC_AINV_INV_H")) {
			body = "{\"value\":[{\"InvoiceUUID\":\"" + uuid + "\",\"InvoiceNumber\":\"X\"}]}";
		} else if (path.endsWith("ZC_AINV_INV_I")) {
			body = "{\"value\":[{\"ItemUUID\":\"56b09c12-4ee5-1fd1-b4ac-56570f4d94c8\",\"InvoiceUUID\":\"" + uuid + "\"}]}";
		} else if (path.endsWith("ZC_AINV_INV_REF")) {
			body = "{\"value\":[{\"RefUUID\":\"56b09c12-4ee5-1fd1-b4ac-56570f4eb4c8\",\"InvoiceUUID\":\"" + uuid + "\"}]}";
		} else {
			exchange.sendResponseHeaders(404, -1);
			exchange.close();
			return;
		}
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json;odata.metadata=minimal");
		exchange.sendResponseHeaders(200, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
