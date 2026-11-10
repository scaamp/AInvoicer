package customer.cap_java.ksef.inbound.persistence;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.RequestContext;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.ksef.inbound.sync.SyncScope;

/** Krótkie transakcje CAP; HTTP i document store zawsze poza ChangeSet. */
public final class InboundPersistence {
    public static final String DOCUMENTS = "customer.cap_java.KsefInboundDocuments";
    public static final String GRANTS = "customer.cap_java.KsefInboundAccessGrants";
    public static final String CONFLICTS = "customer.cap_java.KsefInboundIntegrityConflicts";
    public static final String CHECKPOINTS = "customer.cap_java.KsefInboundSyncCheckpoints";
    public static final String LEASES = "customer.cap_java.KsefInboundSyncLeases";
    final PersistenceService db;
    private final CdsRuntime runtime;

    public InboundPersistence(PersistenceService db, CdsRuntime runtime) {
        this.db = db;
        this.runtime = runtime;
    }

    /** Nowy RequestContext i ChangeSet zgodnie z istniejącym wzorcem workerów AInvoicer. */
    public <T> T tx(Supplier<T> work) {
        return runtime.requestContext().systemUser().run((RequestContext ctx) ->
                runtime.changeSetContext().run(cs -> { return work.get(); }));
    }

    static Map<String, Object> key(SyncScope scope) {
        return Map.of("environment", scope.environment().name(), "contextNip", scope.contextNip(),
                "subjectRole", scope.role().name());
    }

    static String id(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    static Instant instant(Object value) {
        return value instanceof Instant instant ? instant : Instant.parse(value.toString());
    }

    /** Tylko naruszenie unikalności jest wyścigiem INSERT; inne błędy DB propagują się. */
    static boolean uniqueViolation(ServiceException error) {
        // CAP 5.2 CdsErrorStatuses.UNIQUE_CONSTRAINT_VIOLATED = 409006.
        // Enum jest w runtime-only cds-services-utils; używamy publicznego ErrorStatus z services-api.
        return "409006".equals(error.getErrorStatus().getCodeString());
    }
}
