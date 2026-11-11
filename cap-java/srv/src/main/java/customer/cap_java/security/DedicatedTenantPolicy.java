package customer.cap_java.security;

import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import com.sap.cds.services.runtime.CdsRuntime;
import com.sap.cloud.environment.servicebinding.api.ServiceBinding;

/**
 * INT-03: jedna polityka dedicated tenant dla wszystkich endpointów, metadata i batch.
 * Oficjalny decoder SAP nadal sprawdza podpis/JWKS, audience i czas. Dekorator sprawdza
 * wyłącznie już zweryfikowane claims, przed utworzeniem principal CAP; brak własnej kryptografii
 * i SecurityFilterChain. Błąd uwierzytelnienia daje 401, nigdy pustą odpowiedź 200.
 */
@Configuration(proxyBeanMethods = false)
public class DedicatedTenantPolicy {

    /** Lazy runtime zapobiega inicjalizacji CAP podczas rejestracji BeanPostProcessor. */
    @Bean
    static BeanPostProcessor dedicatedTenantDecoder(ObjectProvider<CdsRuntime> runtime) {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof JwtDecoder sapDecoder)) { return bean; }
                return (JwtDecoder) encoded -> {
                    var verified = sapDecoder.decode(encoded);
                    String expected = expectedTenant(runtime.getObject());
                    Map<String, Object> claims = verified.getClaims();
                    if (!allowed(expected, claims)) {
                        throw new BadJwtException("AINV_DEDICATED_TENANT_REQUIRED");
                    }
                    return verified;
                };
            }
        };
    }

    /** SAP XsuaaToken.getAppTid() czyta zid; opcjonalne aliasy nie mogą wskazywać innej strefy. */
    static boolean allowed(String expected, Map<String, Object> claims) {
        return expected.equals(claims.get("zid"))
                && (!claims.containsKey("app_tid") || expected.equals(claims.get("app_tid")))
                && (!claims.containsKey("zone_uuid") || expected.equals(claims.get("zone_uuid")));
    }

    /** Tenant wyłącznie z bindingu platformy; identityzone (nazwa) nie jest identyfikatorem strefy. */
    static String expectedTenant(CdsRuntime runtime) {
        var bindings = runtime.getEnvironment().getServiceBindings().filter(DedicatedTenantPolicy::isXsuaa).toList();
        if (bindings.size() != 1) {
            throw new IllegalStateException("Dedicated tenant requires exactly one XSUAA binding");
        }
        return expectedTenant(bindings.getFirst().getCredentials());
    }

    static String expectedTenant(Map<String, Object> credentials) {
        String expected = null;
        for (String key : new String[] {"tenantid", "identityzoneid", "zone_uuid"}) {
            Object value = credentials.get(key);
            if (value == null) { continue; }
            if (!(value instanceof String id) || id.isBlank() || expected != null && !expected.equals(id)) {
                throw new IllegalStateException("XSUAA dedicated tenant identifiers missing or inconsistent");
            }
            expected = (String) value;
        }
        if (expected == null || credentials.containsKey("tenantmode") && !"dedicated".equals(credentials.get("tenantmode"))) {
            throw new IllegalStateException("XSUAA dedicated tenant identifier and dedicated mode required");
        }
        return expected;
    }

    static boolean isXsuaa(ServiceBinding binding) {
        return binding.getServiceName().map("xsuaa"::equals).orElse(false) || binding.getTags().contains("xsuaa");
    }
}
