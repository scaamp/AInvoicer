package customer.cap_java.ksef.inbound;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Wynik przejścia wszystkich stron jednego zestawu filtrów {@code POST /invoices/query/metadata}.
 *
 * @param query             zapytanie, które wykonano
 * @param invoices          metadane bez duplikatów numeru KSeF, w kolejności rosnącej daty
 * @param pagesFetched      liczba pobranych stron
 * @param duplicatesSkipped identyczne powtórzenia tego samego numeru KSeF (np. przesunięcie stron)
 * @param truncated         KSeF zwrócił {@code isTruncated=true} (limit 10 000) — segment NIE jest kompletny
 * @param complete          ostatnia strona miała {@code hasMore=false} i nie było obcięcia
 * @param hwmDate           {@code permanentStorageHwmDate} (stały dla wszystkich stron; UTC; może być null)
 * @param lastSortDate      data sortowania ostatniego rekordu (PermanentStorage/Invoicing) — punkt kontynuacji
 *                          po obcięciu; {@code null} dla zapytań po dacie Issue lub pustego wyniku
 */
public record MetadataSegment(MetadataQuery query, List<KsefInvoiceMetadata> invoices, int pagesFetched,
        int duplicatesSkipped, boolean truncated, boolean complete, OffsetDateTime hwmDate,
        OffsetDateTime lastSortDate) {
    public MetadataSegment {
        invoices = List.copyOf(invoices);
        if (truncated && complete) {
            throw new IllegalArgumentException("KSEF_INBOUND_SEGMENT_STATE");
        }
    }
}
