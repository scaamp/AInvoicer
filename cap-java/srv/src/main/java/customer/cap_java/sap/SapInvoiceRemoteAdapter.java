package customer.cap_java.sap;

import static com.sap.cds.ql.CQL.get;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.sap.cds.Result;
import com.sap.cds.ql.Predicate;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.cqn.CqnSelect;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.request.RequestContext;
import com.sap.cds.services.runtime.CdsRuntime;

/** Read-only adapter do zweryfikowanego RAP OData V4, wywoływany przez CAP Remote Service. */
@Component
public class SapInvoiceRemoteAdapter {

	static final String MODEL = "com.sap.gateway.srvd_a2x.zapi_ainv_inv_h_o4.v0001";
	static final String HEADERS = MODEL + ".ZC_AINV_INV_H";
	private static final String ITEMS = MODEL + ".ZC_AINV_INV_I";
	private static final String REFERENCES = MODEL + ".ZC_AINV_INV_REF";

	private static final List<String> HEADER_COLUMNS = List.of(
		"InvoiceUUID", "InvoiceNumber", "OriginProcess", "Direction", "InvoiceKind", "InvoiceStatus",
		"CompanyCode", "LocalLastChangedAt", "KsefNumber", "KsefEnvironment", "KsefRecordedAt", "KsefWriteRequestId",
		"IssueDate", "PostingDate", "DueDate", "SellerNip", "SellerName", "BuyerNip",
		"BuyerName", "NetAmount", "TaxAmount", "GrossAmount", "CurrencyCode");
	private static final List<String> ITEM_COLUMNS = List.of(
		"ItemUUID", "InvoiceUUID", "ItemNo", "MaterialId", "Description", "Quantity", "UnitCode",
		"UnitPrice", "NetAmount", "TaxRate", "TaxCategory", "TaxAmount", "GrossAmount", "CurrencyCode",
		"LocalLastChangedAt");
	private static final List<String> REFERENCE_COLUMNS = List.of(
		"RefUUID", "InvoiceUUID", "SourceSystem", "SourceModule", "ReferenceRole", "SourceDocNo",
		"SourceFiscalYear", "SourceItemNo");

	private final CqnService remote;
	private final CdsRuntime runtime;

	@Autowired
	public SapInvoiceRemoteAdapter(@Qualifier("AinvoicerH01") CqnService remote, CdsRuntime runtime) {
		this.remote = remote;
		this.runtime = runtime;
	}

	/** Konstruktor testowy: bez runtime, wywołania idą bezpośrednio do podanego serwisu. */
	SapInvoiceRemoteAdapter(CqnService remote) {
		this(remote, null);
	}

	/**
	 * Odczytuje nagłówki i oba zbiory dzieci osobnymi CQN; nie zapisuje ani nie replikuje danych SAP.
	 * Model zdalny wymaga internal-user, więc odczyt (np. z workera bez użytkownika) idzie w kontekście
	 * systemowym; dane SAP nie są wtedy filtrowane per użytkownik CAP — autoryzację robi wywołujący.
	 */
	public List<SapInvoiceSnapshot> readInvoices() {
		if (runtime == null) {
			return doReadInvoices();
		}
		return runtime.requestContext().systemUser().run((RequestContext ctx) -> doReadInvoices());
	}

	/**
	 * Odczyt jednej faktury (nagłówek po kluczu + pozycje + referencje) przez communication usera.
	 * Brak faktury = pusty Optional. Wywołujący sprawdza zakres NIP/CompanyCode użytkownika CAP —
	 * DCL RAP dla communication usera nie izoluje danych per użytkownik końcowy.
	 */
	public java.util.Optional<SapInvoiceSnapshot> readInvoice(UUID invoiceId) {
		if (runtime == null) {
			return doReadInvoice(invoiceId);
		}
		return runtime.requestContext().systemUser().run((RequestContext ctx) -> doReadInvoice(invoiceId));
	}

	private java.util.Optional<SapInvoiceSnapshot> doReadInvoice(UUID invoiceId) {
		List<Map<String, Object>> headers = rows(remote.run(Select.from(HEADERS)
			.columns(HEADER_COLUMNS.toArray(String[]::new)).where(get("InvoiceUUID").eq(invoiceId))));
		if (headers.isEmpty()) {
			return java.util.Optional.empty();
		}
		if (headers.size() > 1 || !invoiceId.equals(uuid(headers.get(0).get("InvoiceUUID")))) {
			throw new IllegalStateException("SAP_INVOICE_KEY_AMBIGUOUS");
		}
		List<UUID> ids = List.of(invoiceId);
		return java.util.Optional.of(new SapInvoiceSnapshot(headers.get(0),
			rows(remote.run(selectChildren(ITEMS, ITEM_COLUMNS, ids))),
			rows(remote.run(selectChildren(REFERENCES, REFERENCE_COLUMNS, ids)))));
	}

	private List<SapInvoiceSnapshot> doReadInvoices() {
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
		// Łańcuch `eq ... or` zamiast `in` (OData 4.01) - nie zakładamy wsparcia `in` w RAP V4 H01.
		Predicate filter = invoiceIds.stream().<Predicate>map(id -> get("InvoiceUUID").eq(id))
			.reduce(Predicate::or).orElseThrow();
		return Select.from(entity).columns(columns.toArray(String[]::new)).where(filter);
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
