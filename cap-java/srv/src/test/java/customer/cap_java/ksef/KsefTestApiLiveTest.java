package customer.cap_java.ksef;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import pl.akmf.ksef.sdk.api.services.DefaultCryptographyService;

/** Jawnie bramkowane auth TEST. Bez open/send; tokeny nie trafiają do raportu Surefire. */
@Tag("live-ksef")
@EnabledIfEnvironmentVariable(named = "AINV_KSEF_LIVE_AUTH", matches = "true")
class KsefTestApiLiveTest {
    @Test void challengeXadesRedeemAndAuthorizedRead() throws Exception {
        var client = KsefSdkConfiguration.createClient(KsefSdkConfiguration.TEST_ORIGIN);
        var challenge = client.getAuthChallenge();
        assertNotNull(challenge.getChallenge());
        var auth = new KsefAuthentication(client, new DefaultCryptographyService(client), Clock.systemUTC(),
                System.getenv("AINV_KSEF_NIP"), System.getenv().getOrDefault("AINV_KSEF_TOKEN", ""), true);
        var tokens = auth.authenticate(System.getenv("AINV_KSEF_NIP"));
        assertTrue(tokens.accessValidUntil().isAfter(Clock.systemUTC().instant()));
        assertNotNull(client.getActiveSessions(10, null, tokens.accessToken()));
        // Wymuszenie refresh sprawdza realny endpoint bez drukowania JWT.
        var refresh = auth.refresh(tokens);
        assertTrue(refresh.accessValidUntil().isAfter(Clock.systemUTC().instant()));
        System.out.println("KSEF TEST: challenge PASS, auth business 200, redeem PASS, authorized READ PASS, refresh PASS");
    }
}
