package customer.cap_java.ksef.inbound;

import java.util.regex.Pattern;

import pl.akmf.ksef.sdk.system.KSeFNumberValidator;

/**
 * Walidacja numeru KSeF przed użyciem go w ścieżce HTTP lub jako klucza tożsamości.
 *
 * <p>Dlaczego dwa sprawdzenia: SDK 3.0.29 wstawia numer do ścieżki {@code /invoices/ksef/{ksefNumber}}
 * przez {@code String.replace} bez kodowania URL, a {@link KSeFNumberValidator} sprawdza tylko długość
 * i CRC-8 (nie zestaw znaków). Dlatego najpierw ścisły wzorzec (35-znakowa postać KSeF 2.0 z oficjalnego
 * OpenAPI, wielkie litery HEX), potem suma kontrolna oficjalnym walidatorem SDK. Odrzucenie chroni przed
 * wstrzyknięciem ścieżki/zapytania ({@code /}, {@code ?}, {@code ..}) i błędnymi kluczami deduplikacji.
 * 36-znakowa postać zgodności z KSeF 1.0 nie jest akceptowana.
 */
public final class KsefNumbers {
    private static final Pattern FORMAT = Pattern.compile(
            "[1-9]((\\d[1-9])|([1-9]\\d))\\d{7}"
                    + "-(20[2-9]\\d|2[1-9]\\d{2}|[3-9]\\d{3})(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])"
                    + "-[0-9A-F]{12}-[0-9A-F]{2}");

    private KsefNumbers() {
    }

    public static boolean isValid(String ksefNumber) {
        return ksefNumber != null && FORMAT.matcher(ksefNumber).matches()
                && KSeFNumberValidator.isValid(ksefNumber).isValid();
    }

    public static String requireValid(String ksefNumber) {
        if (!isValid(ksefNumber)) {
            throw new IllegalArgumentException("KSEF_NUMBER_INVALID");
        }
        return ksefNumber;
    }
}
