package customer.cap_java.ksef.inbound;

import static customer.cap_java.ksef.inbound.InboundFailure.Category.*;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import pl.akmf.ksef.sdk.client.interfaces.KSeFClient;
import pl.akmf.ksef.sdk.client.model.ApiException;
import pl.akmf.ksef.sdk.client.model.exceptions.BadRequestApiException;
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceMetadata;
import pl.akmf.ksef.sdk.client.model.invoice.QueryInvoiceMetadataResponse;
import pl.akmf.ksef.sdk.client.model.util.SortOrder;

/**
 * Adapter odczytu faktur INBOUND nad oficjalnym {@link KSeFClient} (SDK MF 3.0.29, ta sama JVM co CAP).
 *
 * <p>Używane metody SDK (sygnatury zweryfikowane w źródłach 3.0.29):
 * <ul>
 *   <li>{@code queryInvoiceMetadata(Integer pageOffset, Integer pageSize, SortOrder, InvoiceQueryFilters, String accessToken)}
 *       → {@code POST /v2/invoices/query/metadata}, uprawnienie {@code InvoiceRead};</li>
 *   <li>{@code getInvoice(String ksefNumber, String accessToken)} → {@code GET /v2/invoices/ksef/{ksefNumber}},
 *       uprawnienie {@code InvoiceRead}; zwraca surowe bajty XML.</li>
 * </ul>
 *
 * <p>Zasady: jedno wywołanie = jedno żądanie HTTP (bez wewnętrznego retry — patrz {@link ReadRetryPolicy});
 * token musi być wydany dla tego samego kontekstu; kontekst musi mieć to samo środowisko, co klient;
 * błędy SDK są tłumaczone na {@link InboundFailure} bez body/URL/tokenu. Przekierowania nie są śledzone
 * (bean {@code KsefSdkConfiguration} używa {@code Redirect.NEVER}; tutaj 3xx = {@code UNEXPECTED_REDIRECT}).
 *
 * <p>Ograniczenie SDK: {@code getInvoice} buforuje całe body ({@code BodyHandlers.ofByteArray()}), więc limit
 * rozmiaru jest egzekwowany po odebraniu odpowiedzi, a timeout żądania (20 s) ogranicza czas transferu.
 * Klasa nie przechowuje stanu żądań; jest bezpieczna wątkowo przy bezpiecznym wątkowo {@code KSeFClient}.
 */
public class KsefInboundClient {
    /** Limit MF dla faktury z załącznikiem: 3 MB (limity/limity.md, 2026-11-09). */
    public static final int DEFAULT_MAX_INVOICE_BYTES = 3 * 1024 * 1024;
    /** Twardy sufit konfiguracji — kontekst może mieć podniesione limity, ale nie bez ograniczeń. */
    static final int HARD_MAX_INVOICE_BYTES = 16 * 1024 * 1024;
    /** Gdy 429 nie ma czytelnego Retry-After — konserwatywna przerwa zamiast natychmiastowej próby. */
    static final long DEFAULT_RATE_LIMIT_SECONDS = 60;
    static final int MF_INVOICE_NOT_FOUND = 21164;
    static final int MF_INVOICE_NOT_YET_AVAILABLE = 21165;

    private final KSeFClient sdk;
    private final KsefEnvironment environment;
    private final InvoiceMetadataMapper mapper;
    private final int maxInvoiceBytes;
    private final Clock clock;

    public KsefInboundClient(KSeFClient sdk, KsefEnvironment environment, int maxInvoiceBytes, Clock clock) {
        this.sdk = Objects.requireNonNull(sdk);
        this.environment = Objects.requireNonNull(environment);
        if (maxInvoiceBytes <= 0 || maxInvoiceBytes > HARD_MAX_INVOICE_BYTES) {
            throw new IllegalArgumentException("KSEF_INBOUND_MAX_INVOICE_BYTES_INVALID");
        }
        this.maxInvoiceBytes = maxInvoiceBytes;
        this.clock = Objects.requireNonNull(clock);
        this.mapper = new InvoiceMetadataMapper();
    }

    public KsefEnvironment environment() {
        return environment;
    }

    /**
     * Pobiera jedną stronę metadanych. Odpowiedź niezgodna z kontraktem (brak wymaganych flag, więcej
     * pozycji niż {@code pageSize}, {@code hasMore=true} przy pustej stronie, niepoprawna pozycja) jest
     * odrzucana w całości — częściowa strona nigdy nie jest traktowana jako wynik.
     *
     * @param pageOffset indeks strony (0, 1, 2…), nie numer rekordu
     */
    public MetadataPage queryMetadataPage(KsefTaxpayerContext context, InboundAccessToken token, MetadataQuery query,
            int pageOffset) {
        requireScope(context, token);
        Objects.requireNonNull(query, "query");
        if (pageOffset < 0) {
            throw InboundFailure.of(INVALID_REQUEST, "PAGE_OFFSET");
        }
        QueryInvoiceMetadataResponse response;
        try {
            response = sdk.queryInvoiceMetadata(pageOffset, query.pageSize(), SortOrder.ASC, query.toSdkFilters(),
                    token.value());
        } catch (Exception e) {
            throw classify(e, clock);
        }
        if (response == null || response.getHasMore() == null || response.getIsTruncated() == null
                || response.getInvoices() == null) {
            throw InboundFailure.of(CORRUPT_RESPONSE, "PAGE_REQUIRED_FIELDS");
        }
        List<InvoiceMetadata> items = response.getInvoices();
        if (items.size() > query.pageSize()) {
            throw InboundFailure.of(CORRUPT_RESPONSE, "PAGE_OVERSIZED");
        }
        if (items.isEmpty() && (response.getHasMore() || response.getIsTruncated())) {
            throw InboundFailure.of(CORRUPT_RESPONSE, "PAGE_EMPTY_WITH_MORE");
        }
        var mapped = new ArrayList<KsefInvoiceMetadata>(items.size());
        for (InvoiceMetadata item : items) {
            mapped.add(mapper.map(context, query.role(), item));
        }
        var hwm = response.getPermanentStorageHwmDate();
        return new MetadataPage(mapped, response.getHasMore(), response.getIsTruncated(),
                hwm == null ? null : hwm.withOffsetSameInstant(ZoneOffset.UTC));
    }

    /**
     * Pobiera dokładne bajty XML faktury. Numer KSeF jest walidowany przed wywołaniem (wzorzec + CRC),
     * bo SDK wstawia go do ścieżki bez kodowania. Pusta odpowiedź → {@code CORRUPT_RESPONSE};
     * przekroczenie limitu → {@code PAYLOAD_TOO_LARGE}. Poprawność XML sprawdza parser.
     */
    public RetrievedInvoice downloadInvoice(KsefTaxpayerContext context, InboundAccessToken token, String ksefNumber) {
        requireScope(context, token);
        if (!KsefNumbers.isValid(ksefNumber)) {
            throw InboundFailure.of(INVALID_REQUEST, "KSEF_NUMBER");
        }
        byte[] body;
        try {
            body = sdk.getInvoice(ksefNumber, token.value());
        } catch (Exception e) {
            throw classify(e, clock);
        }
        if (body == null || body.length == 0) {
            throw InboundFailure.of(CORRUPT_RESPONSE, "INVOICE_EMPTY");
        }
        if (body.length > maxInvoiceBytes) {
            throw InboundFailure.of(PAYLOAD_TOO_LARGE, null);
        }
        return new RetrievedInvoice(environment, ksefNumber, body);
    }

    private void requireScope(KsefTaxpayerContext context, InboundAccessToken token) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(token, "token");
        if (context.environment() != environment) {
            throw InboundFailure.of(SCOPE_MISMATCH, "ENVIRONMENT");
        }
        if (!token.context().equals(context)) {
            throw InboundFailure.of(SCOPE_MISMATCH, "TOKEN_CONTEXT");
        }
    }

    /**
     * Tłumaczy wyjątek SDK na kategorię. Kontrakt SDK 3.0.29 (zweryfikowany w źródłach):
     * odpowiedź spoza 2xx → {@link ApiException} z kodem HTTP i nagłówkami; nieczytelny JSON odpowiedzi 2xx →
     * {@code KsefApiException} z kodem 0; błąd sieci/timeout/przerwanie → {@code SystemKSeFSDKException}
     * (RuntimeException) z przyczyną {@code IOException}/{@code HttpTimeoutException}/{@code InterruptedException}.
     */
    static InboundFailure classify(Throwable error, Clock clock) {
        if (error instanceof InboundFailure failure) {
            return failure;
        }
        if (error instanceof ApiException api) {
            return classifyHttp(api, clock);
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException) {
                return InboundFailure.of(TIMEOUT, null);
            }
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return InboundFailure.of(INTERRUPTED, null);
            }
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return InboundFailure.of(NETWORK, null);
            }
        }
        return InboundFailure.of(UNCLASSIFIED, error.getClass().getSimpleName());
    }

    private static InboundFailure classifyHttp(ApiException api, Clock clock) {
        int http = api.getCode();
        Integer mfCode = firstMfCode(api);
        Instant notBefore = retryAfter(api.getResponseHeaders(), clock);
        if (http == 0) {
            return new InboundFailure(CORRUPT_RESPONSE, 0, null, null, "UNREADABLE");
        }
        if (http >= 300 && http < 400) {
            return new InboundFailure(UNEXPECTED_REDIRECT, http, null, null, null);
        }
        return switch (http) {
            case 400 -> {
                if (Integer.valueOf(MF_INVOICE_NOT_FOUND).equals(mfCode)) {
                    yield new InboundFailure(NOT_FOUND_IN_CONTEXT, http, mfCode, null, null);
                }
                if (Integer.valueOf(MF_INVOICE_NOT_YET_AVAILABLE).equals(mfCode)) {
                    yield new InboundFailure(NOT_YET_AVAILABLE, http, mfCode, notBefore, null);
                }
                yield new InboundFailure(INVALID_REQUEST, http, mfCode, null, null);
            }
            case 401 -> new InboundFailure(AUTHENTICATION, http, mfCode, null, null);
            case 403 -> new InboundFailure(AUTHORIZATION, http, mfCode, null, null);
            case 404 -> new InboundFailure(NOT_FOUND_IN_CONTEXT, http, mfCode, null, null);
            case 429 -> new InboundFailure(RATE_LIMITED, http, mfCode,
                    notBefore != null ? notBefore : clock.instant().plusSeconds(DEFAULT_RATE_LIMIT_SECONDS), null);
            default -> http >= 500 && http < 600
                    ? new InboundFailure(SERVER_ERROR, http, mfCode, notBefore, null)
                    : new InboundFailure(UNEXPECTED_STATUS, http, mfCode, null, null);
        };
    }

    /** Pierwszy {@code exceptionCode} z formatu legacy albo {@code errors[].code} z Problem Details. */
    private static Integer firstMfCode(ApiException api) {
        var legacy = api.getExceptionResponse();
        if (legacy != null && legacy.getException() != null && legacy.getException().getExceptionDetailList() != null) {
            for (var detail : legacy.getException().getExceptionDetailList()) {
                if (detail != null && detail.getExceptionCode() > 0) {
                    return detail.getExceptionCode();
                }
            }
        }
        if (api instanceof BadRequestApiException bad && bad.getBadRequestProblemDetails() != null
                && bad.getBadRequestProblemDetails().getErrors() != null) {
            for (var detail : bad.getBadRequestProblemDetails().getErrors()) {
                if (detail != null && detail.getCode() > 0) {
                    return detail.getCode();
                }
            }
        }
        return null;
    }

    /** Retry-After: liczba sekund albo data HTTP (RFC 1123); wartość nieczytelna = brak wskazania. */
    static Instant retryAfter(HttpHeaders headers, Clock clock) {
        if (headers == null) {
            return null;
        }
        String value = headers.firstValue("Retry-After").orElse(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            return seconds < 0 ? null : clock.instant().plusSeconds(seconds);
        } catch (NumberFormatException notSeconds) {
            try {
                return ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (RuntimeException notDate) {
                return null;
            }
        }
    }
}
