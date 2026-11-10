package customer.cap_java.ksef.inbound;

import java.security.MessageDigest;
import java.util.Objects;
import java.util.function.Supplier;

import customer.cap_java.ksef.KsefDocumentStore;
import customer.cap_java.ksef.fa3.Fa3SchemaValidator;
import customer.cap_java.ksef.inbound.fa3.Fa3InboundInvoice;
import customer.cap_java.ksef.inbound.fa3.Fa3ParseException;
import customer.cap_java.ksef.inbound.fa3.SecureFa3InvoiceParser;
import customer.cap_java.ksef.inbound.persistence.CqnInboundDocumentRepository;
import customer.cap_java.ksef.inbound.sync.InboundInvoiceSink;
import customer.cap_java.ksef.inbound.sync.SyncScope;

/** Idempotentny pipeline INBOUND; obiekt per przebieg, bez współdzielonego JWT w singletonie. */
public final class PersistentInboundInvoiceSink implements InboundInvoiceSink {
    private final SyncScope scope;
    private final Supplier<InboundAccessToken> token;
    private final InvoiceRetrievalService retrieval;
    private final CqnInboundDocumentRepository documents;
    private final KsefDocumentStore store;
    private final SecureFa3InvoiceParser parser;
    private final Fa3SchemaValidator schema;

    public PersistentInboundInvoiceSink(SyncScope scope, InboundAccessToken token, InvoiceRetrievalService retrieval,
            CqnInboundDocumentRepository documents, KsefDocumentStore store, SecureFa3InvoiceParser parser,
            Fa3SchemaValidator schema) {
        this(scope, () -> token, retrieval, documents, store, parser, schema);
    }

    public PersistentInboundInvoiceSink(SyncScope scope, Supplier<InboundAccessToken> token, InvoiceRetrievalService retrieval,
            CqnInboundDocumentRepository documents, KsefDocumentStore store, SecureFa3InvoiceParser parser,
            Fa3SchemaValidator schema) {
        this.scope = scope;
        this.token = token;
        this.retrieval = retrieval;
        this.documents = documents;
        this.store = store;
        this.parser = parser;
        this.schema = schema;
    }

    /** Checkpoint jest możliwy dopiero po powrocie ze wszystkich accept; awaria pozostawia okno do retry. */
    @Override public void accept(KsefInvoiceMetadata metadata) {
        if (com.sap.cds.services.changeset.ChangeSetContext.isActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("KSEF_INBOUND_BUSINESS_TRANSACTION_FORBIDDEN");
        }
        validateScope(metadata);
        var row = documents.prepare(metadata);
        if (CqnInboundDocumentRepository.isStored(row)) {
            documents.grantStored(metadata);
            return;
        }
        var context = new KsefTaxpayerContext(scope.environment(), scope.contextNip());
        RetrievedInvoice invoice;
        try {
            invoice = retrieval.downloadVerified(context, token.get(), metadata);
        } catch (InboundFailure error) {
            documents.processingFailed(metadata, error.category() == InboundFailure.Category.INTEGRITY_MISMATCH);
            throw error;
        }
        byte[] xml = invoice.content();
        Fa3InboundInvoice parsed = null;
        String parseStatus = "PARSED";
        String code = null;
        try {
            parsed = parser.parse(xml);
            if (!metadata.formCode().isFa3()) {
                parseStatus = "UNSUPPORTED_SCHEMA";
                parsed = null;
            } else if (!schema.validate(xml).isEmpty()) {
                parseStatus = "INVALID";
                code = "XSD_INVALID";
                parsed = null;
            } else {
                retrieval.crossCheck(metadata, parsed);
            }
        } catch (Fa3ParseException error) {
            parseStatus = switch (error.code()) {
                case UNSUPPORTED_SCHEMA, UNSUPPORTED_DOCUMENT -> "UNSUPPORTED_SCHEMA";
                default -> "INVALID";
            };
            code = error.code().name();
        } catch (InboundFailure error) {
            documents.processingFailed(metadata, true);
            throw error;
        }
        String reference;
        try {
            // Odczyt po zapisie wykrywa błędny provider; potwierdzenie trwałości jest jego kontraktem.
            reference = store.put(xml);
            if (reference == null || reference.isBlank() || reference.length() > 500
                    || !MessageDigest.isEqual(xml, store.get(reference))) {
                throw new IllegalStateException("KSEF_INBOUND_STORE_VERIFICATION_FAILED");
            }
        } catch (RuntimeException error) {
            documents.storageFailed(metadata);
            throw new IllegalStateException("KSEF_INBOUND_DOCUMENT_STORE_FAILED");
        }
        // Awaria DB po put pozostawia bezpieczny orphan. Retry użyje tej samej treści/referencji.
        documents.complete(metadata, reference, invoice.size(), parseStatus, code,
                parsed == null ? null : parsed.grossAmount(), parsed == null ? null : parsed.seller().name());
        if ("INVALID".equals(parseStatus)) {
            throw new IllegalStateException("KSEF_INBOUND_DOCUMENT_QUARANTINED");
        }
    }

    /** Metadane z obcego scope nigdy nie uzyskują grantu ani dostępu do store. */
    private void validateScope(KsefInvoiceMetadata metadata) {
        Objects.requireNonNull(metadata);
        if (metadata.environment() != scope.environment() || !scope.contextNip().equals(metadata.contextNip())
                || metadata.role() != scope.role()
                || scope.role() == KsefSubjectRole.SELLER && !scope.contextNip().equals(metadata.sellerNip())
                || scope.role() == KsefSubjectRole.BUYER && "Nip".equals(metadata.buyerIdentifierType())
                        && !scope.contextNip().equals(metadata.buyerIdentifier())) {
            throw InboundFailure.of(InboundFailure.Category.SCOPE_MISMATCH, "SINK_CONTEXT");
        }
    }
}
