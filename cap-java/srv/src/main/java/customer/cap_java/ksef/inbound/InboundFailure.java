package customer.cap_java.ksef.inbound;

import java.time.Instant;
import java.util.Objects;

/**
 * Sklasyfikowany błąd odczytu INBOUND. Komunikat zawiera wyłącznie kod kategorii, status HTTP i kod MF —
 * nigdy body odpowiedzi, URL, numer KSeF, NIP ani token. Przyczyna z SDK nie jest dołączana, bo jej
 * komunikat ({@code ApiException#getMessage}) zawiera treść odpowiedzi serwera.
 *
 * <p>{@link Category#transientRead()} mówi tylko, że <i>bezpieczny odczyt</i> można powtórzyć później
 * (odczyty KSeF nie mają efektów ubocznych). Nie oznacza automatycznego retry: decyduje
 * {@link ReadRetryPolicy} (ograniczona liczba prób) albo przyszły trwały harmonogram.
 */
public final class InboundFailure extends RuntimeException {
    public enum Category {
        /** HTTP 401 — brak/wygasły token; wymagane ponowne uwierzytelnienie, nie retry. */
        AUTHENTICATION(false),
        /** HTTP 403 — brak uprawnienia (np. InvoiceRead); nie oznacza, że faktura nie istnieje. */
        AUTHORIZATION(false),
        /**
         * HTTP 400 + kod MF 21164 (lub defensywnie 404) — faktury nie znaleziono <i>w tym kontekście</i>.
         * Nie wolno z tego wnioskować o globalnym nieistnieniu dokumentu.
         */
        NOT_FOUND_IN_CONTEXT(false),
        /** HTTP 400 + kod MF 21165 — faktura przetworzona, ale jeszcze niedostępna do pobrania. */
        NOT_YET_AVAILABLE(true),
        /** HTTP 429 — limit API; {@link #retryNotBefore()} z nagłówka Retry-After. */
        RATE_LIMITED(true),
        /** HTTP 5xx. */
        SERVER_ERROR(true),
        /** Timeout żądania HTTP (HttpTimeoutException). */
        TIMEOUT(true),
        /** Inny błąd I/O połączenia. */
        NETWORK(true),
        /** Przerwanie wątku; flaga przerwania jest przywracana. */
        INTERRUPTED(false),
        /** HTTP 3xx — klient nie podąża za przekierowaniami (brak przejścia na niezaufany host). */
        UNEXPECTED_REDIRECT(false),
        /** HTTP 400 z innym kodem MF (np. 21405 walidacja, 21183 zakres poza HWM) albo lokalnie błędny parametr. */
        INVALID_REQUEST(false),
        /** Odpowiedź nieczytelna, niezgodna z kontraktem albo wewnętrznie sprzeczna. */
        CORRUPT_RESPONSE(false),
        /** Dokument przekracza skonfigurowany limit rozmiaru. */
        PAYLOAD_TOO_LARGE(false),
        /** Skrót SHA-256 bajtów lub dane XML niezgodne z metadanymi KSeF; ten sam numer KSeF, inna treść. */
        INTEGRITY_MISMATCH(false),
        /** Token/kontekst/środowisko/rola nie pasują do siebie albo odpowiedź dotyczy innego podmiotu. */
        SCOPE_MISMATCH(false),
        /** Inny status HTTP. */
        UNEXPECTED_STATUS(false),
        /** Nieznany typ błędu — fail closed, bez retry. */
        UNCLASSIFIED(false);

        private final boolean transientRead;

        Category(boolean transientRead) {
            this.transientRead = transientRead;
        }

        public boolean transientRead() {
            return transientRead;
        }
    }

    private final Category category;
    private final int httpStatus;
    private final Integer mfExceptionCode;
    private final Instant retryNotBefore;

    InboundFailure(Category category, int httpStatus, Integer mfExceptionCode, Instant retryNotBefore, String detail) {
        super(message(category, httpStatus, mfExceptionCode, detail), null, false, true);
        this.category = Objects.requireNonNull(category);
        this.httpStatus = httpStatus;
        this.mfExceptionCode = mfExceptionCode;
        this.retryNotBefore = retryNotBefore;
    }

    static InboundFailure of(Category category, String detail) {
        return new InboundFailure(category, 0, null, null, detail);
    }

    public Category category() {
        return category;
    }

    /** 0, gdy błąd nie pochodzi z odpowiedzi HTTP. */
    public int httpStatus() {
        return httpStatus;
    }

    /** Kod {@code exceptionCode} MF (np. 21164), jeśli odpowiedź go zawierała. */
    public Integer mfExceptionCode() {
        return mfExceptionCode;
    }

    /** Najwcześniejszy moment kolejnego odczytu wg Retry-After; {@code null}, gdy brak wskazania. */
    public Instant retryNotBefore() {
        return retryNotBefore;
    }

    public boolean transientRead() {
        return category.transientRead();
    }

    private static String message(Category category, int http, Integer mfCode, String detail) {
        var text = new StringBuilder("KSEF_INBOUND_").append(category.name());
        if (http > 0) {
            text.append("_HTTP_").append(http);
        }
        if (mfCode != null) {
            text.append("_MF_").append(mfCode);
        }
        if (detail != null && !detail.isBlank()) {
            text.append('_').append(detail);
        }
        return text.toString();
    }
}
