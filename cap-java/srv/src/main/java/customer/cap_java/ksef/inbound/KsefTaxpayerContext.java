package customer.cap_java.ksef.inbound;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Zaufany kontekst podatnika (środowisko + NIP), w którym backend czyta faktury z KSeF.
 *
 * <p><b>Granica zaufania:</b> obiekt tworzy wyłącznie kod backendu na podstawie konfiguracji związanej
 * z credentialem KSeF (tak jak {@code KsefAuthentication} wiąże token z {@code ainv.ksef.nip}).
 * Nigdy nie budować go z NIP przekazanego przez UI5/OData — sam NIP w payload nie daje prawa
 * działania w imieniu podatnika, a rola XSUAA {@code KsefOperator} nie oznacza uprawnienia
 * {@code InvoiceRead} w KSeF.
 */
public record KsefTaxpayerContext(KsefEnvironment environment, String nip) {
    /** Wzorzec {@code Nip} z oficjalnego OpenAPI KSeF 2.0. */
    static final Pattern NIP_PATTERN = Pattern.compile("[1-9]((\\d[1-9])|([1-9]\\d))\\d{7}");
    private static final int[] NIP_WEIGHTS = {6, 5, 7, 2, 3, 4, 5, 6, 7};

    public KsefTaxpayerContext {
        Objects.requireNonNull(environment, "environment");
        if (!isValidNip(nip)) {
            throw new IllegalArgumentException("KSEF_INBOUND_CONTEXT_NIP_INVALID");
        }
    }

    /** Wzorzec OpenAPI + suma kontrolna NIP (wagi 6,5,7,2,3,4,5,6,7; modulo 11). */
    public static boolean isValidNip(String nip) {
        if (nip == null || !NIP_PATTERN.matcher(nip).matches()) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < NIP_WEIGHTS.length; i++) {
            sum += (nip.charAt(i) - '0') * NIP_WEIGHTS[i];
        }
        int check = sum % 11;
        return check != 10 && check == nip.charAt(9) - '0';
    }
}
