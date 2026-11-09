package customer.cap_java.ksef.inbound.sync;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

import customer.cap_java.ksef.inbound.MetadataQuery;
import customer.cap_java.ksef.inbound.MetadataSegment;

/**
 * Czysty planer okien synchronizacji przyrostowej według MF (pobieranie-faktur/hwm.md,
 * przyrostowe-pobieranie-faktur.md): data {@code PermanentStorage}, sortowanie rosnące,
 * {@code restrictToPermanentStorageHwmDate=true} („scenariusz 1 — tylko do HWM”), okna przyległe.
 *
 * <p>Reguły przesunięcia checkpointu ({@link #advance}):
 * <ol>
 *   <li>bez potwierdzenia, że wszystkie dokumenty okna są trwale przetworzone — {@code HOLD};</li>
 *   <li>{@code isTruncated=true} — kolejne okno od daty ostatniego rekordu ({@code lastSortDate});
 *       rekordy graniczne zostaną pobrane ponownie i odfiltrowane deduplikacją po numerze KSeF;
 *       brak postępu (ostatnia data = początek okna) — {@code HOLD}, aby nie zapętlić się na 10 000 rekordach
 *       z jedną datą;</li>
 *   <li>segment kompletny — kolejne okno od {@code permanentStorageHwmDate}, ale nie dalej niż jawne
 *       {@code dateRange.to} (inaczej pominęlibyśmy niezapytany przedział);</li>
 *   <li>brak HWM, cofnięcie, HWM w przyszłości (poza tolerancją zegara) — {@code HOLD};</li>
 *   <li>daty zawsze w UTC; checkpoint nigdy się nie cofa.</li>
 * </ol>
 * Planer nie wykonuje I/O i nie zapisuje stanu.
 */
public final class InvoiceSyncWindowPlanner {
    private final Clock clock;
    private final Duration maxWindow;
    private final Duration clockSkewTolerance;

    public InvoiceSyncWindowPlanner(Clock clock, Duration maxWindow, Duration clockSkewTolerance) {
        this.clock = Objects.requireNonNull(clock);
        if (maxWindow == null || maxWindow.isNegative() || maxWindow.isZero()
                || maxWindow.compareTo(MetadataQuery.MAX_RANGE) > 0) {
            throw new IllegalArgumentException("KSEF_INBOUND_MAX_WINDOW_INVALID");
        }
        if (clockSkewTolerance == null || clockSkewTolerance.isNegative()) {
            throw new IllegalArgumentException("KSEF_INBOUND_CLOCK_SKEW_INVALID");
        }
        this.maxWindow = maxWindow;
        this.clockSkewTolerance = clockSkewTolerance;
    }

    /** Okno maksymalnie 100 dni (limit MF), tolerancja zegara 5 minut. */
    public static InvoiceSyncWindowPlanner defaults(Clock clock) {
        return new InvoiceSyncWindowPlanner(clock, MetadataQuery.MAX_RANGE, Duration.ofMinutes(5));
    }

    /**
     * Okno do pobrania.
     *
     * @param from            początek (UTC), włącznie
     * @param to              koniec (UTC) albo {@code null} — „do HWM” wyznacza KSeF
     * @param expectedVersion wersja checkpointu, na której oparto okno (0 = brak checkpointu)
     */
    public record SyncWindow(SyncScope scope, OffsetDateTime from, OffsetDateTime to, long expectedVersion) {
        public MetadataQuery query() {
            return MetadataQuery.incremental(scope.role(), from, to);
        }
    }

    public enum AdvanceKind { ADVANCE, HOLD }

    /** Decyzja o checkpoincie: {@code next} tylko dla ADVANCE; {@code reason} opisuje HOLD. */
    public record Advance(AdvanceKind kind, OffsetDateTime next, String reason) {
        static Advance hold(String reason) {
            return new Advance(AdvanceKind.HOLD, null, reason);
        }
    }

    /**
     * @param initialFrom punkt startowy dla zakresu bez checkpointu (decyzja biznesowa, np. data uruchomienia
     *                    KSeF dla podatnika); wymagany tylko, gdy checkpoint nie istnieje
     */
    public SyncWindow plan(SyncScope scope, Optional<SyncCheckpoint> checkpoint, OffsetDateTime initialFrom) {
        Objects.requireNonNull(scope, "scope");
        if (checkpoint.isPresent() && !checkpoint.get().scope().equals(scope)) {
            throw new IllegalArgumentException("KSEF_INBOUND_CHECKPOINT_SCOPE_MISMATCH");
        }
        OffsetDateTime from = checkpoint.map(SyncCheckpoint::continuationFrom).orElse(initialFrom);
        if (from == null) {
            throw new IllegalArgumentException("KSEF_INBOUND_INITIAL_FROM_REQUIRED");
        }
        from = from.withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        if (from.isAfter(now.plus(clockSkewTolerance))) {
            throw new IllegalStateException("KSEF_INBOUND_CHECKPOINT_IN_FUTURE");
        }
        // Zakres > 100 dni jest odrzucany przez KSeF; długą zaległość nadrabiamy kolejnymi oknami.
        OffsetDateTime to = Duration.between(from, now).compareTo(maxWindow) > 0 ? from.plus(maxWindow) : null;
        return new SyncWindow(scope, from, to, checkpoint.map(SyncCheckpoint::version).orElse(0L));
    }

    /**
     * @param allPersisted {@code true} wyłącznie gdy każdy dokument segmentu został trwale przetworzony
     */
    public Advance advance(SyncWindow window, MetadataSegment segment, boolean allPersisted) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(segment, "segment");
        if (segment.query().dateType() != MetadataQuery.DateType.PERMANENT_STORAGE) {
            throw new IllegalArgumentException("KSEF_INBOUND_SEGMENT_NOT_INCREMENTAL");
        }
        if (!segment.query().from().equals(window.from())) {
            throw new IllegalArgumentException("KSEF_INBOUND_SEGMENT_WINDOW_MISMATCH");
        }
        if (!allPersisted) {
            return Advance.hold("PROCESSING_INCOMPLETE");
        }
        OffsetDateTime next;
        if (segment.truncated()) {
            if (segment.lastSortDate() == null) {
                return Advance.hold("TRUNCATED_WITHOUT_LAST_DATE");
            }
            next = segment.lastSortDate();
            if (!next.isAfter(window.from())) {
                return Advance.hold("TRUNCATED_NO_PROGRESS");
            }
        } else if (!segment.complete()) {
            return Advance.hold("SEGMENT_INCOMPLETE");
        } else if (segment.hwmDate() == null) {
            return Advance.hold("HWM_MISSING");
        } else {
            next = segment.hwmDate();
            if (window.to() != null && next.isAfter(window.to())) {
                next = window.to();
            }
            if (next.isBefore(window.from())) {
                return Advance.hold("HWM_REGRESSION");
            }
            if (next.equals(window.from())) {
                return Advance.hold("HWM_UNCHANGED");
            }
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (next.isAfter(now.plus(clockSkewTolerance))) {
            return Advance.hold("CONTINUATION_IN_FUTURE");
        }
        return new Advance(AdvanceKind.ADVANCE, next.withOffsetSameInstant(ZoneOffset.UTC), null);
    }
}
