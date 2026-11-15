---
title: "KSEF-STOR-04 — prywatny magazyn HANA BLOB dokumentów workera KSeF"
date: 2026-11-15
type: guide
status: offline-implemented-deploy-no-go
tags: [ainvoicer, ksef, storage, hana, blob, hdi, cap-java, security, inbound, g1]
---

# KSEF-STOR-04 — `KsefDocumentStore` → CAP `PersistenceService` → `KsefStoredDocuments`

[Project Home](../README.md) · [STATUS](../STATUS.md) · [Dziennik](../journal/2026-11-15-ksef-stor-04-private-hana-storage.md) ·
[KSEF-STOR-01 (Object Store)](KSEF_DURABLE_STORAGE.md) · [Staging INBOUND](KSEF_INBOUND_PERSISTENCE.md) ·
[G1](CAP_HANA_02G1_CATALOG_RECONCILIATION.md) · [IN-03B](KSEF_IN_03B_WORKER_READINESS.md) ·
[BLOB-LAB-01](BLOB_LAB_01_OWNER_LED_HDI.md) · [ADR-0008](../decisions/ADR-0008-document-storage-dev-test-proposal.md)

> **Status 15.11.2026:** dedykowana encja i adapter zaimplementowane, przetestowane **OFFLINE (H2)**
> oraz skompilowane do artefaktów HANA. **Bez HDI deploy, bez migracji i bez jakiejkolwiek operacji LIVE.**
> Adapter niepodłączony do beana, profilu, workera ani CF. Wdrożenie tabeli wymaga osobnej zgody właściciela.

## 1. Cel i zakres

Usunięcie blokera **D-1** z KSEF-STOR-03: dokumenty workera trafiały do laboratoryjnej tabeli `KsefDocuments`,
która ma publiczną projekcję `BlobService.Documents`, a izolacja opierała się wyłącznie na filtrze
`createdBy = $user` (test `knownGapPrincipalNameCollision…` wykazał, że użytkownik o nazwie principala widzi wiersze).
Zgodnie z decyzją właściciela (zlecenie 14/15.11) problem rozwiązano **strukturalnie**: dokumenty workera mają własną,
prywatną encję bez żadnej projekcji. Nie ukrywano principala ani nie zmieniano jego nazwy.

Zgoda obejmuje zmiany CDS w gałęzi, build i testy offline. **Nie obejmuje** HDI deploy, migracji LIVE, wiringu,
aktywacji INBOUND/schedulera, KSeF SEND, SAP WRITE ani endpointu pobierania XML.

To zadanie jest odrębne od owner-led [BLOB-LAB-01](BLOB_LAB_01_OWNER_LED_HDI.md): pliki laboratorium
(`db/documents.cds`, `srv/blob-service.cds`, `BlobDocumentsHandler`) pozostały **bez zmian**, a tabela LAB nie jest
już używana przez magazyn workera.

## 2. Relacja do PR #20 (KSEF-STOR-03)

- [PR #20](https://github.com/scaamp/AInvoicer/pull/20) (Draft, HEAD `85936cdf7d8237756f48b3b03f5c3ba890652191`) pozostaje
  **niescalony**. Ten PR go **zastępuje** (supersedes).
- **Commity PR #20 nie są włączone** do tej gałęzi (brak cherry-pick/merge), aby historia `main` nie zawierała nawet
  pośrednio wariantu zapisującego do tabeli LAB. Kod został **przeniesiony plikowo i zrefaktoryzowany**: klasy
  `HanaKsefDocumentStore`, `HanaBlobObjectStorageClient` i oba zestawy testów mają te same nazwy i rolę,
  zmieniło się mapowanie na tabelę (sekcja 5).
- Zalecenie po review tego PR: zamknąć PR #20 jako *superseded by KSEF-STOR-04* (decyzja właściciela; agent nie zamyka PR).
- Przewodnik i dziennik STOR-03 istnieją tylko w gałęzi PR #20; kluczowe fakty audytu przeniesiono tutaj (sekcje 5 i 10).

## 3. Model CDS

Plik [`cap-java/db/ksef-storage.cds`](../../cap-java/db/ksef-storage.cds):

```cds
@cds.autoexpose: false
entity KsefStoredDocuments {
  key environment  : String(4)  not null;   // TEST | DEMO | PROD
  key channel      : String(16) not null;   // INBOUND | OUTBOUND
  key ownerSegment : String(32) not null;   // NIP (OUTBOUND) albo '-' (INBOUND)
  key sha256       : String(64) not null;   // hex, małe litery, liczony przez backend
  mimeType         : String(100) not null;  // obecnie wyłącznie application/xml
  sizeBytes        : Integer64 not null;    // długość zapisana przez backend
  storedAt         : Timestamp not null;    // zegar backendu; karencja audytu orphan
  content          : LargeBinary not null;  // dokładne bajty (HANA BLOB)
}
```

Uzasadnienie i korekty względem propozycji z polecenia:

| Element | Decyzja | Powód |
| --- | --- | --- |
| Złożony PK 4 kolumn | przyjęty bez zmian | PK = logiczny adres dokumentu = fizyczne UNIQUE; brak losowego ID, brak zajmowania ID |
| Wartości kolumn klucza | segmenty z `DocumentReference` (`TEST`, `INBOUND`, `-`/NIP) | 1:1 z referencją `ksefdoc:v1:<ENV>:<CHANNEL>:<NIP\|->:<sha256>`; brak tłumaczeń |
| Długości `4/16/32/64` | przyjęte | `channel`/`ownerSegment` mają zapas na przyszłe kanały/identyfikatory; ścisłą domenę egzekwuje Java (regex + `DocumentScope`) |
| `sizeBytes Integer64` | przyjęty | kontrola limitu **przed** odczytem BLOB i druga kontrola długości po odczycie |
| `storedAt` | **dodany** | audyt orphan potrzebuje czasu zapisu; jawna kolumna zamiast `managed` (bez `createdBy`/`modifiedBy`) |
| `not null` wszędzie | **dodany** | brak częściowych wierszy; pusty BLOB/brak rozmiaru niemożliwy przez DDL |
| `cuid`/`managed` | **pominięte** | brak losowego UUID i brak zależności od principala |
| `@cds.autoexpose: false` | **dodany** | jawna deklaracja intencji (wartość domyślna); test pilnuje braku projekcji |
| `@Core.MediaType` | **pominięty** | encja nie jest wystawiana; brak semantyki OData Media |
| Kolumna prefiksu wdrożenia | **brak** | wdrożenia izoluje osobny kontener HDI; `keyPrefix` z Object Store nie jest utrwalany |

Wynik rzeczywistej kompilacji (`@sap/cds` 10.2.1, compiler 7.2.1, `sqlMapping: plain`):

```sql
COLUMN TABLE customer_cap_java_KsefStoredDocuments (
  environment NVARCHAR(4) NOT NULL,
  channel NVARCHAR(16) NOT NULL,
  ownerSegment NVARCHAR(32) NOT NULL,
  sha256 NVARCHAR(64) NOT NULL,
  "MIMETYPE" NVARCHAR(100) NOT NULL,
  sizeBytes BIGINT NOT NULL,
  storedAt TIMESTAMP NOT NULL,
  content BLOB NOT NULL,
  PRIMARY KEY(environment, channel, ownerSegment, sha256)
)
```

H2 (`schema-h2.sql`, generowany przez Maven): te same kolumny i PK; `storedAt TIMESTAMP(7)`, `content BINARY LARGE OBJECT`.

## 4. Brak publicznego OData

- Żaden plik `srv/*.cds` nie importuje `db/ksef-storage.cds`; encja trafia do modelu persystencji, bo CAP ładuje cały folder `db/`.
- Kompilacja HANA: **0 widoków** czytających tabelę; CAP runtime: żadna encja serwisu nie ma zapytania na `KsefStoredDocuments`.
- HTTP H2: `$metadata` `BlobService` i `KsefService` nie zawiera `StoredDocuments` ani `ownerSegment`; GET/POST/DELETE
  `KsefStoredDocuments` i adres z kluczem złożonym → 404/≥400 w obu serwisach; `$batch` nie otwiera ścieżki.
- `BlobService.Documents` jest projekcją wyłącznie tabeli LAB, więc **żadna** nazwa principala (także dawny principal
  STOR-03, `system-internal`, `privileged`, `anonymous`) ani kontekst uprzywilejowany nie widzi dokumentów workera.

## 5. Migracja koncepcyjna STOR-03 → STOR-04 i mapowanie adaptera

| Aspekt | STOR-03 (`KsefDocuments`, PR #20) | STOR-04 (`KsefStoredDocuments`) |
| --- | --- | --- |
| Tabela | współdzielona z LAB, publiczna projekcja | prywatna, bez projekcji |
| Adres wiersza | `fileName` = klucz obiektu + `createdBy = urn:ainvoicer:ksef-document-store` | PK `(environment, channel, ownerSegment, sha256)` |
| UNIQUE | deterministyczny UUID v3 w `ID`; po „zajęciu” ID losowy UUID | złożony PK; brak fallbacku |
| Duplikaty przy wyścigu | możliwe po zajętym ID (odczyt najstarszego) | niemożliwe (PK) |
| Rozmiar | nieutrwalany | `sizeBytes`, sprawdzany przed i po odczycie BLOB |
| Czas | `createdAt` (managed) | `storedAt` (zegar backendu) |
| Izolacja wdrożeń | `keyPrefix` w `fileName` | kontener HDI (prefiks nie jest utrwalany) |
| Fabryka | `create(db, runtime, keyPrefix, envs[, max])` | `create(db, runtime, envs[, max])` + wariant z budżetem pamięci |
| Budżet pamięci | brak | semafor bajtów w locie (domyślnie 4 × limit, max 64 MiB) |

Bez zmian: `KsefDocumentStore`, `DurableInboundDocumentStore`, `ContentAddressedDocumentStore`, `DocumentScope`,
`DocumentReference`, `ObjectStorageClient`, format referencji `ksefdoc:v1` oraz kody błędów `KSEF_DOCUMENT_*`.
`HanaBlobObjectStorageClient` parsuje ścisły klucz SPI `hana-ksef/v1/<env>/<channel>/<NIP|shared>/<sha256>.xml`
na kolumny PK (kombinacje niedozwolone przez `DocumentScope`, np. INBOUND z NIP, są odrzucane).

**Migracja danych: brak.** STOR-03 nigdy nie był podłączony ani wdrożony, więc w `KsefDocuments` nie ma dokumentów
workera do przeniesienia. Żaden kod nie czyta ani nie przepisuje wierszy LAB. Gdyby kiedyś (np. po decyzji D-2 z STOR-03)
powstały takie wiersze, migracja wymaga osobnego zadania: odczyt po `createdBy`, `put` przez nowy adapter (weryfikacja
SHA-256), porównanie referencji, dopiero potem decyzja o LAB — nigdy automatycznie.

Przyszłe podłączenie (niezaimplementowane, osobny krok po zgodzie):

```java
var root = HanaKsefDocumentStore.create(db, runtime, EnumSet.of(KsefEnvironment.TEST));
DurableInboundDocumentStore inbound = root.forScope(DocumentScope.inbound(KsefEnvironment.TEST));
```

## 6. Idempotencja, błędy i recovery

Przepływ zapisu: `PersistentInboundInvoiceSink` → `store.put(xml)` → `ContentAddressedDocumentStore`
(SHA-256 → referencja → klucz) → `HanaBlobObjectStorageClient.createIfAbsent` → `PersistenceService` INSERT → odczyt
kontrolny (długość + SHA-256) → referencja → sink zapisuje `xmlStorageRef` i finalizuje w osobnej transakcji.

| Sytuacja | Zachowanie |
| --- | --- |
| Te same bajty, ten sam scope | ta sama referencja, jeden wiersz, brak UPDATE (`storedAt` bez zmian) |
| 8 równoległych `put` | jeden wiersz; pozostałe dostają naruszenie PK `409006` → `ALREADY_EXISTS` |
| `ALREADY_EXISTS` | najpierw kontrola istniejącego wiersza (`sizeBytes`, `mimeType`), potem pełny odczyt kontrolny i SHA-256 |
| Ten sam SHA w innym scope | osobny wiersz i referencja (`environment`/`channel`/`ownerSegment` w PK) |
| Klucz z hashem niezgodnym z bajtami | `HANA_DOCUMENT_KEY_CONTENT_MISMATCH` (REJECTED) — PK zawsze = hash zapisywanych bajtów |
| Uszkodzony BLOB / `sizeBytes` / `mimeType` | `KSEF_DOCUMENT_INTEGRITY_FAILED`; `put` nie nadpisuje uszkodzonego wiersza |
| Wiersz pod obcym hashem (wstawiony poza adapterem) | `KSEF_DOCUMENT_INTEGRITY_FAILED` |
| Brak wiersza (utrata danych) | `KSEF_DOCUMENT_NOT_FOUND`, fail closed; brak „odtwarzania” z innych źródeł |
| Błąd DB inny niż `409006` | `UNAVAILABLE` → ≤ 3 próby z backoffem → `KSEF_DOCUMENT_STORE_UNAVAILABLE` (`retryable`), bez SQL/hosta |
| Brak budżetu pamięci w 10 s | `UNAVAILABLE` (retryable), brak zapisu |
| Awaria po zapisie, przed finalizacją | wiersz = bezpieczny orphan; retry tego samego dokumentu ponownie go używa (jeden wiersz) |
| Błąd storage w sinku | `STORAGE_FAILED`, brak grantu, **checkpoint nieprzesunięty**; kolejny run kończy się `COMMITTED` |

**Utrata danych:** magazyn wykrywa utratę/podmianę, ale jej nie zapobiega (brak WORM). Dokument INBOUND można
odzyskać wyłącznie ponownym pobraniem z KSeF (osobne zadanie rekoncyliacji, nie automatyczny retry); dokumentu OUTBOUND
nie należy generować ponownie „na ślepo”, bo nowe bajty dadzą inną referencję — wymaga to decyzji operatora.
Referencja w CAP do brakującego dokumentu pozostaje, a odczyt kończy się `NOT_FOUND` — bez podstawiania innych bajtów.

## 7. Granice transakcji

- Każda operacja DB adaptera: `requestContext().systemUser().run(... changeSetContext().run(...))` — ten sam wzorzec
  co `InboundPersistence.tx` i `KsefSubmissionWorker.tx`. Zapis jest trwały niezależnie od transakcji wywołującego
  (test: rollback wywołującego pozostawia orphan, retry go używa). Sink INBOUND i tak zabrania aktywnej transakcji.
- INSERT i odczyt po konflikcie PK to **osobne** ChangeSety (na HANA błąd w transakcji oznacza rollback całości).
- Odczyt: transakcja 1 — `sizeBytes`/`mimeType`; rezerwacja pamięci poza transakcją (nie blokuje połączenia z puli);
  transakcja 2 — strumień BLOB czytany najwyżej do `sizeBytes + 1` bajtów, inaczej `CORRUPTED`.
- Brak HTTP CAP → CAP, JDBC i JPA; `BlobService` nie uczestniczy (handler LAB nie widzi zdarzeń `PersistenceService`).

## 8. Limity i pamięć

- **XML:** dokładne bajty z API (BOM, CRLF, kodowanie zachowane — test). Walidacja FA(3) należy do sinka, nie do storage.
- **Domyślny limit 3 MiB** = `KsefInboundClient.DEFAULT_MAX_INVOICE_BYTES`; konfigurowalny 1 B … **16 MiB**
  (= twardy limit klienta INBOUND); testy: 2 MiB, 3 MiB, 3 MiB + 1, 16 MiB, 16 MiB + 1, 0, null.
  Laboratoryjny limit 1 MiB nie obowiązuje. Limit egzekwowany przy zapisie i przy odczycie (przed wczytaniem BLOB).
- **Budżet pamięci instancji:** semafor w jednostkach 1 KiB; domyślnie `min(4 × limit, 64 MiB)`, ≥ limit dokumentu,
  ≤ 64 MiB. Obejmuje bajty materializowane w adapterze (INSERT i odczyt BLOB); kopie po stronie wywołującego
  (sink, parser) nie są liczone — równoległość workera musi być nadal ograniczona konfiguracją.
- **Streaming:** INSERT przekazuje `InputStream`, odczyt czyta strumień `LargeBinary` z twardym limitem w tej samej
  transakcji. SPI `byte[]` wymaga materializacji; pełny streaming end-to-end wymagałby zmiany kontraktu `KsefDocumentStore`.
- **PDF:** poza zakresem (`mimeType` inny niż `application/xml` = naruszenie integralności).

## 9. Wpływ na G1 i katalog HDI

Wyprowadzone z rzeczywistej kompilacji (`cds.compile.to.hana`, ten sam sposób co test G1), porównanie modelu z i bez
`ksef-storage.cds`: **26 → 27 artefaktów, +1 dodany, 0 usuniętych, 0 zmienionych**.

| Obiekt | Zmiana |
| --- | --- |
| Tabela | **+1** `CUSTOMER_CAP_JAVA_KSEFSTOREDDOCUMENTS` (16 tabel zamiast 15) |
| Kolumny | `ENVIRONMENT NVARCHAR(4)`, `CHANNEL NVARCHAR(16)`, `OWNERSEGMENT NVARCHAR(32)`, `SHA256 NVARCHAR(64)`, `MIMETYPE NVARCHAR(100)`, `SIZEBYTES BIGINT`, `STOREDAT TIMESTAMP`, `CONTENT BLOB` — wszystkie `NOT NULL` |
| PK | `(ENVIRONMENT, CHANNEL, OWNERSEGMENT, SHA256)` (16 PK zamiast 15) |
| Indeksy UNIQUE | **bez zmian** (6) — unikalność zapewnia PK |
| Widoki | **bez zmian** (5) — brak nowego widoku |
| Istniejące tabele/widoki/indeksy | bez zmian (identyczne źródła artefaktów) |

SHA-256 źródła `customer.cap_java.KsefStoredDocuments.hdbtable`: `27cac1a20896f4e7971198c72e2024d48fa3e7f1e5f3d93c2e7ed2a77304de6a`.

**Zmiana w tym PR (zakres zatwierdzony przez właściciela 15.11):** wyłącznie test niezależnej kompilacji
w `deploy/test/hdi-catalog-readonly.test.mjs` — oczekuje CORE14 + LAB + jawnie nazwanej, **niewdrożonej** tabeli
STOR-04, jej PK i dokładnego DDL, a także sprawdza, że tabela **nie** jest w runtime kontraktu (`EXPECTED`,
`PRIMARY_KEYS`) i że żaden widok jej nie czyta. **Runtime walidatora** (`deploy/scripts/hdi-catalog-readonly.cjs`:
`EXPECTED`, SQL, task CF, hash) **bez zmian**, więc G1 LIVE dla obecnego wdrożenia nadal oczekuje 15/5/6/15.

**Propozycja aktualizacji G1 (do wdrożenia razem z zatwierdzonym HDI deploy, uzgodnić z Codexem):**

1. Do `EXPECTED.TABLE` dodać `CUSTOMER_CAP_JAVA_KSEFSTOREDDOCUMENTS` → **CORE15**: 16 tabel / 5 widoków / 6 UNIQUE / 16 PK.
2. W `PRIMARY_KEYS` dla tej tabeli: `['ENVIRONMENT', 'CHANNEL', 'OWNERSEGMENT', 'SHA256']`.
3. Opcjonalnie (rekomendowane) kontrola kolumn jak dla LAB: 8 kolumn z typami powyżej, `IS_NULLABLE = FALSE`;
   wymaga rozszerzenia jedynego SQL o drugą tabelę w `TABLE_COLUMNS` i ponownego przeliczenia limitu 4096 znaków taska.
4. Alternatywa bez łamania G1 przed deployem: osobna kategoria wyniku `HDI_CATALOG_KSEF_STORAGE=PASS|MISSING`
   (jak `OWNER_LAB_ODATA`), aby CORE nie dawał FAIL w oknie między merge a deployem.
5. Po zmianie: przeliczyć SHA-256 runtime taska, `node --test deploy/test/hdi-catalog-readonly.test.mjs`,
   test „undeployed STOR-04 table” zamienić na asercję członkostwa w CORE15.

## 10. Bezpieczeństwo — potwierdzenia

| # | Wymaganie | Dowód (offline H2 / model) |
| --- | --- | --- |
| 1 | Brak odczytu przez BlobService | projekcja tylko tabeli LAB; HTTP lista/filtr po SHA = 0; CQN dla 5 nazw principala i kontekstu uprzywilejowanego = 0 |
| 2 | Brak publicznego CRUD | brak encji w `$metadata`; GET/POST/DELETE → 404/≥400; test modelu CAP i kompilacji HANA (0 widoków) |
| 3 | Scope z zaufanego backendu | `forScope(DocumentScope)` w kodzie konfiguracji; PK liczony z bajtów i scope, nie z wejścia |
| 4 | Referencja ≠ uprawnienie | referencja obcego scope → `ACCESS_DENIED` przed DB; ID/hash/klucz obiektu → `REFERENCE_INVALID` |
| 5 | XML dopiero po grancie | brak endpointu; kontrakt w sekcji 12 wymaga filtra grantu przed odczytem referencji |
| 6 | Izolacja TEST/DEMO/PROD | `environment` w PK; `allowedEnvironments` blokuje PROD (`SCOPE_FORBIDDEN`) |
| 7 | INBOUND/OUTBOUND | `channel` w PK; ten sam hash → osobne wiersze/referencje; widok OUTBOUND odrzuca referencję INBOUND |
| 8 | NIP OUTBOUND w zakresie | `ownerSegment` w PK; NIP B nie czyta dokumentu NIP A (także znając hash) |
| 9 | URL OData nie ujawnia klucza | brak ścieżki OData; referencja nie wychodzi do OData (`xmlStorageRef` niewystawiony; test overview) |
| 10 | Hash nie daje dostępu | zgadywanie: 16 losowych hashy → `NOT_FOUND`; znany hash obcego scope → `ACCESS_DENIED` |

Dodatkowo: `createdBy` w payloadzie LAB jest ignorowany i nie tworzy wiersza workera; wiersz LAB z identycznymi bajtami
nie jest uznawany za dokument magazynu; tabela nie ma kolumn principala. Brak WORM: administrator HANA/HDI może zmienić
dane — zostanie to **wykryte** (`INTEGRITY_FAILED`), nie zapobiegnięte. Opcjonalne zawężenie ról HDI `_RT`
do INSERT/SELECT na tej tabeli — do rozważenia przy deployu.

## 11. Wymagania HANA LIVE (osobne zgody, nie w tym zadaniu)

1. Kod `409006` dla duplikatu PK na HANA (SQL 301) przez CAP Java + sterownik HANA.
2. Zachowanie blokad przy równoległym INSERT tego samego PK (czekanie vs natychmiastowy błąd), czasy.
3. Odczyt `BLOB` jako strumienia w obrębie transakcji dla 1–3 MiB (i 16 MiB, jeśli limit zostanie podniesiony); pamięć.
4. `TIMESTAMP` `storedAt` i porównania karencji audytu.
5. Trwałość po restarcie procesu CF i po nocnym zatrzymaniu Free Tier.
6. Plan zapytań po PK (prefiks partycji = 3 kolumny PK) dla listingu audytu.
7. G1 po deployu: CORE15 MATCH (sekcja 9) oraz brak nowych widoków.

## 12. Plan zatwierdzonego deploymentu (propozycja, niewykonany)

1. Review i merge tego PR (właściciel); zamknięcie PR #20 jako superseded.
2. Aktualizacja G1 do CORE15 w uzgodnionym zakresie (Codex/właściciel), CI zielone.
3. Odczyt stanu CF/HANA (read-only), potwierdzenie, że wdrożony model = oczekiwany przed zmianą (G1 15/5/6/15).
4. Build MTAR z dokładnego SHA `main`, `verify:hana`, porównanie hash źródła `hdbtable` z sekcji 9.
5. **Osobna zgoda właściciela** na HDI deploy (task deployera): tworzy wyłącznie nową tabelę, bez zmian istniejących.
6. G1 LIVE po deployu → CORE15 MATCH; dopiero potem syntetyczne DML na TEST (osobna zgoda) i pomiary z sekcji 11.
7. Wiring adaptera, INBOUND CF i scheduler — wyłącznie osobnymi zadaniami po zniesieniu guardu
   `KSEF_INBOUND_CLOUD_ACTIVATION_NOT_READY`.

Rollback: tabela jest addytywna; wycofanie aplikacji nie wymaga zmian DB. Usunięcie tabeli (undeploy) **kasuje dokumenty**
— dozwolone tylko świadomie, gdy nie ma referencji w `KsefInboundDocuments`/`KsefInvoices`.

## 13. Ograniczenia HANA Cloud Free Tier

Zgodnie z [ADR-0008](../decisions/ADR-0008-document-storage-dev-test-proposal.md) i licencją Free Tier: nocne zatrzymanie,
**brak backupu/recovery i snapshotów**, ryzyko usunięcia instancji po długim braku startu, limit pamięci/dysku.
Wniosek: Free Tier nadaje się wyłącznie do syntetycznych danych DEV/TEST. Trwałość w tabeli ≠ archiwum: nie spełnia
wymogów retencji dokumentów księgowych. PROD wymaga płatnej instancji z backupem i polityką retencji lub osobnego
magazynu archiwalnego (decyzja kosztowa poza tym zadaniem).

## 14. Kontrakt przyszłego Download XML (propozycja, niezaimplementowana)

Bez zmian merytorycznych względem STOR-03 (D-5), dostosowany do STOR-04:

```cds
// srv/ksef-service.cds — PROPOZYCJA, nie w kodzie
@restrict: [{ grant: 'READ', to: 'KsefOperator' }]
@readonly
entity InboundInvoiceXml as projection on inbound.KsefInboundDocuments {
  key ID as id,
  null as content : LargeBinary @Core.MediaType: 'application/xml'
} // bez asocjacji; projekcja na metadane INBOUND, NIGDY na KsefStoredDocuments
```

Handler `@On READ /content`: (1) rola; (2) filtr grantu jak `InboundOverviewHandler` (środowisko + NIP z XSUAA ∩
`KsefInboundAccessGrants`); (3) wiersz `STORED/VERIFIED`, odczyt `xmlStorageRef` przez `PersistenceService`;
(4) `store.get` w scope INBOUND/env; (5) `application/xml`, `Content-Disposition: attachment`, `Cache-Control: no-store`;
błędy bez rozróżnienia „obcy”/„nie istnieje” (404). Testy: A/B NIP, brak grantu, inny env, brak roli, anonim, `$batch`,
`$expand`, `$filter` po polach wewnętrznych, `ingestionStatus != STORED`, uszkodzony BLOB → 5xx bez treści.
Wymaga osobnego zadania i decyzji bezpieczeństwa.

## 15. Testy offline

| Suite | Zakres |
| --- | --- |
| `HanaKsefDocumentStoreTest` (27) | dokładne bajty + PK z backendu, idempotencja bez UPDATE, 8 wątków → 1 wiersz, fizyczny PK `409006`, ten sam SHA w 4 scope, nowa instancja, 15 zniekształconych referencji, obcy scope/NIP/env, zgadywanie hash, uszkodzenia BLOB/`sizeBytes`/`mimeType`, sfałszowany wiersz pod obcym hashem, brak nadpisania, odrzucenie kluczy niezgodnych z treścią/scope, limity 2/3/16 MiB (+1, 0, null), za duży wiersz przed odczytem BLOB, budżet pamięci, awaria DB bez wycieku, chwilowy błąd + retry, rollback wywołującego, audyt orphan ze stronicowaniem i karencją, brak ścieżek OData/`$metadata`/`$batch`, odwrócona luka D-1 (5 principali + privileged), spoofing `createdBy`, wiersz LAB z identycznymi bajtami + działający LAB, brak projekcji w modelu CAP |
| `HanaDocumentStoreInboundPipelineTest` (4) | niezmieniony sink + runner/checkpoint: pełny przepływ, crash po put i recovery bez drugiego wiersza, awaria DB → `STORAGE_FAILED` bez checkpointu → recovery, jeden wiersz dla wielu grantów, odmowa widoku OUTBOUND |
| G1 `hdi-catalog-readonly.test.mjs` (63) | w tym niezależna kompilacja: 16/5/6, PK i dokładny DDL STOR-04, brak w runtime kontraktu, brak widoku |
| Regresja | `BlobServiceODataTest` 3/3 (BLOB-LAB-01), pełne `mvn clean verify`, testy deploy — wyniki w [dzienniku](../journal/2026-11-15-ksef-stor-04-private-hana-storage.md) |

Kontrola mutacyjna (lokalnie, przywrócono kod): wyłączenie budżetu pamięci i kontroli `sizeBytes` przed odczytem
powoduje porażkę odpowiednio `memoryBudget…` i `oversizedStoredRow…`.

## 16. Ryzyka i pozostające blockery

- **HDI deploy niewykonany** — tabela istnieje tylko w modelu; adapter nie działa na HANA LIVE do czasu zgody.
- **G1 CORE15** — runtime walidatora wymaga aktualizacji razem z deployem (sekcja 9).
- **Brak WORM i backupu (Free Tier)** — wykrywamy podmianę/utratę, nie zapobiegamy; brak odtworzenia po utracie bazy.
- **Pamięć poza adapterem** — sink/parser trzymają własne kopie; równoległość workera ograniczać konfiguracją.
- **Klasyfikacja błędów** — każdy błąd DB poza `409006` jest traktowany jako chwilowy (błąd schematu też będzie ponawiany ≤ 3×).
- **INBOUND CF NO-GO** (IN-03B) — globalny budżet API, wiring, security acceptance nadal otwarte; guard CF bez zmian.
