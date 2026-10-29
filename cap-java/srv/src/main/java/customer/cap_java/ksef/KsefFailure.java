package customer.cap_java.ksef;

import java.time.Instant;
import pl.akmf.ksef.sdk.client.model.ApiException;

/** Wyjątek aplikacyjny bez body, tokenu, adresu podpisanego ani cause SDK. */
public final class KsefFailure extends RuntimeException {
    private final boolean ambiguous;
    private final boolean retryable;
    private final Instant retryNotBefore;

    public KsefFailure(String code, boolean ambiguous, boolean retryable, Instant retryNotBefore) {
        super(code);
        this.ambiguous = ambiguous;
        this.retryable = retryable;
        this.retryNotBefore = retryNotBefore;
    }

    public boolean ambiguous() { return ambiguous; }
    public boolean retryable() { return retryable; }
    public Instant retryNotBefore() { return retryNotBefore; }

    /** POST o nieznanym wyniku nigdy nie staje się automatycznie retryable. */
    static KsefFailure from(Exception error, boolean effect, String stage) {
        int http = error instanceof ApiException api ? api.getCode() : 0;
        Instant notBefore = null;
        if (error instanceof ApiException api && api.getResponseHeaders() != null) {
            String value = api.getResponseHeaders().firstValue("Retry-After").orElse(null);
            if (value != null) {
                try { notBefore = Instant.now().plusSeconds(Math.max(0, Long.parseLong(value))); }
                catch (RuntimeException e) {
                    try { notBefore = java.time.ZonedDateTime.parse(value,
                            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
                    catch (RuntimeException ignored) { notBefore = Instant.now().plusSeconds(60); }
                }
            }
        }
        boolean transientError = http == 0 || http == 429 || http >= 500;
        return new KsefFailure("KSEF_" + stage + (http == 0 ? "_IO" : "_HTTP_" + http),
                effect && transientError, !effect && transientError, notBefore);
    }

    static KsefFailure invalid(String code) { return new KsefFailure(code, false, false, null); }
}
