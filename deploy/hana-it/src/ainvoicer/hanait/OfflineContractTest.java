package ainvoicer.hanait;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ten test sprawdza dystrybucję JUnit i bramkę w nowej JVM; nigdy nie łączy się z bazą. */
class OfflineContractTest {
    @Test void packagedHarnessGateAndPhaseContract() throws Exception {
        var type = Class.forName(Main.IT);
        var validate = type.getDeclaredMethod("validate", Map.class);
        validate.setAccessible(true);
        var env = new HashMap<String, String>();
        env.put("AINV_HANA_TEST_RUN_ID", "1e335114-c800-4ff3-9ed8-56a914126af8");
        env.put("AINV_HANA_TEST_NIP", "7770002317");
        env.put("AINV_HANA_TEST_HDI_NAME", "ainvoicer-db");
        env.put("VCAP_SERVICES", "{\"hana\":[{\"name\":\"ainvoicer-db\",\"plan\":\"hdi-shared\","
            + "\"credentials\":{\"schema\":\"offline\",\"user\":\"offline\"}}]}");
        for (String phase : Main.PHASES) {
            env.put("AINV_HANA_TEST_PHASE", phase);
            env.put("AINV_HANA_TEST_CONSENT", "READ_ONLY_CHECK_APPROVED_HDI");
            if (Main.READ.contains(phase)) assertDoesNotThrow(() -> validate.invoke(null, env));
            else assertThrows(java.lang.reflect.InvocationTargetException.class, () -> validate.invoke(null, env));
            env.put("AINV_HANA_TEST_CONSENT", "WRITE_SYNTHETIC_DATA_TO_APPROVED_HDI");
            assertDoesNotThrow(() -> validate.invoke(null, env));
        }
        env.put("AINV_HANA_TEST_PHASE", "drop");
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> validate.invoke(null, env));
        env.remove("AINV_HANA_TEST_CONSENT");
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> validate.invoke(null, env));
    }

    /** KSEF-STOR-04R: spakowana bramka harnessu storage, pin commitu z JAR i rozdział zgód odczyt/zapis. */
    @Test void packagedStor04GateCommitPinAndConsentContract() throws Exception {
        String commit = Main.buildCommit();
        assertTrue(commit.matches("[0-9a-f]{40}"));
        var gate = Class.forName(Main.STOR04_IT + "$Gate");
        var validate = gate.getDeclaredMethod("validate", Map.class, String.class);
        validate.setAccessible(true);
        var env = new HashMap<String, String>();
        env.put("AINV_STOR04_IT_RUN_ID", "1e335114-c800-4ff3-9ed8-56a914126af8");
        env.put("AINV_STOR04_IT_NIP", "7770002317");
        env.put("AINV_STOR04_IT_ENVIRONMENT", "TEST");
        env.put("AINV_STOR04_IT_HDI_NAME", "ainvoicer-db");
        env.put("AINV_STOR04_IT_COMMIT", commit);
        env.put("VCAP_SERVICES", "{\"hana\":[{\"label\":\"hana\",\"name\":\"ainvoicer-db\",\"plan\":\"hdi-shared\","
            + "\"credentials\":{\"schema\":\"offline\",\"user\":\"offline\"}}]}");
        for (var phase : Main.STOR04_PHASES.entrySet()) {
            boolean read = Main.STOR04_READ.contains(phase.getKey());
            env.put("AINV_STOR04_IT_PHASE", phase.getValue());
            env.put("AINV_STOR04_IT_MAX_WRITES", Integer.toString(Main.STOR04_PLANNED_ROWS.get(phase.getKey())));
            env.put("AINV_STOR04_IT_CONSENT", read ? "READ_ONLY_STOR04_CHECK_APPROVED_HDI" : "WRITE_SYNTHETIC_STOR04_DOCUMENTS_APPROVED_HDI");
            assertDoesNotThrow(() -> validate.invoke(null, env, commit), phase.getKey());
            assertThrows(java.lang.reflect.InvocationTargetException.class, () -> validate.invoke(null, env, "0".repeat(40)));
            env.put("AINV_STOR04_IT_CONSENT", read ? "WRITE_SYNTHETIC_STOR04_DOCUMENTS_APPROVED_HDI" : "READ_ONLY_STOR04_CHECK_APPROVED_HDI");
            assertThrows(java.lang.reflect.InvocationTargetException.class, () -> validate.invoke(null, env, commit));
            env.put("AINV_STOR04_IT_CONSENT", "WRITE_SYNTHETIC_DATA_TO_APPROVED_HDI");
            assertThrows(java.lang.reflect.InvocationTargetException.class, () -> validate.invoke(null, env, commit));
        }
        env.clear();
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> validate.invoke(null, env, commit));
    }
}
