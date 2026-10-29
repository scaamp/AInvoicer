package customer.cap_java.ksef;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.outbox.OutboxMessage;
import com.sap.cds.services.outbox.OutboxMessageEventContext;
import com.sap.cds.services.outbox.OutboxService;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.RequestContext;
import com.sap.cds.services.runtime.CdsRuntime;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;

/**
 * Atomowe zlecenie + natywny persistent outbox. HTTP działa między krótkimi transakcjami.
 * Przed każdym POST utrwalamy etap. Wygasłe przejęcie nie pozwala ponownie wysłać XML.
 * Rekoncyliacja używa wyłącznie zapisanych referencji i SHA-256; brak dowodu oznacza zatrzymanie.
 */
@Component
public class KsefSubmissionWorker implements EventHandler {
    static final String SUBMISSIONS = "customer.cap_java.KsefSubmissions";
    static final String HISTORY = "customer.cap_java.KsefSubmissionHistory";
    static final String OPERATIONS = "customer.cap_java.KsefOperations";
    static final String INVOICES = "customer.cap_java.KsefInvoices";
    static final String SNAPSHOTS = "customer.cap_java.KsefInvoiceSnapshots";
    private static final Set<String> EFFECTS = Set.of("AUTHENTICATING", "OPENING", "OPENED", "SENDING", "CLOSING");
    private static final Set<String> FINAL = Set.of("COMPLETED", "REJECTED", "RECONCILIATION_REQUIRED", "READ_RETRY_EXHAUSTED");
    private final PersistenceService db;
    private final CdsRuntime runtime;
    private final OutboxService outbox;
    private final KsefAuthentication authentication;
    private final KsefOnlineSession session;
    private final KsefDocumentStore documents;
    private final Clock clock;
    private final boolean enabled;
    private final String configuredNip;

    public KsefSubmissionWorker(PersistenceService db, CdsRuntime runtime,
            @Qualifier("KsefSubmissionOutbox") OutboxService outbox, KsefAuthentication authentication,
            KsefOnlineSession session, KsefDocumentStore documents, Clock clock,
            @Value("${ainv.ksef.enabled:false}") boolean enabled,
            @Value("${ainv.ksef.nip:}") String configuredNip) {
        this.db = db;
        this.runtime = runtime;
        this.outbox = outbox;
        this.authentication = authentication;
        this.session = session;
        this.documents = documents;
        this.clock = clock;
        this.enabled = enabled;
        this.configuredNip = configuredNip;
    }

    /**
     * Wewnętrzne API po walidacji FA(3). Wywołujący obejmuje enqueue transakcją CAP.
     * Zlecenie, historia, powiązania i outbox commitują atomowo. Plik bez transakcji może zostać
     * osierocony po rollback; jego hash pozostaje bezpieczny, a żadne HTTP nie zostało wykonane.
     */
    public UUID enqueue(UUID operationId, UUID snapshotId, byte[] xml) {
        requireEnabled();
        var operation = db.run(Select.from(OPERATIONS).byId(operationId.toString()).lock()).single();
        var snapshot = db.run(Select.from(SNAPSHOTS).byId(snapshotId.toString())).single();
        String hash = KsefInvoiceSnapshotRegistry.sha256Hex(xml);
        if (!configuredNip.equals(operation.get("taxpayerNip"))
                || !configuredNip.equals(snapshot.get("taxpayerNip"))
                || !"TEST".equals(operation.get("environment")) || !"TEST".equals(snapshot.get("environment"))
                || !"OUTBOUND".equals(operation.get("direction")) || !"OUTBOUND".equals(snapshot.get("direction"))
                || !"SEND_INVOICE".equals(operation.get("operationType"))
                || !"FA(3)".equals(snapshot.get("schemaVersion"))
                || !String.valueOf(snapshot.get("invoiceUUID")).equalsIgnoreCase(String.valueOf(operation.get("sapDocumentRef")))
                || !hash.equals(snapshot.get("xmlSha256"))
                || ((Number) snapshot.get("xmlByteLength")).intValue() != xml.length) {
            throw KsefFailure.invalid("KSEF_SUBMISSION_SCOPE_INVALID");
        }
        var existing = db.run(Select.from(SUBMISSIONS).byId(operationId.toString())).first();
        if (existing.isPresent()) {
            if (!snapshotId.toString().equals(existing.get().get("snapshotId"))) {
                throw KsefFailure.invalid("KSEF_SUBMISSION_IDEMPOTENCY_CONFLICT");
            }
            return operationId;
        }
        if (!"NEW".equals(operation.get("status"))) { throw KsefFailure.invalid("KSEF_OPERATION_NOT_NEW"); }
        Object invoiceId = operation.get("invoice_ID");
        if (invoiceId != null) {
            var invoice = db.run(Select.from(INVOICES).byId(invoiceId)).single();
            if (!configuredNip.equals(invoice.get("taxpayerNip")) || !"OUTBOUND".equals(invoice.get("direction"))
                    || !operation.get("sapDocumentRef").equals(invoice.get("sapDocumentRef"))) {
                throw KsefFailure.invalid("KSEF_INVOICE_SCOPE_INVALID");
            }
        }
        String reference = documents.put(xml);
        if (invoiceId != null) {
            db.run(Update.entity(INVOICES).byId(invoiceId).data(Map.of("xmlStorageRef", reference)));
        }
        db.run(Insert.into(SUBMISSIONS).entry(Map.of("ID", operationId.toString(), "snapshotId", snapshotId.toString(),
                "taxpayerNip", configuredNip, "state", "PREPARED", "xmlStorageRef", reference, "xmlSha256", hash)));
        db.run(Update.entity(OPERATIONS).byId(operationId.toString()).data(Map.of("snapshot_ID", snapshotId.toString(), "status", "QUEUED")));
        history(operationId, "PREPARED", "QUEUED");
        var message = OutboxMessage.create();
        message.setParams(Map.of("submissionId", operationId.toString()));
        outbox.submit("submitInvoice", message);
        return operationId;
    }

    @On(service = "KsefSubmissionOutbox", event = "submitInvoice")
    public void onSubmit(OutboxMessageEventContext context) {
        if (!Boolean.TRUE.equals(context.getIsInbound())) { return; }
        process(UUID.fromString((String) context.getMessage().getParams().get("submissionId")));
        context.setCompleted();
    }

    /** Po restarcie budzi collector bez zmiany niezmiennego payloadu starych wiadomości. */
    @EventListener(ApplicationReadyEvent.class)
    public void wakeAfterRestart() {
        if (!enabled) { return; }
        tx(() -> {
            if (!db.run(Select.from(SUBMISSIONS).where(s -> s.get("state")
                    .in("PREPARED", "AUTHENTICATING", "OPENING", "OPENED", "SENDING", "POLLING", "ACCEPTED", "CLOSING", "WAITING_UPO", "INVOICE_REJECTED"))
                    .limit(1)).list().isEmpty()) { outbox.submit("wakeCollector", OutboxMessage.create()); }
            return null;
        });
    }

    @On(service = "KsefSubmissionOutbox", event = "wakeCollector")
    public void wake(OutboxMessageEventContext context) {
        if (Boolean.TRUE.equals(context.getIsInbound())) { context.setCompleted(); }
    }

    /** Jedna dostawa wykonuje ograniczoną liczbę odczytów; kolejne odczyty planuje trwały outbox. */
    public void process(UUID id) {
        requireEnabled();
        Claim claim = tx(() -> claim(id));
        if (claim == null) { return; }
        Map<String, Object> row = claim.row;
        String stage = (String) row.get("state");
        try {
            if (EFFECTS.contains(stage)) {
                // Restart po efekcie: utrwaliliśmy etap, lecz nie znamy odpowiedzi. Nigdy ponowny POST.
                save(claim, "RECONCILIATION_REQUIRED", Map.of("lastCode", "KSEF_INTERRUPTED_" + stage));
                return;
            }
            byte[] xml = documents.get((String) row.get("xmlStorageRef"));
            if (!row.get("xmlSha256").equals(KsefInvoiceSnapshotRegistry.sha256Hex(xml))) {
                throw KsefFailure.invalid("KSEF_XML_HASH_MISMATCH");
            }
            if ("PREPARED".equals(stage)) { save(claim, "AUTHENTICATING", Map.of()); }
            var tokens = authentication.authenticate((String) row.get("taxpayerNip"));
            if ("PREPARED".equals(stage)) {
                var encryption = session.encryption();
                save(claim, "OPENING", Map.of());
                String reference = session.open(encryption, tokens);
                save(claim, "OPENED", Map.of("sessionReference", reference));
                save(claim, "SENDING", Map.of());
                String invoiceReference = session.send(reference, xml, encryption, tokens);
                save(claim, "POLLING", Map.of("invoiceReference", invoiceReference));
            }
            tokens = authentication.refreshIfNeeded(tokens);
            if ("POLLING".equals(row.get("state"))) {
                var status = session.status((String) row.get("sessionReference"), (String) row.get("invoiceReference"), tokens);
                int code = status.getStatus().getCode();
                if (code == 100 || code == 150) { throw pending("KSEF_INVOICE_PENDING"); }
                if (code != 200) {
                    save(claim, "INVOICE_REJECTED", Map.of("lastCode", "KSEF_INVOICE_REJECTED_" + code));
                } else {
                    String expectedHash = Base64.getEncoder().encodeToString(HexFormat.of().parseHex((String) row.get("xmlSha256")));
                    if (status.getKsefNumber() == null || status.getKsefNumber().isBlank()
                            || !expectedHash.equals(status.getInvoiceHash())
                            || !row.get("invoiceReference").equals(status.getReferenceNumber())) {
                        throw KsefFailure.invalid("KSEF_ACCEPTANCE_PROOF_INVALID");
                    }
                    save(claim, "ACCEPTED", Map.of("ksefNumber", status.getKsefNumber(), "lastCode", "KSEF_ACCEPTED_200"));
                }
            }
            if (Set.of("ACCEPTED", "INVOICE_REJECTED").contains(row.get("state"))) {
                boolean rejected = "INVOICE_REJECTED".equals(row.get("state"));
                save(claim, "CLOSING", Map.of("closeAttempted", true));
                session.close((String) row.get("sessionReference"), tokens);
                save(claim, rejected ? "REJECTED" : "WAITING_UPO", Map.of("closeConfirmed", true));
                if (rejected) { return; }
            }
            if ("WAITING_UPO".equals(row.get("state"))) {
                int code = session.sessionStatus((String) row.get("sessionReference"), tokens);
                if (code == 100 || code == 170) { throw pending("KSEF_SESSION_PENDING"); }
                if (code != 200) { throw KsefFailure.invalid("KSEF_SESSION_STATUS_" + code); }
                if (!session.upoReady((String) row.get("sessionReference"), tokens)) { throw pending("KSEF_UPO_PENDING"); }
                byte[] upo = session.upo((String) row.get("sessionReference"), (String) row.get("invoiceReference"), tokens);
                if (upo == null || upo.length == 0) { throw pending("KSEF_UPO_PENDING"); }
                verifyUpoXml(upo);
                save(claim, "COMPLETED", Map.of("upoStorageRef", documents.put(upo), "lastCode", "KSEF_UPO_STORED"));
            }
        } catch (KsefFailure failure) {
            if (failure.retryable() && !EFFECTS.contains(row.get("state"))) {
                int attempts = ((Number) row.getOrDefault("readAttempts", 0)).intValue() + 1;
                Instant notBefore = failure.retryNotBefore() != null ? failure.retryNotBefore()
                        : clock.instant().plusMillis(Math.min(30000, 2000L << Math.min(attempts - 1, 4))
                                + ThreadLocalRandom.current().nextLong(500));
                save(claim, attempts >= 10 ? "READ_RETRY_EXHAUSTED" : (String) row.get("state"),
                        Map.of("readAttempts", attempts, "retryNotBefore", notBefore, "lastCode", failure.getMessage()));
                if (attempts < 10) { throw pending("KSEF_READ_SCHEDULED"); }
            } else {
                save(claim, "RECONCILIATION_REQUIRED", Map.of("lastCode", failure.getMessage()));
            }
        } catch (Exception unexpected) {
            save(claim, "RECONCILIATION_REQUIRED", Map.of("lastCode", "KSEF_UNEXPECTED_ERROR"));
        } finally {
            release(claim);
        }
    }

    /** Rekoncyliacja wyłącznie odczytami; nie uruchamia open/send/redeem starego auth ani close. */
    public void reconcile(UUID id) {
        requireEnabled();
        Claim claim = tx(() -> claimForReconciliation(id));
        if (claim == null) { return; }
        try {
            var row = claim.row;
            if (row.get("sessionReference") == null) { return; }
            var tokens = authentication.authenticate((String) row.get("taxpayerNip"));
            String hash = Base64.getEncoder().encodeToString(HexFormat.of().parseHex((String) row.get("xmlSha256")));
            var found = session.findByHash((String) row.get("sessionReference"), hash, tokens);
            if (found == null || found.getReferenceNumber() == null) { return; }
            // Utracony send został rozpoznany po hashu w tej samej sesji, bez ponownej wysyłki.
            if (Boolean.TRUE.equals(row.get("closeAttempted"))) {
                int status = session.sessionStatus((String) row.get("sessionReference"), tokens);
                if (status != 200) { return; }
                if (found.getStatus().getCode() != 200 || found.getKsefNumber() == null) { return; }
                save(claim, "ACCEPTED", Map.of("invoiceReference", found.getReferenceNumber(), "ksefNumber", found.getKsefNumber()));
                save(claim, "WAITING_UPO", Map.of("closeConfirmed", true));
            } else {
                save(claim, "POLLING", Map.of("invoiceReference", found.getReferenceNumber(), "lastCode", "KSEF_SEND_RECONCILED"));
            }
        } finally { release(claim); }
    }

    /** Odczyt metadanych dla operatora backendu; bez JWT, XML ani danych kluczy. */
    public Map<String, Object> status(UUID id) {
        return tx(() -> new LinkedHashMap<>(db.run(Select.from(SUBMISSIONS).byId(id.toString())).single()));
    }

    /** Kontrolowane wznowienie tylko bezpiecznych odczytów po wyczerpaniu budżetu. */
    public void resumeReads(UUID id) {
        requireEnabled();
        tx(() -> {
            var row = db.run(Select.from(SUBMISSIONS).byId(id.toString()).lock()).single();
            if (!"READ_RETRY_EXHAUSTED".equals(row.get("state")) || row.get("sessionReference") == null
                    || row.get("invoiceReference") == null
                    || (row.get("retryNotBefore") instanceof Instant due && due.isAfter(clock.instant()))) {
                throw KsefFailure.invalid("KSEF_READ_RESUME_NOT_ALLOWED");
            }
            String state = Boolean.TRUE.equals(row.get("closeConfirmed")) ? "WAITING_UPO" : "POLLING";
            var data = new LinkedHashMap<String, Object>();
            data.put("state", state);
            data.put("readAttempts", 0);
            data.put("retryNotBefore", null);
            db.run(Update.entity(SUBMISSIONS).byId(id.toString()).data(data));
            history(id, state, "KSEF_READS_RESUMED");
            var message = OutboxMessage.create();
            message.setParams(Map.of("submissionId", id.toString()));
            outbox.submit("submitInvoice", message);
            return null;
        });
    }

    private Claim claim(UUID id) {
        var row = db.run(Select.from(SUBMISSIONS).byId(id.toString()).lock()).first().orElse(null);
        if (row == null || FINAL.contains(row.get("state"))) { return null; }
        return claimRow(id, row);
    }

    private Claim claimForReconciliation(UUID id) {
        var row = db.run(Select.from(SUBMISSIONS).byId(id.toString()).lock()).single();
        if (!"RECONCILIATION_REQUIRED".equals(row.get("state"))) {
            throw KsefFailure.invalid("KSEF_RECONCILIATION_STATE_INVALID");
        }
        return claimRow(id, row);
    }

    private Claim claimRow(UUID id, Map<String, Object> row) {
        Instant now = clock.instant();
        if (row.get("leaseUntil") instanceof Instant lease && lease.isAfter(now)) {
            throw pending("KSEF_CLAIM_ACTIVE");
        }
        if (row.get("retryNotBefore") instanceof Instant notBefore && notBefore.isAfter(now)) {
            throw pending("KSEF_READ_NOT_DUE");
        }
        String token = UUID.randomUUID().toString();
        db.run(Update.entity(SUBMISSIONS).byId(id.toString())
                .data(Map.of("claimToken", token, "leaseUntil", now.plus(Duration.ofMinutes(5)))));
        return new Claim(id, token, new LinkedHashMap<>(row));
    }

    /** Etap + dowód biznesowy + historia w jednej krótkiej transakcji, warunkowane ważnym claimem. */
    private void save(Claim claim, String state, Map<String, Object> changes) {
        tx(() -> {
            var data = new LinkedHashMap<>(changes);
            data.put("state", state);
            long changed = db.run(Update.entity(SUBMISSIONS).data(data).where(s -> s.get("ID").eq(claim.id.toString())
                    .and(s.get("claimToken").eq(claim.token)).and(s.get("leaseUntil").gt(clock.instant())))).rowCount();
            if (changed != 1) { throw KsefFailure.invalid("KSEF_CLAIM_LOST"); }
            if ("ACCEPTED".equals(state)) {
                db.run(Update.entity(OPERATIONS).byId(claim.id.toString()).data(Map.of("status", "ACCEPTED",
                        "ksefNumber", data.get("ksefNumber"), "referenceNumber", data.getOrDefault("invoiceReference", claim.row.get("invoiceReference")),
                        "confirmedXmlSha256", claim.row.get("xmlSha256"))));
                updateInvoice(claim.id, Map.of("businessStatus", "ACCEPTED", "ksefNumber", data.get("ksefNumber")));
            } else if ("INVOICE_REJECTED".equals(state)) {
                db.run(Update.entity(OPERATIONS).byId(claim.id.toString()).data(Map.of("status", "REJECTED", "errorCode", data.get("lastCode"))));
                updateInvoice(claim.id, Map.of("businessStatus", "REJECTED"));
            } else if ("RECONCILIATION_REQUIRED".equals(state)) {
                // Potwierdzonego ACCEPTED nie cofamy, gdy niepewne jest tylko close/UPO.
                db.run(Update.entity(OPERATIONS).data(Map.of("status", state, "errorCode", data.get("lastCode")))
                        .where(o -> o.get("ID").eq(claim.id.toString()).and(o.get("status").ne("ACCEPTED")).and(o.get("status").ne("REJECTED"))));
            } else if ("POLLING".equals(state)) {
                db.run(Update.entity(OPERATIONS).byId(claim.id.toString())
                        .data(Map.of("status", "SUBMITTED", "referenceNumber", changes.getOrDefault("invoiceReference", claim.row.get("invoiceReference")))));
            } else if ("COMPLETED".equals(state)) {
                updateInvoice(claim.id, Map.of("upoStorageRef", data.get("upoStorageRef")));
            }
            history(claim.id, state, (String) changes.get("lastCode"));
            return null;
        });
        claim.row.putAll(changes);
        claim.row.put("state", state);
    }

    private void updateInvoice(UUID id, Map<String, Object> changes) {
        var operation = db.run(Select.from(OPERATIONS).byId(id.toString())).single();
        if (operation.get("invoice_ID") != null) {
            db.run(Update.entity(INVOICES).byId(operation.get("invoice_ID")).data(changes));
        }
    }

    private void release(Claim claim) {
        tx(() -> {
            var values = new LinkedHashMap<String, Object>();
            values.put("claimToken", null);
            values.put("leaseUntil", null);
            db.run(Update.entity(SUBMISSIONS).data(values).where(s -> s.get("ID").eq(claim.id.toString())
                    .and(s.get("claimToken").eq(claim.token))));
            return null;
        });
    }

    private void history(UUID id, String state, String code) {
        var row = new LinkedHashMap<String, Object>();
        row.put("ID", UUID.randomUUID().toString());
        row.put("submissionId", id.toString());
        row.put("occurredAt", clock.instant());
        row.put("state", state);
        row.put("code", code);
        db.run(Insert.into(HISTORY).entry(row));
    }

    private void requireEnabled() {
        if (!enabled || configuredNip.isBlank()) { throw KsefFailure.invalid("KSEF_TRANSPORT_DISABLED"); }
    }

    static KsefFailure pending(String code) { return new KsefFailure(code, false, true, null); }

    /** Osobny RequestContext/ChangeSet commitują etap przed siecią, także wewnątrz dostawy outbox. */
    <T> T tx(Supplier<T> action) {
        return runtime.requestContext().systemUser().run((RequestContext ctx) ->
                runtime.changeSetContext().run(cs -> { return action.get(); }));
    }

    /** Parser bez DTD/XXE. To kontrola formatu UPO, nie walidacja podpisu MF. */
    static void verifyUpoXml(byte[] bytes) {
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var root = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes)).getDocumentElement();
            if (!root.getNodeName().contains("Potwierdzenie") && !root.getNodeName().contains("Upo")
                    && !root.getNodeName().contains("UPO")) { throw KsefFailure.invalid("KSEF_UPO_FORMAT_INVALID"); }
        } catch (KsefFailure safe) { throw safe; }
        catch (Exception e) { throw KsefFailure.invalid("KSEF_UPO_FORMAT_INVALID"); }
    }

    private record Claim(UUID id, String token, Map<String, Object> row) { }
}
