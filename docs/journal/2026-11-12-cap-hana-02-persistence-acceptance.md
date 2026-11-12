---
title: "Dziennik — CAP-HANA-02 audyt read-only i plan akceptacji persystencji HANA"
date: 2026-11-12
type: journal
status: w-trakcie
tags: [ainvoicer, journal, cap-hana-02, hana, hdi, persistence]
---

# Dziennik — CAP-HANA-02: audyt read-only i plan akceptacji

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) ·
[Przewodnik CAP-HANA-02](../guides/CAP_HANA_02_PERSISTENCE_ACCEPTANCE.md)

## Date

2026-11-12 (Europe/Warsaw), Claude Code.

## Branch

`feature/cap-hana-02-persistence-acceptance` z `origin/main` `bd38156`, worktree `D:\AInvoicer-cap-hana-02`.

## Goal

Faza 1 read-only (main/CI/CF/HANA/HDI, flagi, połączenie, metoda katalogowego SQL, ocena harnessu, WARN outbox)
oraz Faza 2 — plan testów do zatwierdzenia. Zakończyć przed pierwszym zapisem do HANA; bez DML/restartu/tasków.

## Initial State

`D:\AInvoicer` na `main` `4186081`, 2 commity za `origin/main`; niecommitowana zmiana właściciela w `.env.example`
i nieśledzony `cap-java/db/documents.cds` (BLOB-LAB-01) — pozostawione bez zmian, branch utworzony w osobnym worktree.

## Implementation

1. Odczyty CF (CLI 8.19.0, API 3.235.0, target `dev`): `apps`, `services`, `mta-ops`, `mta ainvoicer`, `app ainvoicer-srv`,
   `ssh-enabled`, `space-ssh-allowed`, `service` (HANA/HDI), `service-keys ainvoicer-db`; CAPI: parametry HANA
   (filtrowane do `serviceStopped`/allowlist/wersji), `environment_variables` CAP (tylko nazwy flag; inne wartości redagowane),
   `tasks`, `features`, `usage_summary` org. `cf logs ainvoicer-srv --recent` zapisany tymczasowo i usunięty — bufor pusty.
2. CI przez publiczne GitHub API (lista runów).
3. Offline: `cds build --for hana --production` do katalogu scratch → oczekiwany katalog HDI (14 tabel z PK, 4 widoki,
   6 unikalnych indeksów); diff modelu `fe0a0c1..bd38156` pusty.
4. Odtworzenie WARN outbox lokalnie (H2) przez `HanaReadinessConfigurationTest` — 9 WARN, analiza w przewodniku §4.
5. Nowy skrypt read-only `deploy/scripts/hdi-catalog-readonly.cjs` (task na istniejącym droplecie deployera) + 9 testów;
   smoke z prawdziwym `hdb` 2.30.1 na zamkniętym porcie lokalnym (ścieżka błędu bez komunikatu serwera).
6. Harness: zgoda `READ_ONLY_CHECK_APPROVED_HDI` dla `check/recover/verify-cleanup`, nowa faza `verify-cleanup`,
   `validate(Map)`; regresja Java (+2) i Node (+1).
7. Plan G0–G9, parametry runu i dokładny zakres zapisów: [przewodnik](../guides/CAP_HANA_02_PERSISTENCE_ACCEPTANCE.md).

## Changed Files

- `deploy/scripts/hdi-catalog-readonly.cjs` (nowy), `deploy/test/hdi-catalog-readonly.test.mjs` (nowy).
- `deploy/scripts/hana-integration.mjs`, `deploy/test/hana-integration.test.mjs`.
- `cap-java/srv/src/test/java/customer/cap_java/hana/HanaPersistenceIT.java`, `HanaHarnessSafetyTest.java` (tylko źródła testowe).
- `docs/guides/CAP_HANA_02_PERSISTENCE_ACCEPTANCE.md` (nowy), ten dziennik, `docs/guides/CAP_HANA_01_FIRST_DEPLOYMENT_RUNBOOK.md`,
  `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/README.md`.
- Bez zmian: kod produkcyjny, MTA/extension, CDS (w tym `documents.cds`), XSUAA/IAM.

## Commands Executed

Read-only CF/CAPI jak w Implementation (Git Bash, `MSYS_NO_PATHCONV=1`); `git worktree add -b …`;
`mvn -B test -Dtest=HanaReadinessConfigurationTest,HanaHarnessSafetyTest`; `mvn -B verify` (cap-java);
`npm ci --prefix deploy`, `npm test --prefix deploy`, `node deploy/scripts/check-secrets.mjs`;
`node deploy/scripts/hdi-catalog-readonly.cjs --task-json` (tylko generacja, bez wysyłki).
**Nie wykonano:** `cf run-task`/POST tasks, `cf restart`, `cf env`, service keys, DML/DDL, deploy/rollback, push/PR.

## Tests & Results

| Test | Wynik | Zakres |
| --- | --- | --- |
| `mvn -B verify` (worktree) | **PASS** — 470 run, 0 fail, 6 skipped (Azurite), 2:18 min | lokalny H2/offline |
| `HanaHarnessSafetyTest` | **6/6 PASS** (+2 nowe) | H2 |
| `HanaReadinessConfigurationTest` | **2/2 PASS**; 9 WARN „Failed to register handler method” | H2 |
| `npm test --prefix deploy` | **43: 42 PASS, 1 skipped** (Go ZIP reader tylko w CI) | offline |
| `check-secrets` | PASS (heurystyka śledzonych plików) | offline |
| Katalogowy SQL HDI | **NIE WYKONANO** — brak autoryzowanego kanału; procedura G1 do zgody | — |
| DML/restart/CAS/LIVE | **NIE WYKONANO** | — |

## Problems Encountered

- Logi CF z 11.11 wypadły z bufora — dzisiejszy stan puli JDBC niepotwierdzony (propozycja G0).
- Run CI `43097095232` (`899fca0`) pozostaje `queued` od 11.11; kod pokryty runem `bd38156` SUCCESS. Anulowanie — decyzja właściciela.
- Harness nie ma gdzie się wykonać (brak runnera w CF, SSH CAP wyłączone, laptop bez credentiali) — RUNTIME BLOCKED.
- `5260002008` (NIP zakresu użytkownika H01/XSUAA) nie przechodzi sumy kontrolnej `KsefTaxpayerContext.isValidNip`:
  dla CAP-HANA-02 to zaleta (izolacja), ale do sprawdzenia przed INBOUND LIVE dla tego kontekstu.

## Decisions Made

- Rekomendacja katalogu: jednorazowy task na istniejącym droplecie deployera zamiast DB Explorer/simulate-make/laptopa.
- Rekomendacja runnera: tymczasowa aplikacja bez trasy (R2) jako osobny etap offline + zgoda; nie modyfikujemy dropletu CAP.
- WARN outbox: bez zmian kodu; kryterium do przyszłej aktywacji outbox.

## Current State

Faza 1 zakończona read-only; Faza 2 opisana; **GO** do zatwierdzenia G0/G1, **NO-GO** dla pierwszego DML.
Branch lokalny, bez commit/push/PR w chwili wpisu.

## Next Steps

1. Właściciel: review brancha → commit/push/PR i CI; decyzja o G0 (odczyt w sesji) i G1 (task katalogowy).
2. Po `MATCH`: decyzja o wariancie runnera R2 i przygotowanie pakietu offline (osobne zadanie).
3. Dopiero potem osobne zgody G3→G9 według przewodnika.
