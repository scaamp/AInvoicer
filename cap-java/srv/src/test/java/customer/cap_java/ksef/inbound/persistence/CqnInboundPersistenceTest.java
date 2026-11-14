package customer.cap_java.ksef.inbound.persistence;

import static customer.cap_java.ksef.inbound.InboundTestSupport.*;
import static customer.cap_java.ksef.inbound.persistence.InboundPersistence.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.sap.cds.ql.Delete;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.Application;
import customer.cap_java.ksef.KsefDocumentStore;
import customer.cap_java.ksef.KsefInboundAccess;
import customer.cap_java.ksef.KsefFailure;
import customer.cap_java.ksef.fa3.Fa3SchemaValidator;
import customer.cap_java.ksef.inbound.*;
import customer.cap_java.ksef.inbound.fa3.SecureFa3InvoiceParser;
import customer.cap_java.ksef.inbound.sync.*;
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient;

/** Prawdziwe CAP CQN/H2, mock SDK, syntetyczne XML. Nie dowodzi zachowania HANA Cloud. */
@SpringBootTest(classes = Application.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:inbound-persistence;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "logging.level.root=WARN" })
@ActiveProfiles("test")
class CqnInboundPersistenceTest {
    static final OffsetDateTime START = OffsetDateTime.parse("2026-11-01T00:00:00Z");
    static final OffsetDateTime HWM = OffsetDateTime.parse("2026-11-09T10:00:00Z");
    static final SyncScope SCOPE = SyncScope.of(CONTEXT, KsefSubjectRole.BUYER);
    static final Fa3SchemaValidator SCHEMA = new Fa3SchemaValidator();

    @Autowired PersistenceService db;
    @Autowired CdsRuntime runtime;
    InboundPersistence persistence;
    CqnInboundDocumentRepository documents;
    CqnSyncCheckpointRepository checkpoints;
    KSeFClient sdk;
    InvoiceRetrievalService retrieval;
    MemoryStore store;
    byte[] xml;

    @BeforeEach void reset() throws Exception {
        persistence = new InboundPersistence(db, runtime);
        documents = new CqnInboundDocumentRepository(persistence, CLOCK);
        checkpoints = new CqnSyncCheckpointRepository(persistence);
        persistence.tx(() -> {
            for (String entity : List.of(GRANTS, CONFLICTS, DOCUMENTS, CHECKPOINTS, LEASES)) { db.run(Delete.from(entity)); }
            return null;
        });
        sdk = mock(KSeFClient.class);
        retrieval = new InvoiceRetrievalService(inboundClient(sdk), ReadRetryPolicy.noRetry(CLOCK));
        store = new MemoryStore();
        xml = resource("fa3-purchase-synthetic.xml");
        when(sdk.getInvoice(anyString(), anyString())).thenReturn(xml);
    }

    KsefInvoiceMetadata metadata(byte[] bytes, KsefSubjectRole role) {
        var json = metadataJson(ksefNumber(1), "2026-11-02T09:00:00Z", hashOf(bytes));
        return new InvoiceMetadataMapper().map(CONTEXT, role, sdkItem(json));
    }

    PersistentInboundInvoiceSink sink(KsefDocumentStore storage, KsefSubjectRole role) {
        return new PersistentInboundInvoiceSink(SyncScope.of(CONTEXT, role), token(), retrieval, documents,
                storage, new SecureFa3InvoiceParser(), SCHEMA);
    }

    InvoiceSyncRunner runner(SyncCheckpointRepository repository) {
        return new InvoiceSyncRunner(retrieval, InvoiceSyncWindowPlanner.defaults(CLOCK), repository, CLOCK);
    }

    void segment(byte[] bytes) throws Exception {
        when(sdk.queryInvoiceMetadata(eq(0), anyInt(), any(), any(), any())).thenReturn(response(false, false,
                HWM.toString(), sdkItem(metadataJson(ksefNumber(1), "2026-11-02T09:00:00Z", hashOf(bytes)))));
    }

    List<Map<String, Object>> rows(String entity) {
        return persistence.tx(() -> db.run(Select.from(entity)).list().stream()
                .<Map<String, Object>>map(HashMap<String, Object>::new).toList());
    }

    @Test void importIsIdempotentAndOverviewContainsExactAmountWithoutSecrets() throws Exception {
        segment(xml);
        var first = runner(checkpoints).runOnce(CONTEXT, KsefSubjectRole.BUYER, token(), START, sink(store, KsefSubjectRole.BUYER));
        assertEquals(InvoiceSyncRunner.Status.COMMITTED, first.status());
        sink(store, KsefSubjectRole.BUYER).accept(metadata(xml, KsefSubjectRole.BUYER));
        assertEquals(1, rows(DOCUMENTS).size());
        assertEquals(1, rows(GRANTS).size());
        assertEquals(1, store.data.size());
        verify(sdk, times(1)).getInvoice(anyString(), anyString());
        var result = new CqnInboundOverviewRepository(persistence).find(SCOPE, 0, 25).getFirst();
        assertEquals("1269.00", result.grossAmount());
        assertEquals("PARSED", result.parseStatus());
        assertEquals("VERIFIED", result.integrityStatus());
        assertEquals("NOT_POSTED", result.sapPostingStatus());
        assertTrue(result.xmlAvailable());
        assertFalse(result.toString().contains("xmlStorageRef"));
        assertFalse(rows(DOCUMENTS).getFirst().containsKey("companyCode"));
        assertEquals("Vat", rows(DOCUMENTS).getFirst().get("invoiceType"));
    }

    @Test void databaseEnforcesNaturalDocumentAndGrantUniqueness() {
        var metadata = metadata(xml, KsefSubjectRole.BUYER);
        documents.prepare(metadata);
        var duplicate = rows(DOCUMENTS).getFirst();
        duplicate.put("ID", UUID.randomUUID().toString());
        assertThrows(ServiceException.class, () -> persistence.tx(() -> db.run(Insert.into(DOCUMENTS).entry(duplicate))));
        sink(store, KsefSubjectRole.BUYER).accept(metadata);
        var grant = rows(GRANTS).getFirst();
        grant.put("ID", UUID.randomUUID().toString());
        assertThrows(ServiceException.class, () -> persistence.tx(() -> db.run(Insert.into(GRANTS).entry(grant))));
        assertEquals(1, rows(DOCUMENTS).size());
        assertEquals(1, rows(GRANTS).size());
    }

    @Test void sameKsefNumberInAnotherEnvironmentIsASeparateDatabaseIdentityWithoutAnyProdHttp() {
        var test = metadata(xml, KsefSubjectRole.BUYER);
        var prod = new InvoiceMetadataMapper().map(new KsefTaxpayerContext(KsefEnvironment.PROD, CONTEXT_NIP),
                KsefSubjectRole.BUYER, sdkItem(metadataJson(ksefNumber(1), "2026-11-02T09:00:00Z", hashOf(xml))));
        documents.prepare(test);
        documents.prepare(prod);
        assertEquals(2, rows(DOCUMENTS).size());
        assertNotEquals(CqnInboundDocumentRepository.documentId(test), CqnInboundDocumentRepository.documentId(prod));
        verifyNoInteractions(sdk);
    }

    @Test void grantsAreSeparateForEachContextAndRoleAndDoNotBroadenAccess() {
        sink(store, KsefSubjectRole.BUYER).accept(metadata(xml, KsefSubjectRole.BUYER));
        sink(store, KsefSubjectRole.THIRD_PARTY).accept(metadata(xml, KsefSubjectRole.THIRD_PARTY));
        var seller = new KsefTaxpayerContext(KsefEnvironment.TEST, SELLER_NIP);
        var sellerMetadata = new InvoiceMetadataMapper().map(seller, KsefSubjectRole.SELLER,
                sdkItem(metadataJson(ksefNumber(1), "2026-11-02T09:00:00Z", hashOf(xml))));
        new PersistentInboundInvoiceSink(SyncScope.of(seller, KsefSubjectRole.SELLER),
                InboundAccessToken.issuedFor(seller, FAKE_JWT), retrieval, documents, store,
                new SecureFa3InvoiceParser(), SCHEMA).accept(sellerMetadata);
        assertEquals(3, rows(GRANTS).size());
        var overview = new CqnInboundOverviewRepository(persistence);
        assertEquals(1, overview.find(SCOPE, 0, 10).size());
        assertTrue(overview.find(SyncScope.of(CONTEXT, KsefSubjectRole.AUTHORIZED), 0, 10).isEmpty());
        assertTrue(overview.find(new SyncScope(KsefEnvironment.PROD, CONTEXT_NIP, KsefSubjectRole.BUYER), 0, 10).isEmpty());
        assertTrue(overview.find(new SyncScope(KsefEnvironment.TEST, "1234563218", KsefSubjectRole.BUYER), 0, 10).isEmpty());
    }

    @Test void scopeMismatchCannotCreateDocumentOrGrantOrDownloadXml() throws Exception {
        assertThrows(InboundFailure.class, () -> sink(store, KsefSubjectRole.THIRD_PARTY)
                .accept(metadata(xml, KsefSubjectRole.BUYER)));
        assertTrue(rows(DOCUMENTS).isEmpty());
        verifyNoInteractions(sdk);
    }

    @Test void hashConflictIsAppendedAndOriginalIsNeverOverwrittenOrGrantedToNewRole() {
        sink(store, KsefSubjectRole.BUYER).accept(metadata(xml, KsefSubjectRole.BUYER));
        var original = rows(DOCUMENTS).getFirst();
        byte[] changed = new String(xml, StandardCharsets.UTF_8).replace("1269.00", "1268.00").getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> sink(store, KsefSubjectRole.THIRD_PARTY)
                .accept(metadata(changed, KsefSubjectRole.THIRD_PARTY)));
        assertEquals(1, rows(CONFLICTS).size());
        assertEquals(original.get("sha256"), rows(DOCUMENTS).getFirst().get("sha256"));
        assertEquals(original.get("xmlStorageRef"), rows(DOCUMENTS).getFirst().get("xmlStorageRef"));
        assertEquals("CONFLICT", rows(DOCUMENTS).getFirst().get("integrityStatus"));
        assertEquals(1, rows(GRANTS).size());
        assertFalse(new CqnInboundOverviewRepository(persistence).find(SCOPE, 0, 10).getFirst().xmlAvailable());
    }

    @Test void concurrentInsertAndImportUsesOneDocumentAndOneGrant() throws Exception {
        var metadata = metadata(xml, KsefSubjectRole.BUYER);
        var barrier = new CyclicBarrier(2);
        var results = parallel(() -> {
            barrier.await(10, TimeUnit.SECONDS);
            sink(store, KsefSubjectRole.BUYER).accept(metadata);
            return true;
        });
        assertEquals(List.of(true, true), results);
        assertEquals(1, rows(DOCUMENTS).size());
        assertEquals(1, rows(GRANTS).size());
        assertEquals("STORED", rows(DOCUMENTS).getFirst().get("ingestionStatus"));
    }

    @Test void concurrentFirstCheckpointInsertHasExactlyOneWinner() throws Exception {
        var barrier = new CyclicBarrier(2);
        var results = parallel(() -> {
            barrier.await(10, TimeUnit.SECONDS);
            return checkpoints.commit(SCOPE, 0, HWM, NOW);
        });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, checkpoints.find(SCOPE).orElseThrow().version());
    }

    @Test void checkpointCasRejectsOtherWorkerAndCannotRegressOrStayEqual() throws Exception {
        assertTrue(checkpoints.commit(SCOPE, 0, START, NOW));
        var barrier = new CyclicBarrier(2);
        var results = parallel(() -> {
            barrier.await(10, TimeUnit.SECONDS);
            return checkpoints.commit(SCOPE, 1, HWM, NOW);
        });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertFalse(checkpoints.commit(SCOPE, 2, START, NOW));
        assertFalse(checkpoints.commit(SCOPE, 2, HWM, NOW));
        assertEquals(HWM, checkpoints.find(SCOPE).orElseThrow().continuationFrom());
        assertEquals(2, checkpoints.find(SCOPE).orElseThrow().version());
    }

    @Test void checkpointPreservesNanosecondsAndOrderingAcrossWholeSecond() {
        var fraction = START.plusNanos(100);
        assertTrue(checkpoints.commit(SCOPE, 0, START, NOW));
        assertTrue(checkpoints.commit(SCOPE, 1, fraction, NOW));
        assertEquals(fraction, checkpoints.find(SCOPE).orElseThrow().continuationFrom());
        assertFalse(checkpoints.commit(SCOPE, 2, START.plusNanos(99), NOW));
        assertTrue(checkpoints.commit(SCOPE, 2, START.plusSeconds(1), NOW));
    }

    @Test void storageFailureKeepsCheckpointAndLeavesDurableFailureStateForRetry() throws Exception {
        segment(xml);
        KsefDocumentStore failing = mock(KsefDocumentStore.class);
        when(failing.put(any())).thenThrow(new IllegalStateException("SECRET-PROVIDER-ERROR"));
        var error = assertThrows(IllegalStateException.class, () -> runner(checkpoints)
                .runOnce(CONTEXT, KsefSubjectRole.BUYER, token(), START, sink(failing, KsefSubjectRole.BUYER)));
        assertFalse(error.toString().contains("SECRET"));
        assertTrue(checkpoints.find(SCOPE).isEmpty());
        assertTrue(rows(GRANTS).isEmpty());
        assertEquals("STORAGE_FAILED", rows(DOCUMENTS).getFirst().get("ingestionStatus"));
        assertEquals(InvoiceSyncRunner.Status.COMMITTED, runner(checkpoints)
                .runOnce(CONTEXT, KsefSubjectRole.BUYER, token(), START, sink(store, KsefSubjectRole.BUYER)).status());
    }

    @Test void partialWindowStorageFailureRetainsFirstDocumentAndRetryDoesNotDownloadItAgain() throws Exception {
        byte[] second = new String(xml, StandardCharsets.UTF_8).replace("FZ/2026/11/0001", "FZ/2026/11/0002")
                .getBytes(StandardCharsets.UTF_8);
        when(sdk.getInvoice(eq(ksefNumber(2)), anyString())).thenReturn(second);
        when(sdk.queryInvoiceMetadata(eq(0), anyInt(), any(), any(), any())).thenReturn(response(false, false,
                HWM.toString(), sdkItem(metadataJson(ksefNumber(1), "2026-11-02T09:00:00Z", hashOf(xml))),
                sdkItem(metadataJson(ksefNumber(2), "2026-11-03T09:00:00Z", hashOf(second)))));
        var failed = new AtomicBoolean();
        KsefDocumentStore failSecondOnce = new KsefDocumentStore() {
            public String put(byte[] bytes) {
                if (java.util.Arrays.equals(bytes, second) && failed.compareAndSet(false, true)) {
                    throw new IllegalStateException("synthetic-storage-error");
                }
                return store.put(bytes);
            }
            public byte[] get(String ref) { return store.get(ref); }
        };
        assertThrows(IllegalStateException.class, () -> runner(checkpoints).runOnce(CONTEXT, KsefSubjectRole.BUYER,
                token(), START, sink(failSecondOnce, KsefSubjectRole.BUYER)));
        assertTrue(checkpoints.find(SCOPE).isEmpty());
        assertEquals(2, rows(DOCUMENTS).size());
        assertEquals(1, rows(GRANTS).size());
        assertEquals(InvoiceSyncRunner.Status.COMMITTED, runner(checkpoints).runOnce(CONTEXT, KsefSubjectRole.BUYER,
                token(), START, sink(failSecondOnce, KsefSubjectRole.BUYER)).status());
        assertEquals(2, rows(GRANTS).size());
        verify(sdk, times(1)).getInvoice(eq(ksefNumber(1)), anyString());
        verify(sdk, times(2)).getInvoice(eq(ksefNumber(2)), anyString());
    }

    @Test void crashAfterStorageBeforeDatabaseFinalizeRecoversIdempotently() throws Exception {
        segment(xml);
        var crashed = new AtomicBoolean();
        KsefDocumentStore crashOnce = new KsefDocumentStore() {
            public String put(byte[] bytes) {
                String ref = store.put(bytes);
                if (crashed.compareAndSet(false, true)) { throw new SimulatedProcessDeath(); }
                return ref;
            }
            public byte[] get(String ref) { return store.get(ref); }
        };
        assertThrows(SimulatedProcessDeath.class, () -> runner(checkpoints)
                .runOnce(CONTEXT, KsefSubjectRole.BUYER, token(), START, sink(crashOnce, KsefSubjectRole.BUYER)));
        assertTrue(checkpoints.find(SCOPE).isEmpty());
        assertEquals("PENDING", rows(DOCUMENTS).getFirst().get("ingestionStatus"));
        assertEquals(1, store.data.size());
        documents = new CqnInboundDocumentRepository(new InboundPersistence(db, runtime), CLOCK);
        assertEquals(InvoiceSyncRunner.Status.COMMITTED, runner(new CqnSyncCheckpointRepository(persistence))
                .runOnce(CONTEXT, KsefSubjectRole.BUYER, token(), START, sink(crashOnce, KsefSubjectRole.BUYER)).status());
        assertEquals(1, store.data.size());
        assertEquals(1, rows(DOCUMENTS).size());
    }

    @Test void unsupportedSchemaIsStoredExplicitlyAndNeverParsedAsFa3() throws Exception {
        byte[] fa2 = new String(xml, StandardCharsets.UTF_8).replace(SecureFa3InvoiceParser.FA3_NAMESPACE,
                "http://crd.gov.pl/wzor/2023/06/29/12648/").getBytes(StandardCharsets.UTF_8);
        when(sdk.getInvoice(anyString(), anyString())).thenReturn(fa2);
        segment(fa2);
        assertEquals(InvoiceSyncRunner.Status.COMMITTED, runner(checkpoints)
                .runOnce(CONTEXT, KsefSubjectRole.BUYER, token(), START, sink(store, KsefSubjectRole.BUYER)).status());
        var overview = new CqnInboundOverviewRepository(persistence).find(SCOPE, 0, 10).getFirst();
        assertEquals("UNSUPPORTED_SCHEMA", overview.parseStatus());
        assertNull(overview.grossAmount());
        assertTrue(overview.xmlAvailable());
    }

    @Test void invalidXsdIsQuarantinedAndStopsCheckpoint() throws Exception {
        // Parser IN-01 ignoruje adresy; pełna walidacja XSD wykrywa brak wymaganego AdresL1.
        byte[] invalid = new String(xml, StandardCharsets.UTF_8).replace("<AdresL1>ul. Testowa 1</AdresL1>", "")
                .getBytes(StandardCharsets.UTF_8);
        when(sdk.getInvoice(anyString(), anyString())).thenReturn(invalid);
        segment(invalid);
        assertThrows(IllegalStateException.class, () -> runner(checkpoints)
                .runOnce(CONTEXT, KsefSubjectRole.BUYER, token(), START, sink(store, KsefSubjectRole.BUYER)));
        assertTrue(checkpoints.find(SCOPE).isEmpty());
        assertEquals("QUARANTINED", rows(DOCUMENTS).getFirst().get("ingestionStatus"));
        assertEquals("INVALID", rows(DOCUMENTS).getFirst().get("parseStatus"));
        assertNull(rows(DOCUMENTS).getFirst().get("grossAmount"));
    }

    @Test void downloadHashMismatchDoesNotStoreOrGrantOrAdvanceCheckpoint() throws Exception {
        segment("different-bytes".getBytes(StandardCharsets.UTF_8));
        assertThrows(InboundFailure.class, () -> runner(checkpoints)
                .runOnce(CONTEXT, KsefSubjectRole.BUYER, token(), START, sink(store, KsefSubjectRole.BUYER)));
        assertTrue(checkpoints.find(SCOPE).isEmpty());
        assertTrue(store.data.isEmpty());
        assertTrue(rows(GRANTS).isEmpty());
    }

    @Test void twoWorkersCannotHoldSameLeaseAndExpiredWorkerCannotCommitOrReleaseSuccessor() throws Exception {
        var time = new MutableClock(NOW);
        var leases = new CqnInboundSyncLeaseRepository(persistence, time);
        var barrier = new CyclicBarrier(2);
        var claims = parallel(() -> {
            barrier.await(10, TimeUnit.SECONDS);
            return leases.claim(SCOPE, Duration.ofMinutes(2));
        });
        assertEquals(1, claims.stream().filter(java.util.Optional::isPresent).count());
        var first = claims.stream().flatMap(java.util.Optional::stream).findFirst().orElseThrow();
        var old = leases.fenced(checkpoints, first);
        time.now = NOW.plus(Duration.ofMinutes(16));
        var next = leases.claim(SCOPE, Duration.ofMinutes(2)).orElseThrow();
        assertFalse(old.commit(SCOPE, 0, HWM, time.instant()));
        assertTrue(checkpoints.find(SCOPE).isEmpty());
        leases.finish(first, time.instant().plusSeconds(10000), true);
        leases.requireOwned(next);
        assertTrue(leases.fenced(checkpoints, next).commit(SCOPE, 0, HWM, time.instant()));
    }

    @Test void retryAfterIsDurableAndBlocksClaimsIndependentlyOfCheckpoint() {
        var time = new MutableClock(NOW);
        var leases = new CqnInboundSyncLeaseRepository(persistence, time);
        var first = leases.claim(SCOPE, Duration.ofMinutes(2)).orElseThrow();
        leases.finish(first, NOW.plusSeconds(7200), false);
        time.now = NOW.plusSeconds(3600);
        var restarted = new CqnInboundSyncLeaseRepository(new InboundPersistence(db, runtime), time);
        assertTrue(restarted.claim(SCOPE, Duration.ofMinutes(2)).isEmpty());
        assertTrue(checkpoints.find(SCOPE).isEmpty());
        time.now = NOW.plusSeconds(7201);
        assertTrue(restarted.claim(SCOPE, Duration.ofMinutes(2)).isPresent());
    }

    @Test void coordinatorCommitsThenDefersAndDoesNotAuthenticateAgainDuringCooldown() throws Exception {
        segment(xml);
        var access = mock(KsefInboundAccess.class);
        var session = mock(KsefInboundAccess.AccessSession.class);
        when(access.open()).thenReturn(session);
        when(session.token()).thenReturn(token());
        var leases = new CqnInboundSyncLeaseRepository(persistence, CLOCK);
        var synchronization = new InboundSynchronization(SCOPE, START, CLOCK, access, retrieval, documents,
                checkpoints, leases, store, SCHEMA);
        assertEquals(InvoiceSyncRunner.Status.COMMITTED, synchronization.runOnce().orElseThrow().status());
        assertTrue(synchronization.runOnce().isEmpty());
        verify(access, times(1)).open();
        assertEquals(HWM, checkpoints.find(SCOPE).orElseThrow().continuationFrom());
    }

    @Test void coordinatorPersistsRateLimitDeferralAndNeverMovesCheckpoint() throws Exception {
        when(sdk.queryInvoiceMetadata(anyInt(), anyInt(), any(), any(), any()))
                .thenThrow(apiError(429, Map.of("Retry-After", "7200")));
        var access = mock(KsefInboundAccess.class);
        var session = mock(KsefInboundAccess.AccessSession.class);
        when(access.open()).thenReturn(session);
        when(session.token()).thenReturn(token());
        var leases = new CqnInboundSyncLeaseRepository(persistence, CLOCK);
        var synchronization = new InboundSynchronization(SCOPE, START, CLOCK, access, retrieval, documents,
                checkpoints, leases, store, SCHEMA);
        assertThrows(InboundFailure.class, synchronization::runOnce);
        assertTrue(checkpoints.find(SCOPE).isEmpty());
        assertEquals(NOW.plusSeconds(7200), instant(rows(LEASES).getFirst().get("retryNotBefore")));
        assertTrue(synchronization.runOnce().isEmpty());
        verify(access, times(1)).open();
    }

    @Test void concurrentCoordinatorsDoNotAuthenticateOrReadWithoutLease() throws Exception {
        segment(xml);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var access = mock(KsefInboundAccess.class);
        var session = mock(KsefInboundAccess.AccessSession.class);
        when(session.token()).thenReturn(token());
        when(access.open()).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return session;
        });
        var leases = new CqnInboundSyncLeaseRepository(persistence, CLOCK);
        var first = new InboundSynchronization(SCOPE, START, CLOCK, access, retrieval, documents,
                checkpoints, leases, store, SCHEMA);
        var second = new InboundSynchronization(SCOPE, START, CLOCK, access, retrieval, documents,
                checkpoints, new CqnInboundSyncLeaseRepository(persistence, CLOCK), store, SCHEMA);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(first::runOnce);
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertTrue(second.runOnce().isEmpty());
                verify(access, times(1)).open();
                verifyNoInteractions(sdk);
                assertTrue(checkpoints.find(SCOPE).isEmpty());
            } finally { release.countDown(); }
            assertEquals(InvoiceSyncRunner.Status.COMMITTED, result.get(10, TimeUnit.SECONDS).orElseThrow().status());
        }
    }

    @Test void ambiguousAuthenticationBlocksScopeWithoutBlindRedeemOrAuthRetry() {
        var access = mock(KsefInboundAccess.class);
        when(access.open()).thenThrow(new KsefFailure("KSEF_REDEEM_IO", true, false, null));
        var time = new MutableClock(NOW);
        var leases = new CqnInboundSyncLeaseRepository(persistence, time);
        var synchronization = new InboundSynchronization(SCOPE, START, time, access, retrieval, documents,
                checkpoints, leases, store, SCHEMA);
        assertThrows(KsefFailure.class, synchronization::runOnce);
        time.now = NOW.plusSeconds(86400);
        assertTrue(synchronization.runOnce().isEmpty());
        verify(access, times(1)).open();
        verifyNoInteractions(sdk);
        assertEquals("BLOCKED", rows(LEASES).getFirst().get("lastCode"));
    }

    @Test void synchronizationRefusesExternalIoInsideBusinessTransaction() {
        var synchronization = new InboundSynchronization(SCOPE, START, CLOCK, mock(KsefInboundAccess.class),
                retrieval, documents, checkpoints, new CqnInboundSyncLeaseRepository(persistence, CLOCK), store, SCHEMA);
        persistence.tx(() -> {
            // Pierwsza operacja DB inicjuje transakcję CAP/Spring.
            db.run(Select.from(DOCUMENTS));
            assertThrows(IllegalStateException.class, synchronization::runOnce);
            return null;
        });
        assertTrue(rows(LEASES).isEmpty());
        verifyNoInteractions(sdk);
    }

    static <T> List<T> parallel(Callable<T> task) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(task);
            var b = executor.submit(task);
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
    }

    static final class SimulatedProcessDeath extends Error { }

    static final class MemoryStore implements KsefDocumentStore {
        final Map<String, byte[]> data = new ConcurrentHashMap<>();
        public String put(byte[] bytes) {
            String reference = customer.cap_java.sap.KsefInvoiceSnapshotRegistry.sha256Hex(bytes);
            data.putIfAbsent(reference, bytes.clone());
            return reference;
        }
        public byte[] get(String reference) { return data.get(reference).clone(); }
    }

    static final class MutableClock extends java.time.Clock {
        volatile Instant now;
        MutableClock(Instant now) { this.now = now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public java.time.Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
