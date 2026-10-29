package customer.cap_java.ksef;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;
import pl.akmf.ksef.sdk.api.builders.auth.AuthKsefTokenRequestBuilder;
import pl.akmf.ksef.sdk.api.builders.auth.AuthTokenRequestBuilder;
import pl.akmf.ksef.sdk.api.builders.auth.AuthTokenRequestSerializer;
import pl.akmf.ksef.sdk.api.services.DefaultCertificateService;
import pl.akmf.ksef.sdk.api.services.DefaultCryptographyService;
import pl.akmf.ksef.sdk.api.services.DefaultSignatureService;
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient;
import pl.akmf.ksef.sdk.client.model.auth.ContextIdentifier;
import pl.akmf.ksef.sdk.client.model.auth.SignatureResponse;
import pl.akmf.ksef.sdk.client.model.auth.TokenInfo;
import pl.akmf.ksef.sdk.client.model.xml.SubjectIdentifierTypeEnum;

/** Challenge → token/XAdES TEST → status biznesowy 200 → pojedynczy redeem. */
@Component
public class KsefAuthentication {
    private final KSeFClient client;
    private final DefaultCryptographyService crypto;
    private final Clock clock;
    private final String configuredNip;
    private final String token;
    private final boolean testXades;

    public KsefAuthentication(KSeFClient client, @Lazy DefaultCryptographyService crypto, Clock clock,
            @Value("${ainv.ksef.nip:}") String nip,
            @Value("${ainv.ksef.token:}") String token,
            @Value("${ainv.ksef.test-xades:false}") boolean testXades) {
        this.client = client;
        this.crypto = crypto;
        this.clock = clock;
        this.configuredNip = nip;
        this.token = token;
        this.testXades = testXades;
    }

    /** Jawnie skonfigurowany NIP wiąże credential z kontekstem; payload nie nadaje uprawnienia. */
    public Tokens authenticate(String nip) {
        if (!nip.equals(configuredNip) || !nip.matches("[0-9]{10}") || (token.isBlank() && !testXades)) {
            throw KsefFailure.invalid("KSEF_AUTH_CONFIGURATION_MISSING");
        }
        SignatureResponse started;
        try {
            var challenge = client.getAuthChallenge();
            if (!token.isBlank()) {
                var identifier = new ContextIdentifier();
                identifier.setType(ContextIdentifier.IdentifierType.NIP);
                identifier.setValue(nip);
                var encrypted = crypto.encryptKsefTokenUsingPublicKey(token, challenge.getTimestamp());
                started = client.authenticateByKSeFToken(new AuthKsefTokenRequestBuilder()
                        .withChallenge(challenge.getChallenge()).withContextIdentifier(identifier)
                        .withEncryptedToken(Base64.getEncoder().encodeToString(encrypted))
                        .withPublicKeyId(crypto.getKsefToken().getPublicKeyId()).build());
            } else {
                var request = new AuthTokenRequestBuilder().withChallenge(challenge.getChallenge())
                        .withContextNip(nip).withSubjectType(SubjectIdentifierTypeEnum.CERTIFICATE_SUBJECT).build();
                var cert = new DefaultCertificateService().getCompanySeal("AInvoicer TEST", "VATPL-" + nip, "AInvoicer TEST");
                String signed = new DefaultSignatureService().sign(AuthTokenRequestSerializer
                        .authTokenRequestSerializer(request).getBytes(StandardCharsets.UTF_8), cert.certificate(), cert.getPrivateKey());
                started = client.submitAuthTokenRequest(signed, false);
            }
        } catch (Exception e) { throw KsefFailure.from(e, true, "AUTH_START"); }

        // Polling auth jest ograniczony i działa wyłącznie poza transakcją biznesową CAP.
        Instant deadline = clock.instant().plusSeconds(60);
        for (int attempt = 0; attempt < 15 && clock.instant().isBefore(deadline); attempt++) {
            try {
                var status = client.getAuthStatus(started.getReferenceNumber(), started.getAuthenticationToken().getToken());
                int code = status.getStatus().getCode();
                if (code == 200) {
                    if (Boolean.TRUE.equals(status.getIsTokenRedeemed())) {
                        throw KsefFailure.invalid("KSEF_AUTH_ALREADY_REDEEMED");
                    }
                    return redeemOnce(started.getAuthenticationToken().getToken());
                }
                if (code != 100) { throw KsefFailure.invalid("KSEF_AUTH_REJECTED_" + code); }
            } catch (KsefFailure safe) { throw safe; }
            catch (Exception e) {
                KsefFailure failure = KsefFailure.from(e, false, "AUTH_STATUS");
                if (!failure.retryable() || failure.retryNotBefore() != null) { throw failure; }
            }
            pause(Duration.ofMillis(Math.min(4000, 1000L << Math.min(attempt, 2))
                    + ThreadLocalRandom.current().nextLong(250)));
        }
        throw KsefFailure.invalid("KSEF_AUTH_PENDING");
    }

    /** Utrata odpowiedzi redeem pozostawia stan niejednoznaczny; brak retry tej operacji. */
    private Tokens redeemOnce(String temporaryToken) {
        try {
            var result = client.redeemToken(temporaryToken);
            return new Tokens(valid(result.getAccessToken()), valid(result.getRefreshToken()));
        } catch (KsefFailure safe) { throw safe; }
        catch (Exception e) { throw KsefFailure.from(e, true, "REDEEM"); }
    }

    /** Tokeny są obiektami per wykonanie, bez cache singletona i bez serializacji do bazy/logów. */
    public Tokens refreshIfNeeded(Tokens tokens) {
        if (tokens.access.getValidUntil().toInstant().isAfter(clock.instant().plusSeconds(60))) { return tokens; }
        if (!tokens.refresh.getValidUntil().toInstant().isAfter(clock.instant())) {
            throw KsefFailure.invalid("KSEF_REFRESH_EXPIRED");
        }
        return refresh(tokens);
    }

    /** Jawny refresh dla długiej sesji backendu; utrata odpowiedzi nie uruchamia retry. */
    public Tokens refresh(Tokens tokens) {
        try { return new Tokens(valid(client.refreshAccessToken(tokens.refresh.getToken()).getAccessToken()), tokens.refresh); }
        catch (KsefFailure safe) { throw safe; }
        catch (Exception e) { throw KsefFailure.from(e, true, "REFRESH"); }
    }

    private TokenInfo valid(TokenInfo info) {
        if (info == null || info.getToken() == null || info.getToken().isBlank() || info.getValidUntil() == null
                || !info.getValidUntil().toInstant().isAfter(clock.instant())) {
            throw KsefFailure.invalid("KSEF_TOKEN_RESPONSE_INVALID");
        }
        return info;
    }

    private static void pause(Duration delay) {
        try { Thread.sleep(delay); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw KsefFailure.invalid("KSEF_AUTH_INTERRUPTED"); }
    }

    public static final class Tokens {
        private final TokenInfo access;
        private final TokenInfo refresh;
        Tokens(TokenInfo access, TokenInfo refresh) { this.access = access; this.refresh = refresh; }
        String accessToken() { return access.getToken(); }
        public Instant accessValidUntil() { return access.getValidUntil().toInstant(); }
        @Override public String toString() { return "KsefTokens[REDACTED]"; }
    }
}
