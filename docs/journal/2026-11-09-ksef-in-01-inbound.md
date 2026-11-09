---
title: "Dziennik — KSEF-IN-01 Inbound Invoice Retrieval Core"
date: 2026-11-09
type: journal
status: zakonczony
tags: [ainvoicer, journal, ksef, inbound, cap-java, fa3, hwm, security]
---

# Dziennik — KSEF-IN-01 Inbound Invoice Retrieval Core

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Architektura INBOUND](../guides/KSEF_INBOUND_ARCHITECTURE.md)

## Date

2026-11-09 (Europe/Warsaw).

## Branch

`feature/ksef-in-01-inbound-read-core` w osobnym worktree `D:\AInvoicer-ksef-in-01`, utworzonym z
`origin/feature/cap-07b-live-btp` @ `9d7a380dcf1c07cd27cad34f3b9ca6e397cac6cf` (zweryfikowane po `git fetch`).
Sprint równoległy do CAP-07C (Codex, worktree `D:\AInvoicer`, branch `feature/cap-07c-autonomous-hardening`) — nietknięty.

## Goal

Fundament pobierania faktur zakupowych z KSeF 2.0 w CAP Java, offline: rzeczywisty kontrakt SDK, adapter metadanych,
pobranie XML z SHA-256, bezpieczny parser FA(3), negatywna macierz błędów, deduplikacja, planer HWM, kontrakt checkpointów,
dokumentacja. Bez KSeF LIVE, SAP WRITE, zmian BTP, OData, ról, persystencji.

## Initial State

- `origin/feature/cap-07b-live-btp` = `9d7a380` (zgodne z poleceniem).
- Istniejące worktree: `D:\AInvoicer` (Codex, CAP-07C, lokalne zmiany), `C:\Users\jkupi\AppData\Local\Temp\ainvoicer-cap07c-validation`
  (detached), `D:\AInvoicer-auth`, `D:\AInvoicer-fa3`, `D:\AInvoicer-ui5`. Ścieżka `D:\AInvoicer-ksef-in-01` i branch wolne.
- SDK `pl.akmf.ksef-sdk:ksef-client:3.0.29` w lokalnym `~/.m2` (z `-sources.jar`); JDK 25.0.4.1, Maven 3.9.16.
- Bazowy `mvn clean verify` w nowym worktree: **237/237 PASS**, BUILD SUCCESS.

## Contract verification

- SDK 3.0.29 (źródła): `queryInvoiceMetadata(Integer, Integer, SortOrder, InvoiceQueryFilters, String)`,
  `getInvoice(String, String)`, `initAsyncQueryInvoice`, `checkStatusAsyncQueryInvoice`; modele `InvoiceMetadata`,
  `QueryInvoiceMetadataResponse` (`hasMore`, `isTruncated`, `permanentStorageHwmDate`), `InvoiceQueryDateRange`
  (`restrictToPermanentStorageHwmDate`), `KSeFNumberValidator`; `ExceptionHandler`/`ApiException`/`SystemKSeFSDKException`.
- Ustalenia krytyczne: kwoty metadanych jako `Double`; `getInvoice` wstawia numer do ścieżki bez kodowania i buforuje body;
  `KSeFNumberValidator` sprawdza tylko długość i CRC; komunikaty wyjątków SDK zawierają body/URL.
- `CIRFMF/ksef-api` @ `ce3670e`: `open-api.json` — `pageSize` 10–250, zakres dat ≤ 100 dni, „nie znaleziono” = 400/21164,
  „jeszcze niedostępna” = 400/21165, `vatAmount` w PLN, `invoiceHash` = SHA-256 Base64, `KsefNumber` pattern;
  `pobieranie-faktur/hwm.md`, `przyrostowe-pobieranie-faktur.md`, `limity/limity-api.md` (metadane 20 req/h PROD,
  pobranie 64 req/h), `limity/limity.md` (1 MB / 3 MB), `faktury/numer-ksef.md` (35 znaków, CRC-8 0x07).
- Test integracyjny SDK MF (`v3.0.29`) potwierdza `pageOffset` jako indeks strony (`pageOffset += 1`).

## Implementation

Nowe pliki (wyłącznie):

- `cap-java/srv/src/main/java/customer/cap_java/ksef/inbound/` — `KsefEnvironment`, `KsefSubjectRole`, `KsefTaxpayerContext`,
  `InboundAccessToken`, `KsefNumbers`, `InboundFailure`, `MetadataQuery`, `KsefInvoiceMetadata`, `InvoiceMetadataMapper`,
  `MetadataPage`, `KsefInboundClient`, `RetrievedInvoice`, `ReadRetryPolicy`, `MetadataSegment`, `InvoiceRetrievalService`.
- `…/inbound/fa3/` — `SecureFa3InvoiceParser`, `Fa3InboundInvoice`, `Fa3ParseException`.
- `…/inbound/sync/` — `InvoiceDeduplicationPolicy`, `SyncScope`, `SyncCheckpoint`, `SyncCheckpointRepository`,
  `InboundInvoiceSink`, `InvoiceSyncWindowPlanner`, `InvoiceSyncRunner`.
- Testy w `cap-java/srv/src/test/java/customer/cap_java/ksef/inbound/` (+ `fa3/`, `sync/`), fixture
  `cap-java/srv/src/test/resources/ksef/inbound/fa3-purchase-synthetic.xml` (syntetyczny, zgodny z oficjalnym XSD).
- `docs/guides/KSEF_INBOUND_ARCHITECTURE.md`, ten dziennik.

Brak adnotacji Spring — kontekst aplikacji CAP bez zmian. Nie modyfikowano żadnego istniejącego pliku.

## Commands Executed

```text
git fetch origin; git worktree list; git worktree add -b feature/ksef-in-01-inbound-read-core D:/AInvoicer-ksef-in-01 origin/feature/cap-07b-live-btp
unzip ksef-client-3.0.29-sources.jar (scratchpad); curl raw.githubusercontent.com/CIRFMF/ksef-api/main/{…}.md, open-api.json
curl raw.githubusercontent.com/CIRFMF/ksef-client-java/v3.0.29/demo-web-app/…/QueryInvoiceIntegrationTest.java
mvn -f cap-java/pom.xml clean verify -Dcds.cds.skipVersionCheck=true        # baseline 237/237
mvn -f cap-java/pom.xml compile -o …
mvn -f cap-java/pom.xml test -o -Dtest=… (testy INBOUND)                     # 41 + 36 PASS
mvn -f cap-java/pom.xml clean verify -Dcds.cds.skipVersionCheck=true        # 314/314 PASS
```

Żadnych wywołań `api-test.ksef.mf.gov.pl` ani innego środowiska KSeF; tylko publiczne repozytoria GitHub MF.

## Test Results

| Klasa testowa | Testy | Wynik |
| --- | --- | --- |
| `KsefInboundClientHttpContractTest` (prawdziwy klient HTTP SDK z produkcyjnej fabryki + zaślepka 127.0.0.1) | 14 | PASS |
| `InvoiceRetrievalServiceTest` (mock interfejsu `KSeFClient`) | 11 | PASS |
| `InboundPrimitivesTest` | 11 | PASS |
| `SecureFa3InvoiceParserTest` | 18 | PASS |
| `InvoiceDeduplicationPolicyTest` | 7 | PASS |
| `InvoiceSyncWindowPlannerTest` | 9 | PASS |
| `InvoiceSyncRunnerTest` | 7 | PASS |
| **INBOUND razem** | **77** | **PASS** |
| **`mvn clean verify` (cały CAP)** | **314** (237 + 77) | **PASS**, BUILD SUCCESS, JAR zbudowany |

Macierz z polecenia → test:

| Scenariusz | Dowód |
| --- | --- |
| Poprawna strona / pusta lista | `validMetadataPageIsMappedAndRequestMatchesOfficialContract`, `emptyListIsAValidCompletePage` |
| Paginacja bez utraty | `paginationCollectsAllPagesWithoutLossInOrder` |
| Ucięty wynik ≠ kompletny | `truncatedResultIsNotComplete` |
| 401 / 403 / 404 (+21164) | `httpErrorsAreClassifiedWithoutLeakingBodyOrToken`, `downloadErrorsDistinguishNotFoundFromAccessAndAvailability` |
| 429 + Retry-After | `rateLimitCarriesRetryAfterAndIsTransient`, `rateLimitedPageIsRetriedAfterShortRetryAfter`, `longRetryAfterIsLeftToTheScheduler` |
| Timeout bez utraty checkpointu | `timeoutIsClassifiedAsTransientTimeout`, `timeoutDuringFetchKeepsCheckpoint` |
| Uszkodzone XML / XXE / duży XML / nieobsługiwana schema | `malformedXmlIsRejected`, `xxeWithLocalFileIsRejectedWithoutReadingTheFile`, `externalDtdAndParameterEntitiesAreNeverFetched`, `entityExpansionBombIsRejectedQuickly`, `inputSizeLimitIsEnforcedBeforeParsing`, `oversizedInvoiceIsRejected`, `unsupportedSchemasAreExplicitlyRecognised` |
| SHA-256 integralności | `downloadReturnsExactBytesAndSha256`, `downloadVerifiedChecksKsefHashOfExactBytes` |
| Ten sam numer — bez duplikatu / inny hash — konflikt | `sameDocumentReadAgainDoesNotCreateAnotherLogicalRecord`, `sameKsefNumberWithDifferentHashIsIntegrityConflictAndNotOverwritten`, `duplicatesAcrossPagesAreSkippedButHashConflictIsIntegrityError` |
| Dwie role podmiotu | `twoRolesProduceTwoGrantsWithoutMixingAccess`, `checkpointsOfDifferentRolesAreIndependent` |
| HWM normalny / `isTruncated` / brak HWM | `completeSegmentAdvancesToHwmInUtc`, `truncatedSegmentContinuesFromLastRecordDate`, `truncatedWindowContinuesFromLastDateAndBoundaryRecordIsDeduplicated`, `missingHwmOrUnpersistedWindowNeverMovesCheckpoint`, `missingHwmHoldsCheckpoint` |
| Błąd przetwarzania / retry okna | `failureWhileProcessingLeavesCheckpointUncommittedAndRetryIsIdempotent` |
| Brak przekierowania na niezaufany host | `redirectIsNotFollowedToAnotherHost` |
| Obcy token/środowisko/NIP | `tokenOrEnvironmentFromAnotherScopeIsRejectedBeforeHttp`, `responseForAnotherBuyerIsAScopeMismatch`, `downloadUsingMetadataOfAnotherContextIsRejectedBeforeHttp` |

Uwaga do rodzaju dowodów: to są testy **mock / lokalne**; żaden z nich nie dowodzi zachowania wdrożonego KSeF TEST.

## Security

- Brak sekretów: tokeny w testach to syntetyczny napis z markerem; testy sprawdzają, że marker, body odpowiedzi, host i NIP
  nie trafiają do wyjątków.
- XML: StAX JDK bez DTD/encji, resolver blokujący; test XXE pliku lokalnego (marker nie wycieka), zewnętrzne DTD i encje
  parametryczne (0 żądań do zaślepki), billion laughs < 5 s.
- Brak publicznego endpointu, brak zmian XSUAA/IAM/DCL, brak dostępu przeglądarki do XML.

## Concurrent Development

- Worktree Codexa `D:\AInvoicer` nie był modyfikowany (tylko odczyt `AGENTS.md` i `git worktree list`/`fetch`).
  Kilka poleceń odczytu wykonano, gdy powłoka po resecie wskazywała ten katalog — wyłącznie odczyty, bez zapisu i bez zmiany brancha.
- Celowo niezmienione: `mta.yaml`, `deploy/`, `approuter/`, `.github/`, `sapui5/`, `application.yaml`, `security/`,
  `db/schema.cds`, `srv/*.cds`, `pom.xml`, `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/README.md`, `README.md`,
  `docs/architecture/`, ADR-0005, wszystkie klasy OUTBOUND (`KsefAuthentication`, `KsefSdkConfiguration`, `fa3/*`).
- Testy używają refleksji do produkcyjnej fabryki `KsefSdkConfiguration.createClient(String)` (package-private) zamiast jej zmiany.

## Risks / Limitations

- Kontrakt potwierdzony z OpenAPI/SDK/zaślepek — **nie** z wdrożonym KSeF TEST (zachowanie 21164 vs 403,
  `invoiceHash` = SHA-256 bajtów `GET /invoices/ksef`, `restrictToPermanentStorageHwmDate` — TO VERIFY LIVE).
- Precyzja kwot metadanych ograniczona przez `Double` w SDK.
- Brak trwałości, harmonogramu, lease; exactly-once niedeklarowane.
- Limit 20 req/h dla metadanych na PROD ogranicza paginację; przerwanie segmentu wymaga powtórzenia okna.

## Integration patch (do wykonania po scaleniu, poza tym branchem)

1. `docs/README.md` — dodać linki: przewodnik `guides/KSEF_INBOUND_ARCHITECTURE.md` i ten dziennik.
2. `docs/STATUS.md` — wpis KSEF-IN-01 (offline PASS 77 testów, 314 całość; LIVE NOT EXECUTED).
3. `docs/ROADMAP.md` — pozycje KSEF-IN-02/03/04 z przewodnika (sekcja 13).

Te pliki są równolegle modyfikowane przez CAP-07C, dlatego nie zmieniano ich tutaj.

## Next Step

KSEF-IN-02 — Persistence & Synchronization (backlog w [przewodniku, sekcja 13](../guides/KSEF_INBOUND_ARCHITECTURE.md#13-ksef-in-02--persistence--synchronization-backlog-nie-implementowano)):
ADR modelu stagingu → encje CDS → `SyncCheckpointRepository` na CQN z testem współbieżności → idempotentny sink z
`KsefDocumentStore` → adapter tokenu `KsefInboundAccess` → wiring z flagą OFF → (po zgodzie) jeden syntetyczny odczyt KSeF TEST.
