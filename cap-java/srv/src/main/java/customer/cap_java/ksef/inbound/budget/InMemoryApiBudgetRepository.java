package customer.cap_java.ksef.inbound.budget;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import customer.cap_java.ksef.inbound.KsefEnvironment;

/**
 * WYŁĄCZNIE OFFLINE / jedna JVM: wszystkie instancje polityki muszą dostać ten sam obiekt repozytorium.
 * Monitor obejmuje decyzję i zapis; nie zapewnia trwałości ani koordynacji procesów/Cloud Foundry.
 * Brak beana Spring i automatycznej rejestracji. Restart/nowy obiekt zeruje historię.
 */
public final class InMemoryApiBudgetRepository implements ApiBudgetRepository {
    private final Map<KsefEnvironment, State> states = new EnumMap<>(KsefEnvironment.class);

    @Override
    public synchronized <T> T transact(KsefEnvironment environment, Function<State, Change<T>> transition) {
        Objects.requireNonNull(environment);
        Change<T> change = Objects.requireNonNull(transition.apply(states.getOrDefault(environment, State.empty())));
        states.put(environment, Objects.requireNonNull(change.state()));
        return change.result();
    }
}
