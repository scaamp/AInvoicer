---
title: "CAP-HANA-02R2 — izolowany runner testu HANA"
date: 2026-11-12
type: guide
status: offline-verified-live-not-executed
tags: [ainvoicer, cap-java, hana, hdi, cloud-foundry, integration-test, runner]
---

# Izolowany runner `ainvoicer-hana-it`

**Wyłącznie implementacja i testy offline. Nie wykonano G0–G4, żadnego wywołania CF, połączenia HANA,
DML/DDL, deploymentu, bindingu ani restartu.** Gotowość techniczna offline nie oznacza zgody na G2.
Katalog G1 nadal PENDING; pierwszy DML pozostaje NO-GO do odrębnego zatwierdzenia.

**Nowy blocker G2, ujawniony podczas pracy:** `main=2fe9402` zawiera
[relację właściciela o wdrożeniu LAB do tego samego HDI](https://github.com/scaamp/AInvoicer/blob/2fe9402e10e3109a17f5fd9259cbea4a4b8e3d4a/docs/journal/2026-11-12-blob-lab-01-owner-live-hdi-blob.md).
Tabela `CUSTOMER_CAP_JAVA_KSEFDOCUMENTS` jest według tego wpisu już w `ainvoicer-db`, choć jej CDS
pozostaje poza Git. To dowód OWNER-REPORTED, nie własny odczyt tej sesji. Niezmieniony G1 sprawdza
dokładny katalog14 tabel i może zwrócić MISMATCH z powodu dodatkowej tabeli LAB. **G2 pozostaje NO-GO**
do świadomego uzgodnienia zatwierdzonego katalogu/źródeł i uzyskania MATCH. Nie dopisano whitelisty,
nie skopiowano `documents.cds`, nie usuwano tabeli i nie zmieniano skryptu G1 w ramach R2.

[Plan akceptacji CAP-HANA-02](CAP_HANA_02_PERSISTENCE_ACCEPTANCE.md) ·
[Runbook pierwszego deploymentu](CAP_HANA_01_FIRST_DEPLOYMENT_RUNBOOK.md) ·
[Granice BLOB-LAB-01](BLOB_LAB_01_OWNER_LED_HDI.md) ·
[Dziennik R2](../journal/2026-11-12-cap-hana-02r2-isolated-runner.md).

## Decyzja i architektura

R2 jest tymczasową aplikacją CF tylko do tasków. Nie zmienia dropletu `ainvoicer-srv`, jego MTA,
konfiguracji ani uprawnień. Używa istniejących `ainvoicer-db` (hana/hdi-shared) i `ainvoicer-auth`
(xsuaa/application). Nowa aplikacja i dwa bindingi wymagają osobnej zgody. Nie tworzy usług ani service keys.

```text
zatwierdzony checkout → Maven clean verify → skrypt Node → runner.jar + manifest.yml + evidence.json
                                                         ↓ po G1 i zgodzie G2
CF ainvoicer-hana-it (0 web instances, bez trasy, SapMachine 25)
  task: bramka R2 → JUnit Platform → istniejący HanaPersistenceIT (profil cf, WebEnvironment.NONE)
                                    ├─ CAP/CQN → binding HDI → istniejąca HANA
                                    └─ binding XSUAA wymagany przez CloudIdentityGuard
```

| Rozwiązanie | Ocena |
| --- | --- |
| R2: osobny prebuilt JAR + JUnit Launcher API | Wybrane: uruchamia rzeczywisty harness CQN bez Mavena w CF i bez zmiany CAP. |
| JUnit Console standalone + pełny osobny classpath | Dodatkowy uber-JAR dublowałby JUnit. Mały launcher korzysta z wersji już rozwiązanej przez Maven (obecnie 6.0.3). |
| Testy w droplecie CAP (R1) | Wymaga zmiany produkcyjnego pakietu/stagingu; odrzucone. |
| Task SQL deployera (R3) | Dobry dla G1; nie bada CQN, mapowania błędów CAP, repozytoriów ani fencing. |
| Laptop/SSH/DB Explorer | Brak zatwierdzonego kanału bez credentiali/zmiany sieci/kluczy/restartu. |

To implementacja wcześniej wybranego R2, bez nowej decyzji o właścicielu danych lub storage. Nie dotyka LAB.
`docs/STATUS.md`, `docs/ROADMAP.md` i `docs/README.md` pozostają bez zmian zgodnie ze zleceniem,
co jest świadomym odstępstwem od ogólnego workflow dokumentacji w AGENTS.md.

## Pakiet i integralność

Artefakty ignorowane przez Git znajdują się w `deploy/build/hana-it/`:

| Plik/katalog | Zawartość |
| --- | --- |
| `runner.jar` | Spring Boot executable JAR z nowym `Start-Class=ainvoicer.hanait.Main`; zachowane klasy CAP i skompilowany model `edmx/csn.json`. |
| `BOOT-INF/classes/customer/cap_java/hana/HanaPersistenceIT.class` | Dokładnie jeden oryginalny test integracyjny; bez zmian jego semantyki/źródła. |
| `BOOT-INF/classes/ainvoicer/hanait/` | Bramka/launcher oraz jeden kontrakt offline. Poza pakietem component scan aplikacji. |
| `BOOT-INF/lib/` | Zależności aplikacji i rozwiązany przez Maven test-scope oraz zgodny `junit-platform-launcher`; bez instalacji Mavena w CF. |
| `manifest.yml` | Osobny manifest CF: `no-route: true`, `instances: 0`, 1G pamięci/dysku, fs4, SAP Java Jakarta buildpack, SapMachine25. Bez consentu/fazy i automatycznych bindingów. |
| `evidence.json` | Commit, SHA-256 wejść, oryginalnego JAR, manifestu, runnera i wszystkich wpisów archiwum. |
| `deps/`, `classes/` | Wyłącznie lokalne pliki robocze. **Nie wysyłać całego katalogu build**; manifest wskazuje sam `runner.jar`. |

Nowe nested JAR mają metodę ZIP STORED wymaganą przez Boot; aktualizowany jest `classpath.idx`.
Metadane ZIP mają Unix creator3 oraz 0644/0755 (regresja incydentu CAP-HANA-01S).
Nie kopiujemy test resources ani innych klas IT, lokalnych bindingów ani plików LAB.
Usuwamy lokalny `schema-h2.sql` z runnera; runtime i oryginalny harness wymagają produktu JDBC `HDB`.

`verify` przelicza hashe wejść, wszystkich wpisów i JAR, sprawdza manifest, klasy bytecode25,
Boot JarLauncher, JUnit engine/launcher, SpringExtension i sterownik HANA. Wymaga zaufanego checkoutu
i zachowanego evidence. Hash jest kontrolą integralności, **nie podpisem cyfrowym**; nie chroni przed
podmianą artefaktu razem z dowodami przez uprawnionego operatora. Powtarzalny proces builda nie
oznacza identycznych bajtów ZIP przy różnych timestampach. Po zmianie wejść wymagany rebuild.

## Build i testy offline

Wymagania: JDK25 (`java`, `javac`), Maven ≥3.9.14 (zweryfikowano3.9.16), Node22/24, npm,
zainstalowany lokalnie oficjalny SDK MF3.0.29 według istniejącego workflow. Rozwiązywanie zależności
może korzystać z repozytoriów Maven/npm; „offline” oznacza tutaj **bez połączenia do BTP/HANA/KSeF**.

```powershell
# W osobnym worktree; nigdy w katalogu LAB właściciela.
npm ci --prefix deploy
node deploy/scripts/hana-it-runner.mjs build
node deploy/scripts/hana-it-runner.mjs verify
node deploy/scripts/hana-it-runner.mjs test
npm test --prefix deploy
node deploy/scripts/check-secrets.mjs
git diff --check
```

`build` wykonuje zwykłe `mvn -B -f cap-java/pom.xml clean verify -Dcds.cds.skipVersionCheck=true`,
bez profilu `hana-integration`, następnie kopiuje zależności testowe i kompiluje launcher `javac --release25`.
Test o sufiksie IT nie jest wykonywany przez tę regresję Maven.

`java -jar deploy/build/hana-it/runner.jar --self-test` wykonuje wyłącznie kontrakt JUnit offline.
`--preflight PHASE` wymaga całego środowiska i waliduje bramki, ale **nie tworzy Spring/DataSource**.
Testy Node używają syntetycznego VCAP tylko dla preflight i ujemnych startów; nie jest to dowód LIVE.
Sprawdzają również uruchomienie rozpakowanego JAR przez Boot JarLauncher, tak jak w układzie CF.

## Bramka uruchomienia i logi

Każdy `--run PHASE` przed startem JUnit/Spring sprawdza:

- Dokładną fazę, zgodny `AINV_HANA_TEST_PHASE`, jawny istniejący consent, UUID i poprawny NIP.
- Jedyne HDI R2: `ainvoicer-db`, dokładnie jeden binding hana/hdi-shared i jeden xsuaa/application
  `ainvoicer-auth`, brak dodatkowych bindingów, puste/niekompletne credentiale i DBADMIN odrzucane.
- `VCAP_APPLICATION`: nazwa `ainvoicer-hana-it`, EU20-001 API, właściwa org, space `dev`, puste URI.
- Profil `cf`, identity-smoke OFF, hana-readiness ON, KSeF/INBOUND/SAP WRITE/sweeper OFF,
  storage `off`, SQL init `never`. Brak zewnętrznych Spring config/DataSource, CDS overrides,
  `SPRING_APPLICATION_JSON` i alternatywnych źródeł bindingów.
- Zakaz LIVE w CI/GitHub Actions. Wewnętrzne testy preflight sanitizują środowisko wyłącznie offline.

Launcher utrwala `web-application-type=none`, outbox persistent/inMemory OFF oraz scheduling OFF;
istniejący IT dodatkowo narzuca flagi bezpieczeństwa i guard HANA. Sweeper pozostaje OFF.
Oryginalne tabele outbox są testowane bez dostawy. Nie uruchamia się HTTP ani głównego `Application.main`.

`VCAP_SERVICES` przekazuje wyłącznie platforma. Nie ustawiać go ręcznie, nie wykonywać `cf env`,
nie eksportować credentiali. Brak service keys potwierdza się osobno w G2 przez **metadane CAPI**:
VCAP sam nie dowodzi pochodzenia credentiali. Nie twierdzimy, że R2 może uniemożliwić operatorowi CF
z uprawnieniami SpaceDeveloper zmianę konfiguracji lub ominięcie jego launch command.

Stdout/stderr JUnit/Spring są wyciszone. Dozwolone są jedynie znaczniki harnessu:
`HANA_TEST_PHASE_PASS=...`, `HANA_UNIQUE_SQLSTATE=...;VENDOR_CODE=...`,
`HANA_CAS_CHANGED=0|1;LEASE_CLAIMED=true|false` oraz stały wynik/bramka R2.
Nie wypisujemy wyjątków, credentiali, hosta, NIP, UUID, payloadu ani surowych komunikatów JDBC.
Ta polityka ogranicza diagnostykę: FAIL wymaga analizy offline, nigdy włączenia trace z bindingami.
Logi platformy/buildpacka są poza filtrem launchera; nie przekazywać sekretów przez JVM options.

| Kod procesu | Znaczenie |
| --- | --- |
| 0 | Preflight/self-test PASS albo wszystkie znalezione testy JUnit zakończone sukcesem. Preflight nie jest PASS HANA. |
| 1 | FAIL JUnit, błąd wykonania, brak testów, skip lub abort. |
| 2 | Niepoprawne wywołanie/consent/target/flag/binding; przed startem Springa. |

Bez argumentów runner odmawia (2). Nie ma fazy `all`, domyślnego DML ani automatycznej zgody.
Generator poleceń nie wykonuje CF; błędne dane zwracają2 bez wypisywania wartości.

| Faza | Rodzaj | Wymagana istniejąca zgoda |
| --- | --- | --- |
| `check` | READ ONLY | `READ_ONLY_CHECK_APPROVED_HDI` lub zgoda zapisowa |
| `recover` | READ ONLY | jw. |
| `verify-cleanup` | READ ONLY | jw. |
| `seed` | DML | `WRITE_SYNTHETIC_DATA_TO_APPROVED_HDI` |
| `contend` | DML | jw. |
| `cleanup` | DML | jw. |

## Procedura G0–G4 — wyłącznie do zatwierdzenia przez właściciela

Każdy etap kończy się przeglądem dowodów, bez automatycznego przejścia dalej.
Poniższe komendy **nie zostały wykonane**. Nie uruchamiać dokumentu jako jednego skryptu.
Przed etapami CF wyłączyć tracing (`CF_TRACE`), transkrypcję terminala oraz dumpowanie środowiska.
Zachować to samo zatwierdzone UUID/NIP przez cały cykl; nigdy ponownie seed po niejednoznacznym wyniku.

```powershell
$cf = Join-Path $env:USERPROFILE 'tools/cf8/x/cf.exe'
$scratch = Join-Path $env:TEMP 'cap-hana-02r2'
New-Item -ItemType Directory -Force $scratch | Out-Null
# $cf ma wskazywać istniejący CLI8.19.0. Nie wykonujemy login/target ani zmian BTP automatycznie.
node deploy/scripts/hana-it-runner.mjs commands | Set-Content -Encoding ascii "$scratch\g2.ps1"
node deploy/scripts/hana-it-runner.mjs cleanup-commands | Set-Content -Encoding ascii "$scratch\remove.ps1"
node deploy/scripts/hana-it-runner.mjs target-guard | Set-Content -Encoding ascii "$scratch\target-guard.ps1"
# Przejrzeć oba pliki. Generator wymaga poprawnej integralności pakietu dla G2.
```

### G0 — autoryzowany GET CAP

W istniejącej sesji przeglądarki użytkownika A z właściwą rolą AppRouter uruchomić w DevTools Console:

```javascript
fetch('/odata/v4/KsefService/KsefOperations?$top=1', {
  credentials: 'same-origin', headers: { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
}).then(async response => {
  const body = response.ok ? await response.json() : null;
  console.log('G0', response.status, 'rows', Array.isArray(body?.value) ? body.value.length : 'INVALID');
});
```

Uruchomić tylko na zatwierdzonym origin istniejącego AppRoutera, nie na stronie logowania.
Wynik: HTTP200 i `rows0` dla świeżego zakresu. 200 HTML/logowanie, 401/403 ani sam anonymous GET
nie zaliczają G0. Nie kopiować cookies/tokenów. Dowód właściciela oznaczyć OWNER-REPORTED.
Zmiany CF: brak. Restart/downtime: brak. Zasoby: obecny CAP/HANA. Zatrzymanie: przerwać odczyt;
brak rollbacku danych. Timeout/błąd bazy = STOP, nie restart CAP.

### G1 — jednorazowy task katalogowy (osobna zgoda)

Użyć istniejącego, wcześniej zreviewowanego programu CAP-HANA-02, bez jego modyfikacji:

```powershell
node deploy/scripts/hdi-catalog-readonly.cjs --print-runtime-source
node deploy/scripts/hdi-catalog-readonly.cjs --task-json | Set-Content -Encoding ascii "$scratch\catalog.json"
# Sprawdzić stan HANA, brak tasków i zatwierdzić G1.
. "$scratch\target-guard.ps1"
& $cf target
$deployerGuid = & $cf app ainvoicer-db-deployer --guid
& $cf curl -X POST "/v3/apps/$deployerGuid/tasks" -d "@$scratch\catalog.json"
& $cf tasks ainvoicer-db-deployer
& $cf logs ainvoicer-db-deployer --recent | Select-String 'HDI_CATALOG'
```

Oczekiwane: task SUCCEEDED/kod0 oraz `verdict=MATCH tables=14/14 views=4/4 uniqueIndexes=6/6 primaryKeys=14/14
missing=[] unexpected=[]`. Kod2/MISMATCH/błąd = STOP bez DDL. Zmiana CF: jeden task deployera;
256MB RAM/512MB disk, istniejący binding. Restart/downtime CAP: brak. Limit skryptu60s.
Zatrzymanie zatwierdzonego taska: `& $cf terminate-task ainvoicer-db-deployer <sequence-id-z-cf-tasks>`.
Rollback: brak zapisów biznesowych; usunąć lokalny JSON po zebraniu wyniku, nie usuwać deployera/usługi.

### G2 — utworzenie, bindingi i staging R2 (osobna zgoda, dopiero po MATCH)

Właściciel zatwierdza dokładny commit/pakiet/SHA, nazwę aplikacji, dwa bindingi, stack/buildpack i quota.
Odczyty `cf target`, `cf apps`, `cf service ainvoicer-db`, `cf service ainvoicer-auth` oraz aktualny stan HANA
muszą potwierdzić wskazane środowisko. Generator `commands` produkuje dokładne polecenia:

```powershell
node deploy/scripts/hana-it-runner.mjs verify
Get-FileHash deploy/build/hana-it/runner.jar -Algorithm SHA256
Get-Content "$scratch\g2.ps1"              # review, nie wykonuje komend CF
# Dopiero po odrębnej zgodzie G2 i MATCH G1:
. "$scratch\g2.ps1"
```

Skrypt sprawdza lokalny target CF, integralność pakietu, managed service plans i brak service keys
(metadane CAPI bez endpointów credentiali), odmawia nadpisania już istniejącej aplikacji, następnie:

```powershell
# Wyciąg z generatora — nie wykonywać drugi raz po g2.ps1.
& $cf push ainvoicer-hana-it -f deploy/build/hana-it/manifest.yml --no-route --no-start
& $cf bind-service ainvoicer-hana-it ainvoicer-db
& $cf bind-service ainvoicer-hana-it ainvoicer-auth
& $cf push ainvoicer-hana-it -f deploy/build/hana-it/manifest.yml --task --no-route
```

**`--no-start` nie stage'uje aplikacji.** Drugi push `--task` tworzy droplet bez uruchomienia
web/testu; pominięcie go uniemożliwiłoby run-task. Nie łączyć `--no-start` z ostatnim push.
Manifest nie ustawia consentu ani fazy. Nie dodawać automatycznego `start`, `restart` ani default DML.
Skrypt kończy natychmiast po błędzie CLI; częściowy G2 wymaga inspekcji, nie powtórzenia w pętli.

Wynik: staged droplet, brak URI, zero web instancji, SapMachine25, dwa bindingi wyłącznie do istniejących
usług, brak wykonanych tasków. Potwierdzić `cf app ainvoicer-hana-it`, `cf tasks ainvoicer-hana-it`,
`cf service ainvoicer-db`, `cf service ainvoicer-auth`; nie odczytywać `cf env`.
Metadane tras: `cf curl /v3/apps/<runner-guid>/routes` → puste resources. Powtórzyć kontrolę kluczy
z generatora. Restart/downtime CAP: brak. Zasoby: nowa app/droplet + storage pakietu oraz staging RAM/disk;
nowy runtime user HDI i credential XSUAA w bindingach. Brak nowej HANA/HDI/XSUAA service instance.
Zatrzymanie: przerwać dalsze kroki; po ocenie stanu osobno zatwierdzić usunięcie runnera poniżej.

### G3 — `check` (osobna zgoda read-only)

W zarządzanej sesji właściciel ustawia **sam** `AINV_HANA_TEST_CONSENT` na istniejący token read-only.
Generator nie robi tego automatycznie. Ustawić również scope wcześniej zatwierdzonego runu:

```powershell
$env:AINV_HANA_TEST_RUN_ID = '<zatwierdzony UUID>'
$env:AINV_HANA_TEST_NIP = '<zatwierdzony syntetyczny NIP>'
$env:AINV_HANA_TEST_HDI_NAME = 'ainvoicer-db'
# AINV_HANA_TEST_CONSENT ustawiany ręcznie po zgodzie; nie kopiujemy bindingów.
node deploy/scripts/hana-it-runner.mjs task check | Set-Content -Encoding ascii "$scratch\check.json"
if ($LASTEXITCODE -ne 0) { throw 'Task generation blocked' }
# Zweryfikować puste trasy/bindingi i HANA RUNNING.
. "$scratch\target-guard.ps1"
$runnerGuid = & $cf app ainvoicer-hana-it --guid
& $cf curl -X POST "/v3/apps/$runnerGuid/tasks" -d "@$scratch\check.json"
& $cf tasks ainvoicer-hana-it
& $cf logs ainvoicer-hana-it --recent | Select-String 'R2_|HANA_'
```

JSON zawiera **dokładny launch command jednej fazy** i 1024MB RAM/disk. Używa JRE wystage'owanego przez
SAP buildpack: `.java-buildpack/sap_machine_jre/bin/java -Xmx512m -XX:MaxMetaspaceSize=256m -cp .
org.springframework.boot.loader.launch.JarLauncher --run check`, z jawnie zweryfikowanym scope/consentem.
Nie potrzebuje systemowego Mavena/JUnit ani Node w droplecie. Nie używać `cf set-env ...CONSENT`:
zgoda trafia do pojedynczego command taska i nie pozostaje domyślna dla przyszłych faz.

Wynik: SUCCEEDED, `HANA_TEST_PHASE_PASS=check`, `R2_PHASE_RESULT=check;EXIT=0`.
Preflight PASS nie zastępuje tych dowodów. Brak wyniku lub FAIL = STOP, bez automatycznego retry.
Zmiana CF: jeden task, zero zmian danych. Restart/downtime CAP: brak. Zasoby:1GB/task i połączenia HANA.
Zatrzymanie: `& $cf terminate-task ainvoicer-hana-it <sequence-id>`; task znika po zakończeniu,
aplikacja/bindingi pozostają do osobno zatwierdzonego cleanup.

### G4 — pierwszy syntetyczny DML (nowa, osobna zgoda)

Wymagania: G0/G1/G2/G3 potwierdzone, ten sam scope, HANA RUNNING, wyłączone efekty zewnętrzne,
zarezerwowany nieużywany scope, plan recovery i czas na cleanup. Review dokładnego zakresu zapisu
w [tabeli seed CAP-HANA-02](CAP_HANA_02_PERSISTENCE_ACCEPTANCE.md#6-faza-2--plan-testów-każda-bramka--osobna-zgoda).
Właściciel sam zastępuje consent tokenem zapisowym. Ta instrukcja nie jest zgodą.

```powershell
node deploy/scripts/hana-it-runner.mjs task seed | Set-Content -Encoding ascii "$scratch\seed.json"
if ($LASTEXITCODE -ne 0) { throw 'DML task generation blocked' }
# Kontrola bieżącego runtime i osobna zgoda na seed:
. "$scratch\target-guard.ps1"
& $cf curl -X POST "/v3/apps/$runnerGuid/tasks" -d "@$scratch\seed.json"
& $cf tasks ainvoicer-hana-it
& $cf logs ainvoicer-hana-it --recent | Select-String 'R2_|HANA_'
```

Wynik: SUCCEEDED + `HANA_TEST_PHASE_PASS=seed`, `R2_PHASE_RESULT=seed;EXIT=0` i rzeczywisty SQLState unique.
Zmiany: syntetyczne rekordy runu w5 tabelach, odrzucany duplikat i rollback/CAS zgodnie z oryginalnym harness.
Restart/downtime CAP: brak. Zasoby: jeden task1GB, transakcje/locki w istniejącym HDI.
Przerwanie lub niejednoznaczny commit: **nie ponawiać seed**. Zatrzymać task po decyzji właściciela,
zachować UUID, uruchomić osobno zatwierdzony `recover` i przeanalizować. Brak automatycznego rollbacku DML.

## Dalsze fazy, recovery i bezpieczne usunięcie

Generator `task PHASE` tworzy analogiczny JSON dla jednej fazy. `recover` i `verify-cleanup` przyjmują
zgodę read-only, `contend` i `cleanup` wyłącznie zgodę DML. Nie ma łańcucha uruchamianego automatycznie.
Każdy POST tworzy nową JVM/task; dwa równoległe `contend` to osobne taski i **2GB** łącznej pamięci.
Oryginalny plan G5/G7/G8/G9 opisuje agregację CAS/lease i wymagane dowody. Opcjonalny restart CAP/G6
jest poza tym zleceniem i nie jest potrzebny do dowodu trwałości w nowej JVM.

Po DML: `recover` → osobno zatwierdzony `cleanup` → `verify-cleanup` w nowej JVM → ponowne G1.
Cleanup kasuje tylko klucze własnego runu po kontroli markerów/lease/checkpointu; odmowa oznacza STOP,
nigdy szeroki DELETE. Częściowy seed może uniemożliwić automatyczny cleanup — ręczna analiza zakresu,
bez zmiany UUID i bez eskalacji DBADMIN. Usunięcie aplikacji **nie usuwa rekordów HANA**.

```powershell
node deploy/scripts/hana-it-runner.mjs cleanup-commands | Set-Content -Encoding ascii "$scratch\remove.ps1"
Get-Content "$scratch\remove.ps1"
# Dopiero po osobnej zgodzie, bez RUNNING/CANCELING tasków i po cleanup danych (jeśli był DML):
. "$scratch\remove.ps1"
```

Skrypt ponownie sprawdza target, wyświetla taski i wymaga ręcznego potwierdzenia `REMOVE_RUNNER`.
Wykonuje wyłącznie `unbind-service ainvoicer-hana-it ainvoicer-auth`, `unbind-service ... ainvoicer-db`,
`delete ainvoicer-hana-it` (zwykłe potwierdzenie CLI). Nie usuwa usług/tras/kluczy/HDI ani bindingów CAP.
Przed usunięciem zachować oczyszczone wyniki i sekwencje tasków; po potwierdzić brak aplikacji i brak
jej bindingów oraz działanie CAP/AppRouter bez zmian. Lokalne JSON/pakiety usunąć w kontrolowanym zakresie.

## Quota, koszt i otwarte ryzyka

- Zero web instancji ogranicza pamięć po stagingu; app/droplet/package nadal zajmują zasoby i quota app.
  Jeden task wymaga1GB RAM/1GB disk, dwa `contend`2GB, staging ma osobną rezerwację platformy.
  Rzeczywistą wolną quota i indywidualne koszty PAYG potwierdza właściciel przed G2; nie gwarantujemy0PLN.
- SapMachine25/Jakarta/fs4 i ścieżka JRE wymagają stagingu LIVE. Fs4 jest historycznie używany i deprecated;
  nie migrujemy stosu CAP. Brak wsparcia wymaga osobnej decyzji o stacku runnera, bez obejść guardów.
- Działanie JAR i exploded JAR sprawdzono lokalnie; nie jest to test SAP buildpacka, autoryzacji XSUAA,
  sieci CF, bindingu, HANA precision/SQLState/CAS/locków ani katalogu. Wszystkie te wyniki LIVE PENDING.
- Read-only oznacza semantykę faz harnessu, nie użytkownika DB z uprawnieniem wyłącznie SELECT.
  Runtime HDI ma DML na potrzeby późniejszego G4; bramki i zatwierdzony kod ograniczają wykonanie.
- Brak publicznej trasy nie usuwa możliwości outbound; chronią flagi i guardy oryginalnego IT.
  Nie dodajemy reguł ASG ani zmian IAM. Przyszłe nowe schedulery wymagają ponownego review izolacji.
- Free Tier może zatrzymać bazę. Nie restartować CAP/HANA automatycznie; nowe zadanie przy niedostępnej
  bazie ma FAIL. Po DML brak backupu nie jest zastępowany runnerem; recovery wymaga zachowania scope.

## Dowody offline i źródła

Wyniki sesji i dokładne polecenia są w [dzienniku R2](../journal/2026-11-12-cap-hana-02r2-isolated-runner.md).
CI uruchamia build/test runnera po istniejących buildach; nie otrzymuje VCAP ani credentiali BTP.
Żaden PASS lokalny/H2 nie jest deklaracją PASS rzeczywistej HANA.

- [SAP CAP Java Persistence / HANA](https://cap.cloud.sap/docs/java/cqn-services/persistence-services#sap-hana): wykorzystanie managed bindingu.
- [JUnit6.0.3 Launcher API](https://docs.junit.org/6.0.3/advanced-topics/launcher-api.html): selekcja klasy i wynik sesji.
- [CF tasks](https://docs.cloudfoundry.org/devguide/using-tasks.html): osobne kontenery i dziedziczenie bindingów.
- [CLI8 push](https://cli.cloudfoundry.org/en-US/v8/push.html): różnica `--no-start`/`--task`.
- [VCAP_APPLICATION](https://docs.cloudfoundry.org/devguide/deploy-apps/environment-variable.html#VCAP-APPLICATION): pola targetu i URI.
