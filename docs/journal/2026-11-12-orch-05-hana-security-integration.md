---
title: "ORCH-05 — review i integracja HANA R2 oraz CAP-07E"
date: 2026-11-12
type: journal
status: zakonczony
tags: [ainvoicer, journal, orch-05, hana, security, integration, owner-led]
---

# ORCH-05 — review, wyniki i decyzje właściciela

[STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Project Home](../README.md) ·
[R2](../guides/CAP_HANA_02_R2_RUNNER.md) · [CAP-07E](../guides/CAP_07E_SECURITY_ACCEPTANCE.md) ·
[BLOB-LAB — dowody właściciela](2026-11-12-blob-lab-01-owner-live-hdi-blob.md)

## Date / Goal

12.11.2026, Europe/Warsaw. Codex: niezależny review dwóch gałęzi, kontrolowana integracja tylko po
zielonym CI dokładnych HEAD, regresja offline i trzy decyzje przed dalszym HANA/security.
Nie wykonano żadnych operacji LIVE CF/HANA/H01/KSeF. `cf version` i `cf push --help` odczytują lokalny CLI.

## Initial State / Branch

- Główny checkout `D:\AInvoicer`: `main`, czyste drzewo. Po `git fetch origin` i GitHub API:
  `main=ccbf0e7181a52ff209c368fe6af50a2177aa89b4`, zamiast podanego `2fe9402`.
- **Istotna zmiana właściciela:** commit [30b6234](https://github.com/scaamp/AInvoicer/commit/30b6234)
  już dodał `cap-java/db/documents.cds` do Git; merge `ccbf0e7` go zachował. Agent nie dodawał ani
  nie modyfikował tego pliku. Informacja „brak CDS w Git” jest prawdziwa dla wcześniejszej sesji LAB,
  lecz nie dla obecnego `main`. Zakaz dalszej autonomicznej implementacji LAB pozostaje w mocy.
- R2: [PR #15](https://github.com/scaamp/AInvoicer/pull/15), HEAD
  `4dd9ce172068fbc555a504915e4219806b295b67` (commity `8f425db`, `4dd9ce1`).
- CAP-07E: HEAD `18a275a294a6fe21c03cf2e77dbbb6c5a7d0e880`; brak PR i brak uruchomionego CI.
  Utworzono [PR #16](https://github.com/scaamp/AInvoicer/pull/16), następnie poprawiono realną lukę
  narzędzia: [715ef432f46af4454b9b277fe3d43510439123bf](https://github.com/scaamp/AInvoicer/commit/715ef432f46af4454b9b277fe3d43510439123bf).
- Izolowane worktree `D:\AInvoicer-orch-05` (`review/orch-05-hana-security`) i
  `D:\AInvoicer-orch-05-security` (`review/cap-07e-redirect`). Nie nadpisano istniejących worktree agentów.
- Przeczytano AGENTS, CLAUDE, STATUS, ROADMAP, dzienniki HANA/LAB, skill CAP i ADR-0001/0006/0007.

## Code Review — PR #15

**PASS WITH NOTES — kod offline dopuszczony do merge; brak zgody wykonawczej G1–G4.**

| Obszar | Wynik review |
| --- | --- |
| Oryginalny harness | Diff `HanaPersistenceIT.java` względem main pusty. Kopiowany jest oryginalny skompilowany IT, bez zmiany faz/asercji, test resources, H2 seed i innych IT. |
| JAR / JUnit | Boot `JarLauncher`, zmieniony wyłącznie Start-Class launchera; nested JAR w STORED, nowy classpath.idx, launcher/engine 6.0.3 z tego samego zestawu Maven; klasy Java 25 (major 69). Brak Mavena w CF. |
| Integralność | Rehash runner.jar, manifestu, każdego wpisu ZIP i śledzonych źródeł; kontrola uprawnień Unix i niezbędnych klas/drivera; testy usunięcia IT, zmiany manifestu i bajtów JAR. Hash nie jest podpisem ani dowodem tożsamości wdrożonego dropletu. |
| Consent | Domyślny start odmawia przed Spring. Read-only tylko check/recover/verify-cleanup; seed/contend/cleanup wymagają tokenu zapisowego. Nie ma domyślnej fazy ani generatora przyznającego consent. Aktualność zgody sprawdza człowiek — tekstowy token nie jest systemem autoryzacji. |
| Środowisko | Dokładne app/API/org/space i puste URI; dokładnie HDI ainvoicer-db + XSUAA ainvoicer-auth; DBADMIN, dodatkowe bindingi, service key markers, CI, nadpisania DataSource/config i flagi efektów ON odrzucane. Pochodzenie bindingów/0 service keys wymaga dodatkowo metadanych CAPI. |
| Izolacja | Zero web instances, no-route, wyłączone KSeF/INBOUND/WRITE/sweeper/outbox i brak Destination; brak startu Application.main. Runtime user HDI ma DML — „read-only” opisuje fazę, nie grant SQL. |
| Logowanie | JUnit/Spring stdout/stderr wyciszone; tylko stałe znaczniki/asercje SQLState/vendor code. Brak logowania VCAP/credentiali/JDBC URL; przed Main logi JVM/buildpacka pozostają poza filtrem. |
| CF CLI 8 | Lokalny CLI 8.19.0 i oficjalny kontrakt: pierwszy push --no-start nie stage'uje; bindingi powstają przed drugim push --task --no-route; --task stage'uje bez web/startu/tasku. Manifest 0/no-route zgodny. |
| Task API | POST /v3/apps/:guid/tasks: command, name, memory_in_mb=1024, disk_in_mb=1024. Command jednej fazy, bez service keys i credentiali; Java z dropletu, -Xmx512m/-XX:MaxMetaspaceSize=256m i exploded JarLauncher. Shell wartości ograniczone do walidowanych stałych/UUID/NIP. |
| JVM | Launcher/JUnit i exploded layout sprawdzone w nowych JVM JDK25. Harness nie używa Mockito mocks. Brak wykazanego wymagania javaagent/add-opens; lokalne testy nie potwierdzają JRE/buildpacka SAP w CF. Ścieżka JRE, moduły i zużycie pamięci wymagają osobno zatwierdzonego G2/G3. Nie deklarujemy ich LIVE PASS. |
| PowerShell / usuwanie | Wygenerowane skrypty przechodzą Parser PowerShell. Ponowny target guard, odmowa nadpisania istniejącego runnera i przerwanie na błędzie CLI; cleanup sprawdza aktywne taski i potwierdzenie człowieka, usuwa tylko dwa bindingi runnera i runner, bez -r/delete-service. Nie usuwa danych HANA. |

Uwagi: G1 ma zamrożony katalog 14 tabel i nie jest zgodny z rozszerzeniem LAB; fs4 jest deprecated;
koszt/quota i faktyczny runtime CF pozostają niezweryfikowane w tej sesji. Żadna z tych uwag nie jest
powodem do automatycznego DDL, zmiany oczekiwań ani wdrożenia runnera.

## Code Review — CAP-07E / PR #16

**Wersja 18a275a: BLOCKED do poprawki klasyfikatora. Wersja 715ef43: PASS WITH NOTES dla kodu offline,
z oddzielnym warunkiem zielonego CI. Security acceptance nadal PENDING.**

- Zakres źródłowy: nowa klasa testowa, sondy/testy Node, oczyszczony raport JSON i dokumentacja.
  Brak zmian produkcyjnej implementacji CAP, XSUAA, DedicatedTenantPolicy, MTA, Destination/IAM lub LAB.
- 6 nowych testów faktycznie uruchamia skład HANA readiness bez IdentitySmokeReadOnlyHandler, oba outbox
  OFF i transporty OFF; H2 i jawne ustawienia security zastępują profil cf, ponieważ guard CF słusznie
  odrzuca H2. Test nie jest dowodem połączenia HANA ani LIVE tokenu XSUAA.
- Brak roli, obcy tenant, list/count/key/OR/expand/private entities, brak nowych operacji po odrzuceniu
  requestSend oraz dozwolony raw CREATE scharakteryzowano offline. Nie osłabiono polityki tenanta.
- Raport 38/38 ma wyłącznie statusy/klasyfikację/czasy/correlation/request ID, bez tokenów, cookies,
  surowych odpowiedzi, danych użytkowników i parametrów OAuth. To anonimowe GET, w tym syntetyczny
  malformed JWT; nie jest to test istniejącego business usera ani autoryzacji H01.
- **Defekt ORCH-05-S1, MEDIUM:** poprzedni klasyfikator HTML200 przyjmował np. URL XSUAA w zmiennej
  i faktyczne `window.location="/public-app"`. Poprawka 715ef43 sprawdza całą gramatykę strony i rzeczywisty
  literalny cel nawigacji, dozwolone instrukcje cookie z AppRouter23.4.0 i nonce, Content-Type/no-store;
  dodatkowy HTML/JS, HTTP/lokalny cel/niestandardowy port i wabiki dają FAIL. Dwie nowe regresje.
- Historyczny raport 38/38 nie został zmieniony. **36 sond nie korzysta z poprawianej klasyfikacji;
  2 nawigacje wymagają ponownego LIVE pomiaru poprawionym narzędziem przed sign-off.** Nie wykonano go tutaj.
- `requestSendWithKsefDisabled…` dowodzi braku zapisu po błędzie, lecz ogólne `status>=400` nie dowodzi
  dojścia do guardu KSeF (SAP może zawieść wcześniej). Kolejność E-3 potwierdzono inspekcją handlera.

## E-2 i E-3 — decyzje biznesowe pozostawione właścicielowi

**E-2, MEDIUM/LOW:** grant CREATE w `ksef-service.cds` + `KsefOperationsHandler` pozwala KsefOperator
zapisać własne CHECK_STATUS/RECEIVE_INVOICE przy transporcie OFF. Test HTTP/H2 potwierdza201, nowy wiersz,
status NEW i TEST; SEND_INVOICE jest blokowane. Ten sam kontrakt na HANA oznacza możliwość trwałego
szumu, ale w tej sesji nie wykonano LIVE POST. To nie jest bypass istniejącej autoryzacji.
UI mapper i reguły wyliczają wynik wysyłki z SEND_INVOICE, więc raw CREATE nie podrobi wyniku KSeF;
wiersze pozostają widoczne w historii operacji i mogą zaśmiecać monitoring/audyt. Rekomendacja:
przed aktywacją KSeF usunąć publiczny grant CREATE, pozostawić READ i dedykowane akcje.
Minimalna regresja po decyzji: własny/obcy/no-role CREATE oraz batch odrzucone i DB bez przyrostu;
requestSend z dozwolonym transportem nadal atomowo zapisuje zlecenie. **Patch niewykonany.**

**E-3, LOW:** CAP sprawdza rolę przed handlerem; potem `requestSend → parse UUID → SAP READ
technicznym Destination → 404 jeśli brak → autoryzacja NIP/spółka (403) → reguły faktury →
worker.acceptsContext (409)`. Przy KSeF OFF uprawniony operator nadal może wywołać techniczny READ
i rozróżnić brak od obcego zakresu (gdy zna UUID). Losowe UUID utrudniają enumerację, lecz nie usuwają
wycieku istnienia. Minimalny patch po decyzji: guard wyłączonego transportu po walidacji wejścia,
przed sap.readInvoice; kontrolę zakresu zachować po SAP READ dla trybu ON. Regresja: OFF zawsze
ten sam bezpieczny błąd dla syntetycznych własnych/obcych/brakujących UUID, zero wywołań SAP i zapisów;
ON zachowuje 403/404, idempotencję i brak zapisu dla obcego zakresu. **Patch niewykonany.**

## HANA / Git / G1 — oddzielne stany

| Warstwa | Zweryfikowany stan |
| --- | --- |
| Bazowy model integracyjny | Git ma dotychczasowe14 tabel/4 widoki/6 unikalnych indeksów; źródłowy skrypt G1 porównuje dokładnie ten historyczny zbiór. |
| Model właściciela | W main od30b6234 jest KsefDocuments(cuid, managed): fileName255, mimeType100, content LargeBinary, sha25664. To nadal LAB, bez serwisu mediów lub adaptera storage KSeF. |
| Fizyczne HDI | OWNER-REPORTED / OWNER-SCREENSHOT-VERIFIED: task#3 SUCCEEDED, CUSTOMER_CAP_JAVA_KSEFDOCUMENTS.CONTENT=BLOB, SELECT COUNT(*)=0 jako _RT. Pełny katalog15 tabel nie został niezależnie odczytany. |
| G1 | Oczekuje14 tabel i14 PK; LAB może dać unexpected TABLE i15/14 PK. MISMATCH nie oznacza automatycznie uszkodzenia bazy. G1 nie bada szczegółowych kolumn ani definicji widoków. |
| Następny deploy | Obecny main zna już źródło LAB; stare wydania/release worktree sprzed30b6234 go nie znają. Przed deploymentem wymagane porównanie dokładnego MTAR/deployed design-time, nie tylko nazwy Git branch. |

HDI deployer działa różnicowo: brakujący plik nie jest domyślnie automatycznie undeployowany;
`undeploy.json` lub `--auto-undeploy` mogą zaplanować jego usunięcie. Obecny generator odrzuca undeploy
i auto-undeploy w pakiecie, ale nie dowodzi konfiguracji przyszłego taska ani env deployera.
Zmiana definicji istniejącego hdbtable może również zmienić tabelę. **Nie można gwarantować ani
automatycznego skasowania, ani zachowania LAB przez dowolny przyszły HDI make.** Nie wykonano make,
simulate-make, DDL, cleanup ani odczytu danych LAB w tej sesji.

## Decyzja A — HDI catalog (do zatwierdzenia)

**GO do review kryteriów; G1 wykonawczo NO-GO w obecnym kontrakcie.** Zalecany jawny baseline:

1. Podział dowodu na zatwierdzony core14 i dokładnie jeden wyjątek właściciela LAB; żadnego ogólnego
   „>=14” ani akceptacji dowolnej15. Zbiory nazw core14/4 widoków/6 indeksów muszą być identyczne
   z przypiętym modelem/artefaktem wydania; każdy brak lub inny nadmiar = STOP.
2. Dodatkowa tabela tylko `CUSTOMER_CAP_JAVA_KSEFDOCUMENTS`, PK(ID), oczekiwane9 kolumn cuid+managed+4
   własne: ID NVARCHAR36 NOT NULL, CREATEDAT/MODIFIEDAT TIMESTAMP, CREATEDBY/MODIFIEDBY NVARCHAR255,
   FILENAME NVARCHAR255, MIMETYPE NVARCHAR100 (quoting zgodnie z DDL), CONTENT BLOB, SHA256 NVARCHAR64.
   Sprawdzić nullability/defaulty dokładnie z zatwierdzonym hdbtable, a nie domyślać się z samego licznika.
3. Dołączyć źródłowy commit30b6234 i hash CDS/hdbtable/MTAR, zestawić z rzeczywistym design-time HDI;
   obecne screenshoty potwierdzają CONTENT i pustą tabelę, nie pełną zgodność wszystkich kolumn.
4. Po decyzji osobno zamówić mały patch komparatora i testy: core bez LAB (historyczny), zaakceptowane
   core+LAB, brak core, obca15, zły PK/typ/kolumny LAB. Obecny skrypt wymaga rozszerzonego SELECT dla
   szczegółów kolumn; niczego nie zmieniono wyłącznie dla PASS. Dopiero potem zgoda na read-only G1.

Zmiana oczekiwań G1 ani commit owner-led LAB nie należą do wykonanych poprawek ORCH-05.

## Decyzja B — runner R2 (do zatwierdzenia po G1)

**NO-GO na G2 teraz:** katalog nieuzgodniony i brak aktualnej zgody stagingowej.

| Parametr do zatwierdzenia | Zakres |
| --- | --- |
| Aplikacja | ainvoicer-hana-it; API EU20-001, org National Center for Cryptography_dev-v2xzij9s, space dev |
| Źródło | HEAD PR#15: `4dd9ce172068fbc555a504915e4219806b295b67`. Pakiet po integracji: evidence.commit=`d4dc2047e690d7f8c6099a7d2c4dd0f37f53608d`; runner.jar SHA-256=`6afb2da17a57a21ec365b850d64508955a47273ecb3809308793cacc1db6b069`. Przed zgodą ponownie przeliczyć hash i inputs; rebuild daje nowy hash. |
| Memory/disk | 1GiB RAM i1GiB disk/task; dwa contend=2GiB RAM; staging ma osobną rezerwację. Quota nie była odczytywana LIVE. |
| Bindingi | Wyłącznie istniejące ainvoicer-db(hana/hdi-shared) i ainvoicer-auth(xsuaa/application); bez keys/DBADMIN/nowych usług. |
| Runtime | cflinuxfs4, sap_java_buildpack_jakarta, SapMachine25; buildpack i ścieżkę JRE potwierdzić w zatwierdzonym stagingu. |
| Powierzchnia | no-route,0 web instances, brak domyślnej fazy/consentu; osobny task check po G2, bez automatycznego DML. |
| PAYG | Task/staging, storage dropletu i istniejąca HANA mogą zużywać płatną quota. Zero instances nie jest gwarancją kosztu0. |
| Usuwanie | Po braku aktywnych tasków i zatwierdzonej kontroli danych: wygenerowany cleanup, unbind tylko dwóch bindingów runnera i delete tylko runnera. Usługi/CAP pozostają. Danych to nie usuwa. |
| DML | G4 wymaga nowej, niezależnej zgody na dokładny run UUID/NIP/HDI/fazę i plan recover/cleanup; zgoda G1/G2/check nie uprawnia do seed/contend/cleanup. |

## Decyzja C — security (najmniejszy zestaw działań właściciela)

**Security sign-off NO-GO / PENDING.** Właściciel/administrator:

1. Zapewnia drugie konto B bez admin/developer, bez roli CAP i bez Business Role H01; uzgadnia znane
   syntetyczne fixtures A/B. A z rolą ma odczyt własnych danych; B bez ról dostaje403/0 również dla
   bezpośrednich dzieci RAP. Następnie B z własnym zakresem: lista/count/known keys/OR/expand nie
   ujawniają danych A. Losowy UUID404 lub puste zbiory bez fixtures nie zaliczają izolacji.
2. Odczytuje Destination AINV_H01_UI_USER: OAuth2UserTokenExchange, właściwy binding/URL/audience,
   bez technicznych User/Password i bez fallbacku do AINV_H01/ClientCredentials; sprawdza mapowanie
   business usera i efektywne role/atrybuty XSUAA. Funkcjonalne SSO/HTTP200 to osobny dowód.
3. Organizuje krótki, zatwierdzony standardowy trace/audit H01 z user+ścieżka+czas/korelacja dla jednego
   GET A i B, potem wyłącza diagnostykę. Test różnicowy A>0/B=0 jest dowodem pośrednim DCL, nie
   autorytatywną identyfikacją principala konkretnego GET. Brak narzędzia = nadal BLOCKED.
4. Decyduje o E-2/E-3; potwierdza commit/MTAR SHA wdrożonego dropletu, kontroluje nagłówki cache,
   logout i expiry15min w osobnej sesji oraz ponawia dwie nawigacje poprawionym narzędziem.

Agent nie tworzył kont, ról, obiektów ABAP ani konfiguracji IAM/Destination; plan nie jest ich zgodą wykonawczą.

## Implementation / Changed Files

- PR#16: poprawka `deploy/scripts/live-security.mjs`, regresje `deploy/test/live-security.test.mjs`,
  dopisek review do `docs/guides/CAP_07E_SECURITY_ACCEPTANCE.md`. Raportu historycznego nie zmieniono.
- ORCH-05: ten dziennik, `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/README.md`.
- Przewodnik R2: aktualizacja stanu Git LAB oraz doprecyzowanie, że seed zapisuje4 tabele,
  a piątą (sync leases) dopiero contend; kod i semantyka harnessu bez zmian.
- Poza źródłami: tylko ignorowane logi/buildy, pakiet R2 i lokalna analiza w izolowanych worktree.
- Bez zmian agentowych: documents.cds, model/handler produkcyjny CAP, MTA/extensions, XSUAA,
  SAP/HANA/CF runtime, transporty i outbox.

## Commands Executed / Tests & Results

- Git: status/branch/log/diff, fetch, merge-tree, dwa nowe worktree, lokalne merge do regresji;
  jawne add trzech plików poprawki, commit715ef43 i zwykły push feature branch (bez force).
- API GitHub: odczyty main/PR/Actions, utworzenie PR#16. CI sprawdzane po head_sha, nie po samym tytule.
- Node24.13.0, JDK25.0.4.101, Maven3.9.16, lokalny cf8.19.0; npm ci dla deploy/approuter.
- `node deploy/scripts/hana-it-runner.mjs build`: Maven clean verify, **476 run /470 PASS /6 skipped**
  (Azurite),0 failures/errors, JAR i pakiet R2 PASS. To regresja wspólnego drzewa, nie realnej HANA.
- `node deploy/scripts/hana-it-runner.mjs verify/test`: integralność PASS, **5/5** procesów/scenariuszy
  offline PASS (wiele nowych JVM), także exploded Boot launcher; bez połączenia HANA.
- Po poprawce: `npm test --prefix deploy`: **51 PASS /1 skipped** (Go niedostępne lokalnie);
  `node --test approuter/test/xs-app.test.mjs`: **10/10 PASS**.
- `node deploy/scripts/check-secrets.mjs`, `git diff --check`, parser wygenerowanych PowerShell: PASS.
  Skan wzorców to heurystyka, nie pełny audyt credentiali.
- Nie uruchamiano sond LIVE, testów live-sap/live-ksef, profilu hana-integration, CF push/run-task/deploy,
  restartu/usuwania/bindingów, SQL HANA ani BLOB upload/download lub osobnego builda HDI/deploymentu LAB.
  Ogólna regresja Maven kompiluje niezmieniony, śledzony model main, również encję właściciela;
  nie dodaje usługi mediów i nie realizuje żadnego kolejnego kroku LAB. SHA-256 niezmienionego CDS
  w checkoutcie Windows: `8d5b3e5837c8f1dd8d6fd279889f3295448554dfb7d9b67ded0a07720a2cc3e3`.

## CI / Merge / Current State

| Bramka | Wynik |
| --- | --- |
| R2 dokładny HEAD | `4dd9ce172068fbc555a504915e4219806b295b67`, [CI43283992370 attempt2 SUCCESS](https://github.com/scaamp/AInvoicer/actions/runs/43283992370/attempts/2); potwierdzone GitHub API po head_sha. |
| CAP-07E pierwotny HEAD | `18a275a` nie miał CI przed PR; run43288775071 anulowany automatycznie po push poprawki, nie jest PASS. |
| CAP-07E końcowy HEAD | `715ef432f46af4454b9b277fe3d43510439123bf`, [CI43289386147 SUCCESS](https://github.com/scaamp/AInvoicer/actions/runs/43289386147). |
| Aktualny main przed merge | `ccbf0e7181a52ff209c368fe6af50a2177aa89b4`, niezależnie [CI43288447486 SUCCESS](https://github.com/scaamp/AInvoicer/actions/runs/43288447486). |
| Merge R2 | [f55d0e8c0736055b698b1714927aec836fc2930b](https://github.com/scaamp/AInvoicer/commit/f55d0e8c0736055b698b1714927aec836fc2930b), PR#15 MERGED przez API z expected_head_sha. |
| Ponowna bramka CAP-07E | Po fetch main=f55d0e8, merge-tree bez konfliktu; HEAD715ef43 i CI SUCCESS ponownie potwierdzone. Lokalny wspólny model/testy zawierają oba PR-y. |
| Merge CAP-07E | [d4dc2047e690d7f8c6099a7d2c4dd0f37f53608d](https://github.com/scaamp/AInvoicer/commit/d4dc2047e690d7f8c6099a7d2c4dd0f37f53608d), PR#16 MERGED przez API z expected_head_sha. |

Git diff między lokalnym recenzowanym drzewem obu PR-ów a rzeczywistym main=d4dc204 jest pusty.
Po merge przełączono izolowany worktree na detached d4dc204 i uruchomiono ponownie pełny build R2,
regresję Maven/Node/AppRouter i integralność. **PASS po merge:** Maven476 (470 PASS/6 skipped),
deploy51 PASS/1 skipped, runner5/5, AppRouter10/10, skan sekretów i diff-check PASS.
Nie scalono innych gałęzi i nie wykonano zmian runtime. Dokumentacja ORCH-05 będzie osobnym
commitem po integracji, bez źródeł LAB lub konfiguracji produkcyjnej.

**Stan końcowy:** oba PR-y zintegrowane; zgoda na G1/G2/G4 i security sign-off nie zostały udzielone.
Main zintegrowanego kodu: `d4dc2047e690d7f8c6099a7d2c4dd0f37f53608d`; późniejszy commit zawierający
ten wpis zmienia wyłącznie pięć plików dokumentacji. Główny worktree właściciela pozostawiono bez zmian.

Kryteria ORCH-05: review R2 i CAP-07E, PR/CI dokładnych HEAD, brak konfliktów, ocena G1/LAB,
merge wyłącznie dwóch zielonych PR-ów, regresja wspólnego main, brak LIVE, owner-led LAB i decyzje A/B/C
zostały wykonane w zakresie opisanym w tym raporcie. **Nie oznacza to zaliczenia oczekujących bramek HANA/security.**

## Problems Encountered / Decisions Made

- Nieaktualny stan wejściowy LAB i main skorygowano dowodem Git/API, bez zmiany LAB.
- Fałszywy PASS HTML200 naprawiono z regresją przed integracją; nie zmieniono E-2/E-3.
- Pierwszy start testu Node w nowym worktree wymagał npm ci (brak yaml); po instalacji testy PASS.
- Brak LIVE jest świadomą granicą zlecenia, nie zaliczeniem przyszłych testów.

## Next Steps

1. Właściciel: decyzja A i osobne zlecenie patcha katalogu; potem ewentualna zgoda G1.
2. Po G1 MATCH: decyzja B o konkretnym pakiecie/quota/G2; osobno check i G4 DML.
3. Decyzja C: konta/fixtures/Destination/principal, E-2/E-3 i security acceptance.

## Źródła kontraktów

- [CF CLI8 push](https://cli.cloudfoundry.org/en-US/v8/push.html) i [CF tasks](https://docs.cloudfoundry.org/devguide/using-tasks.html).
- [CAPI3.235.0 Create a task](https://v3-apidocs.cloudfoundry.org/version/3.235.0/index.html#create-a-task).
- [JUnit6.0.3 Launcher API](https://docs.junit.org/6.0.3/advanced-topics/launcher-api.html),
  [Boot Executable Jar](https://docs.spring.io/spring-boot/specification/executable-jar/launching.html),
  [SAP SapMachine](https://github.com/SAP-docs/btp-cloud-platform/blob/main/docs/30-development/sapmachine-785d6b3.md).
- [SAP CAP Java Security](https://cap.cloud.sap/docs/java/security).
- [Oficjalny HDI deployer — delta i undeploy allowlist](https://www.npmjs.com/package/@sap/hdi-deploy?activeTab=readme).
- Przypięty AppRouter23.4.0: package-lock i lokalnie zainstalowane źródła oauth2-strategy.js/cookie-utils.js/csp-utils.js.
