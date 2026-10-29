package customer.cap_java.ksef;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sap.cds.UniqueConstraintException;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.UserInfo;

import cds.gen.ksefservice.RequestSendContext;
import cds.gen.ksefservice.SendRequestResult;
import customer.cap_java.ksef.fa3.Fa3Document;
import customer.cap_java.ksef.fa3.Fa3ValidationException;
import customer.cap_java.sap.SapInvoiceRemoteAdapter;
import customer.cap_java.sap.SapInvoiceSnapshot;

/**
 * Akcja OData V4 {@code KsefService.requestSend(invoiceUUID)} — serwerowa ochrona przed duplikatem
 * wysyłki (ADR-0005, decyzja 7). Jedynym wejściem jest InvoiceUUID: NIP, CompanyCode, kierunek,
 * kwoty, XML i klucz idempotencji CAP wyznacza sam z odczytu RAP (communication user) i konfiguracji.
 *
 * <p>Kolejność: (1) odczyt RAP i walidacje bez zapisu, (2) wygenerowanie i walidacja FA(3) bez DB,
 * (3) w jednym ChangeSet żądania: atomowa rezerwacja {@code KsefSendReservations} po PK
 * (invoiceUUID, environment) albo CAS przepięcia po potwierdzonym odrzuceniu, operacja NEW,
 * migawka SAP_RAP, zlecenie i outbox. Rollback dowolnego kroku cofa wszystko; po commit worker
 * wysyła asynchronicznie. Odpowiedź QUEUED NIE oznacza przyjęcia przez KSeF. Brak wywołań KSeF.
 */
@Component
@ServiceName("KsefService")
public class KsefSendRequestHandler implements EventHandler {
    static final String RESERVATIONS = "customer.cap_java.KsefSendReservations";
    static final String ENVIRONMENT = "TEST";
    /** Kody odrzucenia, które mogą oznaczać duplikat w KSeF — wymagają uzgodnienia, nie ponownej wysyłki. */
    private static final Set<String> POSSIBLE_DUPLICATE = Set.of("KSEF_INVOICE_REJECTED_440");

    private final PersistenceService db;
    private final SapInvoiceRemoteAdapter sap;
    private final KsefFa3Documents documents;
    private final KsefPartyDataProvider parties;
    private final KsefInvoicePreparation preparation;
    private final KsefSubmissionWorker worker;
    private final boolean retryAfterRejection;
    private final int maxAttempts;

    public KsefSendRequestHandler(PersistenceService db, SapInvoiceRemoteAdapter sap, KsefFa3Documents documents,
            KsefPartyDataProvider parties, KsefInvoicePreparation preparation, KsefSubmissionWorker worker,
            @Value("${ainv.ksef.send.retry-after-rejection:false}") boolean retryAfterRejection,
            @Value("${ainv.ksef.send.max-attempts:1}") int maxAttempts) {
        // Błędna zaufana konfiguracja zatrzymuje start, zamiast otwierać nieograniczone ponowienia.
        // Limit obejmuje pierwszą próbę; zmiana polityki nie modyfikuje istniejących rezerwacji.
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("ainv.ksef.send.max-attempts must be between 1 and 10");
        }
        this.db = db;
        this.sap = sap;
        this.documents = documents;
        this.parties = parties;
        this.preparation = preparation;
        this.worker = worker;
        this.retryAfterRejection = retryAfterRejection;
        this.maxAttempts = maxAttempts;
    }

    @On(event = RequestSendContext.CDS_NAME)
    public void onRequestSend(RequestSendContext context) {
        UUID invoiceId = parseUuid(context.getInvoiceUUID());
        UserInfo user = context.getUserInfo();

        // (1) Dane faktury wyłącznie z RAP; przeglądarka niczego nie dostarcza poza kluczem.
        SapInvoiceSnapshot invoice;
        java.util.Optional<SapInvoiceSnapshot> found;
        try {
            found = sap.readInvoice(invoiceId);
        } catch (RuntimeException unavailable) {
            // Bez treści zdalnego błędu (może zawierać URL/konfigurację Destination).
            throw fail(ErrorStatuses.BAD_GATEWAY, "SAP_INVOICE_READ_FAILED");
        }
        invoice = found.orElseThrow(() -> fail(ErrorStatuses.NOT_FOUND, "KSEF_INVOICE_NOT_FOUND"));
        Map<String, Object> header = invoice.header();
        String nip = text(header.get("SellerNip"));
        String companyCode = text(header.get("CompanyCode"));
        if (!user.getAttributeValues("taxpayerNips").contains(nip)) {
            throw fail(ErrorStatuses.FORBIDDEN, "KSEF_TAXPAYER_NOT_AUTHORIZED");
        }
        if (!user.getAttributeValues("companyCodes").contains(companyCode)) {
            throw fail(ErrorStatuses.FORBIDDEN, "KSEF_COMPANY_CODE_NOT_AUTHORIZED");
        }
        if (!"OUTBOUND".equals(header.get("Direction"))) {
            throw fail(ErrorStatuses.UNPROCESSABLE_ENTITY, "KSEF_OUTBOUND_ONLY");
        }
        // RAP ma już potwierdzony wynik — biznesowo faktura jest w KSeF.
        if (!text(header.get("KsefNumber")).isBlank()) {
            throw fail(ErrorStatuses.CONFLICT, "KSEF_ALREADY_RECORDED_IN_SAP");
        }
        if (!worker.acceptsContext(nip)) {
            throw fail(ErrorStatuses.CONFLICT, "KSEF_CONTEXT_NOT_CONFIGURED");
        }

        // (2) FA(3) + XSD + reguły domenowe przed jakimkolwiek zapisem; te bajty pójdą do migawki i wysyłki.
        var party = parties.forInvoice(header)
                .orElseThrow(() -> fail(ErrorStatuses.UNPROCESSABLE_ENTITY, "KSEF_PARTY_DATA_MISSING"));
        Fa3Document document;
        try {
            document = documents.prepare(invoice, party);
        } catch (KsefFailure | Fa3ValidationException invalid) {
            throw fail(ErrorStatuses.UNPROCESSABLE_ENTITY, "KSEF_FA3_INVALID");
        }

        // (3) Atomowa rezerwacja i zlecenie w ChangeSet żądania OData.
        UUID operationId = UUID.randomUUID();
        int attempt = reserve(invoiceId, nip, companyCode, operationId);
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("ID", operationId.toString());
        operation.put("sapDocumentRef", invoiceId.toString());
        operation.put("taxpayerNip", nip);
        operation.put("companyCode", companyCode);
        operation.put("direction", "OUTBOUND");
        operation.put("environment", ENVIRONMENT);
        operation.put("operationType", "SEND_INVOICE");
        operation.put("status", "NEW");
        // Deterministyczny klucz to druga, niezależna bariera UNIQUE (taxpayerNip, idempotencyKey).
        operation.put("idempotencyKey", "SEND:" + invoiceId + ":" + ENVIRONMENT + ":" + attempt);
        insertGuarded(KsefSubmissionWorker.OPERATIONS, operation);
        preparation.enqueueVerifiedFromRap(operationId, invoice, document);

        SendRequestResult result = SendRequestResult.create();
        result.setOperationId(operationId.toString());
        result.setStatus("QUEUED");
        result.setAttempt(attempt);
        context.setResult(result);
    }

    /**
     * Zwraca numer próby. Pierwsza próba: INSERT po kluczu głównym (równoległy INSERT przegrywa w bazie).
     * Kolejna: wyłącznie po potwierdzonym, zamkniętym odrzuceniu i włączonej polityce — warunkowy UPDATE
     * (CAS) po poprzednim operationId i numerze próby; wyścig dwóch ponowień wygrywa jeden.
     */
    private int reserve(UUID invoiceId, String nip, String companyCode, UUID operationId) {
        // Także starsza próba (NEW/FAILED/nieznana) musi blokować, nawet gdy rezerwacja wskazuje nowszą.
        var unresolved = db.run(Select.from(KsefSubmissionWorker.OPERATIONS).columns("status")
                .where(o -> o.get("sapDocumentRef").eq(invoiceId.toString()).and(o.get("environment").eq(ENVIRONMENT))
                        .and(o.get("operationType").eq("SEND_INVOICE")).and(o.get("status").ne("REJECTED")))
                .limit(1)).first();
        if (unresolved.isPresent()) {
            String status = text(unresolved.get().get("status"));
            throw fail(ErrorStatuses.CONFLICT, "ACCEPTED".equals(status) ? "KSEF_ALREADY_ACCEPTED"
                    : "RECONCILIATION_REQUIRED".equals(status) ? "KSEF_RECONCILIATION_REQUIRED" : "KSEF_SEND_IN_PROGRESS");
        }
        var existing = db.run(Select.from(RESERVATIONS).where(r -> r.get("invoiceUUID").eq(invoiceId.toString())
                .and(r.get("environment").eq(ENVIRONMENT))).lock()).first();
        if (existing.isEmpty()) {
            requireNoLegacySend(invoiceId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("invoiceUUID", invoiceId.toString());
            row.put("environment", ENVIRONMENT);
            row.put("taxpayerNip", nip);
            row.put("companyCode", companyCode);
            row.put("operationId", operationId.toString());
            row.put("attempt", 1);
            insertGuarded(RESERVATIONS, row);
            return 1;
        }
        var reservation = existing.get();
        String previousOperation = text(reservation.get("operationId"));
        int previousAttempt = ((Number) reservation.get("attempt")).intValue();
        var operation = db.run(Select.from(KsefSubmissionWorker.OPERATIONS).byId(previousOperation)).first()
                .orElseThrow(() -> fail(ErrorStatuses.CONFLICT, "KSEF_SEND_STATE_UNKNOWN"));
        String status = text(operation.get("status"));
        switch (status) {
            case "ACCEPTED" -> throw fail(ErrorStatuses.CONFLICT, "KSEF_ALREADY_ACCEPTED");
            case "RECONCILIATION_REQUIRED" -> throw fail(ErrorStatuses.CONFLICT, "KSEF_RECONCILIATION_REQUIRED");
            case "REJECTED" -> requireRetryAllowed(previousOperation, previousAttempt);
            default -> throw fail(ErrorStatuses.CONFLICT, "KSEF_SEND_IN_PROGRESS");
        }
        int attempt = previousAttempt + 1;
        long changed = db.run(Update.entity(RESERVATIONS).data(Map.of("operationId", operationId.toString(),
                "attempt", attempt, "taxpayerNip", nip, "companyCode", companyCode)).where(r -> r.get("invoiceUUID").eq(invoiceId.toString())
                .and(r.get("environment").eq(ENVIRONMENT)).and(r.get("operationId").eq(previousOperation))
                .and(r.get("attempt").eq(previousAttempt)))).rowCount();
        if (changed != 1) {
            throw fail(ErrorStatuses.CONFLICT, "KSEF_SEND_ALREADY_REQUESTED");
        }
        return attempt;
    }

    /** Ponowienie tylko po końcowym odrzuceniu (sesja zamknięta) i bez sygnału możliwego duplikatu. */
    private void requireRetryAllowed(String operationId, int attempt) {
        var submission = db.run(Select.from(KsefSubmissionWorker.SUBMISSIONS).byId(operationId)).first();
        if (submission.isEmpty() || !"REJECTED".equals(submission.get().get("state"))) {
            throw fail(ErrorStatuses.CONFLICT, "KSEF_SEND_IN_PROGRESS");
        }
        boolean duplicateSignal = !db.run(Select.from(KsefSubmissionWorker.HISTORY).columns("ID")
                .where(h -> h.get("submissionId").eq(operationId).and(h.get("code").in(List.copyOf(POSSIBLE_DUPLICATE))))
                .limit(1)).list().isEmpty();
        if (duplicateSignal) {
            throw fail(ErrorStatuses.CONFLICT, "KSEF_POSSIBLE_DUPLICATE_RECONCILIATION_REQUIRED");
        }
        if (!retryAfterRejection) {
            throw fail(ErrorStatuses.CONFLICT, "KSEF_RESEND_NOT_ALLOWED");
        }
        if (attempt >= maxAttempts) {
            throw fail(ErrorStatuses.CONFLICT, "KSEF_RESEND_LIMIT_REACHED");
        }
    }

    /** Operacje SEND sprzed wprowadzenia rezerwacji: aktywne/przyjęte/nieznane też blokują. */
    private void requireNoLegacySend(UUID invoiceId) {
        var blocking = db.run(Select.from(KsefSubmissionWorker.OPERATIONS).columns("status")
                .where(o -> o.get("sapDocumentRef").eq(invoiceId.toString()).and(o.get("environment").eq(ENVIRONMENT))
                        .and(o.get("operationType").eq("SEND_INVOICE")))
                .limit(1)).first();
        if (blocking.isPresent()) {
            throw fail(ErrorStatuses.CONFLICT, "ACCEPTED".equals(blocking.get().get("status"))
                    ? "KSEF_ALREADY_ACCEPTED" : "KSEF_SEND_IN_PROGRESS");
        }
    }

    /** INSERT, którego naruszenie unikalności/blokady oznacza równoległą próbę → 409, bez szczegółów DB. */
    private void insertGuarded(String entity, Map<String, Object> row) {
        try {
            db.run(Insert.into(entity).entry(row));
        } catch (RuntimeException failure) {
            if (isConcurrencyConflict(failure)) {
                throw fail(ErrorStatuses.CONFLICT, "KSEF_SEND_ALREADY_REQUESTED");
            }
            throw failure;
        }
    }

    static boolean isConcurrencyConflict(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof UniqueConstraintException) {
                return true;
            }
            if (t instanceof ServiceException service && service.getErrorStatus().getHttpStatus() == 409) {
                return true;
            }
            // 23xxx = naruszenie ograniczenia; 40001 = konflikt serializacji; HYT00 = timeout blokady (H2).
            if (t instanceof SQLException sql && sql.getSQLState() != null && (sql.getSQLState().startsWith("23")
                    || "40001".equals(sql.getSQLState()) || "HYT00".equals(sql.getSQLState()))) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value == null ? "" : value.trim());
        } catch (IllegalArgumentException invalid) {
            throw fail(ErrorStatuses.BAD_REQUEST, "KSEF_INVOICE_UUID_INVALID");
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static ServiceException fail(ErrorStatuses status, String code) {
        return new ServiceException(status, code);
    }
}
