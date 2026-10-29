package customer.cap_java.sap;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.persistence.PersistenceService;

import customer.cap_java.sap.SapKsefResultWriteAdapter.Outcome;

/**
 * Wewnętrzna obsługa operacyjna writebacku: wykrywanie osieroconych i wyczerpanych poleceń oraz
 * kontrolowane wznowienie. Celowo bez endpointu HTTP/OData; to API Java dla zaufanego kodu
 * operacyjnego. Wznowienie ponawia ten sam niezmienny payload i WriteRequestId w SAP — nigdy nie
 * wysyła faktury do KSeF.
 */
@Component
public class SapKsefResultWriteRecovery {
    /** Rozróżnia przyczynę zatrzymania: awarię lub odmowę po stronie SAP, konflikt domenowy, niespójną odpowiedź. */
    public enum FailureKind { NONE, SAP_AVAILABILITY, BUSINESS_CONFLICT, INVALID_RESPONSE }

    public record WriteStatus(UUID writeId, UUID invoiceId, String state, String outcome,
            FailureKind failureKind, int attempts, Instant lastAttemptAt, Instant retryNotBefore,
            Instant leaseUntil, boolean orphaned, boolean resumable) { }

    private final PersistenceService db;
    private final SapKsefResultWriteback writeback;
    private final Clock clock;
    private final Duration staleAfter;

    public SapKsefResultWriteRecovery(PersistenceService db, SapKsefResultWriteback writeback, Clock clock,
            @Value("${ainv.sap-write.stale-after:PT10M}") Duration staleAfter) {
        this.db = db;
        this.writeback = writeback;
        this.clock = clock;
        this.staleAfter = staleAfter;
    }

    /** Klasyfikacja wyłącznie z bezpiecznego kodu; nie wymaga ani nie ujawnia treści odpowiedzi SAP. */
    static FailureKind classify(String outcome) {
        if (outcome == null || Outcome.APPLIED.name().equals(outcome)
                || Outcome.ALREADY_APPLIED.name().equals(outcome)) {
            return FailureKind.NONE;
        }
        for (Outcome known : Outcome.values()) {
            if (known.name().equals(outcome)) {
                return FailureKind.BUSINESS_CONFLICT;
            }
        }
        if ("SAP_INVALID_RESULT".equals(outcome)) {
            return FailureKind.INVALID_RESPONSE;
        }
        return FailureKind.SAP_AVAILABILITY;
    }

    /** Polecenia wymagające uwagi operatora: zatrzymane, wyczerpane, osierocone (PROCESSING po terminie, PENDING bez postępu). */
    public List<WriteStatus> needingAttention() {
        return writeback.inNewTransaction(() -> {
            List<WriteStatus> result = new ArrayList<>();
            var rows = db.run(Select.from(SapKsefResultWriteback.WRITES).where(w -> w.get("state").in(
                    SapKsefResultWriteback.PENDING, SapKsefResultWriteback.PROCESSING,
                    SapKsefResultWriteback.RETRY_EXHAUSTED, SapKsefResultWriteback.RECONCILIATION_REQUIRED))).list();
            Instant now = clock.instant();
            for (var row : rows) {
                var status = toStatus(row, now);
                boolean active = SapKsefResultWriteback.PENDING.equals(status.state())
                        || SapKsefResultWriteback.PROCESSING.equals(status.state());
                if (!active || status.orphaned()) {
                    result.add(status);
                }
            }
            return result;
        });
    }

    public WriteStatus status(UUID writeId) {
        return writeback.inNewTransaction(() -> toStatus(db.run(Select.from(SapKsefResultWriteback.WRITES)
                .byId(writeId.toString())).first().orElseThrow(() -> new IllegalArgumentException("WRITE_NOT_FOUND")),
                clock.instant()));
    }

    /**
     * Kontrolowane wznowienie po usunięciu przyczyny. Dozwolone wyłącznie dla przyczyn po stronie SAP
     * (dostępność, odmowa HTTP, wyłączony transport), wyczerpanych prób i osieroconych poleceń.
     * Konflikt domenowy i niespójna odpowiedź wymagają rekoncyliacji, a nie ponowienia. Wznowienie
     * przed terminem Retry-After jest odrzucane. {@code reasonCode} to krótki kod operatora, nie dane osobowe.
     */
    public void resume(UUID writeId, String reasonCode) {
        if (reasonCode == null || !reasonCode.matches("[A-Z0-9_]{1,30}")) {
            throw new IllegalArgumentException("RESUME_REASON_INVALID");
        }
        writeback.inNewTransaction(() -> {
            var row = db.run(Select.from(SapKsefResultWriteback.WRITES).byId(writeId.toString())).first()
                    .orElseThrow(() -> new IllegalArgumentException("WRITE_NOT_FOUND"));
            Instant now = clock.instant();
            var status = toStatus(row, now);
            if (!status.resumable()) {
                Instant notBefore = status.retryNotBefore();
                throw new IllegalStateException(notBefore != null && notBefore.isAfter(now)
                        ? "RESUME_TOO_EARLY" : "RESUME_NOT_ALLOWED");
            }
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("state", SapKsefResultWriteback.PENDING);
            change.put("cycleAttempts", 0);
            change.put("claimToken", null);
            change.put("leaseUntil", null);
            change.put("retryNotBefore", null);
            change.put("stateChangedAt", now);
            // CAS na stanie i liczniku prób: równoległy worker lub drugi operator nie zostaną nadpisani.
            int attempts = status.attempts();
            String state = status.state();
            long updated = db.run(Update.entity(SapKsefResultWriteback.WRITES).data(change).where(w ->
                    w.get("ID").eq(writeId.toString()).and(w.get("state").eq(state))
                            .and(w.get("attempts").eq(attempts)))).rowCount();
            if (updated != 1) {
                throw new IllegalStateException("RESUME_CONFLICT");
            }
            writeback.history(writeId, "RESUMED:" + reasonCode, attempts);
            // Nowa wiadomość z tym samym writeId; stara (jeśli jeszcze istnieje) jest bezpieczna dzięki dzierżawie.
            writeback.publish(writeId);
            return null;
        });
    }

    private WriteStatus toStatus(Map<String, Object> row, Instant now) {
        String state = (String) row.get("state");
        String outcome = (String) row.get("outcome");
        Instant retryNotBefore = (Instant) row.get("retryNotBefore");
        Instant leaseUntil = (Instant) row.get("leaseUntil");
        Instant changedAt = (Instant) row.get("stateChangedAt");
        FailureKind kind = classify(outcome);
        boolean orphaned = (SapKsefResultWriteback.PROCESSING.equals(state)
                && leaseUntil != null && !leaseUntil.isAfter(now))
                || (SapKsefResultWriteback.PENDING.equals(state) && changedAt != null
                && changedAt.plus(staleAfter).isBefore(now));
        boolean allowedState = SapKsefResultWriteback.RETRY_EXHAUSTED.equals(state)
                || (SapKsefResultWriteback.RECONCILIATION_REQUIRED.equals(state)
                        && kind == FailureKind.SAP_AVAILABILITY)
                || orphaned;
        boolean resumable = allowedState && (retryNotBefore == null || !retryNotBefore.isAfter(now));
        return new WriteStatus(UUID.fromString((String) row.get("ID")),
                UUID.fromString((String) row.get("invoiceUUID")), state, outcome, kind,
                (Integer) row.get("attempts"), (Instant) row.get("lastAttemptAt"), retryNotBefore, leaseUntil,
                orphaned, resumable);
    }
}
