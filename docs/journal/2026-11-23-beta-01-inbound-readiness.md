---
title: "Dziennik — BETA-01: kandydat Beta 0.1 KSeF INBOUND"
date: 2026-11-23
type: journal
status: zakonczony
tags: [ainvoicer, journal, beta, inbound, hana, sapui5, security]
---

# Dziennik — BETA-01 (KSeF INBOUND Beta 0.1)

[Project Home](../README.md) · [STATUS](../STATUS.md) · [BETA_ROADMAP](../BETA_ROADMAP.md) ·
[Raport BETA-01](../guides/BETA_01_INBOUND_READINESS.md)

## Date

2026-11-23 (Europe/Warsaw), wykonanie rzeczywiste tego dnia.

## Branch

`feature/beta-01-inbound-readiness` (worktree `C:\Users\jkupi\Documents\AInvoicer-beta01`) z `origin/main=603e171`.
Workstreamy: `beta-01/in-05-storage` (Agent B, worktree `…-beta01-b`), `beta-01/ui5-inbox` (Agent C, `…-beta01-c`);
scalone `--no-ff` po review. Checkout właściciela (`feature/abap-08-live-integration`, czysty) nietknięty.

## Goal

Z prototypu do technicznego kandydata **Beta 0.1 — KSeF INBOUND**: read-only CF PRECHECK (lub BLOCKED_AUTH), R-1,
G1 offline, wiring prywatnego storage, pipeline offline, minimalny manual import, opt-in build Inboxa na CF, bezpieczny
download XML, testy A/B, pełna regresja, `BETA_ROADMAP`, jeden PR bez merge. Zakaz LIVE poza odczytem metadanych CF.

## Initial State

`git fetch`: `origin/main` nadal `603e171b5467f6d1600193eed666a91e9297b3a9`; CI run 43739912629 tego SHA
sprawdzony przez API GitHub — **SUCCESS** (niezależnie od relacji właściciela). Przeczytano AGENTS/CLAUDE, STATUS,
ROADMAP, przewodniki STOR-04/STOR-04R, INBOUND, Inbox UI5, INT-02, IN-03B, CAP-07E, dziennik ORCH-09 i kod
(`KsefInboundConfiguration`, `LocalTestDocumentStore`, `HanaKsefDocumentStore`, sink, synchronizacja, handler Overview,
`build-cf.mjs`, `xs-app.json`). Potwierdzono: HANA store bez beana, guard CF bezwarunkowy, UI CF wymusza Inbox `disabled`.
Brak `gh` CLI w środowisku. KSEF-IN-04 LIVE PASS — **OWNER-REPORTED** (szczegóły w raporcie), nie powtarzano.

## Implementation

- **A (orkiestrator):** CF — `cf version/plugins/target/orgs` wyłącznie na istniejącej sesji lokalnej: CLI 7.8.0, API US10,
  brak org/space, brak pluginu MultiApps → **BLOCKED_AUTH** (bez `cf login`, `cf api`, `cf env`). R-1 → **R1_BLOCKED**.
  G1: `npm ci --prefix deploy` i `--prefix cap-java` w izolowanym worktree; stary G1 63/63, STOR-04 86/86, task JSON
  generowany (runtime `6ebdfd14…`, 4071), **niewykonany**.
- **B (KSEF-IN-05):** opt-in wiring `InboundDocumentStorage`, manual import za flagą, download XML jako media stream
  CAP za flagą, wspólny `InboundGrantScope`, testy pipeline/security. [Przewodnik](../guides/KSEF_IN_05_PRIVATE_INBOUND_STORAGE.md).
- **C (UI5):** `build:cf:inbox-beta` (+ `--with-xml-download`), weryfikator artefaktu beta, stany UX 401/403/5xx/login,
  przycisk Download, testy XSS. [Przewodnik](../guides/BETA_01_UI5_INBOX_CF.md).
- **Review/integracja:** przegląd diffów B i C (kontrakt download, kolejność kontroli, brak zmian G1/LAB/OUTBOUND,
  domyślny `build:cf` bez zmian); dodany test łączony `InboundPipelineToODataTest` (pipeline → bean storage → OData).
  Pierwsza wersja testu odtwarzała okno z metadanymi sprzed HWM — walidacja zakresu poprawnie zwróciła
  `KSEF_INBOUND_CORRUPT_RESPONSE_OUT_OF_RANGE`; scenariusz zmieniono na utratę checkpointu (najgorszy przypadek).

## Changed Files

Kod: patrz przewodniki B/C oraz `cap-java/srv/src/test/java/customer/cap_java/InboundPipelineToODataTest.java`.
Dokumentacja: `docs/BETA_ROADMAP.md`, `docs/guides/BETA_01_INBOUND_READINESS.md`, ten dziennik, nowe wpisy na górze
`docs/STATUS.md`, `docs/ROADMAP.md`, `docs/README.md` (historia zachowana). Bez zmian: `db/*.cds`, LAB, `approuter/`,
`mta.yaml`, `deploy/`, OUTBOUND. Wygenerowane `node_modules/`, `target/`, `dist-*`, MTAR — ignorowane, niecommitowane.

## Commands Executed

Bez `CF_TRACE`/`DEBUG`. Logi w katalogu tymczasowym sesji (poza Git). Skrypt regresji w kolejności CI:
`npm ci` (UI5, storage), `validate:smoke`, `check-secrets.mjs`, `build:btp:smoke`, `build:btp:hana`, `verify:hana`,
`verify:stor04-release`, `hana-it-runner.mjs build|test`, `runner.jar --self-test`, `npm test --prefix deploy/storage`,
UI5 `build`, `test:cf-build`, `test:runner`, `build:cf:inbox-beta`, `serve-mock` + `npm test`, `test:inbox:e2e`,
`npm test --prefix deploy`, `git diff --check`.

## Tests & Results

Pełna tabela: [raport §11](../guides/BETA_01_INBOUND_READINESS.md#11-czy-wykonano-wszystkie-testy). Skrót:
Maven **707/0/0/6**, JAR security 10/10, deploy **199 + 1 SKIP Go** (G1 63, STOR-04 86), QUnit **121/732**, OPA **24/193**,
Inbox E2E **16/77**, cf-build 23/23, runner 8/8, R2 6/6 + self-test, Azurite 6/6, oba MTAR, stor04 +1/0/0, secrets PASS,
diff-check PASS. Wszystko offline. **LIVE: NIE WYKONANO.**

## Problems Encountered

- G1 61/63 z poprzedniego preflightu: przyczyna potwierdzona — brak `deploy/node_modules` (`terser`) oraz
  `cap-java/node_modules` (test niezależnej kompilacji CDS). Po `npm ci` w izolowanym worktree 63/63.
- Pierwszy bieg `mvn verify` w tle został przerwany przed testami — nie liczony; wynik pochodzi z `build:btp:smoke`.
- `test:cf-build` w regresji: 1 skip, bo artefakt beta budowano po tym kroku; po buildzie 23/23.
- Środowisko nie ma `gh` CLI — PR tworzony przez GitHub API (szczegóły poniżej).

## Decisions Made

- Download jako encja `@cds.persistence.skip` (brak widoku HDI → G1 bez zmian), nie `@RestController`.
- Storage/download zablokowane na CF przez `CloudHanaReadinessGuard` do osobnej decyzji; guard INBOUND bez zmian.
- Manual import bez publicznej akcji; filtr `ksefNumber` wymaga potwierdzenia LIVE.
- Beta UI jako osobny opt-in build; `mta.yaml` nadal pakuje domyślny `dist-cf`.

## Current State

Techniczny kandydat Beta 0.1 **OFFLINE**. CF PRECHECK **BLOCKED_AUTH**, R-1 **R1_BLOCKED**, CURRENT G1 gotowy do zgody.
**Nie READY FOR LIVE ACCEPTANCE, nie USER-READY.** PR i CI dokładnego HEAD — w raporcie sesji/komentarzu PR.

## Next Steps

1. Review i merge PR przez właściciela (bez auto-merge).
2. Właściciel odnawia sesję CF EU20 → read-only PRECHECK i decyzja R-1.
3. Kolejne zgody wg [raportu §13](../guides/BETA_01_INBOUND_READINESS.md#13-jakie-dokładnie-kolejne-zgody-musi-wydać-właściciel).
