package customer.cap_java.ksef.inbound;

import static customer.cap_java.ksef.inbound.InboundFailure.Category.*;
import static customer.cap_java.ksef.inbound.InboundTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.http.HttpHeaders;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

import customer.cap_java.ksef.inbound.fa3.SecureFa3InvoiceParser;
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient;
import pl.akmf.ksef.sdk.client.model.KsefApiException;
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceMetadata;
import pl.akmf.ksef.sdk.client.model.invoice.QueryInvoiceMetadataResponse;
import pl.akmf.ksef.sdk.client.model.util.SortOrder;

/** Paginacja, obcięcie, spójność, retry i integralność pobrania — bez sieci (mock interfejsu SDK). */
class InvoiceRetrievalServiceTest {
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-11-01T00:00:00Z");
    private static final MetadataQuery QUERY = new MetadataQuery(KsefSubjectRole.BUYER,
            MetadataQuery.DateType.PERMANENT_STORAGE, FROM, null, true, 10);
    private static final String HWM = "2026-11-09T10:00:00Z";

    private final KSeFClient sdk = mock(KSeFClient.class);
    private final List<Duration> sleeps = new ArrayList<>();
    private final InvoiceRetrievalService service = new InvoiceRetrievalService(inboundClient(sdk),
            new ReadRetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(10), CLOCK, sleeps::add));

    @Test
    void paginationCollectsAllPagesWithoutLossInOrder() throws Exception {
        when(sdk.queryInvoiceMetadata(eq(0), eq(10), eq(SortOrder.ASC), any(), eq(FAKE_JWT)))
                .thenReturn(response(true, false, HWM, item(1, "2026-11-02T00:00:00Z"), item(2, "2026-11-02T00:00:01Z")));
        when(sdk.queryInvoiceMetadata(eq(1), eq(10), eq(SortOrder.ASC), any(), eq(FAKE_JWT)))
                .thenReturn(response(true, false, HWM, item(3, "2026-11-03T00:00:00Z")));
        when(sdk.queryInvoiceMetadata(eq(2), eq(10), eq(SortOrder.ASC), any(), eq(FAKE_JWT)))
                .thenReturn(response(false, false, HWM, item(4, "2026-11-04T00:00:00Z")));

        MetadataSegment segment = service.fetchSegment(CONTEXT, token(), QUERY);

        assertTrue(segment.complete());
        assertFalse(segment.truncated());
        assertEquals(3, segment.pagesFetched());
        assertEquals(List.of(ksefNumber(1), ksefNumber(2), ksefNumber(3), ksefNumber(4)),
                segment.invoices().stream().map(KsefInvoiceMetadata::ksefNumber).toList());
        assertEquals(OffsetDateTime.parse(HWM), segment.hwmDate());
        assertEquals(OffsetDateTime.parse("2026-11-04T00:00:00Z"), segment.lastSortDate());
        verify(sdk, times(3)).queryInvoiceMetadata(anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void truncatedResultIsNotComplete() throws Exception {
        when(sdk.queryInvoiceMetadata(eq(0), anyInt(), any(), any(), any()))
                .thenReturn(response(true, true, HWM, item(1, "2026-11-02T00:00:00Z"), item(2, "2026-11-05T12:00:00Z")));

        MetadataSegment segment = service.fetchSegment(CONTEXT, token(), QUERY);

        assertTrue(segment.truncated());
        assertFalse(segment.complete());
        assertEquals(OffsetDateTime.parse("2026-11-05T12:00:00Z"), segment.lastSortDate());
        verify(sdk, times(1)).queryInvoiceMetadata(anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void emptyResultIsCompleteAndKeepsHwm() throws Exception {
        when(sdk.queryInvoiceMetadata(eq(0), anyInt(), any(), any(), any())).thenReturn(response(false, false, HWM));
        MetadataSegment segment = service.fetchSegment(CONTEXT, token(), QUERY);
        assertTrue(segment.complete());
        assertTrue(segment.invoices().isEmpty());
        assertNull(segment.lastSortDate());
        assertEquals(OffsetDateTime.parse(HWM), segment.hwmDate());
    }

    @Test
    void inconsistentPagesRejectTheWholeSegment() throws Exception {
        when(sdk.queryInvoiceMetadata(eq(0), anyInt(), any(), any(), any()))
                .thenReturn(response(true, false, HWM, item(1, "2026-11-02T00:00:00Z")));
        when(sdk.queryInvoiceMetadata(eq(1), anyInt(), any(), any(), any()))
                .thenReturn(response(false, false, "2026-11-09T10:00:01Z", item(2, "2026-11-03T00:00:00Z")));
        assertCategory(CORRUPT_RESPONSE, "HWM_UNSTABLE");

        reset(sdk);
        when(sdk.queryInvoiceMetadata(eq(0), anyInt(), any(), any(), any()))
                .thenReturn(response(false, false, HWM, item(1, "2026-11-03T00:00:00Z"), item(2, "2026-11-02T00:00:00Z")));
        assertCategory(CORRUPT_RESPONSE, "ORDER");

        reset(sdk);
        when(sdk.queryInvoiceMetadata(eq(0), anyInt(), any(), any(), any()))
                .thenReturn(response(false, false, HWM, item(1, "2026-10-31T23:59:59Z")));
        assertCategory(CORRUPT_RESPONSE, "OUT_OF_RANGE");
    }

    @Test
    void duplicatesAcrossPagesAreSkippedButHashConflictIsIntegrityError() throws Exception {
        when(sdk.queryInvoiceMetadata(eq(0), anyInt(), any(), any(), any()))
                .thenReturn(response(true, false, HWM, item(1, "2026-11-02T00:00:00Z")));
        when(sdk.queryInvoiceMetadata(eq(1), anyInt(), any(), any(), any()))
                .thenReturn(response(false, false, HWM, item(1, "2026-11-02T00:00:00Z"), item(2, "2026-11-02T00:00:00Z")));
        MetadataSegment segment = service.fetchSegment(CONTEXT, token(), QUERY);
        assertEquals(2, segment.invoices().size());
        assertEquals(1, segment.duplicatesSkipped());

        reset(sdk);
        var conflicting = metadataJson(ksefNumber(1), "2026-11-02T00:00:00Z", hashOf("other".getBytes()));
        when(sdk.queryInvoiceMetadata(eq(0), anyInt(), any(), any(), any()))
                .thenReturn(response(true, false, HWM, item(1, "2026-11-02T00:00:00Z")));
        when(sdk.queryInvoiceMetadata(eq(1), anyInt(), any(), any(), any()))
                .thenReturn(response(false, false, HWM, sdkItem(conflicting)));
        assertCategory(INTEGRITY_MISMATCH, "DUPLICATE_KSEF_NUMBER_HASH");
    }

    @Test
    void rateLimitedPageIsRetriedAfterShortRetryAfter() throws Exception {
        when(sdk.queryInvoiceMetadata(eq(0), anyInt(), any(), any(), any()))
                .thenReturn(response(true, false, HWM, item(1, "2026-11-02T00:00:00Z")));
        when(sdk.queryInvoiceMetadata(eq(1), anyInt(), any(), any(), any()))
                .thenThrow(apiError(429, Map.of("Retry-After", "3")))
                .thenReturn(response(false, false, HWM, item(2, "2026-11-03T00:00:00Z")));

        MetadataSegment segment = service.fetchSegment(CONTEXT, token(), QUERY);

        assertEquals(2, segment.invoices().size());
        assertEquals(List.of(Duration.ofSeconds(3)), sleeps);
        verify(sdk, times(2)).queryInvoiceMetadata(eq(1), anyInt(), any(), any(), any());
    }

    @Test
    void nonTransientErrorOnLaterPageDiscardsPartialResult() throws Exception {
        when(sdk.queryInvoiceMetadata(eq(0), anyInt(), any(), any(), any()))
                .thenReturn(response(true, false, HWM, item(1, "2026-11-02T00:00:00Z")));
        when(sdk.queryInvoiceMetadata(eq(1), anyInt(), any(), any(), any())).thenThrow(apiError(403, Map.of()));
        var failure = assertThrows(InboundFailure.class, () -> service.fetchSegment(CONTEXT, token(), QUERY));
        assertEquals(AUTHORIZATION, failure.category());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void downloadVerifiedChecksKsefHashOfExactBytes() throws Exception {
        byte[] xml = InboundTestSupport.resource("fa3-purchase-synthetic.xml");
        var metadata = mapped(metadataJson(ksefNumber(5), "2026-11-02T00:00:00Z", hashOf(xml)));
        when(sdk.getInvoice(ksefNumber(5), FAKE_JWT)).thenReturn(xml);

        RetrievedInvoice invoice = service.downloadVerified(CONTEXT, token(), metadata);
        assertArrayEquals(xml, invoice.content());

        byte[] tampered = xml.clone();
        tampered[tampered.length - 2] = ' ';
        when(sdk.getInvoice(ksefNumber(5), FAKE_JWT)).thenReturn(tampered);
        var failure = assertThrows(InboundFailure.class, () -> service.downloadVerified(CONTEXT, token(), metadata));
        assertEquals(INTEGRITY_MISMATCH, failure.category());
    }

    @Test
    void downloadUsingMetadataOfAnotherContextIsRejectedBeforeHttp() throws Exception {
        var other = new KsefTaxpayerContext(KsefEnvironment.TEST, OTHER_NIP);
        var json = metadataJson(ksefNumber(6), "2026-11-02T00:00:00Z", SOME_HASH);
        ((ObjectNode) json.get("buyer").get("identifier")).put("value", OTHER_NIP);
        var foreignMetadata = new InvoiceMetadataMapper().map(other, KsefSubjectRole.BUYER, sdkItem(json));
        var failure = assertThrows(InboundFailure.class, () -> service.downloadVerified(CONTEXT, token(), foreignMetadata));
        assertEquals(SCOPE_MISMATCH, failure.category());
        verifyNoInteractions(sdk);
    }

    @Test
    void notYetAvailableInvoiceIsRetriedWithBackoff() throws Exception {
        byte[] xml = InboundTestSupport.resource("fa3-purchase-synthetic.xml");
        var metadata = mapped(metadataJson(ksefNumber(7), "2026-11-02T00:00:00Z", hashOf(xml)));
        var notYet = new KsefApiException(400, "x", HttpHeaders.of(Map.of(), (a, b) -> true),
                JSON.readValue(legacyError(21165, "Jeszcze niedostepna"), pl.akmf.ksef.sdk.client.model.ExceptionResponse.class));
        when(sdk.getInvoice(ksefNumber(7), FAKE_JWT)).thenThrow(notYet).thenReturn(xml);
        assertArrayEquals(xml, service.downloadVerified(CONTEXT, token(), metadata).content());
        assertEquals(1, sleeps.size());
    }

    @Test
    void crossCheckConfirmsXmlMatchesMetadata() throws Exception {
        byte[] xml = InboundTestSupport.resource("fa3-purchase-synthetic.xml");
        var parsed = new SecureFa3InvoiceParser().parse(xml);
        var json = metadataJson(ksefNumber(8), "2026-11-02T00:00:00Z", hashOf(xml));
        json.put("invoiceNumber", "FZ/2026/11/0001");
        service.crossCheck(mapped(json), parsed);

        json.put("invoiceNumber", "FZ/2026/11/9999");
        var failure = assertThrows(InboundFailure.class, () -> service.crossCheck(mapped(json), parsed));
        assertEquals(INTEGRITY_MISMATCH, failure.category());
        assertTrue(failure.getMessage().endsWith("XML_INVOICE_NUMBER"));
    }

    private void assertCategory(InboundFailure.Category category, String detail) {
        var failure = assertThrows(InboundFailure.class, () -> service.fetchSegment(CONTEXT, token(), QUERY));
        assertEquals(category, failure.category());
        assertTrue(failure.getMessage().endsWith(detail), failure.getMessage());
    }

    private static KsefInvoiceMetadata mapped(ObjectNode json) {
        return new InvoiceMetadataMapper().map(CONTEXT, KsefSubjectRole.BUYER, sdkItem(json));
    }
}
