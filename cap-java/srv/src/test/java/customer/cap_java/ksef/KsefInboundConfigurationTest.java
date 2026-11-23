package customer.cap_java.ksef;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.ksef.inbound.DurableInboundDocumentStore;
import customer.cap_java.ksef.inbound.KsefEnvironment;
import customer.cap_java.ksef.inbound.KsefTaxpayerContext;
import customer.cap_java.ksef.inbound.sync.InboundSynchronization;
import customer.cap_java.ksef.inbound.sync.InboundSyncScheduler;
import customer.cap_java.ksef.storage.InboundDocumentStorage;
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient;
import pl.akmf.ksef.sdk.client.model.auth.TokenInfo;

/** Wiring i fail-closed bez uruchamiania sieci/SAP/BTP. */
class KsefInboundConfigurationTest {
    @TempDir Path directory;

    @Test void readModelDoesNotRequireSynchronizationAuthOrDocumentStore() {
        new ApplicationContextRunner().withUserConfiguration(KsefInboundReadConfiguration.class)
            .withBean(PersistenceService.class, () -> mock(PersistenceService.class))
            .withBean(CdsRuntime.class, () -> mock(CdsRuntime.class))
            .run(app -> {
                assertNull(app.getStartupFailure());
                assertNotNull(app.getBean(customer.cap_java.ksef.inbound.persistence.CqnInboundOverviewRepository.class));
                assertTrue(app.getBeansOfType(InboundSynchronization.class).isEmpty());
                assertTrue(app.getBeansOfType(KsefInboundAccess.class).isEmpty());
                assertTrue(app.getBeansOfType(KsefDocumentStore.class).isEmpty());
            });
    }

    private ApplicationContextRunner context(KsefDocumentStore store) {
        return new ApplicationContextRunner().withUserConfiguration(KsefInboundConfiguration.class,
                KsefInboundSchedulerConfiguration.class)
                .withBean(KsefDocumentStore.class, () -> store);
    }

    @Test void inboundIsOffByDefaultIncludingCloudProfile() {
        var sdk = mock(KSeFClient.class);
        var auth = mock(KsefAuthentication.class);
        context(mock(KsefDocumentStore.class)).withBean(KSeFClient.class, () -> sdk)
                .withBean(KsefAuthentication.class, () -> auth).withPropertyValues("spring.profiles.active=cf")
                .run(app -> {
                    assertNull(app.getStartupFailure());
                    assertTrue(app.getBeansOfType(InboundSynchronization.class).isEmpty());
                    assertTrue(app.getBeansOfType(KsefInboundAccess.class).isEmpty());
                    assertTrue(app.getBeansOfType(InboundSyncScheduler.class).isEmpty());
                    verifyNoInteractions(sdk, auth);
                });
    }

    @Test void cloudOptInWithoutDurableProviderFailsClosedBeforeAnyHttp() {
        context(mock(KsefDocumentStore.class)).withPropertyValues("spring.profiles.active=cf", "ainv.ksef.inbound.enabled=true")
                .run(app -> assertTrue(messages(app.getStartupFailure()).contains("KSEF_INBOUND_DURABLE_STORE_REQUIRED")));
    }

    @Test void cloudDetectedByVcapAlsoFailsClosed() {
        context(mock(KsefDocumentStore.class)).withPropertyValues("VCAP_APPLICATION={}", "ainv.ksef.inbound.enabled=true")
                .run(app -> assertTrue(messages(app.getStartupFailure()).contains("KSEF_INBOUND_DURABLE_STORE_REQUIRED")));
    }

    @Test void durableStoreAloneCannotActivateCloudWithoutApiBudgetAndDeploymentDecision() {
        context(mock(DurableInboundDocumentStore.class)).withPropertyValues("spring.profiles.active=cf", "ainv.ksef.inbound.enabled=true")
                .run(app -> assertTrue(messages(app.getStartupFailure()).contains("KSEF_INBOUND_CLOUD_ACTIVATION_NOT_READY")));
    }

    @Test void schedulerOptInAloneDoesNotEnableSynchronization() {
        context(mock(KsefDocumentStore.class)).withPropertyValues("ainv.ksef.inbound.scheduler.enabled=true")
                .run(app -> {
                    assertNull(app.getStartupFailure());
                    assertTrue(app.getBeansOfType(InboundSyncScheduler.class).isEmpty());
                    assertTrue(app.getBeansOfType(InboundSynchronization.class).isEmpty());
                });
    }

    @Test void schedulerCannotBypassCloudGuardWithDurableStorage() {
        for (String cloud : java.util.List.of("spring.profiles.active=cf", "spring.profiles.active=cloud",
                "spring.profiles.active=prod", "spring.profiles.active=production", "VCAP_APPLICATION={}")) {
            var sdk = mock(KSeFClient.class);
            var auth = mock(KsefAuthentication.class);
            context(mock(DurableInboundDocumentStore.class)).withBean(KSeFClient.class, () -> sdk)
                    .withBean(KsefAuthentication.class, () -> auth)
                    .withPropertyValues(cloud, "ainv.ksef.inbound.enabled=true", "ainv.ksef.inbound.scheduler.enabled=true")
                    .run(app -> {
                        assertTrue(messages(app.getStartupFailure()).contains("KSEF_INBOUND_CLOUD_ACTIVATION_NOT_READY"));
                        verifyNoInteractions(sdk, auth);
                    });
        }
    }

    @Test void localOptInRequiresConfiguredStorage() {
        context(new LocalTestDocumentStore("")).withPropertyValues("ainv.ksef.inbound.enabled=true")
                .run(app -> assertTrue(messages(app.getStartupFailure()).contains("KSEF_INBOUND_DOCUMENT_STORE_REQUIRED")));
    }

    @Test void localWiringDoesNotStartSynchronizationOrAuthenticate() {
        localStartup(false);
    }

    @Test void schedulerOptInRegistersDelayedTaskWithoutStartupHttp() {
        localStartup(true);
    }

    private void localStartup(boolean schedulerEnabled) {
        var sdk = mock(KSeFClient.class);
        var auth = mock(KsefAuthentication.class);
        context(new LocalTestDocumentStore(directory.toString())).withBean(KSeFClient.class, () -> sdk)
                .withBean(KsefAuthentication.class, () -> auth).withBean(Clock.class, Clock::systemUTC)
                .withBean(PersistenceService.class, () -> mock(PersistenceService.class))
                .withBean(CdsRuntime.class, () -> mock(CdsRuntime.class))
                .withPropertyValues("ainv.ksef.inbound.enabled=true", "ainv.ksef.nip=1111111111",
                        "ainv.ksef.inbound.scheduler.enabled=" + schedulerEnabled,
                        "ainv.ksef.inbound.initial-from=2026-11-01T00:00:00Z", "ainv.ksef.local-store=" + directory)
                .run(app -> {
                    assertNull(app.getStartupFailure(), () -> messages(app.getStartupFailure()));
                    assertNotNull(app.getBean(InboundSynchronization.class));
                    assertEquals(schedulerEnabled ? 1 : 0, app.getBeansOfType(InboundSyncScheduler.class).size());
                    if (schedulerEnabled) {
                        var tasks = app.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class)
                                .getScheduledTasks();
                        assertEquals(1, tasks.size());
                        var task = (org.springframework.scheduling.config.IntervalTask) tasks.iterator().next().getTask();
                        assertEquals(java.time.Duration.ofMinutes(15), task.getInitialDelayDuration());
                        assertEquals(java.time.Duration.ofMinutes(15), task.getIntervalDuration());
                    }
                    verifyNoInteractions(sdk, auth);
                });
    }

    @Test void accessAdapterUsesExistingAuthenticationAndRefreshBoundToConfiguredTestNip() {
        var auth = mock(KsefAuthentication.class);
        var context = new KsefTaxpayerContext(KsefEnvironment.TEST, "1111111111");
        var access = token("synthetic-access");
        var refresh = token("synthetic-refresh");
        var tokens = new KsefAuthentication.Tokens(access, refresh);
        when(auth.authenticate(context.nip())).thenReturn(tokens);
        when(auth.refreshIfNeeded(tokens)).thenReturn(tokens);
        var session = new KsefInboundAccess(auth, context).open();
        assertNotNull(session.token());
        assertEquals("InboundAccessSession[REDACTED]", session.toString());
        verify(auth).authenticate("1111111111");
        verify(auth).refreshIfNeeded(tokens);
        assertThrows(IllegalArgumentException.class, () -> new KsefInboundAccess(auth,
                new KsefTaxpayerContext(KsefEnvironment.PROD, context.nip())));
    }

    // ---- KSEF-IN-05 (B1): jawny magazyn INBOUND ma pierwszeństwo, guard CF bez zmian ----------------------

    /** Mock interfejsu trwałego store; osobny typ, aby nie był kandydatem dla wspólnego {@code KsefDocumentStore}. */
    interface DurableInboundDocumentStorePlaceholder extends DurableInboundDocumentStore { }

    private static InboundDocumentStorage inboundStorage() {
        return new InboundDocumentStorage(mock(DurableInboundDocumentStorePlaceholder.class), KsefEnvironment.TEST);
    }

    @Test void dedicatedInboundStorageDoesNotOpenCloudGuardEvenWithSchedulerOptIn() {
        for (String cloud : java.util.List.of("spring.profiles.active=cf", "VCAP_APPLICATION={}")) {
            var sdk = mock(KSeFClient.class);
            var auth = mock(KsefAuthentication.class);
            // Wspólny store NIE jest trwały: gdyby konfiguracja go użyła, kod byłby DURABLE_STORE_REQUIRED.
            context(mock(KsefDocumentStore.class)).withBean(InboundDocumentStorage.class, KsefInboundConfigurationTest::inboundStorage)
                    .withBean(KSeFClient.class, () -> sdk).withBean(KsefAuthentication.class, () -> auth)
                    .withPropertyValues(cloud, "ainv.ksef.inbound.enabled=true", "ainv.ksef.inbound.scheduler.enabled=true")
                    .run(app -> {
                        assertTrue(messages(app.getStartupFailure()).contains("KSEF_INBOUND_CLOUD_ACTIVATION_NOT_READY"));
                        verifyNoInteractions(sdk, auth);
                    });
        }
    }

    @Test void dedicatedInboundStorageIsPreferredLocallyOverUnconfiguredSharedStore() {
        var sdk = mock(KSeFClient.class);
        var auth = mock(KsefAuthentication.class);
        var dedicated = inboundStorage();
        // LocalTestDocumentStore("") bez jawnego katalogu dałby KSEF_INBOUND_DOCUMENT_STORE_REQUIRED.
        context(new LocalTestDocumentStore("")).withBean(InboundDocumentStorage.class, () -> dedicated)
                .withBean(KSeFClient.class, () -> sdk).withBean(KsefAuthentication.class, () -> auth)
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(PersistenceService.class, () -> mock(PersistenceService.class))
                .withBean(CdsRuntime.class, () -> mock(CdsRuntime.class))
                .withPropertyValues("ainv.ksef.inbound.enabled=true", "ainv.ksef.nip=1111111111",
                        "ainv.ksef.inbound.initial-from=2026-11-01T00:00:00Z")
                .run(app -> {
                    assertNull(app.getStartupFailure(), () -> messages(app.getStartupFailure()));
                    var synchronization = app.getBean(InboundSynchronization.class);
                    var field = InboundSynchronization.class.getDeclaredField("store");
                    field.setAccessible(true);
                    assertSame(dedicated.store(), field.get(synchronization));
                    assertEquals(1, app.getBeansOfType(KsefDocumentStore.class).size(), "brak drugiego kandydata KsefDocumentStore");
                    verifyNoInteractions(sdk, auth);
                });
    }

    @Test void hanaStorageWiringIsOffByDefaultAndRegistersNoStore() {
        storageRunner()
                .withBean(javax.sql.DataSource.class, () -> dataSource("HDB"))
                .run(app -> {
                    assertNull(app.getStartupFailure());
                    assertTrue(app.getBeansOfType(InboundDocumentStorage.class).isEmpty());
                    assertTrue(app.getBeansOfType(KsefDocumentStore.class).isEmpty());
                });
    }

    @Test void hanaStorageFailsClosedWithoutHanaAndNeverFallsBackToH2OnCloud() {
        String enabled = "ainv.ksef.inbound.storage.hana.enabled=true";
        for (String product : java.util.List.of("H2", "PostgreSQL")) {
            storageRunner()
                    .withBean(javax.sql.DataSource.class, () -> dataSource(product))
                    .withPropertyValues(enabled)
                    .run(app -> assertTrue(messages(app.getStartupFailure()).contains("KSEF_INBOUND_STORAGE_HANA_REQUIRED"), product));
        }
        // Jawna zgoda na H2 nie działa dla innej bazy ani w chmurze.
        storageRunner()
                .withBean(javax.sql.DataSource.class, () -> dataSource("PostgreSQL"))
                .withPropertyValues(enabled, "ainv.ksef.inbound.storage.hana.allow-local-h2=true")
                .run(app -> assertTrue(messages(app.getStartupFailure()).contains("KSEF_INBOUND_STORAGE_HANA_REQUIRED")));
        for (String cloud : java.util.List.of("spring.profiles.active=cf", "spring.profiles.active=cloud",
                "spring.profiles.active=prod", "spring.profiles.active=production", "VCAP_APPLICATION={}")) {
            storageRunner()
                    .withBean(javax.sql.DataSource.class, () -> dataSource("H2"))
                    .withPropertyValues(enabled, "ainv.ksef.inbound.storage.hana.allow-local-h2=true", cloud)
                    .run(app -> assertTrue(messages(app.getStartupFailure()).contains("KSEF_INBOUND_STORAGE_HANA_REQUIRED_ON_CLOUD"), cloud));
        }
        storageRunner()
                .withPropertyValues(enabled)
                .run(app -> assertTrue(messages(app.getStartupFailure()).contains("KSEF_INBOUND_STORAGE_HANA_REQUIRED")));
        storageRunner()
                .withBean(javax.sql.DataSource.class, () -> dataSource("HDB"))
                .withPropertyValues(enabled, "ainv.ksef.inbound.environment=DEMO")
                .run(app -> assertTrue(messages(app.getStartupFailure()).contains("KSEF_INBOUND_STORAGE_ENVIRONMENT_UNSUPPORTED")));
    }

    @Test void hanaStorageRegistersExactlyOneScopedInboundTestStore() {
        for (String[] scenario : new String[][]{{"HDB", "spring.profiles.active=cf"}, {"HDB", "spring.profiles.active=default"},
                {"H2", "ainv.ksef.inbound.storage.hana.allow-local-h2=true"}}) {
            storageRunner()
                    .withBean(javax.sql.DataSource.class, () -> dataSource(scenario[0]))
                    .withPropertyValues("ainv.ksef.inbound.storage.hana.enabled=true", scenario[1])
                    .run(app -> {
                        assertNull(app.getStartupFailure(), () -> messages(app.getStartupFailure()));
                        var storage = app.getBean(InboundDocumentStorage.class);
                        assertEquals(KsefEnvironment.TEST, storage.environment());
                        assertEquals("ScopedDocumentStore[TEST/INBOUND]", storage.store().toString());
                        assertTrue(app.getBeansOfType(KsefDocumentStore.class).isEmpty(), "uchwyt nie jest KsefDocumentStore");
                    });
        }
    }

    private static org.springframework.boot.test.context.runner.ApplicationContextRunner storageRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(customer.cap_java.ksef.storage.KsefInboundHanaStorageConfiguration.class)
                .withBean(PersistenceService.class, () -> mock(PersistenceService.class))
                .withBean(CdsRuntime.class, () -> mock(CdsRuntime.class));
    }

    /** Syntetyczny DataSource zgłaszający wyłącznie nazwę produktu JDBC (bez połączenia sieciowego). */
    private static javax.sql.DataSource dataSource(String product) {
        try {
            var metadata = mock(java.sql.DatabaseMetaData.class);
            when(metadata.getDatabaseProductName()).thenReturn(product);
            var connection = mock(java.sql.Connection.class);
            when(connection.getMetaData()).thenReturn(metadata);
            var dataSource = mock(javax.sql.DataSource.class);
            when(dataSource.getConnection()).thenReturn(connection);
            return dataSource;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- KSEF-IN-05 (B3): manual import tylko za dwiema flagami, bez startowego HTTP, CF nadal zablokowane ----

    @Test void manualImportRequiresInboundAndOwnFlagAndNeverStartsHttp() {
        for (boolean manual : new boolean[]{false, true}) {
            var sdk = mock(KSeFClient.class);
            var auth = mock(KsefAuthentication.class);
            context(new LocalTestDocumentStore(directory.toString())).withBean(KSeFClient.class, () -> sdk)
                    .withBean(KsefAuthentication.class, () -> auth).withBean(Clock.class, Clock::systemUTC)
                    .withBean(PersistenceService.class, () -> mock(PersistenceService.class))
                    .withBean(CdsRuntime.class, () -> mock(CdsRuntime.class))
                    .withPropertyValues("ainv.ksef.inbound.enabled=true", "ainv.ksef.nip=1111111111",
                            "ainv.ksef.inbound.manual-import.enabled=" + manual,
                            "ainv.ksef.inbound.initial-from=2026-11-01T00:00:00Z", "ainv.ksef.local-store=" + directory)
                    .run(app -> {
                        assertNull(app.getStartupFailure(), () -> messages(app.getStartupFailure()));
                        assertEquals(manual ? 1 : 0, app.getBeansOfType(
                                customer.cap_java.ksef.inbound.sync.InboundManualImport.class).size());
                        verifyNoInteractions(sdk, auth);
                    });
        }
        // Sama flaga importu bez ainv.ksef.inbound.enabled nie tworzy niczego.
        context(mock(KsefDocumentStore.class)).withPropertyValues("ainv.ksef.inbound.manual-import.enabled=true")
                .run(app -> {
                    assertNull(app.getStartupFailure());
                    assertTrue(app.getBeansOfType(customer.cap_java.ksef.inbound.sync.InboundManualImport.class).isEmpty());
                });
        // Chmura: guard bezwarunkowy także z importem i dedykowanym magazynem.
        context(mock(KsefDocumentStore.class)).withBean(InboundDocumentStorage.class, KsefInboundConfigurationTest::inboundStorage)
                .withPropertyValues("VCAP_APPLICATION={}", "ainv.ksef.inbound.enabled=true",
                        "ainv.ksef.inbound.manual-import.enabled=true")
                .run(app -> assertTrue(messages(app.getStartupFailure()).contains("KSEF_INBOUND_CLOUD_ACTIVATION_NOT_READY")));
    }

    private static TokenInfo token(String value) {
        var token = new TokenInfo();
        token.setToken(value);
        token.setValidUntil(java.time.OffsetDateTime.now().plusHours(1));
        return token;
    }

    private static String messages(Throwable error) {
        assertNotNull(error, "Expected fail-closed startup");
        var result = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) { result.append(current.getMessage()).append('\n'); }
        return result.toString();
    }
}
