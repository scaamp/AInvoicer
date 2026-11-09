package customer.cap_java.ksef.inbound.sync;

import static customer.cap_java.ksef.inbound.InboundTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import customer.cap_java.ksef.inbound.KsefInvoiceMetadata;
import customer.cap_java.ksef.inbound.KsefSubjectRole;
import customer.cap_java.ksef.inbound.MetadataQuery;
import customer.cap_java.ksef.inbound.MetadataSegment;
import customer.cap_java.ksef.inbound.sync.InvoiceSyncWindowPlanner.AdvanceKind;
import customer.cap_java.ksef.inbound.sync.InvoiceSyncWindowPlanner.SyncWindow;

/** Czysta logika okien HWM: brak I/O, zegar stały 2026-11-09T12:00Z. */
class InvoiceSyncWindowPlannerTest {
    private static final SyncScope SCOPE = SyncScope.of(CONTEXT, KsefSubjectRole.BUYER);
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-11-01T00:00:00Z");
    private final InvoiceSyncWindowPlanner planner = InvoiceSyncWindowPlanner.defaults(CLOCK);

    @Test
    void firstWindowStartsAtInitialFromAndIsOpenEndedToHwm() {
        SyncWindow window = planner.plan(SCOPE, Optional.empty(), OffsetDateTime.parse("2026-11-01T01:00:00+01:00"));
        assertEquals(FROM, window.from());
        assertNull(window.to());
        assertEquals(0, window.expectedVersion());
        MetadataQuery query = window.query();
        assertEquals(MetadataQuery.DateType.PERMANENT_STORAGE, query.dateType());
        assertTrue(query.restrictToHwm());
        assertEquals(KsefSubjectRole.BUYER, query.role());
    }

    @Test
    void checkpointDrivesNextWindowAndLongBacklogIsSplitInto100DayWindows() {
        var checkpoint = new SyncCheckpoint(SCOPE, OffsetDateTime.parse("2026-05-01T00:00:00Z"), 7, Instant.EPOCH);
        SyncWindow window = planner.plan(SCOPE, Optional.of(checkpoint), null);
        assertEquals(OffsetDateTime.parse("2026-05-01T00:00:00Z"), window.from());
        assertEquals(OffsetDateTime.parse("2026-08-09T00:00:00Z"), window.to());
        assertEquals(7, window.expectedVersion());
    }

    @Test
    void invalidPlanningInputsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> planner.plan(SCOPE, Optional.empty(), null));
        assertThrows(IllegalStateException.class, () -> planner.plan(SCOPE, Optional.empty(),
                OffsetDateTime.parse("2026-11-09T12:30:00Z")));
        var foreign = new SyncCheckpoint(SyncScope.of(CONTEXT, KsefSubjectRole.SELLER), FROM, 1, Instant.EPOCH);
        assertThrows(IllegalArgumentException.class, () -> planner.plan(SCOPE, Optional.of(foreign), null));
    }

    @Test
    void completeSegmentAdvancesToHwmInUtc() {
        SyncWindow window = planner.plan(SCOPE, Optional.empty(), FROM);
        var advance = planner.advance(window, segment(window, false, true, "2026-11-09T13:00:00+02:00", null), true);
        assertEquals(AdvanceKind.ADVANCE, advance.kind());
        assertEquals(OffsetDateTime.parse("2026-11-09T11:00:00Z"), advance.next());
        assertEquals(ZoneOffset.UTC, advance.next().getOffset());
    }

    @Test
    void truncatedSegmentContinuesFromLastRecordDate() {
        SyncWindow window = planner.plan(SCOPE, Optional.empty(), FROM);
        var advance = planner.advance(window, segment(window, true, false, "2026-11-09T10:00:00Z",
                "2026-11-04T08:00:00Z"), true);
        assertEquals(AdvanceKind.ADVANCE, advance.kind());
        assertEquals(OffsetDateTime.parse("2026-11-04T08:00:00Z"), advance.next(),
                "obcięcie: od LastPermanentStorageDate, nie od HWM");
    }

    @Test
    void truncatedSegmentWithoutProgressIsHeld() {
        SyncWindow window = planner.plan(SCOPE, Optional.empty(), FROM);
        var advance = planner.advance(window, segment(window, true, false, "2026-11-09T10:00:00Z",
                FROM.toString()), true);
        assertEquals(AdvanceKind.HOLD, advance.kind());
        assertEquals("TRUNCATED_NO_PROGRESS", advance.reason());
    }

    @Test
    void missingHwmOrUnpersistedWindowNeverMovesCheckpoint() {
        SyncWindow window = planner.plan(SCOPE, Optional.empty(), FROM);
        assertEquals("HWM_MISSING", planner.advance(window, segment(window, false, true, null, null), true).reason());
        assertEquals("PROCESSING_INCOMPLETE",
                planner.advance(window, segment(window, false, true, "2026-11-09T10:00:00Z", null), false).reason());
        assertEquals("SEGMENT_INCOMPLETE",
                planner.advance(window, segment(window, false, false, "2026-11-09T10:00:00Z", null), true).reason());
    }

    @Test
    void hwmNeverRegressesNeverSkipsExplicitEndAndIsNotInFuture() {
        SyncWindow window = planner.plan(SCOPE, Optional.empty(), FROM);
        assertEquals("HWM_REGRESSION",
                planner.advance(window, segment(window, false, true, "2026-10-31T00:00:00Z", null), true).reason());
        assertEquals("HWM_UNCHANGED",
                planner.advance(window, segment(window, false, true, FROM.toString(), null), true).reason());
        assertEquals("CONTINUATION_IN_FUTURE",
                planner.advance(window, segment(window, false, true, "2026-11-09T13:00:00Z", null), true).reason());

        var old = new SyncCheckpoint(SCOPE, OffsetDateTime.parse("2026-05-01T00:00:00Z"), 1, Instant.EPOCH);
        SyncWindow bounded = planner.plan(SCOPE, Optional.of(old), null);
        var capped = planner.advance(bounded, segment(bounded, false, true, "2026-09-01T00:00:00Z", null), true);
        assertEquals(bounded.to(), capped.next(), "HWM za jawnym dateRange.to nie może pominąć niezapytanego zakresu");
    }

    @Test
    void segmentMustBelongToTheWindow() {
        SyncWindow window = planner.plan(SCOPE, Optional.empty(), FROM);
        var other = MetadataQuery.incremental(KsefSubjectRole.BUYER, FROM.plusHours(1), null);
        var segment = new MetadataSegment(other, List.of(), 1, 0, false, true, OffsetDateTime.parse("2026-11-09T10:00:00Z"), null);
        assertThrows(IllegalArgumentException.class, () -> planner.advance(window, segment, true));
    }

    private static MetadataSegment segment(SyncWindow window, boolean truncated, boolean complete, String hwm, String last) {
        return new MetadataSegment(window.query(), List.<KsefInvoiceMetadata>of(), 1, 0, truncated, complete,
                hwm == null ? null : OffsetDateTime.parse(hwm).withOffsetSameInstant(ZoneOffset.UTC),
                last == null ? null : OffsetDateTime.parse(last));
    }
}
