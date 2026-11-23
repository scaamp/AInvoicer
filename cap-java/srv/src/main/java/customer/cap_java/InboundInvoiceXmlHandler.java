package customer.cap_java;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.sap.cds.Row;
import com.sap.cds.ql.CQL;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.cqn.CqnAnalyzer;
import com.sap.cds.ql.cqn.CqnSelect;
import com.sap.cds.services.ErrorStatus;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CdsReadEventContext;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;

import customer.cap_java.ksef.KsefFailure;
import customer.cap_java.ksef.storage.InboundDocumentStorage;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;

/**
 * KSEF-IN-05 (B4): {@code GET /odata/v4/KsefService/InboundInvoiceXml(<uuid>)/content} — oryginalne bajty XML
 * faktury INBOUND z prywatnego magazynu (scope {@code INBOUND/TEST}).
 *
 * <p>Kolejność kontroli (każda porażka przed odczytem BLOB; brak rozróżnienia „obcy” / „nie istnieje”):
 * <ol>
 *   <li>CAP: uwierzytelnienie (401) i rola {@code KsefOperator} z {@code @restrict} (403);</li>
 *   <li>flaga {@code ainv.ksef.inbound.xml-download.enabled} (domyślnie OFF → 404), wyłącznie bezpośredni GET
 *       zasobu {@code /content} (żądanie {@code $batch} lub odczyt encji bez {@code /content} → 403),
 *       wyłącznie jednego klucza (lista/filtry/{@code $count} → 400);</li>
 *   <li>zakres principala z {@link InboundGrantScope}: 1 NIP + 1 CompanyCode, grant {@code (NIP, BUYER)}, TEST;</li>
 *   <li>wiersz {@code STORED/VERIFIED} z referencją — referencja czytana z DB po stronie serwera;</li>
 *   <li>{@code store.get} w scope INBOUND/TEST + ponowna kontrola SHA-256 i długości z metadanych wiersza.</li>
 * </ol>
 * Brak podłączonego magazynu → 503 bez szczegółów; awaria magazynu lub integralności → 500 bez szczegółów.
 * Treść XML, referencja i hash nigdy nie trafiają do logów ani komunikatów błędów. Brak HTTP do KSeF.
 */
@Component
@ServiceName("KsefService")
public class InboundInvoiceXmlHandler implements EventHandler {
    static final String ENTITY = "KsefService.InboundInvoiceXml";
    static final String ENABLED = "ainv.ksef.inbound.xml-download.enabled";
    private static final Set<String> MEDIA_COLUMNS = Set.of("id", "fileName", "content");
    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Logger log = LoggerFactory.getLogger(InboundInvoiceXmlHandler.class);
    /** 503 bez szczegółów: magazyn niepodłączony lub chwilowo niedostępny (CAP 5.2 nie ma stałej 503). */
    static final ErrorStatus UNAVAILABLE = new ErrorStatus() {
        @Override public String getCodeString() { return "503"; }
        @Override public int getHttpStatus() { return 503; }
    };

    private final PersistenceService db;
    private final ObjectProvider<InboundDocumentStorage> storage;
    private final boolean enabled;

    public InboundInvoiceXmlHandler(PersistenceService db, ObjectProvider<InboundDocumentStorage> storage,
            @Value("${" + ENABLED + ":false}") boolean enabled) {
        this.db = db;
        this.storage = storage;
        this.enabled = enabled;
    }

    /** Jedyne źródło danych encji bez persystencji; CAP nie wykona żadnego SELECT na tej encji. */
    @On(event = CqnService.EVENT_READ, entity = ENTITY)
    public void read(CdsReadEventContext context) {
        if (!enabled) { throw notFound(); }
        String id = requestedId(context);
        rejectBatchAndMarkNoStore();
        InboundDocumentStorage inbound = storage.getIfAvailable();
        if (inbound == null) {
            throw new ServiceException(UNAVAILABLE, "INBOUND_XML_UNAVAILABLE");
        }
        // Grant i stan wiersza w jednym CQN po stronie DB — przed jakimkolwiek odczytem BLOB.
        Row row = db.run(Select.from(InboundGrantScope.DOCUMENTS)
                .columns("ID", "ksefNumber", "xmlStorageRef", "sha256", "sizeBytes")
                .where(CQL.get("ID").eq(id)
                        .and(InboundGrantScope.visible(context.getUserInfo(), "ID"))
                        .and(CQL.get("ingestionStatus").eq("STORED"))
                        .and(CQL.get("integrityStatus").eq("VERIFIED"))
                        .and(CQL.get("xmlStorageRef").isNotNull())
                        .and(CQL.get("parseStatus").in("PARSED", "UNSUPPORTED_SCHEMA"))))
                .first().orElseThrow(InboundInvoiceXmlHandler::notFound);
        byte[] xml = readVerified(inbound, row);
        var result = new HashMap<String, Object>();
        result.put("id", id);
        result.put("fileName", fileName((String) row.get("ksefNumber"), id));
        result.put("content", new ByteArrayInputStream(xml));
        context.setResult(List.of(result));
    }

    /** Odczyt w scope INBOUND/TEST i niezależna kontrola SHA-256/długości z metadanych KSeF zapisanych w stagingu. */
    private static byte[] readVerified(InboundDocumentStorage inbound, Row row) {
        byte[] xml;
        try {
            xml = inbound.store().get((String) row.get("xmlStorageRef"));
        } catch (KsefFailure failure) {
            log.warn("INBOUND_XML_READ_FAILED code={} retryable={}", failure.getMessage(), failure.retryable());
            throw new ServiceException(failure.retryable() ? UNAVAILABLE : ErrorStatuses.SERVER_ERROR,
                    "INBOUND_XML_UNAVAILABLE");
        } catch (RuntimeException failure) {
            log.warn("INBOUND_XML_READ_FAILED code=UNEXPECTED type={}", failure.getClass().getSimpleName());
            throw new ServiceException(ErrorStatuses.SERVER_ERROR, "INBOUND_XML_UNAVAILABLE");
        }
        Object size = row.get("sizeBytes");
        boolean intact = xml != null && xml.length > 0
                && MessageDigest.isEqual(KsefInvoiceSnapshotRegistry.sha256Hex(xml).getBytes(), String.valueOf(row.get("sha256")).getBytes())
                && (size == null || ((Number) size).longValue() == xml.length);
        if (!intact) {
            log.warn("INBOUND_XML_READ_FAILED code=INTEGRITY_MISMATCH");
            throw new ServiceException(ErrorStatuses.SERVER_ERROR, "INBOUND_XML_UNAVAILABLE");
        }
        return xml;
    }

    /**
     * Dozwolony wyłącznie kształt media read: jeden segment z kluczem, bez where/expand/orderBy/paging,
     * kolumny ⊆ {id, fileName, content} z {@code content}. Lista, {@code $count}, {@code $filter} → 400.
     */
    private static String requestedId(CdsReadEventContext context) {
        CqnSelect query = context.getCqn();
        boolean mediaOnly = query.items().stream().anyMatch(i -> i.isRef() && "content".equals(i.asRef().lastSegment()))
                && query.items().stream().allMatch(i -> i.isRef() && i.asRef().segments().size() == 1
                        && MEDIA_COLUMNS.contains(i.asRef().lastSegment()));
        if (query.ref().segments().size() != 1 || query.ref().rootSegment().filter().isEmpty() || query.where().isPresent()
                || query.search().isPresent() || !query.orderBy().isEmpty() || !query.groupBy().isEmpty()
                || query.having().isPresent() || !mediaOnly) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST, "INBOUND_XML_CONTENT_BY_KEY_ONLY");
        }
        Map<String, Object> keys = CqnAnalyzer.create(context.getModel()).analyze(query).targetKeyValues();
        Object id = keys.get("id");
        if (keys.size() != 1 || id == null || !UUID_PATTERN.matcher(id.toString()).matches()) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST, "INBOUND_XML_CONTENT_BY_KEY_ONLY");
        }
        return id.toString();
    }

    /**
     * Pobranie wyłącznie bezpośrednim GET (nie w {@code $batch}: brak multipart z treścią faktury i obejścia nagłówków).
     * Brak kontekstu HTTP (wywołanie spoza adaptera OData) = odmowa. Na odpowiedź nakładamy {@code Cache-Control: no-store}.
     */
    private static void rejectBatchAndMarkNoStore() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes http)
                || http.getRequest().getRequestURI() == null || !"GET".equals(http.getRequest().getMethod())
                // Wyłącznie zasób mediów; $batch (URI .../$batch) i odczyt encji bez /content są odrzucane.
                || !http.getRequest().getRequestURI().endsWith("/content")) {
            throw new ServiceException(ErrorStatuses.FORBIDDEN, "INBOUND_XML_DIRECT_GET_ONLY");
        }
        if (http.getResponse() != null) {
            http.getResponse().setHeader("Cache-Control", "no-store");
            http.getResponse().setHeader("X-Content-Type-Options", "nosniff");
        }
    }

    /** Nazwa pliku wyłącznie z bezpiecznego alfabetu (numer KSeF to [0-9A-F-]); inaczej identyfikator dokumentu. */
    static String fileName(String ksefNumber, String id) {
        String base = ksefNumber != null && ksefNumber.matches("[0-9A-Za-z-]{1,60}") ? ksefNumber : id;
        return base + ".xml";
    }

    private static ServiceException notFound() {
        return new ServiceException(ErrorStatuses.NOT_FOUND, "INBOUND_XML_NOT_FOUND");
    }
}
