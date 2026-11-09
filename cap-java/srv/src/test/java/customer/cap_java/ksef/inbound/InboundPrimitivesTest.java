package customer.cap_java.ksef.inbound;

import static customer.cap_java.ksef.inbound.InboundTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQueryDateType;
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQuerySubjectType;
import pl.akmf.ksef.sdk.system.KSeFNumberValidator;

/** Numer KSeF, kontekst podatnika, token, parametry zapytania i polityka ponawiania odczytów. */
class InboundPrimitivesTest {

    @Test
    void officialKsefNumberExampleIsValidAndGeneratorMatchesSdkCrc() {
        // Przykład z oficjalnej dokumentacji MF faktury/numer-ksef.md.
        assertTrue(KsefNumbers.isValid("5265877635-20250826-0100001AF629-AF"));
        String generated = ksefNumber(42);
        assertTrue(KSeFNumberValidator.isValid(generated).isValid());
        assertTrue(KsefNumbers.isValid(generated));
    }

    @Test
    void ksefNumberRejectsWrongCrcCaseLegacyFormAndPathCharacters() {
        String valid = ksefNumber(7);
        String wrongCrc = valid.substring(0, 33) + (valid.endsWith("00") ? "01" : "00");
        assertFalse(KsefNumbers.isValid(wrongCrc));
        assertFalse(KsefNumbers.isValid(valid.toLowerCase()));
        assertFalse(KsefNumbers.isValid(valid.substring(0, 26) + "-" + valid.substring(26))); // 36-znakowa postać KSeF 1.0
        assertFalse(KsefNumbers.isValid(null));
        // Napis z „/” i poprawnym CRC przechodzi walidator SDK (tylko długość + CRC), ale nie nasz wzorzec.
        String injected = crcValid("2222222222-20261101-000000/../.-");
        assertTrue(KSeFNumberValidator.isValid(injected).isValid());
        assertFalse(KsefNumbers.isValid(injected));
    }

    @Test
    void taxpayerContextRequiresValidNipChecksum() {
        assertTrue(KsefTaxpayerContext.isValidNip("1234563218"));
        assertFalse(KsefTaxpayerContext.isValidNip("1234563219"));
        assertFalse(KsefTaxpayerContext.isValidNip("0123456789"));
        assertThrows(IllegalArgumentException.class, () -> new KsefTaxpayerContext(KsefEnvironment.TEST, "123"));
        assertThrows(NullPointerException.class, () -> new KsefTaxpayerContext(null, CONTEXT_NIP));
    }

    @Test
    void accessTokenIsRedactedAndBoundToContext() {
        var token = token();
        assertEquals("InboundAccessToken[REDACTED]", token.toString());
        assertEquals(CONTEXT, token.context());
        assertThrows(IllegalArgumentException.class, () -> InboundAccessToken.issuedFor(CONTEXT, " "));
    }

    @Test
    void metadataQueryEnforcesOfficialLimitsAndNormalisesToUtc() {
        var from = OffsetDateTime.parse("2026-11-01T01:00:00+02:00");
        var query = MetadataQuery.incremental(KsefSubjectRole.BUYER, from, null);
        assertEquals(OffsetDateTime.parse("2026-10-31T23:00:00Z"), query.from());
        var filters = query.toSdkFilters();
        assertEquals(InvoiceQuerySubjectType.SUBJECT2, filters.getSubjectType());
        assertEquals(InvoiceQueryDateType.PERMANENTSTORAGE, filters.getDateRange().getDateType());
        assertEquals(Boolean.TRUE, filters.getDateRange().getRestrictToPermanentStorageHwmDate());
        assertNull(filters.getDateRange().getTo());

        assertThrows(IllegalArgumentException.class, () -> new MetadataQuery(KsefSubjectRole.BUYER,
                MetadataQuery.DateType.PERMANENT_STORAGE, from, null, true, 9));
        assertThrows(IllegalArgumentException.class, () -> new MetadataQuery(KsefSubjectRole.BUYER,
                MetadataQuery.DateType.PERMANENT_STORAGE, from, null, true, 251));
        assertThrows(IllegalArgumentException.class, () -> new MetadataQuery(KsefSubjectRole.BUYER,
                MetadataQuery.DateType.ISSUE, from, null, true, 100));
        assertThrows(IllegalArgumentException.class,
                () -> MetadataQuery.incremental(KsefSubjectRole.BUYER, from, from.plusDays(100).plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> MetadataQuery.incremental(KsefSubjectRole.BUYER, from, from));
        assertDoesNotThrow(() -> MetadataQuery.incremental(KsefSubjectRole.BUYER, from, from.plusDays(100)));
    }

    @Test
    void subjectRolesMapToOfficialSubjectTypesAndDirections() {
        assertEquals(InvoiceQuerySubjectType.SUBJECT1, KsefSubjectRole.SELLER.sdkType());
        assertEquals(KsefSubjectRole.Direction.OUTGOING, KsefSubjectRole.SELLER.direction());
        assertEquals(KsefSubjectRole.Direction.INCOMING, KsefSubjectRole.BUYER.direction());
        assertEquals(InvoiceQuerySubjectType.SUBJECT3, KsefSubjectRole.THIRD_PARTY.sdkType());
        assertEquals(KsefSubjectRole.Direction.OTHER, KsefSubjectRole.AUTHORIZED.direction());
    }

    // ------------------------------------------------------------------ ReadRetryPolicy

    @Test
    void retryAfterWithinInlineLimitIsHonouredThenSucceeds() {
        var sleeps = new ArrayList<Duration>();
        var policy = new ReadRetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(10), CLOCK, sleeps::add);
        var calls = new AtomicInteger();
        String result = policy.execute(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new InboundFailure(InboundFailure.Category.RATE_LIMITED, 429, null, NOW.plusSeconds(2), null);
            }
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(List.of(Duration.ofSeconds(2)), sleeps);
    }

    @Test
    void longRetryAfterIsLeftToTheScheduler() {
        var sleeps = new ArrayList<Duration>();
        var policy = new ReadRetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(10), CLOCK, sleeps::add);
        var calls = new AtomicInteger();
        var failure = assertThrows(InboundFailure.class, () -> policy.execute(() -> {
            calls.incrementAndGet();
            throw new InboundFailure(InboundFailure.Category.RATE_LIMITED, 429, null, NOW.plusSeconds(600), null);
        }));
        assertEquals(1, calls.get());
        assertTrue(sleeps.isEmpty());
        assertEquals(NOW.plusSeconds(600), failure.retryNotBefore());
    }

    @Test
    void nonTransientFailuresAreNeverRetriedAndAttemptsAreBounded() {
        var sleeps = new ArrayList<Duration>();
        var policy = new ReadRetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(10), CLOCK, sleeps::add);
        for (var category : List.of(InboundFailure.Category.AUTHENTICATION, InboundFailure.Category.AUTHORIZATION,
                InboundFailure.Category.NOT_FOUND_IN_CONTEXT, InboundFailure.Category.CORRUPT_RESPONSE,
                InboundFailure.Category.UNEXPECTED_REDIRECT, InboundFailure.Category.UNCLASSIFIED)) {
            var calls = new AtomicInteger();
            assertThrows(InboundFailure.class, () -> policy.execute(() -> {
                calls.incrementAndGet();
                throw InboundFailure.of(category, null);
            }));
            assertEquals(1, calls.get(), category.name());
        }
        var calls = new AtomicInteger();
        assertThrows(InboundFailure.class, () -> policy.execute(() -> {
            calls.incrementAndGet();
            throw InboundFailure.of(InboundFailure.Category.TIMEOUT, null);
        }));
        assertEquals(3, calls.get());
        // Wykładniczy backoff z jitterem ≤ 250 ms: ~1 s, ~2 s.
        assertEquals(2, sleeps.size());
        assertTrue(sleeps.get(0).compareTo(Duration.ofMillis(1000)) >= 0 && sleeps.get(0).compareTo(Duration.ofMillis(1250)) <= 0);
        assertTrue(sleeps.get(1).compareTo(Duration.ofMillis(2000)) >= 0 && sleeps.get(1).compareTo(Duration.ofMillis(2250)) <= 0);
    }

    @Test
    void interruptionDuringBackoffStopsAndRestoresFlag() {
        var policy = new ReadRetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(10), CLOCK,
                d -> { throw new InterruptedException(); });
        var failure = assertThrows(InboundFailure.class, () -> policy.execute(() -> {
            throw InboundFailure.of(InboundFailure.Category.NETWORK, null);
        }));
        assertEquals(InboundFailure.Category.INTERRUPTED, failure.category());
        assertTrue(Thread.interrupted(), "flaga przerwania musi zostać przywrócona");
    }

    @Test
    void retryAfterHttpDateAndGarbageAreParsedSafely() {
        var headers = java.net.http.HttpHeaders.of(java.util.Map.of("Retry-After",
                List.of("Mon, 09 Nov 2026 12:01:00 GMT")), (a, b) -> true);
        assertEquals(NOW.plusSeconds(60), KsefInboundClient.retryAfter(headers, CLOCK));
        var garbage = java.net.http.HttpHeaders.of(java.util.Map.of("Retry-After", List.of("soon")), (a, b) -> true);
        assertNull(KsefInboundClient.retryAfter(garbage, CLOCK));
        var negative = java.net.http.HttpHeaders.of(java.util.Map.of("Retry-After", List.of("-5")), (a, b) -> true);
        assertNull(KsefInboundClient.retryAfter(negative, CLOCK));
    }

    private static String crcValid(String data32) {
        assertEquals(32, data32.length());
        int crc = 0;
        for (byte b : data32.getBytes(java.nio.charset.StandardCharsets.US_ASCII)) {
            crc ^= b & 0xFF;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
            }
        }
        return data32 + "-" + String.format("%02X", crc);
    }
}
