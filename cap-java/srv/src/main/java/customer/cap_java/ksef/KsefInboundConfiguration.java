package customer.cap_java.ksef;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import customer.cap_java.ksef.fa3.Fa3SchemaValidator;
import customer.cap_java.ksef.inbound.*;
import customer.cap_java.ksef.inbound.persistence.*;
import customer.cap_java.ksef.inbound.sync.*;
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient;

/** Opcjonalny wiring synchronizacji INBOUND. Odczyt stagingu jest rejestrowany osobno, bez HTTP do KSeF. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ainv.ksef.inbound.enabled", havingValue = "true")
@Import(KsefInboundReadConfiguration.class)
public class KsefInboundConfiguration {
    private final KsefTaxpayerContext context;
    private final OffsetDateTime initialFrom;

    public KsefInboundConfiguration(Environment environment, KsefDocumentStore store,
            @Value("${ainv.ksef.nip:}") String nip,
            @Value("${ainv.ksef.inbound.environment:TEST}") String ksefEnvironment,
            @Value("${ainv.ksef.inbound.initial-from:}") String initialFrom,
            @Value("${ainv.ksef.local-store:}") String localStore) {
        boolean cloud = environment.containsProperty("VCAP_APPLICATION")
                || Arrays.stream(environment.getActiveProfiles()).anyMatch(p ->
                        java.util.Set.of("cf", "cloud", "prod", "production").contains(p));
        if (cloud) {
            if (!(store instanceof DurableInboundDocumentStore)) {
                throw new IllegalStateException("KSEF_INBOUND_DURABLE_STORE_REQUIRED");
            }
            // Brak globalnego budżetu API per kontekst/IP: nawet trwały store nie aktywuje CF.
            throw new IllegalStateException("KSEF_INBOUND_CLOUD_ACTIVATION_NOT_READY");
        }
        if (!(store instanceof DurableInboundDocumentStore) && (!(store instanceof LocalTestDocumentStore) || localStore.isBlank())) {
            throw new IllegalStateException("KSEF_INBOUND_DOCUMENT_STORE_REQUIRED");
        }
        this.context = new KsefTaxpayerContext(KsefEnvironment.valueOf(ksefEnvironment), nip);
        if (context.environment() != KsefEnvironment.TEST) {
            throw new IllegalStateException("KSEF_INBOUND_ENVIRONMENT_UNSUPPORTED");
        }
        if (initialFrom.isBlank()) { throw new IllegalStateException("KSEF_INBOUND_INITIAL_FROM_REQUIRED"); }
        this.initialFrom = OffsetDateTime.parse(initialFrom);
    }

    @Bean CqnSyncCheckpointRepository inboundCheckpoints(InboundPersistence persistence) {
        return new CqnSyncCheckpointRepository(persistence);
    }
    @Bean CqnInboundDocumentRepository inboundDocuments(InboundPersistence persistence, Clock clock) {
        return new CqnInboundDocumentRepository(persistence, clock);
    }
    @Bean CqnInboundSyncLeaseRepository inboundLeases(InboundPersistence persistence, Clock clock) {
        return new CqnInboundSyncLeaseRepository(persistence, clock);
    }
    @Bean KsefInboundAccess inboundAccess(KsefAuthentication authentication) {
        return new KsefInboundAccess(authentication, context);
    }
    @Bean InvoiceRetrievalService inboundRetrieval(KSeFClient sdk, Clock clock) {
        return new InvoiceRetrievalService(new KsefInboundClient(sdk, context.environment(),
                KsefInboundClient.DEFAULT_MAX_INVOICE_BYTES, clock), ReadRetryPolicy.defaults(clock));
    }
    @Bean InboundSynchronization inboundSynchronization(Clock clock, KsefInboundAccess access,
            InvoiceRetrievalService retrieval, CqnInboundDocumentRepository documents,
            CqnSyncCheckpointRepository checkpoints, CqnInboundSyncLeaseRepository leases, KsefDocumentStore store) {
        return new InboundSynchronization(SyncScope.of(context, KsefSubjectRole.BUYER), initialFrom, clock,
                access, retrieval, documents, checkpoints, leases, store, new Fa3SchemaValidator());
    }
}
