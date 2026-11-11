package customer.cap_java.ksef.inbound.budget;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import customer.cap_java.ksef.inbound.KsefEnvironment;
import customer.cap_java.ksef.inbound.KsefTaxpayerContext;
import customer.cap_java.ksef.inbound.budget.ApiBudgetRepository.Attempt;
import customer.cap_java.ksef.inbound.budget.ApiBudgetRepository.Change;
import customer.cap_java.ksef.inbound.budget.ApiBudgetRepository.State;

/**
 * Niepodłączony komponent OFFLINE: dokładne okna przesuwne + limit trwających wywołań.
 * ALLOW atomowo rezerwuje jedną próbę; DEFER/REJECT nie przyznają prawa do HTTP.
 * Brak domyślnych limitów MF, schedulera, sleep, HTTP i credentiali. Clock jest próbkowany
 * dopiero wewnątrz transakcji repozytorium, aby kolejność wątków nie cofała czasu budżetu.
 */
public final class InboundApiBudget {
    /** Tylko aktualnie zaimplementowane endpointy INBOUND; auth/eksport wymagają osobnej integracji. */
    public enum Operation { QUERY_METADATA, DOWNLOAD_INVOICE }
    public enum Outcome { ALLOW, DEFER, REJECT }
    public enum Reason {
        RESERVED, WINDOW_EXHAUSTED, CONCURRENCY_LIMIT, RETRY_AFTER, CLOCK_REGRESSION,
        STATE_UNAVAILABLE, INVALID_CONFIGURATION, INVALID_REQUEST, POLICY_MISMATCH
    }

    /** Brak roli: BUYER/SELLER tego samego kontekstu współdzielą limit endpointu MF. */
    public record Request(KsefTaxpayerContext context, Operation operation, String egressGroup) {
        @Override public String toString() { return "ApiBudgetRequest[REDACTED]"; }
    }

    /** Permit dotyczy jednej próby i nie jest credentialem KSeF ani zgodą na późniejsze wysłanie HTTP. */
    public record Permit(KsefEnvironment environment, UUID id) { }

    /** retryNotBefore jest tylko wskazówką ponownej oceny; null = termin nieznany, np. awaria DB. */
    public record Decision(Outcome outcome, Reason reason, Instant retryNotBefore, Permit permit) { }
    public record Window(Duration duration, int maxCalls) { }

    /**
     * aggregateWindows/maxConcurrent = założenia aplikacji per środowisko, łącznie dla wszystkich NIP
     * i operacji. endpointWindows = limity per (kontekst NIP, grupa wyjścia/IP, operacja).
     * Grupa pochodzi z konfiguracji backendu; przy nieznanym NAT wszystkie wyjścia mają jedną grupę.
     */
    public record Limits(List<Window> aggregateWindows, Map<Operation, List<Window>> endpointWindows,
            int maxConcurrent) {
        public Limits {
            aggregateWindows = freeze(aggregateWindows);
            var copy = new HashMap<Operation, List<Window>>();
            if (endpointWindows != null) {
                endpointWindows.forEach((key, value) -> copy.put(key, freeze(value)));
            }
            endpointWindows = Collections.unmodifiableMap(copy);
        }

        private boolean valid() {
            return maxConcurrent > 0 && validWindows(aggregateWindows) && !endpointWindows.isEmpty()
                    && endpointWindows.entrySet().stream().allMatch(e -> e.getKey() != null && validWindows(e.getValue()));
        }

        private Duration retention() {
            return java.util.stream.Stream.concat(aggregateWindows.stream(),
                    endpointWindows.values().stream().flatMap(List::stream))
                    .map(Window::duration).max(Duration::compareTo).orElseThrow();
        }
    }

    private final Map<KsefEnvironment, Limits> configuration;
    private final ApiBudgetRepository repository;
    private final Clock clock;

    public InboundApiBudget(Map<KsefEnvironment, Limits> configuration, ApiBudgetRepository repository, Clock clock) {
        this.configuration = Collections.unmodifiableMap(configuration == null ? Map.of() : new HashMap<>(configuration));
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Sprawdza wszystkie okna i rezerwuje próbę w jednej transakcji. Każdy retry wymaga nowego acquire.
     * Wyjątek repozytorium/czasu oznacza DEFER bez lokalnego fallbacku, także po niepewnym commit.
     */
    public Decision acquire(Request request) {
        if (configuration.isEmpty() || configuration.entrySet().stream()
                .anyMatch(e -> e.getKey() == null || e.getValue() == null || !e.getValue().valid())) {
            return reject(Reason.INVALID_CONFIGURATION);
        }
        if (request == null || request.context() == null || request.operation() == null
                || request.egressGroup() == null || !request.egressGroup().matches("[A-Za-z0-9_-]{1,64}")) {
            return reject(Reason.INVALID_REQUEST);
        }
        Limits limits = configuration.get(request.context().environment());
        if (limits == null || !limits.endpointWindows().containsKey(request.operation())) {
            return reject(Reason.INVALID_CONFIGURATION);
        }
        try {
            return repository.transact(request.context().environment(), state -> reserve(state, request, limits));
        } catch (RuntimeException unavailable) {
            // Nie przenosimy wiadomości/cause adaptera (może zawierać dane połączenia).
            return defer(Reason.STATE_UNAVAILABLE, null);
        }
    }

    private Change<Decision> reserve(State state, Request request, Limits limits) {
        Instant now = clock.instant();
        if (state.limits() != null && !state.limits().equals(limits)) {
            return new Change<>(state, reject(Reason.POLICY_MISMATCH));
        }
        if (state.lastObservedAt() != null && now.isBefore(state.lastObservedAt())) {
            return new Change<>(state, defer(Reason.CLOCK_REGRESSION, state.lastObservedAt()));
        }
        Duration retention = limits.retention();
        now.plus(retention); // Przepełnienie osi czasu musi zamknąć bramkę przed rezerwacją.
        // Trwających prób nie usuwamy nawet po najdłuższym oknie: crash nie zwalnia slotu w ciemno.
        var attempts = state.attempts().stream()
                .filter(a -> a.inFlight() || a.chargedAt().plus(retention).isAfter(now)).toList();
        State current = new State(limits, now, state.blockedUntil(), attempts);
        WindowAvailability aggregate = windowDue(attempts, a -> true, limits.aggregateWindows(), now);
        WindowAvailability endpoint = windowDue(attempts, a -> sameBucket(a.request(), request),
                limits.endpointWindows().get(request.operation()), now);
        Instant due = later(aggregate.due(), endpoint.due());
        Reason reason = Reason.WINDOW_EXHAUSTED;
        if (current.blockedUntil() != null && current.blockedUntil().isAfter(now)) {
            due = later(due, current.blockedUntil());
            reason = Reason.RETRY_AFTER;
        }
        if (attempts.stream().filter(Attempt::inFlight).count() >= limits.maxConcurrent()) {
            // Czas zwolnienia slotu jest nieznany; bez arbitralnego TTL i bez automatycznego wygaśnięcia.
            return new Change<>(current, defer(Reason.CONCURRENCY_LIMIT, null));
        }
        if (aggregate.pending() || endpoint.pending()) {
            return new Change<>(current, defer(reason, null));
        }
        if (due != null) {
            return new Change<>(current, defer(reason, due));
        }
        UUID id = UUID.randomUUID();
        var updated = new ArrayList<>(attempts);
        updated.add(new Attempt(id, request, now, true));
        return new Change<>(new State(limits, now, current.blockedUntil(), updated),
                new Decision(Outcome.ALLOW, Reason.RESERVED, null, new Permit(request.context().environment(), id)));
    }

    /**
     * Zwalnia dokładnie własny slot; opłata w oknach trwa jeszcze od momentu complete. Retry-After (już sparsowany przez
     * adapter HTTP) rozszerza konserwatywną blokadę CAŁEGO środowiska, również innych NIP/operacji.
     * null oznacza brak wskazania; 429 bez poprawnego nagłówka wymaga fallbacku adaptera.
     * false = brak potwierdzenia (obcy/powtórzony permit, zegar/DB); integracja musi wstrzymać globalny dispatch.
     */
    public boolean complete(Permit permit, Instant retryNotBefore) {
        if (permit == null || permit.environment() == null || permit.id() == null) { return false; }
        try {
            return repository.transact(permit.environment(), state -> {
                Instant now = clock.instant();
                if (state.lastObservedAt() != null && now.isBefore(state.lastObservedAt())) {
                    return new Change<>(state, false);
                }
                var match = state.attempts().stream().filter(a -> a.id().equals(permit.id()) && a.inFlight()).findFirst();
                if (match.isEmpty()) { return new Change<>(state, false); }
                var attempts = state.attempts().stream().map(a -> a.id().equals(permit.id())
                        ? new Attempt(a.id(), a.request(), now, false) : a).toList();
                return new Change<>(new State(state.limits(), now, later(state.blockedUntil(), retryNotBefore), attempts), true);
            });
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Zakończone próby: okno (now-duration, now], bez resetu o pełnej minucie/godzinie.
     * In-flight zawsze obciąża okno; termin jego zwolnienia jest nieznany, więc due = null.
     */
    private record WindowAvailability(Instant due, boolean pending) { }

    private static WindowAvailability windowDue(List<Attempt> attempts, Predicate<Attempt> bucket, List<Window> windows, Instant now) {
        Instant due = null;
        for (Window window : windows) {
            long pending = attempts.stream().filter(bucket).filter(Attempt::inFlight).count();
            var active = attempts.stream().filter(bucket).filter(a -> !a.inFlight())
                    .filter(a -> a.chargedAt().plus(window.duration()).isAfter(now))
                    .map(Attempt::chargedAt).sorted().toList();
            if (pending >= window.maxCalls()) {
                return new WindowAvailability(null, true);
            }
            if (active.size() + pending >= window.maxCalls()) {
                due = later(due, active.get((int) (active.size() + pending - window.maxCalls())).plus(window.duration()));
            }
        }
        return new WindowAvailability(due, false);
    }

    private static boolean sameBucket(Request a, Request b) {
        return a.context().equals(b.context()) && a.operation() == b.operation() && a.egressGroup().equals(b.egressGroup());
    }

    private static boolean validWindows(List<Window> windows) {
        var durations = new HashSet<Duration>();
        return !windows.isEmpty() && windows.stream().allMatch(w -> w != null && w.duration() != null
                && !w.duration().isNegative() && !w.duration().isZero() && w.maxCalls() > 0 && durations.add(w.duration()));
    }

    private static List<Window> freeze(List<Window> windows) {
        return Collections.unmodifiableList(windows == null ? List.of() : new ArrayList<>(windows));
    }

    private static Instant later(Instant a, Instant b) {
        return a == null ? b : b == null || a.isAfter(b) ? a : b;
    }

    private static Decision reject(Reason reason) { return new Decision(Outcome.REJECT, reason, null, null); }
    private static Decision defer(Reason reason, Instant due) { return new Decision(Outcome.DEFER, reason, due, null); }
}
