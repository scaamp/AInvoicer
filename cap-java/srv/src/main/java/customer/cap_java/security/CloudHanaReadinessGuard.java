package customer.cap_java.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import com.sap.cds.services.outbox.OutboxService;
import com.sap.cds.services.runtime.CdsRuntime;

/** Jawny wariant INT-03: HANA bez dostaw outbox, synchronizacji, storage providera i efektów zewnętrznych. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ainv.deployment.hana-readiness", havingValue = "true")
public class CloudHanaReadinessGuard {
    public CloudHanaReadinessGuard(CloudPersistenceGuard persistenceChecked, Environment env, CdsRuntime runtime) {
        verifyFlags(env);
        var properties = runtime.getEnvironment().getCdsProperties().getOutbox();
        if (!Boolean.FALSE.equals(properties.getPersistent().isEnabled())
                || !Boolean.FALSE.equals(properties.getInMemory().isEnabled())
                || runtime.getServiceCatalog().getServices(OutboxService.class).findAny().isPresent()) {
            throw new IllegalStateException("HANA readiness requires all outbox services and delivery disabled");
        }
    }

    /** Sprawdzamy zbindowane właściwości, nie obecność samej nazwy env w deskryptorze. */
    static void verifyFlags(Environment env) {
        for (String flag : new String[]{"ainv.deployment.identity-smoke", "ainv.ksef.enabled", "ainv.ksef.inbound.enabled",
                "ainv.sap-write.enabled", "ainv.sap-write.sweeper.enabled"}) {
            if (!Boolean.FALSE.equals(env.getProperty(flag, Boolean.class))) {
                throw new IllegalStateException("HANA readiness requires " + flag + "=false");
            }
        }
        if (!"off".equals(env.getProperty("ainv.ksef.storage.provider", "off"))) {
            throw new IllegalStateException("HANA readiness requires Object Store provider off");
        }
    }
}
