---
title: "CAP-HANA-02G1 — ścisłe uzgodnienie katalogu CORE i OWNER LAB"
date: 2026-11-14
type: guide
status: offline-verified-live-not-executed
tags: [ainvoicer, cap-hana-02g1, hana, hdi, catalog, owner-led]
---

# CAP-HANA-02G1 — uzgodnienie katalogu HDI

**Aktualizacja KSEF-IN-03B, 14.11.2026:** obowiązuje dokładnie CORE14 + OWNER LAB + OWNER LAB OData:
**15 tabel / 5 widoków / 6 UNIQUE / 15 PK**, pełny eksport **70 wierszy**. Piąty widok
`BLOBSERVICE_DOCUMENTS` wymaga `IS_VALID=TRUE` i dokładnego SELECT siedmiu kolumn z tabeli LAB.
W `DETAIL` wiersza VIEW ma teraz `TRUE:<pełna definicja SQL>` zamiast `-`; inne widoki nadal `-`.
Szczegóły, granice normalizacji i test CSN/HDI: [IN-03B](KSEF_IN_03B_WORKER_READINESS.md#g1-po-blobservice).
Nie wykonano G1 LIVE. Wynik wdrożenia BlobService z 13.11 jest wcześniejszym raportem, nie nowym pomiarem katalogu.

Pierwotne zlecenie obejmowało wyłącznie walidator, testy offline i ten przewodnik. **Nie wykonano żadnej
operacji CF/HANA LIVE, taska, HDI make, DDL/DML, odczytu BLOB ani zmian LAB/CAP.**
W pierwotnym zleceniu celowo nie zmieniono STATUS, ROADMAP, README ani plików produkcyjnych
(ograniczenie zakresu miało pierwszeństwo nad workflow dokumentacji). Późniejsze
[CI recovery PR #17](../journal/2026-11-13-cap-hana-02g1-ci-recovery.md) obejmuje runner Chrome
i standardową dokumentację; nie zmienia kontraktu katalogu ani dalszych etapów laboratorium.

[Plan G0–G9](CAP_HANA_02_PERSISTENCE_ACCEPTANCE.md) · [Runner R2](CAP_HANA_02_R2_RUNNER.md) ·
[Decyzja A ORCH-05](../journal/2026-11-12-orch-05-hana-security-integration.md) ·
[Granice LAB](BLOB_LAB_01_OWNER_LED_HDI.md) ·
[Dowody właściciela](../journal/2026-11-12-blob-lab-01-owner-live-hdi-blob.md).

## Źródła i granice dowodów

| Warstwa | Dowód w tej sesji |
| --- | --- |
| Git | `git fetch origin` i `git ls-remote`: main = `4d4dbfca48f654f74cef787e6a680f8a79607512`; osobny worktree `D:\AInvoicer-cap-hana-02g1`, branch `feature/cap-hana-02g1-catalog-reconciliation`. Model LAB obecny od `30b6234`. |
| Lokalny design-time właściciela | Odczyt istniejących `D:\AInvoicer\cap-java\db\src\gen\*.hdbtable`, `*.hdbview`, `*.hdbindex`; nie są śledzone w Git i same nie dowodzą wdrożenia. |
| Niezależna kontrola offline | Przypięty lockfile: cds-dk 10.2.0, CAP Node/compiler 10.2.1/7.2.1. `cds.load('*')` i `cds.compile.to.hana(model, {sqlMapping:'plain'})` wykonane w pamięci dla niezmienionego modelu worktree: 15/4/6; porównano nazwy, wszystkie PK i kolumny LAB ze stałymi walidatora. LAB DDL zgodny z istniejącym plikiem właściciela. Bez budowania MTAR i bez zapisu artefaktów HDI. |
| Rzeczywisty katalog HANA / deployed design-time | **Nieodczytany** w tej sesji. Zgodność z MTAR/deployed HDI nadal wymaga dowodu przy osobno zatwierdzonym G1. |
| Wcześniejsze LIVE | Task #3 SUCCESS, tabela LAB, `CONTENT BLOB`, COUNT=0: OWNER-REPORTED / OWNER-SCREENSHOT-VERIFIED z 12.11; nie są niezależnym pomiarem pełnego katalogu G1. |

SHA-256 LAB `.hdbtable` po normalizacji do LF, usunięciu skrajnego whitespace i dodaniu końcowego LF:
`ea96b2b96e3b22e9fc4d328bf481ed599aa6ed70ab03c379882d7738efb2c2ce`.
To hash definicji offline, nie hash MTAR i nie dowód wdrożenia.

## Audyt i zatwierdzony kontrakt

Dotychczasowy skrypt porównywał nazwy 14 tabel, 4 widoków i 6 indeksów UNIQUE oraz obecność indeksu PK
na każdej tabeli. Nie sprawdzał kolumn PK ani LAB, pomijał nieunikalne indeksy i nie odrzucał wszystkich
duplikatów. Schemat pochodził z dokładnie jednego bindingu `hana/hdi-shared`, `ainvoicer-db`.
Kody wyjścia 0/2/1 zachowano; nie ma automatycznej naprawy ani retry.

Kontrakt ma trzy jawne zakresy:

| Zakres | Wymagania |
| --- | --- |
| CORE | Dokładnie historyczne 14 nazw tabel, 4 widoków i 6 unikalnych indeksów z `EXPECTED` w [walidatorze](../../deploy/scripts/hdi-catalog-readonly.cjs). PK(ID), z trzema wyjątkami poniżej. Każdy indeks UNIQUE musi należeć do właściwej tabeli. |
| OWNER LAB | Wyłącznie `CUSTOMER_CAP_JAVA_KSEFDOCUMENTS`, jeden PK(ID), dokładnie 9 kolumn opisanych niżej. Bez nowych indeksów UNIQUE. |
| OWNER LAB OData | Dokładnie `BLOBSERVICE_DOCUMENTS`, poprawny stan i definicja siedmiu kolumn według IN-03B. |
| Całość | W domyślnym trybie dokładnie 15 tabel/5 widoków/6 indeksów UNIQUE/15 PK. Brak obcych tabel, widoków, dodatkowych jawnych indeksów, duplikatów i niekompletnych metadanych. Każdy wiersz musi mieć oczekiwany fizyczny schemat HDI. |

Złożone PK z artefaktów (kolejność jest sprawdzana):

- `CUSTOMER_CAP_JAVA_KSEFSENDRESERVATIONS`: `(INVOICEUUID, ENVIRONMENT)`.
- `CUSTOMER_CAP_JAVA_KSEFINBOUNDSYNCCHECKPOINTS`: `(ENVIRONMENT, CONTEXTNIP, SUBJECTROLE)`.
- `CUSTOMER_CAP_JAVA_KSEFINBOUNDSYNCLEASES`: `(ENVIRONMENT, CONTEXTNIP, SUBJECTROLE)`.

Kolumny LAB po rozwiązaniu identyfikatorów fizycznych z `.hdbtable`:

| Nazwa katalogowa | Typ | Długość | Nullable |
| --- | --- | --- | --- |
| ID | NVARCHAR | 36 | FALSE |
| CREATEDAT | TIMESTAMP | bez parametru w DDL | TRUE |
| CREATEDBY | NVARCHAR | 255 | TRUE |
| MODIFIEDAT | TIMESTAMP | bez parametru w DDL | TRUE |
| MODIFIEDBY | NVARCHAR | 255 | TRUE |
| FILENAME | NVARCHAR | 255 | TRUE |
| MIMETYPE | NVARCHAR | 100 | TRUE |
| CONTENT | BLOB | bez parametru w DDL | TRUE |
| SHA256 | NVARCHAR | 64 | TRUE |

DDL zawiera `"MIMETYPE"`; pozostałe identyfikatory są niecytowane, więc HANA zapisuje je uppercase.
Wiersze katalogu zawierają rozwiązaną nazwę **bez otaczających cudzysłowów**. Walidator nie wykonuje
`UPPER()` na odpowiedziach: cytowane `"mimeType"` jest inną kolumną i daje MISMATCH.
Nazwy spoza obsługiwanego alfabetu identyfikatorów modelu są odrzucane, bez ich wypisywania.

Kontrola długości dotyczy NVARCHAR; katalogowa LENGTH dla TIMESTAMP/BLOB nie jest limitem zadanym
w tym DDL, więc nie porównujemy jej z wymyśloną stałą. Typ i nullable sprawdzamy zawsze.
Nie sprawdzamy definicji SQL czterech widoków CORE, wszystkich kolumn CORE, defaultów/triggerów, zawartości tabel,
kolumn indeksów UNIQUE ani grantów runtime. MATCH dotyczy opisanego kontraktu katalogowego.

## Tryby, SQL i raportowanie

Wszystkie wywołania CLI na laptopie są offline i nie otwierają połączenia:

```powershell
node deploy/scripts/hdi-catalog-readonly.cjs --print-runtime-source
node deploy/scripts/hdi-catalog-readonly.cjs --task-json
node deploy/scripts/hdi-catalog-readonly.cjs --print-console-sql
node deploy/scripts/hdi-catalog-readonly.cjs --from-console <scratch>/catalog.json --schema <oczekiwany-schemat-HDI>
```

Opcjonalne `--core-only` oznacza jawny historyczny kontrakt 14/4/6 i `OWNER_LAB=NOT_REQUESTED`.
Nie wybiera się go automatycznie na podstawie liczby tabel. W tym trybie obecna tabela LAB
jest nadmiarem; **nie wolno użyć go do akceptacji obecnego wspólnego kontenera CORE+LAB**.

Zachowano `--task-json`, `--print-runtime-source`, nazwę taska i pola JSON CAPI (256 MB/512 MB).
Rozszerzony runtime używa Brotli+base64 zamiast gzip, obsługiwanego przez Node 22/24 bez dodatkowych
pakietów. Test rozpakowuje i wykonuje dokładnie wysyłany program, również dla przypadków negatywnych.
IN-03B używa przypiętego Terser 5.44.1 (tylko generator) przed Brotli. Lokalnie polecenie ma
4071 znaków przy limicie 4096; generator odmawia przekroczenia limitu.
SHA-256 programu runtime w domyślnym trybie:
`6ebdfd14863cc7a69514e3065759712c3485d51053c052b7b9e68a632aad5785`.
Stary hash/gzip i liczba 14 w runbooku G1 są historyczne i nie obowiązują dla tego patcha.

Jedyny SQL to parametryzowany `SELECT ... UNION ALL ...` do `SYS.TABLES`, `SYS.VIEWS`, `SYS.INDEXES`,
`SYS.INDEX_COLUMNS` (kolumny PK) i `SYS.TABLE_COLUMNS` (tylko LAB). Pięć parametrów to ten sam schemat
z bindingu. Schemat jest także zwracany przy każdym wierszu i porównywany, ale nie logowany.
Brak DDL/DML/CALL, `SELECT *`, odczytu wartości CONTENT lub biznesowych COUNT.
Wersja Console zastępuje parametry przez `CURRENT_SCHEMA` bez konkatenowania nazwy schematu do SQL.

Przykład zgodnego wyniku z przyszłego pomiaru skryptu (nie wynik LIVE tej sesji):

```text
HDI_CATALOG verdict=MATCH tables=15/15 views=5/5 uniqueIndexes=6/6 primaryKeys=15/15 missing=[] unexpected=[]
HDI_CATALOG_CORE=PASS HDI_CATALOG_OWNER_LAB=PASS HDI_CATALOG_OWNER_LAB_ODATA=PASS evidence=SCRIPT_MEASURED
```

| Kod | Znaczenie / działanie |
| --- | --- |
| 0 | MATCH dla wybranego trybu. Nadal sprawdzić klasę dowodu i tożsamość środowiska. |
| 2 | MISMATCH: brak/nadmiar/duplikat/niezgodna definicja/schemat lub pusta odpowiedź. STOP, analiza źródeł i uprawnień, bez automatycznych zmian DB. |
| 1 | Błąd/odmowa: binding, CI, DBADMIN, połączenie, SQL, timeout lub niepoprawny plik importu. Brak werdyktu MATCH. |

CORE i LAB mają osobne statusy; błędy globalne (np. nieprawidłowy schemat lub uszkodzone wiersze)
nie dają PASS dla LAB. Raport błędu zawiera tylko etap, kod, SQLState i kontrolowany lokalny powód,
bez treści błędu serwera, hosta, użytkownika, hasła, certyfikatu lub schematu.

## SQL Console właściciela — ścieżka bez taska

1. Właściciel otwiera **HANA Cloud Central → Schemas & HDI Containers → ainvoicer-db →
   Open HDI Container in SQL Console**. To ścieżka udokumentowana wcześniejszym wykonaniem właściciela,
   nie nowa weryfikacja UI. Nie wybiera konsoli instancji DBADMIN. Jeśli UI żąda service key,
   nowego bindingu lub podniesienia uprawnień — STOP.
2. W konsoli sprawdza lokalnie `SELECT CURRENT_USER, CURRENT_SCHEMA FROM DUMMY;`.
   Porównuje fizyczny schemat z zaufaną identyfikacją istniejącego kontenera; sufiks `_RT` i zgodny
   tekst schematu w pliku same nie dowodzą tożsamości kontenera. Nie publikuje użytkownika ani schematu.
3. Generuje `--print-console-sql`, przegląda SQL i samodzielnie wykonuje go w tym kontekście.
   Zachowuje **pełną listę** wierszy z czterema nagłówkami `KIND, NAME, DETAIL, SCHEMA_NAME`;
   sprawdza brak obcięcia/paginacji/limitu eksportu. Nie wystarczy screenshot liczników.
4. Jeżeli UI daje CSV, konwertuje go lokalnie do kontraktu JSON poniżej; bez uploadu do zewnętrznych
   konwerterów. Nie zakładamy natywnego eksportu JSON w aktualnej wersji Cloud Central.
   Dla CSV UTF-8 z nagłówkami i separatorem przecinek wystarcza PowerShell:

   ```powershell
   $csv = Import-Csv -LiteralPath '<scratch>/catalog.csv' -Encoding UTF8
   @{ version = 1; rows = @($csv) } | ConvertTo-Json -Depth 5 |
       Set-Content -LiteralPath '<scratch>/catalog.json' -Encoding UTF8
   node deploy/scripts/hdi-catalog-readonly.cjs --from-console '<scratch>/catalog.json' --schema '<oczekiwany-schemat-HDI>'
   ```

   Dostosować `Import-Csv -Delimiter` do rzeczywistego eksportu, bez ręcznego dzielenia przecinków
   i bez poprawiania wartości metadanych. `<oczekiwany-schemat-HDI>` ustalić niezależnie przed importem,
   nie przepisywać automatycznie pierwszego wiersza z niezweryfikowanego pliku.

Kontrakt importu: obiekt z dokładnie `version: 1` i `rows: [...]`; każdy wiersz ma dokładnie cztery
wymienione pola tekstowe. Plik UTF-8 (BOM dozwolony), maks. 5 MiB. Przykład jednego wiersza
(samodzielnie da MISMATCH z powodu braku pozostałych obiektów):

```json
{"version":1,"rows":[{"KIND":"COLUMN","NAME":"CONTENT","DETAIL":"BLOB:0:TRUE","SCHEMA_NAME":"SYNTH_SCHEMA"}]}
```

`DETAIL` dla TABLE i VIEW CORE: `-`; VIEW LAB: `TRUE:<pełna definicja SQL>`;
INDEX: `<CONSTRAINT>@<TABLE_NAME>`; PK_COLUMN: `<POSITION>:<COLUMN_NAME>`
z NAME=tabela; COLUMN: `<DATA_TYPE_NAME>:<LENGTH-lub->:<IS_NULLABLE>` z NAME=kolumna LAB.
Skrypt przyjmuje oba zapisy constraintów HANA: `PRIMARY_KEY`/`PRIMARY KEY`,
`NOT_NULL_UNIQUE`/`NOT NULL UNIQUE`. Nie normalizuje nazw tabel/kolumn.

Brak wymaganych pól, obce pola (także `verdict`), deklarowane liczniki zamiast wierszy i niepoprawny JSON
są odrzucane. Puste, niekompletne lub sprzeczne listy dają MISMATCH. Import nigdy nie łączy się z HANA
ani CF i zawsze emituje `evidence=OWNER-REPORTED`, nawet dla syntetycznego pliku dającego MATCH.
Nie jest podpisem, dowodem autentyczności eksportu ani dowodem połączenia LIVE skryptu.
Surowy eksport przechowywać lokalnie poza Git; do review przekazać oczyszczony raport oraz kontekst/czas
pomiaru, wersję skryptu, hash runtime i potwierdzenie kompletności. CSV/JSON nie zawiera BLOB ani danych faktur.

## Uprawnienia i procedura alternatywna

SAP opisuje `SYS.*` jako dostępne co do zasady przez PUBLIC, ale **wyniki są filtrowane według grantów**.
`CATALOG READ` daje szerszą widoczność; nie wnioskujemy, że runtime HDI ma taki grant lub pełny katalog.
Odczyt COUNT tabeli przez `_RT` nie potwierdza dostępu do wszystkich pięciu widoków katalogowych.
SQL ADT/ABAP i sam fakt bindingu również tego nie potwierdzają.

Przy `[258] insufficient privilege` skrypt kończy się kodem 1 na PREPARE/SELECT; brak retry i GRANT.
Ciche filtrowanie/niepełna widoczność daje MISMATCH (2). Nie da się z tej odpowiedzi odróżnić każdego
braku obiektu od braku widoczności; nie wolno automatycznie tworzyć tabel ani osłabiać kontraktu.
SQL Console `_RT` może mieć ten sam problem i nie jest obejściem grantów. Wtedy zatrzymać G1,
zebrać bezpieczny kod/etap i przekazać właścicielowi/administratorowi do kontrolowanego odczytu
metadanych w już uprawnionym kontekście kontenera. Zmiana grantów, DBADMIN, service key, binding
lub narzędzia dostępu wymaga nowego zakresowego zlecenia; nie należy do G1.
Widoczność niezatwierdzonych obiektów może być ograniczona przez granty, dlatego MATCH oznacza
zgodność **widocznego** katalogu, nie audyt wszystkich ukrytych obiektów całej HANA.

Źródła SAP sprawdzone 13.11.2026:
[INDEXES](https://help.sap.com/docs/hana-cloud-database/sap-hana-cloud-sap-hana-database-sql-reference-guide/indexes-system-view),
[INDEX_COLUMNS](https://help.sap.com/docs/hana-cloud-database/sap-hana-cloud-sap-hana-database-json-document-store-guide/20a6a57e751910149887cbf423a0a16a.html),
[TABLE_COLUMNS](https://help.sap.com/docs/hana-cloud-database/sap-hana-cloud-sap-hana-database-sql-reference-guide/table-columns-system-view),
[CAP compile.to.hana](https://cap.cloud.sap/docs/node.js/cds-compile#hana).

## Walidacja offline i dalsze bramki

Aktualne testy IN-03B: katalog **63/63 PASS**, deploy **105 PASS / 1 Go SKIP** lokalnie.
Niżej zachowano historię oryginalnego G1; pełne nowe wyniki i CI są w [dzienniku IN-03B](../journal/2026-11-14-ksef-in-03b-worker-readiness.md).

Node 24.13.0 / npm 11.6.2 na Windows:

- `node --test deploy/test/hdi-catalog-readonly.test.mjs`: 48/48 PASS; historyczny CORE, CORE+LAB,
  błędne PK, typ/rozmiar/nullable LAB, nadmiar, case/quoting, duplikaty, brak schematu/metadanych,
  puste odpowiedzi, privilege 258, błędne dane Console, pochodzenie dowodu i dokładny runtime taska.
- `npm test --prefix deploy`: 91 testów, 90 PASS, 1 SKIPPED (lokalnie brak Go; wymagany w CI).
  Pierwsza próba: MBT nie miał binarnej zależności po `npm ci --ignore-scripts`; po
  `npm rebuild mbt --prefix deploy` ponowny pełny zestaw PASS z powyższym skip.
- `npm run validate:smoke --prefix deploy`: PASS (wyłącznie lokalny deskryptor).
- Osobne porównanie 25 artefaktów HDI wygenerowanych w pamięci: PASS; szczegóły w tabeli źródeł.
- Próba `npm run test:cf-runtime --prefix deploy`: 10 FAIL z powodu braku
  `cap-java/srv/target/cap-java-exec.jar` w nowym worktree. Nie jest lokalnym PASS runtime;
  JAR ani MTAR nie budowano dla tego patcha. Pełny build/runtime w istniejącym CI PR.
- Skan `node deploy/scripts/check-secrets.mjs` i `git diff --check`: wykonywane przed commitem.
  Skan heurystyczny nie zastępuje audytu credentiali; `.cjs` dodatkowo sprawdzony tymi samymi wzorcami.
- `npm ci --prefix deploy --ignore-scripts`: audit 0; `npm ci --prefix cap-java --ignore-scripts`:
  2 HIGH istniejącego toolchainu developerskiego. Bez aktualizacji zależności lub force fix.

CI bieżącego SHA należy sprawdzić w osobnym PR G1. Workflow pozostaje bez zmian: `pull_request`,
`contents: read`, brak credentiali BTP/KSeF, lokalne mocki/emulator i build bez deploymentu.
Nie uruchamiano workflow z credentialami ani ręcznego dispatchu. Zielone CI nie oznacza HANA LIVE PASS.

**GO do review i osobnego zatwierdzenia read-only G1 po zielonym CI dokładnego HEAD.**
**NO-GO wykonawcze bez zgody; HANA LIVE PENDING.** Właściciel może wybrać Console i import
OWNER-REPORTED albo późniejszy jednorazowy task deployera. Przed zgodą na task trzeba przedstawić
konkretny commit, `--print-runtime-source`, aktualny SHA runtime, `--task-json`, target dev,
istniejący binding `ainvoicer-db`, potwierdzenie braku service keys i rozmiar 256/512 MB.
Zgoda ma dotyczyć wyłącznie jednego taska SELECT i oczekiwanego katalogu CORE+LAB, bez DDL/DML,
restartu, provisioningu lub kolejnych bramek. W tej sesji nie wysłano takiego żądania.

G1 jest niezależne od stagingu R2. Przyjęty kompletny wynik G1 dopiero usuwa blocker katalogu dla
decyzji o G2; **nie autoryzuje G2**, G3 check, G4 DML, restartu ani cleanup. R2 nadal wymaga
oddzielnego review artefaktu/runtime/quota i osobnych zgód zgodnie z jego przewodnikiem.
Następny najmniejszy krok: review PR/CI, potem decyzja właściciela o metodzie i zgodzie na sam odczyt G1.
