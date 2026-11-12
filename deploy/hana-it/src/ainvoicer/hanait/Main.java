package ainvoicer.hanait;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Izolowany entry point R2. Nie uruchamia Application.main, HTTP ani domyślnej fazy. */
public final class Main {
    static final String IT = "customer.cap_java.hana.HanaPersistenceIT";
    static final Set<String> READ = Set.of("check", "recover", "verify-cleanup");
    static final Set<String> PHASES = Set.of("check", "seed", "recover", "contend", "cleanup", "verify-cleanup");
    static final Map<String, String> FLAGS = Map.ofEntries(
        Map.entry("SPRING_PROFILES_ACTIVE", "cf"),
        Map.entry("AINV_DEPLOYMENT_IDENTITY_SMOKE", "false"),
        Map.entry("AINV_DEPLOYMENT_HANA_READINESS", "true"),
        Map.entry("AINV_KSEF_ENABLED", "false"), Map.entry("AINV_KSEF_INBOUND_ENABLED", "false"),
        Map.entry("AINV_SAP_WRITE_ENABLED", "false"), Map.entry("AINV_SAP_WRITE_SWEEPER_ENABLED", "false"),
        Map.entry("AINV_KSEF_STORAGE_PROVIDER", "off"), Map.entry("SPRING_SQL_INIT_MODE", "never"));

    static void require(boolean condition) {
        if (!condition) throw new IllegalStateException("R2_GATE_BLOCKED");
    }

    /** Walidacja przed JUnit/Spring: zgoda, dokładny CF/HDI, brak tras, flagi OFF i binding XSUAA. */
    static void gate(Map<String, String> env, String phase) throws Exception {
        require(Runtime.version().feature() == 25);
        require(PHASES.contains(phase));
        require(!"true".equalsIgnoreCase(env.get("GITHUB_ACTIONS")) && !"true".equalsIgnoreCase(env.get("CI")));
        String consent = env.get("AINV_HANA_TEST_CONSENT");
        require("WRITE_SYNTHETIC_DATA_TO_APPROVED_HDI".equals(consent)
            || (READ.contains(phase) && "READ_ONLY_CHECK_APPROVED_HDI".equals(consent)));
        require(phase.equals(env.get("AINV_HANA_TEST_PHASE")));
        for (var flag : FLAGS.entrySet()) require(flag.getValue().equals(env.get(flag.getKey())));
        require("ainvoicer-db".equals(env.get("AINV_HANA_TEST_HDI_NAME")));
        require(env.getOrDefault("AINV_HANA_TEST_RUN_ID", "").matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"));
        require(customer.cap_java.ksef.inbound.KsefTaxpayerContext.isValidNip(env.get("AINV_HANA_TEST_NIP")));
        // Nadpisania konfiguracji/credentiali spoza platformy nie należą do kontraktu R2.
        for (String key : env.keySet()) {
            require(!key.startsWith("SPRING_DATASOURCE") && !key.startsWith("SPRING_CONFIG")
                && !key.startsWith("CDS_") && !Set.of("SPRING_APPLICATION_JSON", "SERVICE_BINDING_ROOT",
                    "VCAP_SERVICES_FILE_PATH", "AINV_KSEF_TOKEN", "AINV_KSEF_LOCAL_STORE").contains(key));
        }
        var mapper = new ObjectMapper();
        JsonNode app = mapper.readTree(env.getOrDefault("VCAP_APPLICATION", "{}"));
        require("ainvoicer-hana-it".equals(app.path("application_name").asText()));
        require("https://api.cf.eu20-001.hana.ondemand.com".equals(app.path("cf_api").asText()));
        require("National Center for Cryptography_dev-v2xzij9s".equals(app.path("organization_name").asText()));
        require("dev".equals(app.path("space_name").asText()));
        require(app.path("application_uris").isArray() && app.path("application_uris").isEmpty());
        require(!app.has("uris") || (app.path("uris").isArray() && app.path("uris").isEmpty()));
        JsonNode services = mapper.readTree(env.getOrDefault("VCAP_SERVICES", "{}"));
        require(services.isObject());
        int hana = 0, auth = 0, total = 0;
        for (JsonNode bindings : services) {
            require(bindings.isArray());
            for (JsonNode binding : bindings) {
                total++;
                require(!binding.has("key_name") && !binding.has("service_key_name"));
                if ("hana".equals(binding.path("label").asText())) {
                    hana++;
                    require("ainvoicer-db".equals(binding.path("name").asText()));
                    require("hdi-shared".equals(binding.path("plan").asText()));
                    var c = binding.path("credentials");
                    for (String key : new String[]{"schema", "user", "password", "host", "port"})
                        require(!c.path(key).asText().isBlank());
                    require(!"DBADMIN".equalsIgnoreCase(c.path("user").asText().trim()));
                } else if ("xsuaa".equals(binding.path("label").asText())) {
                    auth++;
                    require("ainvoicer-auth".equals(binding.path("name").asText()));
                    require("application".equals(binding.path("plan").asText()));
                    for (String key : new String[]{"clientid", "clientsecret", "url", "identityzoneid"})
                        require(!binding.path("credentials").path(key).asText().isBlank());
                } else require(false);
            }
        }
        require(hana == 1 && auth == 1 && total == 2);
    }

    /** Jedna selekcja JUnit; pominięcie/abort/brak testów nigdy nie daje kodu 0. Bez stack trace i sekretów. */
    public static void main(String[] args) {
        PrintStream safe = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        int code = 2;
        try {
            if (args.length == 1 && "--self-test".equals(args[0])) {
                code = 1;
                // Wyłącznie test kontraktu offline, bez Springa, DataSource i VCAP.
                code = junit("ainvoicer.hanait.OfflineContractTest") ? 0 : 1;
                safe.println("R2_OFFLINE_SELF_TEST=" + (code == 0 ? "PASS" : "FAIL"));
            } else {
                require(args.length == 2 && Set.of("--run", "--preflight").contains(args[0]));
                gate(System.getenv(), args[1]);
                if ("--preflight".equals(args[0])) {
                    Class.forName(IT, false, Main.class.getClassLoader());
                    safe.println("R2_PREFLIGHT_PASS=" + args[1]);
                    code = 0;
                } else {
                    code = 1;
                    System.setProperty("ainv.hana.integration", "true");
                    System.setProperty("spring.main.web-application-type", "none");
                    System.setProperty("spring.task.scheduling.enabled", "false");
                    System.setProperty("cds.outbox.persistent.enabled", "false");
                    System.setProperty("cds.outbox.inMemory.enabled", "false");
                    System.setProperty("cds.environment.local.defaultEnvPath", "r2-no-local-env.json");
                    // Przepuszczamy wyłącznie stałe znaczniki harnessu; surowe błędy JDBC/SDK pozostają wyciszone.
                    System.setOut(new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8) {
                        @Override public void println(String line) {
                            if (line != null && (line.matches("HANA_TEST_PHASE_PASS=(check|seed|recover|contend|cleanup|verify-cleanup)")
                                || line.matches("HANA_UNIQUE_SQLSTATE=[A-Z0-9]{5};VENDOR_CODE=-?[0-9]{1,9}")
                                || line.matches("HANA_CAS_CHANGED=[01];LEASE_CLAIMED=(true|false)"))) safe.println(line);
                        }
                    });
                    code = junit(IT) ? 0 : 1;
                    safe.println("R2_PHASE_RESULT=" + args[1] + ";EXIT=" + code);
                }
            }
        } catch (Throwable error) {
            safe.println("R2_BLOCKED_OR_FAILED;EXIT=" + code);
        }
        System.exit(code);
    }

    static boolean junit(String name) {
        var summary = new SummaryGeneratingListener();
        try (var session = LauncherFactory.openSession()) {
            var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(name)).build();
            session.getLauncher().execute(request, summary);
        }
        var result = summary.getSummary();
        return result.getTestsFoundCount() > 0 && result.getTestsFoundCount() == result.getTestsSucceededCount()
            && result.getTotalFailureCount() == 0 && result.getTestsAbortedCount() == 0
            && result.getTestsSkippedCount() == 0;
    }
}
