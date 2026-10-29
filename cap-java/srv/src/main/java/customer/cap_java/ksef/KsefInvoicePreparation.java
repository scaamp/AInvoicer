package customer.cap_java.ksef;

import java.util.UUID;
import org.springframework.stereotype.Component;
import customer.cap_java.ksef.fa3.SapInvoiceFa3Mapper;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;
import customer.cap_java.sap.SapInvoiceSnapshot;

/** Zaufany producent: snapshot SAP + jawne dane stron → generator B → snapshot CAP → outbox A. */
@Component
public class KsefInvoicePreparation {
    private final KsefFa3Documents documents;
    private final KsefInvoiceSnapshotRegistry registry;
    private final KsefSubmissionWorker worker;

    public KsefInvoicePreparation(KsefFa3Documents documents, KsefInvoiceSnapshotRegistry registry, KsefSubmissionWorker worker) {
        this.documents = documents;
        this.registry = registry;
        this.worker = worker;
    }

    /**
     * Wywołujący pobiera autoryzowany SAP Remote OData PRZED transakcją. Tutaj, w jednym ChangeSet,
     * powstają migawka i zlecenie. Brak adresu/JST/GV lub błędny NIP seeda blokuje przygotowanie.
     * Nie zapisuje SAP, nie wykonuje HTTP i nie nadaje prawa do NIP przekazanego w payload.
     */
    public PreparedSubmission enqueue(UUID operationId, SapInvoiceSnapshot sap, SapInvoiceFa3Mapper.PartyData party) {
        var document = documents.prepare(sap, party);
        byte[] exact = document.xml();
        UUID snapshot = registry.register(sap, String.valueOf(sap.header().get("SellerNip")),
                String.valueOf(sap.header().get("CompanyCode")), "TEST", document.schemaVersion(), exact);
        worker.enqueue(operationId, snapshot, exact);
        return new PreparedSubmission(operationId, snapshot, document.sha256Hex(), document.byteLength());
    }

    public record PreparedSubmission(UUID operationId, UUID snapshotId, String sha256, int byteLength) { }
}
