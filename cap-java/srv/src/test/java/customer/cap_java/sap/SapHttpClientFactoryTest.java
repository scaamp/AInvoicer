package customer.cap_java.sap;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;

import org.apache.http.client.config.RequestConfig;
import org.junit.jupiter.api.Test;

import com.sap.cloud.sdk.cloudplatform.connectivity.DefaultHttpDestination;

class SapHttpClientFactoryTest {
    @Test void configuresEachTimeoutOnlyForH01() throws Exception {
        var factory = new SapHttpClientFactory(Duration.ofSeconds(5), Duration.ofSeconds(20), Duration.ofSeconds(5));
        var h01 = DefaultHttpDestination.builder("http://127.0.0.1").name("AINV_H01").build();
        RequestConfig config = factory.getRequestConfigBuilder(h01).build();
        assertEquals(5000, config.getConnectTimeout());
        assertEquals(20000, config.getSocketTimeout());
        assertEquals(5000, config.getConnectionRequestTimeout());
        assertEquals(20000, factory.getSocketConfigBuilder(h01).build().getSoTimeout());
        var other = DefaultHttpDestination.builder("http://127.0.0.1").name("OTHER").build();
        assertEquals(120000, factory.getRequestConfigBuilder(other).build().getSocketTimeout());
        // SDK opakowuje klienta; HttpClientContext pokazuje konfigurację faktycznie wykonanego requestu.
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { exchange.sendResponseHeaders(204, -1); exchange.close(); });
        server.start();
        var local = DefaultHttpDestination.builder("http://127.0.0.1:" + server.getAddress().getPort())
                .name("AINV_H01").build();
        try (var client = (org.apache.http.impl.client.CloseableHttpClient) factory.createHttpClient(local)) {
            var context = org.apache.http.client.protocol.HttpClientContext.create();
            try (var response = client.execute(new org.apache.http.client.methods.HttpGet(local.getUri()), context)) {
                assertEquals(204, response.getStatusLine().getStatusCode());
            }
            var actual = context.getRequestConfig();
            assertEquals(5000, actual.getConnectTimeout());
            assertEquals(20000, actual.getSocketTimeout());
            assertEquals(5000, actual.getConnectionRequestTimeout());
        } finally { server.stop(0); }
    }

    @Test void rejectsInfiniteAndOverflowingTimeouts() {
        assertThrows(IllegalArgumentException.class, () -> new SapHttpClientFactory(
                Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(ArithmeticException.class, () -> new SapHttpClientFactory(
                Duration.ofDays(100), Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }
}
