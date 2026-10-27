package customer.cap_java.sap;

import static com.sap.cds.ql.CQL.get;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.sap.cds.Result;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.cqn.CqnSelect;
import com.sap.cds.services.cds.CqnService;

/** Read-only adapter do zweryfikowanego RAP OData V4, wywoływany przez CAP Remote Service. */
@Component
public class SapInvoiceRemoteAdapter {

	private static final String HEADERS = "AinvoicerH01.ZC_AINV_INV_H";
	private static final String ITEMS = "AinvoicerH01.ZC_AINV_INV_I";
	private static final String REFERENCES = "AinvoicerH01.ZC_AINV_INV_REF";

	private static final List<String> HEADER_COLUMNS = List.of(
		"InvoiceUUID", "InvoiceNumber", "OriginProcess", "Direction", "InvoiceKind", "InvoiceStatus",
		"CompanyCode", "IssueDate", "PostingDate", "DueDate", "SellerNip", "SellerName", "BuyerNip",
		"BuyerName", "NetAmount", "TaxAmount", "GrossAmount", "CurrencyCode");
	private static final List<String> ITEM_COLUMNS = List.of(
		"ItemUUID", "InvoiceUUID", "ItemNo", "MaterialId", "Description", "Quantity", "UnitCode",
		"UnitPrice", "NetAmount", "TaxRate", "TaxCategory", "TaxAmount", "GrossAmount", "CurrencyCode");
	private static final List<String> REFERENCE_COLUMNS = List.of(
		"RefUUID", "InvoiceUUID", "SourceSystem", "SourceModule", "ReferenceRole", "SourceDocNo",
		"SourceFiscalYear", "SourceItemNo");

	private final CqnService remote;

	public SapInvoiceRemoteAdapter(@Qualifier("AinvoicerH01") CqnService remote) {
		this.remote = remote;
	}

	/** Odczytuje nagłówki i oba zbiory dzieci osobnymi CQN; nie zapisuje ani nie lokalnie replikuje danych SAP. */
	public List<SapInvoiceSnapshot> readInvoices() {
		List<Map<String, Object>> headers = rows(remote.run(select(HEADERS, HEADER_COLUMNS)));
		if (headers.isEmpty()) {
			return List.of();
		}

		List<UUID> invoiceIds = headers.stream().map(row -> uuid(row.get("InvoiceUUID"))).toList();
		List<Map<String, Object>> items = rows(remote.run(selectChildren(ITEMS, ITEM_COLUMNS, invoiceIds)));
		List<Map<String, Object>> references = rows(remote.run(selectChildren(REFERENCES, REFERENCE_COLUMNS, invoiceIds)));

		Map<UUID, List<Map<String, Object>>> itemsByInvoice = groupByInvoice(items);
		Map<UUID, List<Map<String, Object>>> referencesByInvoice = groupByInvoice(references);
		return headers.stream().map(header -> {
			UUID invoiceId = uuid(header.get("InvoiceUUID"));
			return new SapInvoiceSnapshot(header,
				itemsByInvoice.getOrDefault(invoiceId, List.of()),
				referencesByInvoice.getOrDefault(invoiceId, List.of()));
		}).toList();
	}

	private CqnSelect select(String entity, List<String> columns) {
		return Select.from(entity).columns(columns.toArray(String[]::new));
	}

	private CqnSelect selectChildren(String entity, List<String> columns, List<UUID> invoiceIds) {
		return Select.from(entity).columns(columns.toArray(String[]::new))
			.where(get("InvoiceUUID").in(invoiceIds));
	}

	private List<Map<String, Object>> rows(Result result) {
		List<Map<String, Object>> rows = new ArrayList<>();
		rows.addAll(result.list());
		return rows;
	}

	private Map<UUID, List<Map<String, Object>>> groupByInvoice(List<Map<String, Object>> rows) {
		Map<UUID, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
		for (Map<String, Object> row : rows) {
			grouped.computeIfAbsent(uuid(row.get("InvoiceUUID")), ignored -> new ArrayList<>()).add(row);
		}
		return grouped;
	}

	private UUID uuid(Object value) {
		if (value instanceof UUID uuid) {
			return uuid;
		}
		return UUID.fromString(String.valueOf(value));
	}
}
