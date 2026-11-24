---
title: "Dziennik — BETA-02 Live Environment Readiness & First User E2E (przygotowanie)"
date: 2026-11-24
type: journal
status: zablokowany
tags: [ainvoicer, journal, beta, cf, hana, ksef, inbound, uat]
---

# Dziennik — BETA-02

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) ·
[Gotowość LIVE](../guides/BETA_02_LIVE_RELEASE_READINESS.md) · [UAT](../guides/BETA_02_MANUAL_UAT.md)

## Date

2026-11-24 (Europe/Warsaw). Wykonawca: Claude Code (Opus 5.5) na zlecenie właściciela.

## Branch

`main` (merge PR #29) → `feature/beta-02-live-readiness` z `aa30143`, worktree `C:\Users\jkupi\Documents\AInvoicer-beta02`.

## Goal

Doprowadzić AInvoicer do stanu, w którym właściciel może wykonać pierwszy E2E: merge PR #29, read-only CF PRECHECK EU20,
przygotowanie HANA (CURRENT G1, STOR-04), kompletny Beta MTAR, twardy budżet HTTP manual importu, lokalny UAT.
Bez operacji LIVE zmieniających stan (HANA SQL, task G1, HDI, CF deploy/restart, KSeF, SAP H01 WRITE).

## Initial State

Lokalny checkout na `main` = `origin/main` = `603e171`, czyste drzewo (snapshot startowy podawał inną gałąź —
zweryfikowano Gitem). `gh` niedostępny — odczyty GitHub przez publiczne REST API. Relacja właściciela: wykonał
`cf login --sso` (EU20); weryfikacja agenta: patrz Implementation §2.

## Implementation

1. **PR #29:** HEAD `845406c`, `mergeable_state=clean`, CI run `45234799772` SUCCESS na dokładnym HEAD; `main` bez
   branch protection/required checks. Review: grant w jednym CQN przed `store.get`, `sizeBytes>0` + długość + SHA-256,
   wspólny `InboundGrantScope`, brak `xmlStorageRef` w Overview, brak zmian HDI/MTA/OUTBOUND, skan wzorców sekretów
   (trafienia = lokalne hasła mock w testach). Merge `--no-ff` „Merge PR #29: …”, push `603e171..aa30143` (bez force);
   PR `merged=true`; CI main `45369224329` SUCCESS. Baseline Maven na `main`: 715/0/0/6.
2. **CF PRECHECK:** `cf version` (7.8.0), `cf api`/`cf target` (US10, bez org/space), `cf plugins` (brak MultiApps),
   brak `CF_HOME` (proces/User/Machine), jedyny `%USERPROFILE%\.cf`, brak portable `cf8`. Werdykt **BLOCKED_ENV**;
   bez logowania, zmiany targetu i odczytu tokenów. R-1 → **R1_BLOCKED**.
3. **CURRENT G1 offline:** `--task-json` → `runtimeSourceSha256=6ebdfd14…5785`, `commandLength=4071`; zgodne.
4. **Twardy budżet HTTP:** `BudgetedKsefTransport` (świeży `HttpClient` na żądanie + `SSLContext` z jednym `SSLEngine`,
   allowlista, budżet 1..9, termin, fencing, STOP po non-2xx/I/O); `InboundManualImport.RunClients` (klienci per
   przebieg); bean produkcyjny buduje SDK/auth/klienta na budżetowanym transporcie. Test kontrolny potwierdził replay
   domyślnego JDK (≥2 żądania), osłona — dokładnie 1.
5. **Beta MTAR:** `deploy/scripts/beta-release.mjs` (+ test, skrypty `build:btp:beta`/`verify:beta`, krok CI).
6. **Lokalny UAT:** tryb `-Dainv.inbox.local.uat-xml=true` w `InboundLocalServer` + `InboundFixtures.seedXml` (prawdziwy
   adapter magazynu na H2), `prepare-inbox-local.mjs --with-xml-download`, `scripts/beta-uat.mjs` (`start:beta-uat`,
   `--smoke`), krok CI smoke.
7. **Środowisko:** pierwszy Maven w głównym checkoutcie przerwany przez równoległy build Java LS VS Code w tym samym
   `target/` (`NoSuchFileException`) — prace przeniesiono do osobnego worktree.

## Changed Files

- `cap-java/srv/src/main/java/customer/cap_java/ksef/BudgetedKsefTransport.java` (nowy) — transport z budżetem.
- `…/ksef/KsefInboundConfiguration.java` — bean manual importu na budżetowanym transporcie, walidowane limity.
- `…/ksef/inbound/sync/InboundManualImport.java` — `RunClients`/`Clients`, fencing per przebieg.
- `cap-java/srv/src/main/resources/application.yaml` — `max-http-requests: 9`, `max-duration: PT90S`.
- Testy: `BudgetedKsefTransportTest` (nowy, 10), `InboundManualImportTest` (+2), `KsefInboundConfigurationTest` (limity).
- Harness testowy: `InboundLocalServer`, `InboundFixtures` (opt-in XML UAT).
- `sapui5/ainvoicer/scripts/beta-uat.mjs` (nowy), `prepare-inbox-local.mjs`, `package.json` (`start:beta-uat`).
- `deploy/scripts/beta-release.mjs`, `deploy/test/beta-release.test.mjs` (nowe), `deploy/test/ci.test.mjs`, `deploy/package.json`.
- `.github/workflows/ci.yml` — Beta MTAR build/verify, smoke UAT.
- Dokumentacja: `docs/guides/BETA_02_LIVE_RELEASE_READINESS.md`, `docs/guides/BETA_02_MANUAL_UAT.md` (nowe), ten dziennik,
  `STATUS`, `ROADMAP`, `BETA_ROADMAP`, `README`, `BETA_01_INBOUND_READINESS` (nota), `KSEF_IN_05_PRIVATE_INBOUND_STORAGE` (nota).
- Wygenerowane (niecommitowane): `deploy/build/**`, `sapui5/ainvoicer/build/**`, `dist-cf*`.

## Commands Executed

`git fetch/merge --no-ff/push origin main`; GitHub REST (`pulls/29`, `actions/runs/*`, `branches/main`, `commits/*`);
`cf version|api|target|plugins`; `node deploy/scripts/hdi-catalog-readonly.cjs --task-json`; `mvn -B clean verify`;
`mvn test -Dtest=…`; `npm test --prefix deploy`; `npm run test:inbox:e2e`; `node scripts/beta-uat.mjs --smoke`;
łańcuch: `validate:smoke`, `check-secrets`, `build:btp:smoke`, `build:btp:hana`, `verify:hana`, `verify:stor04-release`,
`build:btp:beta` ×2, `verify:beta` ×2, `hana-it-runner.mjs build|test`, `npm test --prefix deploy`.

## Results

| Weryfikacja | Wynik |
| --- | --- |
| Maven (gałąź) | **727 / 0 F / 0 E / 6 S** (main: 715/0/0/6) |
| Celowane: transport + manual import + konfiguracja | 42/42 |
| `deploy` npm test | 206: 205 PASS, 1 SKIP (pierwszy przebieg przed buildem: 203/3 SKIP) |
| hana-it runner | 6/6 |
| Inbox E2E (CAP/UI5 HTTP) | PASS (22/22 asercji) |
| Lokalny UAT smoke | 10/10 PASS |
| Secret scan | PASS (2754 plików, heurystyka) |
| MTAR 0.7.2 / 0.7.4 / 0.7.5 / 0.7.6 | PASS; 0.7.4 +1/0/0; SHA w [§6](../guides/BETA_02_LIVE_RELEASE_READINESS.md#6-beta-cf-release-preparation-offline) |
| CF PRECHECK EU20 | **BLOCKED_ENV** |
| R-1 | **R1_BLOCKED** |
| LIVE (G1/HDI/deploy/KSeF/SAP) | **NOT EXECUTED** |

## Risks / Open Issues

- Sesja EU20 niewidoczna dla agenta; brak pluginu MultiApps w widocznym CLI.
- R-1: zakres przetwarzania zasobów bez `-r` niepotwierdzony na kontrolerze EU20.
- Budżet HTTP udowodniony offline; LIVE i globalny budżet między instancjami nadal otwarte.
- Wywołujący manual import na CF niezaimplementowany (kontrakt §5.1); guard `CloudHanaReadinessGuard` blokuje storage/download.
- Artefakty z tej sesji pochodzą z gałęzi — do zgody wymagany rebuild z `main` po merge BETA-02.

## Next Step

Właściciel udostępnia agentowi sesję EU20 (ścieżka `CF_HOME` i `cf.exe` z terminala z sesją, plugin MultiApps) →
powtórzenie read-only PRECHECK w zatwierdzonym zakresie → decyzja R-1 → zgoda na CURRENT G1.
