package customer.cap_java.sap;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
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

/** Trwałe zlecenie i worker writeback potwierdzonej operacji; bez publicznego endpointu. */
@Component
public class SapKsefResultWriteback implements EventHandler {
    static final String WRITES = "customer.cap_java.SapResultWrites";
    static final String HISTORY = "customer.cap_java.SapResultWriteHistory";
    private static final String OPERATIONS = "customer.cap_java.KsefOperations";
    private static final String INVOICES = "customer.cap_java.KsefInvoices";
    private static final String EVENT = "applyConfirmedResult";
    private final PersistenceService db;
    private final OutboxService outbox;
    private final CdsRuntime runtime;
    private final SapKsefResultWriteAdapter adapter;

    public SapKsefResultWriteback(PersistenceService db,
            @Qualifier("SapResultOutbox") OutboxService outbox,
            CdsRuntime runtime, SapKsefResultWriteAdapter adapter) {
        this.db = db;
        this.outbox = outbox;
        this.runtime = runtime;
        this.adapter = adapter;
    }

    /** W H2/single tenant CAP 5.2 wymaga bodźca collectora po cichym restarcie.
     * Publikujemy pusty sygnał przez publiczne API; stare polecenia zachowują payload i ID. */
    @EventListener(ApplicationReadyEvent.class)
    public void resumePendingWrites() {
        runtime.requestContext().systemUser().run((RequestContext request) ->
                runtime.changeSetContext().run(cs -> {
                    if (!db.run(Select.from(WRITES).columns("ID")
                            .where(r -> r.get("state").eq("PENDING")).limit(1)).list().isEmpty()) {
                        outbox.submit("wakeCollector", OutboxMessage.create());
                    }
                }));
    }

    @On(service = "SapResultOutbox", event = "wakeCollector")
    public void wakeCollector(OutboxMessageEventContext context) {
        if (Boolean.TRUE.equals(context.getIsInbound())) {
            context.setCompleted();
        }
    }

    /**
     * Zaufany backend wywołuje po utrwaleniu potwierdzenia KSeF w tej samej transakcji.
     * Operacja musi mieć ACCEPTED, SEND_INVOICE, OUTBOUND i numer KSeF; NEW jest odrzucane.
     * Oczekiwana wersja pochodzi ze snapshotu dokumentu użytego do wysyłki, nie ze świeżego GET.
     */
    public UUID enqueue(UUID operationId, String companyCode, Instant expectedVersion) {
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
        var command = new SapKsefResultCommand(UUID.fromString((String) operation.get("sapDocumentRef")),
                operationId, (String) operation.get("ksefNumber"), (String) operation.get("taxpayerNip"),
                companyCode, expectedVersion);
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
            if (!command.equals(command(existing.get()))) {
                throw new IllegalStateException("WRITE_IDEMPOTENCY_CONFLICT");
            }
            return operationId;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", operationId.toString());
        row.put("invoiceUUID", command.invoiceId().toString());
        row.put("taxpayerNip", command.taxpayerNip());
        row.put("companyCode", command.companyCode());
        row.put("ksefNumber", command.ksefNumber());
        row.put("environment", "TEST");
        row.put("expectedVersion", command.expectedVersion().toString());
        row.put("state", "PENDING");
        db.run(Insert.into(WRITES).entry(row));
        history(operationId, "QUEUED");
        var message = OutboxMessage.create();
        message.setParams(Map.of("writeId", operationId.toString()));
        // CAP zapisuje zlecenie atomowo z danymi i publikuje dopiero po commit.
        outbox.submit(EVENT, message);
        return operationId;
    }

    /** CAP persistent outbox zapewnia wznowienie; SAP action rozstrzyga ponowienie po utracie odpowiedzi. */
    @On(service = "SapResultOutbox", event = EVENT)
    public void process(OutboxMessageEventContext context) {
        if (!Boolean.TRUE.equals(context.getIsInbound())) {
            return;
        }
        UUID id = UUID.fromString((String) context.getMessage().getParams().get("writeId"));
        var row = db.run(Select.from(WRITES).byId(id.toString()).lock()).first()
                .orElseThrow(() -> new IllegalStateException("WRITE_NOT_FOUND"));
        if (!"PENDING".equals(row.get("state"))) {
            context.setCompleted();
            return;
        }
        Map<String, Object> change = new LinkedHashMap<>();
        try {
            var result = adapter.apply(command(row));
            change.put("state", result.outcome().success() ? "SUCCEEDED" : "RECONCILIATION_REQUIRED");
            change.put("outcome", result.outcome().name());
            change.put("sapRecordedAt", result.recordedAt());
            change.put("sapRecordedRequestId", result.recordedRequestId() == null ? null
                    : result.recordedRequestId().toString());
        } catch (WriteFailure failure) {
            if (failure.retryable()) {
                // Wyjątek bez oryginalnej odpowiedzi: CAP zachowuje bezpieczny kod i ponawia ten sam payload.
                throw failure;
            }
            change.put("state", "RECONCILIATION_REQUIRED");
            change.put("outcome", failure.getMessage());
            change.put("retryNotBefore", failure.retryNotBefore());
        }
        db.run(Update.entity(WRITES).data(change).byId(id.toString()));
        history(id, (String) change.get("outcome"));
        context.setCompleted();
    }

    private SapKsefResultCommand command(Map<String, Object> row) {
        if (!"TEST".equals(row.get("environment"))) {
            throw new IllegalStateException("WRITE_ENVIRONMENT_INVALID");
        }
        return new SapKsefResultCommand(UUID.fromString((String) row.get("invoiceUUID")),
                UUID.fromString((String) row.get("ID")), (String) row.get("ksefNumber"),
                (String) row.get("taxpayerNip"), (String) row.get("companyCode"),
                Instant.parse((String) row.get("expectedVersion")));
    }

    private void history(UUID id, String outcome) {
        db.run(Insert.into(HISTORY).entry(Map.of("ID", UUID.randomUUID().toString(),
                "writeId", id.toString(), "occurredAt", Instant.now(), "outcome", outcome)));
    }
}
