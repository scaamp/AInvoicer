package customer.cap_java.ksef;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.persistence.PersistenceService;
import customer.cap_java.Application;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;

/** Dwa rzeczywiste procesy kontekstu CAP, plikowa H2 i natywny collector; zero HTTP. */
class KsefSubmissionRecoveryTest {
    @TempDir Path directory;

    @TestConfiguration(proxyBeanMethods = false)
    static class Offline {
        @Bean @Primary KsefAuthentication offlineAuthentication() { return mock(KsefAuthentication.class); }
        @Bean @Primary KsefOnlineSession offlineOnlineSession() { return mock(KsefOnlineSession.class); }
        @Bean @Primary KsefFa3Documents offlineFa3Documents() { return mock(KsefFa3Documents.class); }
    }

    @Test void persistedInFlightSendAfterRestartBecomesReconciliationWithoutResending() throws Exception {
        UUID id = UUID.randomUUID();
        UUID snapshot = UUID.randomUUID();
        byte[] bytes = "<synthetic-fixture/>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = KsefInvoiceSnapshotRegistry.sha256Hex(bytes);
        try (var app = start(false, true)) {
            var db = app.getBean(PersistenceService.class);
            var worker = app.getBean(KsefSubmissionWorker.class);
            worker.tx(() -> {
                String sapId = UUID.randomUUID().toString();
                db.run(Insert.into(KsefSubmissionWorker.OPERATIONS).entry(Map.of("ID", id.toString(), "sapDocumentRef", sapId,
                        "taxpayerNip", "1234563218", "direction", "OUTBOUND", "environment", "TEST", "operationType", "SEND_INVOICE",
                        "status", "NEW", "idempotencyKey", id.toString())));
                db.run(Insert.into(KsefSubmissionWorker.SNAPSHOTS).entry(Map.ofEntries(Map.entry("ID", snapshot.toString()),
                        Map.entry("invoiceUUID", sapId), Map.entry("taxpayerNip", "1234563218"), Map.entry("direction", "OUTBOUND"),
                        Map.entry("environment", "TEST"), Map.entry("companyCode", "1000"), Map.entry("schemaVersion", "FA(3)"),
                        Map.entry("sapHeaderVersion", "2026-10-29T12:00:00Z"), Map.entry("itemsFingerprint", "a".repeat(64)),
                        Map.entry("xmlSha256", hash), Map.entry("xmlByteLength", bytes.length))));
                worker.enqueue(id, snapshot, bytes);
                db.run(Update.entity(KsefSubmissionWorker.SUBMISSIONS).byId(id.toString()).data(Map.of("state", "SENDING",
                        "sessionReference", "previous-session", "leaseUntil", Instant.now().minusSeconds(1))));
                return null;
            });
            assertEquals("SENDING", worker.status(id).get("state"));
        }
        try (var app = start(true, false)) {
            var worker = app.getBean(KsefSubmissionWorker.class);
            var db = app.getBean(PersistenceService.class);
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            boolean complete = false;
            while (System.nanoTime() < deadline) {
                complete = "RECONCILIATION_REQUIRED".equals(worker.status(id).get("state"))
                        && db.run(Select.from("cds.outbox.Messages").where(m -> m.get("target").eq("KsefSubmissionOutbox"))).list().isEmpty();
                if (complete) { break; }
                Thread.sleep(50);
            }
            assertTrue(complete, "collector must consume interrupted effect without resending");
            assertEquals("KSEF_INTERRUPTED_SENDING", worker.status(id).get("lastCode"));
            verifyNoInteractions(app.getBean("offlineAuthentication"), app.getBean("offlineOnlineSession"));
            // Kolejka jest pusta przed końcem callbacku; pozwalamy zakończyć scheduler przed zamknięciem H2.
            Thread.sleep(250);
        }
    }

    private ConfigurableApplicationContext start(boolean collector, boolean initialize) {
        String url = "jdbc:h2:file:" + directory.resolve("process").toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE";
        return new SpringApplicationBuilder(Application.class, Offline.class).web(WebApplicationType.SERVLET).profiles("test")
                .run("--server.port=0", "--spring.datasource.url=" + url, "--spring.sql.init.mode=" + (initialize ? "always" : "never"),
                        "--ainv.ksef.enabled=true", "--ainv.ksef.nip=1234563218", "--ainv.ksef.local-store=" + directory.resolve("documents"),
                        "--cds.environment.command.enabled=false", "--cds.outbox.services.KsefSubmissionOutbox.startCollector=" + collector,
                        "--cds.outbox.services.KsefSubmissionOutbox.maxPause=1s", "--logging.level.root=WARN", "--spring.main.banner-mode=off");
    }
}
