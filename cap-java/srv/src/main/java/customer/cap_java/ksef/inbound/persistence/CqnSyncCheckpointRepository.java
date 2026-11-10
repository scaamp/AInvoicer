package customer.cap_java.ksef.inbound.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ServiceException;

import customer.cap_java.ksef.inbound.sync.SyncCheckpoint;
import customer.cap_java.ksef.inbound.sync.SyncCheckpointRepository;
import customer.cap_java.ksef.inbound.sync.SyncScope;

/** CQN CAS po wersji i monotonicznej dacie, bez odczytu-zapisu niezabezpieczonego predykatem. */
public final class CqnSyncCheckpointRepository implements SyncCheckpointRepository {
    private final InboundPersistence persistence;

    public CqnSyncCheckpointRepository(InboundPersistence persistence) { this.persistence = persistence; }

    @Override public Optional<SyncCheckpoint> find(SyncScope scope) {
        return persistence.tx(() -> persistence.db.run(Select.from(InboundPersistence.CHECKPOINTS)
                .matching(InboundPersistence.key(scope))).first().map(row -> new SyncCheckpoint(scope,
                        InboundPersistence.instant(row.get("continuationFrom")).atOffset(ZoneOffset.UTC),
                        ((Number) row.get("version")).longValue(), InboundPersistence.instant(row.get("committedAt")))));
    }

    /** INSERT wersji 1 lub atomowy UPDATE; cofnięcie/równy HWM i przegrany CAS zwracają false. */
    @Override public boolean commit(SyncScope scope, long expectedVersion, OffsetDateTime continuationFrom,
            Instant committedAt) {
        return commitFenced(scope, expectedVersion, continuationFrom, committedAt, () -> true);
    }

    /** Lease jest blokowany i sprawdzany w tej samej transakcji co CAS checkpointu. */
    boolean commitFenced(SyncScope scope, long expectedVersion, OffsetDateTime continuationFrom,
            Instant committedAt, BooleanSupplier fence) {
        Objects.requireNonNull(continuationFrom);
        Objects.requireNonNull(committedAt);
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE) {
            throw new IllegalArgumentException("KSEF_INBOUND_CHECKPOINT_VERSION_INVALID");
        }
        // Stała długość UTC zachowuje również porządek leksykograficzny predykatu CQN lt.
        String next = new java.time.format.DateTimeFormatterBuilder().appendInstant(9).toFormatter()
                .format(continuationFrom.toInstant());
        try {
            return persistence.tx(() -> {
                if (!fence.getAsBoolean()) { return false; }
                Map<String, Object> values = Map.of("continuationFrom", next, "version", expectedVersion + 1,
                        "committedAt", committedAt);
                if (expectedVersion == 0) {
                    var row = new HashMap<>(InboundPersistence.key(scope));
                    row.putAll(values);
                    persistence.db.run(Insert.into(InboundPersistence.CHECKPOINTS).entry(row));
                    return true;
                }
                return persistence.db.run(Update.entity(InboundPersistence.CHECKPOINTS).data(values).where(c ->
                        c.get("environment").eq(scope.environment().name()).and(c.get("contextNip").eq(scope.contextNip()))
                        .and(c.get("subjectRole").eq(scope.role().name())).and(c.get("version").eq(expectedVersion))
                        .and(c.get("continuationFrom").lt(next)))).rowCount() == 1;
            });
        } catch (ServiceException error) {
            if (expectedVersion == 0 && InboundPersistence.uniqueViolation(error)) { return false; }
            throw error;
        }
    }
}
