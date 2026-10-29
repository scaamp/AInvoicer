package customer.cap_java.sap;

import static org.junit.jupiter.api.Assertions.*;

import com.sap.cds.services.ServiceException;
import com.sap.cloud.sdk.datamodel.odata.client.exception.ODataResponseException;
import com.sap.cloud.sdk.datamodel.odata.client.exception.ODataServiceErrorException;

/** Dowód odmowy CRUD: zdalna odpowiedź 403 i uzgodniony kod autoryzacji SAP; bez parsowania message/body. */
final class SapCrudDenialAssertions {
    private SapCrudDenialAssertions() { }

    static void assertAuthorizationDenied(Runnable update, String expectedSapCode) {
        assertNotNull(expectedSapCode, "Wymagany uzgodniony kod odmowy autoryzacyjnej SAP");
        assertFalse(expectedSapCode.isBlank());
        ServiceException failure = assertThrows(ServiceException.class, update::run,
                "Nieautoryzowany UPDATE musi zostać odmówiony");
        ODataResponseException response = null;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ODataResponseException found) { response = found; break; }
        }
        // CAP 5.2.0: REMOTE_HTTP_ERROR (50012005) ma zdalny status w args[0],
        // końcowy Throwable zwykle trafia do cause; obsługujemy również wariant args[1].
        if (response == null && "50012005".equals(failure.getErrorStatus().getCodeString())) {
            for (Object arg : failure.getMessageLookup().getArgs()) {
                if (arg instanceof ODataResponseException found) { response = found; break; }
            }
        }
        assertNotNull(response, "Brak dowodu odpowiedzi HTTP usługi SAP");
        assertEquals(403, response.getHttpCode(), "Zdalny status musi oznaczać odmowę");
        var sap = assertInstanceOf(ODataServiceErrorException.class, response,
                "Brak strukturalnego kodu SAP; sam 403 może oznaczać np. błąd CSRF");
        assertEquals(expectedSapCode, sap.getOdataError().getODataCode(), "Niewłaściwy kod odmowy SAP");
    }
}
