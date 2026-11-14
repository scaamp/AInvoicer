---
title: "KSEF-STOR-03 — adapter dokumentów KSeF nad tabelą HANA KsefDocuments"
date: 2026-11-14
type: guide
status: offline-implemented-integration-no-go
tags: [ainvoicer, ksef, storage, hana, blob, cap-java, security, inbound]
---

# KSEF-STOR-03 — `KsefDocumentStore` → CAP `PersistenceService` → `KsefDocuments`

[Project Home](../README.md) · [STATUS](../STATUS.md) · [Dziennik](../journal/2026-11-14-ksef-stor-03-hana-blob-adapter.md) ·
[KSEF-STOR-01 (Object Store)](KSEF_DURABLE_STORAGE.md) · [Staging INBOUND](KSEF_INBOUND_PERSISTENCE.md) ·
[BLOB-LAB-01](BLOB_LAB_01_OWNER_LED_HDI.md) · [ADR-0008](../decisions/ADR-0008-document-storage-dev-test-proposal.md)

> **Status 14.11.2026:** adapter zaimplementowany i przetestowany **OFFLINE na H2** (25 nowych testów).
> **Niepodłączony** do żadnego beana, profilu, workera ani CF. **Integracja: NO-GO** do decyzji właściciela D-1
> (rozdzielenie danych workera od laboratoryjnej tabeli). Żadnych operacji na HANA LIVE, CF, KSeF ani SAP.

## 1. Zakres i granice

Zlecenie właściciela (14.11.2026): zintegrować istniejącą tabelę `KsefDocuments` z kontraktem
`KsefDocumentStore`, bez używania publicznego `BlobService` jako transportu i bez zmian w: `db/documents.cds`,
`srv/blob-service.cds`, `BlobDocumentsHandler`, `KsefInboundConfiguration`, `InboundSynchronization`,
schedulerze/API Budget, walidatorze G1, konfiguracji LIVE i XSUAA. Wszystkie te pliki pozostały nietknięte.
To zadanie jest odrębne od owner-led BLOB-LAB-01; nie realizuje i nie „domyka” laboratorium.

## 2. Audyt modelu `KsefDocuments` (faza A)

Model (`cuid, managed`): `ID`, `createdAt/By`, `modifiedAt/By`, `fileName String(255)`, `mimeType String(100)`,
`content LargeBinary` (HANA `BLOB`), `sha256 String(64)`. Jedyna projekcja: `BlobService.Documents`
(`@requires KsefOperator`; CREATE; READ/UPDATE `where createdBy = $user`; `createdBy/createdAt/sha256` `@readonly`).

Fakty zmierzone sondą CAP na H2 (14.11, sonda usunięta przed commitem; utrwalone jako testy):

| Fakt | Wynik | Znaczenie |
| --- | --- | --- |
| POST `Documents` z jawnym `ID` | **201, ID klienta zachowane** | deterministyczny UUID może zostać „zajęty” przez użytkownika OData |
| POST z `createdBy` w payloadzie | ignorowane, zapisane `operator-a` | użytkownik nie podszyje się pod principal magazynu |
| `Insert` przez `PersistenceService` z jawnym `createdBy` | zachowane | magazyn może oznaczać swoje wiersze |
| `createdBy` dla `systemUser()` | `system-internal` | bez jawnego principala wiersze workerów miałyby nazwę ogólną |
| Duplikat PK | `ServiceException` `409006` (HTTP 409) | PK działa jak ograniczenie UNIQUE, zgodnie z `InboundPersistence.uniqueViolation` |
| `fileName` ze znakiem `/` przez `BlobService` | 400 (handler LAB) | klucz obiektu z `/` jest nieosiągalny dla POST |

Odpowiedzi na pytania audytu:

1. **Czy model wystarcza?** Do przechowania dokładnych bajtów i SHA-256 — tak. Do *bezpiecznego współdzielenia*
   z laboratorium — nie w pełni: brak kolumn scope/kanału/rodzaju dokumentu, brak UNIQUE poza PK i istnieje
   publiczna projekcja tej samej tabeli. Adapter kompensuje to kodowaniem klucza i principalem (sekcja 3),
   ale separacja nie jest strukturalna → **D-1**.
2. **Środowisko KSeF:** segment klucza (`/v1/test/…`) i referencji (`:TEST:`); `allowedEnvironments` instancji
   (wdrożenie TEST nie zapisze PROD); `keyPrefix` per wdrożenie.
3. **Kanał INBOUND/OUTBOUND:** segment klucza i referencji; widok `forScope(...)` odrzuca obcy kanał przed DB.
4. **Idempotencja:** adres = treść (SHA-256). `ID` = UUID v3 z klucza → PK pełni rolę UNIQUE; konflikt 409006 →
   odczyt istniejącego wiersza → `ALREADY_EXISTS` → kontrolny odczyt i SHA-256 w `ContentAddressedDocumentStore`.
5. **Kolizja z publicznym CREATE:** ID od klienta jest akceptowane, więc **ID nie jest dowodem**. Jeśli PK jest zajęty
   przez obcy wiersz, magazyn zapisuje z losowym ID; odczyt zawsze szuka po `(fileName = klucz, createdBy = principal)`.
   Zajęcie ID nie blokuje (brak DoS) i nie ujawnia dokumentu (test `squattedDeterministicId…`).
6. **Dokument workera vs użytkownika:** `createdBy = modifiedBy = urn:ainvoicer:ksef-document-store` oraz
   `fileName` z `/`. To **konwencja, nie constraint** — patrz D-1.
7. **Niezmienność:** adapter nie ma UPDATE/DELETE; zapis create-only; każdy odczyt weryfikuje SHA-256 treści,
   kolumnę `sha256` i `mimeType`. BlobService pozwala na UPDATE tylko własnych wierszy. Brak WORM na poziomie DB:
   administrator HANA/HDI może zmienić dane — zostanie to wykryte (`KSEF_DOCUMENT_INTEGRITY_FAILED`), nie zapobiegnięte.
8. **NIP i użytkownicy:** OUTBOUND partycja per NIP; INBOUND per środowisko, a prawo odczytu daje wyłącznie
   `KsefInboundAccessGrants` sprawdzany w CAP przed sięgnięciem po referencję. Użytkownik nie ma ścieżki do magazynu.
9. **Trwałość przy restarcie CAP:** zapis jest commitowany w osobnym ChangeSet przed zwrotem referencji; nowa instancja
   adaptera nie ma stanu w pamięci. Na H2 sprawdzono nową instancję adaptera, **nie** restart procesu CF ani HANA.
   Free Tier: brak backupu (ADR-0008) — trwałość ≠ archiwum.
10. **Dodatkowa encja / UNIQUE?** Tak, rekomendowane przed integracją produkcyjną (D-1 niżej).

## 3. Architektura adaptera

```text
PersistentInboundInvoiceSink / KsefSubmissionWorker            (bez zmian kodu)
        │ KsefDocumentStore.put(byte[]) / get(String)
        ▼
ContentAddressedDocumentStore.forScope(DocumentScope)          (KSEF-STOR-01, bez zmian)
  • scope env × kanał × NIP, referencja ksefdoc:v1, SHA-256, retry UNAVAILABLE
        │ ObjectStorageClient (createIfAbsent / read / list — brak delete i overwrite)
        ▼
HanaBlobObjectStorageClient  ── CQN, systemUser + osobny ChangeSet ──▶ PersistenceService → KsefDocuments (HANA BLOB)
```

| Klasa | Odpowiedzialność |
| --- | --- |
| `HanaKsefDocumentStore` | publiczna fabryka: `create(db, runtime, keyPrefix, environments[, maxBytes])` → `ContentAddressedDocumentStore`; bez beana Spring |
| `HanaBlobObjectStorageClient` | package-private SPI nad CQN: mapowanie klucza na wiersz, principal, deterministyczne ID, klasyfikacja błędów |

Brak JPA, JDBC, HTTP CAP → CAP i nowych zależności. Wywołania `BlobService` nie biorą udziału (handler LAB
ma `@ServiceName("BlobService")` i nie widzi zdarzeń `PersistenceService`).

### Mapowanie wiersza

| Kolumna | Wartość magazynu |
| --- | --- |
| `ID` | UUID v3 z `ainvoicer:ksef-stor-03:v1:<klucz>`; po zajęciu — losowy UUID |
| `fileName` | klucz `<keyPrefix>/v1/<env>/<channel>/<NIP\|shared>/<sha256>.xml` (≤ ~160 znaków) |
| `mimeType` | `application/xml` (inne = uszkodzenie) |
| `content` | dokładne bajty; strumień czytany w całości w tej samej transakcji |
| `sha256` | liczony przez backend z zapisywanych bajtów; weryfikowany przy odczycie |
| `createdBy`, `modifiedBy` | `urn:ainvoicer:ksef-document-store` |

### Referencja i scope

Referencja jest identyczna jak w KSEF-STOR-01: `ksefdoc:v1:<ENV>:<CHANNEL>:<NIP|->:<sha256>`; nie zawiera ID, nazwy
tabeli ani prefiksu. Scope wiąże zaufany kod konfiguracji (`forScope(DocumentScope.inbound(TEST))`), nigdy OData.
UUID ani SHA-256 nie są prawem dostępu: referencja obcego scope → `KSEF_DOCUMENT_ACCESS_DENIED` przed DB;
UUID, sam hash, klucz obiektu → `KSEF_DOCUMENT_REFERENCE_INVALID`.

Uwaga do ADR-0008 (proponowany): referencja nie wskazuje backendu. Zmiana backendu w wdrożeniu skutkuje
`KSEF_DOCUMENT_NOT_FOUND` dla starych referencji (fail closed; SHA-256 wyklucza zwrócenie innych bajtów).
Jawny tag providera wymagałby zmiany wspólnego `DocumentReference` — decyzja D-3.

### Transakcje i współbieżność

- Każda operacja: `requestContext().systemUser().run(... changeSetContext().run(...))` — ten sam wzorzec, co
  `InboundPersistence.tx` i `KsefSubmissionWorker.tx`. Zapis jest trwały niezależnie od transakcji wywołującego
  (test: rollback wywołującego pozostawia orphan, retry go ponownie używa). Sink INBOUND i tak zabrania aktywnej transakcji.
- Równoległy `put` tej samej treści: PK → jeden wiersz (8 wątków, H2). Dwa równoległe zapisy po *zajętym* ID mogą
  utworzyć dwa wiersze o tej samej treści — odczyt bierze najstarszy, listing deduplikuje (ryzyko szczątkowe, bez wpływu na bajty).
- Błąd DB inny niż 409006 → `UNAVAILABLE` → ≤ 3 próby z backoffem → `KSEF_DOCUMENT_STORE_UNAVAILABLE` (`retryable=true`).
  INSERT jest atomowy, więc ponowienie jest bezpieczne. Komunikat nie zawiera SQL, hosta ani nazw obiektów.

## 4. Polityka plików i rozmiaru

- **XML:** dokładne bajty z API (BOM, CRLF, kodowanie zachowane — test). Walidacja FA(3) należy do sinka, nie do storage.
- **PDF:** poza zakresem. Adapter zapisuje wyłącznie `application/xml`; pochodny PDF wymaga osobnego typu
  dokumentu/kanału i decyzji (D-4), aby nie mieszać oryginału z wizualizacją.
- **Limit:** domyślnie **3 MiB** = `KsefInboundClient.DEFAULT_MAX_INVOICE_BYTES`; konfigurowalny 1 B … **16 MiB**
  (= twardy limit klienta INBOUND; bajty trzymane w pamięci JVM). Laboratoryjny 1 MiB nie obowiązuje (test 2 MiB i 3 MiB PASS,
  3 MiB + 1 → `KSEF_DOCUMENT_SIZE_INVALID`, bez wiersza). Limit egzekwowany przy zapisie **i** odczycie.
  ADR-0008 proponował 10 MiB jako punkt startowy; 3 MiB jest ciaśniejsze i zgodne z obecnym klientem.
  Limit adaptera musi być ≥ limitu klienta INBOUND — inaczej kontrolowany `STORAGE_FAILED` bez checkpointu.

## 5. Integracja z INBOUND (faza C)

Przepływ bez zmian: `KsefInboundClient` → `InvoiceRetrievalService` (SHA-256) → `PersistentInboundInvoiceSink` (FA(3))
→ `store.put(xml)` + kontrolny `get` → `KsefInboundDocuments.xmlStorageRef` → `KsefInboundAccessGrants`.
Adapter implementuje `DurableInboundDocumentStore` przez `forScope`, więc przyszłe podłączenie to wyłącznie konfiguracja:

```java
var root = HanaKsefDocumentStore.create(db, runtime, "ainvoicer-dev", EnumSet.of(KsefEnvironment.TEST));
DurableInboundDocumentStore inbound = root.forScope(DocumentScope.inbound(KsefEnvironment.TEST));
```

Nie dodano beana ani zmian `KsefInboundConfiguration` — wiring należy do KSEF-IN-03B (Codex) po decyzji D-1.

## 6. Bezpieczny odczyt XML dla Inbox (faza E) — propozycja, niezaimplementowana

Rekomendowany kontrakt (wymaga osobnej decyzji bezpieczeństwa D-5):

```cds
// srv/ksef-service.cds — PROPOZYCJA, nie w kodzie
@restrict: [{ grant: 'READ', to: 'KsefOperator' }]
@readonly
entity InboundInvoiceXml as projection on inbound.KsefInboundDocuments {
  key ID as id,
  null as content : LargeBinary @Core.MediaType: 'application/xml'
} // bez asocjacji: brak $expand z InboundInvoiceOverview
```

Handler `@On READ` dla `/content`: (1) rola przez `@restrict`; (2) ten sam filtr grantu co `InboundOverviewHandler`
(środowisko + NIP z atrybutów XSUAA ∩ `KsefInboundAccessGrants`); (3) dopiero po znalezieniu wiersza odczyt
`xmlStorageRef` przez `PersistenceService` i warunki `STORED/VERIFIED`; (4) `store.get` w scope INBOUND/env;
(5) odpowiedź `application/xml`, `Content-Disposition: attachment`, `Cache-Control: no-store`; błędy bez rozróżnienia
„obcy”/„nie istnieje” (404). `xmlStorageRef` nigdy nie wychodzi do OData. `$batch` przechodzi przez ten sam handler.
Testy do przygotowania wraz z implementacją: A/B NIP, brak grantu, inny env, brak roli, anonim, `$batch`, `$expand`,
`$filter` po kolumnach wewnętrznych, `ingestionStatus != STORED`, uszkodzony BLOB → 5xx bez treści, brak kopiowania
do `BlobService`.

## 7. Decyzje właściciela

| ID | Decyzja | Rekomendacja |
| --- | --- | --- |
| **D-1** | Rozdzielenie danych workera od tabeli LAB | **Dedykowana encja bez żadnej projekcji**, np. `KsefStoredDocuments : managed { key environment; key channel; key ownerSegment; key sha256; sizeBytes; mimeType; content : LargeBinary }`. Złożony PK = UNIQUE i scope w kolumnach, brak zajmowania ID, brak publicznego OData. Wymaga zmiany kontraktu G1 (CORE14 → CORE15) i kontrolowanego HDI deploy — koordynacja z Codexem. Zmiana w adapterze: tylko mapowanie w `HanaBlobObjectStorageClient`. Alternatywa (kolumna `origin` + filtr w `BlobService`) narusza LAB i jest gorsza. |
| D-2 | Akceptacja ryzyka D-1 dla DEV/TEST do czasu migracji | tylko syntetyczne dane, IdP bez użytkownika o nazwie principala; **nie** dla PROD |
| D-3 | Tag providera w referencji | pozostawić `ksefdoc:v1` (niezależna od backendu, fail closed) |
| D-4 | PDF jako pochodna XML | osobny typ/kanał i renderer w osobnym zadaniu |
| D-5 | Endpoint XML Inbox | kontrakt z sekcji 6 po decyzji bezpieczeństwa |
| D-6 | Cykl życia tabeli LAB | jeśli D-2: zakaz zmian/undeploy `KsefDocuments` bez migracji danych workera |

## 8. Testy offline

| Suite | Zakres |
| --- | --- |
| `HanaKsefDocumentStoreTest` (21) | dokładne bajty + SHA-256 backendu, idempotencja bez UPDATE, 8 wątków → 1 wiersz, nowa instancja, 12 zniekształconych/wstrzykniętych referencji, obcy NIP/kanał/env/prefiks, uszkodzony BLOB/kolumna `sha256`/`mimeType`/pusta treść, brak nadpisania, limity 2/3 MiB/+1/0/null/16 MiB+1, za duży wiersz przy odczycie, awaria DB bez wycieku, chwilowy błąd + retry, rollback wywołującego, izolacja HTTP OData (GET/content/lista/filter/PUT/PATCH/DELETE/`$batch`, inna rola 403, anonim 401), fałszywy klucz/principal w POST, wiersz LAB nazwany jak referencja, zajęte ID, audyt orphan, **znana luka D-1** |
| `HanaDocumentStoreInboundPipelineTest` (4) | niezmieniony sink + runner/checkpoint: pełny przepływ, crash po put i recovery bez drugiego wiersza, awaria DB → `STORAGE_FAILED` bez checkpointu → recovery, jeden wiersz dla wielu grantów, odmowa widoku OUTBOUND |
| Regresja | `BlobServiceODataTest` 3/3, `ContentAddressedDocumentStoreTest` 21/21, `DurableStorageInboundPipelineTest` 8/8 oraz pełne `mvn clean verify`: 514 testów, 0 błędów, 6 skipped |

Test `knownGapPrincipalNameCollision…` celowo **potwierdza** lukę: użytkownik z rolą `KsefOperator` i nazwą
równą principalowi widzi 1 wiersz przez `BlobService`. Po wdrożeniu D-1 asercję należy odwrócić.

## 9. Ryzyka

- **D-1 (wysokie dla PROD):** współdzielona tabela z publiczną projekcją; izolacja zależy od nazwy użytkownika IdP.
- **Cykl życia LAB:** zmiana/undeploy `documents.cds` w ćwiczeniu może usunąć dokumenty workera.
- **Pamięć:** dokumenty czytane w całości do `byte[]`; równoległość workera musi być ograniczona.
- **Brak WORM i backupu (Free Tier):** wykrywamy podmianę, nie zapobiegamy; brak odtworzenia po utracie bazy.
- **Duplikaty po zajętym ID przy równoległym zapisie:** możliwe, nieszkodliwe dla bajtów.
- **Klasyfikacja błędów:** każdy błąd DB poza 409006 jest traktowany jako chwilowy (błąd schematu też będzie ponawiany).

## 10. Do potwierdzenia na HANA LIVE (osobne zgody, nie w tym zadaniu)

1. Kod `409006` dla duplikatu PK na HANA (błąd SQL 301) przez CAP Java + sterownik HANA.
2. Zachowanie blokad przy równoległym INSERT tego samego PK (czekanie vs błąd) i czasów.
3. Odczyt `BLOB` jako strumienia w obrębie transakcji, rozmiary 1–3 MiB, pamięć i czas.
4. Jawny `createdBy`/`modifiedBy` zachowany na HANA (jak na H2).
5. Trwałość po restarcie procesu CF i po nocnym zatrzymaniu Free Tier.
6. `startsWith` + stronicowanie listingu i plan zapytania bez indeksu na `fileName` (przy D-1: PK).
7. Uprawnienia HDI `_RT`: INSERT/SELECT bez UPDATE/DELETE niepotrzebnych dla magazynu (opcjonalne zawężenie).
