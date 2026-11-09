package customer.cap_java.ksef.inbound;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQueryDateRange;
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQueryDateType;
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQueryFilters;

/**
 * Parametry {@code POST /invoices/query/metadata} w zakresie potrzebnym INBOUND.
 *
 * <p>Ograniczenia z oficjalnego OpenAPI KSeF 2.0 (zweryfikowane 2026-11-09): {@code pageSize} 10–250,
 * zakres dat maksymalnie 100 dni, {@code restrictToPermanentStorageHwmDate} dotyczy wyłącznie
 * {@code dateType = PermanentStorage}. Sortowanie jest zawsze rosnące — wymaga tego scenariusz przyrostowy
 * MF i od tego zależy wyznaczanie punktu kontynuacji po {@code isTruncated}.
 * Daty są normalizowane do UTC przed wysłaniem.
 */
public record MetadataQuery(KsefSubjectRole role, DateType dateType, OffsetDateTime from, OffsetDateTime to,
        boolean restrictToHwm, int pageSize) {
    public static final int MIN_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 250;
    public static final Duration MAX_RANGE = Duration.ofDays(100);

    public enum DateType {
        ISSUE(InvoiceQueryDateType.ISSUE),
        INVOICING(InvoiceQueryDateType.INVOICING),
        PERMANENT_STORAGE(InvoiceQueryDateType.PERMANENTSTORAGE);

        private final InvoiceQueryDateType sdk;

        DateType(InvoiceQueryDateType sdk) {
            this.sdk = sdk;
        }
    }

    public MetadataQuery {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(dateType, "dateType");
        Objects.requireNonNull(from, "from");
        if (pageSize < MIN_PAGE_SIZE || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("KSEF_INBOUND_PAGE_SIZE_INVALID");
        }
        if (restrictToHwm && dateType != DateType.PERMANENT_STORAGE) {
            throw new IllegalArgumentException("KSEF_INBOUND_HWM_REQUIRES_PERMANENT_STORAGE");
        }
        from = from.withOffsetSameInstant(ZoneOffset.UTC);
        if (to != null) {
            to = to.withOffsetSameInstant(ZoneOffset.UTC);
            if (!to.isAfter(from)) {
                throw new IllegalArgumentException("KSEF_INBOUND_RANGE_EMPTY");
            }
            if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
                throw new IllegalArgumentException("KSEF_INBOUND_RANGE_TOO_LONG");
            }
        }
    }

    /** Zapytanie przyrostowe zalecane przez MF: PermanentStorage, rosnąco, ograniczone do HWM, max strona. */
    public static MetadataQuery incremental(KsefSubjectRole role, OffsetDateTime from, OffsetDateTime to) {
        return new MetadataQuery(role, DateType.PERMANENT_STORAGE, from, to, true, MAX_PAGE_SIZE);
    }

    InvoiceQueryFilters toSdkFilters() {
        var filters = new InvoiceQueryFilters();
        filters.setSubjectType(role.sdkType());
        filters.setDateRange(new InvoiceQueryDateRange(dateType.sdk, from, to, restrictToHwm ? Boolean.TRUE : null));
        return filters;
    }
}
