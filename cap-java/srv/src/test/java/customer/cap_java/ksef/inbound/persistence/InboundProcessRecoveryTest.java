package customer.cap_java.ksef.inbound.persistence;

import static customer.cap_java.ksef.inbound.InboundTestSupport.*;
import static customer.cap_java.ksef.inbound.persistence.InboundPersistence.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import com.sap.cds.ql.Select;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.Application;
import customer.cap_java.ksef.LocalTestDocumentStore;
import customer.cap_java.ksef.fa3.Fa3SchemaValidator;
import customer.cap_java.ksef.inbound.*;
import customer.cap_java.ksef.inbound.fa3.SecureFa3InvoiceParser;
import customer.cap_java.ksef.inbound.sync.*;
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient;

/** Dwie osobne JVM; pierwsza ginie przez halt po put, druga odzyskuje plikową H2 i XML. Zero HTTP. */
class InboundProcessRecoveryTest {
    @TempDir Path directory;

    @Test void actualJvmCrashAfterPutRecoversWindowAndExpiredLeaseWithoutDuplicates() throws Exception {
        runChild("crash", 23);
        runChild("recover", 0);
    }

    private void runChild(String mode, int expectedExit) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path args = directory.resolve(mode + ".args");
        Path log = directory.resolve(mode + ".log");
        // Argfile omija limit Windows command line; wszystkie ścieżki są danymi, bez powłoki.
        String body = "-Dspring.devtools.restart.enabled=false\n-cp\n\"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"\n" + InboundProcessRecoveryTest.class.getName() + "\n" + mode + "\n\""
                + directory.toString().replace("\\", "\\\\") + "\"\n";
        Files.writeString(args, body, StandardCharsets.UTF_8);
        String binary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(binary, "@" + args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "child JVM timed out: " + mode);
            assertEquals(expectedExit, process.exitValue(), () -> {
                try { return Files.readString(log); } catch (Exception e) { return mode; }
            });
        } finally {
            if (process.isAlive()) { process.destroyForcibly().waitFor(); }
        }
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path directory = Path.of(args[1]);
        boolean crash = "crash".equals(mode);
        String url = "jdbc:h2:file:" + directory.resolve("staging").toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
        try (var app = new SpringApplicationBuilder(Application.class).web(WebApplicationType.SERVLET).profiles("test")
                .run("--server.port=0", "--spring.datasource.url=" + url,
                        "--spring.sql.init.mode=" + (crash ? "always" : "never"),
                        "--cds.environment.command.enabled=false", "--ainv.sap-write.sweeper.enabled=false",
                        "--logging.level.root=ERROR", "--spring.main.banner-mode=off")) {
            var persistence = new InboundPersistence(app.getBean(PersistenceService.class), app.getBean(CdsRuntime.class));
            var documents = new CqnInboundDocumentRepository(persistence, CLOCK);
            var checkpoints = new CqnSyncCheckpointRepository(persistence);
            var scope = SyncScope.of(CONTEXT, KsefSubjectRole.BUYER);
            var from = OffsetDateTime.parse("2026-11-01T00:00:00Z");
            var hwm = OffsetDateTime.parse("2026-11-09T10:00:00Z");
            byte[] xml = resource("fa3-purchase-synthetic.xml");
            var item = sdkItem(metadataJson(ksefNumber(1), "2026-11-02T09:00:00Z", hashOf(xml)));
            var metadata = new InvoiceMetadataMapper().map(CONTEXT, KsefSubjectRole.BUYER, item);
            var store = new LocalTestDocumentStore(directory.resolve("xml").toString());
            if (crash) {
                assertTrue(checkpoints.commit(scope, 0, from, NOW));
                new CqnInboundSyncLeaseRepository(persistence, CLOCK).claim(scope, Duration.ofMinutes(2)).orElseThrow();
                documents.prepare(metadata);
                store.put(xml);
                // Dokładnie pomiędzy trwałym put a finalizacją CAP. Brak shutdown hook i close H2.
                Runtime.getRuntime().halt(23);
            }
            assertEquals(from, checkpoints.find(scope).orElseThrow().continuationFrom());
            assertEquals(1, checkpoints.find(scope).orElseThrow().version());
            var pending = persistence.tx(() -> persistence.db.run(Select.from(DOCUMENTS)).single());
            assertEquals("PENDING", pending.get("ingestionStatus"));
            assertNull(pending.get("xmlStorageRef"));
            var clock = Clock.fixed(NOW.plus(Duration.ofMinutes(16)), ZoneOffset.UTC);
            var leases = new CqnInboundSyncLeaseRepository(persistence, clock);
            var claim = leases.claim(scope, Duration.ofMinutes(2)).orElseThrow();
            var sdk = mock(KSeFClient.class);
            when(sdk.getInvoice(anyString(), anyString())).thenReturn(xml);
            when(sdk.queryInvoiceMetadata(eq(0), anyInt(), any(), any(), any()))
                    .thenReturn(response(false, false, hwm.toString(), item));
            var retrieval = new InvoiceRetrievalService(inboundClient(sdk), ReadRetryPolicy.noRetry(clock));
            var sink = new PersistentInboundInvoiceSink(scope, token(), retrieval, documents, store,
                    new SecureFa3InvoiceParser(), new Fa3SchemaValidator());
            var runner = new InvoiceSyncRunner(retrieval, InvoiceSyncWindowPlanner.defaults(clock),
                    leases.fenced(checkpoints, claim), clock);
            assertEquals(InvoiceSyncRunner.Status.COMMITTED, runner.runOnce(CONTEXT, KsefSubjectRole.BUYER,
                    token(), from, sink).status());
            assertEquals(2, checkpoints.find(scope).orElseThrow().version());
            assertEquals(hwm, checkpoints.find(scope).orElseThrow().continuationFrom());
            assertEquals(1, persistence.tx(() -> persistence.db.run(Select.from(DOCUMENTS)).list().size()));
            assertEquals(1, persistence.tx(() -> persistence.db.run(Select.from(GRANTS)).list().size()));
            try (var files = Files.list(directory.resolve("xml"))) { assertEquals(1, files.count()); }
            assertTrue(new CqnInboundOverviewRepository(persistence).find(scope, 0, 10).getFirst().xmlAvailable());
        }
    }
}
