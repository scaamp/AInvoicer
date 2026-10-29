package customer.cap_java.ksef;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.RequestContext;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;
import customer.cap_java.sap.SapKsefResultWriteback;

/**
 * Most „KSeF ACCEPTED → zlecenie SAP writeback” (ADR-0005, decyzja 6).
 *
 * <p>Kolejność gwarantuje, że awaria writebacku nie cofnie przyjęcia: worker najpierw commituje
 * checkpoint ACCEPTED (osobna transakcja), dopiero potem {@link #request(UUID)} w NOWEJ transakcji
 * tworzy {@code SapResultWrites} + wiadomość outbox. Wyjątek enqueue jest przechwytywany i zapisany
 * w historii zlecenia jako bezpieczny kod — ACCEPTED, numer KSeF i dalsze pobranie UPO pozostają.
 * Okno między commitami (awaria procesu, błąd DB) domyka {@link #sweep()} przy starcie i okresowo:
 * ACCEPTED bez {@code SapResultWrites} → idempotentne enqueue (ten sam WriteRequestId = ID operacji).
 * Nie wywołuje KSeF ani HTTP SAP; wyłącznie operacje ze zweryfikowanym powiązaniem z RAP
 * (migawka {@code source = SAP_RAP}) dostają writeback — syntetyczne fixture SDK są pomijane.
 */
@Component
public class KsefAcceptedWriteback {
    public enum Outcome { QUEUED, NOT_ELIGIBLE, FAILED }

    static final String CODE_NOT_ELIGIBLE = "SAP_WRITEBACK_NOT_ELIGIBLE";
    static final String CODE_FAILED = "SAP_WRITEBACK_ENQUEUE_FAILED:";
    private static final String STATE = "SAP_WRITEBACK";
    private static final Logger LOG = LoggerFactory.getLogger(KsefAcceptedWriteback.class);
    private final PersistenceService db;
    private final CdsRuntime runtime;
    private final SapKsefResultWriteback writeback;
    private final Clock clock;
    private final boolean sweepEnabled;
    private final Duration sweepInterval;
    private final int batchSize;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("ksef-writeback-sweeper").factory());

    public KsefAcceptedWriteback(PersistenceService db, CdsRuntime runtime, SapKsefResultWriteback writeback,
            Clock clock,
            @Value("${ainv.sap-write.sweeper.enabled:true}") boolean sweepEnabled,
            @Value("${ainv.sap-write.sweeper.interval:PT5M}") Duration sweepInterval,
            @Value("${ainv.sap-write.sweeper.batch-size:50}") int batchSize) {
        this.db = db;
        this.runtime = runtime;
        this.writeback = writeback;
        this.clock = clock;
        this.sweepEnabled = sweepEnabled;
        this.sweepInterval = sweepInterval;
        this.batchSize = batchSize;
    }

    /**
     * Zleca writeback potwierdzonej operacji. Nigdy nie rzuca wyjątku do wywołującego (worker KSeF):
     * błąd zostaje w historii i zostanie ponowiony przez sweeper. Wywołanie wielokrotne jest no-op.
     */
    public Outcome request(UUID operationId) {
        try {
            if (!tx(() -> eligible(operationId))) {
                tx(() -> recordOnce(operationId, CODE_NOT_ELIGIBLE));
                return Outcome.NOT_ELIGIBLE;
            }
            tx(() -> writeback.enqueue(operationId));
            return Outcome.QUEUED;
        } catch (RuntimeException failure) {
            try {
                // Wyścig dwóch instancji (PK SapResultWrites) kończy się zleceniem drugiej — to sukces.
                if (tx(() -> writeExists(operationId))) {
                    return Outcome.QUEUED;
                }
                tx(() -> {
                    recordOnce(operationId, CODE_FAILED + safeCode(failure));
                    // Rotacja partii: trwale wadliwe pierwsze zlecenia nie mogą zagłodzić kolejnych.
                    // Zmieniamy wyłącznie czas technicznego sprawdzenia, nigdy dowód ACCEPTED.
                    db.run(Update.entity(KsefSubmissionWorker.OPERATIONS).byId(operationId.toString())
                            .data("modifiedAt", clock.instant()));
                    return null;
                });
            } catch (RuntimeException unavailable) {
                // DB niedostępna: sweeper ponowi; w logu tylko kod, bez treści wyjątku.
                LOG.warn("SAP_WRITEBACK_REQUEST_NOT_RECORDED");
            }
            return Outcome.FAILED;
        }
    }

    /**
     * Odtwarza brakujące zlecenia: ACCEPTED (SEND_INVOICE/OUTBOUND/TEST) z migawką SAP_RAP
     * i bez {@code SapResultWrites}. Ograniczony rozmiar partii; zwraca liczbę nowych zleceń.
     */
    public int sweep() {
        List<UUID> candidates = tx(() -> db.run(Select.from(KsefSubmissionWorker.OPERATIONS).columns("ID")
                .where(o -> o.get("status").eq("ACCEPTED").and(o.get("operationType").eq("SEND_INVOICE"))
                        .and(o.get("direction").eq("OUTBOUND")).and(o.get("environment").eq("TEST"))
                        .and(o.to("snapshot").get("source").eq(KsefInvoiceSnapshotRegistry.SOURCE_SAP_RAP))
                        .and(o.to("writeback").get("ID").isNull()))
                .orderBy(o -> o.get("modifiedAt").asc()).limit(batchSize)).list().stream()
                .map(row -> UUID.fromString((String) row.get("ID"))).toList());
        int queued = 0;
        for (UUID id : candidates) {
            if (request(id) == Outcome.QUEUED) {
                queued++;
            }
        }
        return queued;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startSweeper() {
        if (!sweepEnabled) {
            return;
        }
        scheduler.scheduleWithFixedDelay(this::sweepSafely, 0, sweepInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy void stopSweeper() { scheduler.shutdownNow(); }

    private void sweepSafely() {
        try {
            int queued = sweep();
            if (queued > 0) {
                LOG.info("SAP_WRITEBACK_SWEEP_QUEUED {}", queued);
            }
        } catch (RuntimeException failure) {
            LOG.warn("SAP_WRITEBACK_SWEEP_FAILED");
        }
    }

    private boolean eligible(UUID operationId) {
        var operation = db.run(Select.from(KsefSubmissionWorker.OPERATIONS).byId(operationId.toString())).first();
        if (operation.isEmpty() || operation.get().get("snapshot_ID") == null) {
            return false;
        }
        var op = operation.get();
        if (!"ACCEPTED".equals(op.get("status")) || !"SEND_INVOICE".equals(op.get("operationType"))
                || !"OUTBOUND".equals(op.get("direction")) || !"TEST".equals(op.get("environment"))) {
            return false;
        }
        return db.run(Select.from(KsefSubmissionWorker.SNAPSHOTS).columns("source").byId(op.get("snapshot_ID")))
                .first().map(s -> KsefInvoiceSnapshotRegistry.SOURCE_SAP_RAP.equals(s.get("source"))).orElse(false);
    }

    private boolean writeExists(UUID operationId) {
        return db.run(Select.from("customer.cap_java.SapResultWrites").columns("ID").byId(operationId.toString()))
                .first().isPresent();
    }

    /** Jeden wpis historii na kod — sweeper nie zalewa historii powtarzanym tym samym błędem. */
    private Void recordOnce(UUID operationId, String code) {
        boolean known = db.run(Select.from(KsefSubmissionWorker.HISTORY).columns("ID").where(h -> h.get("submissionId")
                .eq(operationId.toString()).and(h.get("code").eq(code))).limit(1)).first().isPresent();
        if (!known) {
            var row = new LinkedHashMap<String, Object>();
            row.put("ID", UUID.randomUUID().toString());
            row.put("submissionId", operationId.toString());
            row.put("occurredAt", clock.instant());
            row.put("state", STATE);
            row.put("code", code);
            db.run(Insert.into(KsefSubmissionWorker.HISTORY).entry(row));
        }
        return null;
    }

    /** Kody domenowe enqueue (np. KSEF_NUMBER_ALREADY_ASSIGNED) są bezpieczne; resztę maskujemy. */
    static String safeCode(RuntimeException failure) {
        String message = failure.getMessage();
        return message != null && message.matches("[A-Z0-9_]{1,60}") ? message : "UNEXPECTED";
    }

    <T> T tx(Supplier<T> action) {
        return runtime.requestContext().systemUser().run((RequestContext ctx) ->
                runtime.changeSetContext().run(cs -> { return action.get(); }));
    }
}
