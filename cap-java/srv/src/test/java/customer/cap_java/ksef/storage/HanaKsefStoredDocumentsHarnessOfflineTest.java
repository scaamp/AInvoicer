package customer.cap_java.ksef.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.sap.cds.ql.Delete;
import com.sap.cds.ql.Select;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.RequestContext;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.Application;
import customer.cap_java.ksef.storage.HanaKsefStoredDocumentsIT.Gate;
import customer.cap_java.ksef.storage.HanaKsefStoredDocumentsIT.Plan;
import customer.cap_java.ksef.storage.HanaKsefStoredDocumentsIT.Product;
import customer.cap_java.ksef.storage.HanaKsefStoredDocumentsIT.Scenarios;

/**
 * KSEF-STOR-04R: regresja harnessu HANA na prywatnej H2 — te same scenariusze, inny produkt DB.
 * To NIE jest dowód HANA LIVE (kod konfliktu, blokady, typy katalogu HDB, trwałość po restarcie CF).
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:ksef-stor-04r-harness;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "logging.level.root=WARN" })
@ActiveProfiles("test")
class HanaKsefStoredDocumentsHarnessOfflineTest {
    static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    static final String NIP = "7770002317";
    @Autowired PersistenceService db;
    @Autowired CdsRuntime runtime;
    @Autowired DataSource dataSource;

    @BeforeEach void clean() {
        tx(() -> db.run(Delete.from(Scenarios.ENTITY))); // wyłącznie prywatna H2 tego testu
    }

    <T> T tx(java.util.function.Supplier<T> work) {
        return runtime.requestContext().systemUser().run((RequestContext ctx) ->
                runtime.changeSetContext().run(cs -> { return work.get(); }));
    }

    long rows() { return tx(() -> db.run(Select.from(Scenarios.ENTITY).columns("sha256")).rowCount()); }

    Scenarios scenarios(String phase, String runId, int maxWrites) {
        var plan = new Plan(phase, runId, NIP, COMMIT, maxWrites, phase.equals("catalog") || phase.equals("recover"));
        return new Scenarios(db, runtime, dataSource, plan, Product.H2_OFFLINE);
    }

    @Test void fullLifecycleCatalogRoundtripContendRecoverWritesExactlyFourRows() throws Exception {
        String run = UUID.randomUUID().toString();
        var catalog = scenarios("catalog", run, 0);
        catalog.run();
        assertEquals(List.of("STOR04_CATALOG=COLUMNS:8;PK:4;PROJECTIONS:0"), catalog.markers);
        assertEquals(0, rows(), "catalog is read-only");
        var roundtrip = scenarios("roundtrip", run, 3);
        roundtrip.run();
        assertEquals(List.of("STOR04_ROUNDTRIP=CREATED:3;ALREADY_EXISTS:2"), roundtrip.markers);
        assertEquals(3, rows());
        var contend = scenarios("contend", run, 1);
        contend.run();
        assertTrue(contend.markers.contains("STOR04_CONTEND=CREATED:1;ALREADY_EXISTS:7"), contend.markers.toString());
        assertTrue(contend.markers.stream().anyMatch(m -> m.matches("HANA_UNIQUE_SQLSTATE=[A-Z0-9]{5};VENDOR_CODE=-?[0-9]+")),
                "SQLState marker is collected (H2 value here, HANA value only from LIVE)");
        assertEquals(4, rows());
        // Nowy obiekt scenariusza = nowy klient i magazyn; w R2 każda faza to nowa JVM (restart kontekstu CAP).
        var recover = scenarios("recover", run, 0);
        recover.run();
        assertEquals(List.of("STOR04_RECOVER=DOCUMENTS:4"), recover.markers);
        assertEquals(4, rows(), "recover never writes");
    }

    @Test void reusedRunIsRefusedBeforeAnyWrite() throws Exception {
        String run = UUID.randomUUID().toString();
        scenarios("roundtrip", run, 3).run();
        var error = assertThrows(IllegalStateException.class, () -> scenarios("roundtrip", run, 3).run());
        assertEquals("STOR04_HARNESS_RUN_ALREADY_USED", error.getMessage());
        assertEquals(3, rows());
        scenarios("contend", run, 1).run();
        assertEquals("STOR04_HARNESS_RUN_ALREADY_USED",
                assertThrows(IllegalStateException.class, () -> scenarios("contend", run, 1).run()).getMessage());
        assertEquals(4, rows());
    }

    @Test void writeBudgetRefusesUnplannedOrOverLimitWritesBeforeTheDatabase() {
        String run = UUID.randomUUID().toString();
        var readOnly = scenarios("recover", run, 0);
        readOnly.store();
        byte[] xml = Scenarios.document(run, "ROUNDTRIP");
        String key = Scenarios.key(Scenarios.INBOUND, xml);
        assertEquals("STOR04_HARNESS_WRITE_NOT_PLANNED", assertThrows(IllegalStateException.class,
                () -> readOnly.budget.createIfAbsent(key, xml, Map.of())).getMessage());
        var roundtrip = scenarios("roundtrip", run, 3);
        roundtrip.store();
        byte[] other = Scenarios.document(run, "UNPLANNED");
        assertEquals("STOR04_HARNESS_WRITE_NOT_PLANNED", assertThrows(IllegalStateException.class,
                () -> roundtrip.budget.createIfAbsent(Scenarios.key(Scenarios.INBOUND, other), other, Map.of())).getMessage());
        assertEquals("STOR04_HARNESS_WRITE_BUDGET_EXCEEDED",
                assertThrows(IllegalStateException.class, () -> scenarios("roundtrip", run, 2).run()).getMessage());
        assertEquals(0, rows(), "refusals happen before any INSERT");
    }

    @Test void readOnlyRecoverOnMissingDocumentsFailsClosedWithoutWriting() {
        assertThrows(Throwable.class, () -> scenarios("recover", UUID.randomUUID().toString(), 0).run());
        assertEquals(0, rows());
    }

    @Test void syntheticDocumentIsDeterministicXmlWithBomCrlfAndRunMarkerOnly() {
        String run = "1e335114-c800-4ff3-9ed8-56a914126af8";
        byte[] first = Scenarios.document(run, "ROUNDTRIP");
        assertArrayEquals(first, Scenarios.document(run, "ROUNDTRIP"));
        assertFalse(java.util.Arrays.equals(first, Scenarios.document(UUID.randomUUID().toString(), "ROUNDTRIP")));
        assertArrayEquals(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF }, java.util.Arrays.copyOf(first, 3));
        String text = new String(first, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("\r\n") && text.contains(run) && text.contains("SYNTHETIC-NOT-AN-INVOICE"));
        assertFalse(text.contains("Faktura") || text.contains("P_15") || text.contains("NIP"), "not a KSeF invoice");
    }

    // ------------------------------------------------------------------ bramka (bez zmiennych procesu)

    static Map<String, String> env(String consent, String phase, String maxWrites) {
        var env = new HashMap<String, String>();
        env.put("AINV_STOR04_IT_CONSENT", consent);
        env.put("AINV_STOR04_IT_PHASE", phase);
        env.put("AINV_STOR04_IT_RUN_ID", "1e335114-c800-4ff3-9ed8-56a914126af8");
        env.put("AINV_STOR04_IT_NIP", NIP);
        env.put("AINV_STOR04_IT_ENVIRONMENT", "TEST");
        env.put("AINV_STOR04_IT_HDI_NAME", "ainvoicer-db");
        env.put("AINV_STOR04_IT_COMMIT", COMMIT);
        env.put("AINV_STOR04_IT_MAX_WRITES", maxWrites);
        env.put("VCAP_SERVICES", "{\"hana\":[{\"label\":\"hana\",\"name\":\"ainvoicer-db\",\"plan\":\"hdi-shared\","
                + "\"credentials\":{\"schema\":\"synthetic\",\"user\":\"synthetic-user\",\"password\":\"synthetic-only\"}}],"
                + "\"xsuaa\":[{\"label\":\"xsuaa\",\"name\":\"ainvoicer-auth\",\"plan\":\"application\"}]}");
        return env;
    }

    static String refusal(Map<String, String> env, String buildCommit) {
        return assertThrows(IllegalStateException.class, () -> Gate.validate(env, buildCommit)).getMessage();
    }

    @Test void gateAcceptsOnlyExactApprovedScopePerPhase() {
        assertEquals(new Plan("catalog", "1e335114-c800-4ff3-9ed8-56a914126af8", NIP, COMMIT, 0, true),
                Gate.validate(env(Gate.READ_ONLY_CONSENT, "catalog", "0"), COMMIT));
        assertEquals(new Plan("recover", "1e335114-c800-4ff3-9ed8-56a914126af8", NIP, COMMIT, 0, true),
                Gate.validate(env(Gate.READ_ONLY_CONSENT, "recover", "0"), COMMIT));
        assertEquals(3, Gate.validate(env(Gate.WRITE_CONSENT, "roundtrip", "3"), COMMIT).maxWrites());
        assertEquals(1, Gate.validate(env(Gate.WRITE_CONSENT, "contend", "1"), COMMIT).maxWrites());
        assertEquals(8, Gate.validate(env(Gate.WRITE_CONSENT, "roundtrip", "8"), COMMIT).maxWrites());
    }

    @Test void gateRefusesBeforeAnyConnection() {
        assertEquals("STOR04_HARNESS_EXPLICIT_OWNER_CONSENT_REQUIRED", refusal(Map.of(), COMMIT), "no approval = no HANA");
        assertEquals("STOR04_HARNESS_CONSENT_DOES_NOT_MATCH_PHASE", refusal(env(Gate.READ_ONLY_CONSENT, "roundtrip", "3"), COMMIT));
        assertEquals("STOR04_HARNESS_CONSENT_DOES_NOT_MATCH_PHASE", refusal(env(Gate.READ_ONLY_CONSENT, "contend", "1"), COMMIT));
        assertEquals("STOR04_HARNESS_CONSENT_DOES_NOT_MATCH_PHASE", refusal(env(Gate.WRITE_CONSENT, "recover", "0"), COMMIT));
        // Ogólna zgoda zapisowa R2 (CAP-HANA-02) nie odblokowuje zapisów BLOB.
        assertEquals("STOR04_HARNESS_EXPLICIT_OWNER_CONSENT_REQUIRED",
                refusal(env("WRITE_SYNTHETIC_DATA_TO_APPROVED_HDI", "roundtrip", "3"), COMMIT));
        assertEquals("STOR04_HARNESS_PHASE_INVALID", refusal(env(Gate.WRITE_CONSENT, "cleanup", "3"), COMMIT));
        assertEquals("STOR04_HARNESS_PHASE_INVALID", refusal(env(Gate.WRITE_CONSENT, "all", "3"), COMMIT));
        assertEquals("STOR04_HARNESS_MAX_WRITES_DOES_NOT_COVER_PHASE", refusal(env(Gate.WRITE_CONSENT, "roundtrip", "2"), COMMIT));
        assertEquals("STOR04_HARNESS_MAX_WRITES_DOES_NOT_COVER_PHASE", refusal(env(Gate.READ_ONLY_CONSENT, "catalog", "1"), COMMIT));
        for (String bad : new String[] { "9", "-1", "03", " 3", "x", "100" }) {
            assertEquals("STOR04_HARNESS_MAX_WRITES_INVALID", refusal(env(Gate.WRITE_CONSENT, "roundtrip", bad), COMMIT), bad);
        }
        assertEquals("STOR04_HARNESS_COMMIT_NOT_APPROVED_BUILD", refusal(env(Gate.READ_ONLY_CONSENT, "catalog", "0"), null));
        assertEquals("STOR04_HARNESS_COMMIT_NOT_APPROVED_BUILD", refusal(env(Gate.READ_ONLY_CONSENT, "catalog", "0"), "f".repeat(40)));
        var shortCommit = env(Gate.READ_ONLY_CONSENT, "catalog", "0");
        shortCommit.put("AINV_STOR04_IT_COMMIT", COMMIT.substring(0, 12));
        assertEquals("STOR04_HARNESS_COMMIT_NOT_APPROVED_BUILD", refusal(shortCommit, COMMIT.substring(0, 12)));
        Map<String, String[]> mutations = Map.of(
                "AINV_STOR04_IT_ENVIRONMENT", new String[] { "PROD", "STOR04_HARNESS_TEST_ONLY" },
                "AINV_STOR04_IT_HDI_NAME", new String[] { "ainvoicer-db-test", "STOR04_HARNESS_HDI_NOT_ALLOWED" },
                "AINV_STOR04_IT_NIP", new String[] { "1234567890", "STOR04_HARNESS_SYNTHETIC_NIP_REQUIRED" },
                "AINV_STOR04_IT_RUN_ID", new String[] { "1E335114-C800-4FF3-9ED8-56A914126AF8", "STOR04_HARNESS_RUN_ID_INVALID" },
                "GITHUB_ACTIONS", new String[] { "true", "STOR04_HARNESS_CI_FORBIDDEN" },
                "CI", new String[] { "true", "STOR04_HARNESS_CI_FORBIDDEN" });
        for (var mutation : mutations.entrySet()) {
            var changed = env(Gate.READ_ONLY_CONSENT, "catalog", "0");
            changed.put(mutation.getKey(), mutation.getValue()[0]);
            assertEquals(mutation.getValue()[1], refusal(changed, COMMIT), mutation.getKey());
        }
        for (String vcap : new String[] { null, "{not json", "{}", "[]",
                "{\"hana\":[{\"label\":\"hana\",\"name\":\"ainvoicer-db\",\"plan\":\"hdi-shared\",\"credentials\":{\"schema\":\"s\",\"user\":\"DBADMIN\"}}]}",
                "{\"hana\":[{\"label\":\"hana\",\"name\":\"other\",\"plan\":\"hdi-shared\",\"credentials\":{\"schema\":\"s\",\"user\":\"u\"}}]}",
                "{\"hana\":[{\"label\":\"hana\",\"name\":\"ainvoicer-db\",\"plan\":\"schema\",\"credentials\":{\"schema\":\"s\",\"user\":\"u\"}}]}",
                "{\"hana\":[{\"label\":\"hana\",\"name\":\"ainvoicer-db\",\"plan\":\"hdi-shared\",\"credentials\":{\"schema\":\"s\",\"user\":\"u\"}},"
                        + "{\"label\":\"hana\",\"name\":\"ainvoicer-db\",\"plan\":\"hdi-shared\",\"credentials\":{\"schema\":\"s\",\"user\":\"u\"}}]}" }) {
            var changed = env(Gate.READ_ONLY_CONSENT, "catalog", "0");
            if (vcap == null) changed.remove("VCAP_SERVICES"); else changed.put("VCAP_SERVICES", vcap);
            assertThrows(IllegalStateException.class, () -> Gate.validate(changed, COMMIT), String.valueOf(vcap));
        }
        for (String required : new String[] { "AINV_STOR04_IT_PHASE", "AINV_STOR04_IT_RUN_ID", "AINV_STOR04_IT_COMMIT", "AINV_STOR04_IT_MAX_WRITES" }) {
            var changed = env(Gate.READ_ONLY_CONSENT, "catalog", "0");
            changed.remove(required);
            assertThrows(IllegalStateException.class, () -> Gate.validate(changed, COMMIT), required);
        }
    }

    @Test void harnessIsDisabledByDefaultAndNeverPartOfRegularBuilds() {
        var enabled = HanaKsefStoredDocumentsIT.class.getAnnotation(EnabledIfSystemProperty.class);
        assertNotNull(enabled);
        assertEquals("ainv.hana.stor04", enabled.named());
        assertEquals("true", enabled.matches());
        assertNull(System.getProperty("ainv.hana.stor04"), "regular test JVM never enables REAL HANA harness");
        assertTrue(HanaKsefStoredDocumentsIT.class.getSimpleName().endsWith("IT"), "surefire does not discover *IT");
    }
}
