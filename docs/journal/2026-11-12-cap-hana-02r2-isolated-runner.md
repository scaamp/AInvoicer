---
title: "Dziennik — CAP-HANA-02R2 izolowany runner HANA"
date: 2026-11-12
type: journal
status: offline-verified-live-pending
tags: [ainvoicer, journal, cap-java, hana, hdi, runner, cloud-foundry]
---

# CAP-HANA-02R2 — implementacja offline

[Przewodnik R2](../guides/CAP_HANA_02_R2_RUNNER.md) ·
[Poprzednia sesja CAP-HANA-02](2026-11-12-cap-hana-02-persistence-acceptance.md).

## Date

2026-11-12, Europe/Warsaw; Codex.

## Branch

`feature/cap-hana-02r2-isolated-runner`, osobny worktree `D:\AInvoicer-cap-hana-02r2`.
Po `git fetch origin` baza `origin/main=e468c76786b862f2b91f9db80dd4c10decca5f47`.

## Goal

Przygotować powtarzalny, izolowany pakiet do późniejszego wykonania istniejącego `HanaPersistenceIT`
w CF, wraz z bramkami, generowaniem poleceń i testami offline. Bez wykonywania żadnego etapu LIVE,
nowych usług/kluczy, restartów, DML/DDL ani ingerencji w BLOB-LAB-01. Osobny PR, bez merge.

## Initial State

Główny worktree `D:\AInvoicer`: `main=4186081`, zmodyfikowany `.env.example` i nieśledzony
`cap-java/db/documents.cds` właściciela. Niczego w nim nie edytowano ani nie budowano.
Przeczytano AGENTS, STATUS, ROADMAP, wskazane runbooki/dziennik i ADR-0001/0007 oraz skill CAP Java.
Stan HANA/MTA pochodzi z wcześniejszej dokumentacji; **nie powtarzano odczytów CF** w tej sesji.

Po publikacji brancha ponowny fetch ujawnił `origin/main=2fe9402` (cztery commity dokumentacyjne
LAB ponad bazą e468c76). Bez zmian kodu i bez konfliktu plików R2; nowe informacje są jednak istotne:
OWNER-REPORTED deployment tabeli `CUSTOMER_CAP_JAVA_KSEFDOCUMENTS` do tego samego `ainvoicer-db`.
G1 ma nadal dokładną listę14 tabel; dodatkowa tabela może dać MISMATCH. Nie zmieniano katalogowego
skryptu ani LAB. **G2 NO-GO** do osobnego uzgodnienia zatwierdzonego katalogu i faktycznego MATCH.

Toolchain lokalny: Microsoft OpenJDK25.0.4.1, Maven3.9.16, Node24.13.0.
CAP5.2.0, Boot4.1.1, SDK3.0.29 według POM. JUnit rozwiązany przez Maven:6.0.3.
SapMachine25 jest wymaganiem przyszłego stagingu; lokalny JDK nie dowodzi stagingu SAP buildpacka.

## Implementation

1. Oddzielny executable Boot JAR z kodem CAP, dokładnie wybranym oryginalnym IT i zależnościami testowymi.
   Mały JUnit Launcher API zamiast Console standalone; brak Mavena w CF i zmian POM/aplikacji produkcyjnej.
2. Preflight przed Spring/DataSource: consent/faza, scope, API/org/space/app bez URI, HDI/XSUAA, flagi OFF,
   zakaz DBADMIN, CI i zewnętrznych override konfiguracji. Faza nie jest wybierana domyślnie.
3. Kontrola SHA wejść/wpisów/JAR, bytecode25, obecności klas/JDBC/Spring/JUnit i metadanych ZIP.
   Nested JAR zapisane STORED dla Boot. Usunięty lokalny H2 DDL; guard/harness nadal wymagają HDB.
4. Generowanie wyłącznie tekstu CF: target guard, metadane istniejących usług i brak kluczy,
   no-route/0 web instances, staged task-only app, oddzielny JSON jednej zatwierdzonej fazy i cleanup.
5. Dokładna procedura G0–G4, recovery/cleanup i ryzyka quota/Free Tier w przewodniku R2.
6. CI rozszerzone o izolowany build i testy procesów offline; brak sekretów/bindingów w workflow.

## Changed Files

- `deploy/scripts/hana-it-runner.mjs` — build/integrity/manifest i generatory tekstu, bez wykonawcy CF.
- `deploy/hana-it/src/ainvoicer/hanait/Main.java` — preflight i launcher z bezpiecznym wyjściem.
- `deploy/hana-it/src/ainvoicer/hanait/OfflineContractTest.java` — kontrakt oryginalnych faz w JUnit.
- `deploy/hana-it/runner.test.mjs` — rzeczywiste nowe JVM i negatywy integralności dystrybucji.
- `deploy/test/hana-it-runner.test.mjs` — manifest, consent/fazy, scope/target i plan CF.
- `.github/workflows/ci.yml` — build i procesowe testy offline R2.
- Przewodnik R2 oraz ten dziennik.

Bez zmian w `cap-java/`, MTA/extensions, handlerach, CDS, XSUAA/IAM, KSeF i LAB.
STATUS/ROADMAP/README niezmienione zgodnie z wyraźnym zleceniem; link dziennika umieszczono w przewodniku.
Wygenerowane JAR, manifest, evidence i logi są ignorowane przez Git.

## Commands Executed

W osobnym worktree: `git fetch origin`, `git worktree add -b ... ... origin/main`,
`npm ci --prefix deploy`, `mvn -B -f cap-java/pom.xml clean verify -Dcds.cds.skipVersionCheck=true`,
Maven dependency plugin3.8.1 `copy-dependencies`/`copy` (launcher dopasowany do engine),
`javac --release 25`, `node deploy/scripts/hana-it-runner.mjs build`, `verify`, `test`,
`npm test --prefix deploy`, skaner sekretów i `git diff --check`.
Sprawdzono generowany tekst planu CF, bez wykonania jego poleceń.

## Tests & Results

| Sprawdzenie | Wynik / granica dowodu |
| --- | --- |
| Maven clean verify | 470 testów, 0 failures/errors, 6 skipped (Azurite); lokalne H2/mock/offline. `HanaPersistenceIT` nie uruchomiony na DB. |
| Deploy Node | 47 testów:46 PASS,1 skipped (niezależny Go ZIP reader dostępny w CI). |
| Procesowe testy pakietu | 5/5 PASS: JUnit kontrakt wszystkich6 faz, bramka consentu/targetu/bindingów/flag, brak sekretów, uszkodzenie pakietu i exit codes. |
| JAR i exploded layout | Launcher JUnit ładuje oryginalny harness; lokalnie, bez Springa/połączenia HANA. |
| Integralność | SHA-256 wszystkich wpisów/artefaktów i wejść, klasy bytecode25, JUnit/Spring/JDBC, ZIP permissions/STORED. |
| Skaner sekretów | Heurystyka śledzonych plików; nie jest audytem credentiali BTP. |
| LIVE HANA/CF/G0–G4 | **NIE WYKONANO**. |
| CI PR | Do sprawdzenia dla opublikowanego HEAD; lokalne wyniki nie zastępują GitHub Actions. |

## Problems Encountered

- Pierwsze pakowanie na Windows przekroczyło długość polecenia `javac`; poprawiono classpath wildcard.
- Faktyczny model w JAR znajduje się w `edmx/csn.json`, nie w korzeniu resources; kontrola poprawiona
  po inspekcji wygenerowanego JAR. Nie zmieniano modelu CAP.
- Dodawane nested JAR wymagają STORED, a ZIP payload Unix modes; oba warunki są w walidatorze.
- Dokumentacja CLI potwierdziła, że `push --no-start` nie stage'uje; plan jawnie dodaje drugi `push --task`.

## Decisions Made

R2 zamiast R1/R3 ze względu na wykonanie tego samego harnessu bez dotykania działającego CAP.
Launcher API6.0.3 zapewnia kontrolę wyniku i brak surowych wyjątków w logach; console-standalone
dublowałby biblioteki. Świadomie przyjęto ograniczoną diagnostykę i osobny etap LIVE stagingu.
Zgodnie z zadaniem nie zmieniano źródeł/semantyki `HanaPersistenceIT`.

## Current State

Implementacja i dowody dotyczą wyłącznie offline. G2 wymaga review/CI, G1 MATCH, potwierdzenia quota/
stanu HANA, uzgodnienia dodatkowej tabeli LAB oraz osobnej zgody właściciela na nową app/staging
i dwa bindingi. G4 wymaga kolejnej zgody DML.
Nie wykonano żadnego CF CLI, BTP LIVE operation, testu rzeczywistej HANA, restartu ani DML.

## Next Steps

1. Review PR i potwierdzenie zielonego CI dla dokładnego HEAD; bez automatycznego merge.
2. Właściciel: osobno zatwierdzić G0/G1 i zebrać autoryzowany GET oraz katalog MATCH.
3. Dopiero potem decyzja o G2, następnie G3 i osobny G4 według przewodnika; rebuild z zatwierdzonego SHA.
