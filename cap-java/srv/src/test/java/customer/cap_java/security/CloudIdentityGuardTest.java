package customer.cap_java.security;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import customer.cap_java.Application;

/**
 * Fail closed na BTP: profil chmurowy bez bindingu XSUAA lub z mock auth nie może wystartować.
 * Testy uruchamiają pełną aplikację CAP (bez portu HTTP) i oczekują przerwania startu.
 */
class CloudIdentityGuardTest {

    private static final String[] LOCAL_DB = {
        "spring.main.web-application-type=none", "spring.sql.init.platform=h2",
        "cds.data-source.auto-config.enabled=false", "spring.datasource.url=jdbc:h2:mem:guard;DB_CLOSE_DELAY=-1",
        "ainv.sap-write.sweeper.enabled=false"
    };

    private static String startFailure(String profile, String... extra) {
        var builder = new SpringApplicationBuilder(Application.class).properties(LOCAL_DB);
        if (profile != null) {
            builder.profiles(profile);
        }
        // Argumenty wiersza poleceń mają pierwszeństwo przed application.yaml (jak nadpisanie na CF).
        String[] args = java.util.Arrays.stream(extra).map(value -> "--" + value).toArray(String[]::new);
        Throwable failure = assertThrows(Throwable.class, () -> {
            try (ConfigurableApplicationContext ignored = builder.run(args)) {
                fail("Application must not start");
            }
        });
        StringBuilder messages = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        return messages.toString();
    }

    @Test void cfProfileWithoutXsuaaBindingFailsClosed() {
        assertTrue(startFailure("cf").contains("requires exactly one XSUAA service binding (found 0)"));
    }

    @Test void productionProfileCannotReEnableMockUsers() {
        assertTrue(startFailure("production", "cds.security.mock.enabled=true")
            .contains("requires cds.security.mock.enabled=false"));
    }

    /** Na CF bez profilu cf CAP domyślnie włącza mock users — guard zatrzymuje start już na tym warunku. */
    @Test void vcapApplicationTriggersGuardEvenWithoutCloudProfile() {
        assertTrue(startFailure(null, "VCAP_APPLICATION={\"application_name\":\"ainvoicer-srv\"}")
            .contains("Cloud runtime requires cds.security.mock.enabled=false"));
        assertTrue(startFailure(null, "VCAP_APPLICATION={\"application_name\":\"ainvoicer-srv\"}",
            "cds.security.mock.enabled=false").contains("requires exactly one XSUAA service binding (found 0)"));
    }

    @Test void authenticationCannotBeSwitchedOffInCloud() {
        assertTrue(startFailure("cf", "cds.security.authentication.mode=never")
            .contains("requires CAP XSUAA authentication to stay enabled"));
    }
}
