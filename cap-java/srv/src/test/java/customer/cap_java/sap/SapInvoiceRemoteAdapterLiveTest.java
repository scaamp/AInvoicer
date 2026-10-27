package customer.cap_java.sap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Test LIVE: realny odczyt SAP H01 przez CAP RemoteService (bez mocków). Wymaga zmiennej
 * AINV_H01_LIVE=true oraz Destination AINV_H01 w zmiennej środowiskowej `destinations`
 * (patrz docs/journal CAP-03). Poświadczeń nie ma w repozytorium ani w logach testu.
 * Uruchomienie: mvn -pl srv verify -Dainv.excludedGroups=none -Dtest=SapInvoiceRemoteAdapterLiveTest
 */
@Tag("live-sap")
@EnabledIfEnvironmentVariable(named = "AINV_H01_LIVE", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SapInvoiceRemoteAdapterLiveTest {

	@Autowired
	private SapInvoiceRemoteAdapter adapter;

	@Test
	void readsSeedFromSapH01ThroughRemoteService() {
		List<SapInvoiceSnapshot> invoices = adapter.readInvoices();

		assertEquals(4, invoices.size());
		assertEquals(9, invoices.stream().mapToInt(i -> i.items().size()).sum());
		assertEquals(6, invoices.stream().mapToInt(i -> i.references().size()).sum());

		Set<UUID> ids = invoices.stream().map(i -> UUID.fromString(i.header().get("InvoiceUUID").toString()))
			.collect(Collectors.toSet());
		assertEquals(4, ids.size());
		for (SapInvoiceSnapshot invoice : invoices) {
			UUID id = UUID.fromString(invoice.header().get("InvoiceUUID").toString());
			assertTrue(invoice.items().stream().allMatch(r -> id.toString().equals(r.get("InvoiceUUID").toString())));
			assertTrue(invoice.references().stream().allMatch(r -> id.toString().equals(r.get("InvoiceUUID").toString())));
		}
	}
}
