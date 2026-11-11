package customer.cap_java.security;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DedicatedTenantPolicyTest {
    @Test void bindingRequiresIdentifierNotZoneName() {
        assertThrows(IllegalStateException.class, () -> DedicatedTenantPolicy.expectedTenant(Map.of("identityzone", "zone-name")));
        assertThrows(IllegalStateException.class, () -> DedicatedTenantPolicy.expectedTenant(Map.of("tenantid", "")));
        assertThrows(IllegalStateException.class, () -> DedicatedTenantPolicy.expectedTenant(Map.of("tenantid", "a", "identityzoneid", "b")));
        assertThrows(IllegalStateException.class, () -> DedicatedTenantPolicy.expectedTenant(Map.of("tenantid", "a", "tenantmode", "shared")));
    }

    @Test void bindingAliasesMustAgree() {
        assertEquals("a", DedicatedTenantPolicy.expectedTenant(Map.of("tenantid", "a", "identityzoneid", "a", "zone_uuid", "a", "tenantmode", "dedicated")));
        assertEquals("a", DedicatedTenantPolicy.expectedTenant(Map.of("identityzoneid", "a")));
        assertEquals("a", DedicatedTenantPolicy.expectedTenant(Map.of("zone_uuid", "a")));
    }

    @Test void xsuaaZoneIsAuthoritativeAndOptionalAliasesCannotBroadenTrust() {
        assertTrue(DedicatedTenantPolicy.allowed("a", Map.of("zid", "a")));
        assertTrue(DedicatedTenantPolicy.allowed("a", Map.of("zid", "a", "app_tid", "a", "zone_uuid", "a")));
        for (var claims : java.util.List.<Map<String, Object>>of(Map.of(), Map.of("app_tid", "a"), Map.of("zid", "b"),
                Map.of("zid", "a", "app_tid", "b"), Map.of("zid", "a", "zone_uuid", "b"))) {
            assertFalse(DedicatedTenantPolicy.allowed("a", claims));
        }
    }
}
