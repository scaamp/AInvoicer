package customer.cap_java.ksef;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.services.persistence.PersistenceService;
import customer.cap_java.ksef.fa3.Fa3Invoice;
import customer.cap_java.ksef.fa3.Fa3InvoiceXmlService;
import customer.cap_java.ksef.fa3.SapInvoiceFa3Mapper;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;
import customer.cap_java.sap.SapInvoiceSnapshot;

/** Rzeczywisty generator B, schema, CAP/H2 i worker A; transport SDK i storage mockowane. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "ainv.ksef.enabled=true", "ainv.ksef.nip=1234563218",
        "cds.outbox.services.KsefSubmissionOutbox.startCollector=false",
        "cds.outbox.persistent.scheduler.allTenantsTask.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:fa3-worker-integrated;DB_CLOSE_DELAY=-1" })
@ActiveProfiles("test")
class KsefFa3IntegrationTest {
    @Autowired KsefInvoicePreparation preparation;
    @Autowired KsefSubmissionWorker worker;
    @Autowired PersistenceService db;
    @Autowired KsefFa3Documents fa3;
    @MockitoBean KsefAuthentication authentication;
    @MockitoBean KsefOnlineSession session;
    @MockitoBean KsefDocumentStore store;

    @Test void generatedBytesHashSnapshotAndSdkInputAreIdentical() {
        UUID operation = UUID.randomUUID();
        var sap = synthetic(operation);
        worker.tx(() -> {
            db.run(Insert.into(KsefSubmissionWorker.OPERATIONS).entry(Map.of("ID", operation.toString(),
                    "sapDocumentRef", operation.toString(), "taxpayerNip", "1234563218", "direction", "OUTBOUND",
                    "environment", "TEST", "operationType", "SEND_INVOICE", "status", "NEW", "idempotencyKey", operation.toString())));
            return null;
        });
        var exactBytes = new AtomicReference<byte[]>();
        when(store.put(any())).thenAnswer(call -> {
            byte[] bytes = call.getArgument(0);
            if (exactBytes.get() == null) { exactBytes.set(bytes.clone()); }
            return "document.xml";
        });
        when(store.get(anyString())).thenAnswer(call -> exactBytes.get().clone());
        var result = worker.tx(() -> preparation.enqueue(operation, sap, party()));
        byte[] generated = exactBytes.get();
        fa3.verify(generated);
        assertEquals(KsefInvoiceSnapshotRegistry.sha256Hex(generated), result.sha256());
        var snapshot = db.run(Select.from(KsefSubmissionWorker.SNAPSHOTS).byId(result.snapshotId().toString())).single();
        assertEquals(result.sha256(), snapshot.get("xmlSha256"));
        assertEquals(generated.length, snapshot.get("xmlByteLength"));
        when(authentication.refreshIfNeeded(any())).thenAnswer(call -> call.getArgument(0));
        when(session.open(any(), any())).thenReturn("session-ref");
        when(session.send(anyString(), any(), any(), any())).thenAnswer(call -> {
            assertArrayEquals(generated, call.getArgument(1));
            return "invoice-ref";
        });
        when(session.status(anyString(), anyString(), any())).thenReturn(KsefSubmissionWorkerTest.status(200, result.sha256()));
        when(session.sessionStatus(anyString(), any())).thenReturn(200);
        when(session.upoReady(anyString(), any())).thenReturn(true);
        when(session.upo(anyString(), anyString(), any())).thenReturn("<Potwierdzenie/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        worker.process(operation);
        assertEquals("COMPLETED", worker.status(operation).get("state"));
        assertEquals(result.sha256(), db.run(Select.from(KsefSubmissionWorker.OPERATIONS).byId(operation.toString()))
                .single().get("confirmedXmlSha256"));
        verify(session, times(1)).send(anyString(), any(), any(), any());
    }

    @Test void malformedOrFa2XmlCannotReachTransport() {
        assertThrows(KsefFailure.class, () -> fa3.verify("<Faktura>".getBytes()));
        assertThrows(KsefFailure.class, () -> fa3.verify("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///missing'>]><x>&e;</x>".getBytes()));
        verifyNoInteractions(session, authentication);
    }

    static SapInvoiceSnapshot synthetic(UUID id) {
        Instant version = Instant.parse("2026-10-29T12:00:00Z");
        return new SapInvoiceSnapshot(Map.ofEntries(Map.entry("InvoiceUUID", id.toString()), Map.entry("Direction", "OUTBOUND"),
                Map.entry("InvoiceKind", "STANDARD"), Map.entry("CompanyCode", "1000"), Map.entry("CurrencyCode", "PLN"),
                Map.entry("SellerNip", "1234563218"), Map.entry("BuyerNip", "9876543210"), Map.entry("SellerName", "Synthetic seller"),
                Map.entry("BuyerName", "Synthetic buyer"), Map.entry("InvoiceNumber", "CAP-FA3-" + id),
                Map.entry("IssueDate", LocalDate.parse("2026-10-29")), Map.entry("LocalLastChangedAt", version),
                Map.entry("NetAmount", new BigDecimal("10.00")), Map.entry("TaxAmount", new BigDecimal("2.30")),
                Map.entry("GrossAmount", new BigDecimal("12.30"))),
                List.of(Map.ofEntries(Map.entry("InvoiceUUID", id.toString()), Map.entry("ItemUUID", UUID.randomUUID().toString()),
                        Map.entry("ItemNo", "000010"), Map.entry("Description", "Synthetic service"), Map.entry("UnitCode", "EA"),
                        Map.entry("Quantity", BigDecimal.ONE), Map.entry("UnitPrice", new BigDecimal("10.00")),
                        Map.entry("NetAmount", new BigDecimal("10.00")), Map.entry("TaxAmount", new BigDecimal("2.30")),
                        Map.entry("TaxRate", new BigDecimal("23")), Map.entry("TaxCategory", "VAT23"),
                        Map.entry("LocalLastChangedAt", version))), List.of());
    }

    static SapInvoiceFa3Mapper.PartyData party() {
        return new SapInvoiceFa3Mapper.PartyData(Instant.parse("2026-10-29T12:00:00Z"),
                new Fa3Invoice.Address("PL", "Testowa 1", null), new Fa3Invoice.Address("PL", "Testowa 2", null), false, false);
    }
}
