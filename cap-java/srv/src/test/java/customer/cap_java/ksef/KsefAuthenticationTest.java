package customer.cap_java.ksef;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.akmf.ksef.sdk.api.services.DefaultCryptographyService;
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient;
import pl.akmf.ksef.sdk.client.model.ApiException;
import pl.akmf.ksef.sdk.client.model.StatusInfo;
import pl.akmf.ksef.sdk.client.model.auth.*;

class KsefAuthenticationTest {
    private final KSeFClient client = mock(KSeFClient.class);
    private final DefaultCryptographyService crypto = mock(DefaultCryptographyService.class);
    private final Instant now = Instant.parse("2026-10-29T12:00:00Z");
    private KsefAuthentication auth;

    @BeforeEach void setup() throws Exception {
        auth = new KsefAuthentication(client, crypto, Clock.fixed(now, ZoneOffset.UTC), "1234563218", "mock-secret", false);
        when(client.getAuthChallenge()).thenReturn(new AuthenticationChallengeResponse("mock-challenge", now));
        when(crypto.encryptKsefTokenUsingPublicKey(anyString(), any())).thenReturn(new byte[]{1, 2});
        var publicKey = new pl.akmf.ksef.sdk.client.model.certificate.publickey.PublicKeyCertificate();
        publicKey.setPublicKeyId("mock-key-id");
        when(crypto.getKsefToken()).thenReturn(publicKey);
        when(client.authenticateByKSeFToken(any())).thenReturn(new SignatureResponse("auth-ref", info("temporary", 600)));
        when(client.getAuthStatus(anyString(), anyString())).thenReturn(status(200));
        when(client.redeemToken("temporary")).thenReturn(new AuthOperationStatusResponse(info("access", 900), info("refresh", 3600)));
    }

    @Test void tokenChallengeStatusAndSingleRedeem() throws Exception {
        var tokens = auth.authenticate("1234563218");
        assertEquals("access", tokens.accessToken());
        assertFalse(tokens.toString().contains("access"));
        verify(crypto).encryptKsefTokenUsingPublicKey("mock-secret", now);
        verify(client).getAuthStatus("auth-ref", "temporary");
        verify(client, times(1)).redeemToken("temporary");
        verify(client, never()).submitAuthTokenRequest(anyString(), anyBoolean());
    }

    @Test void pendingIsNotAcceptedBeforeBusiness200() throws Exception {
        when(client.getAuthStatus(anyString(), anyString())).thenReturn(status(100), status(200));
        assertEquals("access", auth.authenticate("1234563218").accessToken());
        verify(client, times(2)).getAuthStatus(anyString(), anyString());
        verify(client, times(1)).redeemToken(anyString());
    }

    @Test void rejectionDoesNotRedeem() throws Exception {
        when(client.getAuthStatus(anyString(), anyString())).thenReturn(status(450));
        assertEquals("KSEF_AUTH_REJECTED_450", assertThrows(KsefFailure.class,
                () -> auth.authenticate("1234563218")).getMessage());
        verify(client, never()).redeemToken(anyString());
    }

    @Test void lostRedeemResponseIsAmbiguousAndNeverRepeated() throws Exception {
        when(client.redeemToken(anyString())).thenThrow(new ApiException("mock-secret-must-not-leak") { });
        var failure = assertThrows(KsefFailure.class, () -> auth.authenticate("1234563218"));
        assertTrue(failure.ambiguous());
        assertFalse(failure.retryable());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("mock-secret"));
        verify(client, times(1)).redeemToken(anyString());
    }

    @Test void alreadyRedeemedIsNotRedeemedAgain() throws Exception {
        var status = status(200);
        status.setIsTokenRedeemed(true);
        when(client.getAuthStatus(anyString(), anyString())).thenReturn(status);
        assertThrows(KsefFailure.class, () -> auth.authenticate("1234563218"));
        verify(client, never()).redeemToken(anyString());
    }

    @Test void contextRequiresConfiguredCredentialAndMissingSecretFailsClosed() {
        assertThrows(KsefFailure.class, () -> auth.authenticate("9876543210"));
        var missing = new KsefAuthentication(client, crypto, Clock.systemUTC(), "1234563218", "", false);
        assertThrows(KsefFailure.class, () -> missing.authenticate("1234563218"));
        verifyNoInteractions(client);
    }

    @Test void refreshNearExpiryAndKeepStillValidToken() throws Exception {
        var normal = auth.authenticate("1234563218");
        assertSame(normal, auth.refreshIfNeeded(normal));
        var expiring = new KsefAuthentication.Tokens(info("old", 30), info("refresh", 3600));
        var response = new AuthenticationTokenRefreshResponse();
        response.setAccessToken(info("new", 900));
        when(client.refreshAccessToken("refresh")).thenReturn(response);
        assertEquals("new", auth.refreshIfNeeded(expiring).accessToken());
        verify(client, times(1)).refreshAccessToken("refresh");
    }

    private TokenInfo info(String token, long seconds) {
        return new TokenInfo(token, OffsetDateTime.ofInstant(now.plusSeconds(seconds), ZoneOffset.UTC));
    }

    static AuthStatus status(int code) {
        var status = new StatusInfo();
        status.setCode(code);
        var auth = new AuthStatus();
        auth.setStatus(status);
        return auth;
    }
}
