package customer.cap_java.ksef.inbound.persistence;

import static customer.cap_java.ksef.inbound.persistence.InboundPersistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sap.cds.ql.Select;

import customer.cap_java.ksef.inbound.InboundInvoiceOverview;
import customer.cap_java.ksef.inbound.sync.SyncScope;

/** Wewnętrzny read model; INT-02 musi wyznaczyć dozwolony scope z uwierzytelnionej tożsamości. */
public final class CqnInboundOverviewRepository {
    private final InboundPersistence persistence;

    public CqnInboundOverviewRepository(InboundPersistence persistence) { this.persistence = persistence; }

    /** CQN filtruje grant i środowisko w DB przed paginacją; brak grantu daje pustą listę. */
    public List<InboundInvoiceOverview> find(SyncScope allowedScope, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 250) { throw new IllegalArgumentException("KSEF_INBOUND_PAGE_INVALID"); }
        return persistence.tx(() -> persistence.db.run(Select.from(DOCUMENTS).where(d ->
                d.get("environment").eq(allowedScope.environment().name()).and(d.get("ID").in(
                        Select.from(GRANTS).columns("documentId").where(g ->
                                g.get("contextNip").eq(allowedScope.contextNip())
                                .and(g.get("subjectRole").eq(allowedScope.role().name()))))))
                .orderBy(d -> d.get("ID").asc()).limit(limit, offset)).list().stream()
                .map(CqnInboundOverviewRepository::overview).toList());
    }

    private static InboundInvoiceOverview overview(Map<String, Object> row) {
        Object amount = row.get("grossAmount");
        return new InboundInvoiceOverview(UUID.fromString((String) row.get("ID")), text(row, "environment"),
                text(row, "ksefNumber"), text(row, "invoiceNumber"),
                row.get("issueDate") == null ? null : LocalDate.parse(row.get("issueDate").toString()),
                row.get("acquisitionDate") == null ? null : instant(row.get("acquisitionDate")),
                text(row, "sellerNip"), text(row, "sellerName"), text(row, "buyerIdentifierType"),
                text(row, "buyerIdentifierValue"), text(row, "currency"),
                amount == null ? null : new BigDecimal(amount.toString()).toPlainString(),
                null, text(row, "invoiceType"), text(row, "formSystemCode"),
                text(row, "ingestionStatus"), text(row, "parseStatus"), text(row, "integrityStatus"),
                text(row, "sapPostingStatus"), CqnInboundDocumentRepository.isStored(row));
    }

    private static String text(Map<String, Object> row, String key) { return (String) row.get(key); }
}
