package customer.cap_java.ksef.storage;

import static customer.cap_java.ksef.inbound.InboundTestSupport.*;
import static customer.cap_java.ksef.inbound.persistence.InboundPersistence.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.sap.cds.ql.Delete;
import com.sap.cds.ql.Select;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.Application;
import customer.cap_java.ksef.KsefDocumentStore;
import customer.cap_java.ksef.KsefFailure;
import customer.cap_java.ksef.KsefInboundAccess;
import customer.cap_java.ksef.fa3.Fa3SchemaValidator;
import customer.cap_java.ksef.inbound.*;
import customer.cap_java.ksef.inbound.persistence.*;
import customer.cap_java.ksef.inbound.sync.*;
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient;
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQueryFilters;

/**
 * KSEF-IN-05 (B3) — OFFLINE H2 z fałszywym klientem SDK KSeF (mock, zero HTTP) i fałszywym auth:
 * import jednej faktury po numerze KSeF przez istniejący sink i adapter {@link HanaKsefDocumentStore}.
 * Sprawdza liczbę wywołań SDK (metadata ×1, GET ×1, brak retry aplikacyjnego), trwały status i fail-closed przed auth.
 * Nie dowodzi kontraktu MF LIVE (filtr {@code ksefNumber}, semantyka daty w numerze) ani HANA Cloud.
 */
@SpringBootTest(classes = Application.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:ksef-in-05-manual;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "logging.level.root=WARN" })
@ActiveProfiles("test")
class InboundManualImportTest {
    static final SyncScope SCOPE = SyncScope.of(CONTEXT, KsefSubjectRole.BUYER);
    static final String STORE = HanaBlobObjectStorageClient.ENTITY;
    /** Data w numerze = 2026-11-01 (InboundTestSupport); zegar testu 2026-11-09. */
    static final String NUMBER = ksefNumber(1);

    @Autowired PersistenceService db;
    @Autowired CdsRuntime runtime;
    InboundPersistence persistence;
    CqnInboundDocumentRepository documents;
    KSeFClient sdk;
    KsefInboundAccess access;
    byte[] xml;

    @BeforeEach void reset() throws Exception {
        persistence = new InboundPersistence(db, runtime);
        documents = new CqnInboundDocumentRepository(persistence, CLOCK);
        persistence.tx(() -> {
            for (String entity : List.of(GRANTS, CONFLICTS, DOCUMENTS, CHECKPOINTS, LEASES, STORE)) { db.run(Delete.from(entity)); }
            return null;
        });
        sdk = mock(KSeFClient.class);
        xml = resource("fa3-purchase-synthetic.xml");
        when(sdk.getInvoice(anyString(), anyString())).thenReturn(xml);
        when(sdk.queryInvoiceMetadata(anyInt(), anyInt(), any(), any(), any())).thenReturn(response(false, false, null,
                sdkItem(metadataJson(NUMBER, "2026-11-02T09:00:00Z", hashOf(xml)))));
        access = mock(KsefInboundAccess.class);
        var session = mock(KsefInboundAccess.AccessSession.class);
        when(access.open()).thenReturn(session);
        when(session.token()).thenReturn(token());
    }

    InboundManualImport importer(Clock clock, KsefDocumentStore store) {
        return new InboundManualImport(SCOPE, clock, access, inboundClient(sdk), new CqnInboundDocumentRepository(persistence, clock),
                new CqnInboundSyncLeaseRepository(persistence, clock), store, new Fa3SchemaValidator());
    }

    KsefDocumentStore store(PersistenceService persistenceService) {
        return HanaKsefDocumentStore.create(persistenceService, runtime, EnumSet.of(KsefEnvironment.TEST),
                HanaKsefDocumentStore.DEFAULT_MAX_DOCUMENT_BYTES, 4L * HanaKsefDocumentStore.DEFAULT_MAX_DOCUMENT_BYTES,
                Duration.ofSeconds(5), CLOCK, d -> { }).forScope(DocumentScope.inbound(KsefEnvironment.TEST));
    }

    List<Map<String, Object>> rows(String entity) {
        return persistence.tx(() -> db.run(Select.from(entity)).list().stream()
                .<Map<String, Object>>map(HashMap<String, Object>::new).toList());
    }

    @Test void invalidOrFutureNumberFailsBeforeLeaseAuthAndHttp() throws Exception {
        for (String number : new String[]{null, "", "1111111111-20261101-000000000001-00", "../invoices",
                ksefNumber(SELLER_NIP, "20270101", "000000000001")}) {
            assertThrows(IllegalArgumentException.class, () -> importer(CLOCK, store(db)).importOne(number), String.valueOf(number));
        }
        verifyNoInteractions(sdk, access);
        assertTrue(rows(LEASES).isEmpty());
    }

    @Test void importsOneInvoiceWithOneFilteredMetadataQueryAndOneGetWithoutMovingCheckpoint() throws Exception {
        var result = importer(CLOCK, store(db)).importOne(NUMBER);
        assertEquals(InboundManualImport.Outcome.STORED, result.outcome());
        var filters = ArgumentCaptor.forClass(InvoiceQueryFilters.class);
        verify(sdk, times(1)).queryInvoiceMetadata(eq(0), eq(10), any(), filters.capture(), anyString());
        assertEquals(NUMBER, filters.getValue().getKsefNumber());
        assertEquals(Instant.parse("2026-10-31T00:00:00Z"), filters.getValue().getDateRange().getFrom().toInstant());
        assertEquals(NOW, filters.getValue().getDateRange().getTo().toInstant(), "okno przycięte do teraz");
        verify(sdk, times(1)).getInvoice(eq(NUMBER), anyString());
        verify(access, times(1)).open();
        var row = rows(DOCUMENTS).getFirst();
        assertEquals(result.documentId(), row.get("ID"));
        assertEquals(List.of("STORED", "PARSED", "VERIFIED"),
                List.of(row.get("ingestionStatus"), row.get("parseStatus"), row.get("integrityStatus")));
        assertArrayEquals(xml, store(db).get((String) row.get("xmlStorageRef")));
        assertEquals(List.of(CONTEXT_NIP, "BUYER"), List.of(rows(GRANTS).getFirst().get("contextNip"), rows(GRANTS).getFirst().get("subjectRole")));
        assertTrue(rows(CHECKPOINTS).isEmpty(), "import nie przesuwa HWM synchronizacji");
        assertEquals("WAITING", rows(LEASES).getFirst().get("lastCode"));
        assertFalse(result.toString().contains("ksefdoc"), "wynik bez referencji magazynu");
    }

    @Test void secondImportWithinCooldownIsBusyWithoutAuthAndLaterImportDoesNotDownloadAgain() throws Exception {
        importer(CLOCK, store(db)).importOne(NUMBER);
        assertEquals(InboundManualImport.Outcome.BUSY, importer(CLOCK, store(db)).importOne(NUMBER).outcome());
        verify(access, times(1)).open();
        var later = Clock.offset(CLOCK, Duration.ofHours(1));
        assertEquals(InboundManualImport.Outcome.STORED, importer(later, store(db)).importOne(NUMBER).outcome());
        verify(sdk, times(2)).queryInvoiceMetadata(anyInt(), anyInt(), any(), any(), any());
        verify(sdk, times(1)).getInvoice(anyString(), anyString());
        assertEquals(1, rows(DOCUMENTS).size());
        assertEquals(1, rows(GRANTS).size());
        assertEquals(1, rows(STORE).size());
    }

    @Test void emptyPageIsNotFoundWithoutTombstoneAndCanBeImportedAfterCooldown() throws Exception {
        var found = response(false, false, null, sdkItem(metadataJson(NUMBER, "2026-11-02T09:00:00Z", hashOf(xml))));
        when(sdk.queryInvoiceMetadata(anyInt(), anyInt(), any(), any(), any())).thenReturn(response(false, false, null));
        var missing = importer(CLOCK, store(db)).importOne(NUMBER);
        assertEquals(InboundManualImport.Outcome.NOT_FOUND, missing.outcome());
        assertNull(missing.documentId());
        verify(sdk, never()).getInvoice(anyString(), anyString());
        assertTrue(rows(DOCUMENTS).isEmpty());
        assertTrue(rows(GRANTS).isEmpty());
        assertTrue(rows(CHECKPOINTS).isEmpty());
        assertEquals("WAITING", rows(LEASES).getFirst().get("lastCode"));
        assertEquals(InboundManualImport.Outcome.BUSY, importer(CLOCK, store(db)).importOne(NUMBER).outcome());
        when(sdk.queryInvoiceMetadata(anyInt(), anyInt(), any(), any(), any())).thenReturn(found);
        assertEquals(InboundManualImport.Outcome.STORED,
                importer(Clock.offset(CLOCK, Duration.ofHours(1)), store(db)).importOne(NUMBER).outcome());
    }

    @ParameterizedTest
    @CsvSource({"2026-10-30T23:59:59Z, NOT_FOUND", "2026-10-31T00:00:00Z, STORED",
            "2026-12-01T00:00:00Z, STORED", "2026-12-01T00:00:01Z, NOT_FOUND"})
    void metadataWindowBoundsTheResult(String permanentStorage, InboundManualImport.Outcome expected) throws Exception {
        var timestamp = OffsetDateTime.parse(permanentStorage);
        var found = response(false, false, null, sdkItem(metadataJson(NUMBER, permanentStorage, hashOf(xml))));
        var empty = response(false, false, null);
        when(sdk.queryInvoiceMetadata(anyInt(), anyInt(), any(), any(), any())).thenAnswer(call -> {
            var range = call.<InvoiceQueryFilters>getArgument(3).getDateRange();
            assertEquals(OffsetDateTime.parse("2026-10-31T00:00:00Z"), range.getFrom());
            assertEquals(OffsetDateTime.parse("2026-12-01T00:00:00Z"), range.getTo());
            return timestamp.isBefore(range.getFrom()) || timestamp.isAfter(range.getTo()) ? empty : found;
        });
        var afterWindow = Clock.fixed(Instant.parse("2026-12-02T00:00:00Z"), ZoneOffset.UTC);
        assertEquals(expected, importer(afterWindow, store(db)).importOne(NUMBER).outcome());
        int stored = expected == InboundManualImport.Outcome.STORED ? 1 : 0;
        verify(sdk, times(stored)).getInvoice(eq(NUMBER), anyString());
        assertEquals(stored, rows(DOCUMENTS).size());
        assertTrue(rows(CHECKPOINTS).isEmpty());
    }

    @Test void ambiguousOrForeignMetadataIsRejectedWithoutGet() throws Exception {
        when(sdk.queryInvoiceMetadata(anyInt(), anyInt(), any(), any(), any())).thenReturn(response(true, false, null,
                sdkItem(metadataJson(NUMBER, "2026-11-02T09:00:00Z", hashOf(xml)))));
        assertEquals("KSEF_INBOUND_MANUAL_IMPORT_AMBIGUOUS",
                assertThrows(IllegalStateException.class, () -> importer(CLOCK, store(db)).importOne(NUMBER)).getMessage());
        when(sdk.queryInvoiceMetadata(anyInt(), anyInt(), any(), any(), any())).thenReturn(response(false, false, null,
                sdkItem(metadataJson(ksefNumber(2), "2026-11-02T09:00:00Z", hashOf(xml)))));
        assertEquals("KSEF_INBOUND_MANUAL_IMPORT_NUMBER_MISMATCH", assertThrows(IllegalStateException.class,
                () -> importer(Clock.offset(CLOCK, Duration.ofHours(1)), store(db)).importOne(NUMBER)).getMessage());
        verify(sdk, never()).getInvoice(anyString(), anyString());
        assertTrue(rows(DOCUMENTS).isEmpty());
    }

    @Test void failedXmlReadIsNotRetriedAndLeavesDurableStatus() throws Exception {
        when(sdk.getInvoice(anyString(), anyString())).thenThrow(apiError(503, Map.of()));
        assertThrows(InboundFailure.class, () -> importer(CLOCK, store(db)).importOne(NUMBER));
        verify(sdk, times(1)).getInvoice(anyString(), anyString());
        assertEquals("DOWNLOAD_FAILED", rows(DOCUMENTS).getFirst().get("lastCode"));
        assertTrue(rows(GRANTS).isEmpty());
        assertTrue(rows(STORE).isEmpty());
    }

    @Test void timeoutDoesNotTriggerAnotherGetOrAuthenticationAtApplicationLayer() throws Exception {
        when(sdk.getInvoice(anyString(), anyString())).thenThrow(new RuntimeException(new HttpTimeoutException("synthetic")));
        assertThrows(InboundFailure.class, () -> importer(CLOCK, store(db)).importOne(NUMBER));
        verify(sdk, times(1)).getInvoice(eq(NUMBER), anyString());
        verify(access, times(1)).open();
        assertEquals("DOWNLOAD_FAILED", rows(DOCUMENTS).getFirst().get("lastCode"));
        assertEquals("WAITING", rows(LEASES).getFirst().get("lastCode"));
        assertTrue(rows(GRANTS).isEmpty());
    }

    @Test void storageOutageKeepsStorageFailedStatusWithoutGrantThenLaterImportRecovers() throws Exception {
        PersistenceService down = mock(PersistenceService.class, invocation -> {
            throw new ServiceException(ErrorStatuses.SERVER_ERROR, "hana unavailable");
        });
        assertEquals("KSEF_INBOUND_DOCUMENT_STORE_FAILED",
                assertThrows(IllegalStateException.class, () -> importer(CLOCK, store(down)).importOne(NUMBER)).getMessage());
        assertEquals("STORAGE_FAILED", rows(DOCUMENTS).getFirst().get("ingestionStatus"));
        assertTrue(rows(GRANTS).isEmpty());
        assertEquals("WAITING", rows(LEASES).getFirst().get("lastCode"), "storage można ponowić później, bez BLOCKED");
        assertEquals(InboundManualImport.Outcome.STORED,
                importer(Clock.offset(CLOCK, Duration.ofHours(1)), store(db)).importOne(NUMBER).outcome());
        assertEquals("STORED", rows(DOCUMENTS).getFirst().get("ingestionStatus"));
    }

    @Test void ambiguousAuthenticationBlocksFurtherImportsWithoutHttp() throws Exception {
        when(access.open()).thenThrow(new KsefFailure("KSEF_REDEEM_IO", true, false, null));
        assertThrows(KsefFailure.class, () -> importer(CLOCK, store(db)).importOne(NUMBER));
        assertEquals("BLOCKED", rows(LEASES).getFirst().get("lastCode"));
        assertEquals(InboundManualImport.Outcome.BUSY,
                importer(Clock.offset(CLOCK, Duration.ofDays(1)), store(db)).importOne(NUMBER).outcome());
        verify(access, times(1)).open();
        verifyNoInteractions(sdk);
    }

    // ---- BETA-02: klienci per przebieg i fencing lease przed każdym HTTP ---------------------------------------

    InboundManualImport importer(Clock clock, InboundManualImport.RunClients clients) {
        return new InboundManualImport(SCOPE, clock, clients, new CqnInboundDocumentRepository(persistence, clock),
                new CqnInboundSyncLeaseRepository(persistence, clock), store(db), new Fa3SchemaValidator());
    }

    @Test void eachRunOpensFreshClientsWithAFenceBoundToTheOwnedLease() throws Exception {
        var opened = new java.util.concurrent.atomic.AtomicInteger();
        var result = importer(CLOCK, fence -> {
            opened.incrementAndGet();
            fence.run(); // właściciel lease: fencing przepuszcza (transport wywołuje go przed każdym żądaniem)
            return new InboundManualImport.Clients(access, inboundClient(sdk));
        }).importOne(NUMBER);
        assertEquals(InboundManualImport.Outcome.STORED, result.outcome());
        assertEquals(1, opened.get());
        verify(sdk, times(1)).getInvoice(eq(NUMBER), anyString());
    }

    @Test void lostLeaseRefusesAllHttpOfTheRun() throws Exception {
        var clients = (InboundManualImport.RunClients) fence -> {
            // Inny proces przejął lease po claim (symulacja: podmiana tokenu właściciela w DB).
            persistence.tx(() -> db.run(com.sap.cds.ql.Update.entity(LEASES)
                    .data(Map.of("claimToken", java.util.UUID.randomUUID().toString()))));
            fence.run(); // transport wywołuje fencing przed każdym requestem → tu pierwszy request zostałby odmówiony
            return new InboundManualImport.Clients(access, inboundClient(sdk));
        };
        var error = assertThrows(IllegalStateException.class, () -> importer(CLOCK, clients).importOne(NUMBER));
        assertEquals("KSEF_INBOUND_LEASE_LOST", error.getMessage());
        verifyNoInteractions(sdk, access);
        assertTrue(rows(DOCUMENTS).isEmpty());
    }
}
