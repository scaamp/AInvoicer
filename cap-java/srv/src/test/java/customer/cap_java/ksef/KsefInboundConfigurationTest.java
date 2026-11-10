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
        return new ApplicationContextRunner().withUserConfiguration(KsefInboundConfiguration.class)
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

    @Test void localOptInRequiresConfiguredStorage() {
        context(new LocalTestDocumentStore("")).withPropertyValues("ainv.ksef.inbound.enabled=true")
                .run(app -> assertTrue(messages(app.getStartupFailure()).contains("KSEF_INBOUND_DOCUMENT_STORE_REQUIRED")));
    }

    @Test void localWiringDoesNotStartSynchronizationOrAuthenticate() {
        var sdk = mock(KSeFClient.class);
        var auth = mock(KsefAuthentication.class);
        context(new LocalTestDocumentStore(directory.toString())).withBean(KSeFClient.class, () -> sdk)
                .withBean(KsefAuthentication.class, () -> auth).withBean(Clock.class, Clock::systemUTC)
                .withBean(PersistenceService.class, () -> mock(PersistenceService.class))
                .withBean(CdsRuntime.class, () -> mock(CdsRuntime.class))
                .withPropertyValues("ainv.ksef.inbound.enabled=true", "ainv.ksef.nip=1111111111",
                        "ainv.ksef.inbound.initial-from=2026-11-01T00:00:00Z", "ainv.ksef.local-store=" + directory)
                .run(app -> {
                    assertNull(app.getStartupFailure(), () -> messages(app.getStartupFailure()));
                    assertNotNull(app.getBean(InboundSynchronization.class));
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
