package customer.cap_java.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.EventContext;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.HandlerOrder;
import com.sap.cds.services.handler.annotations.ServiceName;

import cds.gen.ksefservice.RequestSendContext;

/**
 * Tryb identity-smoke (CAP-07.1, {@link CloudPersistenceGuard}): baza nietrwała, więc żaden request nie
 * może utworzyć historii procesu KSeF, którą restart by usunął.
 *
 * <p>Aktywny wyłącznie przy {@code ainv.deployment.identity-smoke=true}. Kolejność LATE: najpierw
 * działa autoryzacja CAP ({@code @requires}/{@code @restrict}), więc użytkownik bez roli nadal dostaje
 * 403, a dopiero uprawniony operator — 409 z kodem trybu (jak KSEF_CONTEXT_NOT_CONFIGURED). Odczyty pozostają dostępne do testów zakresu.
 */
@Component
@ServiceName("KsefService")
@ConditionalOnProperty(name = "ainv.deployment.identity-smoke", havingValue = "true")
public class IdentitySmokeReadOnlyHandler implements EventHandler {

    static final String CODE = "AINV_IDENTITY_SMOKE_READ_ONLY";

    @Before(event = RequestSendContext.CDS_NAME)
    @HandlerOrder(HandlerOrder.LATE)
    public void rejectSend(EventContext context) {
        throw readOnly();
    }

    @Before(event = { CqnService.EVENT_CREATE, CqnService.EVENT_UPSERT, CqnService.EVENT_UPDATE,
            CqnService.EVENT_DELETE }, entity = "KsefService.KsefOperations")
    @HandlerOrder(HandlerOrder.LATE)
    public void rejectWrites(EventContext context) {
        throw readOnly();
    }

    private static ServiceException readOnly() {
        return new ServiceException(ErrorStatuses.CONFLICT, CODE);
    }
}
