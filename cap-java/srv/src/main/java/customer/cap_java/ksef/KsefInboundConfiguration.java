package customer.cap_java.ksef;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;

import org.springframework.beans.factory.ObjectProvider;
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
import customer.cap_java.ksef.storage.InboundDocumentStorage;
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient;

/** Opcjonalny wiring synchronizacji INBOUND. Odczyt stagingu jest rejestrowany osobno, bez HTTP do KSeF. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ainv.ksef.inbound.enabled", havingValue = "true")
@Import(KsefInboundReadConfiguration.class)
public class KsefInboundConfiguration {
    private final KsefTaxpayerContext context;
    private final OffsetDateTime initialFrom;
    private final KsefDocumentStore store;

    /**
     * KSEF-IN-05: jeśli zarejestrowano jawny magazyn INBOUND ({@link InboundDocumentStorage}, domyślnie OFF),
     * ma on pierwszeństwo przed wspólnym {@code KsefDocumentStore}. OUTBOUND nadal dostaje ten sam bean co dotąd.
     * Guard chmury pozostaje bezwarunkowy: trwały store nie aktywuje INBOUND na CF.
     */
    public KsefInboundConfiguration(Environment environment, KsefDocumentStore sharedStore,
            ObjectProvider<InboundDocumentStorage> inboundStorage,
            @Value("${ainv.ksef.nip:}") String nip,
            @Value("${ainv.ksef.inbound.environment:TEST}") String ksefEnvironment,
            @Value("${ainv.ksef.inbound.initial-from:}") String initialFrom,
            @Value("${ainv.ksef.local-store:}") String localStore) {
        InboundDocumentStorage dedicated = inboundStorage.getIfAvailable();
        KsefDocumentStore store = dedicated != null ? dedicated.store() : sharedStore;
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
        this.store = store;
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
            CqnSyncCheckpointRepository checkpoints, CqnInboundSyncLeaseRepository leases) {
        return new InboundSynchronization(SyncScope.of(context, KsefSubjectRole.BUYER), initialFrom, clock,
                access, retrieval, documents, checkpoints, leases, store, new Fa3SchemaValidator());
    }

    /**
     * KSEF-IN-05 (B3): wewnętrzny import jednej faktury po numerze KSeF. Domyślnie OFF; bez akcji OData i schedulera.
     * Tworzony tylko w tej konfiguracji, więc dziedziczy guard chmury i kontekst TEST/NIP z backendu.
     */
    @Bean
    @ConditionalOnProperty(name = "ainv.ksef.inbound.manual-import.enabled", havingValue = "true")
    InboundManualImport inboundManualImport(Clock clock, CqnInboundDocumentRepository documents,
            CqnInboundSyncLeaseRepository leases,
            @Value("${ainv.ksef.inbound.manual-import.max-http-requests:9}") int maxHttpRequests,
            @Value("${ainv.ksef.inbound.manual-import.max-duration:PT90S}") String maxDuration,
            @Value("${ainv.ksef.nip:}") String nip,
            @Value("${ainv.ksef.token:}") String token,
            @Value("${ainv.ksef.test-xades:false}") boolean testXades) throws java.security.NoSuchAlgorithmException {
        // BETA-02: limity walidowane przy starcie (1..9 żądań, 1 s..5 min) — błędna konfiguracja = brak beana.
        var limits = new BudgetedKsefTransport.Limits(maxHttpRequests, java.time.Duration.parse(maxDuration),
                java.time.Duration.ofSeconds(5));
        return new InboundManualImport(SyncScope.of(context, KsefSubjectRole.BUYER), clock,
                budgetedClients(KsefSdkConfiguration.TEST_ORIGIN, javax.net.ssl.SSLContext.getDefault(), limits, clock,
                        context, nip, token, testXades),
                documents, leases, store, new Fa3SchemaValidator());
    }

    /**
     * BETA-02: per przebieg nowy transport z twardym budżetem, nowy SDK i nowy auth na tym transporcie.
     * Certyfikaty MF (TOKEN), challenge, auth, polling, redeem, refresh, metadata i GET XML zużywają ten sam budżet;
     * wspólny bean {@code KSeFClient} (OUTBOUND, synchronizacja) pozostaje nietknięty. XAdES nie tworzy kryptografii.
     */
    static InboundManualImport.RunClients budgetedClients(String origin, javax.net.ssl.SSLContext tls,
            BudgetedKsefTransport.Limits limits, Clock clock, KsefTaxpayerContext context,
            String nip, String token, boolean testXades) {
        return fence -> {
            var transport = new BudgetedKsefTransport(origin, limits, clock, fence, tls);
            KSeFClient sdk = KsefSdkConfiguration.createClient(origin, transport);
            // Konstruktor kryptografii SDK pobiera certyfikaty MF — tylko dla TOKEN i już w ramach budżetu/lease.
            var crypto = token == null || token.isBlank() ? null
                    : new pl.akmf.ksef.sdk.api.services.DefaultCryptographyService(sdk);
            var authentication = new KsefAuthentication(sdk, crypto, clock, nip, token, testXades);
            return new InboundManualImport.Clients(new KsefInboundAccess(authentication, context),
                    new KsefInboundClient(sdk, context.environment(), KsefInboundClient.DEFAULT_MAX_INVOICE_BYTES, clock));
        };
    }
}
