package customer.cap_java.ksef;

import static customer.cap_java.ksef.inbound.InboundTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import customer.cap_java.ksef.inbound.*;
import customer.cap_java.ksef.inbound.fa3.Fa3ParseException;
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient;

/** Zgoda, realny SDK na loopback i istniejący pipeline; żadnego zewnętrznego API. */
class KsefInboundOneShotTest {
    private static final String NUMBER = ksefNumber(1);
    private static final byte[] XML = resource("fa3-purchase-synthetic.xml");
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-11-02T00:00:00Z");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2026-11-03T00:00:00Z");

    private static Map<String, String> approval() {
        var env = new HashMap<String, String>();
        env.put("AINV_KSEF_ONESHOT_AUTH_APPROVED", "true");
        env.put("AINV_KSEF_ONESHOT_READ_APPROVED", "true");
        env.put("AINV_KSEF_ONESHOT_ENVIRONMENT", "TEST");
        env.put("AINV_KSEF_NIP", CONTEXT_NIP);
        env.put("AINV_KSEF_ONESHOT_APPROVED_NIP", CONTEXT_NIP);
        env.put("AINV_KSEF_ONESHOT_SCENARIO", "NUMBER");
        env.put("AINV_KSEF_ONESHOT_AUTH_METHOD", "XADES");
        env.put("AINV_KSEF_ONESHOT_MAX_REQUESTS", "9");
        env.put("AINV_KSEF_ONESHOT_NUMBER", NUMBER);
        env.put("AINV_KSEF_ONESHOT_FROM", FROM.toString());
        env.put("AINV_KSEF_ONESHOT_TO", TO.toString());
        return env;
    }

    private static KsefInboundOneShot.Settings settings(boolean buyer) {
        var env = approval();
        if (buyer) { env.put("AINV_KSEF_ONESHOT_SCENARIO", "BUYER"); }
        return KsefInboundOneShot.Settings.approved(env, CLOCK);
    }

    private static OneShotHttpClient transport(String origin, KsefInboundOneShot.Settings settings) {
        return new OneShotHttpClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), origin, settings);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AINV_KSEF_ONESHOT_AUTH_APPROVED", "AINV_KSEF_ONESHOT_READ_APPROVED",
            "AINV_KSEF_ONESHOT_ENVIRONMENT", "AINV_KSEF_ONESHOT_APPROVED_NIP", "AINV_KSEF_ONESHOT_SCENARIO",
            "AINV_KSEF_ONESHOT_AUTH_METHOD", "AINV_KSEF_ONESHOT_MAX_REQUESTS", "AINV_KSEF_ONESHOT_NUMBER"})
    void missingApprovalOrScopeStopsBeforeFactory(String key) {
        var env = approval();
        env.remove(key);
        assertThrows(KsefFailure.class, () -> KsefInboundOneShot.Settings.approved(env, CLOCK));
    }

    @Test void tagRemovalAloneNeverAuthorizesLive() {
        assertThrows(KsefFailure.class, () -> KsefInboundOneShot.Settings.approved(Map.of(), CLOCK));
        assertTrue(KsefInboundOneShotLiveTest.class.isAnnotationPresent(org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable.class));
    }

    @Test void invalidNumberNipEnvironmentAuthBudgetAndWindowAreRejected() {
        for (var pair : List.of(Map.entry("AINV_KSEF_ONESHOT_NUMBER", "../../secret"),
                Map.entry("AINV_KSEF_ONESHOT_APPROVED_NIP", OTHER_NIP),
                Map.entry("AINV_KSEF_ONESHOT_ENVIRONMENT", "PROD"), Map.entry("AINV_KSEF_ONESHOT_ENVIRONMENT", "DEMO"),
                Map.entry("AINV_KSEF_ONESHOT_MAX_REQUESTS", "10"), Map.entry("AINV_KSEF_ONESHOT_MAX_REQUESTS", "0"),
                Map.entry("AINV_KSEF_ONESHOT_AUTH_METHOD", "TOKEN"), Map.entry("AINV_KSEF_TOKEN", FAKE_JWT))) {
            var env = approval();
            env.put(pair.getKey(), pair.getValue());
            assertThrows(KsefFailure.class, () -> KsefInboundOneShot.Settings.approved(env, CLOCK));
        }
        for (String from : List.of("2026-10-01T00:00:00Z", "2026-11-04T00:00:00Z", "not-a-date")) {
            var env = approval();
            env.put("AINV_KSEF_ONESHOT_SCENARIO", "BUYER");
            env.put("AINV_KSEF_ONESHOT_FROM", from);
            assertThrows(KsefFailure.class, () -> KsefInboundOneShot.Settings.approved(env, CLOCK));
        }
    }

    @Test void getUsesRealSdkExactBytesHashParserAndRedactedReport() throws Exception {
        try (var stub = new Stub().on("/v2/invoices/ksef/", (e, n) ->
                Stub.respond(e, 200, "application/xml", XML, Map.of()));
                var transport = transport(stub.origin(), settings(false))) {
            var result = KsefInboundOneShot.read(settings(false),
                    KsefSdkConfiguration.createClient(stub.origin(), transport), token(), CLOCK);
            assertEquals("PASS", result.outcome());
            assertEquals(XML.length, result.bytes());
            assertEquals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(XML)), result.sha256());
            assertEquals("NOT_CHECKED", result.metadataHash());
            assertEquals(1, transport.requests());
            assertEquals(200, transport.lastStatus());
            assertEquals("Bearer " + FAKE_JWT, stub.requests().getFirst().authorization());
            for (String secret : List.of(NUMBER, CONTEXT_NIP, SELLER_NIP, FAKE_JWT, "FZ/2026/11/0001", "1269.00", "Nabywca")) {
                assertFalse(result.toString().contains(secret));
            }
        }
    }

    @ParameterizedTest @ValueSource(ints = {401, 403, 404, 429})
    void httpErrorsAreSingleAttemptWithNoAuthorizationFallback(int code) throws Exception {
        try (var stub = new Stub().on("/v2/invoices/ksef/", (e, n) ->
                Stub.respond(e, code, "application/json", new byte[0], Map.of("Retry-After", "120")));
                var transport = transport(stub.origin(), settings(false))) {
            var sdk = KsefSdkConfiguration.createClient(stub.origin(), transport);
            var error = assertThrows(InboundFailure.class, () -> KsefInboundOneShot.read(settings(false), sdk, token(), CLOCK));
            assertEquals(code, error.httpStatus());
            assertEquals(switch (code) {
                case 401 -> InboundFailure.Category.AUTHENTICATION;
                case 403 -> InboundFailure.Category.AUTHORIZATION;
                case 404 -> InboundFailure.Category.NOT_FOUND_IN_CONTEXT;
                default -> InboundFailure.Category.RATE_LIMITED;
            }, error.category());
            if (code == 429) {
                assertNotNull(error.retryNotBefore());
                assertNotNull(transport.retryNotBefore());
            }
            assertThrows(KsefFailure.class, () -> transport.send(
                    HttpRequest.newBuilder(URI.create(stub.origin() + "/v2/auth/challenge")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofByteArray()));
            assertEquals(1, stub.requests().size());
        }
    }

    @Test void timeoutEmptyOversizeAndInvalidNumberUseExistingFailureCategories() throws Exception {
        var sdk = mock(KSeFClient.class);
        when(sdk.getInvoice(anyString(), anyString())).thenThrow(new RuntimeException(new HttpTimeoutException("SECRET")));
        assertEquals(InboundFailure.Category.TIMEOUT,
                assertThrows(InboundFailure.class, () -> KsefInboundOneShot.read(settings(false), sdk, token(), CLOCK)).category());
        doReturn(new byte[0]).when(sdk).getInvoice(anyString(), anyString());
        assertEquals(InboundFailure.Category.CORRUPT_RESPONSE,
                assertThrows(InboundFailure.class, () -> KsefInboundOneShot.read(settings(false), sdk, token(), CLOCK)).category());
        when(sdk.getInvoice(anyString(), anyString())).thenReturn(new byte[KsefInboundClient.DEFAULT_MAX_INVOICE_BYTES + 1]);
        assertEquals(InboundFailure.Category.PAYLOAD_TOO_LARGE,
                assertThrows(InboundFailure.class, () -> KsefInboundOneShot.read(settings(false), sdk, token(), CLOCK)).category());
        var invalid = new KsefInboundOneShot.Settings(CONTEXT, KsefInboundOneShot.Scenario.NUMBER,
                KsefInboundOneShot.AuthMethod.XADES, "bad", null, 9);
        clearInvocations(sdk);
        assertEquals(InboundFailure.Category.INVALID_REQUEST,
                assertThrows(InboundFailure.class, () -> KsefInboundOneShot.read(invalid, sdk, token(), CLOCK)).category());
        verifyNoInteractions(sdk);
    }

    @Test void buyerReadsOnePageAndFirstDocumentEvenWhenHasMore() throws Exception {
        var item = metadataJson(NUMBER, "2026-11-02T12:00:00Z", hashOf(XML));
        item.put("grossAmount", 1269.0);
        try (var stub = new Stub()
                .on("/v2/invoices/query/metadata", (e, n) -> Stub.json(e, 200,
                        page(true, false, "2026-11-03T00:00:00Z", List.of(item))))
                .on("/v2/invoices/ksef/", (e, n) -> Stub.respond(e, 200, "application/xml", XML, Map.of()));
                var transport = transport(stub.origin(), settings(true))) {
            var result = KsefInboundOneShot.read(settings(true), KsefSdkConfiguration.createClient(stub.origin(), transport), token(), CLOCK);
            assertEquals("PASS", result.outcome());
            assertEquals("PASS", result.metadataHash());
            assertTrue(result.hasMore());
            assertEquals(2, transport.requests());
            var query = stub.requests().getFirst();
            assertTrue(query.query().contains("pageSize=10"));
            assertTrue(query.query().contains("pageOffset=0"));
            assertEquals("Subject2", JSON.readTree(query.body()).get("subjectType").asText());
        }
    }

    @Test void emptyBuyerIsEmptyWithoutGetOrWideningAnd403IsNotEmpty() throws Exception {
        var sdk = mock(KSeFClient.class);
        when(sdk.queryInvoiceMetadata(anyInt(), anyInt(), any(), any(), any())).thenReturn(response(false, false, null));
        var result = KsefInboundOneShot.read(settings(true), sdk, token(), CLOCK);
        assertEquals("EMPTY", result.outcome());
        verify(sdk, never()).getInvoice(any(), any());
        verify(sdk, times(1)).queryInvoiceMetadata(eq(0), eq(10), any(), any(), any());
        when(sdk.queryInvoiceMetadata(anyInt(), anyInt(), any(), any(), any())).thenThrow(apiError(403, Map.of()));
        assertEquals(InboundFailure.Category.AUTHORIZATION,
                assertThrows(InboundFailure.class, () -> KsefInboundOneShot.read(settings(true), sdk, token(), CLOCK)).category());
    }

    @Test void wrongHashCrossCheckAmountsAndScopeFailClosed() throws Exception {
        for (String change : List.of("hash", "number", "gross", "buyer", "date")) {
            var sdk = mock(KSeFClient.class);
            var item = metadataJson(NUMBER, "2026-11-02T12:00:00Z", hashOf(XML));
            item.put("grossAmount", 1269.0);
            switch (change) {
                case "hash" -> item.put("invoiceHash", SOME_HASH);
                case "number" -> item.put("invoiceNumber", "different");
                case "gross" -> item.put("grossAmount", 1.0);
                case "buyer" -> ((com.fasterxml.jackson.databind.node.ObjectNode) item.get("buyer").get("identifier")).put("value", OTHER_NIP);
                case "date" -> item.put("issueDate", "2026-11-01");
            }
            when(sdk.queryInvoiceMetadata(anyInt(), anyInt(), any(), any(), any())).thenReturn(response(false, false, null, sdkItem(item)));
            when(sdk.getInvoice(any(), any())).thenReturn(XML);
            assertThrows(RuntimeException.class, () -> KsefInboundOneShot.read(settings(true), sdk, token(), CLOCK));
            verify(sdk, atMostOnce()).getInvoice(any(), any());
        }
    }

    @Test void unsupportedSchemaReportsBlockedWithSizeAndDigestAndMalformedFails() throws Exception {
        var sdk = mock(KSeFClient.class);
        byte[] unsupported = new String(XML, StandardCharsets.UTF_8).replace("2025/06/25/13775", "2023/06/29/12648")
                .getBytes(StandardCharsets.UTF_8);
        when(sdk.getInvoice(any(), any())).thenReturn(unsupported);
        var result = KsefInboundOneShot.read(settings(false), sdk, token(), CLOCK);
        assertEquals("BLOCKED", result.outcome());
        assertEquals("UNSUPPORTED_SCHEMA", result.xml());
        assertEquals(unsupported.length, result.bytes());
        assertEquals(64, result.sha256().length());
        when(sdk.getInvoice(any(), any())).thenReturn("<broken".getBytes(StandardCharsets.UTF_8));
        assertThrows(Fa3ParseException.class, () -> KsefInboundOneShot.read(settings(false), sdk, token(), CLOCK));
    }

    @Test void transportRejectsSendRefreshForeignOriginsWrongNumberAndRepeatedGetBeforeIo() throws Exception {
        for (String target : List.of("/v2/sessions/online", "/v2/auth/token/refresh", "/v2/invoices/ksef/" + ksefNumber(2),
                "https://api.ksef.mf.gov.pl/v2/auth/challenge", "/v2/invoices/query/metadata")) {
            try (var stub = new Stub(); var transport = transport(stub.origin(), settings(false))) {
                URI uri = URI.create(target.startsWith("https:") ? target : stub.origin() + target);
                var request = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build();
                assertThrows(KsefFailure.class, () -> transport.send(request, HttpResponse.BodyHandlers.ofByteArray()));
                assertEquals(0, transport.requests());
                assertTrue(stub.requests().isEmpty());
            }
        }
        try (var stub = new Stub().on("/v2/invoices/ksef/", (e, n) -> Stub.respond(e, 200, "application/xml", XML, Map.of()));
                var transport = transport(stub.origin(), settings(false))) {
            var request = HttpRequest.newBuilder(URI.create(stub.origin() + "/v2/invoices/ksef/" + NUMBER)).GET().build();
            transport.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertThrows(KsefFailure.class, () -> transport.send(request, HttpResponse.BodyHandlers.ofByteArray()));
            assertEquals(1, stub.requests().size());
        }
    }

    @Test void globalRequestLimitIncludesAuthAndStopsBeforeSecondIo() throws Exception {
        var env = approval();
        env.put("AINV_KSEF_ONESHOT_MAX_REQUESTS", "1");
        try (var stub = new Stub().on("/v2/auth/challenge", (e, n) -> Stub.json(e, 200, "{}".getBytes(StandardCharsets.UTF_8)));
                var transport = transport(stub.origin(), KsefInboundOneShot.Settings.approved(env, CLOCK))) {
            var challenge = HttpRequest.newBuilder(URI.create(stub.origin() + "/v2/auth/challenge"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            transport.send(challenge, HttpResponse.BodyHandlers.ofByteArray());
            var auth = HttpRequest.newBuilder(URI.create(stub.origin() + "/v2/auth/xades-signature"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            assertThrows(KsefFailure.class, () -> transport.send(auth, HttpResponse.BodyHandlers.ofByteArray()));
            assertEquals(1, stub.requests().size());
            assertEquals("REQUEST_LIMIT", transport.stopReason());
        }
    }

    @Test void existingXadesAuthAndJwtFeedGuardedSdkReadOnLoopback() throws Exception {
        String ref = "20261109-AU-0000000000-0000000000-00";
        byte[] challenge = JSON.writeValueAsBytes(Map.of("challenge", "synthetic-challenge", "timestamp", NOW.toString()));
        byte[] started = JSON.writeValueAsBytes(Map.of("referenceNumber", ref,
                "authenticationToken", Map.of("token", "synthetic-temporary", "validUntil", NOW.plusSeconds(600).toString())));
        byte[] redeemed = JSON.writeValueAsBytes(Map.of(
                "accessToken", Map.of("token", FAKE_JWT, "validUntil", NOW.plusSeconds(600).toString()),
                "refreshToken", Map.of("token", "synthetic-refresh", "validUntil", NOW.plusSeconds(3600).toString())));
        try (var stub = new Stub()
                .on("/v2/auth/challenge", (e, n) -> Stub.json(e, 200, challenge))
                .on("/v2/auth/xades-signature", (e, n) -> Stub.json(e, 202, started))
                .on("/v2/auth/" + ref, (e, n) -> Stub.json(e, 200,
                        "{\"status\":{\"code\":200},\"isTokenRedeemed\":false}".getBytes(StandardCharsets.UTF_8)))
                .on("/v2/auth/token/redeem", (e, n) -> Stub.json(e, 200, redeemed))
                .on("/v2/invoices/ksef/", (e, n) -> Stub.respond(e, 200, "application/xml", XML, Map.of()));
                var transport = transport(stub.origin(), settings(false))) {
            var sdk = KsefSdkConfiguration.createClient(stub.origin(), transport);
            var auth = new KsefAuthentication(sdk, null, CLOCK, CONTEXT_NIP, "", true);
            var tokens = auth.authenticate(CONTEXT_NIP);
            assertTrue(tokens.accessValidUntil().isAfter(NOW));
            var result = KsefInboundOneShot.read(settings(false), sdk,
                    InboundAccessToken.issuedFor(CONTEXT, tokens.accessToken()), CLOCK);
            assertEquals("PASS", result.outcome());
            assertEquals(5, transport.requests());
            assertEquals("CONFIRMED", transport.invoiceRead());
            assertEquals(1, stub.requests().stream().filter(r -> r.path().endsWith("/redeem")).count());
            assertEquals("Bearer " + FAKE_JWT, stub.requests().getLast().authorization());
        }
    }

    @Test void authStatusCapIncludesAllThreePollsAndRedeemCannotBeRepeated() throws Exception {
        try (var stub = new Stub().on("/v2/auth/", (e, n) -> Stub.json(e, 200, "{}".getBytes(StandardCharsets.UTF_8)));
                var transport = transport(stub.origin(), settings(false))) {
            var request = HttpRequest.newBuilder(URI.create(stub.origin() + "/v2/auth/20261109-AU-0000000000-0000000000-00")).GET().build();
            for (int i = 0; i < 3; i++) { transport.send(request, HttpResponse.BodyHandlers.ofByteArray()); }
            assertThrows(KsefFailure.class, () -> transport.send(request, HttpResponse.BodyHandlers.ofByteArray()));
            assertEquals(3, stub.requests().size());
        }
        try (var stub = new Stub().on("/v2/auth/token/redeem", (e, n) -> Stub.json(e, 200, "{}".getBytes(StandardCharsets.UTF_8)));
                var transport = transport(stub.origin(), settings(false))) {
            var request = HttpRequest.newBuilder(URI.create(stub.origin() + "/v2/auth/token/redeem"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            transport.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertThrows(KsefFailure.class, () -> transport.send(request, HttpResponse.BodyHandlers.ofByteArray()));
            assertEquals(1, stub.requests().size());
        }
    }

    @Test void uncertainTransportFailureLatchesStopAndNeverRetries() throws Exception {
        var delegate = mock(HttpClient.class);
        when(delegate.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        when(delegate.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new HttpTimeoutException("synthetic"));
        try (var transport = new OneShotHttpClient(delegate, "http://127.0.0.1:1", settings(false))) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:1/v2/invoices/ksef/" + NUMBER)).GET().build();
            assertThrows(HttpTimeoutException.class, () -> transport.send(request, HttpResponse.BodyHandlers.ofByteArray()));
            assertThrows(KsefFailure.class, () -> transport.send(request, HttpResponse.BodyHandlers.ofByteArray()));
            assertEquals("IO_UNCERTAIN", transport.stopReason());
            verify(delegate, times(1)).send(any(), any(HttpResponse.BodyHandler.class));
        }
    }
}
