package customer.cap_java.sap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.outbox.OutboxMessage;
import com.sap.cds.services.outbox.OutboxMessageEventContext;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.RequestContext;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.sap.SapKsefResultWriteAdapter.Outcome;
import customer.cap_java.sap.SapKsefResultWriteAdapter.WriteFailure;
import customer.cap_java.sap.SapKsefResultWriteAdapter.WriteResult;

/** Prawdziwa persystencja CAP/H2 i outbox; transport SAP zastąpiony mockiem. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = { "cds.outbox.services.SapResultOutbox.startCollector=false",
                "cds.outbox.persistent.scheduler.allTenantsTask.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:writeback-unit;DB_CLOSE_DELAY=-1" })
@ActiveProfiles("test")
class SapKsefResultWritebackTest {
    static final String OPS = "customer.cap_java.KsefOperations";
    static final Instant VERSION = Instant.parse("2026-10-28T10:11:12.1234567Z");
    @Autowired CdsRuntime runtime;
    @Autowired PersistenceService db;
    @Autowired SapKsefResultWriteback writeback;
    @MockitoBean SapKsefResultWriteAdapter adapter;
    private UUID id;
    private UUID invoice;

    @BeforeEach void setup() {
        id = UUID.randomUUID();
        invoice = UUID.randomUUID();
        tx(() -> {
            seed(db, id, invoice);
            return null;
        });
    }

    static void seed(PersistenceService db, UUID id, UUID invoice) {
        db.run(Insert.into(OPS).entry(Map.of("ID", id.toString(), "sapDocumentRef", invoice.toString(),
                "taxpayerNip", "1234567802", "direction", "OUTBOUND", "operationType", "SEND_INVOICE",
                "status", "ACCEPTED", "ksefNumber", "1234567802-TEST-123", "idempotencyKey", id.toString())));
    }

    private <T> T tx(Supplier<T> work) {
        return runtime.requestContext().systemUser().run((RequestContext ctx) ->
                runtime.changeSetContext().run(cs -> { return work.get(); }));
    }

    @Test void persistsCommandAndOutboxAtomicallyWithoutCallingSap() {
        tx(() -> {
            assertEquals(id, writeback.enqueue(id, "TST1", VERSION));
            assertEquals(id, writeback.enqueue(id, "TST1", VERSION));
            return null;
        });
        assertEquals(VERSION.toString(), row().get("expectedVersion"));
        assertEquals("PENDING", row().get("state"));
        assertEquals(1, outboxCount());
        verifyNoInteractions(adapter);
    }

    @Test void rollbackRemovesCommandHistoryAndOutboxMessage() {
        assertThrows(IllegalStateException.class, () -> tx(() -> {
            writeback.enqueue(id, "TST1", VERSION);
            throw new IllegalStateException("ROLLBACK_TEST");
        }));
        assertTrue(db.run(Select.from(SapKsefResultWriteback.WRITES).byId(id.toString())).first().isEmpty());
        assertEquals(0, outboxCount());
        assertEquals(0, historyCount());
        verifyNoInteractions(adapter);
    }

    @Test void concurrentEnqueueHasOneDurableCommandAndOneOutboxEntry() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> tx(() -> writeback.enqueue(id, "TST1", VERSION)));
            var b = pool.submit(() -> tx(() -> writeback.enqueue(id, "TST1", VERSION)));
            assertEquals(id, a.get());
            assertEquals(id, b.get());
        }
        assertEquals(1, outboxCount());
        assertEquals(1, historyCount());
    }

    @Test void changedPayloadCannotReplaceExistingCommand() {
        tx(() -> writeback.enqueue(id, "TST1", VERSION));
        assertThrows(IllegalStateException.class, () -> tx(() -> writeback.enqueue(id, "TST2", VERSION)));
        assertEquals("TST1", row().get("companyCode"));
        assertEquals(1, outboxCount());
    }

    @Test void refusesUnconfirmedInboundAndWrongOperationType() {
        for (var change : Map.of("status", "NEW", "direction", "INBOUND", "operationType", "CHECK_STATUS",
                "environment", "PROD").entrySet()) {
            tx(() -> { db.run(Update.entity(OPS).data(change.getKey(), change.getValue()).byId(id.toString())); return null; });
            assertThrows(IllegalStateException.class, () -> tx(() -> writeback.enqueue(id, "TST1", VERSION)));
            tx(() -> { db.run(Update.entity(OPS).data(Map.of("status", "ACCEPTED", "direction", "OUTBOUND",
                    "operationType", "SEND_INVOICE", "environment", "TEST")).byId(id.toString())); return null; });
        }
        assertEquals(0, outboxCount());
    }

    @Test void refusesUserContextAndMissingTransaction() {
        assertThrows(IllegalStateException.class, () -> writeback.enqueue(id, "TST1", VERSION));
        var user = com.sap.cds.services.request.UserInfo.create().setName("operator-a")
                .setIsAuthenticated(true).setIsSystemUser(false);
        assertThrows(IllegalStateException.class, () -> runtime.requestContext().user(user)
                .run((RequestContext ctx) -> runtime.changeSetContext().run(cs -> {
                    return writeback.enqueue(id, "TST1", VERSION);
                })));
    }

    @Test void successAndDuplicateDeliveryKeepOneTerminalHistoryEntry() {
        tx(() -> writeback.enqueue(id, "TST1", VERSION));
        when(adapter.apply(any())).thenAnswer(call -> {
            var command = (SapKsefResultCommand) call.getArgument(0);
            return new WriteResult(Outcome.ALREADY_APPLIED, invoice, command.ksefNumber(), "TEST", VERSION, id);
        });
        process();
        process();
        assertEquals("SUCCEEDED", row().get("state"));
        assertEquals("ALREADY_APPLIED", row().get("outcome"));
        assertEquals(2, historyCount());
        verify(adapter, times(1)).apply(any());
    }

    @Test void domainConflictRequiresReconciliationWithoutRetry() {
        tx(() -> writeback.enqueue(id, "TST1", VERSION));
        when(adapter.apply(any())).thenReturn(new WriteResult(Outcome.VERSION_CONFLICT, invoice, null, null, null, null));
        process();
        assertEquals("RECONCILIATION_REQUIRED", row().get("state"));
        assertEquals("VERSION_CONFLICT", row().get("outcome"));
        process();
        verify(adapter, times(1)).apply(any());
    }

    @Test void ambiguousTransportRetryKeepsOriginalRequestIdAndVersion() {
        tx(() -> writeback.enqueue(id, "TST1", VERSION));
        when(adapter.apply(any())).thenThrow(new WriteFailure("SAP_TRANSPORT_UNKNOWN", true))
                .thenReturn(new WriteResult(Outcome.ALREADY_APPLIED, invoice, "1234567802-TEST-123", "TEST", VERSION, id));
        assertThrows(WriteFailure.class, this::process);
        assertEquals("PENDING", row().get("state"));
        process();
        var commands = org.mockito.ArgumentCaptor.forClass(SapKsefResultCommand.class);
        verify(adapter, times(2)).apply(commands.capture());
        assertEquals(commands.getAllValues().getFirst(), commands.getAllValues().getLast());
        assertEquals(VERSION, commands.getValue().expectedVersion());
        assertEquals(id, commands.getValue().requestId());
        assertEquals("SUCCEEDED", row().get("state"));
    }

    @Test void permanentTransportRefusalIsRecorded() {
        tx(() -> writeback.enqueue(id, "TST1", VERSION));
        when(adapter.apply(any())).thenThrow(new WriteFailure("SAP_HTTP_403", false));
        process();
        assertEquals("RECONCILIATION_REQUIRED", row().get("state"));
        assertEquals("SAP_HTTP_403", row().get("outcome"));
    }

    @Test void rateLimitDeadlineIsPersistedForControlledResume() {
        tx(() -> writeback.enqueue(id, "TST1", VERSION));
        Instant deadline = Instant.parse("2026-10-29T12:00:00Z");
        when(adapter.apply(any())).thenThrow(new WriteFailure("SAP_HTTP_429", false, deadline));
        process();
        assertEquals("RECONCILIATION_REQUIRED", row().get("state"));
        assertEquals(deadline, row().get("retryNotBefore"));
    }

    private void process() {
        tx(() -> {
            var context = OutboxMessageEventContext.create("applyConfirmedResult");
            context.setIsInbound(true);
            var message = OutboxMessage.create();
            message.setParams(Map.of("writeId", id.toString()));
            context.setMessage(message);
            writeback.process(context);
            assertTrue(context.isCompleted());
            return null;
        });
    }

    private Map<String, Object> row() {
        return db.run(Select.from(SapKsefResultWriteback.WRITES).byId(id.toString())).single();
    }

    private long historyCount() {
        return db.run(Select.from(SapKsefResultWriteback.HISTORY).where(r -> r.get("writeId").eq(id.toString()))).list().size();
    }

    private long outboxCount() {
        return db.run(Select.from("cds.outbox.Messages").where(r -> r.get("target").eq("SapResultOutbox")))
                .list().stream().filter(r -> r.get("msg").toString().contains(id.toString())).count();
    }
}
