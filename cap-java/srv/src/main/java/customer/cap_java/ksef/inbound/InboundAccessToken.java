package customer.cap_java.ksef.inbound;

import java.util.Objects;

/**
 * Access token KSeF (JWT) związany z dokładnie jednym {@link KsefTaxpayerContext}.
 *
 * <p>Moduł INBOUND nie uwierzytelnia się sam i nie implementuje JWT: token dostarcza istniejący
 * {@code KsefAuthentication} (challenge → token KSeF/XAdES → redeem) dla NIP tego kontekstu.
 * Wartość jest dostępna tylko w pakiecie adaptera, nie ma {@code equals}/{@code hashCode} po wartości,
 * a {@link #toString()} jest zredagowany — token nie trafia do logów, wyjątków, bazy ani UI5.
 * {@link KsefInboundClient} odrzuca token wydany dla innego kontekstu ({@code SCOPE_MISMATCH}).
 */
public final class InboundAccessToken {
    private final KsefTaxpayerContext context;
    private final String value;

    private InboundAccessToken(KsefTaxpayerContext context, String value) {
        this.context = context;
        this.value = value;
    }

    /**
     * Wiąże JWT uzyskany przez backend z kontekstem, dla którego został wydany.
     * Wywołuj wyłącznie z kodu, który sam uzyskał token dla {@code context.nip()}.
     */
    public static InboundAccessToken issuedFor(KsefTaxpayerContext context, String jwt) {
        Objects.requireNonNull(context, "context");
        if (jwt == null || jwt.isBlank()) {
            throw new IllegalArgumentException("KSEF_INBOUND_TOKEN_MISSING");
        }
        return new InboundAccessToken(context, jwt);
    }

    public KsefTaxpayerContext context() {
        return context;
    }

    /** Wyłącznie dla adaptera SDK w tym pakiecie. */
    String value() {
        return value;
    }

    @Override
    public String toString() {
        return "InboundAccessToken[REDACTED]";
    }
}
