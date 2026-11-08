package customer.cap_java.security;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import customer.cap_java.Application;

/**
 * CAP-07.1: na chmurze bez trwałej bazy (HANA) aplikacja nie startuje; H2 tylko w jawnym trybie
 * identity-smoke z wyłączonym KSeF, writebackiem i sweeperem. Tożsamość jest spełniona syntetycznym
 * bindingiem XSUAA (bez sieci — klucze nie są pobierane przy starcie), więc test izoluje regułę bazy.
 * To NIE jest test HANA Cloud: ścieżka HDB jest sprawdzona wyłącznie regułą {@link CloudPersistenceGuard#verify}.
 */
class CloudPersistenceGuardTest {

    private static final String ENV_PATH_PROPERTY = "cds.environment.local.defaultEnvPath";
    private static Path defaultEnv;

    private static final String[] LOCAL_DB = {
        "spring.main.web-application-type=none", "spring.sql.init.platform=h2",
        "cds.data-source.auto-config.enabled=false", "spring.datasource.url=jdbc:h2:mem:persistence-guard;DB_CLOSE_DELAY=-1",
        "ainv.sap-write.sweeper.enabled=false"
    };

    @BeforeAll static void syntheticXsuaaBinding() throws Exception {
        defaultEnv = Files.createTempFile("ainv-cap071-default-env", ".json");
        Files.writeString(defaultEnv, """
            {"VCAP_SERVICES": {"xsuaa": [{"name": "ainvoicer-auth-offline-test", "label": "xsuaa",
              "plan": "application", "tags": ["xsuaa"],
              "credentials": {"clientid": "sb-offline!t1", "xsappname": "offline!t1", "uaadomain": "localhost",
                "url": "http://localhost:1", "identityzone": "offline-test", "tenantid": "offline-test-tenant"}}]}}
            """);
        System.setProperty(ENV_PATH_PROPERTY, defaultEnv.toString());
    }

    @AfterAll static void clearBinding() throws Exception {
        System.clearProperty(ENV_PATH_PROPERTY);
        Files.deleteIfExists(defaultEnv);
    }

    private static ConfigurableApplicationContext start(String... extra) {
        // Argumenty mają pierwszeństwo przed application.yaml (np. sweeper.enabled=true) — jak env na CF.
        var merged = new java.util.LinkedHashMap<String, String>();
        java.util.stream.Stream.concat(java.util.Arrays.stream(LOCAL_DB), java.util.Arrays.stream(extra))
            .forEach(value -> merged.put(value.substring(0, value.indexOf('=')), value));
        String[] args = merged.values().stream().map(value -> "--" + value).toArray(String[]::new);
        return new SpringApplicationBuilder(Application.class).profiles("cf").run(args);
    }

    private static String startFailure(String... extra) {
        Throwable failure = assertThrows(Throwable.class, () -> {
            try (ConfigurableApplicationContext ignored = start(extra)) {
                fail("Application must not start");
            }
        });
        StringBuilder messages = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        return messages.toString();
    }

    @Test void cloudRuntimeWithEmbeddedH2FailsClosed() {
        String failure = startFailure();
        assertTrue(failure.contains("requires durable SAP HANA Cloud persistence (found database 'H2')"), failure);
        assertFalse(failure.contains("jdbc:"), "bez URL źródła danych w komunikacie");
    }

    @Test void identitySmokeCannotEnableKsefWritebackOrSweeper() {
        for (String unsafe : new String[]{"ainv.ksef.enabled=true", "ainv.sap-write.enabled=true",
                "ainv.sap-write.sweeper.enabled=true"}) {
            String failure = startFailure("ainv.deployment.identity-smoke=true", unsafe);
            assertTrue(failure.contains("Identity smoke mode requires ainv.ksef.enabled=false"), unsafe + "\n" + failure);
        }
    }

    @Test void explicitIdentitySmokeStartsReadOnly() {
        try (ConfigurableApplicationContext context = start("ainv.deployment.identity-smoke=true")) {
            assertNotNull(context.getBean(IdentitySmokeReadOnlyHandler.class));
        }
    }

    @Test void localRuntimeHasNoSmokeHandlerAndNoPersistenceRule() {
        String[] args = java.util.Arrays.stream(LOCAL_DB).map(value -> "--" + value).toArray(String[]::new);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class).run(args)) {
            assertTrue(context.getBeansOfType(IdentitySmokeReadOnlyHandler.class).isEmpty());
        }
    }

    @Test void decisionRuleAcceptsOnlyHana() {
        assertDoesNotThrow(() -> CloudPersistenceGuard.verify("HDB", false, true, true, true));
        assertDoesNotThrow(() -> CloudPersistenceGuard.verify("SAP HANA", false, false, false, true));
        assertThrows(IllegalStateException.class, () -> CloudPersistenceGuard.verify("H2", false, false, false, false));
        assertThrows(IllegalStateException.class, () -> CloudPersistenceGuard.verify("PostgreSQL", false, false, false, false));
        assertThrows(IllegalStateException.class, () -> CloudPersistenceGuard.verify(null, false, false, false, false));
        assertDoesNotThrow(() -> CloudPersistenceGuard.verify("H2", true, false, false, false));
    }
}
