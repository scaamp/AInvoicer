package customer.cap_java.ksef.inbound.sync;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class InboundSyncSchedulerTest {
    @Test void delegatesDueAndReportsOnlyFixedOutcomes() {
        var synchronization = mock(InboundSynchronization.class);
        var scheduler = new InboundSyncScheduler(synchronization);
        when(synchronization.runOnce()).thenReturn(Optional.empty());
        scheduler.applicationReady();
        scheduler.tick();
        verify(synchronization).runOnce();
    }

    @Test void overlappingTicksDoNotStartAnotherRun() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var outcomes = new java.util.concurrent.ConcurrentLinkedQueue<InboundSyncScheduler.Outcome>();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var scheduler = new InboundSyncScheduler(() -> {
            calls.incrementAndGet();
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { throw new AssertionError(error); }
            return Optional.empty();
        }, outcomes::add);
        scheduler.applicationReady();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(scheduler::tick);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                executor.submit(scheduler::tick).get(5, TimeUnit.SECONDS);
                assertEquals(1, calls.get());
                assertTrue(outcomes.contains(InboundSyncScheduler.Outcome.ALREADY_RUNNING));
            } finally { release.countDown(); }
            first.get(5, TimeUnit.SECONDS);
        }
        assertTrue(outcomes.contains(InboundSyncScheduler.Outcome.NOT_DUE));
    }

    @Test void unsafeExceptionIsNotReportedAndGuardIsReleasedForNextTick() {
        var outcomes = new ArrayList<InboundSyncScheduler.Outcome>();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var scheduler = new InboundSyncScheduler(() -> {
            if (calls.incrementAndGet() == 1) { throw new IllegalStateException("synthetic-private-XML-JWT"); }
            return Optional.of(mock(InvoiceSyncRunner.Result.class));
        }, outcomes::add);
        scheduler.applicationReady();
        scheduler.tick();
        scheduler.tick();
        assertEquals(java.util.List.of(InboundSyncScheduler.Outcome.FAILED, InboundSyncScheduler.Outcome.COMPLETED), outcomes);
    }

    @Test void startupReadyEventAndClosedContextNeverTriggerSynchronization() {
        var synchronization = mock(InboundSynchronization.class);
        when(synchronization.runOnce()).thenReturn(Optional.empty());
        var scheduler = new InboundSyncScheduler(synchronization);
        scheduler.tick();
        scheduler.applicationReady();
        verifyNoInteractions(synchronization);
        scheduler.tick();
        verify(synchronization).runOnce();
        scheduler.contextClosed();
        scheduler.tick();
        verifyNoMoreInteractions(synchronization);
    }
}
