package customer.cap_java.ksef;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import com.sap.cds.ql.Select;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.UserInfo;

import customer.cap_java.ksef.fa3.Fa3Document;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;
import customer.cap_java.sap.SapInvoiceRemoteAdapter;
import customer.cap_java.sap.SapInvoiceSnapshot;

/**
 * E2E-01A: wewnętrzny harness TEST, poza artefaktem aplikacji i bez endpointu OData.
 * Używa wyłącznie SAP READ, SELECT stanu CAP i generatora FA(3). Nie ma zależności
 * od preparation/registry, workera, outboxa, storage, SDK ani adaptera SAP WRITE.
 */
final class SapFa3Preflight {
    record ApprovedInvoice(UUID id, String sellerNip, String companyCode,
            String headerVersion, String itemsFingerprint) { }

    record Evidence(Fa3Document document, String headerVersion, String itemsFingerprint) { }

    private final SapInvoiceRemoteAdapter sap;
    private final PersistenceService db;
    private final KsefPartyDataProvider parties;
    private final KsefFa3Documents documents;

    SapFa3Preflight(SapInvoiceRemoteAdapter sap, PersistenceService db,
            KsefPartyDataProvider parties, KsefFa3Documents documents) {
        this.sap = sap;
        this.db = db;
        this.parties = parties;
        this.documents = documents;
    }

    /**
     * Zatwierdzony UUID/zakres/wersje są obowiązkowe; brak prawa zatrzymuje przed SAP READ.
     * Ponowny READ po generowaniu wykrywa drift podczas próby. Wynik jest migawką kontroli,
     * nie rezerwacją ani gwarancją niezmienności po zakończeniu. Transport pozostaje OFF.
     */
    Evidence run(ApprovedInvoice approved, UserInfo user) {
        require(user != null && user.isAuthenticated() && user.hasRole("KsefOperator"), "PREFLIGHT_FORBIDDEN");
        var nips = user.getAttributeValues("taxpayerNips");
        var companies = user.getAttributeValues("companyCodes");
        require(nips != null && companies != null && !(nips.stream().distinct().count() > 1
                && companies.stream().distinct().count() > 1), "PREFLIGHT_AMBIGUOUS_SCOPE");
        require(nips.contains(approved.sellerNip()) && companies.contains(approved.companyCode()), "PREFLIGHT_FORBIDDEN");
        requireUnused(approved.id());
        var snapshot = read(approved.id());
        var header = snapshot.header();
        require(approved.id().toString().equalsIgnoreCase(text(header, "InvoiceUUID")), "PREFLIGHT_UUID_MISMATCH");
        require(approved.sellerNip().equals(header.get("SellerNip")), "PREFLIGHT_FOREIGN_NIP");
        require(approved.companyCode().equals(header.get("CompanyCode")), "PREFLIGHT_FOREIGN_COMPANY");
        require("NEW".equals(header.get("InvoiceStatus")), "PREFLIGHT_BUSINESS_STATUS");
        require(text(header, "KsefNumber").isBlank(), "PREFLIGHT_ALREADY_RECORDED");
        require(approved.headerVersion().equals(KsefInvoiceSnapshotRegistry.headerVersion(header))
                && approved.itemsFingerprint().equals(KsefInvoiceSnapshotRegistry.itemsFingerprint(snapshot.items())),
                "PREFLIGHT_VERSION_CHANGED");
        // Mapper nie kontroluje waluty/brutto pozycji ani ich przypisania: harness odrzuca takie dane.
        for (var item : snapshot.items()) {
            require(approved.id().toString().equalsIgnoreCase(text(item, "InvoiceUUID")), "PREFLIGHT_ITEM_SCOPE");
            require("PLN".equals(item.get("CurrencyCode")), "PREFLIGHT_ITEM_CURRENCY");
            require(amount(item, "NetAmount").add(amount(item, "TaxAmount"))
                    .compareTo(amount(item, "GrossAmount")) == 0, "PREFLIGHT_ITEM_GROSS");
        }
        require(snapshot.references().stream().allMatch(ref -> approved.id().toString()
                .equalsIgnoreCase(text(ref, "InvoiceUUID"))), "PREFLIGHT_REFERENCE_SCOPE");
        var party = parties.forInvoice(header).orElseThrow(() -> new IllegalStateException("PREFLIGHT_PARTY_DATA_MISSING"));
        var document = documents.prepare(snapshot, party);
        documents.verify(document.xml());
        require(snapshot.equals(read(approved.id())), "PREFLIGHT_VERSION_CHANGED_DURING_READ");
        requireUnused(approved.id());
        return new Evidence(document, approved.headerVersion(), approved.itemsFingerprint());
    }

    private SapInvoiceSnapshot read(UUID id) {
        return sap.readInvoice(id).orElseThrow(() -> new IllegalStateException("PREFLIGHT_NOT_FOUND"));
    }

    /** Tylko SELECT, bez lock/rezerwacji; dowolny historyczny SEND (także REJECTED) blokuje pierwszą wysyłkę. */
    private void requireUnused(UUID id) {
        require(db.run(Select.from(KsefSendRequestHandler.RESERVATIONS)
                .columns("operationId").where(r -> r.get("invoiceUUID").eq(id.toString())
                        .and(r.get("environment").eq("TEST"))).limit(1)).first().isEmpty(), "PREFLIGHT_RESERVED");
        require(db.run(Select.from(KsefSubmissionWorker.OPERATIONS).columns("ID")
                .where(o -> o.get("sapDocumentRef").eq(id.toString()).and(o.get("environment").eq("TEST"))
                        .and(o.get("operationType").eq("SEND_INVOICE"))).limit(1)).first().isEmpty(), "PREFLIGHT_PREVIOUS_SEND");
    }

    private static BigDecimal amount(Map<String, Object> row, String key) {
        return new BigDecimal(text(row, key));
    }

    private static String text(Map<String, Object> row, String key) {
        return row.get(key) == null ? "" : row.get(key).toString();
    }

    private static void require(boolean condition, String code) {
        if (!condition) { throw new IllegalStateException(code); }
    }
}
