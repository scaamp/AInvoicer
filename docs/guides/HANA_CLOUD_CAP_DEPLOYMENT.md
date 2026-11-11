---
title: "CAP-HANA — bezpieczne przygotowanie wdrożenia na istniejącej Free Tier"
date: 2026-11-11
type: guide
status: hdi-provisioned-deployment-approval-pending
tags: [ainvoicer, hana, hdi, deployment, security, int-03]
---

# HANA Cloud / CAP — wariant przygotowany, niewdrożony

**Aktualizacja LIVE:** po zgodzie właściciela agent zakończył starą operację i uruchomił poprawione0.7.3.
Merge przeszedł; staging deployera zakończył się ERROR (`package.json: permission denied`).
HDI ma1 app binding do STOPPED deployera bez trasy; CAP nadal nie ma HDI i działa na starym droplecie.
Naprawa metadanych ZIP creator10→Unix3/0644/0755 odbudowana i zweryfikowana offline.
Nowe hashe są na początku runbooka; CI poprawki1ea445a SUCCESS, osobna zgoda na nowy deployment PENDING.
Bez DML/restartu CAP. Aktualne dowody są na końcu dziennika; starsze stany poniżej są historią.

**NO-GO dla dotychczasowych artefaktów:** pierwsza próba została odrzucona przed stagingiem przez
niezmapowany typ `approuter.nodejs`. Naprawiono translację CF dla obu generatorów; oba MTAR
odbudowano z nowymi SHA i zweryfikowano offline. Wymagana osobna zgoda na zakończenie operacji ERROR
i ponowny deployment. Aktualne bajty/SHA i polecenia: początek runbooka CAP-HANA-01.
[Przyczyna i dowody](../journal/2026-11-11-cap-hana-01-first-persistence.md).

Aktualny zestaw po final review/merge INT-03, dokładne artefakty i wykonywalne komendy:
[runbook CAP-HANA-01](CAP_HANA_01_FIRST_DEPLOYMENT_RUNBOOK.md) ·
[dziennik CAP-HANA-01](../journal/2026-11-11-cap-hana-01-first-persistence.md).
Poniższy opis pozostaje kontraktem generatora; historyczne checksumy INT-03 nie zastępują nowego wydania.

**HANA Free Tier LIVE / HDI PROVISIONED / CAP HANA NOT CONNECTED / NOT DEPLOYED.**
Aktualizacja po ręcznej konfiguracji właściciela: CF potwierdza `ainvoicer-db/hana/hdi-shared`,
free=true, brak bindingów i jawny `database_id`. RUNNING/zgodność ID z Cloud Central potwierdził
właściciel. [Historia, źródła i ograniczenia PAYG](../journal/2026-11-11-hdi-shared-provisioning.md).
Nie tworzyć ponownie HDI. Aktualna bramka i propozycja jawnego fs4 są w runbooku CAP-HANA-01.
Build MTAR nie daje zgody na tworzenie HDI, deploy, restart, zapis do HANA/SAP ani KSeF.
[As-built](HANA_CLOUD_FREE_TIER_SETUP.md) · [Audyt](../audits/2026-11-11-int-03-security-persistence.md) ·
[Dziennik](../journal/2026-11-11-int-03-orchestration.md) · [ADR-0007](../decisions/ADR-0007-cap-persistence-hana-cloud.md).

## Historyczny stan INT-03 i bramka kosztowa

Odczyt INT-03 `cf target/services/marketplace/apps` potwierdza właściwe EU20/org/space,
jedną widoczną usługę `hana-cloud/hana-free` o nazwie `ainvoicer-hana-dev`, bez bindingów.
`cf marketplace -e hana` → **No service offerings found**; `hdi-shared` i `ainvoicer-db` nie są dostępne.
CAP/approuter pozostają STARTED 1/1 z XSUAA i Destination.
[Oczyszczony inventory](../../deploy/evidence/2026-11-11-int-03-hana-inventory.json).
To inventory usług CF, nie pełny katalog baz dostępnych brokerowi przez mapowania z innych przestrzeni.

SQL właściciela potwierdził HANA `4.00.000.00.1793723943`; INT-03 nie powtarzał SQL ani nie czytał credentiali.
16 GB RAM, 1 vCPU, 80 GB dysku, Auto-Upsize OFF, 0 replicas, allowed connections tylko CF IP są relacją
as-built właściciela, nie niezależnym SQL/Cloud Central pomiarem INT-03.

Samo istnienie HANA nie tworzy kontenera ani bindingu. Po potwierdzeniu bezpłatności `hana/hdi-shared`
i osobnej zgodzie właściciel wskazuje **Database ID z HANA Cloud Central**, tworząc zatwierdzony HDI
na istniejącej `ainvoicer-hana-dev`. Parametr brokera to `database_id`.
GUID usługi CF z `cf service --guid` **nie jest automatycznie Database ID**.
Jawne wskazanie celu rekomendujemy nawet przy jednej widocznej bazie; unikamy wyboru przez brokera przy
nieznanych mapowaniach. [Kontrakt SAP](https://help.sap.com/docs/HANA_CLOUD/db19c7071e5f4101837e23f06e576495/2863434ddda042b8b8011a3f24856281.html).
Nie zmieniamy entitlementów ani nie tworzymy teraz usług. Nie przywracać płatnego planu `hana`.

## Jeden generator wariantu, trzy istniejące zasoby

`deploy/scripts/hana-readiness.mjs` generuje wariant z `mta.yaml` i wspólnego walidowanego wariantu smoke.
Nie utrzymujemy kolejnej ręcznej kopii MTA. Źródłowy pełny `mta.yaml` pozostaje historycznym szablonem
mogącym tworzyć usługi; **nie używać go bezpośrednio dla tego środowiska**.

Nowy MTAR `ainvoicer_0.7.3.mtar` wymaga wyłącznie `org.cloudfoundry.existing-service`:

| Zasób | Wymagany stan przed przyszłym deployem |
| --- | --- |
| `ainvoicer-auth` | Istniejący dedicated XSUAA; bez aktualizacji deskryptora/scopes/Role Collections |
| `ainvoicer-destination` | Istniejący Destination lite; bez zmian Destinations |
| `ainvoicer-db` | Osobno zatwierdzony istniejący HDI `hana/hdi-shared`, z właściwym Database ID |

Nie ma resource `hana-cloud`, Object Store, tworzenia kontenera ani service keys.
Deployer uruchomi migrację **tylko przypiętego kontenera**; przed zgodą administrator weryfikuje, że
`ainvoicer-db` jest nowy/dedykowany AInvoicer i nie zawiera danych innych aplikacji.
Brak publicznej trasy deployera; `deployed-after` wymusza HDI deployer → CAP → approuter.
[Kolejność MTA](https://help.sap.com/docs/btp/sap-business-technology-platform/parallel-module-deployment).

Build offline nie potwierdza stagingu docelowego buildpacka/stacka: SapMachine25/Node24 i ewentualna
migracja cflinuxfs4→fs5 wymagają zatwierdzonej weryfikacji CF. CAP-07D potwierdził dostępność fs5,
ale nie staging tej kombinacji. Nie zmieniano stosu działających aplikacji.

## Konfiguracja i rzeczywiste guardy

| Właściwość wariantu | Wartość / skutek |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `cf`; mock OFF, index/Fiori preview OFF |
| `AINV_DEPLOYMENT_IDENTITY_SMOKE` | `false`; bez wyjątku H2 |
| `AINV_DEPLOYMENT_HANA_READINESS` | `true`; dodatkowy guard flag i braku usług outbox |
| `AINV_KSEF_ENABLED`, `AINV_KSEF_INBOUND_ENABLED` | `false`; bez SEND/synchronizacji |
| `AINV_SAP_WRITE_ENABLED`, `AINV_SAP_WRITE_SWEEPER_ENABLED` | `false`; bez WRITE i schedulera odtwarzania |
| `AINV_KSEF_STORAGE_PROVIDER` | `off`; adapter Azure bez beana/bindingu |
| `SPRING_SQL_INIT_MODE` | `never`; schemat wyłącznie przez HDI, bez uruchomienia SQL H2 |
| `SPRING_APPLICATION_JSON` | `cds.outbox.persistent.enabled=false`, `cds.outbox.inMemory.enabled=false` |
| Inbox UI CF i external services | OFF w zweryfikowanym payloadzie; odczyt API nie uruchamia INBOUND |

`CloudIdentityGuard` wymaga jednego XSUAA i niepustego spójnego tenant ID z bindingu.
`DedicatedTenantPolicy` dekoruje oficjalny decoder SAP: zweryfikowane `zid` musi odpowiadać bindingowi;
obecne `app_tid`/`zone_uuid` muszą być zgodne. Podpis, klucze, audience i ważność weryfikuje SAP.
`CloudPersistenceGuard` wymaga dokładnie jednego bindingu `hana/hdi-shared` i produktu JDBC HANA;
brak bindingu, niedostępna baza lub H2 przerywa startup. Testy executable JAR potwierdzają negatywy offline.

Wyłączone persistent outbox **nie przechodzi w pamięciowy**: oba są OFF. Zależności workerów od outbox
są lazy, startup writebacku nie budzi kolektora w readiness. Lokalny test H2 wiring potwierdza zero usług
OutboxService; nie jest to test HANA. Dane kolejki pozostają w tabeli dla przyszłych testów trwałości.
Włączenie delivery wymaga osobnego wariantu i testów; nie usuwać tej blokady przez sam env override.

## Powtarzalny build offline i dowód wydania

Z czystego checkoutu zatwierdzonego commita, JDK25/Maven3.9.16/Node22 lub24:

```powershell
npm ci --prefix deploy
npm test --prefix deploy
npm run build:btp:smoke --prefix deploy
npm run verify:release --prefix deploy
npm run build:btp:hana --prefix deploy
npm run verify:hana --prefix deploy
```

Smoke buduje Maven clean verify, executable JAR CF tests, UI5 CF i AppRouter; nie zmienia jego guardów.
Wariant HANA wymaga sprawdzonego smoke z tych samych wejść, kompiluje `cds build --for hana --production`,
pakuje wygenerowane tabele/widoki/indeksy, `.hdiconfig`, `.hdinamespace`, package/lock deployera i wykonuje
oficjalne `mbt assemble`. Nie pakuje fixture, lokalnych bindingów, zależności testowych ani auto-undeploy listy.
Build nie uruchamia `npm start` HDI deployera i nie łączy się z HANA.

Artefakty lokalne (ignorowane w Git):

- `deploy/build/ainvoicer_0.7.2.mtar`, `identity-smoke.mtad.yaml`, `release-evidence.json`;
- `deploy/build/ainvoicer_0.7.3.mtar`, `hana-readiness.mtad.yaml`, `hana-release-evidence.json`;
- `cap-java/srv/target/cap-java-exec.jar`, `cap-java/db/src/gen/` (wyłącznie build).

Evidence obejmuje commit, dirty flag, hash wejść i SHA-256 MTAR/deskryptora/JAR/approutera/HDI.
Walidator odrzuca podmianę payloadu lub konfiguracji. Checksum potwierdza integralność konkretnego builda,
nie byte-for-byte powtarzalność ZIP/JAR z różnymi timestampami. Wyniki i hashe: dziennik INT-03.

## Oddzielny harness REAL HANA — obecnie NIE URUCHAMIAĆ

`HanaPersistenceIT` jest kompilowany offline; sufiks IT nie jest wykonywany przez zwykły Surefire.
Profil `hana-integration` uruchamia wyłącznie ten test przez Failsafe. Node launcher i Java gate przed
DataSource wymagają osobnej zgody, syntetycznego scope i istniejącego bindingu HDI; odrzucają CI i DBADMIN.
Nie przenosić credentiali do GitHub Actions ani plików repo. Baza dopuszcza tylko CF IP, więc lokalny
laptop nie uzyska połączenia bez zmiany sieci; **nie otwierać sieci** na potrzeby tego harness.
Uruchomienie w zatwierdzonym środowisku z bindingiem wymaga osobnego przygotowania właściciela.

Po zatwierdzeniu przyszłego zapisu przygotować w zarządzanym środowisku:

```text
AINV_HANA_TEST_CONSENT=WRITE_SYNTHETIC_DATA_TO_APPROVED_HDI
AINV_HANA_TEST_RUN_ID=<nowy UUID, zachowany na wszystkie fazy>
AINV_HANA_TEST_NIP=<zatwierdzony syntetyczny NIP, nieużywany scope BUYER/TEST>
AINV_HANA_TEST_HDI_NAME=ainvoicer-db
AINV_HANA_TEST_PHASE=check|seed|recover|contend|cleanup
VCAP_SERVICES=<binding przekazany przez platformę; nigdy log/plik Git>
node deploy/scripts/hana-integration.mjs
```

| Faza / scenariusz | Co sprawdza |
| --- | --- |
| `check` | CAP startup/guardy, produkt HDB, binding, model i indeksy JDBC metadata; bez DML |
| `seed` | INSERT/SELECT, Decimal/Timestamp/Integer64, constraint unikalności i kod CAP409006/SQLState, rollback, CAS/HWM |
| `recover` w nowej JVM | Jedna operacja/dokument, niezmienione bajty komunikatu outbox, checkpoint i typy |
| `contend` | CAS, lease/token fencing i rzeczywiste `lock()` z dwoma niezależnymi transakcjami |
| Dwie instancje | Dwa izolowane checkouty/targety, ten sam run/scope, równoległe `contend`; suma CAS_CHANGED=1, jeden lease owner |
| Restart DB | Właściciel osobno zatwierdza stop/start; bez restartu przez harness. Podczas outage startup ma odmówić; po powrocie `recover` |
| `cleanup` | Wyłącznie klucze własnego run UUID i zarezerwowanego scope; bez drop/truncate/delete całych tabel |

Final review: seed operacji/dokumentu/outbox/checkpointu jest atomowy. Cleanup blokuje i weryfikuje
checkpoint (wersja/HWM/czas) oraz znaczniki runu w operacji, dokumencie i outbox. Istniejący lease musi
mieć znacznik tego runu zapisany przez zwycięski `contend`; obcy lease lub zmieniony checkpoint przerywa
cały cleanup. Po przerwanym seed/contend nie poprawiać znaczników ręcznie: zebrać stan i uzgodnić recovery.
Scope pozostaje zarezerwowany do końca testu; nie uruchamiać w nim innych workerów.

Nie wykonywano żadnej fazy na HANA. Dostawy outbox/workflow pozostają OFF; harness testuje trwałość
rekordu i brak duplikacji, **nie udaje E2E delivery**. Test faktycznej dostawy do lokalnego bezpiecznego
consumera po restarcie dwóch instancji oraz zebranie SQLState timeout/deadlock HANA są osobnym krokiem
CAP-HANA-01. Nie mapować H2 `HYT00` na HANA bez dowodu.

## Nocne zatrzymanie, ryzyka i rollback

Brak bazy podczas startu ma dać bezpieczny startup failure; po jej powrocie potrzebny może być
osobno zatwierdzony restart aplikacji, zależnie od stanu CF. Nie zakładamy samoczynnego uzdrowienia.
Pool/reconnect działającego CAP i zachowanie dwóch instancji po outage wymagają pomiaru LIVE.

Przed deployem zachować poprzedni smoke MTAR, jego SHA i evidence oraz aktualne ustawienia tras/bindingów
w kontrolowanym rejestrze administratora. Po błędzie zatrzymać migrację i zebrać oczyszczony wynik.
Rollback aplikacji do poprzedniego smoke wymaga osobnej zgody na deploy/restart; nie usuwać usług/HDI.
Schemat HDI nie cofa się automatycznie — brak `--auto-undeploy`, drop i `--delete-services`.
Free Tier nie zapewnia backup/recovery, więc nie obiecujemy przywrócenia utraconych danych.
Przed migracją już używanego kontenera wymagany jest uzgodniony eksport danych i plan odtworzenia;
dlatego pierwszy deployment tylko na dedykowanym, pustym HDI i danych syntetycznych.

## Historyczny plan przed provisioningiem — postęp CAP-HANA-01

1. **Wykonane:** dostępność/free potwierdzone CF/CAPI. Indywidualne rozliczenie PAYG nadal PENDING.
2. **Wykonane przez właściciela:** HDI; database_id brokera i org/space VERIFIED LIVE,
   zgodny Database ID/RUNNING w Cloud Central OWNER-REPORTED. Nie ponawiać create.
3. Zatwierdzić konkretny MTAR/SHA, migrację i binding CAP do tego HDI, z rollbackiem aplikacji.
4. Zatwierdzić syntetyczny scope i uruchomienie harness w sieci CF, następnie test restart/recovery.
5. Oddzielnie zakończyć LIVE security A/B/principal i zdecydować o ADR-0008; bez aktywowania KSeF/storage.
