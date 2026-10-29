package customer.cap_java.sap;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.sap.cds.services.request.RequestContext;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.sap.SapKsefResultWriteAdapter.Outcome;
import customer.cap_java.sap.SapKsefResultWriteAdapter.WriteFailure;

/**
 * CAP-04D, część LIVE: rzeczywisty HTTP WRITE do SAP H01 (akcja ApplyKsefResult). NIGDY nie uruchamiany
 * domyślnie ani automatycznie. Zapisuje numer KSeF w dedykowanej, syntetycznej fakturze SAP — operacja
 * trwała, bez cofnięcia z poziomu CAP. Nie wysyła niczego do KSeF.
 *
 * <p>Wszystkie warunki muszą być spełnione jednocześnie (brak któregokolwiek przy włączonej fladze = błąd, nie pominięcie):
 * <ul>
 *   <li>tag {@code live-sap-write} włączony: {@code -Dainv.excludedGroups=none},</li>
 *   <li>{@code AINV_H01_LIVE_WRITE=true},</li>
 *   <li>{@code AINV_H01_LIVE_WRITE_INVOICE_UUID} — UUID dedykowanej, dziewiczej (bez numeru KSeF) faktury OUTBOUND,</li>
 *   <li>{@code AINV_H01_LIVE_WRITE_INVOICE_NUMBER} — jej numer faktury (podwójna kontrola tożsamości),</li>
 *   <li>{@code AINV_H01_LIVE_WRITE_CONSENT=APPLY_KSEF_RESULT:<ten sam UUID>} — jawna zgoda na zapis w tej fakturze,</li>
 *   <li>Destination {@code AINV_H01} z principalem komunikacyjnym o ACTVT 16 wyłącznie dla właściwego NIP/CompanyCode
 *       (bez CRUD) — nadanie robi właściciel SAP, nie ten test.</li>
 * </ul>
 * Opcjonalnie: {@code ..._STALE_INVOICE_UUID} i {@code ..._RACE_INVOICE_UUID} (kolejne dziewicze faktury testowe) włączają
 * VERSION_CONFLICT i równoległe wywołania; {@code ..._CRUD_DENIAL=true} włącza próbę zwykłego UPDATE, która MUSI zostać
 * odmówiona (jeśli nie zostanie, zmieni dedykowaną fakturę — dlatego osobna flaga).
 */
@Tag("live-sap-write")
@EnabledIfEnvironmentVariable(named = "AINV_H01_LIVE_WRITE", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = { "ainv.sap-write.enabled=true",
                "cds.outbox.services.SapResultOutbox.startCollector=false",
                "cds.outbox.persistent.scheduler.allTenantsTask.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:writeback-live-write;DB_CLOSE_DELAY=-1" })
@ActiveProfiles("test")
public class SapKsefResultWriteLiveWriteTest {
    @Autowired SapKsefResultWriteAdapter adapter;
    @Autowired SapInvoiceRemoteAdapter reader;
    @Autowired CdsRuntime runtime;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("AinvoicerH01")
    com.sap.cds.services.cds.CqnService remote;

    @Test void livePersistedResultEtagIdempotencyConflictsAndRace() throws Exception {
        UUID invoice = consentedInvoice();
        var before = header(invoice);
        assertEquals("OUTBOUND", before.get("Direction"));
        assertEquals(invoice.toString(), String.valueOf(before.get("InvoiceUUID")).toLowerCase());
        assertEquals(System.getenv("AINV_H01_LIVE_WRITE_INVOICE_NUMBER"), before.get("InvoiceNumber"),
                "tożsamość faktury testowej");
        assertTrue(before.get("KsefNumber") == null || before.get("KsefNumber").toString().isBlank(),
                "faktura musi być dziewicza (bez numeru KSeF)");
        String nip = (String) before.get("SellerNip");
        String company = (String) before.get("CompanyCode");
        Instant version = Instant.parse(KsefInvoiceSnapshotRegistry.headerVersion(before));
        String number = nip + "-LIVETEST-" + UUID.randomUUID().toString().substring(0, 8);
        UUID requestA = UUID.randomUUID();

        // 9. odmowa przy niewłaściwym NIP / CompanyCode (przed jakimkolwiek zapisem)
        var wrongNip = assertThrows(WriteFailure.class, () -> adapter.apply(new SapKsefResultCommand(
                invoice, UUID.randomUUID(), number, "0000000000".equals(nip) ? "1111111111" : "0000000000",
                company, version)));
        assertTrue(wrongNip.getMessage().startsWith("SAP_HTTP_4"), wrongNip.getMessage());
        var wrongCompany = assertThrows(WriteFailure.class, () -> adapter.apply(new SapKsefResultCommand(
                invoice, UUID.randomUUID(), number, nip, "ZZZ9".equals(company) ? "ZZZ8" : "ZZZ9", version)));
        assertTrue(wrongCompany.getMessage().startsWith("SAP_HTTP_4"), wrongCompany.getMessage());
        assertNull(blankToNull(header(invoice).get("KsefNumber")), "odmowy nie mogą nic zapisać");

        // 1 + 2. pierwsze zastosowanie i niezależny GET po commit
        var first = adapter.apply(new SapKsefResultCommand(invoice, requestA, number, nip, company, version));
        assertEquals(Outcome.APPLIED, first.outcome());
        var persisted = header(invoice);
        assertEquals(number, persisted.get("KsefNumber"));
        assertEquals("TEST", persisted.get("KsefEnvironment"));
        assertEquals(requestA.toString(), String.valueOf(persisted.get("KsefWriteRequestId")).toLowerCase());
        assertNotNull(persisted.get("KsefRecordedAt"));
        Instant versionAfterApply = Instant.parse(KsefInvoiceSnapshotRegistry.headerVersion(persisted));

        // 3 + 4. identyczne ponowienie → ALREADY_APPLIED, ETag bez zmian (no-op)
        var again = adapter.apply(new SapKsefResultCommand(invoice, requestA, number, nip, company, version));
        assertEquals(Outcome.ALREADY_APPLIED, again.outcome());
        assertEquals(requestA, again.recordedRequestId());
        assertEquals(versionAfterApply, Instant.parse(KsefInvoiceSnapshotRegistry.headerVersion(header(invoice))));
        // nowy request ID z tym samym wynikiem → no-op z zachowaniem pierwotnego request ID
        var other = adapter.apply(new SapKsefResultCommand(invoice, UUID.randomUUID(), number, nip, company, version));
        assertEquals(Outcome.ALREADY_APPLIED, other.outcome());
        assertEquals(requestA, other.recordedRequestId());

        // 6 + 7. ten sam request ID z innym wynikiem; inny wynik przy nowym request ID
        assertEquals(Outcome.IDEMPOTENCY_CONFLICT, adapter.apply(new SapKsefResultCommand(
                invoice, requestA, number + "X", nip, company, version)).outcome());
        assertEquals(Outcome.RESULT_CONFLICT, adapter.apply(new SapKsefResultCommand(
                invoice, UUID.randomUUID(), number + "Y", nip, company, version)).outcome());
        assertEquals(number, header(invoice).get("KsefNumber"), "konflikty nie nadpisują wyniku");

        // Rekoncyliację po utracie odpowiedzi odwzorowują kroki 3 i 4 (ten sam request ID => ALREADY_APPLIED);
        // ścieżka workera i brak ponownej wysyłki do KSeF są sprawdzane w SapKsefResultSapContractMockTest.
    }

    @Test void liveStaleVersionIsReportedAsVersionConflictWithoutWriting() {
        String raw = System.getenv("AINV_H01_LIVE_WRITE_STALE_INVOICE_UUID");
        org.junit.jupiter.api.Assumptions.assumeTrue(raw != null && !raw.isBlank(), "opcjonalna faktura STALE nie podana");
        UUID invoice = UUID.fromString(raw);
        requireConsent(invoice, "STALE");
        var header = header(invoice);
        assertTrue(blankToNull(header.get("KsefNumber")) == null, "faktura STALE musi być dziewicza");
        Instant version = Instant.parse(KsefInvoiceSnapshotRegistry.headerVersion(header));
        var result = adapter.apply(new SapKsefResultCommand(invoice, UUID.randomUUID(),
                header.get("SellerNip") + "-LIVESTALE", (String) header.get("SellerNip"),
                (String) header.get("CompanyCode"), version.minusNanos(100)));
        assertEquals(Outcome.VERSION_CONFLICT, result.outcome());
        assertNull(blankToNull(header(invoice).get("KsefNumber")));
    }

    @Test void liveTwoParallelActionCallsHaveExactlyOneWinner() throws Exception {
        String raw = System.getenv("AINV_H01_LIVE_WRITE_RACE_INVOICE_UUID");
        org.junit.jupiter.api.Assumptions.assumeTrue(raw != null && !raw.isBlank(), "opcjonalna faktura RACE nie podana");
        UUID invoice = UUID.fromString(raw);
        requireConsent(invoice, "RACE");
        var header = header(invoice);
        assertNull(blankToNull(header.get("KsefNumber")), "faktura RACE musi być dziewicza");
        String nip = (String) header.get("SellerNip");
        String company = (String) header.get("CompanyCode");
        Instant version = Instant.parse(KsefInvoiceSnapshotRegistry.headerVersion(header));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Outcome> a = () -> { start.await(); return adapter.apply(new SapKsefResultCommand(invoice,
                    UUID.randomUUID(), nip + "-LIVERACE-A", nip, company, version)).outcome(); };
            Callable<Outcome> b = () -> { start.await(); return adapter.apply(new SapKsefResultCommand(invoice,
                    UUID.randomUUID(), nip + "-LIVERACE-B", nip, company, version)).outcome(); };
            var fa = pool.submit(a);
            var fb = pool.submit(b);
            start.countDown();
            var results = java.util.List.of(fa.get(60, TimeUnit.SECONDS), fb.get(60, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(o -> o == Outcome.APPLIED).count(), results.toString());
            assertTrue(results.stream().filter(o -> o != Outcome.APPLIED)
                    .allMatch(o -> o == Outcome.RESULT_CONFLICT || o == Outcome.VERSION_CONFLICT), results.toString());
        }
        String stored = (String) header(invoice).get("KsefNumber");
        assertTrue(stored.endsWith("LIVERACE-A") || stored.endsWith("LIVERACE-B"));
    }

    @Test @EnabledIfEnvironmentVariable(named = "AINV_H01_LIVE_WRITE_CRUD_DENIAL", matches = "true")
    void liveActionOnlyPrincipalCannotUseStandardCrud() {
        UUID invoice = consentedInvoice();
        var header = header(invoice);
        // Idempotentna treść (to samo SellerName); poprawny wynik to odmowa. Zapis udany = błąd uprawnień SAP.
        var update = com.sap.cds.ql.Update.entity(SapInvoiceRemoteAdapter.HEADERS)
                .data("SellerName", header.get("SellerName")).byId(invoice.toString());
        assertThrows(RuntimeException.class, () -> runtime.requestContext().systemUser().run(
                (RequestContext ctx) -> remote.run(update)),
                "Zwykły UPDATE principala tylko z ACTVT 16 musi zostać odmówiony");
    }

    // ---- bramki bezpieczeństwa ----------------------------------------------------------------------

    private UUID consentedInvoice() {
        String raw = System.getenv("AINV_H01_LIVE_WRITE_INVOICE_UUID");
        if (raw == null || raw.isBlank() || System.getenv("AINV_H01_LIVE_WRITE_INVOICE_NUMBER") == null) {
            throw new IllegalStateException("LIVE WRITE wymaga AINV_H01_LIVE_WRITE_INVOICE_UUID i _INVOICE_NUMBER");
        }
        UUID invoice = UUID.fromString(raw);
        requireConsent(invoice, "główna");
        return invoice;
    }

    private void requireConsent(UUID invoice, String label) {
        String consent = System.getenv("AINV_H01_LIVE_WRITE_CONSENT");
        // Zgoda dotyczy konkretnych UUID: jedna zmienna może wymieniać kilka, rozdzielonych przecinkiem.
        boolean granted = consent != null && java.util.Arrays.stream(consent.split(","))
                .anyMatch(item -> item.trim().equalsIgnoreCase("APPLY_KSEF_RESULT:" + invoice));
        if (!granted) {
            throw new IllegalStateException("Brak jawnej zgody LIVE WRITE dla faktury " + label
                    + ": AINV_H01_LIVE_WRITE_CONSENT=APPLY_KSEF_RESULT:<UUID>");
        }
    }

    private Map<String, Object> header(UUID invoice) {
        return reader.readInvoices().stream()
                .filter(i -> invoice.toString().equalsIgnoreCase(String.valueOf(i.header().get("InvoiceUUID"))))
                .findFirst().orElseThrow(() -> new AssertionError("faktura nie istnieje w SAP")).header();
    }

    private static Object blankToNull(Object value) {
        return value == null || value.toString().isBlank() ? null : value;
    }
}
