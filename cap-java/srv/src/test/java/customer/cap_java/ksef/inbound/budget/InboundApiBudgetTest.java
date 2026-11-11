package customer.cap_java.ksef.inbound.budget;

import static customer.cap_java.ksef.inbound.budget.InboundApiBudget.Operation.*;
import static customer.cap_java.ksef.inbound.budget.InboundApiBudget.Outcome.*;
import static customer.cap_java.ksef.inbound.budget.InboundApiBudget.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import customer.cap_java.ksef.inbound.KsefEnvironment;
import customer.cap_java.ksef.inbound.KsefTaxpayerContext;
import customer.cap_java.ksef.inbound.budget.InboundApiBudget.*;

/** Wszystkie limity są syntetyczne; testy nie uruchamiają Spring, HTTP, synchronizacji ani bazy. */
class InboundApiBudgetTest {
    private static final Instant START = Instant.parse("2026-11-11T12:00:59.900Z");
    private static final String NIP_A = "1234563218";
    private static final String NIP_B = "2222222222";
    private final MutableClock clock = new MutableClock(START);
    private final InMemoryApiBudgetRepository repository = new InMemoryApiBudgetRepository();

    @Test
    void firstRequestAtomicallyReservesAndCompletionDoesNotRefundWindow() {
        var budget = budget(limits(1, 5, 5));
        var first = budget.acquire(request(NIP_A, QUERY_METADATA));
        assertEquals(ALLOW, first.outcome());
        assertEquals(RESERVED, first.reason());
        assertNotNull(first.permit());
        assertTrue(budget.complete(first.permit(), null));
        assertFalse(budget.complete(first.permit(), null));
        var next = budget.acquire(request(NIP_A, QUERY_METADATA));
        assertEquals(DEFER, next.outcome());
        assertEquals(WINDOW_EXHAUSTED, next.reason());
        assertEquals(START.plusSeconds(60), next.retryNotBefore());
        assertNull(next.permit());
    }

    @Test
    void slidingWindowDoesNotResetAtMinuteBoundaryAndExpiresAtExactNanosecond() {
        var budget = budget(limits(1, 5, 5));
        finish(budget, request(NIP_A, QUERY_METADATA));
        clock.set(START.plusMillis(100)); // Pełna minuta nie zeruje okna.
        assertEquals(DEFER, budget.acquire(request(NIP_A, QUERY_METADATA)).outcome());
        clock.set(START.plusSeconds(60).minusNanos(1));
        assertEquals(DEFER, budget.acquire(request(NIP_A, QUERY_METADATA)).outcome());
        clock.set(START.plusSeconds(60));
        assertEquals(ALLOW, budget.acquire(request(NIP_A, QUERY_METADATA)).outcome());
    }

    @Test
    void allSecondMinuteHourWindowsApplyAndLatestConstraintWins() {
        var windows = List.of(window(1, 1), window(60, 2), window(3600, 3));
        var budget = budget(new Limits(windows, endpoints(window(3600, 100)), 10));
        finish(budget, request(NIP_A, QUERY_METADATA));
        assertEquals(START.plusSeconds(1), budget.acquire(request(NIP_A, QUERY_METADATA)).retryNotBefore());
        clock.set(START.plusSeconds(1));
        finish(budget, request(NIP_A, QUERY_METADATA));
        assertEquals(START.plusSeconds(60), budget.acquire(request(NIP_A, QUERY_METADATA)).retryNotBefore());
        clock.set(START.plusSeconds(60));
        finish(budget, request(NIP_A, QUERY_METADATA));
        assertEquals(START.plusSeconds(3600), budget.acquire(request(NIP_A, QUERY_METADATA)).retryNotBefore());
        clock.set(START.plusSeconds(3600));
        assertEquals(ALLOW, budget.acquire(request(NIP_A, QUERY_METADATA)).outcome());
    }

    @Test
    void multipleNipsHaveSeparateEndpointBudgetButShareAggregateBudget() {
        var budget = budget(limits(2, 1, 10));
        finish(budget, request(NIP_A, QUERY_METADATA));
        assertEquals(DEFER, budget.acquire(request(NIP_A, QUERY_METADATA)).outcome());
        finish(budget, request(NIP_B, QUERY_METADATA));
        var blockedByAggregate = budget.acquire(request(NIP_B, DOWNLOAD_INVOICE));
        assertEquals(DEFER, blockedByAggregate.outcome());
        assertEquals(WINDOW_EXHAUSTED, blockedByAggregate.reason());
    }

    @Test
    void operationsHaveSeparateEndpointBudgetsAndShareAggregateAcrossOperations() {
        var budget = budget(limits(2, 1, 10));
        finish(budget, request(NIP_A, QUERY_METADATA));
        finish(budget, request(NIP_A, DOWNLOAD_INVOICE));
        assertEquals(DEFER, budget.acquire(request(NIP_B, QUERY_METADATA)).outcome());
    }

    @Test
    void egressGroupsSplitEndpointBudgetButCannotBypassApplicationBudget() {
        var budget = budget(limits(2, 1, 10));
        finish(budget, request(NIP_A, QUERY_METADATA));
        finish(budget, new Request(context(KsefEnvironment.TEST, NIP_A), QUERY_METADATA, "other-egress"));
        assertEquals(DEFER, budget.acquire(new Request(context(KsefEnvironment.TEST, NIP_A),
                QUERY_METADATA, "third-egress")).outcome());
    }

    @Test
    void environmentsHaveSeparateWindowsConcurrencyAndCooldown() {
        var config = new HashMap<KsefEnvironment, Limits>();
        for (var env : KsefEnvironment.values()) { config.put(env, limits(1, 1, 1)); }
        var budget = new InboundApiBudget(config, repository, clock);
        for (var env : KsefEnvironment.values()) {
            var allowed = budget.acquire(new Request(context(env, NIP_A), QUERY_METADATA, "egress"));
            assertEquals(ALLOW, allowed.outcome(), env.name());
            assertTrue(budget.complete(allowed.permit(), START.plusSeconds(600)));
            assertEquals(DEFER, budget.acquire(new Request(context(env, NIP_A), QUERY_METADATA, "egress")).outcome());
        }
    }

    @Test
    void concurrencySlotIsNotRecoveredByTimeAndOnlyOwnerCanRelease() {
        var budget = budget(limits(100, 100, 1));
        var held = budget.acquire(request(NIP_A, QUERY_METADATA));
        assertFalse(budget.complete(new Permit(KsefEnvironment.TEST, UUID.randomUUID()), null));
        assertFalse(budget.complete(new Permit(KsefEnvironment.DEMO, held.permit().id()), null));
        clock.set(START.plusSeconds(36000));
        var blocked = budget.acquire(request(NIP_B, DOWNLOAD_INVOICE));
        assertEquals(CONCURRENCY_LIMIT, blocked.reason());
        assertNull(blocked.retryNotBefore());
        assertTrue(budget.complete(held.permit(), null));
        assertEquals(ALLOW, budget.acquire(request(NIP_B, DOWNLOAD_INVOICE)).outcome());
    }

    @Test
    void unfinishedAttemptsKeepWindowChargedEvenWithFreeConcurrencySlots() {
        var budget = budget(limits(2, 10, 10));
        var a = budget.acquire(request(NIP_A, QUERY_METADATA));
        assertEquals(ALLOW, a.outcome());
        assertEquals(ALLOW, budget.acquire(request(NIP_B, DOWNLOAD_INVOICE)).outcome());
        clock.set(START.plusSeconds(600));
        var blocked = budget.acquire(request(NIP_A, DOWNLOAD_INVOICE));
        assertEquals(DEFER, blocked.outcome());
        assertEquals(WINDOW_EXHAUSTED, blocked.reason());
        assertNull(blocked.retryNotBefore());
        assertTrue(budget.complete(a.permit(), null));
        assertEquals(START.plusSeconds(660), budget.acquire(request(NIP_A, DOWNLOAD_INVOICE)).retryNotBefore());
        clock.set(START.plusSeconds(660));
        assertEquals(ALLOW, budget.acquire(request(NIP_A, DOWNLOAD_INVOICE)).outcome());
    }

    @Test
    void completionExtendsWindowToCoverDelayedDispatchAndLongHttp() {
        var budget = budget(limits(1, 10, 10));
        var allowed = budget.acquire(request(NIP_A, QUERY_METADATA));
        clock.set(START.plusSeconds(30));
        assertTrue(budget.complete(allowed.permit(), null));
        clock.set(START.plusSeconds(60));
        assertEquals(START.plusSeconds(90), budget.acquire(request(NIP_A, QUERY_METADATA)).retryNotBefore());
        clock.set(START.plusSeconds(90));
        assertEquals(ALLOW, budget.acquire(request(NIP_A, QUERY_METADATA)).outcome());
    }

    @Test
    void retryAfterBlocksAllWorkersNipsAndOperationsUntilExactDeadline() {
        var budget = budget(limits(100, 100, 10));
        var a = budget.acquire(request(NIP_A, QUERY_METADATA));
        var b = budget.acquire(request(NIP_B, DOWNLOAD_INVOICE));
        Instant due = START.plusSeconds(600);
        assertTrue(budget.complete(a.permit(), due));
        assertTrue(budget.complete(b.permit(), START.plusSeconds(30))); // Nie skraca blokady.
        var next = budget.acquire(request(NIP_B, DOWNLOAD_INVOICE));
        assertEquals(DEFER, next.outcome());
        assertEquals(RETRY_AFTER, next.reason());
        assertEquals(due, next.retryNotBefore());
        clock.set(due.minusNanos(1));
        assertEquals(DEFER, budget.acquire(request(NIP_A, DOWNLOAD_INVOICE)).outcome());
        clock.set(due);
        assertEquals(ALLOW, budget.acquire(request(NIP_A, DOWNLOAD_INVOICE)).outcome());
    }

    @Test
    void retryAfterCannotShortenWindowAndPastDeadlineDoesNotBlock() {
        var budget = budget(limits(1, 10, 10));
        var allowed = budget.acquire(request(NIP_A, QUERY_METADATA));
        assertTrue(budget.complete(allowed.permit(), START.plusSeconds(10)));
        assertEquals(START.plusSeconds(60), budget.acquire(request(NIP_B, DOWNLOAD_INVOICE)).retryNotBefore());
        clock.set(START.plusSeconds(60));
        allowed = budget.acquire(request(NIP_A, QUERY_METADATA));
        assertTrue(budget.complete(allowed.permit(), START));
        clock.set(START.plusSeconds(120));
        assertEquals(ALLOW, budget.acquire(request(NIP_A, QUERY_METADATA)).outcome());
    }

    @Test
    void clockRollbackDefersWithoutRefundAndCompletionWaitsForClockRecovery() {
        var budget = budget(limits(1, 10, 10));
        var allowed = budget.acquire(request(NIP_A, QUERY_METADATA));
        clock.set(START.minusSeconds(1));
        var blocked = budget.acquire(request(NIP_A, QUERY_METADATA));
        assertEquals(DEFER, blocked.outcome());
        assertEquals(CLOCK_REGRESSION, blocked.reason());
        assertEquals(START, blocked.retryNotBefore());
        assertFalse(budget.complete(allowed.permit(), null));
        clock.set(START);
        assertTrue(budget.complete(allowed.permit(), null));
        assertEquals(DEFER, budget.acquire(request(NIP_A, QUERY_METADATA)).outcome());
    }

    @Test
    void unrepresentableExpiryFailsClosedBeforeCharging() {
        clock.set(Instant.MAX);
        var budget = budget(limits(1, 1, 1));
        assertEquals(STATE_UNAVAILABLE, budget.acquire(request(NIP_A, QUERY_METADATA)).reason());
        clock.set(START);
        assertEquals(ALLOW, budget.acquire(request(NIP_A, QUERY_METADATA)).outcome());
    }

    @Test
    void missingEnvironmentOrOperationIsRejected() {
        var budget = budget(new Limits(List.of(window(60, 10)), Map.of(QUERY_METADATA, List.of(window(60, 1))), 1));
        assertEquals(REJECT, budget.acquire(request(NIP_A, DOWNLOAD_INVOICE)).outcome());
        assertEquals(REJECT, budget.acquire(new Request(context(KsefEnvironment.PROD, NIP_A), QUERY_METADATA, "egress")).outcome());
    }

    static Stream<Limits> invalidLimits() {
        return Stream.of(
                new Limits(null, endpoints(window(60, 1)), 1),
                new Limits(List.of(window(60, 1)), null, 1),
                new Limits(List.of(window(60, 1)), Map.of(QUERY_METADATA, List.of()), 1),
                limits(0, 1, 1), limits(-1, 1, 1), limits(1, 0, 1), limits(1, 1, 0), limits(1, 1, -1),
                new Limits(List.of(window(0, 1)), endpoints(window(60, 1)), 1),
                new Limits(List.of(window(-1, 1)), endpoints(window(60, 1)), 1),
                new Limits(List.of(new Window(null, 1)), endpoints(window(60, 1)), 1),
                new Limits(List.of(window(60, 1), window(60, 2)), endpoints(window(60, 1)), 1),
                new Limits(List.of(window(60, 1)), endpoints(window(0, 1)), 1));
    }

    @ParameterizedTest
    @MethodSource("invalidLimits")
    void invalidConfigurationRejectsWithoutRepositoryAccess(Limits invalid) {
        var budget = new InboundApiBudget(Map.of(KsefEnvironment.TEST, invalid), unavailable(), clock);
        var result = budget.acquire(request(NIP_A, QUERY_METADATA));
        assertEquals(REJECT, result.outcome());
        assertEquals(INVALID_CONFIGURATION, result.reason());
        assertNull(result.permit());
    }

    @Test
    void emptyAndNullConfigurationReject() {
        assertEquals(REJECT, new InboundApiBudget(null, unavailable(), clock).acquire(request(NIP_A, QUERY_METADATA)).outcome());
        assertEquals(REJECT, new InboundApiBudget(Map.of(), unavailable(), clock).acquire(request(NIP_A, QUERY_METADATA)).outcome());
    }

    @Test
    void invalidRequestsAreRejectedWithoutRepositoryAccess() {
        var budget = new InboundApiBudget(Map.of(KsefEnvironment.TEST, limits(10, 10, 1)), unavailable(), clock);
        var ctx = context(KsefEnvironment.TEST, NIP_A);
        for (var request : new Request[] {null, new Request(null, QUERY_METADATA, "egress"),
                new Request(ctx, null, "egress"), new Request(ctx, QUERY_METADATA, null),
                new Request(ctx, QUERY_METADATA, ""), new Request(ctx, QUERY_METADATA, "http://untrusted")}) {
            assertEquals(INVALID_REQUEST, budget.acquire(request).reason());
        }
        assertFalse(budget.complete(null, null));
    }

    @Test
    void configurationIsDefensivelyCopied() {
        var windows = new ArrayList<>(List.of(window(60, 1)));
        var endpointMap = new HashMap<>(endpoints(window(60, 10)));
        var limits = new Limits(windows, endpointMap, 10);
        var config = new HashMap<>(Map.of(KsefEnvironment.TEST, limits));
        var budget = new InboundApiBudget(config, repository, clock);
        windows.clear();
        endpointMap.clear();
        config.clear();
        finish(budget, request(NIP_A, QUERY_METADATA));
        assertEquals(DEFER, budget.acquire(request(NIP_B, DOWNLOAD_INVOICE)).outcome());
    }

    @Test
    void policyChangeCannotResetExistingBudgetEvenAfterWindowExpires() {
        var budget = budget(limits(1, 1, 1));
        finish(budget, request(NIP_A, QUERY_METADATA));
        clock.set(START.plusSeconds(600));
        var changed = budget(limits(10, 10, 10));
        var result = changed.acquire(request(NIP_A, QUERY_METADATA));
        assertEquals(REJECT, result.outcome());
        assertEquals(POLICY_MISMATCH, result.reason());
    }

    @Test
    void repositoryFailureNeverFallsBackToLocalAllowAndDoesNotExposeCause() {
        var budget = new InboundApiBudget(Map.of(KsefEnvironment.TEST, limits(10, 10, 1)), unavailable(), clock);
        var result = budget.acquire(request(NIP_A, QUERY_METADATA));
        assertEquals(DEFER, result.outcome());
        assertEquals(STATE_UNAVAILABLE, result.reason());
        assertNull(result.retryNotBefore());
        assertNull(result.permit());
        assertFalse(budget.complete(new Permit(KsefEnvironment.TEST, UUID.randomUUID()), null));
        assertFalse(result.toString().contains("private-diagnostic"));
    }

    @Test
    void lostCommitAcknowledgmentBurnsReservationWithoutGrantingPermission() {
        var failOnce = new ApiBudgetRepository() {
            private boolean first = true;
            @Override public <T> T transact(KsefEnvironment env, Function<State, Change<T>> change) {
                T result = repository.transact(env, change);
                if (first) { first = false; throw new IllegalStateException("private-diagnostic"); }
                return result;
            }
        };
        var budget = new InboundApiBudget(Map.of(KsefEnvironment.TEST, limits(10, 10, 1)), failOnce, clock);
        assertEquals(STATE_UNAVAILABLE, budget.acquire(request(NIP_A, QUERY_METADATA)).reason());
        assertEquals(CONCURRENCY_LIMIT, budget.acquire(request(NIP_B, DOWNLOAD_INVOICE)).reason());
        clock.set(START.plusSeconds(36000));
        assertEquals(CONCURRENCY_LIMIT, budget.acquire(request(NIP_B, DOWNLOAD_INVOICE)).reason());
    }

    @Test
    void completedHistoryIsPrunedButInFlightAttemptSurvives() {
        var budget = budget(limits(10, 10, 10));
        finish(budget, request(NIP_A, QUERY_METADATA));
        budget.acquire(request(NIP_B, QUERY_METADATA));
        clock.set(START.plusSeconds(600));
        budget.acquire(request(NIP_A, DOWNLOAD_INVOICE));
        repository.transact(KsefEnvironment.TEST, state -> {
            assertEquals(2, state.attempts().size());
            assertTrue(state.attempts().stream().allMatch(ApiBudgetRepository.Attempt::inFlight));
            return new ApiBudgetRepository.Change<>(state, null);
        });
    }

    @Test
    @Timeout(15)
    void concurrentPoliciesCannotExceedAggregateAcrossNipsAndOperations() throws Exception {
        var a = budget(limits(7, 100, 24));
        var b = budget(limits(7, 100, 24));
        var results = race(24, i -> {
            var policy = i % 2 == 0 ? a : b;
            var result = policy.acquire(request(i % 3 == 0 ? NIP_A : NIP_B,
                    i % 2 == 0 ? QUERY_METADATA : DOWNLOAD_INVOICE));
            if (result.outcome() == ALLOW) { assertTrue(policy.complete(result.permit(), null)); }
            return result;
        });
        assertEquals(7, results.stream().filter(r -> r.outcome() == ALLOW).count());
        assertEquals(17, results.stream().filter(r -> r.outcome() == DEFER).count());
    }

    @Test
    @Timeout(15)
    void concurrentEndpointAttemptsCannotExceedBudget() throws Exception {
        var budget = budget(limits(100, 4, 24));
        var results = race(24, i -> budget.acquire(request(NIP_A, QUERY_METADATA)));
        assertEquals(4, results.stream().filter(r -> r.outcome() == ALLOW).count());
        assertTrue(results.stream().filter(r -> r.outcome() == DEFER).allMatch(r -> r.reason() == WINDOW_EXHAUSTED));
    }

    @Test
    @Timeout(15)
    void concurrentWorkersCannotExceedInFlightLimit() throws Exception {
        var budget = budget(limits(100, 100, 3));
        var results = race(24, i -> budget.acquire(request(i % 2 == 0 ? NIP_A : NIP_B, QUERY_METADATA)));
        assertEquals(3, results.stream().filter(r -> r.outcome() == ALLOW).count());
        assertEquals(21, results.stream().filter(r -> r.reason() == CONCURRENCY_LIMIT).count());
        for (var result : results) {
            if (result.permit() != null) { assertTrue(budget.complete(result.permit(), null)); }
        }
        assertEquals(ALLOW, budget.acquire(request(NIP_A, DOWNLOAD_INVOICE)).outcome());
    }

    @Test
    void independentRepositoriesDemonstrateNoCrossJvmGuarantee() {
        var a = budget(limits(1, 1, 1));
        var b = new InboundApiBudget(Map.of(KsefEnvironment.TEST, limits(1, 1, 1)),
                new InMemoryApiBudgetRepository(), clock);
        assertEquals(ALLOW, a.acquire(request(NIP_A, QUERY_METADATA)).outcome());
        assertEquals(ALLOW, b.acquire(request(NIP_A, QUERY_METADATA)).outcome());
        assertFalse(request(NIP_A, QUERY_METADATA).toString().contains(NIP_A));
    }

    private static List<Decision> race(int workers, IntFunction<Decision> action) throws Exception {
        var executor = Executors.newFixedThreadPool(workers);
        var ready = new CountDownLatch(workers);
        var start = new CountDownLatch(1);
        try {
            var tasks = new ArrayList<java.util.concurrent.Future<Decision>>();
            for (int i = 0; i < workers; i++) {
                int index = i;
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return action.apply(index);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var results = new ArrayList<Decision>();
            for (var task : tasks) { results.add(task.get(5, TimeUnit.SECONDS)); }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private InboundApiBudget budget(Limits limits) {
        return new InboundApiBudget(Map.of(KsefEnvironment.TEST, limits), repository, clock);
    }

    private static void finish(InboundApiBudget budget, Request request) {
        var result = budget.acquire(request);
        assertEquals(ALLOW, result.outcome());
        assertTrue(budget.complete(result.permit(), null));
    }

    private static Limits limits(int aggregate, int endpoint, int concurrent) {
        return new Limits(List.of(window(60, aggregate)), endpoints(window(60, endpoint)), concurrent);
    }

    private static Map<Operation, List<Window>> endpoints(Window window) {
        return Map.of(QUERY_METADATA, List.of(window), DOWNLOAD_INVOICE, List.of(window));
    }

    private static Window window(long seconds, int calls) { return new Window(Duration.ofSeconds(seconds), calls); }
    private static KsefTaxpayerContext context(KsefEnvironment env, String nip) { return new KsefTaxpayerContext(env, nip); }
    private static Request request(String nip, Operation operation) { return new Request(context(KsefEnvironment.TEST, nip), operation, "egress"); }

    private static ApiBudgetRepository unavailable() {
        return new ApiBudgetRepository() {
            @Override public <T> T transact(KsefEnvironment env, Function<State, Change<T>> transition) {
                throw new IllegalStateException("private-diagnostic");
            }
        };
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        MutableClock(Instant initial) { now = new AtomicReference<>(initial); }
        void set(Instant value) { now.set(value); }
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now.get(), zone); }
    }
}
