package customer.cap_java.ksef.storage;

import static customer.cap_java.ksef.inbound.InboundTestSupport.*;
import static customer.cap_java.ksef.inbound.persistence.InboundPersistence.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import com.sap.cds.ql.Select;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.Application;
import customer.cap_java.ksef.fa3.Fa3SchemaValidator;
import customer.cap_java.ksef.inbound.*;
import customer.cap_java.ksef.inbound.fa3.SecureFa3InvoiceParser;
import customer.cap_java.ksef.inbound.persistence.*;
import customer.cap_java.ksef.inbound.sync.*;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient;

/**
 * Dwie osobne JVM z plikową H2. Pierwsza ginie (halt) po trwałym PUT do storage, przed finalizacją CAP;
 * druga odtwarza staging, ponawia create-only PUT (409 → weryfikacja) i odczytuje dokładne bajty.
 * Storage = emulator Azure Blob w procesie testu (przeżywa obie JVM jak usługa zewnętrzna).
 */
class DurableStorageProcessRecoveryTest {
    @TempDir Path directory;

    @Test void jvmCrashBetweenDurablePutAndCapFinalizationRecoversWithSingleObject() throws Exception {
        try (var fake = new FakeAzureBlobServer()) {
            fake.now = NOW.minus(Duration.ofHours(2));
            runChild("crash", 23, fake.port());
            assertEquals(1, fake.created.get());
            assertEquals(1, fake.blobs.size());
            runChild("recover", 0, fake.port());
            assertEquals(1, fake.created.get(), "brak drugiego obiektu po restarcie");
            assertTrue(fake.conflicts.get() >= 1);
            assertEquals(1, fake.blobs.size());
        }
    }

    private void runChild(String mode, int expectedExit, int port) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path args = directory.resolve(mode + ".args");
        Path log = directory.resolve(mode + ".log");
        String body = "-Dspring.devtools.restart.enabled=false\n-cp\n\"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"\n" + DurableStorageProcessRecoveryTest.class.getName() + "\n" + mode + "\n\""
                + directory.toString().replace("\\", "\\\\") + "\"\n" + port + "\n";
        Files.writeString(args, body, StandardCharsets.UTF_8);
        String binary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(binary, "@" + args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "child JVM timed out: " + mode);
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
        int port = Integer.parseInt(args[2]);
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
            var settings = ObjectStorageSettings.defaults("ainvoicer-test", EnumSet.of(KsefEnvironment.TEST))
                    .withLoopbackHttpForTests(Duration.ofSeconds(5));
            var binding = new AzureObjectStoreBinding(URI.create("http://127.0.0.1:" + port + "/"
                    + FakeAzureBlobServer.ACCOUNT + "/" + FakeAzureBlobServer.CONTAINER), FakeAzureBlobServer.CONTAINER,
                    FakeAzureBlobServer.SAS, AzureObjectStoreBinding.Access.READ_WRITE);
            var store = new ContentAddressedDocumentStore(new AzureBlobSasClient(binding, settings), settings,
                    binding.access(), CLOCK, d -> { }).forScope(DocumentScope.inbound(KsefEnvironment.TEST));
            if (crash) {
                assertTrue(checkpoints.commit(scope, 0, from, NOW));
                new CqnInboundSyncLeaseRepository(persistence, CLOCK).claim(scope, Duration.ofMinutes(2)).orElseThrow();
                documents.prepare(metadata);
                store.put(xml);
                // Pomiędzy potwierdzonym trwałym PUT a finalizacją CAP; brak shutdown hook i close H2.
                Runtime.getRuntime().halt(23);
            }
            var pending = persistence.tx(() -> app.getBean(PersistenceService.class).run(Select.from(DOCUMENTS)).single());
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
            assertEquals(hwm, checkpoints.find(scope).orElseThrow().continuationFrom());
            var stored = persistence.tx(() -> app.getBean(PersistenceService.class).run(Select.from(DOCUMENTS)).single());
            String sha = KsefInvoiceSnapshotRegistry.sha256Hex(xml);
            assertEquals("ksefdoc:v1:TEST:INBOUND:-:" + sha, stored.get("xmlStorageRef"));
            byte[] restored = store.get((String) stored.get("xmlStorageRef"));
            assertArrayEquals(xml, restored);
            assertEquals(stored.get("sha256"), KsefInvoiceSnapshotRegistry.sha256Hex(restored));
            assertEquals(1, persistence.tx(() -> app.getBean(PersistenceService.class).run(Select.from(GRANTS)).list().size()));
        }
    }
}
