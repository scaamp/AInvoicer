---
title: "CAP-HANA-02 — akceptacja rzeczywistej persystencji HANA (plan do zatwierdzenia)"
date: 2026-11-12
type: guide
status: faza-1-zakonczona-dml-no-go
tags: [ainvoicer, cap-hana-02, hana, hdi, cloud-foundry, persistence, acceptance]
---

# CAP-HANA-02 — akceptacja rzeczywistej persystencji HANA

[STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Runbook CAP-HANA-01](CAP_HANA_01_FIRST_DEPLOYMENT_RUNBOOK.md) ·
[Dziennik CAP-HANA-02](../journal/2026-11-12-cap-hana-02-persistence-acceptance.md)

> **Ten dokument nie jest zgodą na DML, restart ani task CF.** Każda bramka G1–G9 wymaga osobnej,
> jawnej zgody właściciela. Żaden test z tego planu nie został wykonany na HANA; nic nie jest PASS LIVE,
> o ile tabela nie mówi inaczej.

Granice (bez wyjątków): bez KSeF SEND/INBOUND, SAP WRITE, nowych usług i kosztów, service keys,
credentiali w repo/logach, zmian XSUAA/IAM, DROP/TRUNCATE, restartu CAP/HANA i DML przed zgodą,
automatycznego deploymentu/rollbacku. BLOB-LAB-01 i `cap-java/db/documents.cds` właściciela — nietknięte.

## 1. Faza 1 — wyniki read-only (12.11.2026, ok. 19:00 CET)

| Obszar | Wynik | Klasa dowodu |
| --- | --- | --- |
| `main` | `origin/main` = `bd38156` (+2 commity docs ponad `899fca0`: `.env.example`, `README.md`); kod `cap-java/`/`deploy/` identyczny z `899fca0`. Lokalny `D:\AInvoicer` na `4186081` (2 commity za), z niecommitowaną zmianą właściciela w `.env.example` i nieśledzonym `cap-java/db/documents.cds` — **nie ruszano** | Git |
| CI | Run `43274518030` (`bd38156`) **SUCCESS**; `43269366403` (`aaaf6d2`) SUCCESS; run `43097095232` dla `899fca0` **utknął w `queued`** od 11.11 20:36 UTC (nie jest wynikiem); `4186081` cancelled (nadpisany) | GitHub API (publiczne) |
| MTA | `ainvoicer` **0.7.3**, brak aktywnych operacji MTA | CF (odczyt) |
| CAP `ainvoicer-srv` | STARTED 1/1, instancja `running` od **2026-11-11T20:31:50Z** (bez restartu od deploymentu), 200 MB/1 GB, fs4, SAP Java bp 2.76.0 | CF |
| AppRouter | STARTED 1/1 | CF |
| Deployer | STOPPED 0/1, bez trasy; 0 tasków CAP | CF |
| HANA `ainvoicer-hana-dev` | `hana-free`, `serviceStopped=false`, wersja 2026.28 (QRC), 16 GB/1 vCPU/80 GB | CAPI `/parameters` |
| Dostęp sieciowy HANA | `whitelistIPs=[]`, `privateLinkAllowlist=[]` — interpretacja „tylko BTP” **TO VERIFY** w Cloud Central (Allowed Connections) | CAPI |
| HDI `ainvoicer-db` | `hdi-shared`, create succeeded; bindingi: deployer + srv; **0 service keys** dla instancji | CF |
| Flagi env CAP | `HANA_READINESS=true`, `IDENTITY_SMOKE=false`, KSeF/INBOUND/SAP WRITE/sweeper `false`, storage `off`, oba outbox OFF (`SPRING_APPLICATION_JSON`), `SPRING_SQL_INIT_MODE=never`, profil `cf` | CAPI `environment_variables` (bez VCAP) |
| SSH / taski | SSH aplikacji **wyłączone** (`ssh=false`), w space dozwolone; `cf enable-ssh` wymagałby restartu → poza zakresem | CF |
| Quota org | `FREE_QUOTA`, użyte 1408 MB / 3 instancje | CAPI `usage_summary` |
| Połączenie CAP→HANA | Dowód z 11.11 (logi startu: `HikariPool-1`, `HanaConnection`, `CloudPersistenceGuard` fail-closed nie zatrzymał startu). Bufor `cf logs --recent` jest dziś **pusty**, więc dziś **nie potwierdzono ponownie** stanu puli | Historyczny LIVE |

Bieżące połączenie można potwierdzić bez DML i bez nowych uprawnień: właściciel w istniejącej sesji
AppRouter (A, rola z CAP-07B.2) wykonuje odczyt `KsefOperations` przez dotychczasową ścieżkę UI/OData.
Oczekiwane: HTTP 200 i pusta kolekcja (HDI świeże, `SQL_INIT=never`, guard wyklucza H2). Wynik zapisuje
właściciel (**OWNER-REPORTED**). Jeżeli HANA była nocą zatrzymana, ten sam odczyt pokaże też,
czy pula Hikari odzyskała połączenie.

## 2. Katalogowy SQL HDI — metoda i blocker

**Wynik dzisiaj: NIE WYKONANO — brak autoryzowanego kanału SQL** (bez service key, DBADMIN, SSH i runnera).
Model wdrożonego 0.7.3 jest identyczny z `bd38156` (`git diff fe0a0c1 bd38156 -- cap-java/db cap-java/srv` pusty);
offline `cds build --for hana --production` daje oczekiwany katalog:

| Typ | Oczekiwane obiekty (nazwy katalogowe HANA) |
| --- | --- |
| Tabele (14, każda z PK) | `CDS_OUTBOX_MESSAGES`, `CUSTOMER_CAP_JAVA_` + `KSEFINBOUNDACCESSGRANTS`, `KSEFINBOUNDDOCUMENTS`, `KSEFINBOUNDINTEGRITYCONFLICTS`, `KSEFINBOUNDSYNCCHECKPOINTS`, `KSEFINBOUNDSYNCLEASES`, `KSEFINVOICES`, `KSEFINVOICESNAPSHOTS`, `KSEFOPERATIONS`, `KSEFSENDRESERVATIONS`, `KSEFSUBMISSIONHISTORY`, `KSEFSUBMISSIONS`, `SAPRESULTWRITEHISTORY`, `SAPRESULTWRITES` |
| Widoki (4) | `KSEFSERVICE_INBOUNDINVOICEOVERVIEW`, `KSEFSERVICE_INVOICEINTEGRATIONSTATUS`, `KSEFSERVICE_KSEFINVOICES`, `KSEFSERVICE_KSEFOPERATIONS` |
| Unikalne indeksy (6) | `CUSTOMER_CAP_JAVA_` + `KSEFINBOUNDACCESSGRANTS_SCOPE`, `KSEFINBOUNDDOCUMENTS_IDENTITY`, `KSEFINVOICESNAPSHOTS_DOCUMENT`, `KSEFOPERATIONS_IDEMPOTENCY`, `KSEFSUBMISSIONS_SNAPSHOT`, `SAPRESULTWRITES_KSEFNUMBER` |

Ocena wariantów:

| Wariant | Ocena |
| --- | --- |
| **A. Jednorazowy task CF na istniejącym droplecie `ainvoicer-db-deployer`** (Node 24 + `hdb` + istniejący binding HDI) z recenzowanym skryptem [`hdi-catalog-readonly.cjs`](../../deploy/scripts/hdi-catalog-readonly.cjs) | **REKOMENDOWANY.** Jedno parametryzowane `SELECT` z `SYS.TABLES/VIEWS/INDEXES` ograniczone do schematu z bindingu; bez service key, nowej usługi/bindingu, DBADMIN, restartu CAP. Wynik: jedna linia `HDI_CATALOG verdict=MATCH|MISMATCH …` bez hosta/schematu/użytkownika. Skutek uboczny: tymczasowy kontener taska (256 MB z quoty, 0 kosztu). Wymaga zgody (G1). |
| B. Faza `check` harnessu | Weryfikuje tylko HDB i jeden indeks; wymaga runnera (§3) — **BLOCKED**. |
| C. SAP HANA Database Explorer / SQL console | Dodanie kontenera HDI może tworzyć service key, a konsola instancji działa jako DBADMIN → **odrzucone** do czasu potwierdzenia, że nie tworzy kluczy. |
| D. `hdi-deploy --simulate-make` | Nie jest czysto read-only (pre-make, np. granty, nadal się wykonują) → **odrzucone**. |
| E. JDBC z laptopa | Wymaga credentiali (service key) i otwarcia allowlisty → **odrzucone**. |
| F. `cf ssh` do CAP | Wymaga `enable-ssh` + restartu CAP → **odrzucone** w tym etapie. |

### G1 — procedura taska katalogowego (do zgody; NIE WYKONANO)

Program wysyłany do taska jest dokładnie złożony z funkcji skryptu (podgląd: `--print-runtime-source`),
skompresowany gzip+base64 (brak problemów z cytowaniem; długość polecenia 3353 < 4096).
SHA-256 programu wykonawczego: `65ab052de0c03f5a3b873cbf377a302f91f66a5a96be25d5cc5adaa3564d425c`.

```powershell
# Z checkoutu z zatwierdzonym SHA tego brancha. Bez trace, bez cf env, bez service keys.
$cf = Join-Path $env:USERPROFILE 'tools/cf8/x/cf.exe'
$scratch = Join-Path $env:TEMP 'cap-hana-02'; New-Item -ItemType Directory -Force $scratch | Out-Null
node deploy/scripts/hdi-catalog-readonly.cjs --print-runtime-source      # review programu
node deploy/scripts/hdi-catalog-readonly.cjs --task-json | Out-File -Encoding ascii "$scratch\hdi-catalog-task.json"
# stderr: runtimeSourceSha256 musi być równy wartości powyżej.
& $cf target; & $cf apps; & $cf service ainvoicer-db       # dev, deployer STOPPED, 0 kluczy
$guid = & $cf app ainvoicer-db-deployer --guid
& $cf curl -X POST "/v3/apps/$guid/tasks" -d "@$scratch\hdi-catalog-task.json"   # MUTACJA: utworzenie taska
& $cf tasks ainvoicer-db-deployer                           # SUCCEEDED / FAILED
& $cf logs ainvoicer-db-deployer --recent | Select-String 'HDI_CATALOG'
Remove-Item "$scratch\hdi-catalog-task.json"
```

Kryterium: `HDI_CATALOG verdict=MATCH tables=14/14 views=4/4 uniqueIndexes=6/6 primaryKeys=14/14 missing=[] unexpected=[]`
i task SUCCEEDED (kod 0). Kod 2 = MISMATCH (analiza, bez naprawy przez DDL); kod 1 = odmowa/błąd
(`HDI_CATALOG_ERROR stage=… code=… sqlState=…`, bez komunikatu serwera). Nie powtarzać w pętli.

## 3. Gotowość harnessu `HanaPersistenceIT` + `hana-integration.mjs`

Mocne strony (inspekcja + regresja H2): bramka przed utworzeniem DataSource (zgoda, brak CI, UUID, NIP z sumą
kontrolną, HDI z listy, dokładnie jeden binding `hdi-shared`, zakaz DBADMIN); atomowy seed z odmową zajętego
scope; marker `INT03-HANA-<uuid>` na każdym rekordzie; cleanup tylko własnych kluczy pod blokadami w jednej
transakcji z odmową przy obcym lease/zmienionym checkpoincie; brak DROP/TRUNCATE/szerokiego DELETE;
osobne JVM dla faz; brak efektów KSeF/SAP (flagi OFF w `@SpringBootTest`).

Poprawki offline w tym branchu (regresja w §7):

1. **Zgoda read-only** `READ_ONLY_CHECK_APPROVED_HDI` odblokowuje wyłącznie `check`, `recover`, `verify-cleanup`;
   `seed`/`contend`/`cleanup` nadal wymagają `WRITE_SYNTHETIC_DATA_TO_APPROVED_HDI`. Wcześniej nawet `check`
   wymagał tokenu zapisu (ryzyko pomyłki przy kopiowaniu zmiennych).
2. **Faza `verify-cleanup`** (nowa JVM, bez DML): brak własnej operacji/klucza idempotencji/dokumentu/outbox/
   checkpointu/lease; rekordy innych runów nie są czytane.
3. Walidacja bramki wydzielona do `validate(Map)` — testowalna offline bez zmiennych procesu.

**Wykonalność w istniejącym środowisku: NIE (RUNTIME BLOCKED).** Droplet CAP nie zawiera klas testowych,
Mavena ani JUnit; SSH CAP wyłączone; laptop nie ma credentiali (zakaz service keys), a allowlista HANA
prawdopodobnie dopuszcza tylko BTP. Profil `cf` w IT uruchamia `CloudIdentityGuard`, więc runner w CF
potrzebuje bindingu HDI **i** XSUAA. Warianty runnera do decyzji (żaden nie jest przygotowany):

| Wariant | Skutki | Ocena |
| --- | --- | --- |
| R2. Tymczasowa aplikacja `ainvoicer-hana-it` bez trasy (`--no-route --no-start`), SapMachine 25, prebuilt pakiet: klasy aplikacji+testów, zależności, `junit-platform-console-standalone`; fazy jako `cf run-task`; po teście `cf delete` + usunięcie bindingów | Nowa aplikacja i 2 nowe bindingi (nowy użytkownik RT HDI + credential XSUAA), bez nowych instancji usług; ≤1 GB quoty na task, 0 kosztu; wymaga osobnego etapu offline (budowa i review pakietu) | **Rekomendowany** do decyzji właściciela |
| R1. Pakiet testowy w nowym dropletcie `ainvoicer-srv` | Zmiana produkcyjnego artefaktu + deployment | Odrzucony |
| R3. Odpowiednik SQL przez task deployera | Testuje constrainty DB, nie CQN/mapowanie CAP 409006/CAS repozytoriów | Tylko uzupełnienie, nie akceptacja |

`contend` jako wyścig wymaga **dwóch** równoległych JVM (dwa taski tej samej aplikacji R2 to spełniają);
pojedynczy task testuje lock na dwóch połączeniach i fencing, ale nie wyścig międzyprocesowy.

## 4. Ostrzeżenia `Failed to register handler method` (outbox OFF)

Odtworzone offline z tą samą konfiguracją startu (`HanaReadinessConfigurationTest`, H2): **9 WARN**, wszystkie
„Could not find any matching service”:
CAP: `CqnServiceOutboxHandler.adjustOutboxMessage/publishedByOutbox`, `AuditLogOutboxHandler.prepareOutboxMessage/publishedByOutbox`,
`MessagingOutboxHandler.adjustMessagingContext`; aplikacja: `KsefSubmissionWorker.onSubmit/wake`,
`SapKsefResultWriteback.process/wakeCollector`.

Przyczyna: `cds.outbox.persistent/inMemory.enabled=false` → brak usług `OutboxService` (wymagane przez
`CloudHanaReadinessGuard`), więc metody `@On(service="KsefSubmissionOutbox"|"SapResultOutbox"|…)` nie mają celu.
Skutek: oczekiwany i nieszkodliwy w tym wariancie — brak kolektorów i dostaw. Bez zmian kodu i bez aktywacji outbox.
Warunki na przyszłość: (a) przy włączaniu outbox 4 WARN aplikacji muszą zniknąć (kryterium akceptacji),
(b) wywołanie `outbox.submit` przez proxy `@Lazy` przy wyłączonym outbox zakończy się błędem — ścieżki są
zamknięte flagami i guardem, (c) syntetyczny wiersz harnessu w `CDS_OUTBOX_MESSAGES` (`target=INT03_NO_DELIVERY`)
musi zostać usunięty przez `cleanup` **przed** jakąkolwiek aktywacją outbox.

## 5. Ryzyka restartu i Free Tier

- Free Tier może być zatrzymywany automatycznie (dokładny harmonogram i retencja **TO VERIFY** w SAP Help/Cloud Central);
  brak backupu. Cały cykl seed→cleanup planować w jednym oknie dnia; przed każdą fazą odczyt `serviceStopped=false`.
- Zatrzymana HANA przy działającym CAP: proces żyje (health = port), operacje DB padają; zdolność puli do
  reconnect **niezmierzona**. Restart CAP przy zatrzymanej HANA → fail-closed guard → pętla crash/restart i przerwa API.
- `cf restart ainvoicer-srv` (1 instancja) = przerwa ~1–2 min i utrata sesji; `--strategy rolling` ogranicza przerwę
  kosztem chwilowo +1 GB quoty (dostępne). Restart nie restage'uje (ten sam droplet fs4, deprecated).
- Restart CAP nie jest potrzebny do dowodu trwałości: `recover` w nowej JVM runnera to dowód trwałości DB;
  restart CAP (G6) dowodzi jedynie ponownego startu aplikacji na HANA — opcjonalny, osobna zgoda.
- Cleanup po przerwanym runie: nigdy szeroki DELETE; `recover` → analiza → `cleanup` (odmawia przy obcym stanie)
  → `verify-cleanup`. Nie zmieniać UUID, by ukryć częściowy wynik.

## 6. Faza 2 — plan testów (każda bramka = osobna zgoda)

Parametry runu (nowe, nieużywane):

| Parametr | Wartość |
| --- | --- |
| `AINV_HANA_TEST_RUN_ID` | `1e335114-c800-4ff3-9ed8-56a914126af8` |
| `AINV_HANA_TEST_NIP` | `7770002317` (syntetyczny, poprawna suma kontrolna; ≠ `5260002008` zakresu użytkownika, który i tak nie przechodzi walidacji harnessu — dane testowe nie są widoczne w roli UI) |
| `AINV_HANA_TEST_HDI_NAME` | `ainvoicer-db` |
| Scope | `(TEST, 7770002317, BUYER)`; marker `INT03-HANA-1e335114-c800-4ff3-9ed8-56a914126af8` |

Dokładny zakres zapisów (wszystko syntetyczne; brak innych tabel):

| Faza | Tabela | Klucz | Operacja / wartości |
| --- | --- | --- | --- |
| seed (1 commit) | `CUSTOMER_CAP_JAVA_KSEFOPERATIONS` | `ID=1e335114-…` | INSERT: NIP 7770002317, `companyCode=TST0`, OUTBOUND/TEST/CHECK_STATUS/NEW, `sapDocumentRef`=`idempotencyKey`=marker |
| seed | `CUSTOMER_CAP_JAVA_KSEFINBOUNDDOCUMENTS` | `ID=e6b6b79d-9b32-380c-8b88-5c7e1d1c4b43` | INSERT: TEST, `ksefNumber=1e335114-c800-4ff3-9ed8-56a914126af`, `invoiceNumber`=marker, 123456789.12, 4294967297 B, czas 100 ns |
| seed | `CDS_OUTBOX_MESSAGES` | `ID=1e335114-…` | INSERT: `target=INT03_NO_DELIVERY`, `msg`=marker, attempts 0 (bez dostawy) |
| seed | `CUSTOMER_CAP_JAVA_KSEFINBOUNDSYNCCHECKPOINTS` | scope | INSERT: version 1, HWM `2026-11-11T08:00:00.1234567Z` |
| seed (odrzucane) | `…KSEFOPERATIONS` | losowe ID | duplikat idempotencji → oczekiwane CAP `409006` (zapis SQLState/vendor code); wymuszony rollback INSERT → brak wiersza; CAS ze starą wersją/cofnięciem HWM → brak zmiany |
| contend | `…KSEFOPERATIONS` | `ID=1e335114-…` | UPDATE `status` NEW→COMPLETED (CAS); `SELECT … FOR UPDATE` z dwóch połączeń |
| contend | `CUSTOMER_CAP_JAVA_KSEFINBOUNDSYNCLEASES` | scope | claim lease + UPDATE `lastCode`=marker; fencing starego tokenu odrzucony |
| cleanup (1 commit, pod blokadami) | 5 tabel powyżej | tylko powyższe klucze | DELETE po walidacji markerów, `version=1`, HWM i właściciela lease |

| Bramka | Zakres | Zgoda | Kryterium |
| --- | --- | --- | --- |
| G0 | Odczyt `KsefOperations` przez AppRouter (sesja właściciela) | brak DML | 200 + pusta kolekcja |
| G1 | Task katalogowy (§2) | task CF | `verdict=MATCH` |
| G2 | Runner R2: budowa offline + review pakietu, potem push/bind | nowa app + 2 bindingi | app bez trasy, binding HDI+XSUAA, 0 kluczy |
| G3 | `check` (`READ_ONLY_CHECK_APPROVED_HDI`) | odczyt | `HANA_TEST_PHASE_PASS=check`, HDB, dokładny HDI |
| G4 | `seed` (`WRITE_…`) | **pierwszy DML** | `HANA_TEST_PHASE_PASS=seed`, `HANA_UNIQUE_SQLSTATE=…` |
| G5 | `recover` w nowej JVM (read-only) | odczyt | dokładnie 1 rekord każdego typu, Decimal/Int64/100 ns, HWM |
| G6 | opcjonalnie `cf restart ainvoicer-srv` + G0 + `recover` | restart | 1/1, guardy, brak H2, `recover` PASS bez ponownego seed |
| G7 | `contend` w 2 równoległych taskach, potem `recover` | DML | suma `HANA_CAS_CHANGED=1`, suma `LEASE_CLAIMED=true` = 1 |
| G8 | `recover` (potwierdzenie przed cleanup) → `cleanup` | DML | cleanup PASS; odmowa przy obcym stanie = STOP i analiza |
| G9 | `verify-cleanup` w nowej JVM, następnie G1 ponownie (katalog bez zmian) | odczyt + task | `HANA_TEST_PHASE_PASS=verify-cleanup`, `MATCH` |

Po G9 (lub przy porzuceniu R2): `cf delete ainvoicer-hana-it` i usunięcie jego bindingów — osobna zgoda.

## 7. GO / NO-GO

- **G0 i G1: GO do zatwierdzenia** (odczyt / jednorazowy task read-only na istniejącym bindingu).
- **Pierwszy DML (G4): NO-GO.** Blokery: (1) brak runnera w sieci CF (R2 nieprzygotowany); (2) katalog HDI
  niepotwierdzony (G1); (3) poprawki harnessu i skrypt katalogowy czekają na review/CI/merge;
  (4) decyzja o bindingach runnera (HDI + XSUAA); (5) okno pracy HANA Free Tier.

Regresja offline tego brancha: `deploy` 43 testy (42 PASS, 1 skipped — niezależny czytnik Go tylko w CI),
harness H2 `HanaHarnessSafetyTest` 6/6 + `HanaReadinessConfigurationTest` 2/2, pełny `mvn verify` 470 testów
(0 FAIL, 6 skipped Azurite), skaner sekretów PASS. To testy offline/H2, nie HANA.
