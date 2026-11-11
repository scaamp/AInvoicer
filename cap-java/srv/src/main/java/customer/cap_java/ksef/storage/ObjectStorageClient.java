package customer.cap_java.ksef.storage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minimalny SPI protokołu object storage. Implementacja nie zna KSeF, scope ani SHA-256 —
 * te reguły egzekwuje {@link ContentAddressedDocumentStore}. Brak operacji delete i overwrite:
 * aplikacja nie potrafi usunąć ani nadpisać dokumentu finansowego.
 *
 * <p>Wszystkie błędy to {@link ObjectStorageException} z kategorią, bez URL, SAS i treści.
 */
public interface ObjectStorageClient {

    enum CreateOutcome { CREATED, ALREADY_EXISTS }

    /** Zapis warunkowy "create-only"; istniejący obiekt nigdy nie jest nadpisywany. */
    CreateOutcome createIfAbsent(String key, byte[] content, Map<String, String> metadata);

    /** Pełny odczyt; pusty wynik oznacza potwierdzone 404. Odpowiedź ponad {@code maxBytes} = błąd. */
    Optional<StoredObject> read(String key, int maxBytes);

    /** Strona listingu partycji; używana wyłącznie przez audyt orphan (tryb raportu). */
    ListPage list(String prefix, String marker, int maxResults);

    record StoredObject(byte[] content, Map<String, String> metadata) {
        public StoredObject {
            content = content.clone();
            metadata = Map.copyOf(metadata);
        }
        @Override public byte[] content() { return content.clone(); }
        @Override public String toString() { return "StoredObject[" + content.length + " bytes]"; }
    }

    record ListedObject(String key, long size, Instant lastModified) { }

    record ListPage(List<ListedObject> objects, String nextMarker) {
        public ListPage { objects = List.copyOf(objects); }
    }
}
