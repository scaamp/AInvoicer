package customer.cap_java.ksef.storage;

/**
 * Sklasyfikowany błąd protokołu storage. Komunikat to wyłącznie stały kod; celowo bez cause,
 * ponieważ wyjątki I/O mogą zawierać host, a URL zawiera SAS token.
 */
public final class ObjectStorageException extends RuntimeException {

    public enum Category {
        /** I/O, timeout, 408/429/5xx, uszkodzenie transportu (Content-MD5) — bezpieczne ponowienie. */
        UNAVAILABLE,
        /** 401/403 — brak lub wygaśnięcie uprawnień SAS, rola binding bez wymaganej operacji. */
        ACCESS_DENIED,
        /** Obiekt/odpowiedź przekracza limit. */
        TOO_LARGE,
        /** Treść niezgodna z nagłówkiem integralności dostawcy. */
        CORRUPTED,
        /** Inny 4xx — błąd kontraktu, nie ponawiać. */
        REJECTED
    }

    private final Category category;
    private final long retryAfterSeconds;

    public ObjectStorageException(Category category, String code) {
        this(category, code, -1);
    }

    public ObjectStorageException(Category category, String code, long retryAfterSeconds) {
        super(code, null, false, false);
        this.category = category;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Category category() { return category; }

    /** Wartość nagłówka Retry-After w sekundach albo -1. */
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
