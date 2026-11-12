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

    /** CAP-HANA-02: kontrola po cleanup wykrywa własne pozostałości i ignoruje rekordy innego runu/scope. */
    @Test void verifyCleanupFailsOnOwnLeftoversAndIgnoresOtherRun() {
        String firstRun = HanaPersistenceIT.runId;
        harness.seed();
        assertThrows(AssertionError.class, harness::verifyCleanup, "seeded run must not pass verify-cleanup");
        String otherRun = UUID.randomUUID().toString();
        HanaPersistenceIT.runId = otherRun;
        HanaPersistenceIT.nip = "9876543210";
        harness.seed();
        HanaPersistenceIT.runId = firstRun;
        HanaPersistenceIT.nip = "1234567802";
        harness.cleanup();
        assertDoesNotThrow(harness::verifyCleanup);
        HanaPersistenceIT.runId = otherRun;
        HanaPersistenceIT.nip = "9876543210";
        assertThrows(AssertionError.class, harness::verifyCleanup, "other run untouched");
        harness.recover();
    }

    /** Bramka bez zmiennych środowiskowych procesu: zgoda read-only nie odblokowuje faz z DML. */
    @Test void readOnlyConsentCannotUnlockDmlPhases() {
        String savedRun = HanaPersistenceIT.runId;
        String savedNip = HanaPersistenceIT.nip;
        try {
            for (String phase : new String[]{"check", "recover", "verify-cleanup"}) {
                assertDoesNotThrow(() -> HanaPersistenceIT.validate(gateEnv(HanaPersistenceIT.READ_ONLY_CONSENT, phase)), phase);
            }
            for (String phase : new String[]{"seed", "contend", "cleanup"}) {
                var error = assertThrows(IllegalStateException.class,
                    () -> HanaPersistenceIT.validate(gateEnv(HanaPersistenceIT.READ_ONLY_CONSENT, phase)), phase);
                assertEquals("HANA_TEST_WRITE_CONSENT_REQUIRED_FOR_PHASE", error.getMessage());
            }
            for (String phase : HanaPersistenceIT.PHASES) {
                assertDoesNotThrow(() -> HanaPersistenceIT.validate(gateEnv(HanaPersistenceIT.WRITE_CONSENT, phase)), phase);
            }
            var ci = new java.util.HashMap<>(gateEnv(HanaPersistenceIT.READ_ONLY_CONSENT, "check"));
            ci.put("GITHUB_ACTIONS", "true");
            assertThrows(IllegalStateException.class, () -> HanaPersistenceIT.validate(ci));
            assertThrows(IllegalStateException.class, () -> HanaPersistenceIT.validate(gateEnv("READ_ONLY", "check")));
            assertThrows(IllegalStateException.class, () -> HanaPersistenceIT.validate(gateEnv(HanaPersistenceIT.WRITE_CONSENT, "drop")));
            var dbadmin = new java.util.HashMap<>(gateEnv(HanaPersistenceIT.WRITE_CONSENT, "check"));
            dbadmin.put("VCAP_SERVICES", dbadmin.get("VCAP_SERVICES").replace("\"synthetic-user\"", "\"DBADMIN\""));
            assertThrows(IllegalStateException.class, () -> HanaPersistenceIT.validate(dbadmin));
        } finally {
            HanaPersistenceIT.runId = savedRun;
            HanaPersistenceIT.nip = savedNip;
        }
    }

    static Map<String, String> gateEnv(String consent, String phase) {
        return Map.of("AINV_HANA_TEST_CONSENT", consent, "AINV_HANA_TEST_PHASE", phase,
            "AINV_HANA_TEST_RUN_ID", "1e335114-c800-4ff3-9ed8-56a914126af8", "AINV_HANA_TEST_NIP", "7770002317",
            "AINV_HANA_TEST_HDI_NAME", "ainvoicer-db",
            "VCAP_SERVICES", "{\"hana\":[{\"label\":\"hana\",\"name\":\"ainvoicer-db\",\"plan\":\"hdi-shared\","
                + "\"credentials\":{\"schema\":\"synthetic\",\"user\":\"synthetic-user\",\"password\":\"synthetic-only\"}}]}");
    }

    long count(String entity) { return harness.persistence().tx(() -> (long) db.run(Select.from(entity)).list().size()); }
}
