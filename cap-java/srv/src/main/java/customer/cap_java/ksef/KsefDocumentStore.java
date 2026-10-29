package customer.cap_java.ksef;

/** Magazyn dokładnych bajtów XML/UPO. Implementacja produkcyjna wymaga osobnego adaptera. */
public interface KsefDocumentStore {
    String put(byte[] bytes);
    byte[] get(String reference);
}
