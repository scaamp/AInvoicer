package customer.cap_java.ksef.inbound;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Kontrolowane ponawianie <b>wyłącznie bezpiecznych odczytów</b> KSeF (metadane, pobranie XML).
 *
 * <p>Reguły: ponawiane są tylko kategorie {@link InboundFailure.Category#transientRead()} (429, 5xx, timeout,
 * błąd sieci, 21165 „jeszcze niedostępna”); liczba prób jest ograniczona; przerwa to Retry-After albo
 * wykładniczy backoff z jitterem. Jeśli wymagana przerwa przekracza {@code maxInlineWait} (typowe dla 429
 * przy limicie 20 req/h na metadane), polityka <b>nie czeka w wątku</b> — zwraca błąd z
 * {@code retryNotBefore}, aby trwały harmonogram (KSEF-IN-02) wznowił odczyt później. 401/403/400/3xx
 * i błędy kontraktu nigdy nie są ponawiane.
 */
public final class ReadRetryPolicy {
    /** Abstrakcja oczekiwania — testy nie śpią naprawdę. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final long MAX_JITTER_MILLIS = 250;

    private final int maxAttempts;
    private final Duration baseBackoff;
    private final Duration maxInlineWait;
    private final Clock clock;
    private final Sleeper sleeper;

    public ReadRetryPolicy(int maxAttempts, Duration baseBackoff, Duration maxInlineWait, Clock clock, Sleeper sleeper) {
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("KSEF_INBOUND_RETRY_ATTEMPTS_INVALID");
        }
        this.maxAttempts = maxAttempts;
        this.baseBackoff = requirePositive(baseBackoff);
        this.maxInlineWait = requirePositive(maxInlineWait);
        this.clock = Objects.requireNonNull(clock);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    /** 3 próby, backoff 1 s/2 s (+jitter ≤ 250 ms), czekanie w wątku maksymalnie 10 s. */
    public static ReadRetryPolicy defaults(Clock clock) {
        return new ReadRetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(10), clock, Thread::sleep);
    }

    /** Pojedyncza próba — do testów i miejsc, gdzie ponawianie zapewnia wyłącznie harmonogram. */
    public static ReadRetryPolicy noRetry(Clock clock) {
        return new ReadRetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(1), clock, d -> { });
    }

    public <T> T execute(Supplier<T> read) {
        for (int attempt = 1; ; attempt++) {
            try {
                return read.get();
            } catch (InboundFailure failure) {
                if (!failure.transientRead() || attempt >= maxAttempts) {
                    throw failure;
                }
                Duration wait = waitBefore(failure, attempt);
                if (wait.compareTo(maxInlineWait) > 0) {
                    throw failure;
                }
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw InboundFailure.of(InboundFailure.Category.INTERRUPTED, null);
                }
            }
        }
    }

    private Duration waitBefore(InboundFailure failure, int attempt) {
        Instant notBefore = failure.retryNotBefore();
        if (notBefore != null) {
            Duration remaining = Duration.between(clock.instant(), notBefore);
            return remaining.isNegative() ? Duration.ZERO : remaining;
        }
        long jitter = ThreadLocalRandom.current().nextLong(MAX_JITTER_MILLIS + 1);
        return baseBackoff.multipliedBy(1L << (attempt - 1)).plusMillis(jitter);
    }

    private static Duration requirePositive(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("KSEF_INBOUND_RETRY_DURATION_INVALID");
        }
        return value;
    }
}
