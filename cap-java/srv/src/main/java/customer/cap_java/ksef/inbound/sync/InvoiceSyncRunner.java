package customer.cap_java.ksef.inbound.sync;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;

import customer.cap_java.ksef.inbound.InboundAccessToken;
import customer.cap_java.ksef.inbound.InvoiceRetrievalService;
import customer.cap_java.ksef.inbound.KsefInvoiceMetadata;
import customer.cap_java.ksef.inbound.KsefSubjectRole;
import customer.cap_java.ksef.inbound.KsefTaxpayerContext;
import customer.cap_java.ksef.inbound.MetadataSegment;

/**
 * Jeden przebieg synchronizacji przyrostowej dla zakresu (kontekst, rola):
 * checkpoint → okno → segment metadanych → przetworzenie każdego dokumentu przez {@link InboundInvoiceSink}
 * → decyzja planera → compare-and-set checkpointu.
 *
 * <p>Zasada nadrzędna: checkpoint jest zatwierdzany dopiero, gdy wszystkie dokumenty okna zostały bezpiecznie
 * przetworzone i utrwalone (sink zakończył się bez wyjątku). Każdy błąd odczytu KSeF lub sinku propaguje się,
 * a checkpoint pozostaje bez zmian — kolejny przebieg powtarza to samo okno (idempotentnie, dzięki
 * deduplikacji). Konflikt wersji (inna instancja zatwierdziła wcześniej) nie nadpisuje jej wyniku.
 *
 * <p>Klasa nie jest jeszcze beanem Spring ani zadaniem harmonogramu — podłączenie w KSEF-IN-02
 * (trwały checkpoint, lease/lock per zakres, harmonogram ≥ 15 min per typ podmiotu, Retry-After).
 */
public final class InvoiceSyncRunner {
    private final InvoiceRetrievalService retrieval;
    private final InvoiceSyncWindowPlanner planner;
    private final SyncCheckpointRepository checkpoints;
    private final Clock clock;

    public InvoiceSyncRunner(InvoiceRetrievalService retrieval, InvoiceSyncWindowPlanner planner,
            SyncCheckpointRepository checkpoints, Clock clock) {
        this.retrieval = Objects.requireNonNull(retrieval);
        this.planner = Objects.requireNonNull(planner);
        this.checkpoints = Objects.requireNonNull(checkpoints);
        this.clock = Objects.requireNonNull(clock);
    }

    public enum Status {
        /** Checkpoint przesunięty. */
        COMMITTED,
        /** Okno przetworzone, ale planer wstrzymał przesunięcie (np. brak HWM, brak postępu). */
        HELD,
        /** Inny przebieg zmienił checkpoint — wynik tego przebiegu nie został zatwierdzony. */
        CONCURRENT_UPDATE
    }

    /**
     * @param processed liczba dokumentów przekazanych do sinku
     * @param advance   decyzja planera
     */
    public record Result(Status status, InvoiceSyncWindowPlanner.SyncWindow window, int processed,
            InvoiceSyncWindowPlanner.Advance advance) {
    }

    public Result runOnce(KsefTaxpayerContext context, KsefSubjectRole role, InboundAccessToken token,
            OffsetDateTime initialFrom, InboundInvoiceSink sink) {
        Objects.requireNonNull(sink, "sink");
        SyncScope scope = SyncScope.of(context, role);
        var window = planner.plan(scope, checkpoints.find(scope), initialFrom);
        MetadataSegment segment = retrieval.fetchSegment(context, token, window.query());
        int processed = 0;
        for (KsefInvoiceMetadata metadata : segment.invoices()) {
            sink.accept(metadata);
            processed++;
        }
        var advance = planner.advance(window, segment, true);
        if (advance.kind() != InvoiceSyncWindowPlanner.AdvanceKind.ADVANCE) {
            return new Result(Status.HELD, window, processed, advance);
        }
        boolean committed = checkpoints.commit(scope, window.expectedVersion(), advance.next(), clock.instant());
        return new Result(committed ? Status.COMMITTED : Status.CONCURRENT_UPDATE, window, processed, advance);
    }
}
