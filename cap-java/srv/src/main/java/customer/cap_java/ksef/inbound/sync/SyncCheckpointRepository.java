package customer.cap_java.ksef.inbound.sync;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Kontrakt trwałego repozytorium checkpointów (implementacja CAP/HANA w KSEF-IN-02).
 *
 * <p>Wymagania dla implementacji: klucz unikalny {@code (environment, contextNip, role)};
 * {@link #commit} to atomowe compare-and-set po wersji (np. {@code UPDATE … WHERE version = ?} albo INSERT
 * z ograniczeniem unikalności dla pierwszego zapisu), wykonywane w osobnej, krótkiej transakcji
 * <b>po</b> trwałym zapisaniu dokumentów okna. Implementacja nie może cofać checkpointu ani nadpisywać
 * zmiany innej instancji CF. Bez trwałej bazy i testu współbieżności nie deklarujemy exactly-once.
 */
public interface SyncCheckpointRepository {
    Optional<SyncCheckpoint> find(SyncScope scope);

    /**
     * Zapisuje nowy punkt kontynuacji, jeśli bieżąca wersja jest równa {@code expectedVersion}
     * ({@code 0} = checkpoint jeszcze nie istnieje).
     *
     * @return {@code true} po zapisie; {@code false}, gdy wersja się zmieniła (współbieżny przebieg) —
     *         wtedy wynik okna nie jest zatwierdzany, a kolejny przebieg zaczyna od zapisanego stanu
     */
    boolean commit(SyncScope scope, long expectedVersion, OffsetDateTime continuationFrom, Instant committedAt);
}
