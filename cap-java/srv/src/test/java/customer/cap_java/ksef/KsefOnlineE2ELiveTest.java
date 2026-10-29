package customer.cap_java.ksef;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import com.sap.cds.ql.Insert;
import com.sap.cds.services.persistence.PersistenceService;
import customer.cap_java.Application;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;
import customer.cap_java.sap.SapInvoiceSnapshot;

/**
 * Jawny harness E2E TEST: oficjalny fixture XML + XSD, trwała CAP/H2, prawdziwy SDK.
 * SEND tworzy marker CREATE_NEW przed pierwszą siecią. RESUME nie tworzy nowej operacji.
 * Źródłowa migawka jest syntetyczna lokalnie; test nie dowodzi odczytu H01 ani WRITE SAP.
 */
@Tag("live-ksef")
@EnabledIfEnvironmentVariable(named = "AINV_KSEF_LIVE_SEND", matches = "true")
class KsefOnlineE2ELiveTest {
    @Test void oneSyntheticInvoiceOrReadOnlyResume() throws Exception {
        String mode = required("AINV_KSEF_E2E_MODE");
        assertTrue(Set.of("SEND", "RESUME").contains(mode), "AINV_KSEF_E2E_MODE must be SEND or RESUME");
        assertEquals("true", required("AINV_KSEF_SYNTHETIC"));
        String nip = required("AINV_KSEF_NIP");
        Path directory = Path.of(required("AINV_KSEF_E2E_DIR")).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path marker = directory.resolve("send-attempt.json");
        Path dbPath = directory.resolve("process");
        boolean initialize = !Files.exists(directory.resolve("process.mv.db"));
        UUID id;
        byte[] xml = null;
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        if ("SEND".equals(mode)) {
            assertFalse(Files.exists(marker), "Existing attempt must be resumed; never delete marker to retry send");
            xml = Files.readAllBytes(Path.of(required("AINV_KSEF_XML")));
            // Zewnętrzny etap lxml waliduje pełne, oficjalne XSD bez rozwijania DFA JAXP.
            // Do sieci mogą trafić wyłącznie dokładne bajty z potwierdzonym digestem tego etapu.
            assertEquals(required("AINV_KSEF_VALIDATED_SHA256"), KsefInvoiceSnapshotRegistry.sha256Hex(xml));
            var parser = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            parser.setNamespaceAware(true);
            parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            parser.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            parser.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = parser.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml));
            var seller = (org.w3c.dom.Element) document.getElementsByTagNameNS("*", "Podmiot1").item(0);
            assertEquals(nip, seller.getElementsByTagNameNS("*", "NIP").item(0).getTextContent());
            id = UUID.randomUUID();
            Files.writeString(marker, mapper.writeValueAsString(Map.of("operationId", id.toString(), "environment", "TEST",
                    "xmlSha256", KsefInvoiceSnapshotRegistry.sha256Hex(xml), "synthetic", true)),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } else {
            assertTrue(Files.exists(marker), "RESUME requires persisted marker");
            id = UUID.fromString(mapper.readTree(Files.readString(marker)).get("operationId").asText());
            assertFalse(initialize, "RESUME requires existing database; do not recreate it");
        }
        String url = "jdbc:h2:file:" + dbPath.toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE";
        // Collector jest wyłączony tylko w harnessie: sam wywołujemy TEN SAM worker poza transakcją
        // i czekamy na trwały termin retryNotBefore. Natywny dispatch/restart sprawdzają testy lokalne.
        try (var app = new SpringApplicationBuilder(Application.class).web(WebApplicationType.SERVLET).profiles("test")
                .run("--server.port=0", "--spring.datasource.url=" + url, "--spring.sql.init.mode=" + (initialize ? "always" : "never"),
                        "--ainv.ksef.enabled=true", "--ainv.ksef.nip=" + nip, "--ainv.ksef.test-xades=true",
                        "--ainv.ksef.local-store=" + directory.resolve("documents"), "--ainv.sap-write.enabled=false",
                        "--cds.environment.command.enabled=false", "--cds.outbox.services.KsefSubmissionOutbox.startCollector=false",
                        "--cds.outbox.services.SapResultOutbox.startCollector=false", "--logging.level.root=WARN", "--spring.main.banner-mode=off")) {
            var worker = app.getBean(KsefSubmissionWorker.class);
            if ("SEND".equals(mode)) {
                byte[] exactBytes = xml;
                worker.tx(() -> {
                    var db = app.getBean(PersistenceService.class);
                    var sap = syntheticSource(nip, id);
                    // To wyłącznie lokalna syntetyczna migawka, nie rekord H01.
                    UUID snapshot = app.getBean(KsefInvoiceSnapshotRegistry.class).register(sap, nip, "1000", "TEST", "FA(3)", exactBytes);
                    String invoiceId = UUID.randomUUID().toString();
                    db.run(Insert.into(KsefSubmissionWorker.INVOICES).entry(Map.of("ID", invoiceId, "taxpayerNip", nip,
                            "direction", "OUTBOUND", "sapDocumentRef", id.toString(), "businessStatus", "UNCONFIRMED")));
                    db.run(Insert.into(KsefSubmissionWorker.OPERATIONS).entry(Map.of("ID", id.toString(), "taxpayerNip", nip,
                            "direction", "OUTBOUND", "environment", "TEST", "operationType", "SEND_INVOICE", "status", "NEW",
                            "idempotencyKey", "synthetic-e2e-" + id, "sapDocumentRef", id.toString(), "invoice_ID", invoiceId)));
                    worker.enqueue(id, snapshot, exactBytes);
                    return null;
                });
            }
            long deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos();
            Map<String, Object> result;
            do {
                result = worker.status(id);
                if ("RESUME".equals(mode)) {
                    assertNotEquals("PREPARED", result.get("state"), "RESUME never initiates a new send");
                }
                if ("RECONCILIATION_REQUIRED".equals(result.get("state"))) {
                    if ("RESUME".equals(mode)) { worker.reconcile(id); result = worker.status(id); }
                    if ("RECONCILIATION_REQUIRED".equals(result.get("state"))) { break; }
                }
                if (Set.of("COMPLETED", "REJECTED", "READ_RETRY_EXHAUSTED").contains(result.get("state"))) { break; }
                if (result.get("retryNotBefore") instanceof Instant due && due.isAfter(Instant.now())) {
                    Thread.sleep(Math.min(1000, Math.max(1, Duration.between(Instant.now(), due).toMillis())));
                    continue;
                }
                try { worker.process(id); }
                catch (KsefFailure failure) {
                    if (!failure.retryable()) { throw failure; }
                }
                result = worker.status(id);
                System.out.println("KSEF TEST operation=" + id + " state=" + result.get("state") + " code=" + result.get("lastCode"));
            } while (System.nanoTime() < deadline);
            result = worker.status(id);
            var report = new java.util.LinkedHashMap<String, Object>();
            for (String field : List.of("ID", "state", "xmlSha256", "sessionReference", "invoiceReference", "ksefNumber", "upoStorageRef", "lastCode")) {
                report.put(field, result.get(field));
            }
            Files.writeString(directory.resolve("result.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            assertEquals("COMPLETED", result.get("state"), "Existing operation retained; inspect result.json and RESUME without another SEND");
            byte[] upo = app.getBean(KsefDocumentStore.class).get((String) result.get("upoStorageRef"));
            assertTrue(upo.length > 0);
            System.out.println("KSEF TEST SUCCESS operation=" + id + " number=" + result.get("ksefNumber") + " UPO bytes=" + upo.length);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) { throw new IllegalArgumentException(name + " is required"); }
        return value;
    }

    private static SapInvoiceSnapshot syntheticSource(String nip, UUID id) {
        Instant version = Instant.parse("2026-10-29T12:00:00Z");
        return new SapInvoiceSnapshot(Map.of("InvoiceUUID", id.toString(), "Direction", "OUTBOUND", "SellerNip", nip,
                "CompanyCode", "1000", "LocalLastChangedAt", version), List.of(), List.of());
    }
}
