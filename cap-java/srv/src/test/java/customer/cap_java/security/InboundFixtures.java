package customer.cap_java.security;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.sap.cds.ql.Delete;
import com.sap.cds.ql.Insert;
import com.sap.cds.services.persistence.PersistenceService;

/** Wyłącznie syntetyczne fixture testowe; brak wywołań KSeF i magazynu XML. */
public final class InboundFixtures {
    public static final String A = "1234567802", B = "9876543210";
    public static final String OWN = "10000000-0000-4000-8000-000000000001";
    public static final String SHARED = "10000000-0000-4000-8000-000000000002";
    public static final String FOREIGN = "10000000-0000-4000-8000-000000000003";
    public static final String NO_GRANT = "10000000-0000-4000-8000-000000000004";
    public static final String DEMO = "10000000-0000-4000-8000-000000000005";
    public static final String CONFLICT = "10000000-0000-4000-8000-000000000006";
    public static final String UNSUPPORTED = "10000000-0000-4000-8000-000000000007";
    public static final String UNKNOWN = "10000000-0000-4000-8000-000000000008";
    public static final String SELLER = "10000000-0000-4000-8000-000000000009";
    private InboundFixtures() { }

    public static void seed(PersistenceService db) {
        db.run(Delete.from("customer.cap_java.KsefInboundAccessGrants"));
        db.run(Delete.from("customer.cap_java.KsefInboundDocuments"));
        document(db, OWN, "FV/O'Brien/INT-02", "TEST", "PARSED", "VERIFIED", "9999999999999999.99", A);
        grant(db, OWN, B, "SELLER"); // Dwa granty nie mogą podwoić listy/count użytkownika A.
        document(db, SHARED, "FV/SHARED", "TEST", "PARSED", "VERIFIED", "123.45", A);
        grant(db, SHARED, B, "BUYER");
        document(db, FOREIGN, "FOREIGN-B", "TEST", "PARSED", "VERIFIED", "50.00", B);
        document(db, NO_GRANT, "NO-GRANT", "TEST", "PARSED", "VERIFIED", "50.00", null);
        document(db, DEMO, "OTHER-ENV", "DEMO", "PARSED", "VERIFIED", "50.00", A);
        document(db, CONFLICT, "FV/CONFLICT", "TEST", "PARSED", "CONFLICT", "30.01", A);
        document(db, UNSUPPORTED, "FV/UNSUPPORTED", "TEST", "UNSUPPORTED_SCHEMA", "VERIFIED", null, A);
        document(db, UNKNOWN, "FV/UNKNOWN", "TEST", "NOT_PARSED", "PENDING", null, A);
        document(db, SELLER, "SELLER-ONLY", "TEST", "PARSED", "VERIFIED", "20.00", null);
        grant(db, SELLER, A, "SELLER");
    }

    private static void document(PersistenceService db, String id, String number, String env, String parse,
            String integrity, String amount, String nip) {
        var row = new HashMap<String, Object>();
        row.put("ID", id);
        row.put("environment", env);
        row.put("invoiceNumber", number);
        row.put("ksefNumber", A + "-20261110-" + id.substring(24) + "-AA");
        row.put("sellerNip", A);
        row.put("sellerName", "Synthetic supplier INT-02");
        row.put("buyerIdentifierType", "Nip");
        row.put("buyerIdentifierValue", A); // Widoczność zależy od grantu, nigdy od tego pola.
        row.put("issueDate", "2026-11-09");
        row.put("acquisitionDate", Instant.parse("2026-11-10T10:00:00Z"));
        row.put("currency", "PLN");
        row.put("grossAmount", amount == null ? null : new BigDecimal(amount));
        row.put("invoiceType", "VAT");
        row.put("formSystemCode", "FA (3)");
        row.put("sha256", "0".repeat(64));
        row.put("xmlStorageRef", "test-fixture-no-xml-provider");
        row.put("ingestionStatus", "NOT_PARSED".equals(parse) ? "PENDING" : "STORED");
        row.put("parseStatus", parse);
        row.put("integrityStatus", integrity);
        if (id.equals(UNKNOWN)) {
            for (var field : new String[]{"invoiceType", "formSystemCode", "sellerName", "issueDate", "acquisitionDate"}) {
                row.put(field, null);
            }
        }
        db.run(Insert.into("customer.cap_java.KsefInboundDocuments").entry(row));
        if (nip != null) { grant(db, id, nip, "BUYER"); }
    }

    private static void grant(PersistenceService db, String id, String nip, String role) {
        db.run(Insert.into("customer.cap_java.KsefInboundAccessGrants").entry(Map.of("ID", UUID.randomUUID().toString(),
                "documentId", id, "contextNip", nip, "subjectRole", role, "firstSeenAt", Instant.now())));
    }
}
