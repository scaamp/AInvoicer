---
title: "KSEF-STOR-04 — prywatna tabela HANA BLOB dla dokumentów workera KSeF"
date: 2026-11-15
type: journal
status: zakonczony
tags: [ainvoicer, journal, ksef, storage, hana, blob, hdi, g1, security]
---

# KSEF-STOR-04 — prywatny magazyn HANA BLOB

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) ·
[Przewodnik](../guides/KSEF_STOR_04_PRIVATE_HANA_STORAGE.md)

## Date

2026-11-14/15, Europe/Warsaw (start 14.11 wieczorem, testy i publikacja 15.11).

## Branch

`feature/ksef-stor-04-private-hana-storage`, worktree `D:\AInvoicer-ksef-stor-04`, baza `origin/main` = `c5041bd`
(zgodny ze wskazanym SHA `c5041bdd934638b99587c757588d706868e16633`). Upstream gałęzi bazowej odpięty.

## Goal

Usunąć bloker D-1 z KSEF-STOR-03 strukturalnie: dedykowana, nieudostępniana encja dla dokumentów workera,
adapter KSEF-STOR-03 przeniesiony na nową tabelę, testy offline (równoległość, bezpieczeństwo, INBOUND),
analiza wpływu na G1 z rzeczywistej kompilacji, dokumentacja, osobny PR i CI. Bez HDI deploy, migracji LIVE,
wiringu, aktywacji INBOUND/schedulera, KSeF SEND, SAP WRITE i bez merge.

## Initial State

- Główny checkout `D:\AInvoicer` czysty, lokalny `main` = `7a47ac9` (za `origin/main`); niezmieniany.
- `origin/main` = `c5041bd` (merge PR #19 IN-03B, `gh`: MERGED 14.11 18:36Z).
- PR #20: Draft, OPEN, HEAD `85936cdf7d8237756f48b3b03f5c3ba890652191` (zgodny ze zleceniem), gałąź oparta o `7a47ac9`.
- Przeczytano: AGENTS, CLAUDE, STATUS, ROADMAP, dziennik IN-03B, BLOB-LAB-01, Skill CAP Java, przewodnik STOR-03
  (z gałęzi PR #20), kod STOR-03, `ContentAddressedDocumentStore`, `DocumentScope`, `DocumentReference`,
  `ObjectStorageClient`, `ObjectStorageSettings`, walidator i test G1.
- Kolizja z BLOB-LAB-01: brak — zlecenie to odrębny sprint z jawną zgodą na zmiany CDS; pliki LAB nie są zmieniane.
- Wersje: JDK Microsoft 25.0.4.1, Maven 3.9.16, Node 24.13.0, `@sap/cds` 10.2.1, cds-dk 10.2.0, compiler 7.2.1
  (lockfile), CAP Java 5.2.0 (`cds4j-api` 5.2.0). `gh` 2.104.0 uwierzytelniony (pełna ścieżka w Git Bash).

## Implementation

- **Model:** nowy `cap-java/db/ksef-storage.cds` — `KsefStoredDocuments` z PK `(environment, channel, ownerSegment, sha256)`,
  `mimeType`, `sizeBytes Integer64`, `storedAt Timestamp`, `content LargeBinary`; wszystkie `not null`; bez `cuid`/`managed`;
  `@cds.autoexpose: false`; żaden serwis nie importuje pliku.
- **Adapter:** `HanaBlobObjectStorageClient` przepisany z mapowania `fileName`/`createdBy`/UUID na złożony PK:
  ścisłe parsowanie klucza SPI, SHA-256 i rozmiar z backendu, `409006` → kontrola istniejącego wiersza → `ALREADY_EXISTS`,
  brak UPDATE/DELETE, odczyt w dwóch krokach (metadane/limit → budżet pamięci → strumień BLOB z twardym limitem),
  listing po PK, semafor bajtów w locie. `HanaKsefDocumentStore`: fabryka bez `keyPrefix`, z wariantem budżetu pamięci.
- **Bez zmian:** `KsefDocumentStore`, `DurableInboundDocumentStore`, `ContentAddressedDocumentStore`, `DocumentScope`,
  `DocumentReference`, `ObjectStorageClient`, sink/runner INBOUND, `KsefInboundConfiguration` (guard CF), scheduler,
  `application.yaml`, MTA, role, LAB (`documents.cds`, `blob-service.cds`, `BlobDocumentsHandler`).
- **G1:** po decyzji właściciela (AskUserQuestion 15.11: „Tylko test kompilacji”) zmieniono wyłącznie test niezależnej
  kompilacji; runtime walidatora nietknięty. Propozycja CORE15 w przewodniku (sekcja 9).
- **PR #20:** kod przeniesiony plikowo (bez cherry-pick); PR #20 do zamknięcia jako superseded po review.

## Changed Files

| Plik | Zmiana |
| --- | --- |
| `cap-java/db/ksef-storage.cds` | nowa encja prywatna |
| `cap-java/srv/src/main/java/customer/cap_java/ksef/storage/HanaBlobObjectStorageClient.java` | nowy (port STOR-03 na PK) |
| `cap-java/srv/src/main/java/customer/cap_java/ksef/storage/HanaKsefDocumentStore.java` | nowy (port STOR-03, bez prefiksu, budżet pamięci) |
| `cap-java/srv/src/test/java/customer/cap_java/ksef/storage/HanaKsefDocumentStoreTest.java` | nowy, 27 testów |
| `cap-java/srv/src/test/java/customer/cap_java/ksef/storage/HanaDocumentStoreInboundPipelineTest.java` | nowy (port STOR-03), 4 testy |
| `deploy/test/hdi-catalog-readonly.test.mjs` | tylko test niezależnej kompilacji: 16 tabel, PK i DDL STOR-04, brak w runtime kontraktu |
| `docs/guides/KSEF_STOR_04_PRIVATE_HANA_STORAGE.md`, ten dziennik, `STATUS`, `ROADMAP`, `README` | dokumentacja |

Wygenerowane i ignorowane (niecommitowane): `schema-h2.sql`, `srv/src/gen`, `db/src/gen`, `target/`, `deploy/build/`.

## Commands Executed

W `D:\AInvoicer-ksef-stor-04` (Git Bash), poza wskazanymi:

```text
git fetch origin --prune
git worktree add -b feature/ksef-stor-04-private-hana-storage D:/AInvoicer-ksef-stor-04 origin/main
git branch --unset-upstream
gh pr view 20 / gh pr view 19            (pełna ścieżka gh.exe)
npm ci --prefix cap-java --ignore-scripts
npm ci --prefix deploy --ignore-scripts
node <scratch>/stor04-compile.cjs cap-java   (cds.compile.to.hana + to.sql h2, odczyt DDL)
node <scratch>/stor04-diff.cjs cap-java      (porównanie artefaktów z / bez ksef-storage.cds)
node --test deploy/test/hdi-catalog-readonly.test.mjs
mvn -B test -Dcds.cds.skipVersionCheck=true -Dtest=HanaKsefDocumentStoreTest,HanaDocumentStoreInboundPipelineTest,BlobServiceODataTest
mvn -B test ... -Dtest=HanaKsefDocumentStoreTest   (2 mutacje, kod przywrócony, cmp OK)
mvn -B clean verify -Dcds.cds.skipVersionCheck=true   (cap-java/)
npm rebuild mbt --prefix deploy
npm test --prefix deploy
npm run validate:smoke --prefix deploy
node deploy/scripts/check-secrets.mjs
npm run build:btp:smoke --prefix deploy
npm run build:btp:hana --prefix deploy
npm run verify:hana --prefix deploy
git diff --check
```

## Tests & Results

Wszystko lokalnie i offline (H2 / kompilacja / MTAR niewdrożony):

| Zakres | Wynik |
| --- | --- |
| Kompilacja HANA modelu | 16 `.hdbtable` / 5 `.hdbview` / 6 `.hdbindex`; vs model bez STOR-04: 26 → 27, **+1, 0 usuniętych, 0 zmienionych** |
| Projekcje na `KsefStoredDocuments` | 0 (CSN, widoki HANA, runtime CAP, `$metadata`) |
| `HanaKsefDocumentStoreTest` | **27/27 PASS** |
| `HanaDocumentStoreInboundPipelineTest` | **4/4 PASS** |
| `BlobServiceODataTest` (BLOB-LAB-01) | **3/3 PASS** |
| Kontrola mutacyjna | wyłączenie budżetu pamięci i kontroli `sizeBytes` → odpowiednio 2 testy FAIL (oczekiwane), kod przywrócony |
| Pełny `mvn clean verify` | **528 run / 0 failures / 0 errors / 6 skipped** (Azurite bez emulatora), BUILD SUCCESS |
| G1 | **63/63 PASS** |
| Deploy | **105 PASS / 1 SKIP** (Go lokalnie niedostępne; CI ma Go) |
| Smoke validate / check-secrets | PASS / PASS (2688 plików, heurystyka) |
| MTAR smoke + HANA + `verify:hana` | **PASS** na commicie kodu `f2b8f0f` (smoke: Maven 528/0/0/6, UI5 CF 71 plików, oba etapy evidence; HANA: release hashes PASS, „Undeployed HANA release evidence PASS”). Payload HDI w MTAR: **16 hdbtable / 5 hdbview / 6 hdbindex**; `KsefStoredDocuments.hdbtable` SHA-256 `27cac1a2…04de6a` = wynik `compile.to.hana`. Evidence: `deploymentApproved=false`, `hdiCreation=NOT EXECUTED`; `workingTreeDirty=true` wyłącznie przez niezacommitowane `docs/` (poza `sourceHash`). Artefakt niewdrożony, w ignorowanym `deploy/build/` |
| CI dokładnego HEAD | CI_RESULT |
| LIVE | **NIE WYKONANO**: HDI deploy, CF, HANA DDL/DML, G1 LIVE, KSeF, SAP WRITE, restarty, service keys |

## Problems Encountered

- **Konflikt wymagań G1:** test niezależnej kompilacji G1 oczekiwał dokładnie 15 tabel, a polecenie zabraniało zmian
  walidatora bez uzgodnienia i wymagało CI SUCCESS. Zatrzymano się i zapytano właściciela; wybrano zmianę wyłącznie testu.
- **Pierwsza mutacja nie skompilowała się** (`if (false)` usunęło jedyne źródło `InterruptedException`); to błąd mutanta,
  nie wynik testu. Powtórzono z kompilowalnymi mutantami — testy wykryły obie zmiany.
- **`npm test --prefix deploy` 1 FAIL za pierwszym razem:** brak binarki `mbt` po `npm ci --ignore-scripts`
  (ENOENT `mtad.yaml`). Środowisko, nie kod: `npm rebuild mbt` (jak w IN-03B) → 105 PASS / 1 Go skip.
- Ostrzeżenia LF→CRLF przy `git add` — tylko kopia robocza Windows; `git diff --check` PASS.

## Decisions Made

- Encja prywatna, złożony PK z segmentami referencji; korekty: `storedAt`, `not null`, brak `managed`, brak kolumny prefiksu
  (izolacja wdrożeń = kontener HDI). Uzasadnienie: przewodnik, sekcja 3.
- Brak migracji danych (STOR-03 nigdy niewdrożony); LAB nie jest czytany ani zmieniany.
- PR #20 zastąpiony bez włączania commitów; zamknięcie PR #20 pozostawione właścicielowi.
- G1: tylko test; CORE15 jako propozycja do wdrożenia razem z HDI deploy.
- Bez ADR: decyzja D-1 została podjęta przez właściciela w zleceniu; ADR-0008 pozostaje propozycją.

## Current State

Implementacja offline gotowa i przetestowana; PR otwarty do review. HDI deploy, G1 CORE15, pomiary HANA LIVE i wiring
adaptera — **nie wykonane**, każde wymaga osobnej zgody. INBOUND CF nadal NO-GO (guard bez zmian).

## Next Steps

1. Niezależny review PR STOR-04; decyzja o zamknięciu PR #20 jako superseded.
2. Uzgodnienie z Codexem zakresu G1 CORE15 (przewodnik §9) przed lub razem z HDI deploy.
3. Osobna zgoda właściciela na HDI deploy wyłącznie nowej tabeli; potem G1 LIVE i pomiary z §11 (najpierw `409006` na HANA).
4. Dopiero potem wiring adaptera (osobne zadanie), nadal z guardem CF i schedulerem OFF.
