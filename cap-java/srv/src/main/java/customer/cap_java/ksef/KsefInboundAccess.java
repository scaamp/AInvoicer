package customer.cap_java.ksef;

import java.util.Objects;

import customer.cap_java.ksef.inbound.InboundAccessToken;
import customer.cap_java.ksef.inbound.KsefEnvironment;
import customer.cap_java.ksef.inbound.KsefTaxpayerContext;

/** Adapter istniejącego auth; jedyny kontekst pochodzi z konfiguracji backendu, nigdy z DTO. */
public final class KsefInboundAccess {
    private final KsefAuthentication authentication;
    private final KsefTaxpayerContext trustedContext;

    public KsefInboundAccess(KsefAuthentication authentication, KsefTaxpayerContext trustedContext) {
        this.authentication = Objects.requireNonNull(authentication);
        this.trustedContext = Objects.requireNonNull(trustedContext);
        // Istniejący KsefSdkConfiguration ma wyłącznie endpoint TEST.
        if (trustedContext.environment() != KsefEnvironment.TEST) {
            throw new IllegalArgumentException("KSEF_INBOUND_AUTH_ENVIRONMENT_UNSUPPORTED");
        }
    }

    /** Auth/redeem odbywa się raz na przebieg i nigdy nie jest automatycznie ponawiany po niejednoznaczności. */
    public AccessSession open() { return new AccessSession(authentication.authenticate(trustedContext.nip())); }

    public final class AccessSession {
        private KsefAuthentication.Tokens tokens;
        private AccessSession(KsefAuthentication.Tokens tokens) { this.tokens = tokens; }

        /** Korzysta z refresh istniejącego auth; obiekt per worker, bez współdzielonego cache JWT. */
        public InboundAccessToken token() {
            tokens = authentication.refreshIfNeeded(tokens);
            return InboundAccessToken.issuedFor(trustedContext, tokens.accessToken());
        }

        @Override public String toString() { return "InboundAccessSession[REDACTED]"; }
    }
}
