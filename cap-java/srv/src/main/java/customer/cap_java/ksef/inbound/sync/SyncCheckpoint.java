package customer.cap_java.ksef.inbound.sync;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Zatwierdzony punkt kontynuacji synchronizacji przyrostowej (data PermanentStorage w UTC).
 *
 * @param scope              zakres checkpointu
 * @param continuationFrom   początek kolejnego okna ({@code dateRange.from}); okna są przyległe
 * @param version            wersja dla optymistycznej kontroli współbieżności (≥ 1)
 * @param committedAt        moment zatwierdzenia
 */
public record SyncCheckpoint(SyncScope scope, OffsetDateTime continuationFrom, long version, Instant committedAt) {
    public SyncCheckpoint {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(continuationFrom, "continuationFrom");
        Objects.requireNonNull(committedAt, "committedAt");
        if (version < 1) {
            throw new IllegalArgumentException("KSEF_INBOUND_CHECKPOINT_VERSION_INVALID");
        }
        continuationFrom = continuationFrom.withOffsetSameInstant(ZoneOffset.UTC);
    }
}
