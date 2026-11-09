---
title: "INT-01 — integracja CAP-07C i KSEF-IN-01"
date: 2026-11-09
type: journal
status: zakonczony
tags: [ainvoicer, journal, integration, cap-java, ksef, inbound, ci]
---

# INT-01 — integracja branchy

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Audyt i plan merge](../audits/2026-11-09-int-01-integration-review.md)

## Date

2026-11-09, Europe/Warsaw.

## Branch

`feature/int-01-cap07c-ksef-integration`, nowy worktree `D:\AInvoicer-int-01`.
Oryginalny `D:\AInvoicer` i worktree Claude nie zostały przełączone ani użyte do buildów/serwerów.

## Goal

Połączyć hardening/CI CAP-07C i offline INBOUND, zachować historię i OUTBOUND, wykonać pełną regresję,
review, dokumentację, push i PR do CAP-07C. Bez merge main, deployu, SAP WRITE i LIVE KSeF.

## Initial State

Po `git fetch --all --prune`: main `552fdb9`, CAP-07B `9d7a380`, CAP-07C `e850a69`, INBOUND `344a67b`.
Drzewo `D:\AInvoicer` czyste. CAP-07C względem 07B: 36 plików; INBOUND względem 07B: 37 plików.
Merge-base: `9d7a380dcf1c07cd27cad34f3b9ca6e397cac6cf`.
PR #1 OPEN/conflict; PR #2 OPEN/mergeable. `gh` nieobecne; odczyt i utworzenie PR przez connector GitHub.

Historyczne wyniki dotyczą swoich branchy: CAP-07C **243 Maven**, identity-smoke/AI hardening i
[CI PASS dla e850a69](https://github.com/scaamp/AInvoicer/actions/runs/42672815377);
KSEF-IN-01 **77 nowych testów**, metadata/XML/parser/deduplikacja/HWM, bez LIVE KSeF
([dziennik źródłowy](2026-11-09-ksef-in-01-inbound.md)). Wyników tych nie zastępuje wynik INT-01.

## Implementation

`git merge --no-ff origin/feature/ksef-in-01-inbound-read-core`
utworzył `350e64392a0eccacb30a30921606f8e106a9fb90`, rodzice CAP-07C i INBOUND. Zero konfliktów.
Wszystkie 37 plików INBOUND zachowano. Dodatkowa regresja w `XsuaaJwtODataContractTest`
sprawdza brak beanów pakietu INBOUND i brak jego typów w autoryzowanym OData `$metadata` przy prawdziwym profilu cf.
Brak zmiany kodu produkcyjnego, POM, CDS, ADR-0005 i istniejących workflow.

## Changed Files

- Merge: 25 klas produkcyjnych INBOUND, 9 plików Java testów, fixture XML oraz przewodnik i dziennik INBOUND.
- INT-01: test `XsuaaJwtODataContractTest`, niniejszy dziennik, audyt, STATUS/ROADMAP/README.
- Wygenerowane JAR/MTAR, logi i node_modules są lokalne i nie trafiają do commita.

## Commands Executed

Worktree INT-01; JDK Microsoft **25.0.4.1**, Maven **3.9.16**, Node **24.13.0**, npm **11.6.2**,
CAP **5.2.0**, Boot **4.1.1**, MF SDK **3.0.29**, mbt **1.2.49**, AppRouter **23.4.0**.

```powershell
git fetch --all --prune
git status --short
git worktree list
git branch -a
git log --graph --oneline --decorate -30
git worktree add -b feature/int-01-cap07c-ksef-integration D:/AInvoicer-int-01 origin/feature/cap-07c-autonomous-hardening
git merge --no-ff origin/feature/ksef-in-01-inbound-read-core -m "merge(int-01): integrate KSeF inbound core with CAP-07C"
mvn -f cap-java/pom.xml clean verify '-Dcds.cds.skipVersionCheck=true'
npm ci --prefix deploy
npm test --prefix deploy
npm run validate:smoke --prefix deploy
npm run build:btp:smoke --prefix deploy
npm ci --prefix sapui5/ainvoicer
npm run build --prefix sapui5/ainvoicer
npm run test:runner --prefix sapui5/ainvoicer
node deploy/scripts/check-secrets.mjs
git diff --check
git merge-tree --write-tree origin/main origin/feature/cap-06-ui5-09-dual-backend
```

`build:btp:smoke` ponawia pełne Maven clean verify, uruchamia 8 testów executable JAR,
UI5 `npm ci`, `build:cf`, `test:cf-build`, AppRouter `npm ci`/`test:config`, mbt assemble i kontrolę MTAR.
Bez równoległych buildów na tym samym target. UI5 mock na własnym **18085** (8085 należał do innego procesu).
Końcowe QUnit i OPA: `node scripts/browser-test.mjs qunit http://localhost:18085/test/...` z katalogu UI5,
tymczasowa kopia ui5-mock bez backend proxy US10; CDN wyłącznie dla bibliotek UI5. Serwer zatrzymano i kopię usunięto.

## Tests & Results

| Suite | Expected | Actual | Result |
| --- | --- | --- | --- |
| Pierwszy Maven merge HEAD | około 320 | 320, failure/error/skip=0 | PASS |
| Końcowy Maven z regresją INT-01 | 243 + 77 + 1 | **321**, failure/error/skip=0 | PASS |
| INBOUND | 77 | **77** | PASS |
| OUTBOUND/FA(3) | zachowane | **104** (w tym accepted-writeback 8) | PASS |
| SAP adaptery/writeback/consent | zachowane | **71**; writeback subset 62 z accepted-writeback, consent 4 | PASS |
| Security/H2/auth/XSUAA | fail closed | **58**, w tym JWT CF 13 | PASS |
| Pozostałe CAP | zachowane | **11** | PASS |
| Executable JAR CF | 8 | **8**, 0 skips | PASS |
| QUnit, mock | 79 | **79 / 446 asercji** | PASS |
| OPA, mock | 13 | **13 / 80 asercji** | PASS |
| Browser runner | 4 | **4** | PASS |
| UI5 CF artefakt | 8 | **8** | PASS |
| AppRouter | 8 | **8** | PASS |
| deploy invariants | 4 | **4** | PASS |
| UI5 build / CF build / MTAR | poprawny artefakt | 67 366 302 B MTAR, 75 065 650 B JAR | PASS |
| Secret patterns / whitespace | brak ustaleń | 2530 śledzonych plików po dodaniu dokumentacji, 0 wzorców | PASS, heurystyka |
| GitHub Actions INT-01 | zintegrowany kod | **321 Maven, 79 QUnit, 13 OPA**, wszystkie kroki workflow success | PASS dla `18ca64e`, run poniżej |

Subsetów writeback nie należy dodawać do sumy 321: accepted-writeback jest w OUTBOUND, pozostałe writeback w SAP.
LIVE testy jawnie wyłączone tagami Maven, nie są przypadkowym skipem.

## Problems Encountered

UI5 tooling nadal 74 advisories; brak nowych zależności INBOUND. OPA loguje dwa oczekiwane odrzucenia
TTS przez mock, asercje 80/80 PASS i brak LIVE AI. Po restarcie własnego mock pierwszy odczyt był
przed gotowością (runner exit 2); po komunikacie `Server started` pełne QUnit i OPA powtórzono, PASS.
Ryzyka pamięci XML, checkpointów i przyszłej autoryzacji opisano w audycie; bez blokera integracyjnego.

## Decisions Made

Brak refaktoryzacji i zmiany architektury. INBOUND pozostaje niepodłączonym rdzeniem, bez endpointu/schedulera.
Symulacja PR #1 w osobnym indexie zachowuje architekturę main i dokumentację CAP-06.1; źródłowego brancha nie zmieniono.
Rekomendowane uproszczenie: zbiorczy CAP-07C → main po review ownera, potem retarget INT-01 → main.
Historia poszczególnych sprintów pozostaje zachowana przez merge commits; szczegóły i wariant etapowy w audycie.

## Current State

Lokalna regresja i audyt ukończone. Branch wypchnięty; [PR #3](https://github.com/scaamp/AInvoicer/pull/3)
OPEN, base `feature/cap-07c-autonomous-hardening`; GitHub potwierdził `mergeable=true` dla `18ca64e`.
PR ma 43 zmienione pliki; pierwotny kod INBOUND identyczny z `344a67b`, pozostały kod produkcyjny
identyczny z CAP-07C. Obaj rodzice merge są przodkami brancha. Repo źródłowe pozostaje czyste na `e850a69`.

**CI Ubuntu PASS dla `18ca64e034855935baad1340d1d1dbc7b7d50a18`**:
[run 42685746135](https://github.com/scaamp/AInvoicer/actions/runs/42685746135), job `129391972677`.
Zweryfikowano zakończenie workflow `success`, wszystkie kroki oraz logi: Maven **321/321**, zero failures/errors/skips;
MTAR validation PASS; QUnit **79/446**, OPA **13/80**. JAR CF, AppRouter, CF artefakt, runner i secret scan również success.
Nie dodano nowego workflow — istniejący automatycznie objął pakiet INBOUND.

Commity INT-01: `350e643` (merge dwóch sprintów), `18ca64e` (regresja izolacji i dokumentacja),
następnie dokumentacyjny `docs(int-01): record successful Ubuntu CI and PR publication`.
Ostatni commit aktualizuje wyłącznie dowody publikacji/review; bieżący wynik dla jego HEAD należy sprawdzać
w [checks PR #3](https://github.com/scaamp/AInvoicer/pull/3/checks). Nie przypisujemy wyniku run 42685746135 innemu SHA.

Wykonano również `git push -u origin feature/int-01-cap07c-ksef-integration`, ponowny push aktualizacji dokumentacji,
odczyty PR/workflow/job/log przez connector GitHub; pomocniczo publiczny REST Actions do potwierdzenia uruchomienia.
`git diff --check`, kontrola względnych linków nowych dokumentów, scan 2530 plików i brak śledzonych JAR/MTAR PASS.

Dotychczasowy LIVE CF (ainvoicer-srv/approuter/auth/destination, identity-smoke, bez HANA) to stan historycznie
potwierdzony w CAP-07C; INT-01 nie odpytywał ani nie zmieniał CF. SSO H01 nie zostało zaliczone.
Nie wykonano merge main, deploy/restart, SAP WRITE, KSeF SEND ani INBOUND LIVE.

## Next Steps

1. Owner: review/scalenie stosu; obecny PR INT-01 ma bazę CAP-07C, retarget dopiero po scaleniu wcześniejszych zmian.
2. CAP-07B.2: osobna zgoda na CF redeploy, Role Collections i Destination/SSO H01, testy rzeczywistych użytkowników A/B.
3. KSEF-IN-02: staging/checkpoint DB i storage/lease; CAP-04D wymaga osobnej zgody i fixture. E2E-01 nadal wymaga danych RAP/PartyData, trwałości i osobnej zgody na TEST SEND.
