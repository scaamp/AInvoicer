package customer.cap_java;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/** Bramka H2 odmawia startu dla CF/PROD/proxy, także przy mieszanych profilach. */
class H2ConsoleSecurityConfigurationTest {
    private MockEnvironment local() {
        var env = new MockEnvironment().withProperty("server.address", "127.0.0.1");
        env.setActiveProfiles("default", "h2-console-local");
        return env;
    }

    @Test void explicitIsolatedLocalConsoleIsAllowed() {
        assertDoesNotThrow(() -> new H2ConsoleSecurityConfiguration(local()));
    }

    @ParameterizedTest @ValueSource(strings = {"cf", "cloud", "prod", "production"})
    void cloudOrProductionCannotEnableConsoleEvenWithLocalProfile(String profile) {
        var env = local();
        env.setActiveProfiles("default", "h2-console-local", profile);
        assertThrows(IllegalStateException.class, () -> new H2ConsoleSecurityConfiguration(env));
    }

    @ParameterizedTest @ValueSource(strings = {"VCAP_APPLICATION", "VCAP_SERVICES"})
    void cloudEnvironmentCannotEnableConsole(String property) {
        assertThrows(IllegalStateException.class, () -> new H2ConsoleSecurityConfiguration(local().withProperty(property, "{}")));
    }

    @ParameterizedTest @ValueSource(strings = {"", "0.0.0.0", "::", "localhost", "192.0.2.10"})
    void publicOrUnspecifiedBindIsRejected(String address) {
        assertThrows(IllegalStateException.class, () -> new H2ConsoleSecurityConfiguration(local().withProperty("server.address", address)));
    }

    @Test void defaultProfileAloneAndForwardedHeadersAreRejected() {
        var env = local();
        env.setActiveProfiles("default");
        assertThrows(IllegalStateException.class, () -> new H2ConsoleSecurityConfiguration(env));
        assertThrows(IllegalStateException.class, () -> new H2ConsoleSecurityConfiguration(local()
            .withProperty("server.forward-headers-strategy", "framework")));
        assertThrows(IllegalStateException.class, () -> new H2ConsoleSecurityConfiguration(local()
            .withProperty("spring.h2.console.settings.web-allow-others", "true")));
    }
}
