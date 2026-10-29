package customer.cap_java.ksef.fa3;

/**
 * Pojedyncze naruszenie: stabilny kod (do logiki i testów), ścieżka pola oraz opis techniczny.
 * Opis może zawierać dane faktury (np. komunikat parsera XSD), ale nigdy sekrety.
 *
 * @param code np. REQUIRED, VAT_AMOUNT_MISMATCH, XSD_INVALID
 * @param path ścieżka w modelu wejściowym albo „linia:kolumna” dokumentu XML
 */
public record Fa3Violation(String code, String path, String detail) {

    @Override
    public String toString() {
        return code + " @ " + path + (detail == null ? "" : ": " + detail);
    }
}
