package customer.cap_java.sap;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only migawka faktury SAP z oryginalnymi kluczami UUID. */
public record SapInvoiceSnapshot(
	Map<String, Object> header,
	List<Map<String, Object>> items,
	List<Map<String, Object>> references) {

	public SapInvoiceSnapshot {
		header = immutableCopy(header);
		items = items.stream().map(SapInvoiceSnapshot::immutableCopy).toList();
		references = references.stream().map(SapInvoiceSnapshot::immutableCopy).toList();
	}

	private static Map<String, Object> immutableCopy(Map<String, Object> source) {
		// OData nullable daty i timestampy mogą mieć wartość null.
		return Collections.unmodifiableMap(new LinkedHashMap<>(source));
	}
}
