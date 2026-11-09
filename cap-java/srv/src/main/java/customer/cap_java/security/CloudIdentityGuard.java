package customer.cap_java.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import com.sap.cds.services.environment.CdsProperties;
import com.sap.cds.services.runtime.CdsRuntime;
import com.sap.cloud.environment.servicebinding.api.ServiceBinding;

/**
 * Fail-fast tożsamości platformy (CAP-07, ADR-0006).
 *
 * <p>CAP Java aktywuje walidację JWT wyłącznie przy bindingu XSUAA/IAS. Bez bindingu i z wyłączonym
 * mock auth aplikacja nadal by wystartowała — odmawiałaby dostępu, ale bez jasnej przyczyny, a błędna
 * konfiguracja mock mogłaby otworzyć lokalne konta. W środowisku chmurowym (profil cf/cloud/prod/production
 * albo obecny VCAP_APPLICATION) wymagamy więc: mock auth OFF, uwierzytelnianie CAP aktywne i dokładnie
 * jeden binding XSUAA. Inaczej start się nie udaje, zanim HTTP zostanie udostępnione.
 *
 * <p>Sam guard nie waliduje tokenów; robi to oficjalna integracja cds-feature-identity +
 * resourceserver-security-spring-boot-starter. Lokalny development (profil default/test) jest pominięty.
 */
@Configuration(proxyBeanMethods = false)
public class CloudIdentityGuard {

    static final Profiles CLOUD_PROFILES = Profiles.of("cf", "cloud", "prod", "production");

    public CloudIdentityGuard(Environment environment, CdsRuntime runtime) {
        if (!isCloudRuntime(environment)) {
            return;
        }
        CdsProperties.Security security = runtime.getEnvironment().getCdsProperties().getSecurity();
        if (Boolean.TRUE.equals(security.getMock().isEnabled())) {
            throw new IllegalStateException("Cloud runtime requires cds.security.mock.enabled=false");
        }
        if ("never".equalsIgnoreCase(security.getAuthentication().getMode())
                || Boolean.FALSE.equals(security.getAuthentication().getAuthConfig().isEnabled())
                || Boolean.FALSE.equals(security.getXsuaa().isEnabled())) {
            throw new IllegalStateException("Cloud runtime requires CAP XSUAA authentication to stay enabled");
        }
        long xsuaaBindings = runtime.getEnvironment().getServiceBindings().filter(CloudIdentityGuard::isXsuaa).count();
        if (xsuaaBindings != 1) {
            // Bez szczegółów bindingu (credentials) w komunikacie.
            throw new IllegalStateException("Cloud runtime requires exactly one XSUAA service binding (found "
                    + xsuaaBindings + "); no mock authentication fallback");
        }
    }

    static boolean isCloudRuntime(Environment environment) {
        return environment.acceptsProfiles(CLOUD_PROFILES) || environment.containsProperty("VCAP_APPLICATION");
    }

    private static boolean isXsuaa(ServiceBinding binding) {
        return binding.getServiceName().map("xsuaa"::equals).orElse(false) || binding.getTags().contains("xsuaa");
    }
}
