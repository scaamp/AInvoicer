package customer.cap_java.ksef.inbound.sync;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/** Lokalny, jawnie opcjonalny zegar; trwałe due/lease/HWM nadal należą do runOnce, CF jest zablokowane. */
public final class InboundSyncScheduler {
    public enum Outcome { NOT_READY, COMPLETED, NOT_DUE, ALREADY_RUNNING, FAILED }
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean ready = new AtomicBoolean();
    private final Supplier<Optional<InvoiceSyncRunner.Result>> run;
    private final Consumer<Outcome> report;

    public InboundSyncScheduler(InboundSynchronization synchronization) {
        this(synchronization::runOnce, outcome -> LoggerFactory.getLogger(InboundSyncScheduler.class)
                .info("KSEF_INBOUND_TICK outcome={}", outcome));
    }

    InboundSyncScheduler(Supplier<Optional<InvoiceSyncRunner.Result>> run, Consumer<Outcome> report) {
        this.run = run;
        this.report = report;
    }

    /** Zdarzenie gotowości tylko uzbraja zegar; samo nigdy nie wywołuje synchronizacji. */
    @EventListener(ApplicationReadyEvent.class)
    public void applicationReady() { ready.set(true); }

    @EventListener(ContextClosedEvent.class)
    public void contextClosed() { ready.set(false); }

    /** Pierwszy tick dopiero po 15 min; brak nadrabiania ticków i brak równoległego run w jednej JVM. */
    @Scheduled(initialDelayString = "PT15M", fixedDelayString = "PT15M")
    public void tick() {
        if (!ready.get()) { report.accept(Outcome.NOT_READY); return; }
        if (!running.compareAndSet(false, true)) { report.accept(Outcome.ALREADY_RUNNING); return; }
        Outcome outcome;
        try {
            outcome = run.get().isPresent() ? Outcome.COMPLETED : Outcome.NOT_DUE;
        } catch (RuntimeException error) {
            // Wyjątek providera może zawierać XML/JWT/URL: raportujemy wyłącznie stały kod, bez cause.
            outcome = Outcome.FAILED;
        } finally {
            running.set(false);
        }
        report.accept(outcome);
    }
}
