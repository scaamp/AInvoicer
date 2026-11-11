---
title: "CAP-HANA-01 — pierwsze HDI i kontrolowany deployment CAP"
date: 2026-11-11
type: guide
status: approval-gate-no-go
tags: [ainvoicer, cap-hana-01, hana, hdi, cloud-foundry, deployment, recovery]
---

# CAP-HANA-01 — runbook do osobnego zatwierdzenia

**NO-GO dla LIVE.** Przygotowanie offline nie upoważnia do provisioningu, deploymentu, DDL/DML,
restartu, zmian IAM/Destination/routingu ani włączenia KSeF. Wszystkie mutujące komendy poniżej
są procedurą przyszłą. Nie wykonano ich w tej sesji.

[Wariant HANA i guardy](HANA_CLOUD_CAP_DEPLOYMENT.md) ·
[Dziennik i checksumy tego wydania](../journal/2026-11-11-cap-hana-01-first-persistence.md) ·
[Security acceptance](CAP_07D_SECURITY_ACCEPTANCE.md) ·
[ADR-0007](../decisions/ADR-0007-cap-persistence-hana-cloud.md).

## Stan początkowy i bramki

Świeży read-only CF inventory 11 listopada: EU20 API `https://api.cf.eu20-001.hana.ondemand.com`,
org `National Center for Cryptography_dev-v2xzij9s`, space `dev`. Istnieją `ainvoicer-auth`
(`xsuaa/application`), `ainvoicer-destination` (`destination/lite`) i nieprzypięta
`ainvoicer-hana-dev` (`hana-cloud/hana-free`). Oferta `hana` jest niewidoczna, `ainvoicer-db` nie istnieje.
CAP i AppRouter STARTED 1/1, tylko dwa bindingi identity/Destination, droplety z 9 listopada bez zmiany
checksum względem CAP-07D. To potwierdza brak przejścia CAP na HDI, a nie bieżący SQL health HANA.

Historyczny inventory efektywnych env potwierdził H2 identity-smoke. W tej sesji nie pobierano
`cf env`, endpointu `/env`, pełnego `VCAP_SERVICES`, service keys ani parametrów brokera z credentialami.
Aktualnych nadpisań env nie potwierdzono niezależnie; administrator sprawdza wyłącznie bezpieczne
właściwości w Cockpit. `create succeeded` usługi HANA to wynik provisioningu z 08:57 UTC, nie ping SQL.
Ostatni SQL wersji i parametry bazy to dowód właściciela opisany w [as-built](HANA_CLOUD_FREE_TIER_SETUP.md).

| Klasa | Warunek przed wykonaniem |
| --- | --- |
| COST | **COST VERIFICATION BLOCKED**: potwierdzić dla tego konta dostępność i rozliczenie `hana/hdi-shared`; dokumentacja ogólna nie wystarcza. Bez płatnego planu HANA/Object Store. |
| INFRA | Administrator potwierdza Database ID w HANA Cloud Central, bieżący status RUNNING i mapowanie do właściwego org/space; zatwierdza dedykowany pusty HDI. |
| SECURITY | LIVE A/B, DCL i request-correlated principal H01 nadal PENDING. Właściciel musi rozstrzygnąć tę bramkę przed ekspozycją nowego runtime; workflow KSeF pozostaje OFF. |
| RUNTIME | Zatwierdzić staging/stack i sprawdzić efektywne bezpieczne flagi, brak nadpisań Spring oraz brak credentiali transportów; wybrać środowisko wykonania IT. |
| DATABASE | SQLState timeout/deadlock, lock/CAS/lease oraz restart/reconnect niepotwierdzone na HANA. Są przedmiotem przyszłych testów. |
| PERMISSION | Oddzielne zgody na HDI, konkretny MTAR/SHA z DDL/bindingami/przerwą, syntetyczne DML, restart aplikacji i ewentualny stop/start bazy oraz rollback. |

Brak planu w CF nie jest dowodem braku planu w global account. Nie zmieniać entitlementów automatycznie.
Nie podawać DBADMIN do CAP. Database ID nie jest automatycznie GUID-em service instance CF;
nawet podobny host dashboardu nie zastępuje odczytu Database ID z Cloud Central.

## 1. Odtworzenie artefaktów offline

Z czystego checkoutu zatwierdzonego commita, JDK25/Maven3.9.16/Node24:

```powershell
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
Zatwierdzany zestaw z czystego `368314cdcec0d054f3e6a54b0b814418f94313f7`:

| Plik | Bajty | SHA-256 |
| --- | --- | --- |
| `ainvoicer_0.7.3.mtar` | 67 563 132 | `c7108b044eaa2d13e937818eeccb5aa17fc053f59843a3a70779ea55ff5cf5a0` |
| `ainvoicer_0.7.2.mtar` | 67 551 806 | `4a1164b7f947d456883fef4f8396f37b059b783314f9e72d1c5fca06b4beb501` |

Po odbudowie timestampy ZIP/JAR mogą zmienić SHA: nowe bajty wymagają ponownego zatwierdzenia.
Archiwa i logi builda są ignorowane w Git; administrator zachowuje zatwierdzone pliki i evidence
w kontrolowanym rejestrze wydania przed deploymentem.

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

Nie zmieniać stacka niejawnie. Descriptor HANA nie przypina stacka: istniejące aplikacje mogą zachować
fs4, nowy deployer otrzymać domyślny fs5. Plan tych trzech modułów musi być zatwierdzony przed deployem.
Jeśli potrzebna jest zmiana deskryptora/extension, przygotować ją offline, wykonać review i ponownie
zatwierdzić zestaw plików oraz checksumy. Nie traktować lokalnego Microsoft JDK jako dowodu SapMachine.

## 3. Utworzenie HDI — TYLKO po osobnej zgodzie i koszcie

Najpierw administrator udostępnia zatwierdzony bezpłatny `hana/hdi-shared`, jeśli nie jest dostępny.
Nie wybierać płatnego `hana-cloud/hana` jako obejścia. Następnie ręcznie potwierdzić Database ID,
mapowanie i brak `ainvoicer-db`. Jeżeli kontener już istnieje, **nie tworzyć ponownie**: ustalić właściciela,
database_id, plan i brak cudzych danych. Sama nazwa nie jest dowodem poprawnego celu.

```powershell
# MUTACJA — wyłącznie po osobnej zgodzie na ten kontener i zatwierdzeniu kosztu.
$hanaDatabaseId = Read-Host 'Potwierdzony Database ID z HANA Cloud Central'
if ($hanaDatabaseId -notmatch '^[0-9a-fA-F-]{36}$') { throw 'Database ID wymaga weryfikacji administratora' }
$hdiParamsPath = Join-Path ([IO.Path]::GetTempPath()) ('ainv-hdi-' + [guid]::NewGuid() + '.json')
try {
    [IO.File]::WriteAllText($hdiParamsPath, (@{ database_id = $hanaDatabaseId } | ConvertTo-Json),
        [Text.UTF8Encoding]::new($false))
    & $cfCliPath create-service hana hdi-shared ainvoicer-db -c $hdiParamsPath
    if ($LASTEXITCODE -ne 0) { throw 'Provisioning przerwany; sprawdź stan przed ponowieniem' }
} finally { Remove-Item -LiteralPath $hdiParamsPath -ErrorAction SilentlyContinue }
& $cfCliPath service ainvoicer-db
```

Wynik musi być `create succeeded`, offering `hana`, plan `hdi-shared`, właściwy Database ID potwierdzony
bez ujawniania credentiali. Provisioning jest asynchroniczny: czekać na wynik z rozsądnym odstępem,
nie ponawiać create przy timeout/niejednoznacznym wyniku. Nie tworzyć service key. Bindingi tworzy
zatwierdzony MTA w następnym kroku; nie wywoływać dodatkowego ręcznego bind/restage.

## 4. Deployment — druga osobna bramka

Przed zgodą: zatwierdzone oba MTAR/SHA i extension `deploy/btp-dev-eu20.mtaext`, puste HDI, bieżąca HANA,
plan stacków, security acceptance/ryzyka oraz akceptacja utraty H2 i przerwy w sesjach. Zabezpieczyć
rollback artefaktu smoke przed rozpoczęciem. Poprzedni droplet nie jest kopią MTAR.

```powershell
# MUTACJA — dopiero po zatwierdzeniu dokładnych bajtów MTAR i extension.
npm run verify:hana --prefix deploy
& $cfCliPath deploy deploy/build/ainvoicer_0.7.3.mtar -e deploy/btp-dev-eu20.mtaext
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
& $cfCliPath deploy deploy/build/ainvoicer_0.7.2.mtar -e deploy/btp-dev-eu20.mtaext
& $cfCliPath apps
& $cfCliPath mta ainvoicer
```

Nie przekazywać `--delete-services`. Zachować HDI i jego dane. MTA/HDI mogą pozostać w historii
deploymentu; po rollback sprawdzić bindingi i brak działającego deployera, bez automatycznego kasowania.
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
