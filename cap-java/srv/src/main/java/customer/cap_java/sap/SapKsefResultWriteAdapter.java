package customer.cap_java.sap;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sap.cds.ql.Select;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.request.RequestContext;
import com.sap.cds.services.runtime.CdsRuntime;
import com.sap.cloud.sdk.datamodel.odata.client.exception.ODataResponseException;

import cds.gen.com.sap.gateway.srvd_a2x.zapi_ainv_inv_h_o4.v0001.ZcAinvInvHApplyKsefResultContext;

/** CAP Remote Service wykonuje wyłącznie zweryfikowaną statyczną akcję RAP, nigdy PATCH. */
@Component
public class SapKsefResultWriteAdapter {
    private final CqnService remote;
    private final CdsRuntime runtime;
    private final boolean enabled;

    public SapKsefResultWriteAdapter(@Qualifier("AinvoicerH01") CqnService remote,
            CdsRuntime runtime, @Value("${ainv.sap-write.enabled:false}") boolean enabled) {
        this.remote = remote;
        this.runtime = runtime;
        this.enabled = enabled;
    }

    /** Kontrola bramki poprzedza wszelki HTTP; ponowienie nie pobiera nowej wersji dokumentu. */
    public WriteResult apply(SapKsefResultCommand command) {
        if (!enabled) {
            throw new WriteFailure("SAP_WRITE_DISABLED", false);
        }
        // Błąd Remote Service anuluje swój ChangeSet. Osobna granica pozwala workerowi
        // zapisać trwały wynik odmowy zamiast wycofać własną transakcję.
        return runtime.requestContext().systemUser().run((RequestContext ctx) ->
                runtime.changeSetContext().run(changeSet -> { return execute(command); }));
    }

    private WriteResult execute(SapKsefResultCommand command) {
        var context = ZcAinvInvHApplyKsefResultContext.create();
        context.setCqn(Select.from(SapInvoiceRemoteAdapter.HEADERS));
        context.setInvoiceUUID(command.invoiceId().toString());
        context.setWriteRequestId(command.requestId().toString());
        context.setKsefNumber(command.ksefNumber());
        context.setEnvironment("TEST");
        context.setTaxpayerNip(command.taxpayerNip());
        context.setCompanyCode(command.companyCode());
        context.setExpectedLocalLastChangedAt(command.expectedVersion());
        try {
            remote.emit(context);
        } catch (ServiceException failure) {
            // Nie przenosimy payloadu/odpowiedzi SAP ani credentiali do trwałych błędów outboxa.
            int status = failure.getErrorStatus().getHttpStatus();
            Instant retryNotBefore = null;
            // CAP 5.2 opakowuje odpowiedź zdalną w HTTP 500; rzeczywisty kod jest
            // strukturalnym argumentem komunikatu, nie tekstem wymagającym parsowania.
            if ("50012005".equals(failure.getErrorStatus().getCodeString())) {
                Object[] args = failure.getMessageLookup().getArgs();
                if (args.length > 0 && args[0] instanceof Number httpCode) {
                    status = httpCode.intValue();
                }
                if (args.length > 1 && args[1] instanceof ODataResponseException response) {
                    retryNotBefore = retryNotBefore(response);
                }
            }
            // ServiceException przenosi końcowy argument Throwable do łańcucha cause.
            for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof ODataResponseException response) {
                    retryNotBefore = retryNotBefore(response);
                    break;
                }
            }
            // 429 i jawny Retry-After wstrzymują automatyczne próby. Termin zostaje
            // w persystencji do świadomego wznowienia; ogólny backoff nie może go wyprzedzić.
            throw new WriteFailure("SAP_HTTP_" + status,
                    (status == 408 || status >= 500) && retryNotBefore == null, retryNotBefore);
        } catch (RuntimeException transportFailure) {
            throw new WriteFailure("SAP_TRANSPORT_UNKNOWN", true);
        }
        try {
            var value = context.getResult();
            var outcome = Outcome.valueOf(value.getOutcome());
            UUID invoiceId = UUID.fromString(value.getInvoiceUUID());
            if (!invoiceId.equals(command.invoiceId())) {
                throw new IllegalArgumentException();
            }
            UUID recordedRequestId = value.getKsefWriteRequestId() == null ? null
                    : UUID.fromString(value.getKsefWriteRequestId());
            var result = new WriteResult(outcome, invoiceId, value.getKsefNumber(),
                    value.getKsefEnvironment(), value.getKsefRecordedAt(), recordedRequestId);
            if (outcome.success() && (!command.ksefNumber().equals(result.ksefNumber())
                    || !"TEST".equals(result.environment()) || result.recordedAt() == null
                    || recordedRequestId == null
                    || (outcome == Outcome.APPLIED && !command.requestId().equals(recordedRequestId)))) {
                throw new IllegalArgumentException();
            }
            return result;
        } catch (RuntimeException invalidResponse) {
            // Nieznany Outcome i niespójny sukces wymagają rekoncyliacji, nie są sukcesem HTTP 200.
            throw new WriteFailure("SAP_INVALID_RESULT", false);
        }
    }

    private Instant retryNotBefore(ODataResponseException response) {
        String value = response.getHttpHeaders().stream()
                .filter(header -> "Retry-After".equalsIgnoreCase(header.getName()))
                .map(header -> header.getValue()).findFirst().orElse(null);
        if (value == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return Instant.now().plusSeconds(Math.max(0, seconds));
        } catch (RuntimeException notSeconds) {
            try {
                return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (RuntimeException invalidHeader) {
                return null;
            }
        }
    }

    public enum Outcome {
        APPLIED, ALREADY_APPLIED, VERSION_CONFLICT, IDEMPOTENCY_CONFLICT, RESULT_CONFLICT;

        public boolean success() {
            return this == APPLIED || this == ALREADY_APPLIED;
        }
    }

    public record WriteResult(Outcome outcome, UUID invoiceId, String ksefNumber,
            String environment, Instant recordedAt, UUID recordedRequestId) { }

    public static final class WriteFailure extends RuntimeException {
        private final boolean retryable;
        private final Instant retryNotBefore;

        public WriteFailure(String safeCode, boolean retryable) {
            this(safeCode, retryable, null);
        }

        public WriteFailure(String safeCode, boolean retryable, Instant retryNotBefore) {
            super(safeCode);
            this.retryable = retryable;
            this.retryNotBefore = retryNotBefore;
        }

        public boolean retryable() {
            return retryable;
        }

        public Instant retryNotBefore() {
            return retryNotBefore;
        }
    }
}
