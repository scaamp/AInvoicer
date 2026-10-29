package customer.cap_java.sap;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.sap.cds.ql.Insert;
import com.sap.cds.services.persistence.PersistenceService;

/** Wspólne dane testowe: potwierdzona operacja OUTBOUND z niezmienną migawką dokumentu (bez KSeF). */
final class WritebackFixtures {
    static final String OPS = "customer.cap_java.KsefOperations";
    static final Instant VERSION = Instant.parse("2026-10-28T10:11:12.1234567Z");
    static final String XML_HASH = "a".repeat(64);

    private WritebackFixtures() { }

    /** Wstawia migawkę i potwierdzoną operację; wymaga aktywnej transakcji. */
    static UUID seed(PersistenceService db, UUID id, UUID invoice) {
        return seed(db, id, invoice, "1234567802-TEST-" + id.toString().substring(0, 8), XML_HASH, XML_HASH);
    }

    static UUID seed(PersistenceService db, UUID id, UUID invoice, String ksefNumber, String snapshotHash,
            String confirmedHash) {
        UUID snapshot = UUID.randomUUID();
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("ID", snapshot.toString());
        snap.put("invoiceUUID", invoice.toString());
        snap.put("taxpayerNip", "1234567802");
        snap.put("direction", "OUTBOUND");
        snap.put("environment", "TEST");
        snap.put("companyCode", "TST1");
        snap.put("schemaVersion", "FA(3)");
        snap.put("sapHeaderVersion", VERSION.toString());
        snap.put("itemsFingerprint", "b".repeat(64));
        snap.put("xmlSha256", snapshotHash);
        snap.put("xmlByteLength", 10);
        db.run(Insert.into(SapKsefResultWriteback.SNAPSHOTS).entry(snap));
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("ID", id.toString());
        op.put("sapDocumentRef", invoice.toString());
        op.put("taxpayerNip", "1234567802");
        op.put("direction", "OUTBOUND");
        op.put("operationType", "SEND_INVOICE");
        op.put("status", "ACCEPTED");
        op.put("ksefNumber", ksefNumber);
        op.put("idempotencyKey", id.toString());
        op.put("snapshot_ID", snapshot.toString());
        op.put("confirmedXmlSha256", confirmedHash);
        db.run(Insert.into(OPS).entry(op));
        return snapshot;
    }

    /** Zegar sterowany przez test; pozwala przekroczyć dzierżawę i Retry-After bez czekania. */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        void advance(Duration duration) {
            now.updateAndGet(value -> value.plus(duration));
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
