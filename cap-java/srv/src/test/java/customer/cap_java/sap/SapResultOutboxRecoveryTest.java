package customer.cap_java.sap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.sap.cds.ql.Select;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.RequestContext;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.Application;
import customer.cap_java.sap.SapKsefResultWriteAdapter.Outcome;
import customer.cap_java.sap.SapKsefResultWriteAdapter.WriteFailure;
import customer.cap_java.sap.SapKsefResultWriteAdapter.WriteResult;

/** Restart Spring/CAP + plikowa baza H2; rzeczywisty collector/retry, wyłącznie mock SAP. */
class SapResultOutboxRecoveryTest {
    private static final List<SapKsefResultCommand> CALLS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger ATTEMPTS = new AtomicInteger();
    @TempDir Path temporary;

    @TestConfiguration(proxyBeanMethods = false)
    static class OfflineSap {
        @Bean @Primary
        SapKsefResultWriteAdapter offlineSapAdapter() {
            var adapter = mock(SapKsefResultWriteAdapter.class);
            when(adapter.apply(any())).thenAnswer(call -> {
                SapKsefResultCommand command = call.getArgument(0);
                CALLS.add(command);
                if (ATTEMPTS.incrementAndGet() == 1) {
                    // SAP mógł wykonać zapis, lecz odpowiedź zaginęła.
                    throw new WriteFailure("SAP_TRANSPORT_UNKNOWN", true);
                }
                return new WriteResult(Outcome.ALREADY_APPLIED, command.invoiceId(), command.ksefNumber(),
                        "TEST", SapKsefResultWritebackTest.VERSION, command.requestId());
            });
            return adapter;
        }
    }

    @Test void committedQueueSurvivesRestartAndRetriesSameCommand() throws Exception {
        CALLS.clear();
        ATTEMPTS.set(0);
        UUID id = UUID.randomUUID();
        UUID invoice = UUID.randomUUID();
        try (var app = start(false, true)) {
            var runtime = app.getBean(CdsRuntime.class);
            var db = app.getBean(PersistenceService.class);
            runtime.requestContext().systemUser().run((RequestContext request) ->
                    runtime.changeSetContext().run(cs -> {
                        SapKsefResultWritebackTest.seed(db, id, invoice);
                        app.getBean(SapKsefResultWriteback.class).enqueue(id, "TST1", SapKsefResultWritebackTest.VERSION);
                        assertTrue(CALLS.isEmpty(), "worker cannot run before commit");
                    }));
            assertEquals(1, db.run(Select.from("cds.outbox.Messages")
                    .where(r -> r.get("target").eq("SapResultOutbox"))).list().size());
            assertTrue(CALLS.isEmpty(), "collector deliberately stopped in first process");
        }
        try (var app = start(true, false)) {
            var db = app.getBean(PersistenceService.class);
            // CAP scheduler ma własny 30-sekundowy lookup po restarcie.
            long deadline = System.nanoTime() + Duration.ofSeconds(50).toNanos();
            while (System.nanoTime() < deadline) {
                var row = db.run(Select.from(SapKsefResultWriteback.WRITES).byId(id.toString())).single();
                if ("SUCCEEDED".equals(row.get("state"))) {
                    break;
                }
                Thread.sleep(50);
            }
            var row = db.run(Select.from(SapKsefResultWriteback.WRITES).byId(id.toString())).single();
            assertEquals("SUCCEEDED", row.get("state"), () -> "calls=" + CALLS.size() + ", queue="
                    + db.run(Select.from("cds.outbox.Messages").columns("target", "status", "attempts", "lastError")).list());
            assertEquals("ALREADY_APPLIED", row.get("outcome"));
            assertEquals(2, CALLS.size());
            assertEquals(CALLS.getFirst(), CALLS.getLast());
            assertEquals(id, CALLS.getFirst().requestId());
            assertEquals(SapKsefResultWritebackTest.VERSION, CALLS.getFirst().expectedVersion());
            assertTrue(db.run(Select.from("cds.outbox.Messages")
                    .where(r -> r.get("target").eq("SapResultOutbox"))).list().isEmpty());
            // Dajemy zakończyć się callbackom collectora przed zamknięciem puli H2.
            Thread.sleep(250);
        }
    }

    private ConfigurableApplicationContext start(boolean collector, boolean initialize) {
        String url = "jdbc:h2:file:" + temporary.resolve("writeback").toAbsolutePath().toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE";
        return new SpringApplicationBuilder(Application.class, OfflineSap.class).web(WebApplicationType.SERVLET)
                .profiles("test", "writeback-local").run("--spring.datasource.url=" + url,
                        "--cds.environment.command.enabled=false",
                        "--server.port=0",
                        "--logging.level.com.sap.cds.services.impl.outbox=DEBUG",
                        "--spring.sql.init.mode=" + (initialize ? "always" : "never"),
                        "--cds.outbox.services.SapResultOutbox.startCollector=" + collector,
                        "--cds.outbox.services.SapResultOutbox.maxPause=1s",
                        "--logging.level.root=WARN", "--spring.main.banner-mode=off");
    }
}
