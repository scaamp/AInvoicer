package customer.cap_java.ksef;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;

import customer.cap_java.ksef.inbound.*;
import customer.cap_java.ksef.inbound.fa3.Fa3ParseException;
import pl.akmf.ksef.sdk.api.services.DefaultCryptographyService;

/** Osobna zgoda auth + READ oraz zwalidowany zakres są wymagane niezależnie od tagu JUnit. */
@Tag("live-ksef")
@EnabledIfEnvironmentVariable(named = "AINV_KSEF_ONESHOT_READ_APPROVED", matches = "true")
class KsefInboundOneShotLiveTest {
    @Test void readExactlyOneApprovedTestInvoice() {
        Clock clock = Clock.systemUTC();
        var settings = KsefInboundOneShot.Settings.approved(System.getenv(), clock);
        KsefInboundOneShot.require(System.getProperty("jdk.httpclient.HttpClient.log", "").isBlank()
                && System.getProperty("javax.net.debug", "").isBlank(), "WIRE_LOGGING_FORBIDDEN");
        KsefInboundOneShot.require(Boolean.getBoolean("jdk.httpclient.disableRetryConnect")
                && "1".equals(System.getProperty("jdk.httpclient.redirects.retrylimit")), "JDK_RETRY_MUST_BE_DISABLED");
        // SDK crypto potrafi logować treść wyjątku HTTP. W tym izolowanym harnessie wyciszamy logger.
        var root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var previous = root.getLevel();
        root.setLevel(ch.qos.logback.classic.Level.OFF);
        String run = UUID.randomUUID().toString();
        boolean authenticated = false;
        try (var transport = new OneShotHttpClient(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build(),
                KsefSdkConfiguration.TEST_ORIGIN, settings)) {
            try {
                var sdk = KsefSdkConfiguration.createClient(KsefSdkConfiguration.TEST_ORIGIN, transport);
                // XAdES nie używa crypto: nie inicjujemy niepotrzebnego pobrania certyfikatów MF.
                var crypto = settings.authMethod() == KsefInboundOneShot.AuthMethod.TOKEN
                        ? new DefaultCryptographyService(sdk) : null;
                KsefInboundOneShot.require(transport.stopReason().equals("NONE"), "CERTIFICATES_FAILED");
                var auth = new KsefAuthentication(sdk, crypto, clock, settings.context().nip(),
                        System.getenv().getOrDefault("AINV_KSEF_TOKEN", ""),
                        settings.authMethod() == KsefInboundOneShot.AuthMethod.XADES);
                var tokens = auth.authenticate(settings.context().nip());
                KsefInboundOneShot.require(tokens.accessValidUntil().isAfter(clock.instant().plusSeconds(30)), "JWT_EXPIRES_TOO_SOON");
                authenticated = true;
                var result = KsefInboundOneShot.read(settings, sdk,
                        InboundAccessToken.issuedFor(settings.context(), tokens.accessToken()), clock);
                System.out.println("KSEF_ONESHOT run=" + run + " environment=TEST scenario=" + settings.scenario()
                        + " auth=PASS invoiceRead=CONFIRMED http=" + transport.lastStatus()
                        + " requests=" + transport.requests() + " result=" + result);
                if (result.outcome().equals("BLOCKED")) {
                    throw new AssertionError("KSEF_ONESHOT_BLOCKED_UNSUPPORTED_SCHEMA");
                }
            } catch (RuntimeException failure) {
                // Jawna allowlista kategorii; bez getMessage, cause, URL, numerów, treści i tokenów SDK.
                String category = failure instanceof InboundFailure inbound ? inbound.category().name()
                        : failure instanceof Fa3ParseException xml ? xml.code().name()
                        : failure instanceof KsefFailure ? "AUTH_OR_GUARD_STOP" : "UNCLASSIFIED";
                String read = transport.invoiceRead();
                System.out.println("KSEF_ONESHOT run=" + run + " environment=TEST scenario=" + settings.scenario()
                        + " auth=" + (authenticated ? "PASS" : "NOT_CONFIRMED") + " invoiceRead=" + read
                        + " http=" + transport.lastStatus() + " requests=" + transport.requests()
                        + " outcome=STOP category=" + category + " transport=" + transport.stopReason()
                        + " retryNotBefore=" + transport.retryNotBefore());
                throw new AssertionError("KSEF_ONESHOT_STOP_" + category);
            }
        } finally {
            root.setLevel(previous);
        }
    }
}
