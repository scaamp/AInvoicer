package customer.cap_java;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletContext;

/** Lokalny opt-in konsoli: rzeczywisty servlet i filtr, bez transportów SAP/KSeF. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.h2.console.enabled=true", "server.address=127.0.0.1",
    "spring.datasource.url=jdbc:h2:mem:h2-console-local;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles({"default", "test", "h2-console-local"})
class H2ConsoleLocalODataTest {
    @LocalServerPort int port;
    @Autowired ServletContext servletContext;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;

    @Test void loopbackGetsConsoleButNotAnonymousCapData() throws Exception {
        var http = HttpClient.newHttpClient();
        var console = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/h2-console/"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, console.statusCode());
        assertTrue(console.body().contains("H2 Console"));
        var cap = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
            + "/odata/v4/KsefService/InvoiceIntegrationStatus")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, cap.statusCode());
    }

    @Test void remoteAddressCannotSpoofLoopbackWithForwardedHeaders() throws Exception {
        var request = new MockHttpServletRequest(servletContext, "GET", "/h2-console/");
        request.setServletPath("/h2-console/");
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        var response = new MockHttpServletResponse();
        security.doFilter(request, response, (req, res) -> fail("Remote console request reached servlet"));
        assertEquals(403, response.getStatus());
    }
}
