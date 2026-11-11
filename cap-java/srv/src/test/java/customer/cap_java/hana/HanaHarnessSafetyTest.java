package customer.cap_java.hana;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.sap.cds.ql.*;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.runtime.CdsRuntime;
import customer.cap_java.ksef.inbound.persistence.InboundPersistence;

/** Lokalna regresja bezpieczeństwa harnessu na H2; nie uruchamia bramek ani połączenia REAL HANA. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:hana-harness-safety;DB_CLOSE_DELAY=-1",
    "logging.level.root=WARN", "ainv.sap-write.sweeper.enabled=false"})
@ActiveProfiles("test")
class HanaHarnessSafetyTest {
    @Autowired PersistenceService db;
    @Autowired CdsRuntime runtime;
    HanaPersistenceIT harness;

    @BeforeEach void setup() {
        HanaPersistenceIT.runId = UUID.randomUUID().toString();
        HanaPersistenceIT.nip = "1234567802";
        harness = new HanaPersistenceIT();
        harness.db = db;
        harness.runtime = runtime;
        harness.persistence().tx(() -> {
            for (String entity : new String[]{HanaPersistenceIT.OPS, HanaPersistenceIT.DOCS,
                    "cds.outbox.Messages", InboundPersistence.CHECKPOINTS, InboundPersistence.LEASES}) {
                db.run(Delete.from(entity)); // Wyłącznie prywatna H2 tego testu.
            }
            return true;
        });
    }

    @Test void occupiedScopeRollsBackEntireSeed() {
        var checkpoint = new java.util.HashMap<String, Object>(harness.key());
        checkpoint.putAll(Map.of("continuationFrom", HanaPersistenceIT.NOW.toString(),
            "version", 1L, "committedAt", HanaPersistenceIT.NOW));
        harness.persistence().tx(() -> {
            db.run(Insert.into(InboundPersistence.CHECKPOINTS).entry(checkpoint));
            return true;
        });
        // Symulacja wyścigu: preflight był pusty, ale inny run zarezerwował scope przed INSERT.
        assertThrows(RuntimeException.class, harness::seedRows);
        assertEquals(0, count(HanaPersistenceIT.OPS));
        assertEquals(0, count(HanaPersistenceIT.DOCS));
        assertEquals(0, count("cds.outbox.Messages"));
        assertEquals(1, count(InboundPersistence.CHECKPOINTS));
    }

    @Test void foreignLeaseRefusesAllCleanupAndOwnLeaseAllowsIt() {
        harness.seed();
        var lease = new java.util.HashMap<String, Object>(harness.key());
        lease.putAll(Map.of("lastCode", "RUNNING", "attempts", 1L));
        harness.persistence().tx(() -> { db.run(Insert.into(InboundPersistence.LEASES).entry(lease)); return true; });
        assertThrows(AssertionError.class, harness::cleanup);
        assertEquals(1, count(HanaPersistenceIT.OPS));
        assertEquals(1, count(HanaPersistenceIT.DOCS));
        assertEquals(1, count("cds.outbox.Messages"));
        assertEquals(1, count(InboundPersistence.CHECKPOINTS));
        assertEquals(1, count(InboundPersistence.LEASES));
        harness.persistence().tx(() -> {
            db.run(Update.entity(InboundPersistence.LEASES).matching(harness.key())
                .data("lastCode", HanaPersistenceIT.MARKER + HanaPersistenceIT.runId));
            return true;
        });
        harness.cleanup();
        for (String entity : new String[]{HanaPersistenceIT.OPS, HanaPersistenceIT.DOCS, "cds.outbox.Messages",
                InboundPersistence.CHECKPOINTS, InboundPersistence.LEASES}) assertEquals(0, count(entity));
    }

    @Test void changedCheckpointRefusesCleanup() {
        harness.seed();
        harness.persistence().tx(() -> {
            db.run(Update.entity(InboundPersistence.CHECKPOINTS).matching(harness.key()).data("version", 2L));
            return true;
        });
        assertThrows(AssertionError.class, harness::cleanup);
        assertEquals(1, count(HanaPersistenceIT.OPS));
        assertEquals(1, count(InboundPersistence.CHECKPOINTS));
    }

    @Test void cleanupPreservesAnotherRunAndScope() {
        String firstRun = HanaPersistenceIT.runId;
        harness.seed();
        String otherRun = UUID.randomUUID().toString();
        HanaPersistenceIT.runId = otherRun;
        HanaPersistenceIT.nip = "9876543210";
        harness.seed();
        HanaPersistenceIT.runId = firstRun;
        HanaPersistenceIT.nip = "1234567802";
        harness.cleanup();
        HanaPersistenceIT.runId = otherRun;
        HanaPersistenceIT.nip = "9876543210";
        harness.recover();
        assertEquals(1, count(HanaPersistenceIT.OPS));
        assertEquals(1, count(HanaPersistenceIT.DOCS));
        assertEquals(1, count("cds.outbox.Messages"));
        assertEquals(1, count(InboundPersistence.CHECKPOINTS));
    }

    long count(String entity) { return harness.persistence().tx(() -> (long) db.run(Select.from(entity)).list().size()); }
}
