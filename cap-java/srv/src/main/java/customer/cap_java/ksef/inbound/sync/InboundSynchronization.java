package customer.cap_java.ksef.inbound.sync;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import customer.cap_java.ksef.KsefDocumentStore;
import customer.cap_java.ksef.KsefFailure;
import customer.cap_java.ksef.KsefInboundAccess;
import customer.cap_java.ksef.fa3.Fa3SchemaValidator;
import customer.cap_java.ksef.inbound.InboundFailure;
import customer.cap_java.ksef.inbound.InvoiceRetrievalService;
import customer.cap_java.ksef.inbound.KsefTaxpayerContext;
import customer.cap_java.ksef.inbound.PersistentInboundInvoiceSink;
import customer.cap_java.ksef.inbound.fa3.SecureFa3InvoiceParser;
import customer.cap_java.ksef.inbound.persistence.CqnInboundDocumentRepository;
import customer.cap_java.ksef.inbound.persistence.CqnInboundSyncLeaseRepository;
import customer.cap_java.ksef.inbound.persistence.CqnSyncCheckpointRepository;

/**
 * Jeden jawny przebieg dla zaufanego zakresu, z lease i recovery. Bez @Scheduled/ApplicationReady:
 * globalny budżet endpointów per kontekst/IP nie jest jeszcze wdrożony, automatyzacja pozostaje OFF.
 */
public final class InboundSynchronization {
    private final SyncScope scope;
    private final OffsetDateTime initialFrom;
    private final Clock clock;
    private final KsefInboundAccess access;
    private final InvoiceRetrievalService retrieval;
    private final CqnInboundDocumentRepository documents;
    private final CqnSyncCheckpointRepository checkpoints;
    private final CqnInboundSyncLeaseRepository leases;
    private final KsefDocumentStore store;
    private final Fa3SchemaValidator schema;

    public InboundSynchronization(SyncScope scope, OffsetDateTime initialFrom, Clock clock, KsefInboundAccess access,
            InvoiceRetrievalService retrieval, CqnInboundDocumentRepository documents,
            CqnSyncCheckpointRepository checkpoints, CqnInboundSyncLeaseRepository leases,
            KsefDocumentStore store, Fa3SchemaValidator schema) {
        this.scope = scope;
        this.initialFrom = initialFrom;
        this.clock = clock;
        this.access = access;
        this.retrieval = retrieval;
        this.documents = documents;
        this.checkpoints = checkpoints;
        this.leases = leases;
        this.store = store;
        this.schema = schema;
    }

    /** Brak lease/due = brak auth i HTTP. Przerwane okno rusza ponownie od ostatniego zatwierdzonego HWM. */
    public Optional<InvoiceSyncRunner.Result> runOnce() {
        if (com.sap.cds.services.changeset.ChangeSetContext.isActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("KSEF_INBOUND_BUSINESS_TRANSACTION_FORBIDDEN");
        }
        var claim = leases.claim(scope, Duration.ofMinutes(10));
        if (claim.isEmpty()) { return Optional.empty(); }
        try {
            var session = access.open();
            var context = new KsefTaxpayerContext(scope.environment(), scope.contextNip());
            var runner = new InvoiceSyncRunner(retrieval, InvoiceSyncWindowPlanner.defaults(clock),
                    leases.fenced(checkpoints, claim.get()), clock);
            var sink = new PersistentInboundInvoiceSink(scope, session::token, retrieval, documents, store,
                    new SecureFa3InvoiceParser(), schema);
            leases.requireOwned(claim.get());
            var result = runner.runOnce(context, scope.role(), session.token(), initialFrom, metadata -> {
                leases.requireOwned(claim.get());
                sink.accept(metadata);
            });
            leases.finish(claim.get(), later(null), false);
            return Optional.of(result);
        } catch (InboundFailure error) {
            leases.finish(claim.get(), later(error.retryNotBefore()), !error.transientRead());
            throw error;
        } catch (KsefFailure error) {
            // Nieznany wynik redeem/auth nie uruchamia kolejnego auth automatycznie.
            leases.finish(claim.get(), later(error.retryNotBefore()), error.ambiguous() || !error.retryable());
            throw error;
        } catch (RuntimeException error) {
            // Storage/DB można ponowić; konflikt/quarantine wymaga decyzji operatora w kolejnym etapie.
            boolean blocked = "KSEF_INBOUND_INTEGRITY_CONFLICT".equals(error.getMessage())
                    || "KSEF_INBOUND_DOCUMENT_QUARANTINED".equals(error.getMessage());
            leases.finish(claim.get(), later(null), blocked);
            throw error;
        }
    }

    private Instant later(Instant retryAfter) {
        Instant minimum = clock.instant().plus(CqnInboundSyncLeaseRepository.MIN_INTERVAL)
                .plusMillis(ThreadLocalRandom.current().nextLong(1000));
        return retryAfter != null && retryAfter.isAfter(minimum) ? retryAfter : minimum;
    }
}
