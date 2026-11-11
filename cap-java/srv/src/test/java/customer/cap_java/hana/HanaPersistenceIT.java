package customer.cap_java.hana;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sap.cds.ql.*;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.runtime.CdsRuntime;
import customer.cap_java.ksef.inbound.*;
import customer.cap_java.ksef.inbound.persistence.*;
import customer.cap_java.ksef.inbound.sync.SyncScope;

/**
 * Opt-in REAL HANA, bez mocków i efektów KSeF/SAP. Zwykły verify nie odkrywa sufiksu IT.
 * Fazy seed/recover/contend/cleanup pozwalają uruchomić osobne JVM i powtórzyć recover po
 * osobno zatwierdzonym restarcie DB. Narzędzie nigdy nie restartuje bazy ani aplikacji CF.
 */
@EnabledIfSystemProperty(named = "ainv.hana.integration", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "ainv.deployment.identity-smoke=false", "ainv.deployment.hana-readiness=true", "spring.sql.init.mode=never",
    "ainv.ksef.enabled=false", "ainv.ksef.inbound.enabled=false", "ainv.sap-write.enabled=false",
    "ainv.sap-write.sweeper.enabled=false", "ainv.ksef.storage.provider=off",
    "cds.outbox.persistent.enabled=false", "cds.outbox.inMemory.enabled=false",
    "logging.level.root=ERROR", "logging.level.com.zaxxer.hikari=OFF", "spring.main.banner-mode=off"
})
@ActiveProfiles("cf")
class HanaPersistenceIT {
    static final String OPS = "customer.cap_java.KsefOperations";
    static final String DOCS = InboundPersistence.DOCUMENTS;
    static final String MARKER = "INT03-HANA-";
    static final Instant NOW = Instant.parse("2026-11-11T08:00:00.123456700Z");
    static String runId, nip, phase, hdi;
    @Autowired PersistenceService db;
    @Autowired CdsRuntime runtime;
    @Autowired DataSource dataSource;

    /** Bramki przed utworzeniem kontekstu Spring/DataSource: brak zgody = zero połączeń i zapisów. */
    @DynamicPropertySource static void gate(DynamicPropertyRegistry properties) {
        if ("true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                || !"WRITE_SYNTHETIC_DATA_TO_APPROVED_HDI".equals(System.getenv("AINV_HANA_TEST_CONSENT"))) {
            throw new IllegalStateException("HANA_TEST_EXPLICIT_OWNER_CONSENT_REQUIRED_NO_CI");
        }
        runId = UUID.fromString(required("AINV_HANA_TEST_RUN_ID")).toString();
        nip = required("AINV_HANA_TEST_NIP");
        if (!KsefTaxpayerContext.isValidNip(nip)) throw new IllegalStateException("HANA_TEST_SYNTHETIC_NIP_REQUIRED");
        hdi = required("AINV_HANA_TEST_HDI_NAME");
        if (!Set.of("ainvoicer-db", "ainvoicer-db-test").contains(hdi)) throw new IllegalStateException("HANA_TEST_HDI_NOT_ALLOWED");
        phase = required("AINV_HANA_TEST_PHASE");
        if (!Set.of("check", "seed", "recover", "contend", "cleanup").contains(phase)) throw new IllegalStateException("HANA_TEST_PHASE_INVALID");
        // Weryfikacja także przy bezpośrednim -Phana-integration, przed utworzeniem DataSource.
        try {
            var services = new com.fasterxml.jackson.databind.ObjectMapper().readTree(required("VCAP_SERVICES"));
            var bindings = services.path("hana");
            if (bindings.size() != 1 || !hdi.equals(bindings.path(0).path("name").asText())
                    || !"hdi-shared".equals(bindings.path(0).path("plan").asText())
                    || bindings.path(0).path("credentials").path("schema").asText().isBlank()
                    || bindings.path(0).path("credentials").path("user").asText().isBlank()
                    || "DBADMIN".equalsIgnoreCase(bindings.path(0).path("credentials").path("user").asText())) {
                throw new IllegalStateException("HANA_TEST_MANAGED_HDI_BINDING_REQUIRED");
            }
        } catch (java.io.IOException error) { throw new IllegalStateException("HANA_TEST_BINDING_INVALID"); }
        properties.add("cds.environment.local.defaultEnvPath", () -> "hana-test-no-local-default-env.json");
    }

    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("HANA_TEST_REQUIRED_" + name);
        return value;
    }

    InboundPersistence persistence() { return new InboundPersistence(db, runtime); }
    SyncScope scope() { return new SyncScope(KsefEnvironment.TEST, nip, KsefSubjectRole.BUYER); }
    String docId() { return UUID.nameUUIDFromBytes((runId + "doc").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(); }

    @Test void realHanaPersistenceAndRecovery() throws Exception {
        var hanaBindings = runtime.getEnvironment().getServiceBindings().filter(b -> b.getServiceName().map("hana"::equals).orElse(false)).toList();
        assertEquals(1, hanaBindings.size());
        assertEquals(hdi, hanaBindings.getFirst().getName().orElse(""));
        assertEquals("hdi-shared", hanaBindings.getFirst().getServicePlan().orElse(""));
        try (var connection = dataSource.getConnection()) {
            assertEquals("HDB", connection.getMetaData().getDatabaseProductName());
            // JDBC metadata zamiast założeń o nazwach indeksów i uprawnieniach widoków systemowych HANA.
            try (var indexes = connection.getMetaData().getIndexInfo(null, connection.getSchema(), "CUSTOMER_CAP_JAVA_KSEFOPERATIONS", true, false)) {
                assertTrue(indexes.next(), "unique indexes must exist in deployed HDI schema");
            }
        }
        switch (phase) {
            case "check" -> { // Start/metadata bez zapisu.
                assertNotNull(runtime.getCdsModel().getEntity(DOCS));
            }
            case "seed" -> seed();
            case "recover" -> recover();
            case "contend" -> contend();
            case "cleanup" -> cleanup();
            default -> fail("Unknown phase");
        }
        System.out.println("HANA_TEST_PHASE_PASS=" + phase); // bez credentiali/danych/payloadu
    }

    /** Preflight odmawia użycia zajętego scope. Każdy zapis ma korelację z zatwierdzonym run UUID. */
    void seed() {
        assertTrue(new CqnSyncCheckpointRepository(persistence()).find(scope()).isEmpty(), "scope must be isolated and unused");
        assertTrue(persistence().tx(() -> db.run(Select.from(InboundPersistence.LEASES).matching(key())).list().isEmpty()));
        persistence().tx(() -> {
            db.run(Insert.into(OPS).entry(operation(runId)));
            db.run(Insert.into(DOCS).entry(Map.ofEntries(Map.entry("ID", docId()), Map.entry("environment", "TEST"),
                Map.entry("ksefNumber", runId.substring(0, 35)), Map.entry("invoiceNumber", MARKER + runId),
                Map.entry("sellerNip", nip), Map.entry("buyerIdentifierType", "NIP"), Map.entry("sha256", "0".repeat(64)),
                Map.entry("grossAmount", new BigDecimal("123456789.12")), Map.entry("sizeBytes", 4294967297L), Map.entry("acquisitionDate", NOW))));
            // Bez wywoływania submit/delivery: bada wyłącznie trwałość tabeli outbox.
            db.run(Insert.into("cds.outbox.Messages").entry(Map.of("ID", runId, "target", "INT03_NO_DELIVERY",
                "msg", MARKER + runId, "timestamp", NOW, "attempts", 0)));
            return true;
        });
        var checkpoint = new CqnSyncCheckpointRepository(persistence());
        assertTrue(checkpoint.commit(scope(), 0, NOW.atOffset(ZoneOffset.UTC), NOW));
        assertFalse(checkpoint.commit(scope(), 0, NOW.plusSeconds(1).atOffset(ZoneOffset.UTC), NOW));
        // Test rzeczywistego constraint w osobnej transakcji; bez SQLState specyficznego dla H2.
        var conflict = assertThrows(ServiceException.class, () -> persistence().tx(() -> { db.run(Insert.into(OPS).entry(operation(UUID.randomUUID().toString()))); return true; }));
        assertEquals("409006", conflict.getErrorStatus().getCodeString(), "CAP unique conflict mapping on HANA");
        for (Throwable cause = conflict; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().matches("[A-Z0-9]{5}")) {
                System.out.println("HANA_UNIQUE_SQLSTATE=" + sql.getSQLState() + ";VENDOR_CODE=" + sql.getErrorCode());
            }
        }
        var checkpointValue = NOW.atOffset(ZoneOffset.UTC);
        assertFalse(checkpoint.commit(scope(), 1, checkpointValue.minusSeconds(1), NOW), "HWM cannot move backward");
        assertFalse(checkpoint.commit(scope(), 99, checkpointValue.plusSeconds(1), NOW), "stale CAS cannot advance HWM");
        String rollbackId = UUID.randomUUID().toString();
        assertThrows(IllegalStateException.class, () -> persistence().tx(() -> {
            var row = new HashMap<>(operation(rollbackId)); row.put("idempotencyKey", MARKER + rollbackId);
            db.run(Insert.into(OPS).entry(row)); throw new IllegalStateException("INT03_EXPECTED_ROLLBACK");
        }));
        assertTrue(persistence().tx(() -> db.run(Select.from(OPS).byId(rollbackId)).list().isEmpty()));
        recover();
    }

    Map<String, Object> operation(String id) {
        return Map.of("ID", id, "taxpayerNip", nip, "companyCode", "TST0", "sapDocumentRef", MARKER + runId,
            "direction", "OUTBOUND", "environment", "TEST", "operationType", "CHECK_STATUS", "status", "NEW", "idempotencyKey", MARKER + runId);
    }

    /** Te same asercje w nowej JVM i po powrocie bazy: dokładnie jeden dokument/operacja/outbox i HWM. */
    void recover() {
        assertEquals(1, persistence().tx(() -> db.run(Select.from(OPS).where(o -> o.get("idempotencyKey").eq(MARKER + runId).and(o.get("taxpayerNip").eq(nip)))).list().size()));
        var doc = persistence().tx(() -> db.run(Select.from(DOCS).byId(docId())).single());
        assertEquals(0, new BigDecimal(doc.get("grossAmount").toString()).compareTo(new BigDecimal("123456789.12")));
        assertEquals(4294967297L, ((Number) doc.get("sizeBytes")).longValue());
        assertEquals(NOW, doc.get("acquisitionDate")); // 100 ns, bez zakładania precyzji H2 1 ns.
        assertEquals(MARKER + runId, persistence().tx(() -> db.run(Select.from("cds.outbox.Messages").byId(runId)).single()).get("msg"));
        var checkpoint = new CqnSyncCheckpointRepository(persistence()).find(scope()).orElseThrow();
        assertEquals(NOW.atOffset(ZoneOffset.UTC), checkpoint.continuationFrom());
    }

    /** Uruchom równocześnie w dwóch JVM: CAS + lease/fencing nie dopuszcza dwóch właścicieli. */
    void contend() throws Exception {
        recover();
        var p = persistence();
        long changed = p.tx(() -> db.run(Update.entity(OPS).byId(runId).data("status", "COMPLETED").where(o -> o.get("status").eq("NEW"))).rowCount());
        assertTrue(changed == 0 || changed == 1);
        var clock = Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC);
        var leases = new CqnInboundSyncLeaseRepository(p, clock);
        var claim = leases.claim(scope(), Duration.ofMinutes(1));
        if (claim.isPresent()) {
            leases.requireOwned(claim.get());
            var stale = new CqnInboundSyncLeaseRepository.Claim(scope(), UUID.randomUUID());
            assertThrows(IllegalStateException.class, () -> leases.requireOwned(stale));
            assertFalse(leases.fenced(new CqnSyncCheckpointRepository(p), stale).commit(scope(), 1, NOW.plusSeconds(2).atOffset(ZoneOffset.UTC), NOW));
        }
        // lock() jest faktycznie wykonywany wewnątrz transakcji HANA, bez przenoszenia reguł timeoutu H2.
        assertNotNull(p.tx(() -> db.run(Select.from(OPS).byId(runId).lock()).single()));
        // Niezależne połączenia: drugi lock nie może zakończyć się przed commitem pierwszego.
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> p.tx(() -> {
                db.run(Select.from(OPS).byId(runId).lock()).single(); held.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("HANA_LOCK_TEST_TIMEOUT"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("HANA_LOCK_INTERRUPTED"); }
                return true;
            }));
            assertTrue(held.await(5, TimeUnit.SECONDS));
            var attempting = new CountDownLatch(1);
            var second = pool.submit(() -> p.tx(() -> { attempting.countDown(); return db.run(Select.from(OPS).byId(runId).lock()).single(); }));
            try {
                assertTrue(attempting.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> second.get(250, TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            assertTrue(first.get(10, TimeUnit.SECONDS)); assertNotNull(second.get(10, TimeUnit.SECONDS));
        } finally { release.countDown(); }
        System.out.println("HANA_CAS_CHANGED=" + changed + ";LEASE_CLAIMED=" + claim.isPresent());
    }

    Map<String, Object> key() { return Map.of("environment", "TEST", "contextNip", nip, "subjectRole", "BUYER"); }

    /** Cleanup wyłącznie własnych syntetycznych kluczy; brak DELETE całych tabel i brak drop schematu. */
    void cleanup() {
        recover();
        persistence().tx(() -> {
            db.run(Delete.from("cds.outbox.Messages").byId(runId));
            db.run(Delete.from(DOCS).byId(docId()));
            db.run(Delete.from(OPS).byId(runId));
            db.run(Delete.from(InboundPersistence.CHECKPOINTS).matching(key()));
            db.run(Delete.from(InboundPersistence.LEASES).matching(key()));
            return true;
        });
    }
}
