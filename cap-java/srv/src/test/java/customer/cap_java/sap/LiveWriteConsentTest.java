package customer.cap_java.sap;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Wyłącznie czyste dane; test nie ma adaptera ani dostępu do SAP. */
class LiveWriteConsentTest {
    private final UUID id = UUID.randomUUID();
    private Map<String, String> env() {
        return new HashMap<>(Map.of("AINV_H01_LIVE_WRITE_CONSENT", "APPLY_KSEF_RESULT:" + id,
            "AINV_H01_LIVE_WRITE_INVOICE_NUMBER", "AINV-CAP04D-ONLY",
            "AINV_H01_LIVE_WRITE_TAXPAYER_NIP", "1234567802", "AINV_H01_LIVE_WRITE_COMPANY_CODE", "1000"));
    }
    private Map<String, Object> header() {
        return new HashMap<>(Map.of("InvoiceUUID", id.toString(), "InvoiceNumber", "AINV-CAP04D-ONLY",
            "SellerNip", "1234567802", "CompanyCode", "1000", "Direction", "OUTBOUND"));
    }
    @Test void exactConsentAndPristineSyntheticScopePass() {
        assertDoesNotThrow(() -> LiveWriteConsent.header(id, "", header(), env()));
    }
    @Test void crudDenialChecksIdentityAlsoAfterResultHasBeenRecorded() {
        var header = header(); header.put("KsefNumber", "already-recorded");
        assertDoesNotThrow(() -> LiveWriteConsent.identity(id, "", header, env()));
        assertThrows(IllegalStateException.class, () -> LiveWriteConsent.header(id, "", header, env()));
        header.put("CompanyCode", "2000");
        assertThrows(IllegalStateException.class, () -> LiveWriteConsent.identity(id, "", header, env()));
    }
    @Test void missingOrDifferentConsentAndEverySeedAreRejected() {
        assertThrows(IllegalStateException.class, () -> LiveWriteConsent.consent(id, Map.of()));
        assertThrows(IllegalStateException.class, () -> LiveWriteConsent.consent(UUID.randomUUID(), env()));
        for (String suffix : new String[]{"14c8", "34c8", "54c8", "74c8"}) {
            UUID seed = UUID.fromString("56b09c12-4ee5-1fd1-b4ac-56570f4d" + suffix);
            var env = env(); env.put("AINV_H01_LIVE_WRITE_CONSENT", "APPLY_KSEF_RESULT:" + seed);
            assertThrows(IllegalStateException.class, () -> LiveWriteConsent.consent(seed, env));
        }
    }
    @Test void missingScopeWrongIdentityInboundOrExistingResultCannotWrite() {
        for (String key : env().keySet()) {
            var env = env(); env.remove(key);
            assertThrows(IllegalStateException.class, () -> LiveWriteConsent.header(id, "", header(), env), key);
        }
        for (var entry : Map.<String, Object>of("Direction", "INBOUND", "InvoiceUUID", UUID.randomUUID().toString(),
                "InvoiceNumber", "AINV-TEST-SEED-2026-FI-AR", "SellerNip", "9876543210",
                "CompanyCode", "2000", "KsefNumber", "existing").entrySet()) {
            var header = header(); header.put(entry.getKey(), entry.getValue());
            assertThrows(IllegalStateException.class, () -> LiveWriteConsent.header(id, "", header, env()), entry.getKey());
        }
        assertThrows(IllegalStateException.class, () -> LiveWriteConsent.header(id, "RACE_", header(), env()));
        assertThrows(IllegalStateException.class, () -> LiveWriteConsent.header(id, "STALE_", header(), env()));
    }
}
