package customer.cap_java.ksef.inbound;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Jedna strona odpowiedzi {@code POST /invoices/query/metadata} po walidacji i mapowaniu.
 *
 * @param invoices  zmapowane metadane (kolejność odpowiedzi KSeF)
 * @param hasMore   {@code hasMore} z odpowiedzi — istnieją kolejne strony
 * @param truncated {@code isTruncated} — osiągnięto limit 10 000 rekordów dla zestawu filtrów
 * @param hwmDate   {@code permanentStorageHwmDate} w UTC; {@code null} dla dat Issue/Invoicing lub gdy brak
 */
public record MetadataPage(List<KsefInvoiceMetadata> invoices, boolean hasMore, boolean truncated, OffsetDateTime hwmDate) {
    public MetadataPage {
        invoices = List.copyOf(invoices);
    }
}
