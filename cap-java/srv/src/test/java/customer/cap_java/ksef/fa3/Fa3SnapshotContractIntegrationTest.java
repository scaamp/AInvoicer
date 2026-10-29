package customer.cap_java.ksef.fa3;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.sap.cds.ql.Select;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.RequestContext;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;
import customer.cap_java.sap.SapInvoiceSnapshot;

/**
 * B4 — kontrakt integracyjny dla workera wysyłki (H2, bez KSeF, bez SAP HTTP):
 * generuj → zweryfikuj → pobierz bajty → hash → zarejestruj migawkę CAP → „wyślij” te same bajty.
 * Wysyłka jest tu wyłącznie lokalnym przechwyceniem bajtów; test nie tworzy potwierdzeń KSeF.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = { "cds.outbox.services.SapResultOutbox.startCollector=false",
                "cds.outbox.persistent.scheduler.allTenantsTask.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:fa3-snapshot-contract;DB_CLOSE_DELAY=-1" })
@ActiveProfiles("test")
class Fa3SnapshotContractIntegrationTest {
    private static final String SNAPSHOTS = "customer.cap_java.KsefInvoiceSnapshots";

    @Autowired Fa3InvoiceXmlService fa3;
    @Autowired KsefInvoiceSnapshotRegistry registry;
    @Autowired PersistenceService db;
    @Autowired CdsRuntime runtime;

    @Test
    void preparedBytesAreRegisteredAndSentUnchanged() {
        SapInvoiceSnapshot sap = Fa3Fixtures.syntheticSdBill();

        // 1-2. Generowanie i walidacja (domena + oficjalny XSD).
        Fa3Document doc = fa3.prepare(new SapInvoiceFa3Mapper().map(sap, Fa3Fixtures.partyData()));
        // 3-4. Dokładne bajty i ich SHA-256.
        byte[] xml = doc.xml();
        String hash = doc.sha256Hex();
        // 5. Migawka CAP w transakcji; schemaVersion z dokumentu ("FA(3)").
        UUID snapshotId = tx(() -> registry.register(sap, Fa3Fixtures.SELLER_NIP, "1000", "TEST",
                doc.schemaVersion(), xml));
        // 6. Stub wysyłki: SDK otrzymałoby dokładnie te bajty (tu tylko przechwycenie, bez sieci).
        byte[] sent = doc.xml();

        var stored = db.run(Select.from(SNAPSHOTS).byId(snapshotId.toString())).single();
        assertEquals(hash, stored.get("xmlSha256"));
        assertEquals(xml.length, stored.get("xmlByteLength"));
        assertEquals("FA(3)", stored.get("schemaVersion"));
        assertArrayEquals(xml, sent);
        assertEquals(hash, KsefInvoiceSnapshotRegistry.sha256Hex(sent));
        // Ponowne przygotowanie tych samych danych daje ten sam dokument → ta sama migawka (idempotencja).
        Fa3Document again = fa3.prepare(new SapInvoiceFa3Mapper().map(sap, Fa3Fixtures.partyData()));
        assertEquals(snapshotId, tx(() -> registry.register(sap, Fa3Fixtures.SELLER_NIP, "1000", "TEST",
                again.schemaVersion(), again.xml())));
        // Bajty odczytane z magazynu można ponownie zweryfikować bez zmiany skrótu.
        assertEquals(hash, fa3.verify(sent).sha256Hex());
    }

    private <T> T tx(Supplier<T> work) {
        return runtime.requestContext().systemUser().run((RequestContext ctx) ->
                runtime.changeSetContext().run(cs -> { return work.get(); }));
    }
}
