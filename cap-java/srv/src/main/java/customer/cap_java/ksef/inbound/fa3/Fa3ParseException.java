package customer.cap_java.ksef.inbound.fa3;

import java.util.Objects;

/**
 * Odrzucenie dokumentu przez {@link SecureFa3InvoiceParser}. Komunikat zawiera wyłącznie kod i ścieżkę
 * elementu (np. {@code MISSING_REQUIRED:Faktura/Fa/P_2}) — nigdy wartości z dokumentu ani fragmentów XML.
 * Przyczyna parsera nie jest dołączana, bo komunikaty StAX mogą cytować treść wejścia.
 */
public final class Fa3ParseException extends RuntimeException {
    public enum Code {
        /** Puste wejście. */
        INPUT_EMPTY,
        /** Wejście większe niż limit bajtów. */
        INPUT_TOO_LARGE,
        /** Dokument zawiera DOCTYPE/DTD — zabronione (ochrona XXE i entity expansion). */
        DOCTYPE_FORBIDDEN,
        /** Odwołanie do encji innej niż predefiniowane XML. */
        ENTITY_FORBIDDEN,
        /** XML nie jest poprawnie sformułowany. */
        MALFORMED_XML,
        /** Kodowanie inne niż UTF-8 (KSeF wymaga UTF-8). */
        UNSUPPORTED_ENCODING,
        /** Element główny nie jest fakturą ustrukturyzowaną (np. inny dokument). */
        UNSUPPORTED_DOCUMENT,
        /** Faktura w innym schemacie/wersji niż FA(3) 1-0E (np. FA(2), PEF, FA_RR). */
        UNSUPPORTED_SCHEMA,
        /** Element w obcym namespace wewnątrz dokumentu FA(3). */
        UNEXPECTED_NAMESPACE,
        /** Przekroczono limit głębokości, liczby elementów, długości tekstu lub liczby wierszy. */
        LIMIT_EXCEEDED,
        /** Brak elementu wymaganego przez XSD FA(3). */
        MISSING_REQUIRED,
        /** Element, który według XSD występuje raz, wystąpił wielokrotnie. */
        DUPLICATE_ELEMENT,
        /** Wartość niezgodna z typem XSD (kwota, data, NIP, waluta, słownik). */
        INVALID_VALUE
    }

    private final Code code;
    private final String path;

    Fa3ParseException(Code code, String path) {
        super(code + (path == null ? "" : ":" + path), null, false, true);
        this.code = Objects.requireNonNull(code);
        this.path = path;
    }

    public Code code() {
        return code;
    }

    /** Ścieżka elementu, której dotyczy błąd; może być {@code null}. */
    public String path() {
        return path;
    }
}
