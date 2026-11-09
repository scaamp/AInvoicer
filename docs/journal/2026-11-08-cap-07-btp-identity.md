---
title: "Dziennik — CAP-07 BTP Identity & Secure Application Routing"
date: 2026-11-08
type: journal
status: zakonczony
tags: [ainvoicer, journal, cap-07, xsuaa, approuter, security, cap-java, cap-04d]
---

# Dziennik — CAP-07 BTP Identity

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [ADR-0006](../decisions/ADR-0006-btp-identity-xsuaa.md) · [Przewodnik BTP](../guides/BTP_IDENTITY_SETUP.md)

## Date

2026-11-08 (Europe/Warsaw).

## Branch

`feature/cap-07-btp-identity`, utworzony z `origin/feature/cap-06-ui5-09-dual-backend` (`ed35935`), bo PR #1 jest nadal otwarty. Stacked PR z bazą `feature/cap-06-ui5-09-dual-backend`.

## Goal

Warstwa uwierzytelniania/autoryzacji SAP BTP dla CAP Java i routing SAPUI5, bez mock fallbacku na CF, bez rozszerzania zakresu NIP/CompanyCode, bez deploymentu, SAP WRITE i KSeF SEND.

## Initial State

- `git fetch`: `origin/main` = `141bda4`, `origin/feature/cap-06-ui5-09-dual-backend` = `ed35935`, drzewo czyste.
- PR #1 (GitHub API, `gh` niezainstalowany): `open`, `mergeable=true`, `mergeable_state=clean`, head `ed35935`, base `main`, 34 pliki (+1209/−42). Brak blockerów w diffie → **READY FOR OWNER MERGE**; merge NIE wykonany.
- Brak `cf`, `btp`, `mbt` CLI → entitlementy i usługi subaccount **niezweryfikowane**.
- Na 127.0.0.1:8080 działał lokalny CAP z CAP-06.1 (`.local/cap061/application-local.yaml`), na ::1:8086 serwer UI5, na 8085 serwer mock. Zatrzymanie CAP zostało zablokowane przez klasyfikator uprawnień; procesów nie zatrzymano.

## Implementation

1. **Weryfikacja źródeł:** dokumentacja CAP (security, authentication, cap-users, versions), Maven Central (POM `cds-starter-cloudfoundry`/`cds-feature-identity` 5.2.0, `resourceserver-security-spring-boot-starter` 4.1.2, `cds-feature-xsuaa` ostatnio 2.10.7), bytecode `cds-feature-identity` i `cds-framework-spring-boot` 5.2.0 (mapowanie scope/atrybutów, warunki aktywacji, źródła bindingów `VCAP_SERVICES`/`cds.environment.local.defaultEnvPath`), npm `@sap/approuter` 23.4.0.
2. **Decyzja:** XSUAA, pojedyncze zależności, deskryptor z wymaganymi atrybutami (ADR-0006).
3. **CAP:** `cds-feature-identity` (runtime) + `resourceserver-security-spring-boot-starter`; test `java-security-test`. BOM `java-bom` z właściwości `sap.security.version`.
4. **`KsefScopeUserInfoProvider`:** fail closed dla niejednoznacznych par (≥2 NIP i ≥2 spółki).
5. **`CloudIdentityGuard`:** na cf/cloud/prod/production lub z `VCAP_APPLICATION` wymaga mock OFF, aktywnego auth i dokładnie jednego bindingu XSUAA.
6. **`xs-security.json`:** porównany z `cds compile srv --to xsuaa`; korekta `valueRequired` i powiązania atrybutów z rolą.
7. **Approuter + `mta.yaml`:** deklaratywnie; walidacja statyczna i rzeczywistym approuterem z syntetycznym bindingiem.
8. **CAP-04D preflight** read-only przez MCP ABAP FS.

## Changed Files

- `cap-java/pom.xml`, `cap-java/srv/pom.xml` — zależności identity/test.
- `cap-java/xs-security.json` — nowy deskryptor XSUAA.
- `cap-java/srv/src/main/java/customer/cap_java/security/KsefScopeUserInfoProvider.java`, `CloudIdentityGuard.java` — nowe.
- `cap-java/srv/src/test/java/customer/cap_java/security/*Test.java` (5 klas) — nowe; `ksef/KsefSendRequestTest.java` (+1 test); `src/test/resources/application-test.yaml` (+2 syntetyczne tożsamości).
- `approuter/package.json`, `package-lock.json`, `xs-app.json`, `.gitignore`, `test/xs-app.test.mjs` — nowe.
- `mta.yaml` — nowy, niewdrożony.
- Dokumentacja: ADR-0006, przewodnik BTP, ten dziennik, STATUS, ROADMAP, RAP_UI_LIVE_CONNECTION, README.

## Commands Executed

```powershell
git fetch origin; git switch -c feature/cap-07-btp-identity origin/feature/cap-06-ui5-09-dual-backend
npx cds compile srv --to xsuaa                       # cap-java, cds-dk 10.2.0
mvn clean verify '-Dcds.cds.skipVersionCheck=true' '-Dmaven.clean.failOnError=false' '-Dspring-boot.repackage.skip=true'
node --test approuter/test/xs-app.test.mjs
npm install (approuter) ; skrypt smoke approutera ze scratchpada (syntetyczny VCAP_SERVICES, port 5099)
npm run build ; npm run test:runner ; npm run test:unit ; npm run test:opa   # sapui5/ainvoicer
```

Pierwszy `mvn clean verify` padł na `Failed to delete srv/target/cap-java-exec.jar` (plik trzymany przez działający CAP na 8080). Dlatego build bez repackage JAR; testy na świeżo skompilowanych klasach. **Pełny `clean verify` z JAR-em do powtórzenia po zatrzymaniu procesu na 8080.**

## Test Results

| Zakres | Wynik | Kategoria |
| --- | --- | --- |
| Maven (przed zmianami kodu, po samych zależnościach) | 203/203 PASS | LOCAL MOCK |
| Maven pełny | **230/230 PASS** (+27), BUILD SUCCESS, 1:15 min | LOCAL MOCK + OFFLINE JWT |
| `XsuaaJwtODataContractTest` | 10/10 PASS; log: „Activated identity security configuration based on XSUAA” | OFFLINE JWT CONTRACT |
| `CloudIdentityGuardTest` | 4/4 PASS | OFFLINE |
| `KsefScopePairODataTest` + `KsefScopeUserInfoProviderTest` + `requestSend` cross | 7 + 4 + 1 PASS | LOCAL MOCK |
| `XsSecurityDescriptorTest` | 1/1 PASS | OFFLINE |
| Approuter kontrakt statyczny | 4/4 PASS | OFFLINE |
| Approuter 23.4.0 runtime (syntetyczny XSUAA) | start OK; CAP/RAP/UI → strona logowania do `/oauth/authorize`; `/resources/` publiczne 200 | LOCAL CONFIG |
| SAPUI5 build / runner | PASS / 3/3 | LOCAL |
| QUnit / OPA | 76/76 (438) / 13/13 (80) | LOCAL MOCK — na istniejącym serwerze 8085 |
| `npm ci` (UI5) | NOT EXECUTED — node_modules używany przez działający serwer 8086 | — |
| LIVE BTP IAS/XSUAA | **BLOCKED** (brak usług/deployu) | — |
| Dual-backend browser LIVE | NOT RE-EXECUTED (CAP 8080 to stary JAR; zmiany UI5 brak) | — |

Macierz bezpieczeństwa (offline JWT, profil `cf`): brak Authorization 401; nieprawidłowy JWT 401; wygasły 401; obcy podpis 401; obcy JKU 401; obca aplikacja/audience 401; Basic mock 401; bez `KsefOperator` 403 (READ i `requestSend`); bez atrybutów 0; obcy NIP/spółka 0; para krzyżowa 0; dozwolony zakres tylko własne rekordy i count 1; obcy `operationId` 404; `$metadata` z tokenem 200, bez 401.

**Zaobserwowane:** obcy `iss` przy poprawnym podpisie kluczem z JKU domeny bindingu → **200**. SAP java-security 4.1.2 nie waliduje osobno `iss` dla XSUAA; zaufanie wynika z JKU (`uaadomain`) + podpis + audience. Utrwalone testem, opisane w ADR-0006. Bez klucza XSUAA claim nie da się podmienić.

## CAP-04D — read-only preflight (MCP ABAP FS, h01)

| Element | Wynik |
| --- | --- |
| Binding `ZAPI_AINV_INV_H_O4` | **potwierdzony**: published, V4, 0001, metadata 200 (hash `fff24256…`) |
| READ + `LocalLastChangedAt` | **potwierdzony**: 4 nagłówki, `KsefNumber` puste, `KsefWriteRequestId` zerowe, `LocalLastChangedAt` 2026-10-26 (brak zapisów) |
| `Z_AINV_KSF` | **potwierdzony**: ACTVT 01/02/03/06/16, pola `ZAINV_NIP`/`ZAINV_BUK`, RT `ZAINV_RT_READ` |
| `ApplyKsefResult` | w imporcie EDMX CAP (2026-10-28); znormalizowany model MCP nie pokazuje akcji → **niezweryfikowany w tej sesji** |
| `ZCS_AINV_H01` | w eksporcie Git (`abap/src/zcs_ainv_h01.sco1.xml`); MCP nie obsługuje typu SCO1 → **niezweryfikowany live** |
| Arrangement, communication principal, nadanie ACTVT16 | **wymaga administratora** (brak odczytu przez MCP) |
| CSRF Web API | **niezweryfikowany** (narzędzie nie wysyła nagłówków); CAP ma `csrf.enabled: true` |

Nie wykonano POST, nie nadano ACTVT16, `ainv.sap-write.enabled` pozostaje `false`.

## Risks / Limitations

- LIVE XSUAA/approuter/CF nieprzetestowane; offline harness używa testowej fabryki JKU dla localhost.
- Business-user SSO do H01 z CF: mechanizm niepotwierdzony (SAP Help nieczytelny w sesji) → BLOCKED.
- Profil `cf` bez bazy danych; SapMachine 25 w buildpacku niepotwierdzona.
- Model tuple NIP×spółka nie istnieje — wielozakresowi operatorzy są blokowani (fail closed).
- Build UI5 zawiera `test/` — wyłączyć z artefaktu przed produkcją.

## Next Step

Właściciel: review/merge PR #1, następnie review stacked PR CAP-07. Zatrzymać lokalny CAP na 8080 i powtórzyć `mvn clean verify` z JAR-em. CAP-07B: entitlementy, decyzja DB, deploy po zgodzie, role collection, testy §9 przewodnika.
