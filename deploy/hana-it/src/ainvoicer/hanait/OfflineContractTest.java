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
}
