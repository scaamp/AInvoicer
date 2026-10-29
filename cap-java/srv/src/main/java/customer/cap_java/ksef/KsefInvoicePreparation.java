package customer.cap_java.ksef;

import java.util.UUID;
import org.springframework.stereotype.Component;
import customer.cap_java.ksef.fa3.Fa3Document;
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
     * Migawka ma pochodzenie UNVERIFIED — taki wynik nie trafi do RAP przez writeback.
     */
    public PreparedSubmission enqueue(UUID operationId, SapInvoiceSnapshot sap, SapInvoiceFa3Mapper.PartyData party) {
        return enqueue(operationId, sap, documents.prepare(sap, party), KsefInvoiceSnapshotRegistry.SOURCE_UNVERIFIED);
    }

    /**
     * Wariant dla requestSend: dokument wygenerowano z odczytu RAP wykonanego przez sam CAP (przed
     * transakcją), więc po potwierdzonym ACCEPTED wynik może trafić do RAP ApplyKsefResult. Te same
     * bajty trafiają do migawki (SHA-256) i do zlecenia wysyłki — XML nie jest generowany ponownie.
     */
    public PreparedSubmission enqueueVerifiedFromRap(UUID operationId, SapInvoiceSnapshot sap, Fa3Document document) {
        return enqueue(operationId, sap, document, KsefInvoiceSnapshotRegistry.SOURCE_SAP_RAP);
    }

    private PreparedSubmission enqueue(UUID operationId, SapInvoiceSnapshot sap, Fa3Document document, String source) {
        byte[] exact = document.xml();
        UUID snapshot = registry.register(sap, String.valueOf(sap.header().get("SellerNip")),
                String.valueOf(sap.header().get("CompanyCode")), "TEST", document.schemaVersion(), exact, source);
        worker.enqueue(operationId, snapshot, exact);
        return new PreparedSubmission(operationId, snapshot, document.sha256Hex(), document.byteLength());
    }

    public record PreparedSubmission(UUID operationId, UUID snapshotId, String sha256, int byteLength) { }
}
