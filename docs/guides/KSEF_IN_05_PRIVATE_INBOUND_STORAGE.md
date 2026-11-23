---
title: "KSEF-IN-05 — prywatny magazyn INBOUND, manual import i pobranie XML (backend)"
date: 2026-11-23
type: guide
status: offline-implemented-live-not-verified
tags: [ainvoicer, ksef, inbound, storage, hana, cap-java, odata, security, g1, in-05]
---

# KSEF-IN-05 — magazyn INBOUND HANA, import jednej faktury i pobranie XML

[Project Home](../README.md) · [STATUS](../STATUS.md) · [STOR-04 (magazyn)](KSEF_STOR_04_PRIVATE_HANA_STORAGE.md) ·
[Staging INBOUND](KSEF_INBOUND_PERSISTENCE.md) · [IN-03B (CF NO-GO)](KSEF_IN_03B_WORKER_READINESS.md) ·
[IN-04 (one-shot)](KSEF_IN_04_ONE_SHOT_LIVE_READ.md) · [INT-02 (Inbox)](INT_02_INBOUND_FULLSTACK.md) ·
[CAP-07E](CAP_07E_SECURITY_ACCEPTANCE.md)

> **Status 23.11.2026:** zaimplementowane i przetestowane **wyłącznie OFFLINE** (H2, mock SDK/auth, lokalny CAP HTTP,
> offline XSUAA JWT). **Bez** wywołań KSeF, SAP H01, CF, HANA SQL, HDI deploy, service keys i IAM.
> Wszystkie nowe funkcje są **domyślnie wyłączone**. Guard `KSEF_INBOUND_CLOUD_ACTIVATION_NOT_READY` bez zmian.
> Gałąź `beta-01/in-05-storage` (baza `origin/main=603e171`).

## 1. Co zrobiono

| Zadanie | Wynik | Klasy / pliki |
| --- | --- | --- |
| B1 wiring magazynu INBOUND | **zaimplementowane, flaga OFF** | `ksef/storage/KsefInboundHanaStorageConfiguration`, `ksef/storage/InboundDocumentStorage`, `KsefInboundConfiguration`, `CloudHanaReadinessGuard` |
| B2 pipeline offline | **rozszerzony test „offline H2”** | `HanaDocumentStoreInboundPipelineTest` (+1 scenariusz) |
| B3 manual import | **implemented-behind-flag** (wewnętrzny serwis Java, bez OData) | `ksef/inbound/sync/InboundManualImport`, `MetadataQuery` (filtr `ksefNumber`), `InboundSynchronization.finishFailed` |
| B4 pobranie XML | **zaimplementowane, flaga OFF** | `srv/ksef-service.cds` (`InboundInvoiceXml`), `InboundInvoiceXmlHandler`, `InboundGrantScope` |
| B5 testy bezpieczeństwa | **lokalny CAP HTTP + offline JWT** | `InboundInvoiceXmlODataTest`, `InboundInvoiceXmlDisabledODataTest`, `InboundInvoiceXmlUnwiredODataTest`, `XsuaaJwtODataContractTest` |

Nie zmieniono: `db/*.cds`, plików LAB (`db/documents.cds`, `srv/blob-service.cds`, `BlobDocumentsHandler`), `sapui5/**`,
`deploy/**`, `approuter/**`, `mta.yaml`, `LocalTestDocumentStore`, `HanaKsefDocumentStore`, `PersistentInboundInvoiceSink`.

## 2. Flagi konfiguracyjne (`application.yaml`)

| Właściwość | Domyślnie | Znaczenie |
| --- | --- | --- |
| `ainv.ksef.inbound.storage.hana.enabled` | `false` | Rejestruje `InboundDocumentStorage` (scope `INBOUND/TEST`) nad `HanaKsefDocumentStore` |
| `ainv.ksef.inbound.storage.hana.allow-local-h2` | `false` | Dopuszcza H2 **wyłącznie lokalnie** (testy offline); w chmurze ignorowane |
| `ainv.ksef.inbound.xml-download.enabled` | `false` | Włącza odczyt `KsefService.InboundInvoiceXml(<id>)/content`; OFF → 404 |
| `ainv.ksef.inbound.manual-import.enabled` | `false` | Bean `InboundManualImport`; wymaga też `ainv.ksef.inbound.enabled=true` |

`CloudHanaReadinessGuard` (tryb `ainv.deployment.hana-readiness=true`) odrzuca start, gdy `storage.hana.enabled`
lub `xml-download.enabled` jest jawnie `true`. Istniejące flagi (`ainv.ksef.inbound.enabled`, `scheduler.enabled`) bez zmian.

## 3. B1 — wiring magazynu

- **Brak kolizji beanów.** `InboundDocumentStorage` **nie** implementuje `KsefDocumentStore`, więc jedynym kandydatem
  tego typu pozostaje `LocalTestDocumentStore` (także dla OUTBOUND `KsefSubmissionWorker`). Test HTTP z włączonym
  magazynem: dokładnie 1 `InboundDocumentStorage`, dokładnie 1 `KsefDocumentStore` = `LocalTestDocumentStore`.
- **Pierwszeństwo dla INBOUND.** `KsefInboundConfiguration` przyjmuje `ObjectProvider<InboundDocumentStorage>`; jeśli
  bean istnieje, synchronizacja i manual import używają jego `store()` (test: `assertSame` na polu `InboundSynchronization`).
- **Tylko TEST.** `ainv.ksef.inbound.environment` ≠ `TEST` → `KSEF_INBOUND_STORAGE_ENVIRONMENT_UNSUPPORTED`;
  fabryka z `allowedEnvironments = {TEST}`, `forScope(DocumentScope.inbound(TEST))`.
- **Tylko HANA.** Rozpoznanie: `DataSource` → `DatabaseMetaData.getDatabaseProductName()` = `HDB` (ta sama reguła co
  `CloudPersistenceGuard.isHana`). Inaczej start kończy się kodem:

| Sytuacja | Wynik |
| --- | --- |
| HANA (`HDB`), lokalnie lub w chmurze | bean `InboundDocumentStorage[TEST/INBOUND]` |
| H2 lokalnie + `allow-local-h2=true` | bean (wyłącznie testy offline) |
| H2/inna baza lokalnie bez zgody, brak `DataSource` | `KSEF_INBOUND_STORAGE_HANA_REQUIRED` |
| H2 w chmurze (`VCAP_APPLICATION` lub profil `cf|cloud|prod|production`), także z `allow-local-h2=true` | `KSEF_INBOUND_STORAGE_HANA_REQUIRED_ON_CLOUD` |
| błąd połączenia przy odczycie produktu | `KSEF_INBOUND_STORAGE_DATABASE_UNREACHABLE` (bez URL/credentiali) |

- **Brak efektów startowych:** jedyne I/O przy starcie to odczyt nazwy produktu DB. Brak GET KSeF, schedulera,
  publicznego OData dla BLOB i dostępu po samym `storageRef`.
- **Guard CF:** z dedykowanym magazynem i `ainv.ksef.inbound.enabled=true` (także ze schedulerem i manual import)
  na `cf` / `VCAP_APPLICATION` nadal `KSEF_INBOUND_CLOUD_ACTIVATION_NOT_READY` (testy w `KsefInboundConfigurationTest`).
- **Uwaga wdrożeniowa:** tabela `KsefStoredDocuments` (STOR-04) **nie jest wdrożona** w HDI. Włączenie flagi na HANA
  bez HDI deploy da błędy odczytu/zapisu (`503/500` w pobraniu) — nie włączać przed zatwierdzonym deployem STOR-04.

## 4. B2 — pipeline offline H2 (nie HANA)

Istniejący `HanaDocumentStoreInboundPipelineTest` pokrywał już przepływ, crash po `put`, awarię storage i współdzielony
wiersz. Dodano scenariusz `offlineH2SynchronizationKeepsBomCrlfAndIsIdempotentAcrossRestart` przez
**`InboundSynchronization.runOnce`** (lease, fake auth/SDK, prawdziwy adapter CQN `HanaKsefDocumentStore`).

| # | Wymaganie | Dowód (test) |
| --- | --- | --- |
| 1 | oryginalne bajty (BOM + CRLF) | nowy scenariusz: `assertArrayEquals` po zapisie i po „restarcie” |
| 2 | SHA-256 | `sha256` wiersza = SHA-256 bajtów z BOM; referencja `ksefdoc:v1:TEST:INBOUND:-:<sha>` |
| 3 | parse FA(3) | `parseStatus=PARSED`, `grossAmount` z XML |
| 4 | put | 1 wiersz `KsefStoredDocuments` |
| 5 | idempotentny drugi put | ten sam `put` → ta sama referencja, nadal 1 wiersz; `storedAt` bez UPDATE |
| 6 | metadane | `ksefNumber`, `invoiceNumber`, `sellerNip`, `environment`, `sizeBytes`, statusy STORED/PARSED/VERIFIED |
| 7 | `xmlStorageRef` | wartość referencji (tylko DB, nie OData) |
| 8 | grant | `(documentId, contextNip, BUYER)` |
| 9 | checkpoint po trwałym zapisie | HWM po COMMITTED; istniejący `crashAfterPutBeforeFinalizationRecoversWithoutSecondRow` |
| 10 | restart i ponowny odczyt | nowe instancje `InboundPersistence`/repozytoriów/store/koordynatora; odczyt tych samych bajtów |
| 11 | brak duplikatu | replay okna po utracie checkpointu: 1 GET XML łącznie, 1 dokument, 1 grant, 1 wiersz magazynu |
| 12 | recovery po awarii storage | istniejący `databaseStorageOutageKeepsCheckpointThenRecovers` (`STORAGE_FAILED`, checkpoint nieprzesunięty, potem COMMITTED) |

„Restart” = nowe obiekty w tej samej JVM i tej samej bazie H2 w pamięci — **nie** jest restartem procesu CF ani HANA.

## 5. B3 — manual import jednej faktury (implemented-behind-flag)

**Analiza reużycia IN-04.** `KsefInboundOneShot` leży w `src/test` (harness LIVE) i scenariusz NUMBER to jeden GET bez
metadanych, a sink wymaga `KsefInvoiceMetadata` (niezależny hash, `permanentStorageDate`, cross-check, grant).
Dlatego import **nie** reużywa harnessu, tylko istniejące komponenty produkcyjne: `KsefInboundAccess` (auth),
`KsefInboundClient` (ta sama klasa i SDK; nowy obiekt bez nowego protokołu), `PersistentInboundInvoiceSink`,
`CqnInboundDocumentRepository`, `CqnInboundSyncLeaseRepository`. Brak nowego klienta KSeF, drugiego auth i nowej tabeli.

Kontrakt `InboundManualImport.importOne(ksefNumber)` (Java, bez akcji OData):

1. Brak aktywnej transakcji; numer: wzorzec + CRC (`KsefNumbers`); okno PermanentStorage
   `[data z numeru − 1 dzień, min(data + 30 dni, teraz)]` — błąd = **zero** lease/auth/HTTP.
2. Lease `KsefInboundSyncLeases` dla (TEST, NIP z `ainv.ksef.nip`, BUYER) — ten sam co synchronizacja:
   zajęty/cooldown/`BLOCKED` → `BUSY` bez auth. Import i synchronizacja dzielą cooldown kontekstu (≥ 15 min).
3. Auth istniejącą ścieżką (`KsefInboundAccess.open`).
4. `POST /invoices/query/metadata` ×1: `ksefNumber` (SDK `InvoiceQueryFilters.setKsefNumber`), BUYER, PermanentStorage,
   pageSize 10, offset 0, **bez retry**. `hasMore`/`isTruncated`/> 1 pozycja → `KSEF_INBOUND_MANUAL_IMPORT_AMBIGUOUS`;
   inny numer → `..._NUMBER_MISMATCH`; pusta strona → `NOT_FOUND` (bez GET, bez wiersza).
5. Sink: `GET /invoices/ksef/{n}` ×1 (`ReadRetryPolicy.noRetry`), SHA-256 z metadanych, parser/XSD/cross-check,
   `store.put`+weryfikacja, finalizacja i grant. **Trwały status** w `KsefInboundDocuments`
   (`PENDING/STORED/STORAGE_FAILED/QUARANTINED`, `lastCode`) i `KsefInboundSyncLeases` (`WAITING/BLOCKED`).
6. Checkpoint synchronizacji **nie** jest zmieniany. Błąd → wspólna polityka `InboundSynchronization.finishFailed`
   (niejednoznaczny auth/redeem lub konflikt/quarantine → `BLOCKED`, wymaga operatora; brak automatycznego retry).

**`NOT_FOUND`** oznacza pustą odpowiedź dla numeru w sprawdzonym oknie PermanentStorage oraz zakresie TEST/NIP/BUYER.
Import zapisuje tylko cooldown `WAITING`. Późniejszy jawny import może znaleźć fakturę, która stała się widoczna
w tym oknie. Dokument zapisany trwale poza oknem może odnaleźć synchronizacja obejmująca jego datę PermanentStorage.
Kod nie zapisuje negatywnego rekordu faktury, grantu ani checkpointu. HWM wyznacza kompletność danych w czasie,
a kontrakt MF nie gwarantuje, że PermanentStorage mieści się między datą z numeru −1 i +30 dni. Zakres zapytania
pozostaje taki sam. Działanie filtra `ksefNumber` w TEST nadal wymaga potwierdzenia LIVE.
[OpenAPI MF](https://github.com/CIRFMF/ksef-api/blob/d4460b6c77616c423924254d8be1cbc2858027c6/open-api.json) ·
[HWM MF](https://github.com/CIRFMF/ksef-api/blob/d4460b6c77616c423924254d8be1cbc2858027c6/pobieranie-faktur/hwm.md).

**Budżet fizycznych żądań HTTP — BLOCKED LIVE.** Produkcyjny manual import używa współdzielonego SDK MF 3.0.29
i JDK `HttpClient`. Jedno `open()` obejmuje challenge, TOKEN/XAdES, do 15 odczytów statusu w limicie rozpoczęcia
pollingu 60 s i jeden redeem. TOKEN może pobrać certyfikaty MF przy inicjalizacji kryptografii. Przed metadata i GET
`session.token()` może wykonać refresh. Metadata i GET mają po jednej próbie aplikacyjnej. SDK korzysta z `send`,
circuit breaker jest wyłączony, redirecty mają `NEVER`. JDK może jednak ponowić fizyczny GET w obrębie jednego `send`
po utracie odpowiedzi. `OneShotWireRetryTest` liczy żądania odebrane na loopback: pass obu wariantów potwierdza retry
na domyślnej JVM i pojedynczą próbę w izolowanej JVM z flagami harnessu. Wynik `Result(outcome, documentId)`
zawiera wyłącznie status i opcjonalne ID dokumentu. Niejednoznaczny start auth/redeem/refresh blokuje lease.
[Właściwości transportu JDK 25](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/module-summary.html).

**Minimalny patch przed LIVE:** wykorzystać istniejące wstrzykiwanie transportu SDK oraz wzorzec `OneShotHttpClient`.
Wydzielić transport dla manual importu z wyłączonym replay, bez przekierowań i challenge proxy. Rezerwować przed
każdą próbą jeden slot wspólnego budżetu na import (propozycja 1–9, domyślnie 9), obejmującego certyfikaty, auth,
polling, redeem, refresh, metadata i GET. Fencing lease przed I/O i trwałe `BLOCKED` przy nieznanym wyniku auth.
Zakres transportu musi objąć także kryptografię TOKEN. Właściwości JDK z harnessu działają na poziomie JVM, więc
wymagają izolacji procesu, jeśli posłużą do dowodu. W CAP potrzebny jest transport z polityką per klient, aby
zachować retry/reconciliation OUTBOUND. Test loopback kompletnej ścieżki ma policzyć sukces, wyczerpanie przed
metadata/GET, polling, timeout i utratę odpowiedzi auth. Dodatkowe bramki LIVE to globalny budżet między JVM,
STOR-04/G1, decyzja o wywołującym i sign-off. Guard CF pozostaje aktywny.

## 6. B4 — kontrakt pobrania XML (backend)

```text
GET /odata/v4/KsefService/InboundInvoiceXml(<uuid>)/content
```

- Encja `KsefService.InboundInvoiceXml { key id: UUID; fileName: String(64); content: LargeBinary }` z
  `@cds.persistence.skip`, `@readonly`, `@restrict READ → KsefOperator`, `@Core.MediaType: 'application/xml'`,
  `@Core.ContentDisposition.Filename: fileName`, `@Core.ContentDisposition.Type: 'attachment'` — standardowy
  media stream CAP OData V4 (bez `@RestController`). `<uuid>` = `id` z `InboundInvoiceOverview`.
- **G1 bez zmian:** rzeczywista kompilacja `cds.compile.to.hana` (`sqlMapping: plain`, model `db`+`srv`+outbox, ta sama
  metoda co test G1): **27 artefaktów, 16 tabel / 5 widoków / 6 indeksów**, SHA-256 zbioru artefaktów
  `d6bb890b895166dd4abf8d9a6b48ef2af2349b87b6b049d297537a56fd05bf15` — **identyczny** z `main` bez zmiany;
  DDL H2 również identyczny (21 instrukcji). Encja jest tylko w modelu serwisu/`$metadata`.

Kolejność kontroli w `InboundInvoiceXmlHandler` (@On READ; CAP nie wykonuje żadnego SELECT na tej encji):

| Krok | Kontrola | Odpowiedź przy odmowie |
| --- | --- | --- |
| 1 | uwierzytelnienie (CAP/XSUAA) | 401 |
| 2 | rola `KsefOperator` (`@restrict`) | 403 |
| 3 | flaga `xml-download.enabled` | 404 `INBOUND_XML_NOT_FOUND` |
| 4 | kształt: jeden klucz, kolumny ⊆ {id, fileName, content} z `content`, bez where/orderBy/search | 400 `INBOUND_XML_CONTENT_BY_KEY_ONLY` (lista, `$count`, `$filter`) |
| 5 | bezpośredni `GET …/content` (URI kończy się `/content`) | 403 `INBOUND_XML_DIRECT_GET_ONLY` (`$batch`, odczyt encji bez `/content`) |
| 6 | magazyn INBOUND podłączony | 503 `INBOUND_XML_UNAVAILABLE` |
| 7 | **jedno CQN w DB przed BLOB:** `ID` ∧ `environment=TEST` ∧ grant `(NIP, BUYER)` ∧ `STORED` ∧ `VERIFIED` ∧ `xmlStorageRef` ∧ `parseStatus ∈ {PARSED, UNSUPPORTED_SCHEMA}` | 404 `INBOUND_XML_NOT_FOUND` (obcy = nieistniejący = nieudostępniony) |
| 8 | `store.get(xmlStorageRef)` w scope INBOUND/TEST, niepusty XML, dodatnie `sizeBytes` typu Integer64 zgodne z liczbą bajtów i zgodny SHA-256 | 500 (lub 503 przy błędzie chwilowym) `INBOUND_XML_UNAVAILABLE` |

Odpowiedź 200: oryginalne bajty, `Content-Type: application/xml`,
`Content-Disposition: attachment; filename="<ksefNumber>.xml"` (alfabet `[0-9A-Za-z-]`, inaczej `<id>.xml`),
`Cache-Control: no-store`, `X-Content-Type-Options: nosniff`. Nagłówki ustawiane przez
`RequestContextHolder` (filtr Spring `RequestContextFilter` obejmuje servlet CAP; brak kontekstu HTTP = odmowa).
Logi: wyłącznie kod błędu (`INBOUND_XML_READ_FAILED code=…`), bez XML, referencji, hasha i numeru faktury.
Mutacje (`POST/PATCH/PUT/DELETE`, `PUT …/content`) odrzuca `@readonly` (403/405).

Model zakresu (`InboundGrantScope`, wspólny z `InboundInvoiceOverview`): rola `KsefOperator`, **dokładnie jeden**
poprawny NIP (`taxpayerNips`) i **dokładnie jeden** CompanyCode (`companyCodes`, `[A-Z0-9]{4}`), środowisko TEST,
grant `KsefInboundAccessGrants(contextNip, BUYER)`. CompanyCode jest **kontekstem sesji**, nie właściwością dokumentu
INBOUND (staging nie ma kolumny spółki): ten sam NIP z inną spółką widzi te same dokumenty; wiele/błędne wartości →
brak dostępu. `KsefScopeUserInfoProvider` dodatkowo zeruje niejednoznaczne pary (N NIP × M spółek).

**Polityka Beta 0.1:** jeden zatwierdzony kontekst NIP/CompanyCode dla całej grupy beta. Szablon XSUAA
`KsefOperator` ma dwa niezależne atrybuty i przyjmie inną poprawnie sformatowaną parę przypisaną przez administratora.
Istniejąca konfiguracja pozwala ograniczyć dostęp operacyjnie przez jedną rolę beta z oboma statycznymi wartościami,
przypisanie tylko zatwierdzonym użytkownikom i sprawdzenie ich efektywnych atrybutów JWT przed aktywacją. Aplikacja
filtruje dokumenty według wpisu BUYER/TEST dla NIP. **Multi-company dla jednego NIP jest NO-GO dla Beta 0.1.**

## 7. Macierz bezpieczeństwa (B5)

| Scenariusz | Overview | Download | Test |
| --- | --- | --- | --- |
| A widzi swoje | 200, 8 dokumentów z grantem BUYER/TEST | 200, dokładne bajty | `InboundInvoiceXmlODataTest` |
| B nie widzi dokumentów A | brak w liście, klucz 404 | 404 identyczne z nieistniejącym | `tenantBIsolated…`, `overviewUsesSameScope…` |
| brak roli | 403 | 403 | test profil + JWT (`XsuaaJwtODataContractTest`) |
| brak uwierzytelnienia / złe hasło / zły JWT | 401 | 401 | oba profile |
| obcy NIP / grant SELLER / DEMO / PENDING / CONFLICT / QUARANTINED / brak grantu | niewidoczne lub `xmlAvailable=false` | 404 | `onlyBuyerGrantInTestEnvironment…` |
| „obca CompanyCode” | ten sam NIP + inna spółka → widzi (kontekst V1) | 200 | `companyCodeIsSessionContext…` |
| wiele/brak/błędne NIP lub CompanyCode | 0 wierszy | 404 | `missingMalformedOrAmbiguousScope…` |
| obcy tenant (zid/app_tid) | 401 | 401 | `XsuaaJwtODataContractTest` (cf, offline JWT); profil `test` nie ma multitenancy |
| bezpośredni nieautoryzowany OData / tabele prywatne | 404 dla `KsefStoredDocuments`, `KsefInboundDocuments` | — | `storageReference…`, istniejące testy CAP-07 |
| zgadywanie ID | 404 | 20× identyczne 404 | `guessedDocumentIdsAllLookIdentical` |
| odczyt po `storageRef`/`sha256` | `$filter`/`$select` → 400 | klucz nie-UUID/`$filter` → 400/404 | `storageReferenceOrHashCannotBeUsed…` |
| lista / `$count` / `$expand` / odczyt encji | — | 400/403 | `collectionCountFiltersAndEntityReads…` |
| `$batch` | zapis w changeset nie usuwa wiersza | część batch 403, brak treści | `batchCannotCarryXmlContent`, `overviewMutationsAndBatchWrites…` |
| POST/PATCH/PUT/DELETE | 403/405 | 400/403/405, bajty bez zmian | `mutationsAreRejected…` |
| CSRF | egzekwuje **AppRouter** (`csrfProtection`); CAP i tak odrzuca mutacje (`@readonly`) | jw. | brak testu AppRouter w IN-05 |
| XSS w `sellerName`/`invoiceNumber` | zwracane jako dane JSON (`application/json`), bez zmian | XML jako `attachment` + `nosniff` | `overviewUsesSameScopeAndReturnsXssPayloadAsInertJsonData`; render/escaping po stronie UI5 (niezweryfikowany tu) |
| awaria magazynu / integralności | — | 500/503 bez szczegółów | `storageOrIntegrityFailures…`, `InboundInvoiceXmlUnwiredODataTest` |
| flaga OFF | — | 404 (encja w `$metadata`) | `InboundInvoiceXmlDisabledODataTest`, identity-smoke JWT |
| JWT/XML/hasło/referencja w logach | — | brak (OutputCaptureExtension) | `logsContainNeither…`, `storageOrIntegrityFailures…` |

## 8. Wyniki testów (rzeczywiste)

Wykonane 23.11.2026 w worktree `AInvoicer-beta01-b` (JDK 25.0.4, Maven 3.9.16, CAP Java 5.2.0, Spring Boot 4.1.1,
`@sap/cds` z `cap-java/node_modules`). Testy `*LiveTest`/`*IT` pozostały wykluczone domyślnie (bez flag opt-in).

| Polecenie | Wynik |
| --- | --- |
| baseline `mvn -B clean verify '-Dcds.cds.skipVersionCheck=true'` na `603e171` | exit 0, **671 run / 0 failures / 0 errors / 6 skipped** |
| `mvn -B clean verify '-Dcds.cds.skipVersionCheck=true'` po zmianach | exit 0, **706 run / 0 failures / 0 errors / 6 skipped** (+35; skip = te same 6 `AzuriteCompatibilityTest`) |
| `node --test deploy/test/hdi-catalog-readonly.test.mjs` (G1, po `npm ci --prefix deploy`) | exit 0, **63/63 PASS** |
| `node --test deploy/test/hdi-catalog-stor04.test.mjs` (STOR-04) | exit 0, **86/86 PASS** |
| `stor04-release-contract` / `stor04-release` / `hana-readiness` / `identity-smoke` | 2/2, 4 PASS + 1 skip (brak MTAR), 2/2, 3/3 |
| `cds.compile.to.hana` przed/po (sekcja 6) | identyczny SHA-256 artefaktów; 16/5/6 |
| `git diff --check` | czysty |

Nowe/rozszerzone suite: `InboundInvoiceXmlODataTest` 17, `InboundInvoiceXmlDisabledODataTest` 1,
`InboundInvoiceXmlUnwiredODataTest` 1, `InboundManualImportTest` 8, `KsefInboundConfigurationTest` 17 (+6),
`HanaDocumentStoreInboundPipelineTest` 5 (+1), `XsuaaJwtODataContractTest` 38 (+1),
`HanaReadinessConfigurationTest` 2 (rozszerzone asercje).

## 9. Czego NIE zweryfikowano

- HANA Cloud LIVE: tabela `KsefStoredDocuments` niewdrożona, adapter działa tylko na H2; zachowanie `409006`, BLOB i blokad na HANA.
- CF: brak deployu, brak testu z bindingiem HANA; guard INBOUND na CF nadal blokuje synchronizację i manual import.
- XSUAA LIVE, AppRouter (CSRF, nagłówki, przekazywanie `Content-Disposition`), render UI5 — tylko offline.
- KSeF TEST: filtr `ksefNumber` i okno dat manual importu; żadnego wywołania KSeF w tej pracy.
- G1/STOR-04 LIVE — porównano wyłącznie kompilację CDS i testy node offline.

## 10. Ryzyka

- **Tabela STOR-04 niewdrożona:** włączenie `storage.hana.enabled` na HANA przed HDI deploy = błędy 5xx/`STORAGE_FAILED`.
- **Wspólny lease importu i synchronizacji:** import odsuwa kolejny przebieg synchronizacji o ≥ 15 min (świadome — wspólny budżet kontekstu).
- **`RequestContextHolder`:** zależność od filtra Spring; jego brak daje odmowę (fail-closed), nie wyciek.
- **CompanyCode nie ogranicza dokumentów INBOUND** (V1) — model tuple NIP↔spółka wymaga osobnej decyzji.
- **Pamięć:** `store.get` materializuje XML (≤ 3 MiB domyślnie) w JVM; równoległe pobrania ogranicza budżet adaptera (64 MiB).
- **Założenie daty w numerze KSeF** (manual import) — do potwierdzenia w zatwierdzonym one-shot.

## 11. Następny krok

1. Review PR (orkiestrator), aktualizacja STATUS/ROADMAP/README poza tym zadaniem.
2. UI5: przycisk „Pobierz XML” = link do `InboundInvoiceXml(<id>)/content` tylko przy `xmlAvailable=true` (Agent UI).
3. Po zatwierdzonym HDI deploy STOR-04 + G1 CORE15: test HANA (`HanaKsefStoredDocumentsIT`), potem decyzja o
   `storage.hana.enabled`/`xml-download.enabled` na CF (osobna zgoda).
4. Manual import LIVE wyłącznie jako zatwierdzony one-shot (numer, NIP, budżet) po spełnieniu kontraktu z sekcji 5.
