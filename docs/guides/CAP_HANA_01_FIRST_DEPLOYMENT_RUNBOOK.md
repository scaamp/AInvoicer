---
title: "CAP-HANA-01 — pierwsze HDI i kontrolowany deployment CAP"
date: 2026-11-11
type: guide
status: hana-deployed-cap-running-sql-dml-pending
tags: [ainvoicer, cap-hana-01, hana, hdi, cloud-foundry, deployment, recovery]
---

# CAP-HANA-01 — runbook do osobnego zatwierdzenia

## CAP-HANA-01S — wynik LIVE: deployment 82250a52 FINISHED (11.11, 21:40 CET)

Zgoda właściciela: „zgadzam się – abort i deploy” (bramka poniżej, bez czekania na CI PR).
Preflight 20:28 UTC PASS (target, HANA `serviceStopped=false`, SHA `54a89984…`/`f11b3196…`);
abort `40b8e964…` → ABORTED, brak aktywnych operacji; ponowny HANA/SHA PASS.

| Kontrola (VERIFIED LIVE, odczyt) | Wynik |
| --- | --- |
| Operacja `82250a52-de0e-11f1-ab29-eeee0a9dd8f2` (`--retries 0`, `-f` tylko bez promptu) | **FINISHED**; MTA `ainvoicer` 0.7.3 |
| HDI | task `deploy` #2 SUCCEEDED 20:29:50 UTC (payload identyczny z #1); deployer STOPPED |
| CAP `ainvoicer-srv` | STARTED 1/1, droplet `730e32a9` (20:31:22 UTC), SAP Java bp 2.76.0, fs4 |
| AppRouter | STARTED 1/1, droplet `da231d8b` (20:33:13 UTC), Node bp 1.9.6, fs4 |
| Bindingi | `ainvoicer-db` → deployer + **srv**; auth/destination → srv + approuter |
| Start CAP (logi, filtrowane) | profil `cf`; DataSource `ds-ainvoicer-db`; `HikariPool-1` — połączenie `com.sap.db.jdbc.HanaConnection`; XSUAA; `Started Application` 5,7 s; guardy fail-closed nie zatrzymały startu |
| Flagi env CAP | HANA_READINESS=true, IDENTITY_SMOKE=false, KSeF/INBOUND/SAP WRITE/sweeper=false, storage off, outbox OFF, SQL init never |
| Anonimowe GET CAP | `/`, `$metadata`, `KsefOperations` → **401**, puste body (wcześniejszy FAIL indeksu 200 usunięty) |
| Anonimowe GET AppRouter | bez XHR: 200 = strona przekierowania logowania XSUAA (~710 B, bez treści UI/OData); z `X-Requested-With` → 401 |

Obserwacja: WARN `Failed to register handler method` dla handlerów outbox (CAP, KSeF worker, SAP writeback)
— oczekiwane przy wyłączonych outboxach; do weryfikacji przy ich włączaniu.

**Nadal PENDING:** katalogowy SQL obiektów HDI (brak autoryzowanego narzędzia — dowodem jest HDI make
i działające połączenie JDBC, nie zapytanie), syntetyczne DML/SELECT, restart i trwałość po restarcie,
testy CAS/lock/konkurencji, LIVE A/B/DCL/principal H01 i końcowy security sign-off. Merge poprawki
generatora do `main` po PR i CI. Nie wykonano DML, restartu, rollbacku ani nowej mutacji po deploymencie.

## CAP-HANA-01S — incydent 40b8e964 i nowa bramka (11.11, 21:40 CET)

**Stan LIVE (VERIFIED, read-only): CAP `ainvoicer-srv` STOPPED — przerwa API.** Po zgodzie właściciela
wykonano abort `d110c171…` (ABORTED) i deployment `196b9b8c…` (`--retries 0`; `-f` tylko dla pominięcia
promptu, brak konfliktujących operacji). Operacja `40b8e964-de0c-11f1-ab29-eeee0a9dd8f2`: staging HDI
deployera OK, task `deploy` **SUCCEEDED** (make OK, kontener `36F0829E…`, 20:14:08 UTC);
`ainvoicer-srv` zatrzymany, staging **BuildpackCompileFailed** (SAP Java bp 2.76.0: *None of the
available runtimes … supports the application*). Operacja aktywna **ERROR**. AppRouter niezaktualizowany
(stary droplet, bez działającego backendu). CAP ma nowe env i bindingi auth/destination/HDI, bieżący
droplet nadal z 09.11 (`944f83c7…`). Nie wykonano retry, restartu, rollbacku ani DML.

**Przyczyna:** `ainvoicer-srv/data.zip` był ZIP-em z jednym plikiem `cap-java-exec.jar`; CF rozpakowuje
payload jako katalog aplikacji, więc brakowało `META-INF/MANIFEST.MF` w katalogu głównym. Działający pakiet
CF z 09.11 ma rozpakowany JAR (`META-INF/`, `BOOT-INF/`, `org/`). Ta sama wada dotyczy rollbacku 0.7.2
`00e31c97…` — **wycofany z rekomendacji**. Weryfikator wymuszał błędny układ, a `test:cf-runtime`
uruchamiał JAR z `target/`, więc defekt nie był wykrywalny offline ani w CI.

**Poprawka (branch `fix/cap-hana-01s-srv-payload-layout`, `fe0a0c1`):** payload `srv` = bajty JAR Spring
Boot (jak `mbt`); weryfikacja manifestu (`JarLauncher`, `Start-Class`), `BOOT-INF/classes`, metadanych Unix
i zakazu opakowanego `.jar/.war`; regresja odrzuca stary `196b9b8c…`. Czysty build z `fe0a0c1`:
Maven 468 (462 PASS/6 skipped), `cf-runtime` 10/10, UI5 CF 13/13, AppRouter 10/10, deploy 32 PASS/1 skipped,
`verify:release` i `verify:hana` PASS.

| Plik | Bajty | SHA-256 |
| --- | --- | --- |
| `D:\AInvoicer-cap-hana-01s\deploy\build\ainvoicer_0.7.3.mtar` | 67595231 | `54a899849073cbebc83e84c7d7780439efc3108dd7c1030750aa70afac0592c5` |
| `D:\AInvoicer-cap-hana-01s\deploy\build\ainvoicer_0.7.2.mtar` (rollback, poprawiony) | 67583902 | `5295f2571413ed8a62f880d69154e9e04ba3ec82536049760084c0a74a1882ed` |
| `D:\AInvoicer\deploy\btp-dev-eu20-hana-first.mtaext` (bez zmian, LF) | 599 | `f11b31967d12cc3f587b55a39aaf385791503b8f2b11af472f2d03bd37cfd14e` |

Porównanie zawartości z `196b9b8c…`: `mtad.yaml` identyczny; HDI 28/28 i AppRouter 74/74 plików identycznych
(ponowny HDI deploy bez zmiany schematu); CAP JAR różni się wyłącznie 15 klasami niepodłączonego
`ksef.inbound.budget` (PR #13). Payload `srv`: 621 wpisów, katalog główny `BOOT-INF`/`META-INF`/`org`.

### Dwie operacje do osobnej zgody — NIE WYKONANO

```powershell
& $cfCliPath deploy -i 40b8e964-de0c-11f1-ab29-eeee0a9dd8f2 -a abort
# Potwierdzić ABORTED, brak aktywnych operacji, target, serviceStopped=false i SHA z tabeli.
& $cfCliPath deploy D:\AInvoicer-cap-hana-01s\deploy\build\ainvoicer_0.7.3.mtar -e D:\AInvoicer\deploy\btp-dev-eu20-hana-first.mtaext --retries 0
```

Po deployu: FINISHED, nowe droplety CAP/AppRoutera, binding CAP→HDI, logi startu (JDBC HANA,
CloudIdentityGuard/CloudPersistenceGuard), anonimowe GET; katalogowy SQL PENDING bez autoryzowanego
narzędzia. DML, restart i testy konkurencji — osobna zgoda. Rollback wyłącznie poprawionym 0.7.2
`5295f257…` (`--version-rule ALL --retries 0`) po osobnej decyzji; schematu HDI nie cofa.

## CAP-HANA-01R — aktualna bramka recovery (11.11, 20:35 CET)

**GO techniczne do osobnego zatwierdzenia kontrolowanej próby DEV; NO-GO wykonawcze bez zgody.**
HANA broker zwraca teraz `serviceStopped=false` (19:31 i 19:33 UTC). Wcześniejszy odczyt
`serviceStopped=true` z raportu Claude Code nie opisuje już bieżącego stanu. Agent nie uruchamiał bazy.
Nie wykonano SQL health check ani nowego stagingu; broker nie potwierdza dostępności SQL.
Dowody: [dziennik recovery](../journal/2026-11-11-cap-hana-01-first-persistence.md#cap-hana-01r--niezależna-weryfikacja-codex-2035-cet).

| Bramka | Bieżący wynik |
| --- | --- |
| Target | API `https://api.cf.eu20-001.hana.ondemand.com`; org `National Center for Cryptography_dev-v2xzij9s`; space `dev`. |
| Poprzednia operacja | `d110c171-ddf8-11f1-8764-eeee0a818911` nadal aktywna **ERROR**; wymaga zatwierdzonego abort, bez retry. Historyczna przyczyna: nieprzenośne prawa ZIP i błąd stagingu `package.json: permission denied`. |
| Runtime | CAP i AppRouter STARTED 1/1, droplety z 09.11, bindingi tylko auth+destination; CAP nadal identity-smoke/H2. Deployer STOPPED, brak dropletu, tylko binding HDI. |
| HANA/HDI | `ainvoicer-hana-dev`: hana-cloud/hana-free; `ainvoicer-db`: hana/hdi-shared, create succeeded, plan `free=true`. HDI `database_id=2161fe76-faf9-40e7-8693-72728fa6d49e`, zgodny z istniejącą HANA. |
| RAM/stack | FREE_QUOTA 4096 MB, użyte 1408 MB, wolne 2688 MB; deployer 256 MB. Wszystkie moduły jawnie fs4 (DEPRECATED); fs5 ACTIVE, migracja poza zakresem. Node bp1.9.6/SAP Java bp2.76.0 dostępne. |
| Artefakty/testy | Wszystkie SHA poniżej zgodne; deskryptory, evidence i sourceHash PASS; generator 31 PASS/1 Go skipped lokalnie; gotowy JAR 10/10 PASS. CI main319adbf COMPLETED/SUCCESS. |
| Bezpieczeństwo | Nowy MTAR: SEND/INBOUND/SAP WRITE/sweeper OFF, storage off, oba outbox OFF, identity-smoke false, SQL init never. Guard blokuje H2 zamiast HANA. |
| Otwarte ograniczenia | Bieżący indeks CAP anonimowo HTTP200 ujawnia kontrakt (**FAIL**); metadata CAP i indeks AppRoutera HTTP401. Poprawka indeksu w nowym JAR przechodzi lokalny test; potwierdzenie LIVE po deployu. A/B/DCL/principal H01 oraz SQL/lock/CAS/restart nadal PENDING. |
| Koszty | Wyłącznie 3 istniejące usługi: auth/destination/db; bez provisioningu i nowych płatnych usług. Nie jest to gwarancja kosztu całego konta PAYG. |

### Dokładny zestaw — ponownie zmierzone SHA-256, bez odbudowy

| Plik | Bajty | SHA-256 |
| --- | --- | --- |
| `D:\AInvoicer-cap-hana-01-build\deploy\build\ainvoicer_0.7.3.mtar` | 67562976 | `196b9b8cc85843f08b200aba9455e6f1c8b725a9ad3b765a3f883ba68b621570` |
| `D:\AInvoicer-cap-hana-01-build\deploy\build\ainvoicer_0.7.2.mtar` (rollback) | 67551647 | `00e31c977eb0d29cb86a4f6b7ecbdf115cfaf1a489f247955545b317f847f962` |
| `D:\AInvoicer\deploy\btp-dev-eu20-hana-first.mtaext` | 599 | `f11b31967d12cc3f587b55a39aaf385791503b8f2b11af472f2d03bd37cfd14e` |

Używać dokładnie wskazanych ścieżek. Kopia extension w nowym worktree ma inne bajty CRLF;
nie zastępuje zatwierdzanego pliku LF. Źródła runtime `main319adbf` są zgodne z buildem4630f89;
nie włączono niezacommitowanego `cap-java/db/documents.cds` z głównego checkoutu.

### Dwie konkretne operacje do zgody — NIE WYKONANO

```powershell
Remove-Item Env:DEBUG,Env:CF_TRACE -ErrorAction SilentlyContinue
$cfCliPath = Join-Path $env:USERPROFILE 'tools/cf8/x/cf.exe'
# A: tylko po osobnej zgodzie na zakończenie starej operacji
& $cfCliPath deploy -i d110c171-ddf8-11f1-8764-eeee0a818911 -a abort
# Potwierdzić ABORTED, brak aktywnej blokady, właściwy target,
# serviceStopped=false i ponownie wszystkie SHA z tabeli.
# B: tylko po osobnej zgodzie na powyższe bajty, DDL/bindingi i przerwę aplikacji
& $cfCliPath deploy D:\AInvoicer-cap-hana-01-build\deploy\build\ainvoicer_0.7.3.mtar -e D:\AInvoicer\deploy\btp-dev-eu20-hana-first.mtaext --retries 0
```

Ryzyko: przerwa CAP/AppRoutera przy zwykłym deploymencie; DDL 14 tabel/6 indeksów/4 widoków
w istniejącym HDI. Pakiet nie zawiera undeploy, CSV ani hdbtabledata, ale pustego schematu nie
potwierdzono SQL. Abort nie cofa aplikacji/schematu. Przy pierwszym błędzie zatrzymać pracę;
bez kolejnego deployu, retry, undeploy, usuwania usług ani samodzielnego rollbacku.

Po zatwierdzonym deployu: sprawdzić FINISHED i HDI make SUCCESS, nowe droplety, binding CAP→HDI,
HANA JDBC oraz przejście CloudIdentityGuard/CloudPersistenceGuard. Odczytać dozwolone flagi
`environment_variables` i powtórzyć anonimowe GET, w tym indeks CAP. Faktyczne istnienie obiektów
potwierdzić katalogowym SQL przez dostępne autoryzowane narzędzie; logi HDI nie zastępują SQL.
Brak takiego narzędzia zgłosić jako PENDING. Syntetyczne DML, restart i testy konkurencji wymagają
osobnej zgody. Nie tworzyć service keys ani odczytywać credentiali/VCAP dla tej weryfikacji.

Rollback aplikacji wymaga osobnej decyzji: zweryfikowany MTAR0.7.2 z tabeli oraz
`D:\AInvoicer-cap-hana-01-build\deploy\btp-dev-eu20.mtaext`
(SHA `4c6afb70c98f3f4bbe851865bdd8eb0c4f2ebd5a28b37d1b31419634fd3405b7`),
`cf deploy <rollback-mtar> -e <rollback-extension> --version-rule ALL --retries 0`.
Zachować HDI i dane, sprawdzić pozostałe bindingi/env oraz stan deployera.
Powrót do efemerycznego H2 nie odtwarza danych HANA ani starej bazy H2; schematu nie cofamy.

## Aktualny wynik zatwierdzonej próby — staging ERROR

Zgoda właściciela na abort30401273… i deployment0.7.3/SHA6a92fb78… została wykonana.
Stara operacja ABORTED. Nowa `d110c171-ddf8-11f1-8764-eeee0a818911` przyjęła typy deploymentu,
utworzyła `ainvoicer-db-deployer` i jego binding HDI, ale staging Node buildpack1.9.6/fs4 zakończył
się `Unable to load package.json: permission denied`, także przy3 automatycznych retry narzędzia.
Nie uruchomiono HDI deployera, CAP/AppRouter nie zostały zaktualizowane. HANA0.7.3 NOT DEPLOYED.

**NO-GO dla archiwów SHA6a92fb78… i66014221… .** Są zachowane w build/rejected-d110c171.
Windows adm-zip zapisał creator10 i katalogi0666; Go archive/zip dla creator10 zwraca tryb000.
Generator normalizuje teraz wszystkie payloady CF do Unix creator3, plików0644 i katalogów0755;
walidator odrzuca nieprzenośne metadane. Regresja Go1.26.10 w CI czyta prawdziwe ZIP-y,
lokalnie bez Go jest jawnie skipped. Odbudowa/nowe SHA/verify oraz CI1ea445a PASS. Nie wykonywać retry d110c171.
Przed nowym deployem potrzebna osobna zgoda na abort tej operacji i nowe bajty MTAR.

Podczas poleceń MultiApps usuwać z procesu **DEBUG i CF_TRACE**. `DEBUG=1` uruchamia HTTP trace
pluginu niezależnie od CF_TRACE=false (potwierdzone cf mta-ops --help). Nie publikować surowych nagłówków.
Odczyty/logi filtrować przed zapisem/wyświetleniem. Ustawienia te dotyczą procesu CLI, nie env aplikacji.

### Aktualny pakiet po normalizacji ZIP — do nowej zgody

**VERIFIED OFFLINE:** czysty sourceCommit4630f89, oba buildy/verify PASS; Maven423 PASS/6 skipped,
CF JAR10/10, UI5 CF13/13, AppRouter10/10. Deploy tests31 PASS/1 Go skipped lokalnie;
Go1.26.10 niezależnie wykonany w CI. Python zipfile odczytał rzeczywisty MTAR Windows:
wszystkie payloady Unix creator3, pliki0644, katalogi0755.
[CI43075836717](https://github.com/scaamp/AInvoicer/actions/runs/43075836717) dla1ea445a:
COMPLETED/SUCCESS, wszystkie kroki SUCCESS, także Go reader, oba MTAR, Azurite, QUnit/OPA i Inbox HTTP.
Późniejszy commit zapisuje wyłącznie ten wynik dokumentacyjny, bez zmiany wejść runtime/CI.

| Plik do zatwierdzenia | Bajty | SHA-256 |
| --- | --- | --- |
| `D:\AInvoicer-cap-hana-01-build\deploy\build\ainvoicer_0.7.3.mtar` | 67562976 | `196b9b8cc85843f08b200aba9455e6f1c8b725a9ad3b765a3f883ba68b621570` |
| `D:\AInvoicer-cap-hana-01-build\deploy\build\ainvoicer_0.7.2.mtar` | 67551647 | `00e31c977eb0d29cb86a4f6b7ecbdf115cfaf1a489f247955545b317f847f962` |
| `D:\AInvoicer\deploy\btp-dev-eu20-hana-first.mtaext` | 599 | `f11b31967d12cc3f587b55a39aaf385791503b8f2b11af472f2d03bd37cfd14e` |

Dotychczasowa zgoda została wykorzystana na poprzednie bajty. **NO-GO wykonawcze do nowej zgody**
na poniższe operacje i hashe. W propozycji jawnie wyłączamy automatyczne retry (`--retries 0`,
obsługa potwierdzona cf deploy --help); nie uruchomiono tych poleceń:

```powershell
Remove-Item Env:DEBUG,Env:CF_TRACE -ErrorAction SilentlyContinue
$cfCliPath = Join-Path $env:USERPROFILE 'tools/cf8/x/cf.exe'
& $cfCliPath deploy -i d110c171-ddf8-11f1-8764-eeee0a818911 -a abort
# Po potwierdzeniu ABORTED/braku blokady, targetu, HANA i dokładnych SHA powyżej:
& $cfCliPath deploy D:\AInvoicer-cap-hana-01-build\deploy\build\ainvoicer_0.7.3.mtar -e D:\AInvoicer\deploy\btp-dev-eu20-hana-first.mtaext --retries 0
```

Zakres nadal bez zmian:3 existing-service, fs4 jawnie, bez H2 fallbacku; wszystkie transporty,
storage i oba outbox OFF. Post-deploy runtime/JDBC/obiekty/autoryzowany READ nadal PENDING;
DML/restart/cleanup wymagają osobnej zgody i runnera. Nie restartować CAP ani usuwać usług jako obejścia.

### Historia naprawy typów i poprzednie bajty — nie używać do następnej próby

**AKTUALIZACJA: NO-GO dla poprzednich MTAR.** Pierwsza próba właściciela została odrzucona przy
scalaniu deskryptorów (operacja `30401273-ddf4-11f1-ab29-eeee0a9dd8f2`, ERROR).
`mbt assemble` nie tłumaczy typów projektu: w `mtad.yaml` wymagane są mapowania
`java→java.tomcat`, `approuter.nodejs→javascript.nodejs`, `hdb→com.sap.xs.hdi`, zgodne z MBT1.2.49.
Generator i regresja poprawione; odbudowa/nowe SHA zweryfikowane offline. Poprzednie bajty0.7.3
i rollback0.7.2 nie są zestawem do ponowienia. Nie wykonywać `cf deploy -a retry`:
użyłby niepoprawnych deskryptorów przypisanych do starej operacji.
Status operacji wskazuje utrzymaną blokadę; przed nowym deploymentem wymagane zatwierdzone zakończenie
starej operacji przez abort. Agent nie wykonał abort/retry/deploy. Aktualne dowody są w dzienniku.
Ocena GO i checksumy niżej zachowują historię poprzedniego preflightu; nie stanowią bieżącej rekomendacji.

### Poprawiony zestaw do nowej zgody — VERIFIED OFFLINE, NOT DEPLOYED

Oba MTAR odbudowano w tym samym worktree z czystego `e2df55aed2ebdd973fb4a27541401a30a43ee73e`.
Maven423 PASS/6 skipped, executable CF JAR10/10, UI5 CF13/13, AppRouter10/10, deploy29/29;
build smoke/HANA, verify:release/verify:hana i odczyt rzeczywistych typów wewnątrz ZIP PASS.
Test porównuje typy z prawdziwym `mbt mtad-gen -p cf` wersji1.2.49, także z extension fs4.
Stare archiwa faktycznie odrzucane przez poprawiony walidator. Nie testowano nowego stagingu CF.

| Poprawiony plik | Bajty | SHA-256 |
| --- | --- | --- |
| `D:\AInvoicer-cap-hana-01-build\deploy\build\ainvoicer_0.7.3.mtar` | 67562971 | `6a92fb787ccc77e1abc0b870a30ccbcba420c1d663446b628d7e3ab2ec8d935b` |
| `D:\AInvoicer-cap-hana-01-build\deploy\build\ainvoicer_0.7.2.mtar` | 67551642 | `66014221b2219cfed68aa89502750ef67b9b65ebfa4d100fd5cb1299abcd3a69` |
| `D:\AInvoicer\deploy\btp-dev-eu20-hana-first.mtaext` | 599 | `f11b31967d12cc3f587b55a39aaf385791503b8f2b11af472f2d03bd37cfd14e` |

Extension i zakres pozostają jak zatwierdzany wcześniej: jawny fs4, te same hosty/usługi,
wszystkie transporty/storage/outbox OFF. Typy w MTAR: CAP java.tomcat, AppRouter javascript.nodejs,
HDI com.sap.xs.hdi. 14 tabel/6 indeksów/4 widoki wygenerowane, wdrożenie nadal PENDING.
Nowe SHA wymagają nowej zgody; dotychczasowa próba właściciela jej nie zastępuje.

**NO-GO wykonawcze do osobnej zgody na poniższe dwie operacje.** Nie uruchomiono ich:

```powershell
# Przed operacją właściciel odnawia sesję, jeśli potrzebne; trace wyłączony, bez publikacji logów HTTP.
$env:CF_TRACE = 'false'
$cfCliPath = Join-Path $env:USERPROFILE 'tools/cf8/x/cf.exe'
& $cfCliPath deploy -i 30401273-ddf4-11f1-ab29-eeee0a9dd8f2 -a abort
# Najpierw potwierdzić zakończenie starej operacji/zwolnienie blokady, target i wszystkie SHA z tabeli.
& $cfCliPath deploy D:\AInvoicer-cap-hana-01-build\deploy\build\ainvoicer_0.7.3.mtar -e D:\AInvoicer\deploy\btp-dev-eu20-hana-first.mtaext
```

Abort nie jest rollbackiem aplikacji/schematu. Jeśli operacja jest już zakończona przez właściciela,
nie ponawiać abort w ciemno; odczytać stan. Nie uruchamiać retry starego MTAR ani undeploy/delete-services.
Podczas diagnostyki wykryto trace CLI ujawniający token; nie zapisano wartości w repo i nie powtarzamy jej.
Właściciel otrzymał zalecenie odnowienia sesji. Odczyty wykonywać z CF_TRACE=false i bez raw HTTP/logów.

### Historia poprzedniego preflightu — stare checksumy wycofane

**GO do zatwierdzenia kontrolowanego pierwszego deploymentu DEV; NOT DEPLOYED.**
To ocena przygotowania konkretnej operacji, nie zgoda wykonawcza ani końcowy security sign-off.
Właściciel musi osobno zatwierdzić polecenie w §4, DDL/bindingi i przerwę CAP/AppRoutera.
LIVE DML, restart testowy, rollback i KSeF nie są objęte tą zgodą. Brak zgody = nie uruchamiać deployu.

[Wariant HANA i guardy](HANA_CLOUD_CAP_DEPLOYMENT.md) ·
[Dziennik i checksumy tego wydania](../journal/2026-11-11-cap-hana-01-first-persistence.md) ·
[Security acceptance](CAP_07D_SECURITY_ACCEPTANCE.md) ·
[ADR-0007](../decisions/ADR-0007-cap-persistence-hana-cloud.md).

## Stan po ręcznym provisioningu HDI i bramki

Świeży read-only CF inventory 11 listopada: EU20 API `https://api.cf.eu20-001.hana.ondemand.com`,
org `National Center for Cryptography_dev-v2xzij9s`, space `dev`. Istnieją `ainvoicer-auth`
(`xsuaa/application`), `ainvoicer-destination` (`destination/lite`), nieprzypięta
`ainvoicer-hana-dev` (`hana-cloud/hana-free`) i nowy `ainvoicer-db` (`hana/hdi-shared`).
**VERIFIED LIVE:** HDI create succeeded 16:49:24Z, 0 credential bindings (aplikacje i service keys),
plan free=true; jawny parametr brokera database_id zgodny z bazą wskazaną przez właściciela.
**OWNER-REPORTED:** w tej sesji właściciel potwierdził RUNNING i zgodny Database ID w Cloud Central.
Broker HANA zwraca serviceStopped=false; nie jest to test SQL.
CAP i AppRouter STARTED 1/1, tylko dwa bindingi identity/Destination, droplety z 9 listopada bez zmiany
checksum względem CAP-07D. To potwierdza brak przejścia CAP na HDI, a nie bieżący SQL health HANA.

**VERIFIED LIVE:** osobny CAPI GET `environment_variables` potwierdza profil cf, identity-smoke=true,
SEND/WRITE/sweeper=false, bez obecnych kluczy nadpisania JAVA_OPTS/JDK_JAVA_OPTIONS/JAVA_TOOL_OPTIONS,
SPRING_DATASOURCE_*, SPRING_CONFIG_*, CDS_SECURITY_* i SPRING_APPLICATION_JSON.
To flagi konfiguracyjne, nie pomiar nowego runtime. Nie pobierano `cf env`, systemowego `/env`,
pełnego VCAP_SERVICES, credentiali bindingu ani service keys. Wybrane niesekretne parametry brokera
odczytano z `/parameters`, bez publikacji surowej odpowiedzi.
[Historia HDI, mapowanie i koszty](../journal/2026-11-11-hdi-shared-provisioning.md).

| Klasa | Warunek przed wykonaniem |
| --- | --- |
| COST | VERIFIED LIVE plan free=true i quota FREE_QUOTA4096 MB; bez nowych usług/entitlementów. Costs and Usage/stawka kontraktowa PAYG PENDING; brak gwarancji kosztów całego konta. |
| INFRA | HDI/database_id/org/space VERIFIED LIVE; Cloud Central RUNNING/zgodny ID OWNER-REPORTED. Nie wykonywano katalogowego SQL: pusty schemat nie jest niezależnie potwierdzony. |
| SECURITY | LIVE A/B, DCL i request-correlated principal H01 PENDING. Dotychczasowy indeks CAP publiczny: FAIL; poprawka zawarta w nowym JAR. Zgoda dotyczy wyłącznie kontrolowanego DEV z tymi jawnymi ograniczeniami; końcowa akceptacja nadal wstrzymana. |
| RUNTIME | Propozycja: wszystkie moduły jawnie fs4, bez migracji istniejących aplikacji. Nowy staging/JDBC/guardy i efektywne flagi sprawdzić po deployu. Runner IT nadal niedostępny. |
| DATABASE | SQLState timeout/deadlock, lock/CAS/lease oraz restart/reconnect niepotwierdzone na HANA. Są przedmiotem przyszłych testów. |
| PERMISSION | HDI utworzony przez właściciela. PENDING zgoda na konkretny MTAR/SHA/extension z DDL/bindingami/przerwą; osobno syntetyczne DML, restart, ewentualny stop/start bazy i rollback. |

Brak planu w CF nie jest dowodem braku planu w global account. Nie zmieniać entitlementów automatycznie.
Nie podawać DBADMIN do CAP. Database ID nie jest automatycznie GUID-em service instance CF;
nawet podobny host dashboardu nie zastępuje odczytu Database ID z Cloud Central.

## 1. Odtworzenie artefaktów offline

Z czystego checkoutu zatwierdzonego commita, JDK25/Maven3.9.16/Node24:

```powershell
# Zweryfikowane wydanie tej sesji powstaje w odizolowanym worktree; główny target ma blokadę JAR.
Set-Location D:\AInvoicer-cap-hana-01-build
npm ci --prefix deploy
npm test --prefix deploy
npm run build:btp:smoke --prefix deploy
npm run verify:release --prefix deploy
npm run build:btp:hana --prefix deploy
npm run verify:hana --prefix deploy
Get-FileHash deploy/build/ainvoicer_0.7.2.mtar -Algorithm SHA256
Get-FileHash deploy/build/ainvoicer_0.7.3.mtar -Algorithm SHA256
```

Wydanie: **`deploy/build/ainvoicer_0.7.3.mtar`**, rollback: **`ainvoicer_0.7.2.mtar`**.
Dokładne SHA i sourceCommit są w dzienniku oraz `hana-release-evidence.json`/`release-evidence.json`.
Zatwierdzany zestaw z czystego `220a512874b1479aa016b30a820735003231502c`,
`D:\AInvoicer-cap-hana-01-build\deploy\build`:

| Plik | Bajty | SHA-256 |
| --- | --- | --- |
| `ainvoicer_0.7.3.mtar` | 67 562 982 | `be7bdbe6757f80e5498cbce033e560bb49d8c4dcd552bb7c181f8218c5bf2b81` |
| `ainvoicer_0.7.2.mtar` | 67 551 656 | `aec601f9283bc92e6151562ea72c76e910eb9e00c813360a40b910f1988c8902` |

Po odbudowie timestampy ZIP/JAR mogą zmienić SHA: nowe bajty wymagają ponownego zatwierdzenia.
Archiwa i logi builda są ignorowane w Git; administrator zachowuje zatwierdzone pliki i evidence
w kontrolowanym rejestrze wydania przed deploymentem.

Wszystkie dalsze polecenia względne wykonywać z tego samego worktree wydania. Nie przenosić samych
manifestów do checkoutu o innych bajtach wejść: LF/CRLF zmienia sourceFilesSha256 i wymaga własnego
builda. Odrzucona kopia z INT-03 w głównym checkoutcie została odłożona pod
`deploy/build/int-03-transfer-rejected/`; nie jest zestawem do zatwierdzenia deploymentu.

Generator, nie pełny `mta.yaml`, jest wejściem deploymentu. Wariant ma trzy existing-service,
HDI deployer bez publicznej trasy, brak CSV/hdbtabledata/undeploy listy, kolejność
**HDI deployer → CAP → AppRouter**. Nie tworzy HANA, XSUAA, Destination ani Object Store.
Brak auto-undeploy nie gwarantuje bezpieczeństwa dowolnej migracji istniejącego schematu:
pierwszy deploy tylko do dedykowanego pustego HDI, po review wygenerowanego modelu.

## 2. Read-only preflight bez credentiali

```powershell
$cfCliPath = Join-Path $env:USERPROFILE 'tools/cf8/x/cf.exe'
& $cfCliPath version
& $cfCliPath target
& $cfCliPath services
& $cfCliPath marketplace -e hana
& $cfCliPath marketplace -e hana-cloud
& $cfCliPath service ainvoicer-hana-dev
& $cfCliPath apps
& $cfCliPath stacks
& $cfCliPath buildpacks
& $cfCliPath plugins
```

Nie archiwizować surowego outputu zawierającego login użytkownika; raportować tylko metadane docelowego
org/space/usług/aplikacji. Nie uruchamiać ogólnego kolektora `readiness:cf` z tego runbooka: obecna
implementacja odczytuje `/env` wraz z bindingami przed redakcją, co wykracza poza zakres tego zlecenia.
Bindingi sprawdzać metadanymi CAPI `/v3/service_credential_bindings?app_guids=<APP_GUID>` bez `/details`.
Nie pobierać service keys ani wykonywać `cf env`.

Obecne droplety: `cflinuxfs4` (DEPRECATED), Java buildpack `2.76.0`, Node buildpack `1.9.6`.
Platforma oferuje `cflinuxfs5` ACTIVE; Node1.9.6 jest dostępny dla fs4/fs5, Java Jakarta2.76 ma
stack=null (brak deklarowanego ograniczenia, nie udany staging). Manifest upstream Node1.9.6 zawiera
Node24.22.1 dla fs4/fs5; nadal wymagany pomiar rzeczywistego stagingu SAP.
CAP wymaga SapMachine25.+; AppRouter dopuszcza22/24, deployer wymaga24, HDI deploy5.7.0 z locka.
Domyślny buildpack Node22 nie spełnia deployera: staging musi uszanować `engines.node=^24.0.0`.

Nie zmieniać stacka niejawnie. Sam descriptor HANA nie przypina stacka. Propozycja osobnego extension
z §4 jawnie wybiera fs4 dla trzech modułów i usuwa zależność od domyślnego stacka nowej aplikacji.
Oryginalny extension bez pinów nie zastępuje nowego w zatwierdzanej operacji HANA.
Jeśli potrzebna jest zmiana deskryptora/extension, przygotować ją offline, wykonać review i ponownie
zatwierdzić zestaw plików oraz checksumy. Nie traktować lokalnego Microsoft JDK jako dowodu SapMachine.

## 3. HDI już istnieje — nie powtarzać provisioningu

**VERIFIED LIVE:** `ainvoicer-db`, `hana/hdi-shared`, create succeeded, bez bindingów.
Pełna sekwencja Cockpit, decyzja kosztowa i rozróżnienie dowodów:
[dziennik HDI](../journal/2026-11-11-hdi-shared-provisioning.md).
Nie wykonywać create-service, zmian entitlementów, service keys ani DBADMIN.
W razie zmiany/niezgodności Database ID lub właściciela kontenera przerwać preflight.
Pierwszy HDI deployer musi raportować brak nieoczekiwanych istniejących obiektów i destrukcyjnych zmian.

## 4. Deployment — druga osobna bramka

Przed zgodą: zatwierdzone oba MTAR/SHA i nowy, odrębny extension
`D:\AInvoicer\deploy\btp-dev-eu20-hana-first.mtaext`, dedykowany HDI, bieżąca HANA,
plan stacków, jawne ryzyka security i akceptacja utraty H2/przerwy w sesjach.
Extension SHA-256: `f11b31967d12cc3f587b55a39aaf385791503b8f2b11af472f2d03bd37cfd14e`.
Zawiera tylko ten sam host prefix oraz `stack: cflinuxfs4` dla wszystkich trzech modułów.
Oryginalny `btp-dev-eu20.mtaext` i oba MTAR pozostają niezmienione; nowy extension jest osobnym
wejściem deploymentu do zatwierdzenia, spoza historycznego sourceFilesSha256 MTAR.
Nie kopiować MTAR do głównego checkoutu. Polecenie wykonuje się z worktree wydania:
`D:\AInvoicer-cap-hana-01-build`. Zabezpieczyć rollback smoke przed rozpoczęciem;
poprzedni droplet nie jest kopią MTAR.

Plan: CAP SapMachine25.+/SAP Java buildpack2.76, AppRouter Node22 lub24/buildpack1.9.6,
HDI deployer Node24/buildpack1.9.6 — wszystko fs4. fs4 jest DEPRECATED, jawnie zachowany
na pierwszy deploy; nie oznacza akceptacji długoterminowej. Node1.9.6 upstream zawiera Node24.22.1
na fs4/fs5, ale dokładne wersje stagingu SAP pozostają PENDING. AppRouter ma zakres22||24,
więc nie deklarować Node24 bez pomiaru. Migracja fs5/JDK25 wymaga osobnego stagingu/review.

Rekomendacja **GO do zatwierdzenia kontrolowanego DEV** opiera się na gotowych artefaktach i nowym
HDI; nie zamyka LIVE A/B/principal. Obecny runtime ma publiczny indeks (25/26 negatywnych prób PASS,
1 FAIL); nowy JAR zawiera poprawkę i tenant gate. Po deployu odmowy anonimowe są obowiązkową bramką.
Brak dowodu wycieku danych między tenantami w tej sesji nie zalicza testu izolacji.

```powershell
# MUTACJA — dopiero po zatwierdzeniu dokładnych bajtów MTAR i extension.
npm run verify:hana --prefix deploy
& $cfCliPath deploy deploy/build/ainvoicer_0.7.3.mtar -e D:\AInvoicer\deploy\btp-dev-eu20-hana-first.mtaext
# Odczyt diagnostyczny po zakończeniu lub błędzie:
& $cfCliPath mta-ops
& $cfCliPath mta ainvoicer
& $cfCliPath apps
& $cfCliPath app ainvoicer-srv
& $cfCliPath app ainvoicer-approuter
& $cfCliPath service ainvoicer-db
```

Administrator ogląda logi deploymentu w zabezpieczonej konsoli. `cf dmol -i <OPERATION_ID>` pobiera logi
do chronionego katalogu poza Git; przed udostępnieniem redaguje URL połączeń, credentiale i payloady.
Nie wklejać surowych `cf logs --recent` do raportu. Zachować statusy modułów, bezpieczne kody błędów
i wersje runtime. Oczekiwania: HDI zakończone SUCCESS przed startem CAP, produkt HDB, brak fallbacku H2,
CAP/AppRouter1/1, brak publicznej trasy deployera, brak startu transportów/outbox/sweepera.

Metadane bindingów: CAP ma auth+destination+db, AppRouter auth+destination, deployer tylko db.
CloudIdentityGuard wymaga jednego XSUAA; DedicatedTenantPolicy odmawia obcego verified zid;
CloudPersistenceGuard wymaga HDI/HDB i odmawia H2; CloudHanaReadinessGuard wymaga wszystkich
transportów oraz obu outbox OFF. Nie usuwać guardów w reakcji na błąd startu.
Health-check port potwierdza proces, nie persystencję. Metadata anonimowo musi być401; poprawnie
uwierzytelnione metadata/data testować istniejącą sesją A/B, bez nadawania nowych ról przez agenta.
Nie wykonywać LIVE requestSend w ramach health check.

## 5. Kontrolowane IT w sieci CF — trzecia bramka

`HanaPersistenceIT` i launcher są źródłami testowymi, **nie częścią produkcyjnego JAR/MTAR**.
`cf run-task ainvoicer-srv --command 'mvn ...'` nie jest gotową procedurą: runtime Java nie zawiera
Mavena, źródeł testowych ani gwarantowanego JDK/Node. Administrator musi wcześniej udostępnić i zatwierdzić
środowisko testowe w sieci CF z checkoutem zatwierdzonego SHA, JDK25, Maven3.9.16, Node24,
przypiętym SDK MF oraz zarządzanymi bindingami HDI i XSUAA. Nie tworzyć nowego publicznego mikroserwisu,
service key, tunelu ani reguły sieciowej jako skrótu. **RUNTIME/PERMISSION BLOCKED**, dopóki runner
nie jest dostępny i binding nie jest dostarczony przez platformę. Nie kopiować VCAP na laptop lub do repo.

Najmniejszy wariant do przygotowania, jeśli pełny runner nadal nie istnieje (**PENDING, nie jest
gotowym poleceniem**): zbudować offline oddzielny pakiet skompilowanego `HanaPersistenceIT`,
konfiguracji testowej, zależności i launchera JUnit, następnie uruchomić go przez zatwierdzony
mechanizm task/SSH w istniejącym kontenerze CAP z platformowym HDI/XSUAA. Nie wymaga Mavena
w produkcyjnym JAR, nowego endpointu ani nowej usługi. Dostarczenie pakietu i dostępność task/SSH
muszą być najpierw zweryfikowane; nie zakładać istnienia tych plików w obecnym droplecie.
Najpierw faza check (HDB/model), następnie osobno zatwierdzony seed→recover→restart CAP→recover→cleanup
z jednym UUID. Pakiet jest testowy i nie wchodzi do publicznego API ani zatwierdzanego MTAR0.7.3.
Jeżeli platforma nie pozwala bezpiecznie dostarczyć/uruchomić pakietu w obecnej aplikacji,
pozostawić test PENDING i uzgodnić pełny runner — bez service keys, otwierania sieci i improwizowanego DML.

W takim zatwierdzonym środowisku wykonać poniższe fazy kolejno, bez zapisywania sekretów w skrypcie:

```bash
# TYLKO po zgodzie na syntetyczne DML. VCAP_SERVICES pochodzi wyłącznie z zarządzanego bindingu.
export AINV_HANA_TEST_CONSENT=WRITE_SYNTHETIC_DATA_TO_APPROVED_HDI
export AINV_HANA_TEST_RUN_ID='<nowy UUID zachowany dla wszystkich faz>'
export AINV_HANA_TEST_NIP='<zatwierdzony poprawny syntetyczny NIP>'
export AINV_HANA_TEST_HDI_NAME=ainvoicer-db
AINV_HANA_TEST_PHASE=check node deploy/scripts/hana-integration.mjs
AINV_HANA_TEST_PHASE=seed node deploy/scripts/hana-integration.mjs
AINV_HANA_TEST_PHASE=recover node deploy/scripts/hana-integration.mjs
```

Każdy launcher uruchamia nową JVM, zwykły Surefire jest pomijany. `check` nie wykonuje DML, lecz
nadal wymaga zgody harnessu przed DataSource. GitHub Actions/DBADMIN są zabronione.
Nie wykonywać `seed` po błędzie bez analizy; nie zmieniać run UUID w celu ukrycia częściowego wyniku.

Faza `contend`: dwie konsole w zatwierdzonym runnerze, każda z tym samym zestawem zmiennych powyżej,
lecz innym checkoutem/targetem i takim samym SHA źródeł. Uruchomić w obu równocześnie:

```bash
AINV_HANA_TEST_PHASE=contend node deploy/scripts/hana-integration.mjs
```

Potem `recover` w nowej JVM. Nie uruchamiać dwóch Mavenów na wspólnym katalogu `target`.

| Scenariusz | Dowód / kryterium |
| --- | --- |
| JDBC/model | `check`: HDB, dokładny HDI, metadata indeksów; wygenerowane tabele/indeksy/widoki po udanym HDI. |
| INSERT/SELECT i unique | `seed`: syntetyczne operacja/dokument/outbox/checkpoint w jednym commicie; Decimal, Timestamp100ns, Integer64; duplicate → CAP409006 z zebranym SQLState/vendor code. |
| Rollback | Po wymuszonym błędzie brak testowego INSERT; przegrana rezerwacja checkpointu nie zostawia częściowego seed. |
| CAS/HWM | Stara wersja i cofnięcie HWM odrzucone. |
| Dwie JVM | Po jednym seed, dwa izolowane checkouty/targety, ten sam UUID/NIP/HDI: uruchomić równolegle fazę `contend`; suma HANA_CAS_CHANGED=1, suma LEASE_CLAIMED=true=1. Powtórzenie contend bez nowego seedu ma inne oczekiwania i nie jest nowym testem wyścigu. |
| Lock/fencing | `contend`: dwa niezależne ChangeSet, drugi lock czeka na commit pierwszego; losowy/stary token nie zatwierdzi checkpointu. |
| Nowa JVM | `recover`: dokładnie jeden własny dokument/operacja/outbox, niezmieniony msg i HWM. To trwałość rekordu outbox, nie jego delivery. |
| Restart CAP | Osobna zgoda na `cf restart ainvoicer-srv`; potem1/1, brak H2, autoryzowany odczyt własnej operacji i `recover`. Nie seedować ponownie. |
| Outage HANA | Osobna zgoda administratora na stop/start; po zaplanowanym stopie bezpieczna odmowa startup/DB operacji, po RUNNING ponowny recover i sprawdzenie pool/reconnect istniejącego CAP. Żadnego automatycznego stop/start. |
| Cleanup | Na końcu jedynie `AINV_HANA_TEST_PHASE=cleanup node deploy/scripts/hana-integration.mjs`; pod blokadami markery runu, checkpoint i właściciel lease. Obcy/zmieniony stan → odmowa całej transakcji. |

Obserwować SQLState/vendor code dla unique, timeout/deadlock i utraty połączenia. Nie mapować H2 HYT00
na HANA. Retry błędu niejednoznacznego commita wymaga odczytu własnych kluczy i rekoncyliacji;
nie ponawiać seed/INSERT w ciemno. Wariant nie uruchamia SEND/redeem/close, SAP WRITE ani delivery.
Wyścig realnego requestSend/rezerwacji i dostawa outbox do bezpiecznego consumera wymagają osobno
przygotowanego testu i wariantu; obecny harness nie dowodzi braku podwójnego wykonania całego workflow.
Po cleanup odczytać brak tylko własnych UUID oraz zachowanie rekordów kontrolnych innego scope.
Jeśli zmieniono checkpoint lub przejęto lease, nie usuwać scope ręcznym szerokim DELETE.

## 6. Nieudany deploy, rollback i nocny restart

Po błędzie HDI: wstrzymać akceptację, odczytać status operacji MTA i bezpieczne diagnostyki HDI;
ustalić, czy DDL zakończyło się częściowo i czy CAP wystartował. Nie powtarzać deploymentu bez analizy.
Nie używać undeploy, `--auto-undeploy`, `--delete-services`, DROP, TRUNCATE ani usuwania kontenera.
Nie cofamy automatycznie schematu; ponowny reviewed deploy jest osobną decyzją.

Po osobnej zgodzie na rollback aplikacji, z zachowanego i zweryfikowanego artefaktu smoke:

```powershell
npm run verify:release --prefix deploy
& $cfCliPath deploy deploy/build/ainvoicer_0.7.2.mtar -e deploy/btp-dev-eu20.mtaext --version-rule ALL
& $cfCliPath apps
& $cfCliPath mta ainvoicer
```

Nie przekazywać `--delete-services`. Zachować HDI i jego dane. MTA/HDI mogą pozostać w historii
deploymentu; po rollback sprawdzić bindingi i brak działającego deployera, bez automatycznego kasowania.
`--version-rule ALL` jawnie dopuszcza zatwierdzony downgrade0.7.3→0.7.2; opcję potwierdzono
w lokalnym `cf deploy --help` multiapps3.12.0. Nie służy omijaniu zgody ani odtwarzaniu schematu.
Smoke musi skutecznie ustawić identity-smoke=true/H2 i zachować mutacje OFF; ewentualne pozostałe
bindingi/env rozstrzyga administrator przed dodatkowym restartem/unbind. Nie obiecywać, że downgrade
MTA sam usunie wszystkie bindingi modułu. Smoke z nowym H2 nie przywraca poprzedniej pamięciowej bazy
ani nie odtwarza danych z HDI. **Rollback aplikacji nie jest rollbackiem schematu.**

Po nocnym zatrzymaniu Free Tier: administrator potwierdza bazę i wykonuje zatwierdzony Start w Cloud
Central, czeka na RUNNING, sprawdza HDI/CAP i dopiero w razie potrzeby zatwierdza restart CAP.
Nie restartować w pętli przy zatrzymanej bazie. `recover` po powrocie bez seed; porównać HWM/UUID/outbox.
Przy utracie danych lub usunięciu bazy nie ma obietnicy backupu: Free Tier i dane syntetyczne,
odtworzenie środowiska jest nową, zatwierdzaną operacją.

## Źródła i granice dowodu

- [SAP CAP Java — HANA binding i precedence DataSource](https://cap.cloud.sap/docs/java/cqn-services/persistence-services#sap-hana).
- [SAP — tworzenie schema/HDI i database_id](https://help.sap.com/docs/HANA_CLOUD/db19c7071e5f4101837e23f06e576495/2863434ddda042b8b8011a3f24856281.html).
- [Cloud Foundry Node buildpack1.9.6 — manifest](https://github.com/cloudfoundry/nodejs-buildpack/blob/v1.9.6/manifest.yml).
- [SAP — SapMachine](https://help.sap.com/docs/btp/sap-business-technology-platform/sapmachine).

Dokumentacja upstream potwierdza kontrakt i dostępne mechanizmy, nie koszt naszego konta ani udany
staging/LIVE. Zgoda na ten runbook nie jest automatyczną zgodą na wszystkie wymienione mutacje.
