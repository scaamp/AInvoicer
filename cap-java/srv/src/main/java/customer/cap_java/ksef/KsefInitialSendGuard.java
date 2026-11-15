package customer.cap_java.ksef;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.sap.cds.ql.Select;
import com.sap.cds.services.persistence.PersistenceService;

/** E2E-01B: blokada pierwszego efektu. Brak zaakceptowanej polityki/approval = NO-GO, bez flagi obejścia. */
@Component
public class KsefInitialSendGuard {
    private final PersistenceService db;

    public KsefInitialSendGuard(PersistenceService db) { this.db = db; }

    /** Wywoływane w krótkim ChangeSet po claim, przed auth/open/send; odmowa zachowuje rezerwację i bajty. */
    public void requireApproved(UUID id, Map<String, Object> submission, byte[] xml, String configuredNip) {
        var op = db.run(Select.from(KsefSubmissionWorker.OPERATIONS).byId(id.toString())).first()
                .orElseThrow(() -> KsefFailure.invalid("KSEF_SEND_BINDING_INVALID"));
        var snapshot = db.run(Select.from(KsefSubmissionWorker.SNAPSHOTS).byId(submission.get("snapshotId"))).first()
                .orElseThrow(() -> KsefFailure.invalid("KSEF_SEND_BINDING_INVALID"));
        boolean valid = "QUEUED".equals(op.get("status")) && "SEND_INVOICE".equals(op.get("operationType"))
                && "TEST".equals(op.get("environment")) && "TEST".equals(snapshot.get("environment"))
                && "OUTBOUND".equals(op.get("direction")) && "OUTBOUND".equals(snapshot.get("direction"))
                && "SAP_RAP".equals(snapshot.get("source")) && "FA(3)".equals(snapshot.get("schemaVersion"))
                && configuredNip.equals(op.get("taxpayerNip")) && configuredNip.equals(snapshot.get("taxpayerNip"))
                && configuredNip.equals(submission.get("taxpayerNip"))
                && Objects.equals(op.get("snapshot_ID"), snapshot.get("ID"))
                && Objects.equals(op.get("sapDocumentRef"), snapshot.get("invoiceUUID"))
                && op.get("companyCode") != null && Objects.equals(op.get("companyCode"), snapshot.get("companyCode"))
                && Objects.equals(submission.get("xmlSha256"), snapshot.get("xmlSha256"))
                && customer.cap_java.sap.KsefInvoiceSnapshotRegistry.sha256Hex(xml).equals(snapshot.get("xmlSha256"))
                && snapshot.get("xmlByteLength") instanceof Number size && size.longValue() == xml.length
                && submission.get("sessionReference") == null && submission.get("invoiceReference") == null
                && !Boolean.TRUE.equals(submission.get("closeAttempted"));
        if (!valid) { throw KsefFailure.invalid("KSEF_SEND_BINDING_INVALID"); }
        try {
            java.time.Instant.parse(String.valueOf(snapshot.get("sapHeaderVersion")));
            if (!String.valueOf(snapshot.get("itemsFingerprint")).matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) { throw KsefFailure.invalid("KSEF_SEND_VERSION_INVALID"); }
        var reservation = db.run(Select.from(KsefSendRequestHandler.RESERVATIONS).where(r -> r.get("invoiceUUID")
                .eq(snapshot.get("invoiceUUID")).and(r.get("environment").eq("TEST")))).first();
        if (reservation.isEmpty() || !id.toString().equals(reservation.get().get("operationId"))
                || !configuredNip.equals(reservation.get().get("taxpayerNip"))
                || !Objects.equals(op.get("companyCode"), reservation.get().get("companyCode"))
                || !Objects.equals(op.get("idempotencyKey"), "SEND:" + snapshot.get("invoiceUUID")
                        + ":TEST:" + reservation.get().get("attempt"))) {
            throw KsefFailure.invalid("KSEF_SEND_RESERVATION_INVALID");
        }
        if (!db.run(Select.from(KsefSubmissionWorker.OPERATIONS).columns("ID").where(o -> o.get("sapDocumentRef")
                .eq(snapshot.get("invoiceUUID")).and(o.get("environment").eq("TEST"))
                .and(o.get("operationType").eq("SEND_INVOICE")).and(o.get("ID").ne(id.toString()))
                .and(o.get("status").ne("REJECTED"))).limit(1)).list().isEmpty()) {
            throw KsefFailure.invalid("KSEF_SEND_COMPETING_OPERATION");
        }
        // Żaden obecny zapis nie dowodzi zgody na dokładną wersję. PROPOSED ADR nie stanowi zgody.
        throw KsefFailure.invalid("KSEF_SNAPSHOT_POLICY_NOT_APPROVED");
    }

}
