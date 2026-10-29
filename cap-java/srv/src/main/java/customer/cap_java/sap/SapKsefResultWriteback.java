package customer.cap_java.sap;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.changeset.ChangeSetContext;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.outbox.OutboxMessage;
import com.sap.cds.services.outbox.OutboxMessageEventContext;
import com.sap.cds.services.outbox.OutboxService;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.RequestContext;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.sap.SapKsefResultWriteAdapter.WriteFailure;
import customer.cap_java.sap.SapKsefResultWriteAdapter.WriteResult;

/**
 * Trwałe zlecenie i worker writeback potwierdzonej operacji; bez publicznego endpointu.
 *
 * <p>Przetwarzanie ma trzy fazy w oddzielnych transakcjach: (A) atomowe przejęcie z dzierżawą,
 * (B) wywołanie HTTP SAP bez żadnej blokady wiersza zlecenia, (C) finalizacja z kontrolą tokenu.
 * Wywołanie wtórne jest bezpieczne, bo SAP rozpoznaje ten sam WriteRequestId (ALREADY_APPLIED);
 * nigdy nie dotyka KSeF.
 */
@Component
public class SapKsefResultWriteback implements EventHandler {
    static final String WRITES = "customer.cap_java.SapResultWrites";
    static final String HISTORY = "customer.cap_java.SapResultWriteHistory";
    static final String SNAPSHOTS = "customer.cap_java.KsefInvoiceSnapshots";
    static final String PENDING = "PENDING";
    static final String PROCESSING = "PROCESSING";
    static final String SUCCEEDED = "SUCCEEDED";
    static final String RECONCILIATION_REQUIRED = "RECONCILIATION_REQUIRED";
    static final String RETRY_EXHAUSTED = "RETRY_EXHAUSTED";
    private static final String OPERATIONS = "customer.cap_java.KsefOperations";
    private static final String INVOICES = "customer.cap_java.KsefInvoices";
    private static final String EVENT = "applyConfirmedResult";
    private final PersistenceService db;
    private final OutboxService outbox;
    private final CdsRuntime runtime;
    private final SapKsefResultWriteAdapter adapter;
    private final Clock clock;
    private final Duration lease;
    private final int maxAttempts;
    private final Duration renewInterval;
    private final ScheduledExecutorService renewals = Executors.newScheduledThreadPool(2,
            Thread.ofPlatform().daemon(true).name("sap-lease-", 0).factory());

    public SapKsefResultWriteback(PersistenceService db,
            @Qualifier("SapResultOutbox") OutboxService outbox,
            CdsRuntime runtime, SapKsefResultWriteAdapter adapter, Clock clock,
            @Value("${ainv.sap-write.lease:PT2M}") Duration lease,
            @Value("${ainv.sap-write.max-attempts:5}") int maxAttempts,
            @Value("${ainv.sap-write.renew-interval:PT20S}") Duration renewInterval) {
        this.db = db;
        this.outbox = outbox;
        this.runtime = runtime;
        this.adapter = adapter;
        this.clock = clock;
        this.lease = lease;
        this.maxAttempts = maxAttempts;
        if (renewInterval.toMillis() <= 0 || renewInterval.multipliedBy(3).compareTo(lease) > 0) {
            throw new IllegalArgumentException("SAP_LEASE_RENEW_INTERVAL_INVALID");
        }
        this.renewInterval = renewInterval;
    }

    /** W H2/single tenant CAP 5.2 wymaga bodźca collectora po cichym restarcie.
     * Publikujemy pusty sygnał przez publiczne API; stare polecenia zachowują payload i ID.
     * PROCESSING obejmuje przejęcie przerwane restartem (po wygaśnięciu dzierżawy zostanie odzyskane). */
    @EventListener(ApplicationReadyEvent.class)
    public void resumePendingWrites() {
        inNewTransaction(() -> {
            if (!db.run(Select.from(WRITES).columns("ID")
                    .where(r -> r.get("state").in(PENDING, PROCESSING)).limit(1)).list().isEmpty()) {
                outbox.submit("wakeCollector", OutboxMessage.create());
            }
            return null;
        });
    }

    @On(service = "SapResultOutbox", event = "wakeCollector")
    public void wakeCollector(OutboxMessageEventContext context) {
        if (Boolean.TRUE.equals(context.getIsInbound())) {
            context.setCompleted();
        }
    }

    /**
     * Zaufany backend wywołuje w nowej transakcji PO commit potwierdzenia KSeF (KsefAcceptedWriteback).
     * Operacja musi mieć ACCEPTED, SEND_INVOICE, OUTBOUND i numer KSeF; NEW jest odrzucane.
     * Oczekiwana wersja i spółka pochodzą wyłącznie z niezmiennej migawki dokumentu wysłanego
     * do KSeF, a skrót XML potwierdzony przy akceptacji musi być identyczny ze skrótem migawki.
     * Nie wykonuje GET SAP ani żadnego HTTP.
     */
    public UUID enqueue(UUID operationId) {
        if (!RequestContext.isActive() || !ChangeSetContext.isActive()
                || !RequestContext.getCurrent(runtime).getUserInfo().isSystemUser()) {
            throw new IllegalStateException("TRUSTED_TRANSACTION_REQUIRED");
        }
        var operation = db.run(Select.from(OPERATIONS).byId(operationId.toString()).lock())
                .first().orElseThrow(() -> new IllegalArgumentException("OPERATION_NOT_FOUND"));
        if (!"ACCEPTED".equals(operation.get("status"))
                || !"TEST".equals(operation.get("environment"))
                || !"OUTBOUND".equals(operation.get("direction"))
                || !"SEND_INVOICE".equals(operation.get("operationType"))) {
            throw new IllegalStateException("CONFIRMED_OUTBOUND_RESULT_REQUIRED");
        }
        if (operation.get("snapshot_ID") == null) {
            throw new IllegalStateException("DOCUMENT_SNAPSHOT_REQUIRED");
        }
        var snapshot = db.run(Select.from(SNAPSHOTS).byId(operation.get("snapshot_ID"))).first()
                .orElseThrow(() -> new IllegalStateException("DOCUMENT_SNAPSHOT_INVALID"));
        // Writeback wyłącznie dla dokumentu, który CAP sam odczytał z RAP (nie dla fixture SDK).
        if (!KsefInvoiceSnapshotRegistry.SOURCE_SAP_RAP.equals(snapshot.get("source"))) {
            throw new IllegalStateException("RAP_LINK_UNVERIFIED");
        }
        UUID invoiceId;
        try {
            invoiceId = UUID.fromString((String) operation.get("sapDocumentRef"));
        } catch (RuntimeException notUuid) {
            throw new IllegalStateException("RAP_LINK_UNVERIFIED");
        }
        if (!invoiceId.equals(UUID.fromString((String) snapshot.get("invoiceUUID")))
                || !operation.get("taxpayerNip").equals(snapshot.get("taxpayerNip"))
                || !"OUTBOUND".equals(snapshot.get("direction"))
                || !"TEST".equals(snapshot.get("environment"))) {
            throw new IllegalStateException("DOCUMENT_SNAPSHOT_SCOPE_CONFLICT");
        }
        // Skrót potwierdzony przez KSeF musi dotyczyć dokładnie tego XML, który przygotowano w SAP.
        if (operation.get("confirmedXmlSha256") == null
                || !operation.get("confirmedXmlSha256").equals(snapshot.get("xmlSha256"))) {
            throw new IllegalStateException("CONFIRMED_DOCUMENT_HASH_MISMATCH");
        }
        var command = new SapKsefResultCommand(invoiceId, operationId, (String) operation.get("ksefNumber"),
                (String) operation.get("taxpayerNip"), (String) snapshot.get("companyCode"),
                Instant.parse((String) snapshot.get("sapHeaderVersion")));
        // Istniejące powiązanie musi wskazywać tę samą fakturę i kontekst podatnika.
        if (operation.get("invoice_ID") != null) {
            var invoice = db.run(Select.from(INVOICES).byId(operation.get("invoice_ID"))).first()
                    .orElseThrow(() -> new IllegalStateException("INVOICE_LINK_INVALID"));
            if (!command.taxpayerNip().equals(invoice.get("taxpayerNip"))
                    || !"OUTBOUND".equals(invoice.get("direction"))
                    || !command.invoiceId().toString().equals(invoice.get("sapDocumentRef"))) {
                throw new IllegalStateException("INVOICE_LINK_SCOPE_CONFLICT");
            }
        }
        var existing = db.run(Select.from(WRITES).byId(operationId.toString())).first();
        if (existing.isPresent()) {
            if (!command.equals(command(existing.get()))
                    || !snapshot.get("ID").equals(existing.get().get("snapshotId"))) {
                throw new IllegalStateException("WRITE_IDEMPOTENCY_CONFLICT");
            }
            return operationId;
        }
        // Ten sam numer KSeF widzą sprzedawca i nabywca, ale w SAP trafia do jednej faktury sprzedażowej.
        // Unikalność (environment, ksefNumber) w bazie jest ostateczną ochroną przy wyścigu.
        boolean numberTaken = !db.run(Select.from(WRITES).columns("ID").where(w -> w.get("environment").eq("TEST")
                .and(w.get("ksefNumber").eq(command.ksefNumber())).and(w.get("ID").ne(operationId.toString())))
                .limit(1)).list().isEmpty();
        if (numberTaken) {
            throw new IllegalStateException("KSEF_NUMBER_ALREADY_ASSIGNED");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", operationId.toString());
        row.put("invoiceUUID", command.invoiceId().toString());
        row.put("snapshotId", snapshot.get("ID"));
        row.put("taxpayerNip", command.taxpayerNip());
        row.put("companyCode", command.companyCode());
        row.put("ksefNumber", command.ksefNumber());
        row.put("environment", "TEST");
        row.put("expectedVersion", command.expectedVersion().toString());
        row.put("state", PENDING);
        row.put("stateChangedAt", clock.instant());
        db.run(Insert.into(WRITES).entry(row));
        history(operationId, "QUEUED", 0);
        publish(operationId);
        return operationId;
    }

    /** CAP zapisuje zlecenie atomowo z danymi i publikuje dopiero po commit. */
    void publish(UUID writeId) {
        var message = OutboxMessage.create();
        message.setParams(Map.of("writeId", writeId.toString()));
        outbox.submit(EVENT, message);
    }

    /**
     * CAP persistent outbox zapewnia dostarczenie; SAP action rozstrzyga ponowienie po utracie odpowiedzi.
     * Transakcja outboxa nie dotyka wiersza zlecenia: przejęcie i finalizacja mają własne,
     * krótkie transakcje, więc HTTP nie trzyma blokady rekordu CAP.
     */
    @On(service = "SapResultOutbox", event = EVENT)
    public void process(OutboxMessageEventContext context) {
        if (!Boolean.TRUE.equals(context.getIsInbound())) {
            return;
        }
        UUID id = UUID.fromString((String) context.getMessage().getParams().get("writeId"));
        Claim claim = inNewTransaction(() -> claim(id));
        switch (claim.kind()) {
            case MISSING -> throw new IllegalStateException("WRITE_NOT_FOUND");
            case FINAL -> {
                context.setCompleted();
                return;
            }
            // Dzierżawa trwa: wiadomość musi pozostać w kolejce, bo właściciel mógł paść razem z procesem.
            case HELD -> throw new WriteFailure("WRITE_CLAIM_ACTIVE", true);
            case CLAIMED -> { }
        }
        WriteResult result = null;
        WriteFailure failure = null;
        String tenant = RequestContext.getCurrent(runtime).getUserInfo().getTenant();
        var renewal = renewals.scheduleAtFixedRate(() -> {
            try {
                // Scheduler nie dziedziczy ThreadLocal CAP; odtwarzamy tenant właściciela polecenia.
                runtime.requestContext().systemUser(tenant).run((RequestContext ctx) -> {
                    renewLease(claim.command().requestId(), claim.token());
                });
            }
            catch (RuntimeException unavailable) {
                // Brak DB nie przedłuża lease; po utracie własności chroni token i idempotencja SAP.
                // Bez treści wyjątku (może zawierać konfigurację połączenia).
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("SAP_LEASE_RENEW_FAILED");
            }
        }, renewInterval.toMillis(), renewInterval.toMillis(), TimeUnit.MILLISECONDS);
        try {
            result = adapter.apply(claim.command());
        } catch (WriteFailure known) {
            failure = known;
        } catch (RuntimeException unexpected) {
            // Nie zostawiamy przejęcia do wygaśnięcia dzierżawy; w logach i bazie tylko bezpieczny kod.
            failure = new WriteFailure("WRITE_UNEXPECTED_ERROR", true);
        } finally {
            renewal.cancel(false);
        }
        final WriteResult finalResult = result;
        final WriteFailure finalFailure = failure;
        boolean retry = inNewTransaction(() -> finalizeClaim(claim, finalResult, finalFailure));
        if (retry) {
            // Wyjątek bez odpowiedzi SAP: CAP zachowuje bezpieczny kod i ponawia ten sam payload.
            throw failure;
        }
        context.setCompleted();
    }

    /** Odnawia wyłącznie nadal ważny claim. CAS claim/resume porównuje leaseUntil, więc nie pomija odnowienia. */
    boolean renewLease(UUID id, UUID token) {
        return inNewTransaction(() -> {
            Instant now = clock.instant();
            return db.run(Update.entity(WRITES).data("leaseUntil", now.plus(lease))
                    .where(w -> w.get("ID").eq(id.toString()).and(w.get("state").eq(PROCESSING))
                            .and(w.get("claimToken").eq(token.toString()))
                            .and(w.get("leaseUntil").gt(now)))).rowCount() == 1;
        });
    }

    @PreDestroy void stopLeaseRenewals() { renewals.shutdownNow(); }

    /** Faza A: warunkowy UPDATE jest atomowym przejęciem; przegrany wyścig nie zmienia wiersza. */
    private Claim claim(UUID id) {
        var row = db.run(Select.from(WRITES).byId(id.toString())).first().orElse(null);
        if (row == null) {
            return new Claim(ClaimKind.MISSING, null, null, 0, 0);
        }
        Instant now = clock.instant();
        String state = (String) row.get("state");
        boolean expired = false;
        if (PROCESSING.equals(state)) {
            Instant until = (Instant) row.get("leaseUntil");
            if (until != null && until.isAfter(now)) {
                return new Claim(ClaimKind.HELD, null, null, 0, 0);
            }
            expired = true;
        } else if (!PENDING.equals(state)) {
            return new Claim(ClaimKind.FINAL, null, null, 0, 0);
        }
        int attempts = (Integer) row.get("attempts");
        int cycle = (Integer) row.get("cycleAttempts");
        long revision = ((Number) row.get("revision")).longValue();
        UUID token = UUID.randomUUID();
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("state", PROCESSING);
        change.put("revision", Math.incrementExact(revision));
        change.put("claimToken", token.toString());
        change.put("leaseUntil", now.plus(lease));
        change.put("attempts", attempts + 1);
        change.put("cycleAttempts", cycle + 1);
        change.put("lastAttemptAt", now);
        change.put("stateChangedAt", now);
        long updated = db.run(Update.entity(WRITES).data(change).where(w -> w.get("ID").eq(id.toString())
                .and(w.get("state").eq(state)).and(w.get("attempts").eq(attempts))
                .and(w.get("revision").eq(revision))
                .and(row.get("leaseUntil") == null ? w.get("leaseUntil").isNull()
                        : w.get("leaseUntil").eq(row.get("leaseUntil"))))).rowCount();
        if (updated != 1) {
            return new Claim(ClaimKind.HELD, null, null, 0, 0);
        }
        if (expired) {
            history(id, "CLAIM_EXPIRED", attempts);
        }
        return new Claim(ClaimKind.CLAIMED, command(row), token, attempts + 1, cycle + 1);
    }

    /** Faza C: zwraca true, gdy wyjątek ma wrócić do CAP w celu zaplanowania kolejnej próby. */
    private boolean finalizeClaim(Claim claim, WriteResult result, WriteFailure failure) {
        UUID id = claim.command().requestId();
        Map<String, Object> change = new LinkedHashMap<>();
        boolean retry = false;
        String outcome;
        if (failure == null) {
            outcome = result.outcome().name();
            change.put("state", result.outcome().success() ? SUCCEEDED : RECONCILIATION_REQUIRED);
            change.put("sapRecordedAt", result.recordedAt());
            change.put("sapRecordedRequestId", result.recordedRequestId() == null ? null
                    : result.recordedRequestId().toString());
            change.put("retryNotBefore", null);
        } else if (failure.retryable()) {
            outcome = failure.getMessage();
            boolean exhausted = claim.cycleAttempt() >= maxAttempts;
            change.put("state", exhausted ? RETRY_EXHAUSTED : PENDING);
            retry = !exhausted;
        } else {
            outcome = failure.getMessage();
            change.put("state", RECONCILIATION_REQUIRED);
            change.put("retryNotBefore", failure.retryNotBefore());
        }
        change.put("outcome", outcome);
        change.put("claimToken", null);
        change.put("leaseUntil", null);
        change.put("stateChangedAt", clock.instant());
        // Tylko właściciel przejęcia może zakończyć próbę; po wygaśnięciu dzierżawy i odzyskaniu
        // przez inny worker spóźniona odpowiedź niczego nie nadpisuje.
        long updated = db.run(Update.entity(WRITES).data(change).where(w -> w.get("ID").eq(id.toString())
                .and(w.get("claimToken").eq(claim.token().toString())).and(w.get("state").eq(PROCESSING)))).rowCount();
        if (updated != 1) {
            history(id, "CLAIM_LOST", claim.attempt());
            return false;
        }
        history(id, outcome, claim.attempt());
        if (change.get("state").equals(RETRY_EXHAUSTED)) {
            history(id, RETRY_EXHAUSTED, claim.attempt());
        }
        return retry;
    }

    SapKsefResultCommand command(Map<String, Object> row) {
        if (!"TEST".equals(row.get("environment"))) {
            throw new IllegalStateException("WRITE_ENVIRONMENT_INVALID");
        }
        return new SapKsefResultCommand(UUID.fromString((String) row.get("invoiceUUID")),
                UUID.fromString((String) row.get("ID")), (String) row.get("ksefNumber"),
                (String) row.get("taxpayerNip"), (String) row.get("companyCode"),
                Instant.parse((String) row.get("expectedVersion")));
    }

    void history(UUID id, String outcome, int attempt) {
        db.run(Insert.into(HISTORY).entry(Map.of("ID", UUID.randomUUID().toString(),
                "writeId", id.toString(), "occurredAt", clock.instant(), "outcome", outcome,
                "attempt", attempt)));
    }

    /** Osobny kontekst i ChangeSet: commit następuje przed i niezależnie od transakcji outboxa. */
    <T> T inNewTransaction(Supplier<T> work) {
        return runtime.requestContext().systemUser().run((RequestContext ctx) ->
                runtime.changeSetContext().run(cs -> { return work.get(); }));
    }

    private enum ClaimKind { MISSING, FINAL, HELD, CLAIMED }

    private record Claim(ClaimKind kind, SapKsefResultCommand command, UUID token, int attempt, int cycleAttempt) { }
}
