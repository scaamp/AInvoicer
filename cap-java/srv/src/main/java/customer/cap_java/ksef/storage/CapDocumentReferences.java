package customer.cap_java.ksef.storage;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.sap.cds.ql.Select;
import com.sap.cds.services.persistence.PersistenceService;

/**
 * Zbiór referencji dokumentów zapisanych w CAP — wejście audytu orphan.
 * Czyta wyłącznie kolumny referencji przez CQN; nie zwraca ich do żadnego publicznego API.
 * Ograniczenie: pełny zbiór w pamięci, wystarczający dla raportu operacyjnego, nie dla milionów wierszy.
 */
public final class CapDocumentReferences {
    static final String INBOUND_DOCUMENTS = "customer.cap_java.KsefInboundDocuments";
    static final String INVOICES = "customer.cap_java.KsefInvoices";
    static final String SUBMISSIONS = "customer.cap_java.KsefSubmissions";

    private CapDocumentReferences() { }

    public static Set<String> collect(PersistenceService db) {
        Set<String> references = new HashSet<>();
        add(db, INBOUND_DOCUMENTS, List.of("xmlStorageRef"), references);
        add(db, INVOICES, List.of("xmlStorageRef", "upoStorageRef"), references);
        add(db, SUBMISSIONS, List.of("xmlStorageRef", "upoStorageRef"), references);
        return Set.copyOf(references);
    }

    private static void add(PersistenceService db, String entity, List<String> columns, Set<String> target) {
        for (String column : columns) {
            db.run(Select.from(entity).columns(c -> c.get(column)).where(c -> c.get(column).isNotNull()))
                    .forEach(row -> target.add((String) row.get(column)));
        }
    }
}
