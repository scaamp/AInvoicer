---
title: "KSeF INBOUND — architektura pobierania faktur zakupowych (KSEF-IN-01)"
date: 2026-11-09
type: guide
status: aktywny
tags: [ainvoicer, ksef, inbound, cap-java, sdk, fa3, hwm, security]
---

# KSeF INBOUND — architektura pobierania faktur zakupowych

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Dziennik KSEF-IN-01](../journal/2026-11-09-ksef-in-01-inbound.md) · [Integracja SDK KSeF](KSEF_JAVA_SDK_INTEGRATION.md) · [Generowanie FA(3)](FA3_XML_GENERATION.md)

> **Stan:** fundament INBOUND zaimplementowany i przetestowany **offline** (lokalna zaślepka HTTP i mock interfejsu SDK).
> Brak wywołań KSeF TEST/DEMO/PROD, brak trwałej bazy, brak endpointu OData, brak harmonogramu.
> Moduł nie jest beanem Spring ani nie jest podłączony do CAP — to zakres KSEF-IN-02.

## 1. Cel i granice

KSEF-IN-01 dostarcza wielokrotnego użytku rdzeń odczytu faktur z KSeF 2.0 w JVM CAP Java:
metadane (`POST /invoices/query/metadata`), pobranie pojedynczego XML (`GET /invoices/ksef/{ksefNumber}`),
bezpieczne parsowanie FA(3), politykę deduplikacji i planer okien High Water Mark.

Poza zakresem: eksport paczek (`/invoices/exports`), deszyfrowanie/ZIP, trwałość (HANA/HDI), `KsefDocumentStore`,
scheduler/outbox, serwis CAP/OData, kokpit UI5, księgowanie FI/MM, nowe role/IAM. OUTBOUND pozostaje nietknięty.

## 2. Architektura modułu

```text
KSeF 2.0 (TEST)                    —— brak wywołań w KSEF-IN-01 (tylko zaślepki) ——
   │  POST /v2/invoices/query/metadata        GET /v2/invoices/ksef/{ksefNumber}
   ▼
pl.akmf.ksef.sdk KSeFClient (SDK MF 3.0.29, bean KsefSdkConfiguration: TEST, Redirect.NEVER, timeout 20 s)
   ▼
customer.cap_java.ksef.inbound
   ├── KsefInboundClient          adapter SDK: 1 wywołanie = 1 żądanie, klasyfikacja błędów, limit rozmiaru
   │     ├── InvoiceMetadataMapper  SDK InvoiceMetadata → KsefInvoiceMetadata (walidacja kontraktu + zakresu)
   │     └── RetrievedInvoice       dokładne bajty XML + SHA-256
   ├── ReadRetryPolicy            ograniczone retry bezpiecznych odczytów (Retry-After, backoff+jitter)
   ├── InvoiceRetrievalService    paginacja segmentu, spójność, downloadVerified, crossCheck XML↔metadane
   ├── fa3/SecureFa3InvoiceParser StAX JDK, bez DTD/encji, FA(3) 1-0E → Fa3InboundInvoice
   └── sync/
         ├── InvoiceDeduplicationPolicy  tożsamość / grant dostępu / fingerprint / konflikt
         ├── InvoiceSyncWindowPlanner    okna PermanentStorage + HWM + isTruncated
         ├── SyncCheckpointRepository    KONTRAKT (compare-and-set) — implementacja w IN-02
         ├── InboundInvoiceSink          KONTRAKT idempotentnego, trwałego przetwarzania — IN-02
         └── InvoiceSyncRunner           checkpoint → okno → segment → sink → commit
   ▼
(KSEF-IN-02) trwały staging + KsefDocumentStore + checkpointy HANA → (później) SAP FI/MM
```

Wszystkie klasy są zwykłymi obiektami z wstrzykiwanymi zależnościami (bez adnotacji Spring), więc nie
zmieniają kontekstu aplikacji ani testów CAP.

## 3. Oficjalny kontrakt KSeF i SDK (zweryfikowany 2026-11-09)

Źródła: `CIRFMF/ksef-api` (commit `ce3670e`, 2026-10-14: `open-api.json`, `pobieranie-faktur/*.md`,
`limity/limity-api.md`, `limity/limity.md`, `faktury/numer-ksef.md`) oraz źródła SDK `ksef-client` **3.0.29**
z lokalnego repozytorium Maven (`ksef-client-3.0.29-sources.jar`). Wersji SDK nie zmieniano.

| Element | Kontrakt | Konsekwencja w kodzie |
| --- | --- | --- |
| `KSeFClient.queryInvoiceMetadata(Integer pageOffset, Integer pageSize, SortOrder, InvoiceQueryFilters, String)` | `POST /invoices/query/metadata`, uprawnienie `InvoiceRead` | jedyna metoda listy metadanych |
| `pageOffset` | **indeks strony** (0,1,2…), nie rekordu | pętla `page++` |
| `pageSize` | 10–250 (OpenAPI; SDK test MF potwierdza komunikat walidacji) | `MetadataQuery` odrzuca inne wartości; domyślnie 250 |
| `dateRange` | max **100 dni**; `restrictToPermanentStorageHwmDate` tylko dla `PermanentStorage` | walidacja + planer dzieli zaległości na okna 100 dni |
| `QueryInvoiceMetadataResponse` | `hasMore`, `isTruncated`, `invoices` wymagane; `permanentStorageHwmDate` stały dla stron zapytania | brak pola = odrzucenie strony; zmiana HWM między stronami = `CORRUPT_RESPONSE` |
| `isTruncated` | limit 10 000 rekordów na zestaw filtrów | kontynuacja od daty ostatniego rekordu, `pageOffset=0` |
| `InvoiceMetadata.netAmount/grossAmount/vatAmount` | OpenAPI `number/double`; SDK `Double`; **`vatAmount` w PLN** | `BigDecimal.valueOf(double)`, kwoty informacyjne; autorytatywne z XML |
| `InvoiceMetadata.invoiceHash` | SHA-256 faktury w Base64 (44 znaki) | weryfikacja pobranych bajtów (`INTEGRITY_MISMATCH`) |
| `KSeFClient.getInvoice(String ksefNumber, String)` | `GET /invoices/ksef/{ksefNumber}`, `application/xml`, uprawnienie `InvoiceRead` | SDK buforuje całe body (`ofByteArray`) — limit egzekwowany po odbiorze |
| Ścieżka w SDK | `String.replace` **bez kodowania URL** | `KsefNumbers`: ścisły wzorzec OpenAPI (35 znaków, HEX wielkie) + CRC-8 `KSeFNumberValidator` przed wywołaniem |
| „Nie znaleziono” | **HTTP 400 + `exceptionCode` 21164** (nie 404) | `NOT_FOUND_IN_CONTEXT`; 404 obsługiwane defensywnie tak samo |
| „Jeszcze niedostępna” | HTTP 400 + 21165 | `NOT_YET_AVAILABLE` (bezpieczny odczyt, retry później) |
| Inne 400 | 21405 walidacja, 21183 `from` > HWM przy restrict | `INVALID_REQUEST` z kodem MF |
| Błędy SDK | non-2xx → `ApiException(code, headers)`; zły JSON 2xx → `KsefApiException` kod 0; sieć/timeout → `SystemKSeFSDKException` z przyczyną `IOException`/`HttpTimeoutException` | `KsefInboundClient.classify` |
| Komunikaty SDK | `ApiException.getMessage()` zawiera body; `SystemKSeFSDKException` zawiera URL | `InboundFailure` bez przyczyny, body, URL, NIP i tokenu |

### Eksport paczek (tylko analiza)

`initAsyncQueryInvoice(InvoiceExportRequest, token)` → `POST /invoices/exports` i
`checkStatusAsyncQueryInvoice(referenceNumber, token)` → `GET /invoices/exports/{referenceNumber}` istnieją w SDK 3.0.29
(modele `InvoiceExportPackage` z `isTruncated`, `lastPermanentStorageDate`, `permanentStorageHwmDate`, części AES-256-CBC,
`_metadata.json`). MF rekomenduje eksport do synchronizacji wysokowolumenowej. W KSEF-IN-01 **nie** zaimplementowano
eksportu, deszyfrowania ani ZIP (backlog KSEF-IN-03).

## 4. Autoryzacja i granice tożsamości

1. Business User → CAP: XSUAA, `KsefOperator` — **nie** oznacza uprawnienia `InvoiceRead` w KSeF.
2. Business User → SAP RAP: SAP IAM/DCL — bez zmian.
3. CAP → SAP: communication principal — bez zmian; INBOUND nie łączy się z SAP.
4. CAP → KSeF: osobny JWT w kontekście podatnika — uzyskiwany przez istniejący `KsefAuthentication`.

Mechanizmy w kodzie:

- `KsefTaxpayerContext(environment, nip)` — tylko z konfiguracji backendu związanej z credentialem; NIP z sumą kontrolną.
  Nigdy z UI5/OData.
- `InboundAccessToken` — JWT związany z kontekstem; wartość package-private, `toString()` zredagowany, brak equals po wartości.
  Klient odrzuca token innego kontekstu i kontekst innego środowiska **przed** HTTP (`SCOPE_MISMATCH`).
- Mapper odrzuca odpowiedź, która nie dotyczy kontekstu (SELLER: NIP sprzedawcy; BUYER: NIP nabywcy, gdy typ `Nip`).
- `downloadVerified` odmawia użycia metadanych odczytanych w innym kontekście.
- Brak endpointu OData, akcji „pobierz wszystko dla NIP”, nowych ról i dostępu przeglądarki do XML.

**Integration patch (IN-02):** `KsefAuthentication.Tokens.accessToken()` jest package-private w `customer.cap_java.ksef`.
Zamiast refaktoryzacji należy dodać w tym pakiecie mały adapter, np.:

```java
// customer.cap_java.ksef.KsefInboundAccess (IN-02)
public InboundAccessToken authenticate(KsefTaxpayerContext context) {
    var tokens = authentication.authenticate(context.nip()); // NIP z konfiguracji, nie z żądania
    return InboundAccessToken.issuedFor(context, tokens.accessToken());
}
```

## 5. Metadata query (Phase A)

`MetadataQuery.incremental(role, from, to)` = `PermanentStorage`, `Asc`, `restrictToPermanentStorageHwmDate=true`,
`pageSize=250`, daty w UTC. `KsefInboundClient.queryMetadataPage` wykonuje dokładnie jedno żądanie i odrzuca całą stronę,
gdy: brakuje wymaganych flag, stron jest więcej niż `pageSize`, `hasMore/isTruncated=true` przy pustej liście,
którakolwiek pozycja ma niepoprawny numer KSeF, brak numeru faktury, daty wystawienia, PermanentStorage, NIP sprzedawcy,
typu nabywcy, formCode, invoiceType, invoiceHash (Base64 SHA-256) albo walutę spoza `[A-Z]{3}`.

`InvoiceRetrievalService.fetchSegment` przechodzi strony do `hasMore=false` (segment kompletny) albo `isTruncated=true`
(segment niekompletny). Dodatkowe kontrole: stały HWM, rosnąca data sortowania, rekordy nie wcześniejsze niż `from`,
górna granica liczby stron, ten sam numer KSeF z innym `invoiceHash` = konflikt. Błąd na dowolnej stronie odrzuca segment.

`KsefInvoiceMetadata` niesie: środowisko, NIP kontekstu, rolę/kierunek, numer KSeF, numer faktury, daty (issue,
invoicing, acquisition = data otrzymania, permanentStorage), sprzedawcę, nabywcę (typ + wartość), kwoty `BigDecimal`,
walutę, typ faktury, formCode, hash, flagi. Brakujące pola = `null`, bez domyślnych wartości.

## 6. Pobranie XML (Phase B)

`KsefInboundClient.downloadInvoice` → `RetrievedInvoice` (kopie tablic, SHA-256 hex/Base64, `toString` bez treści).
Domyślny limit 3 MiB (MF: 1 MB bez załącznika, 3 MB z załącznikiem; twardy sufit konfiguracji 16 MiB).
`InvoiceRetrievalService.downloadVerified` porównuje w czasie stałym SHA-256 dokładnych bajtów z `invoiceHash`
z metadanych. Bajty nie są dekodowane ani serializowane ponownie.

> **TO VERIFY LIVE (IN-02):** równość `invoiceHash` z SHA-256 bajtów zwracanych przez `GET /invoices/ksef` wynika
> z opisu OpenAPI („Skrót SHA256 faktury”). Kontrola jest fail-closed; pierwsze odczyty TEST muszą to potwierdzić.

### Klasyfikacja błędów (`InboundFailure.Category`)

| Sytuacja | Kategoria | Retry odczytu |
| --- | --- | --- |
| 401 | `AUTHENTICATION` | nie (ponowne uwierzytelnienie) |
| 403 | `AUTHORIZATION` — **nie** oznacza braku faktury | nie |
| 400 + 21164 / 404 | `NOT_FOUND_IN_CONTEXT` — nie dowodzi globalnego nieistnienia | nie |
| 400 + 21165 | `NOT_YET_AVAILABLE` | tak |
| 429 (+ `Retry-After` s lub data HTTP; brak → 60 s) | `RATE_LIMITED` | tak, wg `retryNotBefore` |
| 5xx | `SERVER_ERROR` | tak |
| `HttpTimeoutException` | `TIMEOUT` | tak |
| inny `IOException` | `NETWORK` | tak |
| przerwanie wątku | `INTERRUPTED` (flaga przywrócona) | nie |
| 3xx | `UNEXPECTED_REDIRECT` — brak przejścia na host z `Location` | nie |
| inne 400 | `INVALID_REQUEST` (+ kod MF) | nie |
| nieczytelny/niezgodny kontrakt | `CORRUPT_RESPONSE` | nie |
| za duży dokument | `PAYLOAD_TOO_LARGE` | nie |
| hash/dane niezgodne | `INTEGRITY_MISMATCH` | nie |
| token/kontekst/środowisko/strona | `SCOPE_MISMATCH` | nie |
| nieznany wyjątek | `UNCLASSIFIED` (fail closed) | nie |

`ReadRetryPolicy.defaults`: 3 próby, backoff 1 s/2 s + jitter ≤ 250 ms, czekanie w wątku ≤ 10 s. Dłuższy `Retry-After`
(typowy przy limicie 20 req/h na metadane) zwraca błąd z `retryNotBefore` do trwałego harmonogramu — bez blokowania wątku.

## 7. Walidacja i parser FA(3) (Phase C)

`SecureFa3InvoiceParser` (pakiet `inbound.fa3`) jest niezależny od generatora OUTBOUND.

Bezpieczeństwo XML: `XMLInputFactory.newDefaultFactory()` (StAX JDK, niezależnie od classpath), `SUPPORT_DTD=false`,
`IS_SUPPORTING_EXTERNAL_ENTITIES=false`, `ACCESS_EXTERNAL_DTD=""`, resolver odrzucający każde odwołanie; zdarzenie DTD →
`DOCTYPE_FORBIDDEN`, encja niepredefiniowana → `ENTITY_FORBIDDEN`/`MALFORMED_XML`. Limity: bajty (3 MiB), głębokość 32,
400 000 elementów, 8 192 znaki tekstu pola, 10 000 wierszy (`FaWiersz maxOccurs`). Tylko UTF-8. Każdy element musi być
w namespace FA(3) `http://crd.gov.pl/wzor/2025/06/25/13775/` (XSD nie ma `xsd:any`). Wyjątki podają tylko kod i ścieżkę
elementu, bez treści; parser nie loguje.

Rozpoznanie wariantu: inny element główny → `UNSUPPORTED_DOCUMENT`; inny namespace (np. FA(2) `…/2023/06/29/12648/`) albo
`KodFormularza` ≠ `FA (3)`/`1-0E`/`FA`, `WariantFormularza` ≠ 3 → `UNSUPPORTED_SCHEMA`. Nagłówek musi być pierwszy.

Odczytywane pola (nazwy i typy z XSD FA(3) 1-0E): `P_2`, `P_1` (TDataT 2006–2050), `KodWaluty`, `RodzajFaktury`
(VAT/KOR/ZAL/ROZ/UPR/KOR_ZAL/KOR_ROZ), `Podmiot1` (NIP, Nazwa), `Podmiot2` (dokładnie jedna alternatywa
`xsd:choice`: NIP | KodUE+NrVatUE | KodKraju?+NrID | BrakID=1), `P_13_x`, `P_14_x`, `P_14_xW`, `P_15` (TKwotowy),
`FaWiersz` (NrWierszaFa, UU_ID, P_7, P_8A, P_8B TIlosci, P_9A/P_9B TKwotowy2, P_11, P_11A, P_11Vat, P_12).
Brak wymaganego pola → `MISSING_REQUIRED`; powtórzenie → `DUPLICATE_ELEMENT`; zły format (wykładnik, za dużo miejsc po
przecinku, zła data, NIP) → `INVALID_VALUE`. Kwoty jako `BigDecimal` z tekstu.

FA(3) nie ma pól „suma netto/VAT” — parser zwraca rozbicie `P_13_x/P_14_x/P_14_xW` i `P_15` bez wyliczeń.
To nie jest pełna walidacja XSD; pełną zgodność można w IN-02 sprawdzić istniejącym `Fa3SchemaValidator`.
`InvoiceRetrievalService.crossCheck` porównuje XML z metadanymi (numer, data, NIP sprzedawcy, waluta, NIP nabywcy).

## 8. Strategia deduplikacji (Phase D)

| Pojęcie | Klucz | Reguła |
| --- | --- | --- |
| Tożsamość dokumentu | `environment + ksefNumber` | TEST i PROD nigdy się nie mieszają |
| Uprawnienie (grant) | dokument + NIP kontekstu + rola | każdy (kontekst, rola) osobno; brak grantu = brak dostępu |
| Synchronizacja | checkpoint per `(environment, contextNip, role)` | planer HWM, niezależne zakresy |
| Fingerprint | SHA-256 dokładnych bajtów + rozmiar | inny hash dla tej samej tożsamości = `INTEGRITY_CONFLICT` |

`InvoiceDeduplicationPolicy.decide(known, observation)` → `NEW_DOCUMENT`, `UPDATE_KNOWN` (dopisz grant/fingerprint),
`ALREADY_KNOWN`, `INTEGRITY_CONFLICT` (inna treść, numer faktury, NIP sprzedawcy lub identyfikator nabywcy) — bez nadpisania.
`mayAccess`/`mayAccessAs` nie rozszerzają dostępu na inne NIP ani role.

## 9. Plan HWM (Phase E)

Scenariusz MF „tylko do HWM”: `PermanentStorage`, `Asc`, `restrictToPermanentStorageHwmDate=true`, okna przyległe.

| Wynik segmentu | Następne `from` |
| --- | --- |
| kompletny, HWM obecny | `permanentStorageHwmDate` (UTC), ale nie dalej niż jawne `to` |
| `isTruncated=true` | data ostatniego rekordu (odpowiednik `LastPermanentStorageDate`); rekordy graniczne pobierane ponownie i deduplikowane |
| obcięty bez postępu (ostatnia data = `from`) | HOLD `TRUNCATED_NO_PROGRESS` |
| brak HWM | HOLD `HWM_MISSING` |
| HWM < `from` / = `from` / w przyszłości | HOLD `HWM_REGRESSION` / `HWM_UNCHANGED` / `CONTINUATION_IN_FUTURE` |
| sink nie potwierdził utrwalenia | HOLD `PROCESSING_INCOMPLETE` |

Zaległość > 100 dni dzielona jest na okna z jawnym `to = from + 100 dni`. Checkpoint zatwierdza `SyncCheckpointRepository.commit`
(compare-and-set po wersji) dopiero po bezbłędnym przetworzeniu wszystkich dokumentów okna przez sink. Konflikt wersji =
`CONCURRENT_UPDATE`, bez nadpisania. **Nie deklarujemy exactly-once** — wymaga trwałej bazy i testu współbieżności (IN-02).

## 10. Limity i harmonogram (MF, limity-api.md z 2026-09-14)

| Endpoint | req/s | req/min | req/h (PROD) |
| --- | --- | --- | --- |
| `POST /invoices/query/metadata` | 8 | 16 | **20** |
| `GET /invoices/ksef/{ksefNumber}` | 8 | 16 | 64 |
| `POST /invoices/exports` | 8 | 16 | 20 |
| `GET /invoices/exports/{referenceNumber}` | 10 | 60 | 600 |

TEST ma limity 10× wyższe, DEMO jak PROD. Limity liczone per (kontekst, IP), w oknie przesuwnym; 429 + `Retry-After`;
powtarzane przekroczenia wydłużają blokadę. Zalecenia MF: interwał ≥ 15 min per typ podmiotu, pobieranie pojedynczych faktur
tylko przy niskim wolumenie, operacje użytkownika na lokalnej bazie (nie bezpośrednio w KSeF).

## 11. Kontrakt przyszłej persystencji

- `SyncCheckpointRepository` — `find(scope)`, `commit(scope, expectedVersion, continuationFrom, committedAt)`;
  unikalność `(environment, contextNip, role)`, atomowe CAS, brak cofania.
- `InboundInvoiceSink` — idempotentny; sukces tylko po trwałym zapisie dokumentu.
- Implementacja in-memory istnieje **wyłącznie w testach** (`InMemoryCheckpointRepository`).

## 12. Rekomendacja stagingu faktur zakupowych (nie implementowano)

RAP pozostaje źródłem prawdy dla faktur istniejących w SAP (ADR-0005 bez zmian). Faktury z KSeF, których jeszcze nie ma
w SAP, potrzebują osobnego stagingu w CAP — nie rozszerzania `KsefInvoices` (ma `invoiceNumber String(100)` zamiast 256 i
`buyerNip String(10)`, a nabywca może mieć identyfikator VatUe/Other/brak):

- `KsefInboundDocuments` (`environment`, `ksefNumber` UNIQUE razem, `invoiceNumber(256)`, `issueDate`, `acquisitionDate`,
  `permanentStorageDate`, `sellerNip`, `buyerIdentifierType/Value`, `formCode`, `invoiceType`, `sha256`, `sizeBytes`,
  `xmlStorageRef`, `parseStatus`, `integrityStatus`);
- `KsefInboundAccessGrants` (`document`, `contextNip`, `role`, UNIQUE razem, `firstSeenAt`);
- `KsefInboundSyncCheckpoints` (`environment`, `contextNip`, `role` UNIQUE, `continuationFrom`, `version`, `committedAt`);
- `KsefInboundIntegrityConflicts` (append-only) dla `INTEGRITY_CONFLICT`;
- późniejszy status biznesowy zakupu (np. `RECEIVED → REVIEWED → POSTED_IN_SAP`) oddzielony od statusu technicznego.

Odczyt stagingu w CAP musi filtrować po grantach kontekstu użytkownika (atrybut XSUAA NIP), nie po samym `ksefNumber`.

## 13. KSEF-IN-02 — Persistence & Synchronization (backlog, nie implementowano)

1. **Decyzja ADR**: model stagingu INBOUND (sekcja 12), właściciel danych, retencja XML, relacja do `KsefInvoices`.
2. **CDS**: encje `KsefInboundDocuments`, `KsefInboundAccessGrants`, `KsefInboundSyncCheckpoints`,
   `KsefInboundIntegrityConflicts` z ograniczeniami unikalności; brak publikacji w `KsefService` w tym kroku.
3. **CAP persistence**: implementacja `SyncCheckpointRepository` (CQN, `UPDATE … WHERE version = ?`, INSERT z unikalnością),
   test H2 + test równoległy (dwa wątki/transakcje → jeden commit).
4. **Sink**: `downloadVerified` → `KsefDocumentStore.put` (trwały magazyn, nie dysk CF) → `SecureFa3InvoiceParser` +
   `crossCheck` → decyzja `InvoiceDeduplicationPolicy` → zapis w jednej transakcji; dokument nieobsługiwanego schematu
   zapisany z `parseStatus=UNSUPPORTED_SCHEMA`, nie odrzucany po cichu.
5. **Adapter tokenu**: `KsefInboundAccess` w pakiecie `customer.cap_java.ksef` (sekcja 4), NIP wyłącznie z konfiguracji.
6. **Bean wiring**: `@Configuration` z `KsefInboundClient`, `InvoiceRetrievalService`, planerem i runnerem; flaga
   `ainv.ksef.inbound.enabled=false` domyślnie; brak aktywacji na CF bez zgody.
7. **Harmonogram**: trwały lease/lock per `SyncScope` (wiele instancji CF), interwał ≥ 15 min per rola,
   respektowanie `retryNotBefore`, osobny słabszy cykl dla SUBJECT3/AUTHORIZED.
8. **Live TEST (po zgodzie)**: syntetyczna faktura zakupowa w KSeF TEST → metadane BUYER → pobranie → potwierdzenie,
   że `invoiceHash` = SHA-256 bajtów; potwierdzenie zachowania `restrictToPermanentStorageHwmDate` i 21164 vs 403.
9. **Obserwowalność**: metryki (strony, 429, konflikty), audyt bez treści XML/NIP w logach.

KSEF-IN-03: eksport paczek (`/invoices/exports`), AES-256-CBC przez SDK, bezpieczny ZIP (limity, zip-slip, zip-bomb),
`_metadata.json`, HWM z `InvoiceExportPackage`. KSEF-IN-04: kokpit zakupów (UI5, odczyt z stagingu) i integracja SAP FI/MM.

## 14. Ograniczenia bieżącej implementacji

- Brak jakiegokolwiek wywołania KSeF; kontrakt potwierdzony z OpenAPI, źródeł SDK i zaślepek, nie z wdrożonym API.
- Kwoty metadanych mają precyzję `double` (ograniczenie SDK); autorytatywne wartości tylko z XML.
- Limit rozmiaru XML jest egzekwowany po zbuforowaniu odpowiedzi przez SDK (timeout 20 s ogranicza transfer).
- Kontrola stron dla THIRD_PARTY/AUTHORIZED nie jest wykonywana (brak danych o roli kontekstu w `thirdSubjects`).
- Parser nie jest pełnym walidatorem XSD i nie obsługuje FA(2)/PEF/FA_RR (jawny `UNSUPPORTED_SCHEMA`).
- Paginacja offsetowa przy przerwaniu w środku segmentu wymaga powtórzenia całego okna (bezpieczne dzięki deduplikacji,
  kosztowne przy limicie 20 req/h).
- Brak trwałości, harmonogramu, lease, metryk; brak exactly-once.
