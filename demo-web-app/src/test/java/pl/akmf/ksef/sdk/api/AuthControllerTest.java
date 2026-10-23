package pl.akmf.ksef.sdk.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import pl.akmf.ksef.sdk.client.interfaces.CertificateService;
import pl.akmf.ksef.sdk.client.interfaces.SignatureService;
import pl.akmf.ksef.sdk.client.model.StatusInfo;
import pl.akmf.ksef.sdk.client.model.auth.*;
import pl.akmf.ksef.sdk.client.model.certificate.SelfSignedCertificate;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthControllerTest {
    private DefaultKsefClient client;
    private AuthController controller;

    @BeforeEach
    void setup() throws Exception {
        client = mock(DefaultKsefClient.class);
        SignatureService signer = mock(SignatureService.class);
        CertificateService certificates = mock(CertificateService.class);
        controller = new AuthController(client, signer, certificates);
        ReflectionTestUtils.setField(controller, "baseUri", "https://api-test.ksef.mf.gov.pl");
        ReflectionTestUtils.setField(controller, "pollIntervalMs", 1L);
        ReflectionTestUtils.setField(controller, "maxPollAttempts", 3);
        when(certificates.generateSelfSignedCertificateRsa(any())).thenReturn(mock(SelfSignedCertificate.class));
        when(signer.sign(any(), any(), any())).thenReturn("signed-test-xml");
        AuthenticationChallengeResponse challenge = mock(AuthenticationChallengeResponse.class);
        when(challenge.getChallenge()).thenReturn("20261023-TEST-CHALLENGE");
        when(client.getAuthChallenge()).thenReturn(challenge);
        when(client.submitAuthTokenRequest(anyString(), eq(false)))
                .thenReturn(new SignatureResponse("auth-ref", new TokenInfo("temporary-test-token", OffsetDateTime.now().plusMinutes(5))));
    }

    private AuthStatus status(int code) {
        AuthStatus value = new AuthStatus();
        value.setStatus(new StatusInfo(code, "test", null));
        return value;
    }

    @Test
    void waitsFor200AndRedeemsExactlyOnce() throws Exception {
        when(client.getAuthStatus("auth-ref", "temporary-test-token")).thenReturn(status(100), status(100), status(200));
        when(client.redeemToken("temporary-test-token"))
                .thenReturn(new AuthOperationStatusResponse(new TokenInfo("access-test", null), new TokenInfo("refresh-test", null)));
        var result = controller.authStepByStepAsOwner("1234563218");
        assertEquals(200, result.status().getCode());
        assertEquals("access-test", result.accessToken().getToken());
        verify(client, times(3)).getAuthStatus("auth-ref", "temporary-test-token");
        verify(client, times(1)).submitAuthTokenRequest(anyString(), eq(false));
        verify(client, times(1)).redeemToken("temporary-test-token");
    }

    @Test
    void rejectionStopsPollingWithoutRedeem() throws Exception {
        when(client.getAuthStatus(anyString(), anyString())).thenReturn(status(460));
        var error = assertThrows(ResponseStatusException.class, () -> controller.authStepByStepAsOwner("1234563218"));
        assertEquals(401, error.getStatusCode().value());
        verify(client, times(1)).getAuthStatus(anyString(), anyString());
        verify(client, never()).redeemToken(anyString());
    }

    @Test
    void attemptLimitReturns504WithoutRedeem() throws Exception {
        when(client.getAuthStatus(anyString(), anyString())).thenReturn(status(100));
        var error = assertThrows(ResponseStatusException.class, () -> controller.authStepByStepAsOwner("1234563218"));
        assertEquals(504, error.getStatusCode().value());
        verify(client, times(3)).getAuthStatus(anyString(), anyString());
        verify(client, never()).redeemToken(anyString());
    }

    @Test
    void timeoutStopsBeforeAttemptLimit() throws Exception {
        ReflectionTestUtils.setField(controller, "pollTimeoutMs", 5L);
        when(client.getAuthStatus(anyString(), anyString())).thenAnswer(call -> { Thread.sleep(15); return status(100); });
        var error = assertThrows(ResponseStatusException.class, () -> controller.waitForAuthentication("ref", "test-token"));
        assertEquals(504, error.getStatusCode().value());
        verify(client, atMostOnce()).getAuthStatus(anyString(), anyString());
    }

    @Test
    void missingStatusIsContractErrorWithoutRedeem() throws Exception {
        when(client.getAuthStatus(anyString(), anyString())).thenReturn(new AuthStatus());
        var error = assertThrows(ResponseStatusException.class, () -> controller.authStepByStepAsOwner("1234563218"));
        assertEquals(502, error.getStatusCode().value());
        verify(client, never()).redeemToken(anyString());
    }

    @Test
    void failedRedeemIsNotRetried() throws Exception {
        when(client.getAuthStatus(anyString(), anyString())).thenReturn(status(200));
        when(client.redeemToken(anyString())).thenThrow(new IllegalStateException("unknown-outcome"));
        assertThrows(IllegalStateException.class, () -> controller.authStepByStepAsOwner("1234563218"));
        verify(client, times(1)).redeemToken(anyString());
    }

    @Test
    void productionIsBlockedBeforeChallenge() {
        ReflectionTestUtils.setField(controller, "baseUri", "https://api.ksef.mf.gov.pl");
        var error = assertThrows(ResponseStatusException.class, () -> controller.authStepByStepAsOwner("1234563218"));
        assertEquals(412, error.getStatusCode().value());
        verifyNoInteractions(client);
    }

    @Test
    void interruptedWaitPreservesInterruptFlag() throws Exception {
        when(client.getAuthStatus(anyString(), anyString())).thenReturn(status(100));
        try {
            Thread.currentThread().interrupt();
            var error = assertThrows(ResponseStatusException.class, () -> controller.waitForAuthentication("ref", "token"));
            assertEquals(503, error.getStatusCode().value());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
