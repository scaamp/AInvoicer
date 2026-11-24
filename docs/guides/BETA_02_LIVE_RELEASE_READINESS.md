---
title: "BETA-02 — gotowość środowiska LIVE i wydania Beta 0.1"
date: 2026-11-24
type: guide
status: aktywny
tags: [ainvoicer, beta, cf, hana, hdi, mta, ksef, inbound, release]
---

# BETA-02 — Live Environment Readiness

[Project Home](../README.md) · [STATUS](../STATUS.md) · [Roadmapa Beta](../BETA_ROADMAP.md) ·
[Ręczny UAT](BETA_02_MANUAL_UAT.md) · [Raport BETA-01](BETA_01_INBOUND_READINESS.md) ·
[STOR-04R release](KSEF_STOR_04R_HANA_RELEASE_READINESS.md) · [Dziennik](../journal/2026-11-24-beta-02-live-readiness.md)

> **Werdykt 24.11.2026:** PR #29 **MERGED** (`aa30143`, CI main SUCCESS). **CF PRECHECK EU20 = BLOCKED_ENV**
> (proces agenta nie widzi sesji EU20 właściciela) → **R-1 = R1_BLOCKED**. Offline gotowe: CURRENT G1 (task JSON),
> STOR-04 payload +1/0/0, kompletne Beta MTAR (0.7.5 / 0.7.6), twardy budżet HTTP manual importu, lokalny UAT.
> **Nie wykonano żadnej operacji LIVE** (CF/HANA/HDI/KSeF/SAP). STOP przed pierwszą niezatwierdzoną operacją.

## 1. PR #29 — final review i merge

| Bramka | Wynik |
| --- | --- |
| HEAD | `845406c90776afc7b47deedc71a229ba66ccef98` (GitHub API = lokalny `origin/feature/beta-01-inbound-readiness`) |
| Stan / konflikty | open, `mergeable=true`, `mergeable_state=clean`, baza `603e171` = `origin/main` |
| Required checks / ochrona | `main` bez branch protection i bez required checks (API `branches/main`: `protected=false`) — nic do obchodzenia |
| CI dokładnego HEAD | run `45234799772` „Offline security and smoke build”, `pull_request`, **success**, `head_sha=845406c…` |
| Zakres diffu | 50 plików; **brak** zmian `cap-java/db/` (HDI), `mta.yaml`, `approuter/`, ścieżki OUTBOUND SEND |
| XML `sizeBytes > 0`, długość, SHA-256 | `InboundInvoiceXmlHandler.hasExpectedSize` (`Long`, >0, = długość) + `MessageDigest.isEqual` SHA-256 |
| Grant przed BLOB | jeden CQN: `ID` ∧ `InboundGrantScope.visible` ∧ STORED/VERIFIED ∧ referencja → dopiero potem `store.get` |
| Wspólny scope Overview/Download | `InboundGrantScope` używany przez oba handlery (usunięta zduplikowana logika) |
| Brak dostępu po `storageRef` | klient podaje wyłącznie UUID; referencja czytana z DB; projekcja Overview bez `xmlStorageRef`/hash |
| Brak wycieku XML/JWT | logi wyłącznie kody (`INBOUND_XML_READ_FAILED code=…`); brak tokenów w diffie (skan wzorców) |
| CI trzech wariantów CF | `build:cf`, `build:cf:inbox-beta`, `… -- --with-xml-download` (test `ci.test.mjs`) |
| Inbox i SEND domyślnie OFF | `dataMode=disabled` w `build:cf`; `KsefInitialSendGuard` bez zmian; flagi storage/download/import `false` |
| CompanyCode | udokumentowane: kontekst sesji, nie własność dokumentu; multi-company NO-GO |
| Twardy limit fizycznych requestów | w PR #29: **BLOCKED** (udokumentowane); domknięte w BETA-02 (§5) |

**Merge:** `git merge --no-ff` zgodny z konwencją repo (`Merge PR #N: …`), push bez force: `603e171..aa30143`.
GitHub: PR #29 `closed`, `merged=true`, `merge_commit_sha=aa30143d7155aa474449f31aa1fe6b7972865db5`.
CI na `main` (push): run `45369224329` — **success** dla `aa30143`. Baseline lokalny `main`: Maven **715/0/0/6**.

## 2. LIVE CF PRECHECK (read-only) — BLOCKED_ENV

Polecenia wykonane (wyłącznie odczyt lokalnej konfiguracji CLI): `cf version`, `cf api`, `cf target`, `cf plugins`.

| Fakt | Wartość |
| --- | --- |
| CLI widoczny dla procesu agenta | `C:\Program Files\Cloud Foundry\cf.exe` **7.8.0** (+ `cf7.exe`); brak innych instalacji w PATH |
| `CF_HOME` | nieustawione (proces, User, Machine) → `%USERPROFILE%\.cf\config.json` (jedyny katalog `.cf` na dysku C:) |
| API / target | `https://api.cf.us10-001.hana.ondemand.com`, **bez org/space**, inny użytkownik niż oczekiwany kontekst EU20 |
| MultiApps plugin | **brak** (`cf plugins` pusty) → `cf mta`, `cf mta-ops` niedostępne |
| Portable CLI z runbooka | `%USERPROFILE%\tools\cf8\x\cf.exe` **nie istnieje** |

Wniosek: sesja `cf login --sso` właściciela (EU20) **nie jest widoczna** dla procesu agenta (inny `CF_HOME`, inna
instalacja CLI lub inny terminal/profil). Zgodnie z poleceniem **nie** wykonano `cf login`, `cf api`/`cf target` ze zmianą,
nie kopiowano tokenów, nie użyto `cf env`/`cf oauth-token`/`CF_TRACE`/service keys/`cf run-task`.
Wszystkie pytania §2.3 (aplikacje, instancje, wersje modułów, HANA, bindingi, historia MTA, mixed versions) = **NOT VERIFIED**.

**Co właściciel może zrobić (bez zmiany targetu przez agenta):** w terminalu z sesją EU20 odczytać
`echo $env:CF_HOME` oraz `(Get-Command cf).Source` i przekazać obie ścieżki agentowi (bez tokenów) albo uruchomić
agenta z tego samego środowiska.
Plugin MultiApps (`cf install-plugin multiapps`) musi być zainstalowany w tym CLI, aby wykonać `cf mta`/`cf mta-ops`.

## 3. R-1 — selektywny deployment deployera HDI: **R1_BLOCKED**

Analiza kodu MultiApps z ORCH-09 ([dziennik](../journal/2026-11-15-orch-09-outbound-hana-integration.md#r-1--cf-deploy--m-ustalenia-i-blokada-wykonania))
pozostaje jedyną podstawą; **żadne** z 8 pytań nie mogło zostać potwierdzone metadanymi targetu.

| # | Pytanie | Ustalenie (offline) | Dowód LIVE |
| --- | --- | --- | --- |
| 1 | Bezpieczny `-m ainvoicer-db-deployer`? | Pełny MTAR + selekcja: pominięte moduły nie są deployowane ani undeployowane | brak — wersja kontrolera EU20 nieznana |
| 2 | Jakie zasoby? | Bez `-r` klient nie wysyła filtra → kontroler przetwarza **wszystkie** zasoby deskryptora (`ainvoicer-auth`, `ainvoicer-destination`, `ainvoicer-db`, wszystkie `existing-service`) | brak |
| 3 | Zmiana bindingów? | Możliwa aktualizacja metadanych/bindingów zasobów; `existing-service` nie jest tworzony ani aktualizowany | wymaga `cf mta ainvoicer` + bindingów |
| 4 | srv/AppRouter nienaruszone? | Tak według kodu (brak w liście modułów do deploy/undeploy) | brak |
| 5 | Mixed versions? | Po `-m` etykieta MTA ≠ zawartość; operacja traktowana jako UPGRADE | brak |
| 6 | Deskryptor zachowuje moduły? | Tak: Beta i 0.7.4 mają **wszystkie 3 moduły** (nie budujemy skróconego MTAR) | — |
| 7 | Wykrycie częściowego deploymentu | `cf mta-ops` (stan operacji), `cf tasks ainvoicer-db-deployer`, log deployera (1 plik deploy / 0 undeploy) | — |
| 8 | Rollback/recovery bez utraty danych | Nowa tabela pusta; brak undeploy → stare tabele nienaruszone. STOP przy niejednoznaczności, bez retry/abort automatycznego; NEW G1 rozstrzyga stan | — |

**Rekomendacja do decyzji właściciela (nie wykonana):** przy przyszłej zgodzie rozważyć jawne `-r ainvoicer-db`
obok `-m ainvoicer-db-deployer`, aby ograniczyć przetwarzanie zasobów do HDI — semantykę `-r` w wersji kontrolera
EU20 trzeba najpierw potwierdzić `cf mta-ops`/dokumentacją MultiApps danej wersji. Bez PRECHECK pozostaje **R1_BLOCKED**.

## 4. HANA DEV release preparation (offline)

### 4.1. CURRENT G1 — gotowy do osobnej zgody (niewykonany)

| Kontrola | Wynik (finalny `main` `aa30143`) |
| --- | --- |
| Kontrakt | 15 TABLE / 5 VIEW / 6 UNIQUE / 15 PK (CORE 14/4/6 + LAB) |
| `node deploy/scripts/hdi-catalog-readonly.cjs --task-json` | task `cap-hana-02-hdi-catalog-readonly`, 256 MB / 512 MB |
| `runtimeSourceSha256` | `6ebdfd14863cc7a69514e3065759712c3485d51053c052b7b9e68a632aad5785` — **zgodny** z ostatnim znanym |
| Długość programu | `commandLength=4071` ≤ 4096 |
| Testy `deploy` | 199 PASS / 0 FAIL / 2 SKIP (MTAR niezbudowany w tym przebiegu) na `main` |
| Kompatybilność z targetem | **NOT VERIFIED** (BLOCKED_ENV): task wymaga dropletu `ainvoicer-db-deployer` z Node + `hdb` i bindingu HDI |

### 4.2. STOR-04 deployment payload

`verify:stor04-release` (CI i lokalnie): dokładnie **+1** (`src/gen/customer.cap_java.KsefStoredDocuments.hdbtable`,
hash = kontrakt `hdi-catalog-stor04.v1.json`), **0** zmienionych, **0** usuniętych, brak undeploy/hdbtabledata/grants,
`package.json`/lock deployera bez zmian, `ainvoicer-db` jako `existing-service`, BlobService/LAB bez zmian.
Docelowy NEW G1: 16 / 5 / 6 / 16. Wyniki i SHA z bieżącego builda: §6.

### 4.3. Procedura HANA acceptance (każdy krok = osobna zgoda)

Bazuje na istniejących: [R2 runner](CAP_HANA_02_R2_RUNNER.md), [STOR-04R §9–§12](KSEF_STOR_04R_HANA_RELEASE_READINESS.md).

| # | Krok | Komenda/narzędzie | PASS | STOP |
| --- | --- | --- | --- | --- |
| H1 | CURRENT G1 | jeden task z `--task-json` na `ainvoicer-db-deployer` | `MATCH 15/5/6/15` | inny wynik / brak logu |
| H2 | HDI deploy | `cf deploy ainvoicer_0.7.4.mtar -e deploy/btp-dev-eu20-hana-first.mtaext -m ainvoicer-db-deployer --retries 0` (po R-1) | operacja FINISHED, task SUCCEEDED, 1 deploy / 0 undeploy | niejednoznaczność → STOP, bez retry |
| H3 | NEW G1 | kontrakt STOR-04 (`hdi-catalog-stor04.cjs`) | `MATCH 16/5/6/16` | — |
| H4 | Syntetyczny BLOB INSERT/GET | runner R2 (`hana-it-runner.mjs`) | bajty i SHA-256 identyczne | — |
| H5 | Idempotencja | ten sam payload ponownie | ta sama referencja, 1 wiersz | — |
| H6 | Konflikt PK | inna treść, ten sam klucz | odrzucenie bez nadpisania | — |
| H7 | Restart nowej JVM | drugi przebieg runnera | odczyt po restarcie = SHA z H4 | — |

Bez nowych tabel testowych — wystarcza harness R2/STOR-04R.

## 5. Manual import — twardy budżet fizycznych żądań HTTP: **PASS offline**

Problem (BETA-01.1): JDK `HttpClient` ponawia idempotentny GET po utracie odpowiedzi **wewnątrz jednego `send()`**
(`OneShotWireRetryTest`), a flagi `jdk.httpclient.*` działają tylko globalnie dla JVM.

**Rozwiązanie — `BudgetedKsefTransport`** (`customer.cap_java.ksef`), wstrzyknięty przez istniejący punkt
`DefaultKsefClient(HttpClient, …)`; SDK MF bez zmian, brak flag JVM, wspólny bean `KSeFClient` (OUTBOUND, sync) nietknięty:

1. przed każdym żądaniem: STOP po wcześniejszym błędzie, `https` + ten sam origin + GET/POST, limit 1..9, termin
   przebiegu (domyślnie 90 s), **fencing lease** (`leases.requireOwned`) — utrata lease = zero dalszych żądań;
2. każde dopuszczone żądanie wykonuje **świeży** `HttpClient` (pusta pula, HTTP/1.1, `Redirect.NEVER`, bez proxy/Authenticator);
3. jego `SSLContext` wydaje **jeden** `SSLEngine`: ukryty replay/reconnect wymaga nowego połączenia TLS i zostaje odrzucony
   przed wysłaniem bajtów → fizyczne żądania ≤ połączenia TLS ≤ budżet;
4. timeout żądania = min(timeout SDK 20 s, pozostały czas); connect 5 s; non-2xx / I/O = STOP przebiegu.

`InboundManualImport` tworzy na każdy przebieg nowy SDK, auth (certyfikaty TOKEN, challenge, auth, polling, redeem,
refresh) i klienta odczytu na **tym samym** budżetowanym transporcie (`RunClients`). Nieznany wynik auth →
`KsefFailure.ambiguous` → lease **BLOCKED**; brak automatycznych ponowień. Konfiguracja:
`ainv.ksef.inbound.manual-import.max-http-requests=9`, `max-duration=PT90S` (walidowane, poza zakresem = brak startu).
Domyślnie OFF (`manual-import.enabled=false`, `inbound.enabled=false`), TEST-only, na CF nadal guard
`KSEF_INBOUND_CLOUD_ACTIVATION_NOT_READY`.

Dowód (rzeczywisty JDK 25 + TLS loopback + SDK MF 3.0.29, licznik żądań po stronie serwera):

| Test | Wynik |
| --- | --- |
| Kontrola: domyślny klient JDK, utracona odpowiedź GET | **≥ 2** żądania na drucie (replay potwierdzony) |
| To samo przez SDK + `BudgetedKsefTransport` | **dokładnie 1**, `REPLAY_BLOCKED`, kolejne żądanie odmówione przed I/O |
| Budżet 2 → trzecie żądanie | odmowa `BUDGET_EXHAUSTED`, 2 na drucie |
| Utrata lease / termin | `FENCE_LOST` / `DEADLINE`, 0 na drucie |
| 302 / 500 | brak follow, brak retry, STOP przebiegu |
| Obcy origin, `http`, DELETE, `sendAsync` | odmowa, 0 na drucie |
| Fabryka produkcyjna: auth XAdES, utracona odpowiedź challenge | 1 żądanie, `KsefFailure.ambiguous=true` (→ BLOCKED), fence ×1 |
| `InboundManualImportTest` | klienci per przebieg; utrata lease → `KSEF_INBOUND_LEASE_LOST`, zero wywołań SDK/auth |

**Ograniczenia (uczciwie):** dowód offline, nie LIVE; obsługiwane tylko synchroniczne `send` z ciałem czytanym przed
powrotem (SDK 3.0.29 używa `ofByteArray`); budżet obejmuje jeden przebieg w jednej JVM — globalny budżet API między
instancjami CF pozostaje osobną bramką (Beta: 1 instancja, 1 operator, 1 import).

### 5.1. Kontrakt operacji operatorskiej (projekt; wywołujący NIE jest zaimplementowany)

| Element | Kontrakt |
| --- | --- |
| Środowisko | wyłącznie KSeF TEST (`ainv.ksef.inbound.environment=TEST`), origin `https://api-test.ksef.mf.gov.pl` |
| Zakres | jeden NIP z konfiguracji backendu (`ainv.ksef.nip`), rola BUYER, jeden numer KSeF (wzorzec + CRC przed auth) |
| Operator | jedna osoba z jawną zgodą zawierającą numer KSeF (zamaskowany w raportach), czas i SHA artefaktu |
| Lease | jeden `KsefInboundSyncLeases` (10 min) wspólny z synchronizacją; zajęty/cooldown → `BUSY` bez HTTP |
| Budżet | ≤ 9 fizycznych żądań, ≤ 90 s, `BudgetedKsefTransport` |
| Status | `KsefInboundDocuments` (STORED/PARSED/VERIFIED lub stan błędu), `KsefInboundSyncLeases.lastCode` (WAITING/BLOCKED) |
| Deduplikacja | deterministyczny UUID dokumentu + unikalność tożsamości; drugi import = brak drugiego GET |
| Audyt | log techniczny `KSEF_MANUAL_IMPORT_TRANSPORT_STOP reason/reserved/connections` + wiersze statusu; bez XML/JWT/NIP |
| Po niejednoznaczności | zero automatycznych ponowień; BLOCKED wymaga decyzji operatora |
| Wywołujący | **otwarta decyzja**: np. jednorazowy CF task `ainvoicer-srv` z flagami tylko na czas taska — wymaga osobnego kodu, review i zgody; brak OData/schedulera |

## 6. Beta CF release preparation (offline)

Opt-in, kompletne MTAR (3 moduły) bez zmiany `mta.yaml` i standardowych buildów:
`npm run build:btp:beta --prefix deploy [-- --with-xml-download]` → `verify:beta`.

- baza: zweryfikowany kandydat HANA **0.7.4** (`build:btp:smoke` + `build:btp:hana`);
- `ainvoicer-srv` i `ainvoicer-db-deployer`: payload **bajt w bajt** z 0.7.4 (ten sam JAR, ten sam HDI +1/0/0);
- `ainvoicer-approuter`: te same `package*.json`/`xs-app.json`; `resources/` = `dist-cf-inbox-beta` zweryfikowane
  regułami `build-cf.mjs` (Inbox read-only CAP, `viewXml` tylko w wariancie XML, brak mock/fixture/testów/sekretów);
- deskryptor = deskryptor 0.7.4 z inną etykietą: **0.7.5** (Inbox read-only), **0.7.6** (Inbox + przycisk XML);
  usługi wyłącznie `existing-service`, srv z flagami KSeF/INBOUND/SAP WRITE/storage/download OFF i guardem readiness;
- weryfikacja na gotowym pliku po `mbt assemble`, dowód `beta-release-evidence.json` z SHA-256; CI buduje i weryfikuje oba.

Wyniki lokalnego łańcucha (Windows, JDK 25.0.4, Maven 3.9.16, Node 24.19.0, mbt 1.2.49), źródło `b728079`
(gałąź `feature/beta-02-live-readiness`; `workingTreeDirty=true` dotyczy wyłącznie `docs/`, które nie wchodzą do `sourceHash`):

| Artefakt | MTA | SHA-256 MTAR | Uwagi |
| --- | --- | --- | --- |
| identity-smoke | 0.7.2 | `a381a28e2a256d91b00c8ae55f1c80d66b9e48ed0332f1dd02b794a28d5bbf52` | 2 moduły, bez HDI |
| HANA release (STOR-04) | 0.7.4 | `4d161b998fe52d3c2fae5d3de149d954b990afecf94b6d77b090e6a7c43ad4bf` | `STOR04_RELEASE_ARTIFACT=PASS hdiAdded=1 hdiChanged=0 hdiRemoved=0`, nowa tabela `27cac1a2…de6a` |
| Beta Inbox read-only | 0.7.5 | `fe6ef7bc8c6f7a2f130ccd2efed945160b7ec3031d19633d6e8ed9a16e72b757` | AppRouter `04cd3edb…3abe`, `viewXml=false` |
| Beta Inbox + XML UI | 0.7.6 | `0561261b8277f4b9e5c909be2c5293d8dd49ed9242ad026f6b35acf03cbb50d2` | AppRouter `1d986b01…61c9`, `viewXml=true` (backend OFF) |
| wspólne srv / HDI (0.7.4 = 0.7.5 = 0.7.6) | — | srv `cac15ba8…9b65` · HDI `974f0619…29fe3` | bajt w bajt, weryfikowane w `verify:beta` |

**Te SHA nie są artefaktem do zgody** — kandydat do HDI/deploy musi zostać zbudowany ponownie z czystego `main` po merge
BETA-02 (ta sama procedura, nowe SHA zapisane w zgodzie).

### 6.1. Najwęższe flagi dla przyszłej Bety TEST (propozycja; guardy NIE zdjęte)

| Funkcja | Stan w Beta MTAR | Minimalne warunki włączenia |
| --- | --- | --- |
| READ-only Inbox | działa po deploy (Overview na HANA, rola `KsefOperator` + atrybuty XSUAA `taxpayerNips`/`companyCodes` = jedna para) | Role Collection z jedną zatwierdzoną parą NIP/CompanyCode; lista pusta do pierwszego importu |
| HANA private storage | OFF (`CloudHanaReadinessGuard` odrzuca `storage.hana.enabled=true`) | NEW G1 MATCH + osobna zmiana guardu: tryb np. `AINV_DEPLOYMENT_BETA_INBOUND_READ=true` dopuszczający **tylko** `AINV_KSEF_INBOUND_STORAGE_HANA_ENABLED` i `…_XML_DOWNLOAD_ENABLED` przy KSeF/INBOUND/SAP WRITE OFF |
| XML Download | backend OFF (404); przycisk tylko w 0.7.6 | jak wyżej + `AINV_KSEF_INBOUND_XML_DOWNLOAD_ENABLED=true`; bez PUBLIC OData BLOB |
| Manual import | niedostępny na CF (guard) | §5.1 — osobny kod wywołującego i zgoda na jeden import |
| OUTBOUND SEND | **zablokowany** (`KSEF_SNAPSHOT_POLICY_NOT_APPROVED`) | poza zakresem Beta 0.1 |

## 7. Kolejka zgód

Każda zgoda musi określać target (API EU20, org, space), SHA artefaktu, zakres, liczbę prób (1), wpływ i STOP/recovery.

| # | Operacja | Typ | Wymaga przed | Uwagi |
| --- | --- | --- | --- | --- |
| 0 | **Udostępnienie sesji EU20 agentowi** (właściwy `CF_HOME`/CLI + plugin MultiApps) i powtórzenie read-only PRECHECK | odczyt | — | **najbliższa wymagana akcja** |
| 1 | CURRENT G1 | 1 task read-only SQL (utworzenie taska mutuje platformę) | 0 | runtime SHA `6ebdfd14…5785` |
| 2 | HDI deploy STOR-04 | `cf deploy -m ainvoicer-db-deployer` | 0, 1, R-1 | artefakt 0.7.4 z czystego `main` po merge BETA-02 |
| 3 | NEW G1 | 1 task | 2 | `MATCH 16/5/6/16` |
| 4 | Syntetyczny HANA DML (R2) | task runnera | 3 | bez nowych tabel |
| 5 | Pełny CF deploy Beta MTAR | `cf deploy` 0.7.5 lub 0.7.6 | 3 (+4) | smoke + rollback do 0.7.3 |
| 6 | Włączenie INBOUND storage/download | zmiana guardu + env + restart | 5 | §6.1 |
| 7 | Jeden test KSeF TEST | jeden import, ≤ 9 żądań | 6 + kod wywołującego | §5.1 |
