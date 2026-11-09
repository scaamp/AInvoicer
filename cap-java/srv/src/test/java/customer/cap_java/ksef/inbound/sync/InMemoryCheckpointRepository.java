package customer.cap_java.ksef.inbound.sync;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WYŁĄCZNIE TESTOWA implementacja kontraktu {@link SyncCheckpointRepository} (compare-and-set w pamięci).
 * Nie jest trwała i nie dowodzi poprawności w wielu instancjach CF — to zadanie KSEF-IN-02 (HANA/HDI).
 */
final class InMemoryCheckpointRepository implements SyncCheckpointRepository {
    private final Map<SyncScope, SyncCheckpoint> store = new ConcurrentHashMap<>();
    int commits;

    @Override
    public Optional<SyncCheckpoint> find(SyncScope scope) {
        return Optional.ofNullable(store.get(scope));
    }

    @Override
    public synchronized boolean commit(SyncScope scope, long expectedVersion, OffsetDateTime continuationFrom,
            Instant committedAt) {
        SyncCheckpoint current = store.get(scope);
        long currentVersion = current == null ? 0 : current.version();
        if (currentVersion != expectedVersion) {
            return false;
        }
        if (current != null && continuationFrom.isBefore(current.continuationFrom())) {
            throw new IllegalStateException("checkpoint regression");
        }
        store.put(scope, new SyncCheckpoint(scope, continuationFrom, currentVersion + 1, committedAt));
        commits++;
        return true;
    }

    /** Symulacja zatwierdzenia przez inną instancję. */
    void forceExternalCommit(SyncScope scope, OffsetDateTime continuationFrom) {
        SyncCheckpoint current = store.get(scope);
        long version = current == null ? 1 : current.version() + 1;
        store.put(scope, new SyncCheckpoint(scope, continuationFrom, version, Instant.EPOCH));
    }
}
