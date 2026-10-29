package customer.cap_java.sap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.sap.cds.ql.Select;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.RequestContext;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.sap.SapKsefResultWriteAdapter.Outcome;
import customer.cap_java.sap.SapKsefResultWriteAdapter.WriteFailure;
import customer.cap_java.sap.SapKsefResultWriteAdapter.WriteResult;

/**
 * Rzeczywisty collector CAP 5.2 (H2, mock SAP): dokumentuje, co dzieje się po wyczerpaniu limitu prób CAP
 * (niższego tu niż budżet aplikacji), oraz że osierocone polecenie jest wykrywalne i wznawialne.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = { "cds.outbox.services.SapResultOutbox.maxAttempts=2",
                "cds.outbox.services.SapResultOutbox.maxPause=1s",
                "ainv.sap-write.max-attempts=50",
                "ainv.sap-write.stale-after=PT1S",
                "spring.datasource.url=jdbc:h2:mem:writeback-exhaustion;DB_CLOSE_DELAY=-1" })
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SapResultOutboxExhaustionTest {
    @Autowired CdsRuntime runtime;
    @Autowired PersistenceService db;
    @Autowired SapKsefResultWriteback writeback;
    @Autowired SapKsefResultWriteRecovery recovery;
    @MockitoBean SapKsefResultWriteAdapter adapter;

    @Test void capGivesUpSilentlyAfterItsLimitAndRecoveryRestoresTheCommand() throws Exception {
        UUID id = UUID.randomUUID();
        UUID invoice = UUID.randomUUID();
        when(adapter.apply(any())).thenThrow(new WriteFailure("SAP_HTTP_503", true));
        tx(() -> { WritebackFixtures.seed(db, id, invoice); return writeback.enqueue(id); });

        // CAP wykonuje dokładnie swoje dwie próby, po czym przestaje dostarczać wiadomość.
        await(Duration.ofSeconds(60), () -> mockCalls() >= 2);
        Thread.sleep(3500);
        assertEquals(2, mockCalls(), "po wyczerpaniu limitu CAP nie dostarcza wiadomości ponownie");
        var message = db.run(Select.from("cds.outbox.Messages").where(r -> r.get("target").eq("SapResultOutbox")))
                .list().stream().filter(r -> r.get("msg").toString().contains(id.toString())).findFirst().orElseThrow();
        System.out.println("CAP_EXHAUSTED_MESSAGE attempts=" + message.get("attempts") + " status=" + message.get("status"));
        assertEquals(2, ((Number) message.get("attempts")).intValue());

        // Stan polecenia w tej sytuacji: PENDING bez żywej wiadomości — wykrywalne jako osierocone.
        var row = db.run(Select.from(SapKsefResultWriteback.WRITES).byId(id.toString())).single();
        assertEquals("PENDING", row.get("state"));
        var status = recovery.needingAttention().stream().filter(s -> s.writeId().equals(id)).findFirst().orElseThrow();
        assertTrue(status.orphaned());
        assertTrue(status.resumable());

        // Po usunięciu przyczyny kontrolowane wznowienie dostarcza ten sam payload i kończy się sukcesem.
        doAnswer(call -> {
            SapKsefResultCommand command = call.getArgument(0);
            return new WriteResult(Outcome.ALREADY_APPLIED, command.invoiceId(), command.ksefNumber(), "TEST",
                    WritebackFixtures.VERSION, command.requestId());
        }).when(adapter).apply(any());
        recovery.resume(id, "SAP_RESTORED");
        await(Duration.ofSeconds(60), () -> "SUCCEEDED".equals(
                db.run(Select.from(SapKsefResultWriteback.WRITES).byId(id.toString())).single().get("state")));
        assertEquals("SUCCEEDED", db.run(Select.from(SapKsefResultWriteback.WRITES).byId(id.toString()))
                .single().get("state"));
    }

    private int mockCalls() {
        return mockingDetails(adapter).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("apply")).toList().size();
    }

    private static void await(Duration timeout, Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && !condition.get()) {
            Thread.sleep(100);
        }
        assertTrue(condition.get(), "warunek nie został spełniony w czasie " + timeout);
    }

    private <T> T tx(Supplier<T> work) {
        return runtime.requestContext().systemUser().run((RequestContext ctx) ->
                runtime.changeSetContext().run(cs -> { return work.get(); }));
    }
}
