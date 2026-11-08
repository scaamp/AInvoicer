package customer.cap_java;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import jakarta.servlet.ServletContext;

/** Profil bez użytkowników i bez default: globalne ustawienia muszą odmawiać dostępu. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.sql.init.platform=h2", "cds.data-source.auto-config.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:no-users;DB_CLOSE_DELAY=-1",
    "ainv.sap-write.sweeper.enabled=false"
})
@ActiveProfiles("audit-no-users")
class UnconfiguredAuthenticationODataTest {
    @LocalServerPort int port;
    @Autowired Environment environment;
    @Autowired ServletContext servletContext;

    @Test void noDefaultAccountOrAnonymousCanRead() throws Exception {
        var http = HttpClient.newHttpClient();
        for (String user : new String[]{"", "privileged", "system", "authenticated", "operator-a"}) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/odata/v4/KsefService/InvoiceIntegrationStatus"));
            if (!user.isEmpty()) { request.header("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString((user + ":").getBytes(StandardCharsets.UTF_8))); }
            assertEquals(401, http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode(), user);
        }
    }

    @Test void consoleIsOffAndNoPermissiveChainIsRegistered() throws Exception {
        assertEquals("false", environment.getProperty("spring.h2.console.enabled"));
        assertFalse(servletContext.getServletRegistrations().values().stream()
            .anyMatch(registration -> registration.getClassName().contains("h2.server.web")));
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/h2-console/"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() == 401 || response.statusCode() == 404, "No public console and no 500");
    }
}
