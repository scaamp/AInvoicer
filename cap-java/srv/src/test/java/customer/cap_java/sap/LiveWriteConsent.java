package customer.cap_java.sap;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bramki harnessu LIVE: zgoda i syntetyczna tożsamość przed jakimkolwiek HTTP WRITE. */
final class LiveWriteConsent {
    private static final String PREFIX = "AINV_H01_LIVE_WRITE_";
    private static final Set<UUID> PROTECTED_SEED = Set.of(
        UUID.fromString("56b09c12-4ee5-1fd1-b4ac-56570f4d14c8"),
        UUID.fromString("56b09c12-4ee5-1fd1-b4ac-56570f4d34c8"),
        UUID.fromString("56b09c12-4ee5-1fd1-b4ac-56570f4d54c8"),
        UUID.fromString("56b09c12-4ee5-1fd1-b4ac-56570f4d74c8"));

    static void consent(UUID invoice, Map<String, String> env) {
        String raw = env.get(PREFIX + "CONSENT");
        if (PROTECTED_SEED.contains(invoice) || raw == null || !Arrays.stream(raw.split(","))
                .anyMatch(s -> s.trim().equalsIgnoreCase("APPLY_KSEF_RESULT:" + invoice))) {
            throw new IllegalStateException("LIVE WRITE requires explicit consent for a new CAP-04D fixture; seed is protected");
        }
    }

    static void header(UUID invoice, String variant, Map<String, Object> header, Map<String, String> env) {
        identity(invoice, variant, header, env);
        if (header.get("KsefNumber") != null && !header.get("KsefNumber").toString().isBlank()) {
            throw new IllegalStateException("LIVE WRITE fixture must be pristine");
        }
    }

    static void identity(UUID invoice, String variant, Map<String, Object> header, Map<String, String> env) {
        consent(invoice, env);
        String expectedNumber = env.get(PREFIX + variant + "INVOICE_NUMBER");
        String nip = env.get(PREFIX + "TAXPAYER_NIP");
        String company = env.get(PREFIX + "COMPANY_CODE");
        if (expectedNumber == null || !expectedNumber.startsWith("AINV-CAP04D-")
                || !expectedNumber.equals(header.get("InvoiceNumber"))
                || !invoice.toString().equalsIgnoreCase(String.valueOf(header.get("InvoiceUUID")))
                || !"OUTBOUND".equals(header.get("Direction"))
                || nip == null || !nip.matches("[0-9]{10}") || !nip.equals(header.get("SellerNip"))
                || company == null || company.isBlank() || !company.equals(header.get("CompanyCode"))) {
            throw new IllegalStateException("LIVE WRITE fixture must match approved UUID/number/NIP/company and be pristine OUTBOUND");
        }
    }
}
