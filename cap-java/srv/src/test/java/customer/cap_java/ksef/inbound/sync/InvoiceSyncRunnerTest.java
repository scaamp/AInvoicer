package customer.cap_java.ksef.inbound.sync;

import static customer.cap_java.ksef.inbound.InboundTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.http.HttpTimeoutException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import customer.cap_java.ksef.inbound.InboundFailure;
import customer.cap_java.ksef.inbound.InvoiceRetrievalService;
import customer.cap_java.ksef.inbound.KsefInvoiceMetadata;
import customer.cap_java.ksef.inbound.KsefSubjectRole;
import customer.cap_java.ksef.inbound.ReadRetryPolicy;
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient;
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQueryFilters;
import pl.akmf.ksef.sdk.system.SystemKSeFSDKException;

/**
 * Przebieg synchronizacji z mockiem interfejsu SDK i testowym repozytorium checkpointów:
 * checkpoint zatwierdzany wyłącznie po pełnym przetworzeniu, idempotentne powtórzenia, niezależne zakresy.
 */
class InvoiceSyncRunnerTest {
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-11-01T00:00:00Z");
    private static final String HWM = "2026-11-09T10:00:00Z";

    private final KSeFClient sdk = mock(KSeFClient.class);
    private final InMemoryCheckpointRepository checkpoints = new InMemoryCheckpointRepository();
    private final InvoiceSyncRunner runner = new InvoiceSyncRunner(
            new InvoiceRetrievalService(inboundClient(sdk), ReadRetryPolicy.noRetry(CLOCK)),
            InvoiceSyncWindowPlanner.defaults(CLOCK), checkpoints, CLOCK);
    private final SyncScope buyerScope = SyncScope.of(CONTEXT, KsefSubjectRole.BUYER);

    @Test
    void normalWindowIsProcessedThenCheckpointCommittedAtHwm() throws Exception {
        when(sdk.queryInvoiceMetadata(eq(0), anyInt(), any(), any(), any()))
                .thenReturn(response(false, false, HWM, item(1, "2026-11-02T00:00:00Z"), item(2, "2026-11-03T00:00:00Z")));
        var processed = new ArrayList<String>();

        var result = runner.runOnce(CONTEXT, KsefSubjectRole.BUYER, token(), START, m -> processed.add(m.ksefNumber()));

        assertEquals(InvoiceSyncRunner.Status.COMMITTED, result.status());
        assertEquals(List.of(ksefNumber(1), ksefNumber(2)), processed);
        var checkpoint = checkpoints.find(buyerScope).orElseThrow();
        assertEquals(OffsetDateTime.parse(HWM), checkpoint.continuationFrom());
        assertEquals(1, checkpoint.version());
    }

    @Test
    void failureWhileProcessingLeavesCheckpointUncommittedAndRetryIsIdempotent() throws Exception {
        when(sdk.queryInvoiceMetadata(eq(0), anyInt(), any(), any(), any()))
                .thenReturn(response(false, false, HWM, item(1, "2026-11-02T00:00:00Z"), item(2, "2026-11-03T00:00:00Z")));
        Set<String> stored = new LinkedHashSet<>();
        var attempts = new AtomicInteger();
        InboundInvoiceSink failingOnSecond = m -> {
            if (m.ksefNumber().equals(ksefNumber(2)) && attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("simulated storage failure");
            }
            stored.add(m.ksefNumber()); // idempotentny zapis po numerze KSeF
        };

        assertThrows(IllegalStateException.class,
                () -> runner.runOnce(CONTEXT, KsefSubjectRole.BUYER, token(), START, failingOnSecond));
        assertTrue(checkpoints.find(buyerScope).isEmpty(), "checkpoint nie może przesunąć się po błędzie");
        assertEquals(0, checkpoints.commits);

        var retry = runner.runOnce(CONTEXT, KsefSubjectRole.BUYER, token(), START, failingOnSecond);
        assertEquals(InvoiceSyncRunner.Status.COMMITTED, retry.status());
        assertEquals(START, retry.window().from(), "powtórzenie dotyczy tego samego okna");
        assertEquals(Set.of(ksefNumber(1), ksefNumber(2)), stored);
        assertEquals(1, checkpoints.commits);
    }

    @Test
    void timeoutDuringFetchKeepsCheckpoint() throws Exception {
        checkpoints.commit(buyerScope, 0, START, NOW);
        when(sdk.queryInvoiceMetadata(anyInt(), anyInt(), any(), any(), any()))
                .thenThrow(new SystemKSeFSDKException("POST https://example.invalid timeout", new HttpTimeoutException("t")));

        var failure = assertThrows(InboundFailure.class,
                () -> runner.runOnce(CONTEXT, KsefSubjectRole.BUYER, token(), null, m -> fail("no documents")));

        assertEquals(InboundFailure.Category.TIMEOUT, failure.category());
        var checkpoint = checkpoints.find(buyerScope).orElseThrow();
        assertEquals(START, checkpoint.continuationFrom());
        assertEquals(1, checkpoint.version());
    }

    @Test
    void truncatedWindowContinuesFromLastDateAndBoundaryRecordIsDeduplicated() throws Exception {
        var captor = ArgumentCaptor.forClass(InvoiceQueryFilters.class);
        when(sdk.queryInvoiceMetadata(eq(0), anyInt(), any(), captor.capture(), any()))
                .thenReturn(response(true, true, HWM, item(1, "2026-11-02T00:00:00Z"), item(2, "2026-11-04T00:00:00Z")))
                .thenReturn(response(false, false, HWM, item(2, "2026-11-04T00:00:00Z"), item(3, "2026-11-05T00:00:00Z")));
        Set<String> stored = new LinkedHashSet<>();
        var deliveries = new AtomicInteger();
        InboundInvoiceSink sink = m -> {
            deliveries.incrementAndGet();
            stored.add(m.ksefNumber());
        };

        var first = runner.runOnce(CONTEXT, KsefSubjectRole.BUYER, token(), START, sink);
        assertEquals(InvoiceSyncRunner.Status.COMMITTED, first.status());
        assertEquals(OffsetDateTime.parse("2026-11-04T00:00:00Z"), checkpoints.find(buyerScope).orElseThrow().continuationFrom());

        var second = runner.runOnce(CONTEXT, KsefSubjectRole.BUYER, token(), START, sink);
        assertEquals(InvoiceSyncRunner.Status.COMMITTED, second.status());
        assertEquals(OffsetDateTime.parse("2026-11-04T00:00:00Z"), captor.getAllValues().get(1).getDateRange().getFrom());
        assertEquals(OffsetDateTime.parse(HWM), checkpoints.find(buyerScope).orElseThrow().continuationFrom());
        assertEquals(4, deliveries.get(), "rekord graniczny dostarczony ponownie");
        assertEquals(Set.of(ksefNumber(1), ksefNumber(2), ksefNumber(3)), stored, "…ale zapisany raz");
    }

    @Test
    void missingHwmHoldsCheckpoint() throws Exception {
        when(sdk.queryInvoiceMetadata(eq(0), anyInt(), any(), any(), any()))
                .thenReturn(response(false, false, null, item(1, "2026-11-02T00:00:00Z")));
        var result = runner.runOnce(CONTEXT, KsefSubjectRole.BUYER, token(), START, m -> { });
        assertEquals(InvoiceSyncRunner.Status.HELD, result.status());
        assertEquals("HWM_MISSING", result.advance().reason());
        assertTrue(checkpoints.find(buyerScope).isEmpty());
    }

    @Test
    void concurrentCommitByAnotherInstanceIsNotOverwritten() throws Exception {
        when(sdk.queryInvoiceMetadata(eq(0), anyInt(), any(), any(), any())).thenAnswer(invocation -> {
            // Inna instancja zatwierdza swój wynik w trakcie naszego przebiegu.
            checkpoints.forceExternalCommit(buyerScope, OffsetDateTime.parse("2026-11-08T00:00:00Z"));
            return response(false, false, HWM, item(1, "2026-11-02T00:00:00Z"));
        });
        var result = runner.runOnce(CONTEXT, KsefSubjectRole.BUYER, token(), START, m -> { });
        assertEquals(InvoiceSyncRunner.Status.CONCURRENT_UPDATE, result.status());
        assertEquals(OffsetDateTime.parse("2026-11-08T00:00:00Z"),
                checkpoints.find(buyerScope).orElseThrow().continuationFrom());
    }

    @Test
    void checkpointsOfDifferentRolesAreIndependent() throws Exception {
        when(sdk.queryInvoiceMetadata(eq(0), anyInt(), any(), any(), any()))
                .thenReturn(response(false, false, HWM, item(1, "2026-11-02T00:00:00Z")))
                .thenReturn(response(false, false, "2026-11-09T09:00:00Z"));
        runner.runOnce(CONTEXT, KsefSubjectRole.BUYER, token(), START, m -> { });
        List<KsefInvoiceMetadata> seen = new ArrayList<>();
        runner.runOnce(CONTEXT, KsefSubjectRole.SELLER, token(), START, seen::add);

        assertEquals(OffsetDateTime.parse(HWM), checkpoints.find(buyerScope).orElseThrow().continuationFrom());
        assertEquals(OffsetDateTime.parse("2026-11-09T09:00:00Z"),
                checkpoints.find(SyncScope.of(CONTEXT, KsefSubjectRole.SELLER)).orElseThrow().continuationFrom());
        assertTrue(seen.isEmpty());
    }
}
