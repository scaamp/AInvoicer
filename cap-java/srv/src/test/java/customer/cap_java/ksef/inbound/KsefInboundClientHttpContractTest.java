package customer.cap_java.ksef.inbound;

import static customer.cap_java.ksef.inbound.InboundFailure.Category.*;
import static customer.cap_java.ksef.inbound.InboundTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import customer.cap_java.ksef.inbound.InboundTestSupport.Stub;

/**
 * Kontrakt adaptera z prawdziwym klientem HTTP SDK MF 3.0.29 (fabryka produkcyjna {@code KsefSdkConfiguration})
 * i lokalną zaślepką KSeF. Odpowiedzi mają kształt oficjalnego OpenAPI KSeF 2.0. Brak wywołań KSeF.
 */
class KsefInboundClientHttpContractTest {
    private static final String METADATA = "/v2/invoices/query/metadata";
    private static final String INVOICE = "/v2/invoices/ksef/";
    private static final MetadataQuery QUERY = MetadataQuery.incremental(KsefSubjectRole.BUYER,
            OffsetDateTime.parse("2026-11-01T00:00:00+01:00"), null);

    @Test
    void validMetadataPageIsMappedAndRequestMatchesOfficialContract() throws Exception {
        String number = ksefNumber(1);
        try (var stub = new Stub().on(METADATA, (e, call) -> Stub.json(e, 200, page(false, false,
                "2026-11-09T10:00:00.5+00:00",
                List.of(metadataJson(number, "2026-11-02T07:00:06.789+00:00", SOME_HASH)))))) {
            MetadataPage page = inboundClient(productionClient(stub.origin()))
                    .queryMetadataPage(CONTEXT, token(), QUERY, 0);

            assertFalse(page.hasMore());
            assertFalse(page.truncated());
            assertEquals(OffsetDateTime.parse("2026-11-09T10:00:00.5Z"), page.hwmDate());
            KsefInvoiceMetadata m = page.invoices().getFirst();
            assertEquals(KsefEnvironment.TEST, m.environment());
            assertEquals(CONTEXT_NIP, m.contextNip());
            assertEquals(KsefSubjectRole.BUYER, m.role());
            assertEquals(KsefSubjectRole.Direction.INCOMING, m.direction());
            assertEquals(number, m.ksefNumber());
            assertEquals(LocalDate.parse("2026-11-02"), m.issueDate());
            // Offset +01:00 znormalizowany do UTC, ta sama chwila.
            assertEquals(OffsetDateTime.parse("2026-11-02T07:00:00.123Z"), m.invoicingDate());
            assertEquals(ZoneOffset.UTC, m.permanentStorageDate().getOffset());
            assertEquals(SELLER_NIP, m.sellerNip());
            assertEquals("Nip", m.buyerIdentifierType());
            assertEquals(CONTEXT_NIP, m.buyerIdentifier());
            assertEquals(0, new BigDecimal("1000.1").compareTo(m.netAmount()));
            assertEquals(new BigDecimal("1230.12"), m.grossAmount());
            assertEquals(new BigDecimal("230.02"), m.vatAmountPln());
            assertEquals("PLN", m.currency());
            assertEquals("Vat", m.invoiceType());
            assertTrue(m.formCode().isFa3());
            assertEquals(SOME_HASH, m.invoiceHashBase64());
            assertEquals("Online", m.invoicingMode());

            var request = stub.requests().getFirst();
            assertEquals("POST", request.method());
            assertEquals(METADATA, request.path());
            assertTrue(request.query().contains("pageOffset=0"));
            assertTrue(request.query().contains("pageSize=250"));
            assertTrue(request.query().contains("sortOrder=Asc"));
            assertEquals("Bearer " + FAKE_JWT, request.authorization());
            var body = JSON.readTree(request.body());
            assertEquals("Subject2", body.get("subjectType").asText());
            assertEquals("PermanentStorage", body.at("/dateRange/dateType").asText());
            assertTrue(body.at("/dateRange/restrictToPermanentStorageHwmDate").asBoolean());
            assertEquals("2026-10-31T23:00:00Z", body.at("/dateRange/from").asText());
        }
    }

    @Test
    void emptyListIsAValidCompletePage() throws Exception {
        try (var stub = new Stub().on(METADATA, (e, call) -> Stub.json(e, 200,
                page(false, false, "2026-11-09T10:00:00Z", List.of())))) {
            MetadataPage page = inboundClient(productionClient(stub.origin())).queryMetadataPage(CONTEXT, token(), QUERY, 0);
            assertTrue(page.invoices().isEmpty());
            assertFalse(page.hasMore());
        }
    }

    @Test
    void contractViolationsRejectTheWholePage() throws Exception {
        assertMetadataFailure(200, "{\"invoices\":[],\"isTruncated\":false}", CORRUPT_RESPONSE); // brak hasMore
        assertMetadataFailure(200, "{not json", CORRUPT_RESPONSE);
        assertMetadataFailure(200, new String(page(true, false, null, List.of()), StandardCharsets.UTF_8), CORRUPT_RESPONSE);
        var badNumber = metadataJson("1234567890-20261101-00000000000A-00", "2026-11-02T07:00:00Z", SOME_HASH);
        assertMetadataFailure(200, new String(page(false, false, null, List.of(badNumber)), StandardCharsets.UTF_8),
                CORRUPT_RESPONSE);
        var missingHash = metadataJson(ksefNumber(2), "2026-11-02T07:00:00Z", SOME_HASH);
        missingHash.remove("invoiceHash");
        assertMetadataFailure(200, new String(page(false, false, null, List.of(missingHash)), StandardCharsets.UTF_8),
                CORRUPT_RESPONSE);
    }

    @Test
    void responseForAnotherBuyerIsAScopeMismatch() throws Exception {
        var foreign = metadataJson(ksefNumber(3), "2026-11-02T07:00:00Z", SOME_HASH);
        ((com.fasterxml.jackson.databind.node.ObjectNode) foreign.get("buyer").get("identifier")).put("value", OTHER_NIP);
        assertMetadataFailure(200, new String(page(false, false, null, List.of(foreign)), StandardCharsets.UTF_8),
                SCOPE_MISMATCH);
    }

    @Test
    void httpErrorsAreClassifiedWithoutLeakingBodyOrToken() throws Exception {
        var unauthorized = assertMetadataFailure(401, "{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"SECRET-BODY-DETAIL\"}",
                AUTHENTICATION);
        assertFalse(unauthorized.transientRead());
        assertMetadataFailure(403, "{\"title\":\"Forbidden\",\"status\":403,\"detail\":\"SECRET-BODY-DETAIL\"}",
                AUTHORIZATION);
        var invalid = assertMetadataFailure(400, new String(legacyError(21405, "Validation"), StandardCharsets.UTF_8),
                INVALID_REQUEST);
        assertEquals(21405, invalid.mfExceptionCode());
        var server = assertMetadataFailure(503, "", SERVER_ERROR);
        assertTrue(server.transientRead());
        assertMetadataFailure(418, "", UNEXPECTED_STATUS);
    }

    @Test
    void rateLimitCarriesRetryAfterAndIsTransient() throws Exception {
        try (var stub = new Stub().on(METADATA, (e, call) -> Stub.respond(e, 429, "application/json",
                "{\"status\":{\"code\":429,\"description\":\"Too Many Requests\",\"details\":[\"x\"]}}"
                        .getBytes(StandardCharsets.UTF_8), Map.of("Retry-After", "30")))) {
            var failure = assertThrows(InboundFailure.class,
                    () -> inboundClient(productionClient(stub.origin())).queryMetadataPage(CONTEXT, token(), QUERY, 0));
            assertEquals(RATE_LIMITED, failure.category());
            assertEquals(NOW.plusSeconds(30), failure.retryNotBefore());
            assertTrue(failure.transientRead());
            assertEquals(1, stub.requests().size(), "adapter nie ponawia samodzielnie");
        }
    }

    @Test
    void redirectIsNotFollowedToAnotherHost() throws Exception {
        try (var untrusted = new Stub().on("/", (e, call) -> Stub.json(e, 200, page(false, false, null, List.of())));
                var stub = new Stub().on(METADATA, (e, call) -> Stub.respond(e, 302, null, null,
                        Map.of("Location", untrusted.origin() + METADATA)))) {
            var failure = assertThrows(InboundFailure.class,
                    () -> inboundClient(productionClient(stub.origin())).queryMetadataPage(CONTEXT, token(), QUERY, 0));
            assertEquals(UNEXPECTED_REDIRECT, failure.category());
            assertTrue(untrusted.requests().isEmpty(), "token nie może trafić na host z Location");
        }
    }

    @Test
    void downloadReturnsExactBytesAndSha256() throws Exception {
        String number = ksefNumber(10);
        // Celowo: BOM, CRLF, polskie znaki, encja predefiniowana — bajty nie mogą być znormalizowane.
        byte[] xml = ("﻿<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n<Faktura>Zażółć &amp; gęślą</Faktura>\r\n")
                .getBytes(StandardCharsets.UTF_8);
        try (var stub = new Stub().on(INVOICE, (e, call) -> Stub.respond(e, 200, "application/xml", xml, Map.of()))) {
            RetrievedInvoice invoice = inboundClient(productionClient(stub.origin())).downloadInvoice(CONTEXT, token(), number);
            assertArrayEquals(xml, invoice.content());
            byte[] expected = MessageDigest.getInstance("SHA-256").digest(xml);
            assertEquals(Base64.getEncoder().encodeToString(expected), invoice.sha256Base64());
            assertTrue(invoice.matchesKsefHash(Base64.getEncoder().encodeToString(expected)));
            assertFalse(invoice.matchesKsefHash(SOME_HASH));
            assertEquals(INVOICE + number, stub.requests().getFirst().path());
            assertEquals("Bearer " + FAKE_JWT, stub.requests().getFirst().authorization());
            assertFalse(invoice.toString().contains("Zażółć"));
        }
    }

    @Test
    void downloadErrorsDistinguishNotFoundFromAccessAndAvailability() throws Exception {
        var notFound = assertDownloadFailure(400, legacyError(21164, "Faktura nie istnieje"), NOT_FOUND_IN_CONTEXT);
        assertEquals(21164, notFound.mfExceptionCode());
        assertFalse(notFound.transientRead());
        var notYet = assertDownloadFailure(400, legacyError(21165, "Jeszcze niedostepna"), NOT_YET_AVAILABLE);
        assertTrue(notYet.transientRead());
        assertDownloadFailure(404, new byte[0], NOT_FOUND_IN_CONTEXT);
        // 403 to brak uprawnienia — nigdy „faktura nie istnieje”.
        assertDownloadFailure(403, "{\"title\":\"Forbidden\",\"status\":403}".getBytes(StandardCharsets.UTF_8), AUTHORIZATION);
        assertDownloadFailure(401, "{\"title\":\"Unauthorized\",\"status\":401}".getBytes(StandardCharsets.UTF_8), AUTHENTICATION);
        assertDownloadFailure(500, new byte[0], SERVER_ERROR);
        assertDownloadFailure(200, new byte[0], CORRUPT_RESPONSE);
    }

    @Test
    void oversizedInvoiceIsRejected() throws Exception {
        byte[] big = new byte[2048];
        java.util.Arrays.fill(big, (byte) 'a');
        try (var stub = new Stub().on(INVOICE, (e, call) -> Stub.respond(e, 200, "application/xml", big, Map.of()))) {
            var client = new KsefInboundClient(productionClient(stub.origin()), KsefEnvironment.TEST, 1024, CLOCK);
            var failure = assertThrows(InboundFailure.class, () -> client.downloadInvoice(CONTEXT, token(), ksefNumber(11)));
            assertEquals(PAYLOAD_TOO_LARGE, failure.category());
        }
    }

    @Test
    void invalidKsefNumberNeverReachesHttp() throws Exception {
        try (var stub = new Stub().on("/", (e, call) -> Stub.json(e, 200, new byte[] {'x'}))) {
            var client = inboundClient(productionClient(stub.origin()));
            for (String bad : List.of("../../security/public-key-certificates", ksefNumber(12).toLowerCase(), "x")) {
                var failure = assertThrows(InboundFailure.class, () -> client.downloadInvoice(CONTEXT, token(), bad));
                assertEquals(INVALID_REQUEST, failure.category());
            }
            assertTrue(stub.requests().isEmpty());
        }
    }

    @Test
    void timeoutIsClassifiedAsTransientTimeout() throws Exception {
        try (var stub = new Stub().on(INVOICE, (e, call) -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        })) {
            var client = inboundClient(shortTimeoutClient(stub.origin(), Duration.ofMillis(300)));
            var failure = assertThrows(InboundFailure.class, () -> client.downloadInvoice(CONTEXT, token(), ksefNumber(13)));
            assertEquals(TIMEOUT, failure.category());
            assertTrue(failure.transientRead());
        }
    }

    @Test
    void networkFailureIsClassified() {
        // Port 1 na loopback: odmowa połączenia, bez ruchu poza maszynę.
        var client = inboundClient(productionClient("http://127.0.0.1:1"));
        var failure = assertThrows(InboundFailure.class, () -> client.downloadInvoice(CONTEXT, token(), ksefNumber(14)));
        assertEquals(NETWORK, failure.category());
    }

    @Test
    void tokenOrEnvironmentFromAnotherScopeIsRejectedBeforeHttp() throws Exception {
        try (var stub = new Stub().on("/", (e, call) -> Stub.json(e, 200, new byte[] {'x'}))) {
            var client = inboundClient(productionClient(stub.origin()));
            var other = new KsefTaxpayerContext(KsefEnvironment.TEST, OTHER_NIP);
            var foreignToken = InboundAccessToken.issuedFor(other, FAKE_JWT);
            assertEquals(SCOPE_MISMATCH, assertThrows(InboundFailure.class,
                    () -> client.queryMetadataPage(CONTEXT, foreignToken, QUERY, 0)).category());
            var prod = new KsefTaxpayerContext(KsefEnvironment.PROD, CONTEXT_NIP);
            assertEquals(SCOPE_MISMATCH, assertThrows(InboundFailure.class,
                    () -> client.downloadInvoice(prod, InboundAccessToken.issuedFor(prod, FAKE_JWT), ksefNumber(15))).category());
            assertTrue(stub.requests().isEmpty());
        }
    }

    private static InboundFailure assertMetadataFailure(int status, String body, InboundFailure.Category expected)
            throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (var stub = new Stub().on(METADATA, (e, call) -> Stub.json(e, status, bytes))) {
            var failure = assertThrows(InboundFailure.class,
                    () -> inboundClient(productionClient(stub.origin())).queryMetadataPage(CONTEXT, token(), QUERY, 0));
            assertEquals(expected, failure.category(), failure.getMessage());
            assertSafe(failure);
            return failure;
        }
    }

    private static InboundFailure assertDownloadFailure(int status, byte[] body, InboundFailure.Category expected)
            throws Exception {
        try (var stub = new Stub().on(INVOICE, (e, call) -> Stub.json(e, status, body))) {
            var failure = assertThrows(InboundFailure.class,
                    () -> inboundClient(productionClient(stub.origin())).downloadInvoice(CONTEXT, token(), ksefNumber(20)));
            assertEquals(expected, failure.category(), failure.getMessage());
            assertSafe(failure);
            return failure;
        }
    }

    /** Wyjątek nie może nieść tokenu, body odpowiedzi, URL ani przyczyny z SDK. */
    private static void assertSafe(InboundFailure failure) {
        String text = failure.toString();
        assertFalse(text.contains("MARKER"), text);
        assertFalse(text.contains("SECRET-BODY-DETAIL"), text);
        assertFalse(text.contains("127.0.0.1"), text);
        assertFalse(text.contains(CONTEXT_NIP), text);
        assertNull(failure.getCause());
    }
}
