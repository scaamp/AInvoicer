---
title: "KSEF-STOR-04R — gotowość release prywatnej tabeli HANA BLOB KsefStoredDocuments"
date: 2026-11-15
type: guide
status: offline-ready-live-not-executed
tags: [ainvoicer, ksef, storage, hana, blob, hdi, g1, release, cap-java, security]
---

# KSEF-STOR-04R — release `KsefStoredDocuments` na istniejący DEV (przygotowanie, bez deploymentu)

[Project Home](../README.md) · [Dziennik](../journal/2026-11-15-ksef-stor-04r-release-readiness.md) ·
[STOR-04 model/adapter](KSEF_STOR_04_PRIVATE_HANA_STORAGE.md) · [Plan ORCH-08](ORCH_08_STOR_04_HANA_RELEASE_PLAN.md) ·
[G1](CAP_HANA_02G1_CATALOG_RECONCILIATION.md) · [Runner R2](CAP_HANA_02_R2_RUNNER.md) · [BLOB-LAB-01](BLOB_LAB_01_OWNER_LED_HDI.md)

> **Status 15.11.2026: OFFLINE READY FOR OWNER REVIEW — LIVE NOT EXECUTED.**
> Przygotowano wykonywalny, wersjonowany G1 16/5/6/16, weryfikator artefaktu release względem rzeczywiście
> wdrożonego payloadu HDI, domyślnie wyłączony harness REAL HANA (fazy `stor04-*` w runnerze R2) i procedurę
> z osobnymi bramkami. **Nie wykonano:** `cf deploy`/`cf push`/`cf run-task`/HDI deploy, żadnego SQL na HANA,
> restartów CF, odczytów CF/HANA/SAP/KSeF, zmian XSUAA/IAM/Destination, service keys ani merge.
> Bieżący stan CF/HANA **nie jest** w tej sesji LIVE VERIFIED — opisy stanu wdrożenia pochodzą z dzienników.

## 1. Odpowiedź w skrócie

| Pytanie | Odpowiedź |
| --- | --- |
| Czy STOR-04 jest technicznie gotowy do kontrolowanego release? | **Tak, offline** — po review tego PR, zielonym CI i spełnieniu warunków z sekcji 12. |
| Co dokładnie zostanie wdrożone? | Jeden plik HDI `src/gen/customer.cap_java.KsefStoredDocuments.hdbtable` → jedna tabela `CUSTOMER_CAP_JAVA_KSEFSTOREDDOCUMENTS`. 0 widoków, 0 indeksów, 0 zmian/usunięć istniejących artefaktów (dowód bajtowy względem payloadu 13.11). |
| Stary G1 | `hdi-catalog-readonly.cjs` **bez zmian** (15/5/6/15, runtime `6ebdfd14…`, 4071 znaków) — jedyny poprawny dla katalogu sprzed deployu. |
| Nowy G1 | `hdi-catalog-stor04.cjs` (16/5/6/16, kontrakt `STOR04-CATALOG-16-5-6-16`, runtime `e2d8a6972e4ae305e4aeadca2d5a282769928f6a93681a8e3ccd9e0aee39d0b8`, 4055 znaków) — wyłącznie po potwierdzonym HDI deploy. |
| GO/NO-GO pierwszy HDI deploy | **NO-GO teraz.** GO dopiero po: merge, CURRENT G1 MATCH, artefakcie z dokładnego SHA `main`, potwierdzeniu semantyki `cf deploy -m` i jawnej zgodzie (sekcja 12). |
| GO/NO-GO pierwszy BLOB INSERT/GET | **NO-GO.** Wymaga wcześniej: HDI deploy SUCCESS + NEW G1 MATCH + G2 runnera R2 (aplikacja `ainvoicer-hana-it` nie istnieje na CF) + osobnej zgody DML. |

## 2. Faza A — audyt release baseline

| # | Element | Wynik (źródło) |
| --- | --- | --- |
| 1 | Commit `main` | `744836c9dbb5851e5d14b5f23eadd21af31fc897` = `origin/main` po `git fetch` (Git). Worktree `D:\AInvoicer-stor-04r`, branch `feature/ksef-stor-04r-hana-release-readiness`. |
| 2 | `mta.yaml` / manifest | Deskryptor bazowy 0.7.1 tworzy usługi; release używa wyłącznie wariantu `hana-readiness.mjs` z `org.cloudfoundry.existing-service` dla `ainvoicer-db`, `ainvoicer-auth`, `ainvoicer-destination`. Rozszerzenie `deploy/btp-dev-eu20-hana-first.mtaext` (fs4, host `ainvoicer-v2xzij9s`). Brak `manifest.yml` dla CAP; manifest istnieje tylko dla runnera R2. |
| 3 | HDI deployer | `cap-java/db/package.json`: `@sap/hdi-deploy` 5.7.0 (lock), `hdb` 2.30.1, start `--use-hdb --parameter com.sap.hana.di.table/try_fast_table_migration=true`, **bez `--auto-undeploy`**, brak `undeploy.json`. Build `cds build --for hana --production`. |
| 4 | Konfiguracja CAP Java | CAP 5.2.0, Spring Boot 4.1.1, `cds-feature-hana` 5.2.0, ngdbc 2.30.11, profil `cf`; HANA MTAR wymusza KSeF/INBOUND/SAP WRITE/sweeper OFF, storage `off`, SQL init `never`, outbox OFF. Adapter STOR-04 **niepodłączony** (brak beana). |
| 5 | Bindingi (z repo/dzienników) | `ainvoicer-db` (hana/hdi-shared) związany z `ainvoicer-srv` i `ainvoicer-db-deployer`; `ainvoicer-auth` (xsuaa/application); `ainvoicer-destination` (lite). **Brak** aplikacji R2 `ainvoicer-hana-it` (G2 nigdy nie wykonane). Bez odczytu LIVE w tej sesji. |
| 6 | Ostatni zatwierdzony deployment | 13.11.2026, `cf deploy ainvoicer_0.7.3.mtar -e btp-dev-eu20-hana-first.mtaext --retries 0`, operacja `de6d13db-df89-11f1-a97a-eeee0a983f89` exit 0, HDI task #4 SUCCEEDED; MTAR `b30117cf2ef0d212e0c001d4eda765f919632eeac726089d776405ef1cbf947d`, źródło `4d4dbfc` + niezacommitowane pliki LAB ([dziennik](../journal/2026-11-13-blob-lab-01-odata-deployment.md)). Lokalna kopia MTAR w `D:\AInvoicer\deploy\build\` ma **ten sam SHA-256** (sprawdzone). Późniejsze wpisy (14.11/15.11) nie zgłaszają nowego deploymentu. |
| 7 | Wersje Node/cds/CAP/HANA | Node 24.13.0 / npm 11.6.2 lokalnie (CI: Node 22), cds-dk 10.2.0, `@sap/cds` 10.2.1, compiler 7.2.1, JDK 25.0.4.1, Maven 3.9.16, Terser 5.44.1 (generator G1). Runtime deployera: Node 24 + `hdb` z dropletu. |
| 8 | Zawartość MTAR | Sekcja 8. |
| 9 | Obecny wykonywalny G1 | 15/5/6/15, 70 wierszy, runtime `6ebdfd14863cc7a69514e3065759712c3485d51053c052b7b9e68a632aad5785`, task 4071/4096 — odtworzone w tej sesji bajtowo identycznie. |
| 10 | Docelowy kontrakt | `deploy/contracts/hdi-catalog-stor04.v1.json` — 16/5/6/16 + 8 kolumn; teraz `executable: true` z jednym walidatorem, `status` nadal `UNDEPLOYED_RELEASE_CONTRACT`. |
| 11 | Ryzyko undeploy | **Brak w artefakcie:** bez `undeploy.json`, bez `--auto-undeploy`, 0 usuniętych plików względem wdrożonego payloadu. Ryzyko pozostaje **proceduralne**: wdrożenie MTA o tym samym ID z mniejszym zestawem modułów lub ręczny HDI z innym payloadem — zabronione (sekcja 10). |
| 12 | Wpływ na inne części | HDI: tylko nowa tabela. **Pełny MTAR** wdrożyłby jednak nowszy CAP (`srv` payload zmieniony: CAP-07F, IN-03B, IN-04 klient, STOR-04 klasy) — dlatego rekomendacja: wyłącznie moduł deployera (sekcja 8). AppRouter: źródła `approuter/` bez zmian od wdrożonego pakietu LAB, ale payload jest budowany ponownie (UI5 dist) — nie wdrażać. |

Zmiany w `cap-java/db` i `srv/*.cds` od `4d4dbfc`: `documents.cds`/`blob-service.cds` (były już w wdrożonym pakiecie LAB jako
niezacommitowane), `ksef-service.cds` (CAP-07F: `@readonly`, bez zmiany SQL widoku — potwierdza porównanie bajtowe) i nowy
`ksef-storage.cds`.

## 3. Architektura G1: stary i nowy kontrakt, jawne przełączenie

Wybrano **osobny wersjonowany entrypoint** [`deploy/scripts/hdi-catalog-stor04.cjs`](../../deploy/scripts/hdi-catalog-stor04.cjs):

- Stary walidator [`hdi-catalog-readonly.cjs`](../../deploy/scripts/hdi-catalog-readonly.cjs) nie zmienił się ani o bajt → jego
  zrecenzowany runtime pozostaje dowodem dla bramki CURRENT G1.
- Nowy plik ładuje kontrakt v1 ścisłym loaderem (dokładne klucze, 16/5/6, PK każdej tabeli, 8 nazwanych kolumn storage,
  superset starego kontraktu o dokładnie jedną tabelę — inaczej `HDI_CATALOG_CONTRACT_INVALID`).
- Program taska współdzieli **niezmienione** funkcje starego G1 przez `Function#toString`: `selectBinding` (jeden binding
  `ainvoicer-db`, CI/DBADMIN odrzucone), `clientOptions` (TLS z walidacją certyfikatu), `validLabView` (dokładny SELECT
  widoku BlobService), `safeError` (bez treści błędów serwera) i `run` (timeout 60 s, kody 0/2/1). Test sprawdza obecność
  ich dokładnego źródła.
- Własne są: stałe kontraktu, SQL i `evaluate`/`summaryLine`. `evaluate` porównuje **multizbiory kanonicznych wpisów**
  (TABLE, VIEW, DEFINITION, UNIQUE_INDEX, PRIMARY_KEY, PK_COLUMN, COLUMN): brak = `missing`, nadmiar/duplikat/zła definicja
  = `unexpected`. Statusy CORE / OWNER_LAB / OWNER_LAB_ODATA / **storage** liczone są z nazwy obiektu; błędy globalne
  (schemat, uszkodzone wiersze, cel indeksu) psują wszystkie zakresy.
- Brak trybu częściowego, `--core-only`, wildcardów, `>=`, `MISSING=PASS` i autodetekcji. Wybór kontraktu = wybór pliku
  przez operatora w zatwierdzonym etapie. Task ma inną nazwę (`ksef-stor-04-hdi-catalog-readonly`), a wynik zawiera
  `contract=STOR04-CATALOG-16-5-6-16`.

| | Stary G1 (przed deployem) | Nowy G1 (po deployu) |
| --- | --- | --- |
| Plik | `hdi-catalog-readonly.cjs` | `hdi-catalog-stor04.cjs` |
| Kontrakt | CORE14 + LAB + widok LAB | `STOR04-CATALOG-16-5-6-16` (v1) |
| Liczności | 15 / 5 / 6 / 15 | 16 / 5 / 6 / 16 |
| Kolumny | 9 LAB (`NAME` = kolumna) | 9 LAB + 8 storage (`NAME` = `TABELA.KOLUMNA`) |
| Task | `cap-hana-02-hdi-catalog-readonly`, 4071 zn. | `ksef-stor-04-hdi-catalog-readonly`, 4055 zn. |
| SHA-256 runtime | `6ebdfd14863cc7a69514e3065759712c3485d51053c052b7b9e68a632aad5785` | `e2d8a6972e4ae305e4aeadca2d5a282769928f6a93681a8e3ccd9e0aee39d0b8` |
| Na katalogu drugiego stanu | MISMATCH (`unexpected TABLE:…KSEFSTOREDDOCUMENTS`, PK) | MISMATCH (`missing TABLE:…`, `storage=FAIL`, tables=15/16) |

Oczekiwany wynik po deployu (przyszły pomiar skryptu, nie wynik tej sesji):

```text
HDI_CATALOG contract=STOR04-CATALOG-16-5-6-16 verdict=MATCH tables=16/16 views=5/5 uniqueIndexes=6/6 primaryKeys=16/16 storage=PASS missing=[] unexpected=[]
HDI_CATALOG_CORE=PASS HDI_CATALOG_OWNER_LAB=PASS HDI_CATALOG_OWNER_LAB_ODATA=PASS evidence=SCRIPT_MEASURED
```

Ścieżka SQL Console (OWNER-REPORTED) działa jak w G1: `--print-console-sql`, eksport 4 kolumn, `--from-console`.
Nowy SQL kwalifikuje kolumny (`TABLE_NAME || '.' || COLUMN_NAME`) dla `TABLE_NAME IN (LAB, STORAGE)`; eksport starym
SQL daje MISMATCH (fail-closed), więc nie da się go pomylić.

## 4. Dokładny katalog po release

16 tabel: `CDS_OUTBOX_MESSAGES`, `CUSTOMER_CAP_JAVA_` + `KSEFDOCUMENTS` (LAB), `KSEFINBOUNDACCESSGRANTS`, `KSEFINBOUNDDOCUMENTS`,
`KSEFINBOUNDINTEGRITYCONFLICTS`, `KSEFINBOUNDSYNCCHECKPOINTS`, `KSEFINBOUNDSYNCLEASES`, `KSEFINVOICES`, `KSEFINVOICESNAPSHOTS`,
`KSEFOPERATIONS`, `KSEFSENDRESERVATIONS`, **`KSEFSTOREDDOCUMENTS`**, `KSEFSUBMISSIONHISTORY`, `KSEFSUBMISSIONS`,
`SAPRESULTWRITEHISTORY`, `SAPRESULTWRITES`.
5 widoków: `BLOBSERVICE_DOCUMENTS`, `KSEFSERVICE_INBOUNDINVOICEOVERVIEW`, `KSEFSERVICE_INVOICEINTEGRATIONSTATUS`,
`KSEFSERVICE_KSEFINVOICES`, `KSEFSERVICE_KSEFOPERATIONS`.
6 UNIQUE: `…KSEFINBOUNDACCESSGRANTS_SCOPE`, `…KSEFINBOUNDDOCUMENTS_IDENTITY`, `…KSEFINVOICESNAPSHOTS_DOCUMENT`,
`…KSEFOPERATIONS_IDEMPOTENCY`, `…KSEFSUBMISSIONS_SNAPSHOT`, `…SAPRESULTWRITES_KSEFNUMBER`.
16 PK: `(ID)` poza `KSEFSENDRESERVATIONS (INVOICEUUID, ENVIRONMENT)`, `KSEFINBOUNDSYNC{CHECKPOINTS,LEASES}
(ENVIRONMENT, CONTEXTNIP, SUBJECTROLE)` i **`KSEFSTOREDDOCUMENTS (ENVIRONMENT, CHANNEL, OWNERSEGMENT, SHA256)`**.

| Kolumna | Typ | Długość | NULL | Walidowane w G1 |
| --- | --- | --- | --- | --- |
| ENVIRONMENT | NVARCHAR | 4 | NO | typ, długość, nullability, PK poz. 1 |
| CHANNEL | NVARCHAR | 16 | NO | typ, długość, nullability, PK poz. 2 |
| OWNERSEGMENT | NVARCHAR | 32 | NO | typ, długość, nullability, PK poz. 3 |
| SHA256 | NVARCHAR | 64 | NO | typ, długość, nullability, PK poz. 4 |
| MIMETYPE | NVARCHAR | 100 | NO | typ, długość, nullability |
| SIZEBYTES | BIGINT | — | NO | typ, nullability |
| STOREDAT | TIMESTAMP | — | NO | typ, nullability |
| CONTENT | BLOB | — | NO | typ, nullability (wartość nigdy nie jest czytana) |

Długość dla BIGINT/TIMESTAMP/BLOB nie jest parametrem DDL; zgodnie z dotychczasową polityką G1 nie porównujemy jej
z wymyśloną stałą (musi być liczbą lub `-`). Typ jednoznacznie określa rozmiar.

## 5. DDL, PK i hashe artefaktów

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

| Artefakt | SHA-256 | Pochodzenie |
| --- | --- | --- |
| `customer.cap_java.KsefStoredDocuments.hdbtable` | `27cac1a20896f4e7971198c72e2024d48fa3e7f1e5f3d93c2e7ed2a77304de6a` | **bez zmian** względem STOR-04: `cds build --for hana` i `compile.to.hana` w tej sesji dają identyczne bajty |
| Wdrożony MTAR 0.7.3 (13.11) | `b30117cf2ef0d212e0c001d4eda765f919632eeac726089d776405ef1cbf947d` | lokalna kopia = SHA z dziennika |
| Wdrożony payload HDI | `b772fb4b8f9840e9bab2f8a9e042dd9fb98414afc320edd5abc9cdc8f015ba4f` | z kopii MTAR |
| Kandydat MTAR 0.7.4 (lokalny build) | `9bbbc6f1c747b2687b71f0e56d77674f156c434445a06cc48eb3b9b42cd45958` | build z niezacommitowanego drzewa tej sesji — **nie jest artefaktem do wdrożenia** |
| Payload HDI kandydata | `6bcf20139461fed8b0020d7307a5c4e77cd50e7bcb1392ea173ce92e0daba911` | jw. |

Porównanie z wdrożonym payloadem ([manifest hashy](../../deploy/contracts/hdi-deployed-baseline-0.7.3.json), 28 plików):
**+1** (`KsefStoredDocuments.hdbtable`), **0 zmienionych**, **0 usuniętych**, 28 identycznych bajtowo; `package.json`
i `package-lock.json` deployera identyczne po normalizacji końców linii (lokalny checkout ma CRLF przez `core.autocrlf`,
wdrożony LF — różnica bajtowa hasha payloadu, nie treści). Ten sam wynik daje test CI bez MTAR (kompilacja CDS vs manifest).

## 6. Faza D — limit polecenia taska 4096

- Stały limit projektu `MAX_TASK_COMMAND_LENGTH = 4096` zachowany. Dokumentacja CAPI v3 w wyszukiwaniu z 15.11 **nie
  potwierdziła** konkretnej wartości dla `command` taska; traktujemy 4096 jako zachowawczą granicę (nie zwiększamy jej).
- Pierwsza wersja (silnik G1 + 8 kolumn) miała 4323 znaki → przekroczenie. Kompresja prefiksów pogorszyła wynik (4407).
  Rozwiązanie bez usuwania kontroli: nowy `evaluate` na multizbiorach (krótszy kod przy tych samych kontrolach) →
  **4055/4096** (margines 41, większy niż 25 w obecnym G1).
- Format bez zmian: Brotli q11 + base64 w `node -e`, jedna komenda, 256 MB/512 MB, brak dodatkowego taska.
  Generator (`checkedTaskRequest`) odmawia przy przekroczeniu — nigdy nie obcina programu.
- Testy: długość ≤ 4096, deterministyczność (dwa wywołania identyczne, ten sam hash runtime), dekodowanie 1:1 do
  programu, wykonanie dokładnie wysyłanego programu dla MATCH i 74 przypadków negatywnych, granica `max = len` OK /
  `len − 1` odmowa.
- Ryzyko: wynik Brotli może zależeć od wersji biblioteki w Node 22 vs 24; CI (Node 22) wykonuje te same asercje.
  Przy przekroczeniu w przyszłości: STOP i alternatywa **OWNER-REPORTED SQL Console** (bez taska) albo osobno zatwierdzony
  mechanizm pakowania — nie skracanie kontroli.

## 7. Faza E — harness REAL HANA (domyślnie wyłączony)

Klasa [`HanaKsefStoredDocumentsIT`](../../cap-java/srv/src/test/java/customer/cap_java/ksef/storage/HanaKsefStoredDocumentsIT.java)
(pakiet storage, dostęp do package-private klienta), uruchamiana **wyłącznie** przez launcher R2 jako fazy `stor04-*`.
Zwykły `mvn verify` jej nie wykrywa (sufiks IT), profil `hana-integration` obejmuje tylko `HanaPersistenceIT`,
a `@EnabledIfSystemProperty(ainv.hana.stor04=true)` ustawia wyłącznie launcher dla faz storage.

Bramka (`Gate`, przed kontekstem Spring/DataSource; brak zgody = zero HTTP/SQL HANA) wymaga:

| Zmienna | Wymaganie |
| --- | --- |
| `AINV_STOR04_IT_CONSENT` | `READ_ONLY_STOR04_CHECK_APPROVED_HDI` dla `catalog`/`recover`; `WRITE_SYNTHETIC_STOR04_DOCUMENTS_APPROVED_HDI` dla `roundtrip`/`contend`. Zgoda musi pasować do rodzaju fazy; ogólna zgoda R2 `WRITE_SYNTHETIC_DATA_TO_APPROVED_HDI` nie działa. |
| `AINV_STOR04_IT_PHASE` | dokładnie jedna faza; brak `all`/`cleanup`/domyślnej |
| `AINV_STOR04_IT_COMMIT` | 40-hex = commit zapisany w `runner.jar` (`build-commit.txt`); generator tasków odrzuca pakiet z brudnego drzewa |
| `AINV_STOR04_IT_MAX_WRITES` | `0` dla faz odczytu; ≥ planowanych wierszy (3 / 1) dla zapisu; twardy limit 8 |
| `AINV_STOR04_IT_ENVIRONMENT` / `_HDI_NAME` | `TEST` / `ainvoicer-db` |
| `AINV_STOR04_IT_RUN_ID` / `_NIP` | kanoniczny UUID (małe litery) / poprawny syntetyczny NIP (scope OUTBOUND) |
| platforma | jak R2: CI zabronione, flagi OFF, brak nadpisań Spring/CDS, `VCAP_APPLICATION` runnera, dokładnie 1 HDI + 1 XSUAA, bez DBADMIN/service key; zmienne CAP-HANA-02 i STOR-04 nie mogą się mieszać |

`WriteBudget` (dekorator SPI) przepuszcza `createIfAbsent` **tylko** dla kluczy zaplanowanych dla fazy (≤ `MAX_WRITES`);
każdy inny klucz jest odrzucany przed DB. Run może zapisywać raz: zajęty klucz = `STOR04_HARNESS_RUN_ALREADY_USED` bez zapisu.
Dokumenty są syntetycznym XML (BOM, CRLF, znaki UTF-8, UUID runu, `SYNTHETIC-NOT-AN-INVOICE`), nie fakturą KSeF.

| Faza | Rodzaj | Wiersze | Sprawdza |
| --- | --- | --- | --- |
| `stor04-catalog` | READ ONLY | 0 | JDBC: 8 kolumn, typy, długości NVARCHAR, NOT NULL, PK w kolejności; model CAP: 0 encji serwisu czytających tabelę |
| `stor04-roundtrip` | DML | 3 | INSERT/GET dokładnych bajtów, SHA, `sizeBytes`, MIME, BOM/CRLF; ponowne `put` = ta sama referencja, 1 wiersz, `storedAt` bez zmian (brak UPDATE; scenariusz „timeout po commicie”); ten sam SHA w OUTBOUND = osobny wiersz; obcy scope i obcy NIP → `ACCESS_DENIED`; PROD → `SCOPE_FORBIDDEN`; zgadnięty hash → `NOT_FOUND`; klucz/hash zamiast referencji → `REFERENCE_INVALID` bez ujawnienia hash; BlobService (privileged) i tabela LAB: 0 wierszy po SHA (tylko kolumna ID); rollback wywołującego → trwały orphan → audyt → ponowne użycie |
| `stor04-contend` | DML | 1 | 8 równoległych `createIfAbsent` → **1 CREATED + 7 ALREADY_EXISTS**, 1 wiersz; surowy duplikat CQN → CAP `409006` + znacznik `HANA_UNIQUE_SQLSTATE`; odczyt po konflikcie w nowej transakcji |
| `stor04-recover` | READ ONLY | 0 | nowa JVM (restart kontekstu CAP): 4 dokumenty runu identyczne bajtowo, brak dokumentu = `NOT_FOUND` |

Znaczniki logu (jedyne przepuszczane przez filtr R2): `STOR04_CATALOG=…`, `STOR04_ROUNDTRIP=CREATED:3;ALREADY_EXISTS:2`,
`STOR04_CONTEND=CREATED:1;ALREADY_EXISTS:7`, `HANA_UNIQUE_SQLSTATE=…;VENDOR_CODE=…`, `STOR04_RECOVER=DOCUMENTS:4`,
`STOR04_HARNESS_PASS=<faza>`, `R2_PHASE_RESULT=…`.

Mapowanie wymagań, które **nie** są dowodzone na HANA LIVE przez ten harness:

| Wymaganie | Gdzie dowód |
| --- | --- |
| Brak publicznego OData / `$batch` (HTTP) | H2 HTTP (`HanaKsefDocumentStoreTest`) + G1 (0 widoków) + `catalog` (model CAP); runner nie ma HTTP |
| Uszkodzony BLOB / `sizeBytes` (integralność vs błąd DB) | offline H2 — na HANA wymagałoby UPDATE (zakazane); LIVE rozróżnia wyłącznie `NOT_FOUND` od niedostępności |
| Restart aplikacji CF `ainvoicer-srv` | adapter nie jest podłączony do `srv`, więc restart nie dowodzi niczego o magazynie; właściwy test = `recover` w nowej JVM, a po nocnym zatrzymaniu HANA Free Tier — ponowny `recover` |
| Brak UPDATE/DELETE w uprawnieniach DB | runtime `_RT` ma uprawnienia schematu; ograniczenie wynika z kodu adaptera (brak operacji) — zawężenie ról HDI = osobna decyzja |

Wiersze testowe pozostają (adapter nie ma DELETE; brak cleanup) jako oznaczone syntetyczne dane TEST — usunięcie wymaga
osobnej decyzji właściciela i nie jest częścią release.

Generowanie taska (bez wykonania CF): `node deploy/scripts/hana-it-runner.mjs task stor04-<faza>` z ustawionymi przez
właściciela zmiennymi `AINV_STOR04_IT_*`; JSON zawiera jedno polecenie fazy, 1024 MB/1024 MB.

## 8. Faza F — packaging i zakres release

Kandydat `deploy/build/ainvoicer_0.7.4.mtar` (wersja podbita z 0.7.3, żeby nie nosić etykiety wdrożonego pakietu):

| Moduł | Zawartość | Względem wdrożonego 13.11 |
| --- | --- | --- |
| `ainvoicer-db-deployer` | `package.json`, `package-lock.json`, `src/gen/*` (29 plików) | **+1 plik**, 0 zmian/usunięć (`verify:stor04-release`) |
| `ainvoicer-srv` | `cap-java-exec.jar` z HEAD | **zmieniony** (CAP-07F, IN-03B scheduler OFF, IN-04 transport, klasy STOR-04 niepodłączone) |
| `ainvoicer-approuter` | AppRouter + UI5 `dist-cf` | budowany ponownie; nie jest celem release |
| zasoby | `existing-service`: `ainvoicer-auth`, `ainvoicer-destination`, `ainvoicer-db` | bez tworzenia usług |

Rozróżnienie operacji (każda osobno, żadna automatycznie):

1. **Pełny MTAR** — wdraża też nowy CAP i AppRouter (restart obu, nowy kod bezpieczeństwa CAP-07F bez osobnej akceptacji LIVE).
   **Nie rekomendowane** w tym release; wymaga osobnej oceny/akceptacji (CAP-07F/E-2/E-3, smoke, rollback 0.7.3).
2. **Wyłącznie moduł HDI** (rekomendowane): `cf deploy ainvoicer_0.7.4.mtar -e deploy/btp-dev-eu20-hana-first.mtaext -m ainvoicer-db-deployer --retries 0`.
   **Blocker R-1:** semantykę `-m` w aktualnym MultiApps CLI (brak undeploy pozostałych modułów MTA, metadane wersji)
   trzeba potwierdzić w dokumentacji SAP przed zgodą. Nigdy nie budować MTA `ainvoicer` z samym deployerem —
   MultiApps mógłby uznać `srv`/AppRouter za usunięte z MTA.
3. **Przełączenie walidatora** — dokumentacyjny etap: po SUCCESS używamy `hdi-catalog-stor04.cjs`, stary G1 zostaje jako
   historyczny dowód stanu sprzed deployu.
4. **Testy DML** — R2 `stor04-roundtrip`/`stor04-contend` (wymaga wcześniej G2 runnera).
5. **Restart** — `recover` w nowej JVM; restart `ainvoicer-srv` nie jest potrzebny do dowodu.

Uwaga: etykieta MTA (`cf mta ainvoicer`) po wdrożeniu samego deployera może pokazywać 0.7.4, choć `srv` pozostaje na
dropletach z 13.11 — etykieta nie jest dowodem zawartości. Dowodem są SHA payloadów i checksumy dropletów.

## 9. Plan release — każda operacja LIVE to osobna bramka

| # | Bramka | Działanie | Dowód / kryterium STOP | Zgoda |
| --- | --- | --- | --- | --- |
| 0 | PRECHECK | Merge tego PR (właściciel), zielone CI dokładnego SHA `main`. Odczyt CF read-only: target EU20/org/space `dev`, `cf mta ainvoicer` (brak aktywnych operacji), apps 1/1, deployer STOPPED, bindingi, brak service keys (metadane CAPI), HANA RUNNING. Bez `cf env`/`CF_TRACE`. | dowolna rozbieżność z sekcją 2 = STOP | read-only CF |
| 1 | CURRENT G1 | Stary G1: `hdi-catalog-readonly.cjs --task-json`, sprawdzić `runtimeSourceSha256=6ebdfd14…`, `commandLength=4071`; jeden task na deployerze. | `verdict=MATCH tables=15/15 views=5/5 uniqueIndexes=6/6 primaryKeys=15/15`; inaczej STOP bez DDL | 1 task SELECT |
| 2 | ARTIFACT REVIEW | Czysty checkout dokładnego SHA, `npm run build:btp:smoke`, `build:btp:hana`, `verify:hana`, `verify:stor04-release`; zapis SHA MTAR/payloadu HDI; podgląd `--print-runtime-source` nowego G1. | `STOR04_RELEASE_ARTIFACT=PASS hdiAdded=1 hdiChanged=0 hdiRemoved=0`, DDL `27cac1a2…`, `workingTreeDirty=false` | offline |
| 3 | OWNER APPROVAL | Właściciel zatwierdza dokładnie: SHA commit, SHA MTAR, polecenie `-m ainvoicer-db-deployer`, extension, czas, plan przerwania; potwierdzona semantyka `-m` (R-1). | brak którejkolwiek pozycji = NO-GO | **jawna zgoda** |
| 4 | HDI DEPLOY | Jedno `cf deploy … -m ainvoicer-db-deployer --retries 0`; zapis ID operacji; `cf mta-ops`, `cf tasks ainvoicer-db-deployer`, log deployera (lista plików). | operacja FINISHED, task SUCCEEDED, w logu 1 plik do deploy i 0 undeploy; inaczej STOP (sekcja 10) | w zgodzie 3 |
| 5 | NEW G1 | `hdi-catalog-stor04.cjs --task-json` (hash runtime z bramki 2), jeden task. | `contract=STOR04-… verdict=MATCH tables=16/16 … storage=PASS`; MISMATCH = STOP | 1 task SELECT |
| 6 | DML TEST | Najpierw G2 runnera R2 (osobna zgoda wg [R2](CAP_HANA_02_R2_RUNNER.md)), potem `stor04-catalog` → `stor04-roundtrip` → `stor04-contend`, każdy osobno. | `STOR04_HARNESS_PASS=<faza>`, liczniki jak w sekcji 7, kod `409006` + SQLState | osobna zgoda per faza |
| 7 | RESTART TEST | `stor04-recover` w nowej JVM; opcjonalnie ponownie po nocnym zatrzymaniu/wznowieniu HANA. | `STOR04_RECOVER=DOCUMENTS:4` | read-only |
| 8 | ACCEPTANCE | Raport: SHA, ID operacji, sekwencje tasków, znaczniki; aktualizacja STATUS/ROADMAP po zakończeniu pracy równoległej. | brak sekretów/XML w raporcie | właściciel |

Wiring adaptera, INBOUND CF, scheduler, SEND i SAP WRITE **nie są** częścią żadnej bramki.

## 10. Faza G — rollback i recovery

- **Bez** DROP TABLE, DELETE danych, undeploy tabeli, resetu/odtwarzania kontenera HDI, nowej bazy i ponownego HDI deploy
  „na próbę”. LAB (`KsefDocuments`, `BlobService.Documents`) i ich dane pozostają nietknięte; release ich nie zmienia.
- **Rollback aplikacji ≠ rollback schematu.** Przy wariancie „tylko deployer” `srv`/AppRouter się nie zmieniają, więc
  rollback aplikacji nie jest potrzebny. Runtime 0.7.3 (CSN z `4d4dbfc`) nie zna tabeli i działa z katalogiem 16/5/6/16
  (tabela addytywna). Jeżeli w przyszłości wdrożony zostanie nowy `srv`, jego rollback do 0.7.3 nie wymaga zmian DB.
- Po udanym deployu obowiązuje **wyłącznie nowy G1**; stary G1 dałby MISMATCH i nie może „przywrócić” stanu.
- Wynik niejednoznaczny (timeout CLI, operacja ERROR, brak logu taska): **STOP**. Nie ponawiać `cf deploy`. Odczytać
  `cf mta-ops`/stan operacji i taska, zachować log; jeżeli operacja wisi — decyzja właściciela o `cf deploy -i <id> -a abort`
  (bez retry). Następnie rekoncyliacja katalogu: najpierw stary G1, potem nowy G1 (dwa osobne zatwierdzone taski):
  stary MATCH → nic nie wdrożono; nowy MATCH → tabela wdrożona; oba MISMATCH → STOP, zachowanie danych i dowodów, analiza
  z właścicielem/administratorem. HDI make jest transakcyjne, ale nie zakładamy tego bez odczytu katalogu.
- Ślad wdrożenia: ID operacji MTA, numer i stan taska HDI, SHA MTAR/payloadu, wynik obu G1 — w dzienniku, bez sekretów.

## 11. Evidence bez sekretów

- Przed sesją CF: `Remove-Item Env:CF_TRACE`, brak `DEBUG`, brak transkrypcji terminala. Nie wykonywać `cf env`, nie
  pobierać `VCAP_SERVICES`, tokenów ani service keys; nie wklejać nagłówków autoryzacji.
- Logi: wyłącznie `cf logs <app> --recent | Select-String 'HDI_CATALOG|STOR04_|R2_|HANA_UNIQUE'` oraz stan taska/operacji.
- Droplety/aplikacje: metadane CAPI (`/v3/apps/<guid>/droplets/current`: checksum, created_at) — bez endpointów credentiali.
- Dowody harnessu to stałe znaczniki; nie zawierają hosta, schematu, użytkownika, NIP, UUID runu ani bajtów XML.
- Eksport SQL Console (jeśli wybrany) zostaje lokalnie poza Git; do review trafia oczyszczony wynik CLI `OWNER-REPORTED`.

## 12. Warunki zgody właściciela (wszystkie wymagane, każda bramka osobno)

1. Merge tego PR i zielone CI dokładnego SHA `main` (z krokiem `verify:stor04-release`).
2. PRECHECK read-only i **CURRENT G1 MATCH 15/5/6/15** (task lub SQL Console OWNER-REPORTED).
3. MTAR 0.7.4 zbudowany z czystego checkoutu dokładnego SHA, `verify:hana` i `verify:stor04-release` PASS, zapisane SHA.
4. Potwierdzona semantyka `cf deploy -m ainvoicer-db-deployer` (R-1) i zgoda na **tylko** ten moduł, `--retries 0`.
5. Zgoda na NEW G1 (task SELECT) po SUCCESS.
6. Osobno: G2 runnera R2 (nowa aplikacja `ainvoicer-hana-it`, 2 bindingi, quota) — dopiero potem fazy `stor04-*`
   z zatwierdzonym UUID, NIP, commitem i `MAX_WRITES`.
7. Świadomość ograniczeń Free Tier: brak backupu, nocne zatrzymanie; dane wyłącznie syntetyczne TEST.

## 13. Status LIVE

| Operacja | Status |
| --- | --- |
| CF/HANA odczyt stanu w tej sesji | NOT EXECUTED (stan z dzienników) |
| CURRENT G1 (15/5/6/15) | NOT EXECUTED |
| HDI deploy STOR-04 | NOT EXECUTED |
| NEW G1 (16/5/6/16) | NOT EXECUTED |
| G2 runnera R2 | NOT EXECUTED (aplikacja nie istnieje) |
| `stor04-catalog/roundtrip/contend/recover` | NOT EXECUTED |
| Restart CF / HANA | NOT EXECUTED |

## 14. Blockery przed wiring INBOUND

1. Release powyżej (HDI + NEW G1 + DML/recover) zakończony sukcesem LIVE.
2. Rzeczywisty kod konfliktu `409006` i zachowanie blokad przy równoległym INSERT na HANA (faza `contend`).
3. Wiring `HanaKsefDocumentStore.create(...).forScope(inbound(TEST))` w profilu CF za osobną flagą (osobne zadanie).
4. Globalny budżet API między JVM i semantyka lease po expiry (IN-03A/IN-03B) — nadal otwarte.
5. Security acceptance INBOUND (granty, A/B NIP) i zniesienie guardu `KSEF_INBOUND_CLOUD_ACTIVATION_NOT_READY`.
6. Polityka retencji/cleanup syntetycznych i orphan wierszy; brak WORM/backup na Free Tier (ADR-0008).
7. Opcjonalnie zawężenie roli HDI `_RT` do INSERT/SELECT na tabeli storage.
8. Brak publicznego `xmlStorageRef`/Download XML — kontrakt pobierania pozostaje osobną decyzją bezpieczeństwa.

## 15. Testy offline (15.11.2026)

Node 24.13.0 / npm 11.6.2, JDK 25.0.4.1, Maven 3.9.16, Windows; bez połączeń CF/HANA/KSeF/SAP.

| Polecenie | Wynik |
| --- | --- |
| `node --test deploy/test/hdi-catalog-stor04.test.mjs` | **86/86 PASS** (74 negatywne, każdy także w dokładnie wysyłanym programie) |
| `node --test deploy/test/hdi-catalog-readonly.test.mjs` (stary G1, bez zmian) | **63/63 PASS**, runtime `6ebdfd14…`, 4071 |
| `node --test deploy/test/stor04-release-contract.test.mjs` | 2/2 PASS (DDL hash + kolumny vs kompilacja CDS) |
| `node --test deploy/test/stor04-release.test.mjs` | 5/5 PASS (z kandydatem MTAR; bez niego 4 + 1 skip) |
| `npm test --prefix deploy` | **200 testów: 199 PASS / 1 SKIP** (lokalnie brak Go; wymagany w CI) |
| `node deploy/scripts/hana-it-runner.mjs build` (`mvn -B clean verify`) | **Maven 593 / 0 failures / 0 errors / 6 skipped** (6 = Azurite bez emulatora), `R2_BUILD_PASS` |
| `HanaKsefStoredDocumentsHarnessOfflineTest` (H2) | 8/8 PASS — pełny cykl catalog → roundtrip → contend → recover, ponowne użycie runu, budżet zapisów, bramka |
| `HanaKsefDocumentStoreTest` | 27/27 PASS (bez zmian zachowania adaptera) |
| `node deploy/scripts/hana-it-runner.mjs test` + `java -jar runner.jar --self-test` | 6/6 PASS + `R2_OFFLINE_SELF_TEST=PASS` (w tym preflight `stor04-*` w świeżej JVM) |
| `npm run build:btp:smoke` / `build:btp:hana` / `verify:hana` | PASS (0.7.2 smoke, 0.7.4 HANA, 16/6/5) |
| `npm run verify:stor04-release` | `STOR04_RELEASE_ARTIFACT=PASS hdiAdded=1 hdiChanged=0 hdiRemoved=0` |
| `npm run test:cf-runtime` | 10/10 PASS |
| `node deploy/scripts/check-secrets.mjs` / `git diff --check` | PASS (heurystyka, 2713 plików) / PASS |
| Kontrola mutacyjna walidatora (lokalnie, przywrócono) | bez nullability → 8 FAIL; bez pozycji PK → 3 FAIL; bez definicji widoku → 7 FAIL |

`BlobServiceODataTest`, `XsSecurityDescriptorTest` i cała regresja CAP są w 593 testach Maven (PASS). Wynik CI dokładnego
HEAD: w [dzienniku](../journal/2026-11-15-ksef-stor-04r-release-readiness.md).

## 16. Ryzyka

- **R-1 `cf deploy -m`** — semantyka częściowego wdrożenia niezweryfikowana w tej sesji (bez dostępu do dokumentacji
  aktualnej wersji pluginu). Do potwierdzenia przed zgodą.
- **Pełny MTAR** zawiera nowszy CAP niż runtime CF — nie łączyć z release tabeli.
- **Limit 4096** — wartość CAPI niepotwierdzona; margines 41 znaków; Brotli Node 22 vs 24 pilnowane w CI.
- **Uprawnienia katalogu** — runtime `_RT` może nie widzieć wszystkich obiektów `SYS.*` (258 lub MISMATCH) — STOP, bez grantów.
- **Free Tier** — nocne zatrzymanie, brak backupu; dane testowe nieusuwalne przez adapter.
- **Etykieta MTA** — nie jest dowodem zawartości wdrożenia.
- **Harness** — testy H2 potwierdzają logikę scenariuszy, nie zachowanie HANA (blokady, typy JDBC HDB, kod SQLState).
