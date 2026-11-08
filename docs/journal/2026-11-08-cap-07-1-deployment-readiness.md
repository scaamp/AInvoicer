---
title: "Dziennik — CAP-07.1 Cloud Foundry Deployment Readiness, Business User SSO & Security Validation"
date: 2026-11-08
type: journal
status: zakonczony
tags: [ainvoicer, journal, cap-07, btp, cloud-foundry, hana, xsuaa, approuter, sapui5, mta, security, cap-04d]
---

# Dziennik — CAP-07.1 Deployment Readiness

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Runbook](../guides/BTP_FIRST_DEPLOYMENT_RUNBOOK.md) · [ADR-0007](../decisions/ADR-0007-cap-persistence-hana-cloud.md) · [ADR-0006](../decisions/ADR-0006-btp-identity-xsuaa.md) · [Dziennik CAP-07](2026-11-08-cap-07-btp-identity.md)

## Date

2026-11-08 (Europe/Warsaw).

## Branch

`feature/cap-07-1-deployment-readiness` z `feature/cap-07-btp-identity` (`829725b`). Stos PR: `main` ← PR #1 `feature/cap-06-ui5-09-dual-backend` ← `feature/cap-07-btp-identity` ← CAP-07.1.

## Goal

Gotowość pierwszego kontrolowanego wdrożenia BTP CF z rzeczywistą tożsamością: audyt XSUAA/approutera/MTA, naprawa braków, trwała baza, wariant UI5 CF, specyfikacja SSO H01, pełny build i regresja. Bez deployu, provisioningu, SAP WRITE i KSeF SEND.

## Initial State

- `git fetch`: `main` `141bda4`, CAP-06.1 `ed35935`, CAP-07 `829725b` — zgodne z opisem; drzewo czyste.
- PR #1 (GitHub API; `gh` niezainstalowany): OPEN, `mergeable=true`, `mergeable_state=clean`, head `ed35935`, base `main`, 34 pliki → **READY FOR OWNER MERGE** (bez merge). CAP-07 bez PR.
- Narzędzia: JDK 25.0.4, Maven 3.9.16, Node 24.13 / npm 11.6; **brak** `gh`, `cf`, `btp`, `mbt`, GNU make.
- Działające procesy z poprzednich sesji (nie tej): CAP JAR 127.0.0.1:8080 (PID 37384, `.local/cap061`), UI5 `ui5-rap.local.yaml` ::1:8086, mock ::1:8085. **Nie zatrzymano** — nie należą do bieżącej sesji. Pełny build i `npm ci` wykonano w izolowanej kopii drzewa roboczego (scratchpad).

## Findings (audyt)

| # | Obszar | Ustalenie | Działanie |
| --- | --- | --- | --- |
| F1 | DB / krytyczne | Profil `cf` bez bazy → Spring Boot tworzył cichą `jdbc:h2:mem:<uuid>` (dowód: log Hikari przy starcie JAR z syntetycznym XSUAA) | `CloudPersistenceGuard`, `cds-feature-hana`, HDI w MTA (ADR-0007) |
| F2 | Security | `redirect-uris: https://*.${default-domain}/**` — dowolna aplikacja na współdzielonej domenie CF jako cel przekierowania OAuth | zawężone do hosta approutera; jawny `host` |
| F3 | Build | Approuter pakował `dist` developerski: `dataMode=mock`, `test/`, fixture JSON | `npm run build:cf` + weryfikacja artefaktu |
| F4 | Routing | Model domyślny Legacy (`/sap/...ZC_FI_ACDOCA`, `preload`+`earlyRequests`) i flex/LREP bez trasy w approuterze | wariant CF: `legacy.enabled=false`, bez early requests, `flexEnabled=false`; zakładka Legacy ukryta |
| F5 | JWT | `iss` nie jest walidowany dla XSUAA; `jku` ignorowany w produkcji (źródło java-security 4.1.2) | komentarze testów i ADR-0006 skorygowane; model zagrożeń; bez zmiany walidacji |
| F6 | Packaging | MTAR zawierałby `approuter/node_modules`, testy konfiguracji | `ignore` w MTA (buildpack instaluje z lockfile) |
| F7 | UI5 (zachowane) | AI/Scanner/Voice wołają z przeglądarki `*.cfapps.us10-001…` i `api.openai.com` (placeholder `Bearer sk`, bez klucza) | bez zmian (zakres); raportowane przez build CF jako WARN, opisane |
| F8 | requestSend | 404 vs 403 po odczycie SAP systemową destination = wyrocznia istnienia UUID dla operatora z rolą | ryzyko niskie (losowe UUID), pozostawione, opisane |
| F9 | HANA | Mapowanie timeoutu blokady obejmuje SQLState H2 `HYT00`; odpowiednik HANA niezweryfikowany | ryzyko CAP-07B |

Sprawdzone bez uwag: profil `cf` wymaga dokładnie jednego XSUAA i mock OFF (`CloudIdentityGuard`); mock users nie istnieją na `cf` (Basic → 401); `KsefOperator` = scope bez prefiksu; atrybuty z `xs.user.attributes`; brak/obce atrybuty → 0 rekordów; ≥2 NIP × ≥2 spółki → fail closed i 403 `requestSend`; `requestSend` sprawdza NIP **i** spółkę faktury z RAP; CRUD `SEND_INVOICE` odrzucany; obcy klucz 404.

## Implementation

1. **Trwałość (ADR-0007):** `cds-feature-hana` (runtime), `.cdsrc.json` `[production] db=hana`, `cap-java/db/package.json` + lockfile (wg `cds add hana`; build przez przypięty `cds-dk` zamiast `npx`), `CloudPersistenceGuard`, `IdentitySmokeReadOnlyHandler` (opt-in, 409 `AINV_IDENTITY_SMOKE_READ_ONLY` po autoryzacji). Generator `cds add hana/mta` uruchomiony w kopii scratchpad wyłącznie do porównania.
2. **JWT:** kontrakt XSUAA działa jawnie w trybie identity-smoke (+1 test: operator z rolą nie utworzy historii; anonim 401/403). Komentarze issuer/JKU zgodne ze źródłem biblioteki.
3. **UI5 CF:** `scripts/build-cf.mjs`, `ui5-cf.yaml`, `build-cf.test.mjs` (7), `legacy.enabled` w `KsefCockpit` + widoku, test QUnit.
4. **MTA/approuter:** HDI, `dist-cf`, `ignore`, wąski redirect, flagi env OFF, 1 instancja; testy approutera 4 → 7.
5. **Research:** źródło `java-security` 4.1.2 (JwtValidatorBuilder, XsuaaJwtSignatureValidator), CHANGELOG/wydania do 4.3.1; dokumentacja approutera (SAP-docs/btp-cloud-platform). SAP Help ABAP Environment (*Routing via Destination*, *SAML Bearer*) — **nieczytelne** (pusta powłoka JS).
6. **Dokumentacja:** runbook, ADR-0007, korekty ADR-0006, BTP_IDENTITY_SETUP, RAP_UI_LIVE_CONNECTION, STATUS, ROADMAP, README.

## Changed Files

- `cap-java/srv/pom.xml`, `cap-java/.cdsrc.json`, `cap-java/db/package.json`, `cap-java/db/package-lock.json`
- `cap-java/srv/src/main/java/customer/cap_java/security/CloudPersistenceGuard.java`, `IdentitySmokeReadOnlyHandler.java` (nowe)
- `cap-java/srv/src/test/java/customer/cap_java/security/CloudPersistenceGuardTest.java` (nowy), `XsuaaJwtODataContractTest.java`
- `sapui5/ainvoicer/scripts/build-cf.mjs`, `scripts/build-cf.test.mjs`, `ui5-cf.yaml` (nowe); `package.json`, `.gitignore`, `webapp/controller/KsefCockpit.js`, `webapp/view/Main.view.xml`, `webapp/test/unit/controller/Main.controller.js`
- `mta.yaml`, `approuter/test/xs-app.test.mjs`
- Dokumentacja: `docs/guides/BTP_FIRST_DEPLOYMENT_RUNBOOK.md`, `docs/decisions/ADR-0007-…` (nowe), ADR-0006, BTP_IDENTITY_SETUP, RAP_UI_LIVE_CONNECTION, STATUS, ROADMAP, README, ten dziennik.
- Wygenerowane, ignorowane, nie w Git: `cap-java/db/src/gen/`, `sapui5/ainvoicer/build/`, `dist-cf/`, MTAR w scratchpad.

## Commands Executed

```powershell
git fetch --all --prune; GitHub API pulls/1 (curl, bez tokenu)
npx cds add hana ; npx cds add mta ; npx cds build --for hana --production    # kopia scratchpad
mvn -B test -Dtest=CloudPersistenceGuardTest,CloudIdentityGuardTest,XsuaaJwtODataContractTest,XsSecurityDescriptorTest   # drzewo główne, bez clean
mvn -B clean verify '-Dcds.cds.skipVersionCheck=true'                         # izolowana kopia, z repackage
java -jar cap-java-exec.jar (default / cf bez bindingu / cf + syntetyczny XSUAA)  # porty 18081-18083, procesy własne zatrzymane
npm run build:cf ; npm run test:cf-build ; npm run test:runner                # sapui5 (drzewo główne)
npm ci ; npm run build ; test:runner ; test:cf-build ; fiori run ui5-mock :8095 ; browser-test qunit/opa   # sapui5, izolowana kopia
npm ci ; npm run test:config ; approuter 23.4.0 runtime :5099 (syntetyczny XSUAA)   # approuter
mbt 1.2.49 (npm, scratchpad): build → brak make; module-build -m srv,db-deployer,approuter -g ; assemble
MCP ABAP FS h01: odata_resolve_service ×2, odata_get_metadata, odata_query (read-only), get_object_info Z_AINV_KSF
```

## Test Results

| Zakres | Wynik | Kategoria |
| --- | --- | --- |
| Maven pełny `clean verify` (bez `repackage.skip`) | **236/236 PASS**, BUILD SUCCESS 1:31 min, `cap-java-exec.jar` 75 MB | LOCAL MOCK + OFFLINE JWT |
| JAR: MANIFEST | `JarLauncher`, `Start-Class customer.cap_java.Application`, Build-Jdk-Spec 25, Spring Boot 4.1.1 | LOCAL |
| JAR: biblioteki | `cds-feature-identity` 5.2.0, `resourceserver-security-spring-boot-starter`/`java-security` 4.1.2, `cds-feature-hana` 5.2.0, `ngdbc` 2.30.11, H2; brak bibliotek testowych | LOCAL |
| JAR start `default` | start 7,5 s; `$metadata`/status 401, `privileged` 401, `/h2-console/` 401 | LOCAL |
| JAR start `cf` bez bindingu | exit 1: `requires exactly one XSUAA service binding (found 0)` | LOCAL |
| JAR start `cf` + syntetyczny XSUAA, bez HANA | exit 1: `requires durable SAP HANA Cloud persistence (found database 'H2')`, bez URL JDBC w komunikacie | LOCAL |
| `XsuaaJwtODataContractTest` | 11/11 PASS (w tym smoke: operator 409, anonim odmowa) | OFFLINE JWT CONTRACT |
| `CloudPersistenceGuardTest` / `CloudIdentityGuardTest` | 5/5 / 4/4 PASS | OFFLINE |
| SAPUI5 `npm ci` + build (kopia) | PASS | LOCAL |
| SAPUI5 `build:cf` | PASS, 50 plików, `dataMode=rap` w manifest i `Component-preload.js`, 0 testów/fixture/credentiali; WARN 3 zewnętrzne hosty AI | LOCAL |
| `test:cf-build` / `test:runner` | 7/7 / 3/3 PASS | LOCAL |
| QUnit / OPA (własny mock :8095) | **77/77 (442)** / **13/13 (80)** PASS | LOCAL MOCK |
| Approuter `npm ci` + `test:config` | 7/7 PASS | OFFLINE |
| Approuter runtime (syntetyczny XSUAA) | `/index.html`, CAP, `/rap-ui`, `/sap/*`, `/test-resources/*` → strona logowania (`oauth/authorize`); XHR CAP 401; `/resources/` 200 z CDN; 0 wywołań backendów; `JSESSIONID` HttpOnly; Secure | LOCAL CONFIG |
| `mbt build` | **BLOCKED: brak GNU make** | — |
| `mbt module-build` (3 moduły) + `mtad-gen` + `assemble` | PASS → `ainvoicer_0.7.1.mtar` 67 MB: JAR, HDI `src/gen` (16 artefaktów), approuter z `dist-cf` (bez `node_modules`/testów), `xs-security.json`; skan sekretów 0 | LOCAL |
| LIVE XSUAA / CF deploy / SSO H01 | **NOT EXECUTED** (brak deployu i dostępu) | — |
| BTP entitlementy | **NOT VERIFIED** (brak CLI/cockpitu) | — |

## CAP-04D — read-only preflight (MCP ABAP FS, h01)

| Element | Wynik |
| --- | --- |
| `ZAPI_AINV_INV_H_O4/0001` | published, V4, metadata 200, hash `fff24256…` (bez zmian) |
| `ZUI_AINV_INV_H_O4/0001` | published, V4 (resolve OK) |
| READ nagłówków | 4 (2 OUTBOUND, 2 INBOUND), `KsefNumber` puste, `KsefWriteRequestId` zerowe, `LocalLastChangedAt` 2026-10-26T17:28:46.662912Z — brak zapisów od seeda |
| `Z_AINV_KSF` | istnieje (SUSO, `ZAINVOICER`) |
| `ZCS_AINV_H01`, arrangement, principal, nadanie ACTVT16, zakres NIP/spółka principal | **NIE do odczytu przez MCP** → administrator |
| `ApplyKsefResult` | brak w znormalizowanym modelu MCP (akcje nieraportowane) — niezweryfikowane w tej sesji |
| CSRF | niezweryfikowany (narzędzie bez nagłówków); CAP `csrf.enabled: true` |

Brak POST, brak nadań, `ainv.sap-write.enabled=false`.

## Risks / Limitations

- Brak LIVE: XSUAA, approuter na CF, SSO H01, HANA/HDI — wszystkie wyniki są lokalne/offline.
- Wybór wariantu SSO (A/B) i nazwy pól Destination/Communication Arrangement wymagają SAP Help, którego agent nie odczytał.
- `mbt build` (make) nie wykonany — MTAR złożony równoważną ścieżką `module-build` + `assemble`.
- Zachowanie HANA: `lock()`/CAS, mapowanie timeoutów, outbox po restarcie — do CAP-07B.
- Funkcje AI/Scanner/Voice na CF nie działają (zewnętrzne hosty, brak tras).
- npm tooling UI5: 74 advisories (bez zmian, narzędzia developerskie).

## Next Step

Właściciel: (1) merge PR #1, review stacked PR CAP-07 i CAP-07.1; (2) runbook §1–2: potwierdzić subaccount/region H01 vs CF i entitlementy (xsuaa, destination, HANA Cloud, hdi-shared); (3) po zgodzie `mbt build` + `cf deploy`, role collection A/B, Destination `AINV_H01_UI_USER` wg wariantu; (4) przekazać URL approutera do testów runbooku §7.
