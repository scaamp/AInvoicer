package customer.cap_java.sap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sap.cds.Result;
import com.sap.cds.Row;
import com.sap.cds.ql.cqn.CqnSelect;
import com.sap.cds.services.cds.CqnService;

/** Integracyjny test adaptera CQN na granicy CAP Remote Service. */
class SapInvoiceRemoteAdapterIntegrationTest {

	private static final List<String> HEADER_IDS = List.of(
		"56b09c12-4ee5-1fd1-b4ac-56570f4d14c8",
		"56b09c12-4ee5-1fd1-b4ac-56570f4d34c8",
		"56b09c12-4ee5-1fd1-b4ac-56570f4d54c8",
		"56b09c12-4ee5-1fd1-b4ac-56570f4d74c8");

	@Test
	void readsFourSapHeadersNineItemsAndSixReferencesWithoutChangingUuids() {
		CqnService remote = mock(CqnService.class);
		Result headerResult = result(headers());
		Result itemResult = result(children("ItemUUID", new int[] { 2, 3, 2, 2 }, "56b09c12-4ee5-1fd1-b4ac-56570f4d94c8"));
		Result referenceResult = result(children("RefUUID", new int[] { 1, 1, 2, 2 }, "56b09c12-4ee5-1fd1-b4ac-56570f4eb4c8"));
		when(remote.run(any(CqnSelect.class)))
			.thenReturn(headerResult)
			.thenReturn(itemResult)
			.thenReturn(referenceResult);

		List<SapInvoiceSnapshot> invoices = new SapInvoiceRemoteAdapter(remote).readInvoices();

		assertEquals(4, invoices.size());
		assertEquals(9, invoices.stream().mapToInt(invoice -> invoice.items().size()).sum());
		assertEquals(6, invoices.stream().mapToInt(invoice -> invoice.references().size()).sum());
		assertEquals(HEADER_IDS.get(0), invoices.get(0).header().get("InvoiceUUID").toString());
		assertTrue(invoices.get(0).header().containsKey("PostingDate"));
		assertNull(invoices.get(0).header().get("PostingDate"));
		assertEquals("56b09c12-4ee5-1fd1-b4ac-56570f4d94c8",
			invoices.get(0).items().get(0).get("ItemUUID").toString());
		assertEquals(HEADER_IDS.get(0), invoices.get(0).items().get(0).get("InvoiceUUID").toString());
		assertEquals("56b09c12-4ee5-1fd1-b4ac-56570f4eb4c8",
			invoices.get(0).references().get(0).get("RefUUID").toString());
		assertFalse(invoices.get(0).header().containsKey("id"), "SAP UUID must not be replaced by a CAP-generated ID");
		verify(remote, times(3)).run(any(CqnSelect.class));
	}

	private List<Map<String, Object>> headers() {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int index = 0; index < HEADER_IDS.size(); index++) {
			Map<String, Object> header = new HashMap<>();
			header.put("InvoiceUUID", UUID.fromString(HEADER_IDS.get(index)));
			header.put("InvoiceNumber", "AINV-TEST-SEED-2026-" + index);
			if (index == 0) {
				header.put("PostingDate", null);
			}
			rows.add(header);
		}
		return rows;
	}

	private List<Map<String, Object>> children(String key, int[] counts, String firstChildId) {
		List<Map<String, Object>> rows = new ArrayList<>();
		int childIndex = 0;
		for (int parent = 0; parent < counts.length; parent++) {
			for (int child = 0; child < counts[parent]; child++) {
				String id = childIndex++ == 0 ? firstChildId : UUID.randomUUID().toString();
				rows.add(Map.of(key, UUID.fromString(id), "InvoiceUUID", UUID.fromString(HEADER_IDS.get(parent))));
			}
		}
		return rows;
	}

	private Result result(List<Map<String, Object>> rows) {
		Result result = mock(Result.class);
		List<Row> cdsRows = rows.stream().map(values -> {
			Row row = mock(Row.class);
			when(row.get(any())).thenAnswer(invocation -> values.get(invocation.getArgument(0)));
			when(row.size()).thenReturn(values.size());
			when(row.entrySet()).thenReturn(values.entrySet());
			return row;
		}).toList();
		when(result.list()).thenReturn(cdsRows);
		return result;
	}
}
