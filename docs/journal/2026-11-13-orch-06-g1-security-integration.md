---
title: "ORCH-06 — review i integracja CAP-HANA-02G1 oraz CAP-07F"
date: 2026-11-13
type: journal
status: zakonczony
tags: [ainvoicer, journal, orch-06, hana, hdi, security, integration, g1, owner-led]
---

# ORCH-06 — review, integracja PR #17 + PR #18 i bramka G1

[STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Project Home](../README.md) ·
[G1 — kontrakt katalogu](../guides/CAP_HANA_02G1_CATALOG_RECONCILIATION.md) ·
[CAP-07F](../guides/CAP_07F_SECURITY_HARDENING.md) · [ORCH-05](2026-11-12-orch-05-hana-security-integration.md) ·
[BLOB-LAB-01](../guides/BLOB_LAB_01_OWNER_LED_HDI.md)

## Wynik w skrócie

**Oba PR-y MERGED po niezależnym review i pełnej regresji drzewa zintegrowanego. Bez operacji LIVE.**

| Element | Wynik |
| --- | --- |
| [PR #17](https://github.com/scaamp/AInvoicer/pull/17) CAP-HANA-02G1 | Review **PASS WITH NOTES**; HEAD `a87402e8ec741c6400f499bd892b00e895b9a458`, [CI 43460194402](https://github.com/scaamp/AInvoicer/actions/runs/43460194402) SUCCESS; merge [`ad86a0968bbd5aba25884bfe9f08be6169e12f69`](https://github.com/scaamp/AInvoicer/commit/ad86a0968bbd5aba25884bfe9f08be6169e12f69) |
| [PR #18](https://github.com/scaamp/AInvoicer/pull/18) CAP-07F | Review **PASS WITH NOTES**; HEAD `ad095bc125bd6d7f9595f7b10dd97fa86d8d5701`, [CI 43459475238](https://github.com/scaamp/AInvoicer/actions/runs/43459475238) SUCCESS; merge [`790aad8653f650ed371eac6a81c7ab0e10cdd7c2`](https://github.com/scaamp/AInvoicer/commit/790aad8653f650ed371eac6a81c7ab0e10cdd7c2) |
| Konflikty | Brak wspólnych plików; merge-tree main↔#17, main↔#18, #17↔#18 czysty; brak konfliktu semantycznego (artefakty HANA identyczne) |
| Regresja lokalna drzewa #17+#18 | **PASS** (szczegóły niżej); jedyny SKIP to lokalny brak Go (wykonywany w CI) |
| CI kodu na `main=790aad8` | [Run 43467054062](https://github.com/scaamp/AInvoicer/actions/runs/43467054062) **SUCCESS**, push na `790aad8`, job `offline` 24/24 kroków `success`. Run pośredni `ad86a09` (43466989984) anulowany przez `cancel-in-progress` — nie jest PASS |
| LIVE | **Brak** — żadnych CF push/deploy/task/restart, HANA SQL/DDL/DML, HDI make, service keys, SAP H01, KSeF, workerów |
| G1 | **Niewykonane.** Przygotowane dwie opcje do osobnej decyzji właściciela (niżej) |

## Stan wejściowy

13.11.2026, Europe/Warsaw, Claude Code. `git fetch origin` + publiczne REST API GitHub (bez tokenu; `gh`
niedostępne, credentiali nie odczytywano): `main=4d4dbfca48f654f74cef787e6a680f8a79607512`,
HEAD-y obu PR-ów zgodne z wejściem, oba `open`, `mergeable_state=clean`. `main` bez ochrony gałęzi
i bez rulesetów (API `branches/main`, `rules/branches/main`). Izolowany worktree `D:\AInvoicer-orch-06`,
branch `review/orch-06-g1-security-integration` z `origin/main`. Główny checkout właściciela
(`D:\AInvoicer`: zmodyfikowany `documents.cds`, nowe `blob-service.cds` i `BlobDocumentsHandler.java`)
oraz worktree agentów pozostawiono bez zmian.

## Review PR #17 — CAP-HANA-02G1

**PASS WITH NOTES. Brak uwag krytycznych.**

| Obszar | Ustalenie |
| --- | --- |
| Kontrakt CORE14 + LAB | Dokładne zbiory nazw: 14 tabel, 4 widoki, 6 UNIQUE; LAB tylko `CUSTOMER_CAP_JAVA_KSEFDOCUMENTS`. Każdy brak i nadmiar (tabela, widok, indeks nie-PK/nie-UNIQUE, kolumna, PK na obcej tabeli) = MISMATCH. MATCH wymaga całego kontraktu, nie liczników. |
| PK | Dokładnie jeden indeks PK na tabelę i dokładna sekwencja `POSITION:COLUMN`: `KSEFSENDRESERVATIONS(INVOICEUUID, ENVIRONMENT)`, `KSEFINBOUNDSYNCCHECKPOINTS/LEASES(ENVIRONMENT, CONTEXTNIP, SUBJECTROLE)`, pozostałe `(ID)`. |
| LAB 9 kolumn | Typ, długość NVARCHAR (36/255/255/255/100/64), nullable (`ID` NOT NULL), `CONTENT BLOB`; cytowane `"MIMETYPE"` bez normalizacji case. |
| Schemat | Każdy wiersz musi nieść `SCHEMA_NAME` równy schematowi z bindingu / `--schema`; pięć parametrów bind; schemat nie jest logowany. |
| Duplikaty / niekompletność | Duplikat dowolnego obiektu/PK-pozycji, brak metadanych PK/kolumn, puste/`undefined` odpowiedzi, wiersze z innym `KIND` lub znakami sterującymi → MISMATCH. |
| Uprawnienia | `[258]` na PREPARE/SELECT → kod 1, bez retry/GRANT, bez tekstu serwera. Ciche filtrowanie grantów → MISMATCH. MATCH dotyczy katalogu **widocznego** dla `_RT`. |
| Kody wyjścia | 0 MATCH / 2 MISMATCH / 1 błąd-odmowa — zarówno w tasku, jak i w imporcie Console. |
| Bezpieczeństwo | Jedno parametryzowane `SELECT` do 5 widoków `SYS`; brak DDL/DML/CALL, brak odczytu wartości BLOB i danych biznesowych; brak automatycznej naprawy; CI/DBADMIN/obce bindingi odrzucane przed połączeniem. |
| SQL Console | Import przyjmuje wyłącznie `{version:1, rows:[…]}` z dokładnie 4 polami tekstowymi; `verdict`/liczniki/obce pola → kod 1. Wynik zawsze `evidence=OWNER-REPORTED`, a task `SCRIPT_MEASURED`. Plik nie jest dowodem połączenia ani autentyczności. |
| Chrome/CDP | `--remote-debugging-port=0` + `DevToolsActivePort` z prywatnego profilu, deadline 90 s z limitem 1 s na HTTP, natychmiastowa odmowa po exit/signal/spawn error, `finally` zabija Chrome i usuwa profil. Brak `--no-sandbox`, `--disable-web-security`, zmian CSP/Basic Auth, workflow, nowych SKIP. Testy bezpieczeństwa asertują liczbę żądań obcych/własnych i nagłówki Authorization po redirectach — sama gotowość Chrome ich nie zalicza. |

Niezależna weryfikacja ORCH-06: kompilacja modelu do HANA w pamięci (`cds.compile.to.hana`, cds 10.2.1,
`sqlMapping: plain`, bez zapisu artefaktów) — **25 artefaktów, 15 tabel / 4 widoki / 6 UNIQUE**, wszystkie
PK i 9 kolumn LAB równe stałym walidatora. Zestaw artefaktów `main=4d4dbfc` i drzewa #17+#18 identyczny
(SHA-256 zestawu `2bb4923b…`), więc `@readonly` z PR #18 nie zmienia DB. SHA programu taska na drzewie
zintegrowanym: `efb44fb4d0080fecf8e4a9dc16470aca764cb2ea868951fcc4a2aa4b51a5c52f`, długość polecenia 4051/4096.

Uwagi (niekrytyczne):

1. MATCH nie obejmuje definicji SQL widoków, kolumn CORE, kolumn indeksów UNIQUE, defaultów, sekwencji/procedur/synonimów
   ani grantów — jest to udokumentowany zakres kontraktu.
2. Nieoczekiwany indeks UNIQUE z prefiksem LAB raportowany jest jako błąd CORE, nie LAB. Werdykt i tak MISMATCH — kosmetyka.
3. **Lokalny WIP właściciela** (`blob-service.cds` z projekcją `BlobService.Documents`) po wdrożeniu doda widok
   `BLOBSERVICE_DOCUMENTS` (kompilacja w pamięci: 5 widoków). G1 dałby wtedy oczekiwany MISMATCH — to nie awaria bazy,
   tylko nowy obiekt wymagający osobnej decyzji o kontrakcie. Adnotacje `@Core.*` w `documents.cds` nie zmieniają DDL.
4. Przewodnik HANA-02 (opcja C) odrzucał wcześniej konsolę z obawy przed automatycznym service key. Po użyciu
   „Open HDI Container in SQL Console” 12.11 liczba kluczy nie była ponownie odczytana — w G1 jest to krok obowiązkowy.

## Review PR #18 — CAP-07F

**PASS WITH NOTES. Brak uwag krytycznych.**

E-2: projekcja `KsefService.KsefOperations` ma `@readonly` i wyłącznie grant READ z zakresem NIP/CompanyCode.
Testy HTTP/H2: 12 wariantów POST (własne/obce/SEND_INVOICE/sfałszowany status/bez atrybutów) → 403 bez przyrostu
wierszy; `$batch` changeset → wewnętrzne 403, bez 201 i bez wiersza; anonim 401, brak roli 403; PATCH/DELETE
nadal zabronione; READ listy, `$count`, własny klucz 200, obcy 404. Wewnętrzny `PersistenceService` zapisuje
operacje (test idempotencji UNIQUE w DB). Brak konsumenta POST kolekcji: UI5 wywołuje wyłącznie akcje
(`requestSend`, `sync`), worker/outbox/rezerwacje używają encji bazowych. Zmiana API 201/400 → 403 opisana
w [przewodniku CAP-07F](../guides/CAP_07F_SECURITY_HARDENING.md).

E-3: kolejność potwierdzona inspekcją kodu: `@requires: authenticated-user` + `requires KsefOperator` (CAP) →
`parseUuid` (400) → `worker.transportEnabled()` (409 `KSEF_CONTEXT_NOT_CONFIGURED`) → `sap.readInvoice` →
NIP/CompanyCode (403) → reguły → `acceptsContext` → rezerwacja/CAS. Test OFF: własna, obca-NIP, obca-spółka
i brakująca faktura dają **identyczną** odpowiedź; `verifyNoInteractions(sap, authentication, session)`
i niezmienione liczniki operacji, rezerwacji, migawek, zleceń i `cds.outbox.Messages`. Test ON: dokładnie
1 SAP READ na żądanie, 403/404/200 jak wcześniej, rezerwacja i operacja SEND tworzone wewnętrznie.
Istniejące testy idempotencji/CAS/workera (`KsefSendRequestTest` 22/22) bez regresji.

Uwagi:

1. Przy KSeF ON pozostaje świadomy, wcześniej zaakceptowany oracle 404/403 dla znanego UUID.
2. Wdrożony runtime CF (0.7.3) **nie zawiera** E-2/E-3 do czasu osobno zatwierdzonego wydania; nie wykonywano LIVE POST.
3. Walidacja w `KsefOperationsHandler` jest teraz drugą barierą (martwą przy obecnym kontrakcie) — świadomie zachowana.

## Regresja drzewa zintegrowanego (lokalnie, offline)

Lokalne merge `--no-ff` obu HEAD na `origin/main` w worktree ORCH-06. Node 24.13.0, JDK 25.0.4.1, Maven 3.9.16,
Chrome z instalacji Windows. Kroki odpowiadają `.github/workflows/ci.yml`.

| Krok | Wynik |
| --- | --- |
| Browser Security Runner `test:runner` | **8/8 PASS**, 0 skipped (CSP POST i Basic Auth na prawdziwym Chrome) |
| `npm test --prefix deploy` | **90 PASS / 1 SKIP** (niezależny czytnik ZIP Go — brak Go lokalnie, wykonywany w CI) |
| `validate:smoke`, `check-secrets` (2678 plików), `git diff --check` | **PASS** (skan heurystyczny) |
| Testy katalogu `hdi-catalog-readonly.test.mjs` | **48/48 PASS** |
| `build:btp:smoke` (Maven clean verify) | **486 run / 0 failures / 0 errors / 6 skipped**; CF runtime JAR 10/10, UI5 CF 13/13, AppRouter 10/10, MTAR validation PASS |
| W tym testy security / requestSend / OData | `KsefSendTransportDisabledTest` 3/3, `KsefSendRequestTest` 22/22, `KsefServiceODataIntegrationTest` 25/25, `HanaReadinessJwtSecurityTest` 6/6, `XsuaaJwtODataContractTest` 37/37 |
| `build:btp:hana` + `verify:hana` | **PASS** (niewdrożony MTAR, release hashes PASS) |
| R2 `hana-it-runner build` / `test` | **PASS**: drugi, sekwencyjny Maven 486/0/0/6, `R2_BUILD_PASS`; testy procesowe **5/5** |
| Emulator Azurite (`deploy/storage`) | **6/6 PASS** |
| UI5 build, QUnit, OPA (mock 8085) | build PASS; QUnit **112/112** (659 asercji); OPA **21/21** (150 asercji) |
| Inbox na lokalnym CAP HTTP | **16 testów / 73 asercje PASS** (A/B, granty, 401/403/404, awaria, restart) |

Test emulatora uruchomił Maven równolegle do pierwszego builda smoke w tym samym `cap-java/`. Dlatego wynik
potwierdzono drugim, sekwencyjnym `clean verify` w buildzie R2 (te same 486/0/0/6). Własny mock serwer
8085 zatrzymano; obcy proces na 8095 (inna sesja z 8.11) pozostawiono. Nie uruchamiano testów live-sap/live-ksef,
profilu `hana-integration` ani żadnych wywołań CF/HANA.

## Merge i CI

| Krok | Dowód |
| --- | --- |
| Bramka przed merge | Ponowny fetch: `main=4d4dbfc`; HEAD-y bez zmian; CI dokładnych SHA SUCCESS, wszystkie kroki jobu `offline` `success` |
| Merge #17 | `--no-ff` dokładnego `a87402e` na `4d4dbfc` → `ad86a09`; drzewo `e8e976d9…` identyczne z przetestowanym; zwykły push bez force (odrzucony, gdyby `main` się przesunął) |
| Kontrola #18 po #17 | `main=ad86a09`, PR #17 `merged=true`; merge-tree #18 czysty; kombinacja = lokalnie przetestowane drzewo, więc aktualizacja gałęzi #18 zbędna |
| Merge #18 | `--no-ff` dokładnego `ad095bc` na `ad86a09` → `790aad8`; drzewo `af56a9f8…` identyczne z przetestowanym |
| Po merge | PR #17 i #18 `closed`, `merged=true`, `merge_commit_sha` = powyższe; `git log --first-parent 4d4dbfc..main` = wyłącznie te dwa merge |
| CI `main` | [Run 43467054062](https://github.com/scaamp/AInvoicer/actions/runs/43467054062) **SUCCESS**, push na `790aad8`, job `offline` 24/24 kroków `success`. Run pośredni `ad86a09` (43466989984) anulowany przez `cancel-in-progress` — nie jest PASS |

Mechanizm jak w ORCH-04: lokalny merge i push, bo `gh` jest niedostępne, a tokenów z credential managera nie używamy.
Approvals ani branch protection nie były wymagane; niczego nie obchodzono.

## Status bezpieczeństwa (aktualny)

- E-2 i E-3 rozwiązane **w kodzie `main`**; runtime CF bez zmian do osobnego wydania.
- Security sign-off **PENDING**: konto B i fixtures A/B, Destination `AINV_H01_UI_USER`, autorytatywny principal H01
  i DCL dzieci RAP, logout/expiry, ponowienie 2 nawigacji poprawionym klasyfikatorem (ORCH-05 decyzja C).
- KSeF, SAP WRITE, INBOUND, sweeper i outbox nadal OFF.

## G1 — do osobnej decyzji właściciela (NIE WYKONANO)

Kontrakt: domyślny tryb CORE14 + LAB (bez `--core-only`), oczekiwany wynik
`verdict=MATCH tables=15/15 views=4/4 uniqueIndexes=6/6 primaryKeys=15/15`. Pełny zgodny eksport ma
**69 wierszy**: 15 TABLE + 4 VIEW + 21 INDEX (15 PK + 6 UNIQUE) + 20 PK_COLUMN + 9 COLUMN; mniej wierszy
oznacza obcięcie lub filtrowanie. Najpierw ustalić, **co jest wdrożone**: jeżeli wdrożono lokalny `BlobService`,
oczekiwany jest MISMATCH z `VIEW:BLOBSERVICE_DOCUMENTS` — to decyzja o kontrakcie, nie naprawa bazy.

### Opcja 1 — SQL Console (dowód OWNER-REPORTED)

1. Przed: `cf target` (dev) i `cf service-keys ainvoicer-db` — zanotować liczbę kluczy (oczekiwane 0).
2. HANA Cloud Central → instancja → Schemas & HDI Containers → `ainvoicer-db` → **Open HDI Container in SQL Console**
   (nie konsola instancji/DBADMIN). Jeśli UI proponuje service key, binding lub uprawnienia — STOP.
3. Kontekst: `SELECT CURRENT_USER, CURRENT_SCHEMA FROM DUMMY;` — użytkownik z sufiksem `_RT`, schemat zgodny z nazwą
   schematu widoczną w szczegółach kontenera w Cloud Central. Wartości nie publikować.
4. Na laptopie, w czystym checkoucie finalnego `main`:
   `node deploy/scripts/hdi-catalog-readonly.cjs --print-console-sql > <scratch>\g1.sql`; przejrzeć (jedno SELECT,
   5× `CURRENT_SCHEMA`), wkleić i wykonać w konsoli.
5. Eksport **pełnego** wyniku CSV (sprawdzić brak limitu wierszy; oczekiwane 69 przy zgodności), konwersja lokalna
   wg [przewodnika](../guides/CAP_HANA_02G1_CATALOG_RECONCILIATION.md#sql-console-właściciela--ścieżka-bez-taska),
   potem `--from-console <scratch>\catalog.json --schema <schemat z kroku 3>`.
6. Po: ponownie `cf service-keys ainvoicer-db`. Nowy klucz (np. automatyczny klucz narzędzia) = znalezisko do zgłoszenia;
   nie usuwać bez osobnej decyzji.
7. Raport: dwie linie `HDI_CATALOG…` z `evidence=OWNER-REPORTED`, czas, commit, liczba wierszy, wyniki kroków 1/6.
   Surowy CSV/JSON pozostaje lokalnie, poza Git.

To dowód właściciela: plik może być niekompletny lub zmieniony i nie potwierdza niezależnie połączenia.

### Opcja 2 — jednorazowy CF task na deployerze (dowód SCRIPT_MEASURED)

Zgoda musi wymieniać wszystkie parametry; dotyczy wyłącznie jednego taska SELECT.

| Parametr | Wartość do potwierdzenia |
| --- | --- |
| Target | API EU20-001, org z ORCH-05, space `dev` (odczyt `cf target`) |
| Aplikacja | istniejący `ainvoicer-db-deployer`, stan STOPPED; **nie** `cf start`/`restart` (uruchomiłoby HDI deploy) |
| Bindingi | wyłącznie `ainvoicer-db` (`hana`/`hdi-shared`); 0 service keys; brak DBADMIN; brak nowych usług |
| Kod | commit finalnego `main`; `--print-runtime-source` przejrzany; `runtimeSourceSha256=efb44fb4d0080fecf8e4a9dc16470aca764cb2ea868951fcc4a2aa4b51a5c52f` przeliczony ponownie z tego commita |
| Runtime deployera | Node 24 + `hdb` z `db/package.json` dropletu; odczytać SHA dropletu i porównać z ostatnim wdrożeniem |
| Zasoby | `memory_in_mb: 256`, `disk_in_mb: 512` (z `--task-json`); wewnętrzny timeout 60 s |
| Wykonanie | `--task-json > <scratch>\hdi-catalog-task.json`; `cf curl -X POST /v3/apps/<guid>/tasks -d @…` (Git Bash: `MSYS_NO_PATHCONV=1`) |
| Odczyt | `cf tasks ainvoicer-db-deployer`; `cf logs ainvoicer-db-deployer --recent` filtrowane do `HDI_CATALOG`; nie wypisywać env/VCAP |
| Zatrzymanie | jeśli RUNNING > 2 min: `cf terminate-task ainvoicer-db-deployer <id>`; FAILED bez linii werdyktu = kod 1, analiza bez ponowienia |
| Zakazy | bez DDL/DML, service keys, nowych bindingów, restartu CAP, HDI make, kolejnych tasków i bram G2–G4 |

### Ocena

Opcja 2 daje silniejszy dowód (skrypt mierzy w kontekście bindingu), lecz tworzy mutację CF (task) i zużywa quota.
Opcja 1 nie dotyka CF, ale jest wyłącznie OWNER-REPORTED i wymaga kontroli kluczy przed/po. Rekomendacja:
**najpierw ustalić wdrożony model, potem opcja 2**, jeśli właściciel akceptuje jeden task; opcja 1 jako szybki,
słabszy dowód. **G1 MATCH nie autoryzuje G2/G3/G4**, restartu, cleanup ani zmian LAB.

## BLOB-LAB-01

Owner-led bez zmian. ORCH-06 nie zmienił `cap-java/db/documents.cds`, nie dodał serwisu mediów ani handlerów
i nie dotknął lokalnego WIP właściciela. Odczytano wyłącznie `git status`/`git diff` w `D:\AInvoicer`
w celu oceny wpływu na G1 (uwaga 3 przy PR #17).

## Zmienione pliki (ORCH-06)

Ten dziennik oraz `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/README.md`. Kod wyłącznie przez merge PR #17 i #18.

## Następne kroki

1. Właściciel: decyzja G1 (opcja 1 albo 2) po ustaleniu wdrożonego modelu; wynik do review.
2. Osobno: wydanie zawierające CAP-07F (E-2/E-3) i ponowne sondy LIVE po zatwierdzeniu.
3. Security acceptance (decyzja C ORCH-05) i dopiero potem decyzje G2–G4.
