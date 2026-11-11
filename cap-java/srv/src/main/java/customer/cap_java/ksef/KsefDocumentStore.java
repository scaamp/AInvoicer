package customer.cap_java.ksef;

/**
 * Magazyn dokładnych bajtów XML/UPO.
 *
 * <p>Kontrakt: {@code put} tych samych bajtów zwraca tę samą referencję (≤ 500 znaków, nieprzezroczysta,
 * wyłącznie do zapisu w CAP — nigdy do OData/UI); {@code get} zwraca identyczne bajty albo rzuca wyjątek.
 * Błędy magazynu są {@link KsefFailure} bez danych wrażliwych; {@code retryable()} oznacza wyłącznie
 * chwilową niedostępność. Kontekst autoryzacji (środowisko, kanał, NIP) jest związany z instancją —
 * zob. {@code customer.cap_java.ksef.storage.ContentAddressedDocumentStore#forScope}.
 * {@link LocalTestDocumentStore} służy tylko lokalnemu TEST.
 */
public interface KsefDocumentStore {
    String put(byte[] bytes);
    byte[] get(String reference);
}
