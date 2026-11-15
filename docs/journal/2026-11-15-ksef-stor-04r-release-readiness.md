---
title: "Dziennik — KSEF-STOR-04R: gotowość release prywatnej tabeli HANA BLOB"
date: 2026-11-15
type: journal
status: zakonczony
tags: [ainvoicer, journal, ksef, storage, hana, hdi, g1, release]
---

# Dziennik — KSEF-STOR-04R: release readiness `KsefStoredDocuments`

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) ·
[Przewodnik STOR-04R](../guides/KSEF_STOR_04R_HANA_RELEASE_READINESS.md)

## Date

2026-11-15 (Europe/Warsaw).

## Branch

`feature/ksef-stor-04r-hana-release-readiness` w osobnym worktree `D:\AInvoicer-stor-04r`, z `main=744836c9dbb5851e5d14b5f23eadd21af31fc897`.

## Goal

Przygotować kompletny, powtarzalny i bezpieczny release tabeli `KsefStoredDocuments` na istniejący DEV: audyt baseline,
wykonywalny wersjonowany G1 16/5/6/16 przy zachowaniu G1 15/5/6/15, kontrola limitu 4096, harness HANA (wyłączony),
plan packaging/release/rollback i testy offline. **Bez deploymentu, LIVE SQL, restartów i merge.**
Praca równoległa z E2E-01B (Codex): nie zmieniano `KsefSendRequestHandler`, `KsefSubmissionWorker`, RAP, OUTBOUND,
`documents.cds`, `blob-service.cds`, `BlobDocumentsHandler`, `ksef-storage.cds` ani `docs/STATUS.md`/`ROADMAP.md`/`README.md`.

## Initial State

- `git fetch`: `origin/main` = `744836c` (ORCH-08 PR #25); główny worktree czysty; nowy worktree z dokładnego SHA.
- Przeczytano AGENTS, CLAUDE, STATUS, ROADMAP (zakres), przewodniki STOR-04, ORCH-08 release plan, G1, R2, BLOB-LAB-01,
  dziennik ORCH-08 i wdrożenia LAB 13.11.
- Relacja z dziennika (nie odczyt LIVE): ostatni deployment 13.11, MTAR 0.7.3 `b30117cf…` z `4d4dbfc` + pliki LAB,
  HDI task #4 SUCCEEDED. Własna weryfikacja: lokalna kopia `D:\AInvoicer\deploy\build\ainvoicer_0.7.3.mtar` ma ten SHA.
- Odtworzono stary G1 bajtowo: runtime `6ebdfd14…`, 4071 znaków.

## Implementation

1. **Audyt baseline (offline):** porównanie payloadu HDI wdrożonego MTAR z `cds build --for hana` bieżącego HEAD:
   +1 `customer.cap_java.KsefStoredDocuments.hdbtable` (`27cac1a2…`, bez zmian), 0 zmienionych, 0 usuniętych, 28 identycznych;
   `package.json`/lock deployera identyczne po normalizacji EOL. Zapisano manifest hashy wdrożonego payloadu
   (`deploy/contracts/hdi-deployed-baseline-0.7.3.json`). Zidentyfikowano zmiany `srv` od wdrożonej wersji (CAP-07F, IN-03B,
   IN-04, klasy STOR-04) → pełny MTAR wykracza poza release tabeli.
2. **Nowy G1** `deploy/scripts/hdi-catalog-stor04.cjs`: ścisły loader kontraktu v1, SQL z kwalifikowanymi kolumnami LAB i storage,
   silnik multizbiorów, status `storage`, współdzielone niezmienione funkcje runtime starego G1. Stary walidator bez zmian.
   Kontrakt v1: `executable: true`, `validator`, status nadal `UNDEPLOYED_RELEASE_CONTRACT`.
3. **Limit 4096:** pierwsza wersja 4323 znaki (FAIL), próba kompresji prefiksów 4407 (gorzej), silnik multizbiorów 4055 —
   bez usuwania kontroli; generator odmawia przy przekroczeniu (`checkedTaskRequest`).
4. **Harness HANA** `HanaKsefStoredDocumentsIT` (bramka, budżet zapisów, 4 fazy) + package-private przeciążenie fabryki
   `HanaKsefDocumentStore.create(..., decorator)`; regresja H2 `HanaKsefStoredDocumentsHarnessOfflineTest`.
5. **Runner R2:** fazy `stor04-catalog|roundtrip|contend|recover`, osobne zgody, pin commitu (`build-commit.txt` w JAR),
   generator `stor04TaskRequest` odrzucający pakiet z brudnego drzewa; filtr logu przepuszcza tylko znaczniki STOR04.
6. **Packaging:** wariant HANA podbity do **0.7.4** (inna etykieta niż wdrożone 0.7.3); `verify:stor04-release` w CI.
7. Dokumentacja: przewodnik STOR-04R (architektura, katalog, hashe, plan bramek, rollback, evidence, zgody, blockery).

## Changed Files

- Nowe: `deploy/scripts/hdi-catalog-stor04.cjs`, `deploy/scripts/stor04-release.mjs`,
  `deploy/contracts/hdi-deployed-baseline-0.7.3.json`, `deploy/test/hdi-catalog-stor04.test.mjs`,
  `deploy/test/stor04-release.test.mjs`, `cap-java/srv/src/test/java/customer/cap_java/ksef/storage/HanaKsefStoredDocumentsIT.java`,
  `.../HanaKsefStoredDocumentsHarnessOfflineTest.java`, `docs/guides/KSEF_STOR_04R_HANA_RELEASE_READINESS.md`, ten dziennik.
- Zmienione: `deploy/contracts/hdi-catalog-stor04.v1.json` (wykonywalny przez nowy walidator), `deploy/test/stor04-release-contract.test.mjs`,
  `cap-java/srv/src/main/java/customer/cap_java/ksef/storage/HanaKsefDocumentStore.java` (package-private dekorator; brak zmiany
  zachowania), `deploy/hana-it/src/ainvoicer/hanait/Main.java`, `OfflineContractTest.java`, `deploy/scripts/hana-it-runner.mjs`,
  `deploy/hana-it/runner.test.mjs`, `deploy/test/hana-it-runner.test.mjs`, `deploy/scripts/hana-readiness.mjs` (0.7.4),
  `deploy/package.json` (`verify:stor04-release`), `.github/workflows/ci.yml` (krok weryfikacji).
- Bez zmian: `deploy/scripts/hdi-catalog-readonly.cjs`, `cap-java/db/*.cds`, `srv/*.cds`, LAB, handlery SEND/worker, RAP, UI5.
- Wygenerowane (ignorowane, nie w Git): `cap-java/db/src/gen/`, `deploy/build/*`.

## Commands Executed

Katalog `D:\AInvoicer-stor-04r` (Git Bash/PowerShell), bez zmiennych z credentialami:
`git fetch origin`, `git worktree add -b feature/ksef-stor-04r-hana-release-readiness …`, `npm ci --prefix cap-java --ignore-scripts`,
`npm ci --prefix deploy`, `npm run build` (w `cap-java/db`), skrypt porównania payloadu (scratchpad),
`node deploy/scripts/hdi-catalog-{readonly,stor04}.cjs --task-json`, `node --test …`,
`node deploy/scripts/hana-it-runner.mjs build|test`, `java -jar deploy/build/hana-it/runner.jar --self-test`,
`npm run build:btp:smoke|build:btp:hana|verify:hana|verify:stor04-release|test:cf-runtime --prefix deploy`,
`npm test --prefix deploy`, `node deploy/scripts/check-secrets.mjs`, `git diff --check`.
Jedno wyszukiwanie WWW (limit polecenia taska CAPI) — bez danych projektu. **Nie uruchomiono** żadnego `cf`, HDI, SQL HANA.

## Tests & Results

Wszystkie testy lokalne / offline (bez LIVE):

- Nowy G1 **86/86 PASS**; stary G1 **63/63 PASS**; kontrakt 2/2; weryfikator release 5/5.
- `npm test --prefix deploy`: **200 / 199 PASS / 1 SKIP** (brak Go lokalnie).
- Maven `clean verify`: **593 / 0 failures / 0 errors / 6 skipped** (Azurite); harness H2 8/8; adapter 27/27.
- R2: 6/6 + self-test PASS; CF runtime 10/10; oba MTAR i `verify:hana` PASS;
  `STOR04_RELEASE_ARTIFACT=PASS hdiAdded=1 hdiChanged=0 hdiRemoved=0`.
- Skan sekretów PASS, `git diff --check` PASS. Mutacje walidatora wykryte (8/3/7 FAIL), kod przywrócony.
- Lokalne artefakty (z niezacommitowanego drzewa, **nie do wdrożenia**): MTAR 0.7.4 `9bbbc6f1…`, payload HDI `6bcf2013…`,
  `runner.jar` `fe517828…`. Release wymaga przebudowy z czystego, scalonego SHA.
- CI dokładnego HEAD PR: zapis poniżej po publikacji.

## Problems Encountered

- Przekroczenie 4096 znaków (4323) po dodaniu kolumn storage — rozwiązane krótszym silnikiem porównania, nie kosztem kontroli.
- Heredoc w narzędziu Bash zmieniał sekwencje `\\`; edycje z ucieczkami wykonano narzędziem Edit/plikiem skryptu.
- Limit CAPI dla `command` taska niepotwierdzony w dokumentacji (wyszukiwanie) — zachowano zachowawcze 4096.
- `cf deploy -m` (wdrożenie wyłącznie deployera) — semantyka do potwierdzenia przed zgodą (R-1).

## Next Steps

1. Review PR i CI (właściciel); merge wyłącznie decyzją właściciela.
2. Po merge — bramki z [przewodnika](../guides/KSEF_STOR_04R_HANA_RELEASE_READINESS.md#9-plan-release--każda-operacja-live-to-osobna-bramka):
   PRECHECK → CURRENT G1 → ARTIFACT REVIEW → OWNER APPROVAL → HDI DEPLOY (`-m ainvoicer-db-deployer`) → NEW G1 → G2 R2 →
   `stor04-*` → recover → ACCEPTANCE. Każda osobna zgoda.
3. Po zakończeniu prac równoległych: aktualizacja `docs/STATUS.md`, `ROADMAP.md` i indeksu `README.md` (świadomie pominięta tutaj).
