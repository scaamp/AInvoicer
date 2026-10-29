package customer.cap_java.sap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.outbox.OutboxMessage;
import com.sap.cds.services.outbox.OutboxMessageEventContext;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.RequestContext;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.sap.KsefInvoiceSnapshotRegistry.Drift;
import customer.cap_java.sap.SapKsefResultWriteAdapter.Outcome;
import customer.cap_java.sap.SapKsefResultWriteAdapter.WriteFailure;
import customer.cap_java.sap.SapKsefResultWriteAdapter.WriteResult;
import customer.cap_java.sap.SapKsefResultWriteRecovery.FailureKind;
import customer.cap_java.sap.WritebackFixtures.MutableClock;

/**
 * CAP-04 hardening (MOCK/H2): przejęcie z dzierżawą, brak blokady podczas HTTP, odzyskiwanie,
 * wyczerpanie prób i kontrolowane wznowienie, integralność migawki dokumentu. Transport SAP to mock;
 * żaden test nie jest dowodem LIVE SAP ani wysyłki do KSeF.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = { "cds.outbox.services.SapResultOutbox.startCollector=false",
                "cds.outbox.persistent.scheduler.allTenantsTask.enabled=false",
                "ainv.sap-write.max-attempts=3",
                "ainv.sap-write.renew-interval=PT0.05S",
                "spring.datasource.url=jdbc:h2:mem:writeback-hardening;DB_CLOSE_DELAY=-1" })
@ActiveProfiles("test")
class SapKsefResultWritebackHardeningTest {
    private static final Instant VERSION = WritebackFixtures.VERSION;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClock {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }

    @Autowired CdsRuntime runtime;
    @Autowired PersistenceService db;
    @Autowired DataSource dataSource;
    @Autowired SapKsefResultWriteback writeback;
    @Autowired SapKsefResultWriteRecovery recovery;
    @Autowired KsefInvoiceSnapshotRegistry registry;
    @Autowired MutableClock clock;
    @MockitoBean SapKsefResultWriteAdapter adapter;
    private UUID id;
    private UUID invoice;

    @BeforeEach void setup() {
        id = UUID.randomUUID();
        invoice = UUID.randomUUID();
        reset(adapter);
        tx(() -> {
            WritebackFixtures.seed(db, id, invoice, ksef(), WritebackFixtures.XML_HASH,
                    WritebackFixtures.XML_HASH);
            return writeback.enqueue(id);
        });
    }

    private <T> T tx(Supplier<T> work) {
        return runtime.requestContext().systemUser().run((RequestContext ctx) ->
                runtime.changeSetContext().run(cs -> { return work.get(); }));
    }

    private String ksef() {
        return "1234567802-TEST-" + id.toString().substring(0, 8);
    }

    private WriteResult applied(Outcome outcome) {
        return new WriteResult(outcome, invoice, ksef(), "TEST", VERSION, id);
    }

    // ---- P1: HTTP bez blokady rekordu CAP ----------------------------------------------------

    @Test void httpRunsWithoutRowLockAndClaimIsAlreadyCommitted() throws Exception {
        var inHttp = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(adapter.apply(any())).thenAnswer(call -> {
            inHttp.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return applied(Outcome.APPLIED);
        });
        try (var pool = Executors.newSingleThreadExecutor()) {
            var worker = pool.submit(this::process);
            assertTrue(inHttp.await(10, TimeUnit.SECONDS));
            // Niezależne połączenie JDBC widzi wyłącznie zatwierdzone dane: przejęcie jest już zacommitowane...
            try (Connection other = dataSource.getConnection(); var stmt = other.createStatement()) {
                other.setAutoCommit(false);
                stmt.execute("SET LOCK_TIMEOUT 400");
                try (var rs = stmt.executeQuery("SELECT STATE, ATTEMPTS FROM " + table()
                        + " WHERE ID = '" + id + "'")) {
                    assertTrue(rs.next());
                    assertEquals("PROCESSING", rs.getString(1));
                    assertEquals(1, rs.getInt(2));
                }
                // ...a wiersz polecenia nie jest zablokowany na czas HTTP.
                long started = System.nanoTime();
                stmt.executeQuery("SELECT ID FROM " + table() + " WHERE ID = '" + id + "' FOR UPDATE").close();
                assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 300, "row must not be locked");
                other.rollback();
            }
            release.countDown();
            worker.get(10, TimeUnit.SECONDS);
        }
        assertEquals("SUCCEEDED", row().get("state"));
        assertNull(row().get("claimToken"));
        assertNull(row().get("leaseUntil"));
    }

    @Test void twoWorkersOnTheSameMessageCauseExactlyOneSapCall() throws Exception {
        var inHttp = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(adapter.apply(any())).thenAnswer(call -> {
            inHttp.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return applied(Outcome.APPLIED);
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(this::process);
            assertTrue(inHttp.await(10, TimeUnit.SECONDS));
            // Druga dostawa tej samej wiadomości w trakcie HTTP: wiadomość musi zostać (retry), bez drugiego wywołania.
            var second = pool.submit(() -> {
                assertThrows(WriteFailure.class, this::processWithoutCompletionCheck);
            });
            second.get(10, TimeUnit.SECONDS);
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
        }
        verify(adapter, times(1)).apply(any());
        assertEquals("SUCCEEDED", row().get("state"));
        assertEquals(1, ((Number) row().get("attempts")).intValue());
        process(); // późna dostawa po finalizacji tylko kończy wiadomość
        verify(adapter, times(1)).apply(any());
    }

    @Test void simultaneousClaimsYieldASingleWinner() throws Exception {
        when(adapter.apply(any())).thenAnswer(call -> { Thread.sleep(150); return applied(Outcome.APPLIED); });
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try { processWithoutCompletionCheck(); return true; } catch (WriteFailure held) { return false; }
                }));
            }
            start.countDown();
            for (var r : results) { r.get(20, TimeUnit.SECONDS); }
        }
        verify(adapter, times(1)).apply(any());
        assertEquals("SUCCEEDED", row().get("state"));
    }

    @Test void abandonedClaimIsRecoveredAfterLeaseWithTheSameRequest() {
        var seen = new java.util.ArrayList<SapKsefResultCommand>();
        when(adapter.apply(any())).thenAnswer(call -> {
            seen.add(call.getArgument(0));
            if (seen.size() == 1) {
                // Symulacja twardej awarii procesu po wysłaniu do SAP: Error omija obsługę RuntimeException.
                throw new AssertionError("PROCESS_CRASH");
            }
            return applied(Outcome.ALREADY_APPLIED);
        });
        assertThrows(AssertionError.class, this::processWithoutCompletionCheck);
        assertEquals("PROCESSING", row().get("state"));
        // Dzierżawa jeszcze trwa: wiadomość nie może zostać skonsumowana ani wykonana ponownie.
        assertThrows(WriteFailure.class, this::processWithoutCompletionCheck);
        assertEquals(1, seen.size());
        assertTrue(recovery.needingAttention().stream().noneMatch(s -> s.writeId().equals(id)));

        clock.advance(Duration.ofMinutes(3));
        assertTrue(recovery.status(id).orphaned());
        process();
        assertEquals(2, seen.size());
        assertEquals(seen.getFirst(), seen.getLast());
        assertEquals("SUCCEEDED", row().get("state"));
        assertEquals("ALREADY_APPLIED", row().get("outcome"));
        assertEquals(2, ((Number) row().get("attempts")).intValue());
        assertTrue(history().contains("CLAIM_EXPIRED"));
    }

    @Test void lateFinalizeAfterTheLeaseWasTakenOverCannotOverwriteTheResult() throws Exception {
        var inHttp = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(adapter.apply(any())).thenAnswer(call -> {
            if (inHttp.getCount() == 1) {
                inHttp.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return applied(Outcome.RESULT_CONFLICT);   // spóźniona odpowiedź pierwszego workera
            }
            return applied(Outcome.ALREADY_APPLIED);
        });
        try (var pool = Executors.newSingleThreadExecutor()) {
            var slow = pool.submit(this::process);
            assertTrue(inHttp.await(10, TimeUnit.SECONDS));
            clock.advance(Duration.ofMinutes(3));
            process();                                     // drugi worker odzyskuje wygasłą dzierżawę
            assertEquals("SUCCEEDED", row().get("state"));
            release.countDown();
            slow.get(10, TimeUnit.SECONDS);
        }
        assertEquals("SUCCEEDED", row().get("state"));
        assertEquals("ALREADY_APPLIED", row().get("outcome"));
        assertTrue(history().contains("CLAIM_LOST"));
    }

    @Test void unexpectedExceptionReleasesTheClaimWithSafeCodeOnly() {
        when(adapter.apply(any())).thenThrow(new IllegalStateException("https://secret.example/token=abc"));
        var failure = assertThrows(WriteFailure.class, this::processWithoutCompletionCheck);
        assertEquals("WRITE_UNEXPECTED_ERROR", failure.getMessage());
        assertEquals("PENDING", row().get("state"));
        assertNull(row().get("claimToken"));
        assertEquals("WRITE_UNEXPECTED_ERROR", row().get("outcome"));
        assertFalse(history().stream().anyMatch(h -> h.contains("secret")));
    }

    // ---- P1: wyczerpanie prób i kontrolowane wznowienie --------------------------------------

    @Test void exhaustedRetryBudgetIsRecordedAndResumeReusesTheSamePayload() {
        var seen = new java.util.ArrayList<SapKsefResultCommand>();
        when(adapter.apply(any())).thenAnswer(call -> {
            seen.add(call.getArgument(0));
            if (seen.size() <= 3) { throw new WriteFailure("SAP_HTTP_503", true); }
            return applied(Outcome.ALREADY_APPLIED);
        });
        assertThrows(WriteFailure.class, this::processWithoutCompletionCheck);
        assertThrows(WriteFailure.class, this::processWithoutCompletionCheck);
        assertEquals("PENDING", row().get("state"));
        process();   // trzecia próba = limit; wiadomość jest skonsumowana, polecenie jawnie wyczerpane
        assertEquals("RETRY_EXHAUSTED", row().get("state"));
        assertEquals("SAP_HTTP_503", row().get("outcome"));
        var status = recovery.needingAttention().stream().filter(s -> s.writeId().equals(id)).findFirst().orElseThrow();
        assertEquals(FailureKind.SAP_AVAILABILITY, status.failureKind());
        assertTrue(status.resumable());
        int messagesBefore = outboxCount();

        recovery.resume(id, "SAP_RESTORED");
        assertEquals("PENDING", row().get("state"));
        assertEquals(0, ((Number) row().get("cycleAttempts")).intValue());
        assertEquals(3, ((Number) row().get("attempts")).intValue());
        assertEquals(messagesBefore + 1, outboxCount());
        assertTrue(history().contains("RESUMED:SAP_RESTORED"));
        process();
        assertEquals("SUCCEEDED", row().get("state"));
        assertEquals(4, seen.size());
        assertTrue(seen.stream().allMatch(seen.getFirst()::equals));
        assertEquals(id, seen.getLast().requestId());
        assertEquals(VERSION, seen.getLast().expectedVersion());
        assertThrows(IllegalStateException.class, () -> recovery.resume(id, "AGAIN"));
    }

    @Test void retryAfterBlocksResumeUntilTheDeadline() {
        Instant deadline = clock.instant().plusSeconds(60);
        when(adapter.apply(any())).thenThrow(new WriteFailure("SAP_HTTP_429", false, deadline));
        process();
        assertEquals("RECONCILIATION_REQUIRED", row().get("state"));
        assertEquals(FailureKind.SAP_AVAILABILITY, recovery.status(id).failureKind());
        assertFalse(recovery.status(id).resumable());
        assertEquals("RESUME_TOO_EARLY", assertThrows(IllegalStateException.class,
                () -> recovery.resume(id, "OPERATOR")).getMessage());
        clock.advance(Duration.ofSeconds(61));
        assertTrue(recovery.status(id).resumable());
        recovery.resume(id, "OPERATOR");
        assertEquals("PENDING", row().get("state"));
        assertNull(row().get("retryNotBefore"));
    }

    @Test void businessConflictAndInvalidResponseAreNotResumable() {
        when(adapter.apply(any())).thenReturn(applied(Outcome.VERSION_CONFLICT));
        process();
        var status = recovery.status(id);
        assertEquals(FailureKind.BUSINESS_CONFLICT, status.failureKind());
        assertFalse(status.resumable());
        assertEquals("RESUME_NOT_ALLOWED", assertThrows(IllegalStateException.class,
                () -> recovery.resume(id, "OPERATOR")).getMessage());

        tx(() -> db.run(Update.entity(SapKsefResultWriteback.WRITES)
                .data(Map.of("outcome", "SAP_INVALID_RESULT")).byId(id.toString())));
        assertEquals(FailureKind.INVALID_RESPONSE, recovery.status(id).failureKind());
        assertFalse(recovery.status(id).resumable());
        tx(() -> db.run(Update.entity(SapKsefResultWriteback.WRITES)
                .data(Map.of("outcome", "SAP_HTTP_403")).byId(id.toString())));
        assertEquals(FailureKind.SAP_AVAILABILITY, recovery.status(id).failureKind());
        assertTrue(recovery.status(id).resumable());
    }

    @Test void succeededAndFreshPendingCannotBeResumed() {
        assertThrows(IllegalStateException.class, () -> recovery.resume(id, "OPERATOR"));   // świeży PENDING
        when(adapter.apply(any())).thenReturn(applied(Outcome.APPLIED));
        process();
        assertThrows(IllegalStateException.class, () -> recovery.resume(id, "OPERATOR"));   // SUCCEEDED
        assertThrows(IllegalArgumentException.class, () -> recovery.resume(id, "bad reason!"));
    }

    @Test void orphanedPendingIsReportedAndCanBeResumed() {
        assertTrue(recovery.needingAttention().stream().noneMatch(s -> s.writeId().equals(id)));
        clock.advance(Duration.ofMinutes(11));
        var status = recovery.needingAttention().stream().filter(s -> s.writeId().equals(id)).findFirst().orElseThrow();
        assertTrue(status.orphaned());
        assertTrue(status.resumable());
        recovery.resume(id, "LOST_MESSAGE");
        assertFalse(recovery.status(id).orphaned());
    }

    @Test void concurrentResumeOfOrphanedPendingHasExactlyOneWinner() throws Exception {
        clock.advance(Duration.ofMinutes(11));
        var payload = writeback.command(row());
        int messagesBefore = outboxCount();
        var bothRead = new java.util.concurrent.CyclicBarrier(2);
        // Prawdziwe H2/CAP; zatrzymujemy obu operatorów po odczycie TEJ SAMEJ rewizji.
        PersistenceService observedDb = mock(PersistenceService.class, invocation -> {
            Object result;
            try { result = invocation.getMethod().invoke(db, invocation.getRawArguments()); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
            if (invocation.getArguments().length > 0
                    && invocation.getArgument(0) instanceof com.sap.cds.ql.cqn.CqnSelect) {
                bothRead.await(10, TimeUnit.SECONDS);
            }
            return result;
        });
        var controlledRecovery = new SapKsefResultWriteRecovery(observedDb, writeback, clock, Duration.ofMinutes(10));
        var start = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<String> resume = () -> {
                start.await(10, TimeUnit.SECONDS);
                try { controlledRecovery.resume(id, "LOST_MESSAGE"); return "SUCCESS"; }
                catch (IllegalStateException conflict) { return conflict.getMessage(); }
            };
            var first = pool.submit(resume);
            var second = pool.submit(resume);
            var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter("SUCCESS"::equals).count(), results.toString());
            assertEquals(1, results.stream().filter("RESUME_CONFLICT"::equals).count(), results.toString());
        }
        assertEquals(messagesBefore + 1, outboxCount());
        assertEquals(1, history().stream().filter("RESUMED:LOST_MESSAGE"::equals).count());
        assertEquals(payload, writeback.command(row()));
        assertEquals(1L, ((Number) row().get("revision")).longValue());
        when(adapter.apply(payload)).thenReturn(applied(Outcome.APPLIED));
        process();
        assertEquals("SUCCEEDED", row().get("state"));
        verify(adapter, times(1)).apply(payload);
    }

    @Test void expiredProcessingCanBeResumedButActiveClaimCannot() {
        when(adapter.apply(any())).thenThrow(new AssertionError("PROCESS_CRASH"));
        assertThrows(AssertionError.class, this::processWithoutCompletionCheck);
        assertEquals("RESUME_NOT_ALLOWED", assertThrows(IllegalStateException.class,
                () -> recovery.resume(id, "OPERATOR")).getMessage());
        var payload = writeback.command(row());
        clock.advance(Duration.ofMinutes(3));
        recovery.resume(id, "PROCESS_RESTARTED");
        assertEquals("PENDING", row().get("state"));
        assertEquals(payload, writeback.command(row()));
        assertNull(row().get("claimToken"));
    }

    @Test void failedOutboxPublicationRollsBackResumeRevisionAndHistory() {
        clock.advance(Duration.ofMinutes(11));
        var before = new java.util.LinkedHashMap<>(row());
        int messagesBefore = outboxCount();
        var failingWriteback = mock(SapKsefResultWriteback.class,
                org.mockito.AdditionalAnswers.delegatesTo(writeback));
        doThrow(new IllegalStateException("OUTBOX_FAILED")).when(failingWriteback).publish(id);
        var failingRecovery = new SapKsefResultWriteRecovery(db, failingWriteback, clock, Duration.ofMinutes(10));
        assertEquals("OUTBOX_FAILED", assertThrows(IllegalStateException.class,
                () -> failingRecovery.resume(id, "OPERATOR")).getMessage());
        assertEquals(before, row());
        assertEquals(messagesBefore, outboxCount());
        assertTrue(history().stream().noneMatch(h -> h.startsWith("RESUMED:")));
    }

    @Test void renewalKeepsDelayedCallExclusiveAndExpiredOwnerCannotRenew() throws Exception {
        var inHttp = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(adapter.apply(any())).thenAnswer(call -> {
            inHttp.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return applied(Outcome.APPLIED);
        });
        try (var pool = Executors.newSingleThreadExecutor()) {
            var worker = pool.submit(this::process);
            assertTrue(inHttp.await(10, TimeUnit.SECONDS));
            UUID token = UUID.fromString((String) row().get("claimToken"));
            clock.advance(Duration.ofSeconds(90));
            Instant expectedLease = clock.instant().plus(Duration.ofMinutes(2));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!expectedLease.equals(row().get("leaseUntil")) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(expectedLease, row().get("leaseUntil"), "Scheduler musi odnowić lease podczas HTTP");
            clock.advance(Duration.ofSeconds(40)); // Pierwotny lease minął, odnowiony nadal trwa.
            assertThrows(WriteFailure.class, this::processWithoutCompletionCheck);
            assertThrows(IllegalStateException.class, () -> recovery.resume(id, "OPERATOR"));
            assertFalse(writeback.renewLease(id, UUID.randomUUID()));
            release.countDown();
            worker.get(10, TimeUnit.SECONDS);
            assertFalse(writeback.renewLease(id, token));
        } finally { release.countDown(); }
        verify(adapter, times(1)).apply(any());
    }

    @Test void timeoutCanBeRecoveredWithUnchangedRequestAndPayload() {
        var payload = writeback.command(row());
        when(adapter.apply(any())).thenThrow(new WriteFailure("SAP_HTTP_500", true));
        assertThrows(WriteFailure.class, this::processWithoutCompletionCheck);
        assertEquals("PENDING", row().get("state"));
        assertNull(row().get("claimToken"));
        doReturn(applied(Outcome.ALREADY_APPLIED)).when(adapter).apply(payload);
        process();
        assertEquals("SUCCEEDED", row().get("state"));
        verify(adapter, times(2)).apply(payload);
    }

    // ---- P2: integralność dokumentu i unikalność numeru KSeF ------------------------------------

    @Test void enqueueRequiresSnapshotConfirmationHashAndMatchingScope() {
        UUID other = UUID.randomUUID();
        UUID otherInvoice = UUID.randomUUID();
        tx(() -> { WritebackFixtures.seed(db, other, otherInvoice, "1234567802-TEST-A" + other.toString().substring(0, 8),
                "c".repeat(64), "d".repeat(64)); return null; });
        assertEquals("CONFIRMED_DOCUMENT_HASH_MISMATCH", assertThrows(IllegalStateException.class,
                () -> tx(() -> writeback.enqueue(other))).getMessage());

        UUID noSnapshot = UUID.randomUUID();
        tx(() -> { WritebackFixtures.seed(db, noSnapshot, UUID.randomUUID(), "1234567802-TEST-B1", "e".repeat(64), "e".repeat(64));
            db.run(Update.entity(WritebackFixtures.OPS).data("snapshot_ID", null).byId(noSnapshot.toString())); return null; });
        assertEquals("DOCUMENT_SNAPSHOT_REQUIRED", assertThrows(IllegalStateException.class,
                () -> tx(() -> writeback.enqueue(noSnapshot))).getMessage());

        UUID wrongInvoice = UUID.randomUUID();
        tx(() -> { WritebackFixtures.seed(db, wrongInvoice, UUID.randomUUID(), "1234567802-TEST-C1", "f".repeat(64), "f".repeat(64));
            db.run(Update.entity(WritebackFixtures.OPS).data("sapDocumentRef", UUID.randomUUID().toString())
                    .byId(wrongInvoice.toString())); return null; });
        assertEquals("DOCUMENT_SNAPSHOT_SCOPE_CONFLICT", assertThrows(IllegalStateException.class,
                () -> tx(() -> writeback.enqueue(wrongInvoice))).getMessage());
        assertEquals(0, outboxCountFor(other) + outboxCountFor(noSnapshot) + outboxCountFor(wrongInvoice));
    }

    @Test void ksefNumberIsUniquePerEnvironmentButNotGlobalAcrossDirections() {
        UUID second = UUID.randomUUID();
        String sameNumber = (String) tx(() -> db.run(Select.from(WritebackFixtures.OPS).byId(id.toString())).single())
                .get("ksefNumber");
        tx(() -> { WritebackFixtures.seed(db, second, UUID.randomUUID(), sameNumber, "9".repeat(64), "9".repeat(64)); return null; });
        assertEquals("KSEF_NUMBER_ALREADY_ASSIGNED", assertThrows(IllegalStateException.class,
                () -> tx(() -> writeback.enqueue(second))).getMessage());
        // Ten sam numer w KsefInvoices dla strony nabywcy (INBOUND) jest legalny i nie jest blokowany.
        assertDoesNotThrow(() -> tx(() -> db.run(com.sap.cds.ql.Insert.into("customer.cap_java.KsefInvoices")
                .entry(Map.of("ID", UUID.randomUUID().toString(), "taxpayerNip", "9876543210",
                        "direction", "INBOUND", "ksefNumber", sameNumber)))));
    }

    @Test void snapshotRegistryIsIdempotentAndDetectsHeaderAndItemDrift() {
        UUID inv = UUID.randomUUID();
        var item = new java.util.LinkedHashMap<String, Object>(Map.of("ItemUUID", UUID.randomUUID().toString(),
                "InvoiceUUID", inv.toString(), "LocalLastChangedAt", VERSION));
        var header = new java.util.LinkedHashMap<String, Object>(Map.of("InvoiceUUID", inv.toString(),
                "Direction", "OUTBOUND", "SellerNip", "1234567802", "CompanyCode", "TST1",
                "LocalLastChangedAt", VERSION));
        var sap = new SapInvoiceSnapshot(header, List.of(item), List.of());
        byte[] xml = "<Faktura/>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        UUID first = tx(() -> registry.register(sap, "1234567802", "TST1", "TEST", "FA(3)", xml));
        assertEquals(first, tx(() -> registry.register(sap, "1234567802", "TST1", "TEST", "FA(3)", xml)));
        assertNotEquals(first, tx(() -> registry.register(sap, "1234567802", "TST1", "TEST", "FA(3)", "<Faktura x='1'/>".getBytes())));
        var stored = db.run(Select.from(SapKsefResultWriteback.SNAPSHOTS).byId(first.toString())).single();
        assertEquals(KsefInvoiceSnapshotRegistry.sha256Hex(xml), stored.get("xmlSha256"));
        assertEquals(xml.length, stored.get("xmlByteLength"));
        assertEquals(VERSION.toString(), stored.get("sapHeaderVersion"));
        assertEquals(Drift.UNCHANGED, KsefInvoiceSnapshotRegistry.detectDrift(stored, sap));

        var newerHeader = new java.util.LinkedHashMap<>(header);
        newerHeader.put("LocalLastChangedAt", VERSION.plusNanos(100));
        assertEquals(Drift.HEADER_CHANGED, KsefInvoiceSnapshotRegistry.detectDrift(stored,
                new SapInvoiceSnapshot(newerHeader, List.of(item), List.of())));
        var changedItem = new java.util.LinkedHashMap<>(item);
        changedItem.put("LocalLastChangedAt", VERSION.plusNanos(100));
        assertEquals(Drift.ITEMS_CHANGED, KsefInvoiceSnapshotRegistry.detectDrift(stored,
                new SapInvoiceSnapshot(header, List.of(changedItem), List.of())));
        assertEquals(Drift.ITEMS_CHANGED, KsefInvoiceSnapshotRegistry.detectDrift(stored,
                new SapInvoiceSnapshot(header, List.of(item, changedItem), List.of())));
        assertEquals(Drift.HEADER_AND_ITEMS_CHANGED, KsefInvoiceSnapshotRegistry.detectDrift(stored,
                new SapInvoiceSnapshot(newerHeader, List.of(), List.of())));
    }

    @Test void snapshotRegistryRefusesWrongScopeInboundAndEmptyXml() {
        UUID inv = UUID.randomUUID();
        var header = Map.<String, Object>of("InvoiceUUID", inv.toString(), "Direction", "OUTBOUND",
                "SellerNip", "1234567802", "CompanyCode", "TST1", "LocalLastChangedAt", VERSION);
        var sap = new SapInvoiceSnapshot(header, List.of(), List.of());
        byte[] xml = new byte[] { 1 };
        assertThrows(IllegalArgumentException.class, () -> tx(() -> registry.register(sap, "9876543210", "TST1", "TEST", "FA(3)", xml)));
        assertThrows(IllegalArgumentException.class, () -> tx(() -> registry.register(sap, "1234567802", "TST2", "TEST", "FA(3)", xml)));
        assertThrows(IllegalArgumentException.class, () -> tx(() -> registry.register(sap, "1234567802", "TST1", "PROD", "FA(3)", xml)));
        assertThrows(IllegalArgumentException.class, () -> tx(() -> registry.register(sap, "1234567802", "TST1", "TEST", "FA(3)", new byte[0])));
        var inbound = Map.<String, Object>of("InvoiceUUID", inv.toString(), "Direction", "INBOUND",
                "SellerNip", "1234567802", "CompanyCode", "TST1", "LocalLastChangedAt", VERSION);
        assertThrows(IllegalArgumentException.class, () -> tx(() -> registry.register(
                new SapInvoiceSnapshot(inbound, List.of(), List.of()), "1234567802", "TST1", "TEST", "FA(3)", xml)));
    }

    // ---- pomocnicze --------------------------------------------------------------------------------

    private void process() {
        processWithoutCompletionCheck();
    }

    /** Uruchamia handler jak collector CAP: własna transakcja wiadomości; sprawdza kompletność tylko przy sukcesie. */
    private void processWithoutCompletionCheck() {
        tx(() -> {
            var context = OutboxMessageEventContext.create("applyConfirmedResult");
            context.setIsInbound(true);
            var message = OutboxMessage.create();
            message.setParams(Map.of("writeId", id.toString()));
            context.setMessage(message);
            writeback.process(context);
            assertTrue(context.isCompleted());
            return null;
        });
    }

    private String table() {
        return "CUSTOMER_CAP_JAVA_SAPRESULTWRITES";
    }

    private Map<String, Object> row() {
        return db.run(Select.from(SapKsefResultWriteback.WRITES).byId(id.toString())).single();
    }

    private List<String> history() {
        return db.run(Select.from(SapKsefResultWriteback.HISTORY).where(r -> r.get("writeId").eq(id.toString())))
                .list().stream().map(r -> (String) r.get("outcome")).toList();
    }

    private int outboxCount() {
        return outboxCountFor(id);
    }

    private int outboxCountFor(UUID target) {
        return (int) db.run(Select.from("cds.outbox.Messages").where(r -> r.get("target").eq("SapResultOutbox")))
                .list().stream().filter(r -> r.get("msg").toString().contains(target.toString())).count();
    }
}
