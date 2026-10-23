package pl.akmf.ksef.sdk.api;

import jakarta.xml.bind.JAXBException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.akmf.ksef.sdk.api.builders.auth.AuthTokenRequestBuilder;
import pl.akmf.ksef.sdk.api.builders.auth.AuthTokenRequestSerializer;
import pl.akmf.ksef.sdk.api.builders.certificate.CertificateBuilders;
import pl.akmf.ksef.sdk.client.interfaces.CertificateService;
import pl.akmf.ksef.sdk.client.interfaces.SignatureService;
import pl.akmf.ksef.sdk.client.model.ApiException;
import pl.akmf.ksef.sdk.client.model.auth.AuthOperationStatusResponse;
import pl.akmf.ksef.sdk.client.model.auth.AuthStatus;
import pl.akmf.ksef.sdk.client.model.auth.AuthenticationChallengeResponse;
import pl.akmf.ksef.sdk.client.model.auth.SignatureResponse;
import pl.akmf.ksef.sdk.client.model.auth.TokenInfo;
import pl.akmf.ksef.sdk.client.model.StatusInfo;
import pl.akmf.ksef.sdk.client.model.certificate.SelfSignedCertificate;
import pl.akmf.ksef.sdk.client.model.xml.AuthTokenRequest;
import pl.akmf.ksef.sdk.client.model.xml.SubjectIdentifierTypeEnum;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@RestController
@RequiredArgsConstructor
public class AuthController {
    private final DefaultKsefClient ksefClient;
    private final SignatureService signatureService;
    private final CertificateService certificateService;

    @Value("${sdk.config.base-uri}")
    private String baseUri;
    @Value("${e2e.auth.poll-interval-ms:1000}")
    private long pollIntervalMs = 1000;
    @Value("${e2e.auth.max-poll-attempts:20}")
    private int maxPollAttempts = 20;
    @Value("${e2e.auth.poll-timeout-ms:25000}")
    private long pollTimeoutMs = 25000;

    // Zachowujemy pola accessToken/refreshToken i ujawniamy końcowy status biznesowy.
    // Tokeny wracają do klienta REST, ale nie trafiają do logów.
    public record AuthenticationResult(TokenInfo accessToken, TokenInfo refreshToken, StatusInfo status) {}

    /**
     * Cały process autoryzacji krok po kroku
     * Zwraca token JWT oraz refreshToken
     * Inicjalizacja przykladowego identyfikatora - w tym przypadku NIP.
     *
     * @param context nip kontekstu w którym następuje próba uwierzytelnienia
     * @return AuthenticationOperationStatusResponse
     * @throws ApiException if fails to make API call
     */
    @PostMapping(value = "/authenticationProcess/{context}")
    public AuthenticationResult authStepByStepAsOwner(@PathVariable String context) throws ApiException, JAXBException, IOException {
        if (!"https://api-test.ksef.mf.gov.pl".equals(baseUri)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "Samopodpisany certyfikat demo jest dozwolony wyłącznie w KSeF TEST");
        }
        //wykonanie auth challenge
        AuthenticationChallengeResponse challenge = ksefClient.getAuthChallenge();

        //xml niezbędny do uwierzytelnienia
        AuthTokenRequest authTokenRequest = new AuthTokenRequestBuilder()
                .withChallenge(challenge.getChallenge())
                .withContextNip(context)
                .withSubjectType(SubjectIdentifierTypeEnum.CERTIFICATE_SUBJECT)
                .build();

        String xml = AuthTokenRequestSerializer.authTokenRequestSerializer(authTokenRequest);

        //wygenerowanie certyfikatu oraz klucza prywatnego
        CertificateBuilders.X500NameHolder x500 = new CertificateBuilders()
                .buildForOrganization("Kowalski sp. z o.o", "VATPL-" + context, "Kowalski", "PL");

        SelfSignedCertificate cert = certificateService.generateSelfSignedCertificateRsa(x500);

        //podpisanie xml wygenerowanym certyfikatem oraz kluczem prywatnym
        String signedXml = signatureService.sign(xml.getBytes(StandardCharsets.UTF_8), cert.certificate(), cert.getPrivateKey());

        // Przesłanie podpisanego XML do systemu KSeF
        SignatureResponse submitAuthTokenResponse = ksefClient.submitAuthTokenRequest(signedXml, false);

        //Czekanie na zakończenie procesu
        AuthStatus ready = waitForAuthentication(submitAuthTokenResponse.getReferenceNumber(),
                submitAuthTokenResponse.getAuthenticationToken().getToken());

        //pobranie tokenów
        // Redeem jest jednorazowy. Nie obejmujemy go retry ani pętlą pollingu.
        AuthOperationStatusResponse tokens = ksefClient.redeemToken(submitAuthTokenResponse.getAuthenticationToken().getToken());
        return new AuthenticationResult(tokens.getAccessToken(), tokens.getRefreshToken(), ready.getStatus());
    }

    /** GET /v2/auth/{ref}: 100 oznacza oczekiwanie, 200 pozwala na redeem.
     * Pętla nie powtarza challenge, podpisu ani redeem. Błąd sieci przerywa proces,
     * a końcowy status odrzucenia nie jest mylony z przetwarzaniem w toku.
     * Limit czasu obejmuje oczekiwanie; pojedynczy HTTP ogranicza timeout SDK. */
    AuthStatus waitForAuthentication(String referenceNumber, String tempToken) throws ApiException {
        if (pollIntervalMs < 1 || maxPollAttempts < 1 || pollTimeoutMs < 1) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Niepoprawna konfiguracja pollingu auth");
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(pollTimeoutMs);
        for (int attempt = 1; attempt <= maxPollAttempts && System.nanoTime() < deadline; attempt++) {
            AuthStatus result = ksefClient.getAuthStatus(referenceNumber, tempToken);
            Integer code = result == null || result.getStatus() == null ? null : result.getStatus().getCode();
            if (code == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Brak status.code uwierzytelniania");
            if (code == 200) return result;
            if (code != 100) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "KSeF auth status.code=" + code);
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (attempt == maxPollAttempts || remainingMs <= 0) break;
            try {
                Thread.sleep(Math.min(pollIntervalMs, remainingMs));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Przerwano oczekiwanie na auth");
            }
        }
        throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Przekroczono limit oczekiwania na auth KSeF");
    }
}
