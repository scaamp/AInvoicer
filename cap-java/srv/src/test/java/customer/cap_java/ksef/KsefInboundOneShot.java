package customer.cap_java.ksef;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

import customer.cap_java.ksef.inbound.*;
import customer.cap_java.ksef.inbound.fa3.*;
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient;

/** Harness wyłącznie w test classpath: bez Spring, storage, DB, workera i schedulera. */
final class KsefInboundOneShot {
    enum Scenario { NUMBER, BUYER }
    enum AuthMethod { TOKEN, XADES }

    record Settings(KsefTaxpayerContext context, Scenario scenario, AuthMethod authMethod,
            String number, MetadataQuery query, int maxRequests) {
        /** Zgoda i cały zakres muszą być poprawne przed konstrukcją SDK/crypto (crypto robi HTTP). */
        static Settings approved(Map<String, String> env, Clock clock) {
            require("true".equals(env.get("AINV_KSEF_ONESHOT_AUTH_APPROVED")), "AUTH_APPROVAL_REQUIRED");
            require("true".equals(env.get("AINV_KSEF_ONESHOT_READ_APPROVED")), "READ_APPROVAL_REQUIRED");
            require("TEST".equals(env.get("AINV_KSEF_ONESHOT_ENVIRONMENT")), "TEST_ONLY");
            String nip = env.get("AINV_KSEF_NIP");
            require(nip != null && nip.equals(env.get("AINV_KSEF_ONESHOT_APPROVED_NIP")), "APPROVED_CONTEXT_REQUIRED");
            try {
                var context = new KsefTaxpayerContext(KsefEnvironment.TEST, nip);
                var scenario = Scenario.valueOf(env.getOrDefault("AINV_KSEF_ONESHOT_SCENARIO", ""));
                var method = AuthMethod.valueOf(env.getOrDefault("AINV_KSEF_ONESHOT_AUTH_METHOD", ""));
                require(method != AuthMethod.TOKEN || !env.getOrDefault("AINV_KSEF_TOKEN", "").isBlank(), "TOKEN_REQUIRED");
                require(method != AuthMethod.XADES || env.getOrDefault("AINV_KSEF_TOKEN", "").isBlank(), "AMBIGUOUS_AUTH_METHOD");
                int limit = Integer.parseInt(env.getOrDefault("AINV_KSEF_ONESHOT_MAX_REQUESTS", "0"));
                require(limit >= 1 && limit <= 9, "REQUEST_LIMIT_1_TO_9_REQUIRED");
                String number = null;
                MetadataQuery query = null;
                if (scenario == Scenario.NUMBER) {
                    number = KsefNumbers.requireValid(env.get("AINV_KSEF_ONESHOT_NUMBER"));
                } else {
                    var from = OffsetDateTime.parse(env.getOrDefault("AINV_KSEF_ONESHOT_FROM", ""));
                    var to = OffsetDateTime.parse(env.getOrDefault("AINV_KSEF_ONESHOT_TO", ""));
                    require(!to.toInstant().isAfter(clock.instant())
                            && Duration.between(from, to).compareTo(Duration.ofDays(7)) <= 0, "WINDOW_MAX_7_DAYS_PAST");
                    query = new MetadataQuery(KsefSubjectRole.BUYER, MetadataQuery.DateType.PERMANENT_STORAGE,
                            from, to, false, 10);
                }
                return new Settings(context, scenario, method, number, query, limit);
            } catch (RuntimeException invalid) {
                // Nie przenosimy wartości konfiguracji ani przyczyn do Surefire.
                throw stopped("INVALID_APPROVED_SCOPE");
            }
        }

        @Override public String toString() { return "OneShotSettings[REDACTED]"; }
    }

    /** Raport zawiera wyłącznie statusy, liczniki i skrót dokładnych bajtów. */
    record ReadResult(String outcome, int bytes, String sha256, String metadataHash,
            String xml, int metadataCount, boolean hasMore, boolean truncated) { }

    /** Jedna strona BUYER (bez paginacji) i najwyżej jeden GET; istniejąca polityka noRetry. */
    static ReadResult read(Settings settings, KSeFClient sdk, InboundAccessToken token, Clock clock) {
        var client = new KsefInboundClient(sdk, KsefEnvironment.TEST,
                KsefInboundClient.DEFAULT_MAX_INVOICE_BYTES, clock);
        var retrieval = new InvoiceRetrievalService(client, ReadRetryPolicy.noRetry(clock));
        KsefInvoiceMetadata metadata = null;
        int count = 0;
        boolean more = false;
        boolean truncated = false;
        if (settings.scenario() == Scenario.BUYER) {
            var page = client.queryMetadataPage(settings.context(), token, settings.query(), 0);
            count = page.invoices().size();
            more = page.hasMore();
            truncated = page.truncated();
            if (count == 0) {
                return new ReadResult("EMPTY", 0, "NONE", "NOT_CHECKED", "NOT_CHECKED", 0, more, truncated);
            }
            // Wybór jest deterministyczny. Nie szukamy kolejnego dokumentu po błędzie/schemacie.
            metadata = page.invoices().getFirst();
            require(!metadata.permanentStorageDate().isBefore(settings.query().from())
                    && !metadata.permanentStorageDate().isAfter(settings.query().to()), "METADATA_OUTSIDE_WINDOW");
        }
        RetrievedInvoice raw = metadata == null
                ? client.downloadInvoice(settings.context(), token, settings.number())
                : retrieval.downloadVerified(settings.context(), token, metadata);
        String hashCheck = metadata == null ? "NOT_CHECKED" : "PASS";
        Fa3InboundInvoice invoice;
        try {
            invoice = new SecureFa3InvoiceParser().parse(raw.content());
        } catch (Fa3ParseException invalid) {
            if (invalid.code() == Fa3ParseException.Code.UNSUPPORTED_SCHEMA
                    || invalid.code() == Fa3ParseException.Code.UNSUPPORTED_DOCUMENT) {
                return new ReadResult("BLOCKED", raw.size(), raw.sha256Hex(), hashCheck,
                        "UNSUPPORTED_SCHEMA", count, more, truncated);
            }
            throw invalid;
        }
        // Numer KSeF pochodzi z API, P_2 z XML; parser sprawdza datę/NIP/format kwot.
        require(invoice.seller().identifier().equals(raw.ksefNumber().substring(0, 10)), "XML_SELLER_NUMBER_MISMATCH");
        require(settings.context().nip().equals(invoice.seller().identifier())
                || (invoice.buyer().identifierType() == Fa3InboundInvoice.IdentifierType.NIP
                    && settings.context().nip().equals(invoice.buyer().identifier())), "XML_CONTEXT_MISMATCH");
        require(invoice.invoiceNumber() != null && !invoice.invoiceNumber().isBlank()
                && invoice.issueDate() != null && invoice.grossAmount() != null, "XML_BASIC_FIELDS_REQUIRED");
        if (metadata != null) {
            retrieval.crossCheck(metadata, invoice);
            // Brutto ma tę samą walutę w XML i metadanych; nie sumujemy nietrywialnych sekcji netto/VAT.
            require(metadata.grossAmount() != null
                    && metadata.grossAmount().compareTo(invoice.grossAmount()) == 0, "XML_GROSS_MISMATCH");
        }
        return new ReadResult("PASS", raw.size(), raw.sha256Hex(), hashCheck, "FA3_BASIC_PASS", count, more, truncated);
    }

    static void require(boolean valid, String code) {
        if (!valid) { throw stopped(code); }
    }

    static KsefFailure stopped(String code) {
        return KsefFailure.invalid("KSEF_ONESHOT_" + code);
    }
}
