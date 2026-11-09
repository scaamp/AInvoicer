---
title: "Dziennik — CAP-07B First SAP BTP Deployment, XSUAA LIVE, Business-User SSO & Security Acceptance"
date: 2026-11-08
type: journal
status: w-trakcie-bramka-wlasciciela
tags: [ainvoicer, journal, cap-07b, btp, cloud-foundry, xsuaa, hana, approuter, sso, security, cap-04d]
---

# Dziennik — CAP-07B LIVE BTP (etap 1: weryfikacja i bramka właściciela)

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Runbook](../guides/BTP_FIRST_DEPLOYMENT_RUNBOOK.md) · [BTP Identity](../guides/BTP_IDENTITY_SETUP.md) · [RAP UI LIVE](../guides/RAP_UI_LIVE_CONNECTION.md) · [Dziennik CAP-07.1](2026-11-08-cap-07-1-deployment-readiness.md) · [Architektura](../architecture/README.md)

## Date

2026-11-08 (Europe/Warsaw).

## Branch

`feature/cap-07b-live-btp` z `feature/cap-07-1-deployment-readiness` (`c9a8345`) + merge `origin/main` (`552fdb9`, dokumentacja `docs/architecture/` i 2 SVG). Stos: `main` ← PR #1 CAP-06.1 ← CAP-07 ← CAP-07.1 ← CAP-07B.

## Goal

Doprowadzić artefakty CAP-07.1 do rzeczywistego środowiska BTP CF (XSUAA, approuter, SSO H01, HANA/HDI) i wykonać testy bezpieczeństwa LIVE, bez SAP WRITE i KSeF SEND.

## Wynik etapu 1 — uczciwie

**Żaden element BTP nie został wdrożony ani utworzony.** Agent nie ma zalogowanej sesji CF/BTP (brak `cf login`, brak `btp` CLI, brak dostępu do cockpitu), więc inventory subaccountu, entitlementy, quota i koszty HANA pozostają **NOT VERIFIED**, a wszystkie testy LIVE BTP — **BLOCKED (owner login + approval)**. Wykonano wszystko, co da się sprawdzić bez tożsamości właściciela: Git, regresję, MTAR, odczyty H01, łączność z API CF i weryfikację wariantu SSO w dostępnych źródłach.

## Initial State

- `git fetch --all --prune`: `main` `552fdb9`, CAP-06.1 `ed35935`, CAP-07 `829725b`, CAP-07.1 `c9a8345` — **zgodne** z opisem zadania; drzewo czyste.
- `main` miał 1 commit nieobecny w CAP-07.1 (`552fdb9`, `docs/architecture/` + README). Merge do CAP-07B: jeden konflikt w `docs/README.md` (obie listy wpisów zachowane); `docs/architecture/` identyczne z `main` (`git diff origin/main HEAD -- docs/architecture` puste).
- PR: **NOT VERIFIED w tej sesji** (`gh` niezainstalowany; ostatni odczyt API w CAP-07.1: PR #1 OPEN/clean).
- Narzędzia: JDK 25.0.4, Maven 3.9.16, Node/npm jak w CAP-07.1. Brak `cf`, `btp`, `gh`, `mbt`, GNU make.
- Procesy z poprzednich sesji: 8080 (PID 37384), 8085, 8086, MCP 4847 — **nie zatrzymano**; build/testy w izolowanym `git worktree` w scratchpad.

## BTP inventory (read-only)

| Element | Wynik | Kategoria |
| --- | --- | --- |
| API CF `https://api.cf.eu20-001.hana.ondemand.com` | HTTP 200, CC v3 **3.233.0**, cf-deployment v60.9.0, „SAP BTP Cloud Foundry environment”, min. CLI 8.0.0, UAA `uaa.cf.eu20-001…` | LIVE (publiczny endpoint, bez uwierzytelnienia) |
| CF CLI | zainstalowany portable **cf 8.19.0** (`%USERPROFILE%\tools\cf8\x\cf.exe`, oficjalny pakiet packages.cloudfoundry.org, Apache 2.0); `cf api` ustawione | LOCAL |
| `cf target`/`services`/`marketplace`/`buildpacks`/`apps`/quota | **`Not logged in`** → NOT VERIFIED | BLOCKED (login właściciela) |
| `btp` CLI (subaccount, entitlements, role collections, trust) | nie zainstalowano: pobranie wymaga akceptacji SAP EULA w imieniu właściciela | BLOCKED (właściciel) |
| Subaccount EU20 vs Legacy us10 | relacja właściciela z CAP-07.1 (CF `dev-v2xzij9s_dev`, `cf-eu20-001`, ABAP `dev`); host H01 `.abap.eu20` potwierdzony MCP; brak własnego odczytu subaccountu | relacja + LIVE H01 |

Nie zakładano braku usług z powodu braku odczytu.

## Cost / Entitlement gate

| Service | Plan | Status 2026-11-08 | Koszt (wiedza, do potwierdzenia w cockpicie) |
| --- | --- | --- | --- |
| XSUAA | `application` | NOT VERIFIED | usługa bez opłat |
| Destination | `lite` | NOT VERIFIED | bez opłat |
| SAP HANA Cloud | wymagany wyłącznie plan bezpłatny free tier (`hana-cloud` / `hana-free` — nazwa **TO VERIFY** w `cf marketplace`) | NOT VERIFIED | plan płatny = istotny koszt ciągły → **BLOCK bez osobnej zgody** |
| HDI Container | `hana` / `hdi-shared` | NOT VERIFIED | kontener bez osobnej opłaty, wymaga instancji HANA |
| Cloud Foundry Runtime | `free` | AVAILABLE (relacja właściciela); quota NOT VERIFIED | potrzeba ~1,5 GB (srv 1024M + approuter 256M + deployer 256M chwilowo) |
| Java buildpack | `sap_java_buildpack_jakarta` + SapMachine 25 | NOT VERIFIED (`cf buildpacks` wymaga logowania) | — |

Konto jest **free tier (nie trial)**: plany płatne w tym koncie generują rzeczywiste opłaty. Dlatego zgoda na deploy jest warunkowa: tylko gdy inventory pokaże bezpłatny plan HANA. W przeciwnym razie alternatywa **identity-smoke** (runbook §3) — wyłącznie po osobnej zgodzie; nie aktywowano.

## SSO H01 — weryfikacja wariantu A

| Źródło | Ustalenie |
| --- | --- |
| Dokumentacja `@sap/approuter` (npm) | `OAuth2UserTokenExchange` nie współpracuje z przekazywaniem tokenów **IAS** (identity service) przez approuter — AInvoicer używa XSUAA (ADR-0006), więc ograniczenie nie dotyczy; jest warunkiem: nie przełączać approutera na binding `identity` bez zmiany wariantu |
| developers.sap.com *Create a SAP Fiori App and Deploy it to SAP BTP, Cloud Foundry environment* (ABAP env) | ograniczenie: narzędzia muszą być w **tym samym subaccount** co instancja ABAP; użytkownik musi mieć autoryzację do usługi OData (= Business Role H01); właściwości destination nie są pokazane |
| abapeur.fr (źródło wtórne) | generator Fiori dla ABAP Environment tworzy destination z typem wymiany tokenu użytkownika na token ważny w ABAP environment |
| SAP Community Q&A (2021/22) | starsze opinie: brak bezpośredniego użycia JWT sesji / SAML inbound — **nieaktualne lub dotyczą innego scenariusza**; nie przyjęto jako dowodu |
| SAP Help (*Routing via Destination*) | nadal nieodczytany (powłoka JS) |

Wniosek: wariant A `OAuth2UserTokenExchange` z poświadczeniami `uaa` instancji ABAP `dev` jest **spójny z dostępnymi źródłami dla tego samego subaccount**, ale ostatecznym dowodem może być wyłącznie test LIVE (runbook §7 #3, #8: principal w H01 = business user, nie communication user). Nie zmieniono `xs-app.json` ani MTA. Dodatkowy warunek zapisany w runbooku: approuter i H01 muszą używać **tego samego originu IdP** w Trust Configuration (TO VERIFY przez właściciela).

## Implementation

1. Merge `origin/main` (zachowane `docs/architecture/` i oba SVG).
2. **Poprawka testu** `approuter/test/xs-app.test.mjs`: regex usług MTA `\n` → `\r?\n`. Świeży checkout z `core.autocrlf=true` (Windows) dawał CRLF i fałszywy FAIL (`[] != [destination, hana, xsuaa]`); deskryptor był poprawny. Po zmianie 7/7 w drzewie CRLF i LF.
3. **Procedura MTAR**: `mbt module-build -g` uruchomione w katalogu projektu tworzy `.<projekt>_mta_build_tmp` wewnątrz projektu, a `mbt assemble` w tym samym katalogu pakuje go podwójnie (MTAR 134 MB z duplikatem JAR). Poprawny wariant: `module-build -s <repo>` z katalogu nadrzędnego, potem `assemble` w osobnym katalogu z `mtad.yaml`, katalogiem wyników i `cap-java/xs-security.json` → **67 MB** (75 MB po rozpakowaniu) (runbook §4).
4. Dokumentacja: runbook, BTP_IDENTITY_SETUP, RAP_UI_LIVE_CONNECTION, STATUS, ROADMAP, indeks, ten dziennik.

## Commands Executed

```text
git fetch --all --prune; git switch -c feature/cap-07b-live-btp; git merge --no-ff origin/main (konflikt docs/README.md rozwiązany)
git worktree add --detach <scratchpad>/wt07b HEAD
mvn -B -f cap-java/pom.xml clean verify -Dcds.cds.skipVersionCheck=true                         # worktree
npm ci; npm run build:cf; npm run test:cf-build; npm run test:runner  (sapui5/ainvoicer)          # worktree
npm ci; npm run test:config  (approuter)                                                         # worktree + drzewo główne
npx fiori run --config ./ui5-mock.yaml --port 8095; node scripts/browser-test.mjs qunit …unit / …integration   # własny serwer, zatrzymany
npm i mbt@1.2.49 (scratchpad); mbt module-build -s wt07b -m ainvoicer-srv,ainvoicer-db-deployer,ainvoicer-approuter -g; mbt assemble
Invoke-WebRequest api.cf.eu20-001…/ i /v3/info; cf 8.19.0 api https://api.cf.eu20-001.hana.ondemand.com; cf target
MCP ABAP FS h01: odata_resolve_service ×2, odata_get_metadata (UI), odata_query Invoices + _Items + _References, search_objects, get_object_source BDEF ZR_/ZC_AINV_INV_H
```

## Test Results

| Zakres | Wynik | Kategoria |
| --- | --- | --- |
| Maven `clean verify` | **236/236 PASS**, BUILD SUCCESS 1:41 min, `cap-java-exec.jar` 75 MB | LOCAL MOCK + OFFLINE JWT |
| SAPUI5 `npm ci` + `build:cf` | PASS, 50 plików, `dataMode=rap`, capabilities OFF; WARN 3 hosty AI (bez zmian) | LOCAL |
| `test:cf-build` / `test:runner` | 7/7 / 3/3 PASS | LOCAL |
| QUnit / OPA (własny mock :8095) | **77/77 (442)** / **13/13 (80)** PASS | LOCAL MOCK |
| Approuter `test:config` | pierwszy przebieg 6/7 (CRLF) → po poprawce **7/7** (CRLF i LF) | OFFLINE |
| `mbt build` | BLOCKED (brak GNU make) | — |
| `module-build` + `assemble` | PASS → `ainvoicer_0.7.1.mtar` 67 MB (75 MB po rozpakowaniu): JAR, HDI 16 artefaktów (operacje, rezerwacje, snapshoty, zgłoszenia, outbox, writeback + indeksy unikalności), approuter z `dist-cf` (`rap`, `sendInvoice=false`, 0 `node_modules`/testów/fixture/`default-env`), `xs-security.json`; mtad: `AINV_KSEF_ENABLED/AINV_SAP_WRITE_ENABLED="false"`, 1× `forwardAuthToken` (CAP); skan sekretów 0 | LOCAL |
| H01 RAP UI `ZUI_AINV_INV_H_O4/0001` | published V4, metadata 200, Invoices **4**, `_Items` **9**, `_References` **6**; wszystkie CompanyCode 1000, NIP 5260002008 po stronie sprzedawcy lub nabywcy; `KsefNumber` puste; `LocalLastChangedAt` 2026-10-26T17:28:46.662912Z | LIVE READ (developer ADT OAuth MCP = `CB9980000000` z rolą), **nie** SSO z CF |
| H01 Web API `ZAPI_AINV_INV_H_O4/0001` | published V4 (resolve) | LIVE |
| CF deploy, XSUAA, AppRouter, SSO, HANA, A/B, macierz negatywna LIVE | **NOT EXECUTED — BLOCKED** | — |

## Security negative matrix — stan

| Test | Dowód dostępny | Stan |
| --- | --- | --- |
| Anonimowy CAP GET → 401; nieprawidłowy/wygasły JWT, zły audience → 401; brak `KsefOperator` → 403; brak/obce NIP/spółka, para krzyżowa → 0 danych / 403 `requestSend` | `XsuaaJwtODataContractTest` 11/11 (prawdziwy stos SAP, syntetyczny klucz) w 236/236 | OFFLINE PASS · LIVE BLOCKED |
| Brak XSUAA / HANA → startup FAIL; mock auth na CF zabroniony | `CloudIdentityGuardTest`, `CloudPersistenceGuardTest` (w 236/236); start JAR z CAP-07.1 | OFFLINE PASS · LIVE BLOCKED |
| SAP user bez Business Role → RAP bez danych | ABAP-08 2026-11-07 (0/0/0 przed nadaniem roli) | LIVE (historyczny, H01) · przez CF BLOCKED |
| CAP communication user jako UI user | brak trasy approutera do Web API (`xs-app.test.mjs`), `AINV_H01` tylko w CAP | OFFLINE PASS · LIVE BLOCKED |
| SAP WRITE / KSeF SEND | flagi `false` w JAR i mtad, UI `sendInvoice=false` | **NOT EXECUTED** (wymagane) |

## CAP-04D — read-only preflight

| Element | Wynik |
| --- | --- |
| `ApplyKsefResult` | aktywny BDEF `ZR_AINV_INV_H`: `static action ApplyKsefResult parameter ZA_AINV_KSF_REQ result [1] ZA_AINV_KSF_RES`, `internal action ApplyKsefLocked`; projekcja `ZC_AINV_INV_H`: `use action ApplyKsefResult`, `use etag` |
| ETag | `etag master LocalLastChangedAt`, `lock master` |
| Autoryzacja | `define own authorization context { 'Z_AINV_KSF'; }`, `authorization master( global, instance )`; `Z_AINV_KSF` (SUSO) istnieje w `ZAINVOICER` |
| Dane | 4 nagłówki bez zapisów KSeF od seeda (2026-10-26) |
| `ZCS_AINV_H01`, Communication Arrangement, principal, ACTVT=16, zakres NIP/spółka principal, CSRF | **NIE do odczytu przez MCP** (brak typów SCO*/arrangement) → administrator H01 |

Brak POST, brak nadań, brak wyboru faktury. **CAP-04D: BLOCKED** (arrangement/ACTVT16/CSRF niezweryfikowane + osobna zgoda na syntetyczny dokument).

## Risks / Limitations

- Wszystkie wyniki BTP są lokalne/offline; brak jakiegokolwiek LIVE PASS po stronie CF.
- Wariant SSO A niepotwierdzony oficjalnym SAP Help; ryzyko: token exchange może wymagać innego originu/IdP niż domyślny SAP ID service → test #3/#8 rozstrzygnie.
- Drugi business user (B) i dane spoza zakresu A w H01 nie istnieją → test izolacji A/B będzie **BLOCKED** nawet po deployu, dopóki właściciel nie utworzy użytkownika B (bez wymyślania NIP/spółki).
- HANA free tier: automatyczne zatrzymywanie instancji (TO VERIFY) — po restarcie CAP może nie wystartować, gdy HANA stoi (fail-closed, zamierzone).
- `ainv.sap-write.sweeper.enabled: true` w JAR — bez `sap-write` nie wykonuje HTTP; na pustej HDI brak kandydatów.
- F9 z CAP-07.1 (mapowanie timeoutu blokady HANA) nadal niezweryfikowane.

## Next Step

Owner actions (szczegóły w raporcie końcowym sesji i runbooku §1–§2, §11): (1) `cf login --sso` lokalnie przez zainstalowany CLI i target `dev-v2xzij9s_dev`/`dev`; (2) odczyt Entitlements w cockpicie (xsuaa, destination, HANA Cloud free, hdi-shared); (3) warunkowa zgoda na `cf deploy` zakresu z runbooku §4. Potem agent: inventory read-only → deploy → testy runbooku §7.

## Kontynuacja 2026-11-09 — inventory CF po zalogowaniu właściciela (read-only)

Właściciel zalogował się `cf login --sso` we własnym terminalu. Nazwa `dev-v2xzij9s_dev` z cockpitu to nazwa instancji środowiska CF, **nie** nazwa org (`Organization ... not found`). Agent wykonał wyłącznie odczyty (bez `cf env`/service keys):

| Element | Wynik (LIVE, odczyt) |
| --- | --- |
| Org / space | `National Center for Cryptography_dev-v2xzij9s` (GUID `f72a9117-…`) / `dev` (GUID `2e8316e6-…`) |
| Quota | `FREE_QUOTA`: 4 GB pamięci, 20 instancji, 10 tras; **`paid service plans: allowed`** |
| Istniejące | aplikacja `myapp` (128M, nie ruszać), usługi `dev` (abap/free = H01), `default_aicore` (aicore/free) |
| `xsuaa` / `application` | **AVAILABLE, free** |
| `destination` / `lite` | **AVAILABLE, free** |
| `hana` / `hdi-shared` | **BRAK w marketplace** (entitlement „SAP HANA Schemas & HDI Containers” nieprzypisany) |
| `hana-cloud` | tylko `hana` i `relational-data-lake` — **paid**; `hana-free` **BRAK** |
| `postgresql-db` | `standard`/`premium` — paid (poza architekturą ADR-0007) |
| `identity` / `application` | free (nieużywane; nie łączyć z wariantem SSO A) |
| Buildpacki | `sap_java_buildpack_jakarta` **2.76.0** (+2.73/2.72), `nodejs_buildpack` 1.9.6; SapMachine 25 w 2.76 — TO VERIFY przy stagingu |
| Pamięć dla AInvoicer | wolne ~3,9 GB ≥ 1,5 GB (srv 1024M + approuter 256M + deployer 256M) |

Skutek: deploy MTA w obecnej postaci **nie przejdzie** (brak `hana`/`hdi-shared`), a jedyny dostępny plan HANA jest płatny. Plan płatny nalicza opłaty od utworzenia do usunięcia instancji (storage/backup także po zatrzymaniu) — nie ma sposobu na „płatny plan bez płacenia”. Ścieżka bezkosztowa: przypisanie entitlementów free tier `hana-cloud`/`hana-free` (+ `tools`) i `hana`/`hdi-shared` — dostępność `hana-free` w EU20 **TO VERIFY** w Entitlements. Bez niej: identity-smoke (osobna zgoda). Nic nie utworzono.
