package customer.cap_java.ksef.inbound.persistence;

import static customer.cap_java.ksef.inbound.persistence.InboundPersistence.*;

import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ServiceException;

import customer.cap_java.ksef.inbound.KsefInvoiceMetadata;
import customer.cap_java.ksef.inbound.sync.InvoiceDeduplicationPolicy;
import customer.cap_java.ksef.inbound.sync.InvoiceDeduplicationPolicy.*;

/** Staging CAP: rezerwacja przed storage, finalizacja z grantem i append-only historia konfliktów. */
public final class CqnInboundDocumentRepository {
    private final InboundPersistence persistence;
    private final InvoiceDeduplicationPolicy policy = new InvoiceDeduplicationPolicy();
    private final Clock clock;

    public CqnInboundDocumentRepository(InboundPersistence persistence, Clock clock) {
        this.persistence = persistence;
        this.clock = clock;
    }

    /** Wyścig pierwszego INSERT jest rozstrzygany ponownym odczytem w nowej transakcji po rollback. */
    public Map<String, Object> prepare(KsefInvoiceMetadata metadata) {
        Map<String, Object> row = insertRace(() -> persistence.tx(() -> {
            String id = documentId(metadata);
            var found = persistence.db.run(Select.from(DOCUMENTS).byId(id).lock()).first();
            if (found.isEmpty()) {
                var created = metadataRow(metadata);
                persistence.db.run(Insert.into(DOCUMENTS).entry(created));
                return created;
            }
            var existing = found.get();
            String reason = conflictReason(existing, metadata);
            if (reason != null) {
                recordConflict(existing, metadata, reason);
                return null;
            }
            if ("CONFLICT".equals(existing.get("integrityStatus"))) { return null; }
            return new HashMap<>(existing);
        }));
        if (row == null) { throw new IllegalStateException("KSEF_INBOUND_INTEGRITY_CONFLICT"); }
        return row;
    }

    /** Już utrwalony dokument: nowy grant zapisujemy dopiero po kontroli zgodności tożsamości/hash. */
    public void grantStored(KsefInvoiceMetadata metadata) {
        boolean success = persistence.tx(() -> {
            var row = persistence.db.run(Select.from(DOCUMENTS).byId(documentId(metadata)).lock()).single();
            String reason = conflictReason(row, metadata);
            if (reason != null) { recordConflict(row, metadata, reason); return false; }
            if (!isStored(row)) { return false; }
            addGrant(metadata);
            return true;
        });
        if (!success) { throw new IllegalStateException("KSEF_INBOUND_INTEGRITY_CONFLICT"); }
    }

    /** Atomowo finalizuje staging i grant. Oryginał i fingerprint pozostają niezmienne przy konflikcie. */
    public void complete(KsefInvoiceMetadata metadata, String reference, long size, String parseStatus,
            String code, java.math.BigDecimal grossAmount, String sellerName) {
        boolean success = persistence.tx(() -> {
            var row = persistence.db.run(Select.from(DOCUMENTS).byId(documentId(metadata)).lock()).single();
            String reason = conflictReason(row, metadata);
            if (reason == null && row.get("sizeBytes") != null && ((Number) row.get("sizeBytes")).longValue() != size) {
                reason = "CONTENT_HASH_MISMATCH";
            }
            if (reason != null) { recordConflict(row, metadata, reason); return false; }
            if ("CONFLICT".equals(row.get("integrityStatus"))) { return false; }
            if (!"STORED".equals(row.get("ingestionStatus"))) {
                var values = new HashMap<String, Object>();
                values.put("xmlStorageRef", reference);
                values.put("sizeBytes", size);
                values.put("parseStatus", parseStatus);
                values.put("integrityStatus", "VERIFIED");
                values.put("ingestionStatus", "INVALID".equals(parseStatus) ? "QUARANTINED" : "STORED");
                values.put("lastCode", code);
                values.put("grossAmount", grossAmount);
                if (sellerName != null) { values.put("sellerName", sellerName); }
                persistence.db.run(Update.entity(DOCUMENTS).byId(documentId(metadata)).data(values));
            }
            addGrant(metadata);
            return true;
        });
        if (!success) { throw new IllegalStateException("KSEF_INBOUND_INTEGRITY_CONFLICT"); }
    }

    /** Kod bez szczegółów providera; awaria store nie może cofnąć sukcesu drugiego workera. */
    public void storageFailed(KsefInvoiceMetadata metadata) {
        persistence.tx(() -> persistence.db.run(Update.entity(DOCUMENTS)
                .data(Map.of("ingestionStatus", "STORAGE_FAILED", "lastCode", "DOCUMENT_STORE_FAILED"))
                .where(d -> d.get("ID").eq(documentId(metadata)).and(d.get("xmlStorageRef").isNull())
                        .and(d.get("integrityStatus").ne("CONFLICT")))));
    }

    /** Trwały ślad błędu odczytu/integralności, bez nadpisania sukcesu lub konfliktu innego workera. */
    public void processingFailed(KsefInvoiceMetadata metadata, boolean integrityFailure) {
        var values = new HashMap<String, Object>();
        values.put("lastCode", integrityFailure ? "INTEGRITY_MISMATCH" : "DOWNLOAD_FAILED");
        if (integrityFailure) { values.put("integrityStatus", "FAILED"); }
        persistence.tx(() -> persistence.db.run(Update.entity(DOCUMENTS).data(values)
                .where(d -> d.get("ID").eq(documentId(metadata)).and(d.get("xmlStorageRef").isNull())
                        .and(d.get("integrityStatus").ne("CONFLICT")))));
    }

    public static boolean isStored(Map<String, Object> row) {
        return "STORED".equals(row.get("ingestionStatus")) && "VERIFIED".equals(row.get("integrityStatus"))
                && row.get("xmlStorageRef") != null
                && Set.of("PARSED", "UNSUPPORTED_SCHEMA").contains(row.get("parseStatus"));
    }

    public static String documentId(KsefInvoiceMetadata metadata) {
        return id(metadata.environment().name() + ":" + metadata.ksefNumber());
    }

    private String conflictReason(Map<String, Object> row, KsefInvoiceMetadata metadata) {
        if (!row.get("sha256").equals(hash(metadata))) { return "CONTENT_HASH_MISMATCH"; }
        var identity = new DocumentIdentity(metadata.environment(), metadata.ksefNumber());
        var grant = new AccessGrant(identity, metadata.contextNip(), metadata.role());
        var known = new KnownDocument(identity, (String) row.get("invoiceNumber"), (String) row.get("sellerNip"),
                (String) row.get("buyerIdentifierValue"), null, Set.of());
        var observation = new Observation(grant, metadata.invoiceNumber(), metadata.sellerNip(), metadata.buyerIdentifier(), null);
        var decision = policy.decide(known, observation);
        if (decision.outcome() == Outcome.INTEGRITY_CONFLICT) { return decision.conflictReason(); }
        if (!Objects.equals(row.get("buyerIdentifierType"), metadata.buyerIdentifierType())
                || !Objects.equals(row.get("buyerIdentifierValue"), metadata.buyerIdentifier())
                || !Objects.equals(row.get("currency"), metadata.currency())
                || !Objects.equals(row.get("formSystemCode"), metadata.formCode().systemCode())
                || !Objects.equals(row.get("formSchemaVersion"), metadata.formCode().schemaVersion())
                || !Objects.equals(row.get("formValue"), metadata.formCode().value())
                || !Objects.equals(row.get("invoiceType"), metadata.invoiceType())
                || !Objects.equals(String.valueOf(row.get("issueDate")), String.valueOf(metadata.issueDate()))) {
            return "METADATA_MISMATCH";
        }
        return null;
    }

    private void recordConflict(Map<String, Object> row, KsefInvoiceMetadata metadata, String reason) {
        persistence.db.run(Insert.into(CONFLICTS).entry(Map.of("ID", UUID.randomUUID().toString(),
                "documentId", row.get("ID"), "contextNip", metadata.contextNip(), "subjectRole", metadata.role().name(),
                "occurredAt", clock.instant(), "expectedHash", row.get("sha256"), "observedHash", hash(metadata), "reason", reason)));
        persistence.db.run(Update.entity(DOCUMENTS).byId(row.get("ID"))
                .data(Map.of("integrityStatus", "CONFLICT", "lastCode", reason)));
    }

    private void addGrant(KsefInvoiceMetadata metadata) {
        // Blokada dokumentu serializuje dodawanie grantów także z różnych SyncScope.
        String grantId = id(documentId(metadata) + ":" + metadata.contextNip() + ":" + metadata.role());
        if (persistence.db.run(Select.from(GRANTS).byId(grantId)).first().isEmpty()) {
            persistence.db.run(Insert.into(GRANTS).entry(Map.of("ID", grantId, "documentId", documentId(metadata),
                    "contextNip", metadata.contextNip(), "subjectRole", metadata.role().name(), "firstSeenAt", clock.instant())));
        }
    }

    private Map<String, Object> metadataRow(KsefInvoiceMetadata metadata) {
        var row = new HashMap<String, Object>();
        row.put("ID", documentId(metadata));
        row.put("environment", metadata.environment().name());
        row.put("ksefNumber", metadata.ksefNumber());
        row.put("invoiceNumber", metadata.invoiceNumber());
        row.put("issueDate", metadata.issueDate());
        row.put("acquisitionDate", metadata.acquisitionDate() == null ? null : metadata.acquisitionDate().toInstant());
        row.put("permanentStorageDate", metadata.permanentStorageDate().toInstant());
        row.put("sellerNip", metadata.sellerNip());
        row.put("sellerName", metadata.sellerName());
        row.put("buyerIdentifierType", metadata.buyerIdentifierType());
        row.put("buyerIdentifierValue", metadata.buyerIdentifier());
        row.put("currency", metadata.currency());
        row.put("formSystemCode", metadata.formCode().systemCode());
        row.put("formSchemaVersion", metadata.formCode().schemaVersion());
        row.put("formValue", metadata.formCode().value());
        row.put("invoiceType", metadata.invoiceType());
        row.put("sha256", hash(metadata));
        row.put("ingestionStatus", "PENDING");
        row.put("parseStatus", "NOT_PARSED");
        row.put("integrityStatus", "PENDING");
        row.put("sapPostingStatus", "NOT_POSTED");
        return row;
    }

    private static String hash(KsefInvoiceMetadata metadata) {
        byte[] bytes = Base64.getDecoder().decode(metadata.invoiceHashBase64());
        if (bytes.length != 32) { throw new IllegalArgumentException("KSEF_INBOUND_HASH_INVALID"); }
        return HexFormat.of().formatHex(bytes);
    }

    private static <T> T insertRace(Supplier<T> action) {
        try { return action.get(); }
        catch (ServiceException error) {
            if (!uniqueViolation(error)) { throw error; }
            return action.get();
        }
    }
}
