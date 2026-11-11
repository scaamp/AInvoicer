package customer.cap_java.security;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.mock.env.MockEnvironment;
import com.sap.cds.services.outbox.OutboxService;
import com.sap.cds.services.runtime.CdsRuntime;
import customer.cap_java.Application;

/** Lokalny test wiring na H2, NIE test HANA. Brak outbox usług = brak kolektorów i delivery. */
class HanaReadinessConfigurationTest {
    @Test void disabledOutboxesDoNotBreakApplicationWiringOrStartCollectors() {
        try (var app = new SpringApplicationBuilder(Application.class).run(
                "--spring.main.web-application-type=none", "--spring.sql.init.platform=h2",
                "--ainv.deployment.hana-readiness=true", "--ainv.deployment.identity-smoke=false",
                "--ainv.ksef.enabled=false", "--ainv.ksef.inbound.enabled=false", "--ainv.sap-write.enabled=false",
                "--ainv.sap-write.sweeper.enabled=false", "--ainv.ksef.storage.provider=off",
                "--cds.outbox.persistent.enabled=false", "--cds.outbox.inMemory.enabled=false")) {
            var runtime = app.getBean(CdsRuntime.class);
            assertEquals(0, runtime.getServiceCatalog().getServices(OutboxService.class).count());
            assertNotNull(app.getBean(CloudHanaReadinessGuard.class));
            assertTrue(app.getBeansOfType(customer.cap_java.ksef.storage.ContentAddressedDocumentStore.class).isEmpty());
        }
    }

    @Test void readinessFlagsCannotBeEnabledOrOmitted() {
        var env = new MockEnvironment();
        String[] keys = {"ainv.deployment.identity-smoke", "ainv.ksef.enabled", "ainv.ksef.inbound.enabled",
                "ainv.sap-write.enabled", "ainv.sap-write.sweeper.enabled"};
        for (String key : keys) env.setProperty(key, "false");
        assertDoesNotThrow(() -> CloudHanaReadinessGuard.verifyFlags(env));
        for (String key : keys) {
            env.setProperty(key, "true");
            assertThrows(IllegalStateException.class, () -> CloudHanaReadinessGuard.verifyFlags(env));
            env.setProperty(key, "false");
        }
        env.setProperty("ainv.ksef.storage.provider", "azure");
        assertThrows(IllegalStateException.class, () -> CloudHanaReadinessGuard.verifyFlags(env));
    }
}
