package customer.cap_java.ksef.inbound.budget;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import customer.cap_java.ksef.inbound.KsefEnvironment;
import customer.cap_java.ksef.inbound.budget.InboundApiBudget.Limits;
import customer.cap_java.ksef.inbound.budget.InboundApiBudget.Request;

/**
 * Port atomowej zmiany całego budżetu środowiska. Przyszły adapter HANA musi serializować odczyt,
 * decyzję i zapis (także pierwszy INSERT), potwierdzić commit przed zwróceniem wyniku i używać
 * autorytatywnego czasu DB. Błąd/niejednoznaczny commit musi zgłosić wyjątek, nigdy ALLOW.
 * Funkcja nie wykonuje HTTP, oczekiwania ani efektów zewnętrznych; CAS może ją obliczyć ponownie.
 */
public interface ApiBudgetRepository {
    <T> T transact(KsefEnvironment environment, Function<State, Change<T>> transition);

    /** Stan prywatnego budżetu, bez JWT/credentiali/XML; null limits oznacza wyłącznie nowy budżet. */
    record State(Limits limits, Instant lastObservedAt, Instant blockedUntil, List<Attempt> attempts) {
        public State {
            attempts = List.copyOf(attempts);
        }

        public static State empty() {
            return new State(null, null, null, List.of());
        }
    }

    /**
     * Próba obciąża wszystkie okna przez cały czas in-flight. Complete ustawia chargedAt na czas
     * zakończenia, więc opóźniony dispatch/długi HTTP nie odzyskuje budżetu przed zakończeniem.
     */
    record Attempt(UUID id, Request request, Instant chargedAt, boolean inFlight) { }

    record Change<T>(State state, T result) { }
}
