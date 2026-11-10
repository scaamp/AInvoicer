package customer.cap_java.ksef.inbound.persistence;

import static customer.cap_java.ksef.inbound.persistence.InboundPersistence.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ServiceException;

import customer.cap_java.ksef.inbound.sync.SyncCheckpoint;
import customer.cap_java.ksef.inbound.sync.SyncCheckpointRepository;
import customer.cap_java.ksef.inbound.sync.SyncScope;

/** Lease per SyncScope, trwały cooldown i fencing checkpointu przy utracie przejęcia. */
public final class CqnInboundSyncLeaseRepository {
    public static final Duration MIN_INTERVAL = Duration.ofMinutes(15);
    private final InboundPersistence persistence;
    private final Clock clock;

    public CqnInboundSyncLeaseRepository(InboundPersistence persistence, Clock clock) {
        this.persistence = persistence;
        this.clock = clock;
    }

    public record Claim(SyncScope scope, UUID token) { }

    /** INSERT lub UPDATE pod blokadą wiersza; recovery nie wymaga pamięci poprzedniego procesu. */
    public Optional<Claim> claim(SyncScope scope, Duration lease) {
        if (lease == null || lease.isNegative() || lease.isZero() || lease.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("KSEF_INBOUND_LEASE_INVALID");
        }
        Claim claim = new Claim(scope, UUID.randomUUID());
        try {
            return persistence.tx(() -> {
                var row = persistence.db.run(Select.from(LEASES).matching(key(scope)).lock()).first();
                Instant now = clock.instant();
                if (row.isPresent()) {
                    if (row.get().get("claimToken") != null && instant(row.get().get("leaseUntil")).isAfter(now)
                            || row.get().get("retryNotBefore") != null && instant(row.get().get("retryNotBefore")).isAfter(now)
                            || "BLOCKED".equals(row.get().get("lastCode"))) { return Optional.empty(); }
                }
                var values = new HashMap<String, Object>();
                values.put("claimToken", claim.token().toString());
                values.put("leaseUntil", now.plus(lease));
                values.put("retryNotBefore", now.plus(MIN_INTERVAL));
                values.put("lastCode", "RUNNING");
                values.put("attempts", row.map(r -> ((Number) r.get("attempts")).longValue() + 1).orElse(1L));
                if (row.isEmpty()) {
                    values.putAll(key(scope));
                    persistence.db.run(Insert.into(LEASES).entry(values));
                } else {
                    persistence.db.run(Update.entity(LEASES).matching(key(scope)).data(values));
                }
                return Optional.of(claim);
            });
        } catch (ServiceException error) {
            if (uniqueViolation(error)) { return Optional.empty(); }
            throw error;
        }
    }

    /** Nie utrzymuje transakcji podczas HTTP; utrata lease przerywa dalsze przetwarzanie. */
    public void requireOwned(Claim claim) {
        if (!persistence.tx(() -> ownsLocked(claim))) {
            throw new IllegalStateException("KSEF_INBOUND_LEASE_LOST");
        }
    }

    /** Fencing w jednej transakcji z CAS nie pozwala staremu workerowi zatwierdzić nowego okna. */
    public SyncCheckpointRepository fenced(CqnSyncCheckpointRepository checkpoints, Claim claim) {
        return new SyncCheckpointRepository() {
            public Optional<SyncCheckpoint> find(SyncScope scope) { requireScope(scope); return checkpoints.find(scope); }
            public boolean commit(SyncScope scope, long version, OffsetDateTime next, Instant at) {
                requireScope(scope);
                return checkpoints.commitFenced(scope, version, next, at, () -> ownsLocked(claim));
            }
            private void requireScope(SyncScope scope) {
                if (!claim.scope().equals(scope)) { throw new IllegalArgumentException("KSEF_INBOUND_LEASE_SCOPE_INVALID"); }
            }
        };
    }

    /** Retry-After jest utrwalony niezależnie od HWM; stary token nie zwolni nowego przejęcia. */
    public void finish(Claim claim, Instant retryNotBefore, boolean blocked) {
        persistence.tx(() -> {
            if (!ownsLocked(claim)) { return false; }
            var values = new HashMap<String, Object>();
            values.put("claimToken", null);
            values.put("leaseUntil", null);
            values.put("retryNotBefore", retryNotBefore);
            values.put("lastCode", blocked ? "BLOCKED" : "WAITING");
            persistence.db.run(Update.entity(LEASES).matching(key(claim.scope())).data(values));
            return true;
        });
    }

    private boolean ownsLocked(Claim claim) {
        return persistence.db.run(Select.from(LEASES).matching(key(claim.scope())).lock()).first()
                .filter(r -> claim.token().toString().equals(r.get("claimToken")) && r.get("leaseUntil") != null
                        && instant(r.get("leaseUntil")).isAfter(clock.instant())).isPresent();
    }
}
