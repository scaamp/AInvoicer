package customer.cap_java.ksef.storage;

import java.util.Objects;

import customer.cap_java.ksef.inbound.DurableInboundDocumentStore;
import customer.cap_java.ksef.inbound.KsefEnvironment;

/**
 * KSEF-IN-05: jawny uchwyt magazynu dokumentów INBOUND (scope {@code INBOUND/TEST}) zarejestrowany przez
 * {@link KsefInboundHanaStorageConfiguration}.
 *
 * <p>Celowo <b>nie</b> implementuje {@code KsefDocumentStore}: Spring nie może wstrzyknąć go w miejsce
 * {@code LocalTestDocumentStore} (np. do workera OUTBOUND), więc nie powstaje druga kandydatura beana tego typu.
 * Korzystają z niego wyłącznie świadomi konsumenci INBOUND ({@code KsefInboundConfiguration},
 * handler pobrania XML). Referencje magazynu nigdy nie wychodzą do OData/UI.
 */
public final class InboundDocumentStorage {
    private final DurableInboundDocumentStore store;
    private final KsefEnvironment environment;

    public InboundDocumentStorage(DurableInboundDocumentStore store, KsefEnvironment environment) {
        this.store = Objects.requireNonNull(store, "store");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /** Widok magazynu związany z zaufanym scope {@code INBOUND/<environment>}; nie przyjmuje scope od klienta. */
    public DurableInboundDocumentStore store() { return store; }

    /** Środowisko KSeF, dla którego zbudowano scope (w tym wydaniu wyłącznie TEST). */
    public KsefEnvironment environment() { return environment; }

    @Override public String toString() { return "InboundDocumentStorage[" + environment + "/INBOUND]"; }
}
