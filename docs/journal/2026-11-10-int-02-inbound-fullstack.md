---
title: "INT-02 — Inbound Full-Stack Integration"
date: 2026-11-10
type: journal
status: zakonczony
tags: [ainvoicer, inbound, cap-java, odata, sapui5, xsuaa, int-02]
---

# INT-02 — Inbound Full-Stack Integration

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md)

## Date / Goal

2026-11-10, Europe/Warsaw. Połączenie KSEF-IN-02 i UI5-10 przez rzeczywiste, bezpieczne CAP OData V4.
Syntetyczne dane i lokalne HTTP; bez deployu, zmian BTP/H01, synchronizacji/SEND KSeF ani SAP WRITE.

## Initial State / Git

Odczytano AGENTS/CLAUDE, STATUS/ROADMAP, ADR-0001/0006, dokumentację i kod obu sprintów,
Skills CAP Java/KSeF. Pierwotny checkout `D:\AInvoicer` na `feature/cap-07c-autonomous-hardening`
był czysty; poza `fetch` i utworzeniem worktree nie zmieniano go ani worktree autorów.
`git fetch origin` potwierdził:

| Ref | SHA |
| --- | --- |
| origin/main | 0297b87d2fe2ddb337990cd58e56e8e0ce87c328 |
| origin/feature/ksef-in-02-persistence | 5659e18b6f46f6222736eb2ba1de49134370a0d5 |
| origin/feature/ui5-10-inbound-inbox | 2f88accb0f88d61417036fe6edffd9fa0b0892ff |

Oba merge-base z main: `0297b87`. Nowy worktree `D:\AInvoicer-int-02`, branch
`feature/int-02-inbound-fullstack` z origin/main. Merge `--no-ff` kolejno:
`8b7e988` (IN-02) i `51fd4f9` (UI5-10), bez konfliktów, bez squash/reset/force.
PR #5 przy odczycie: OPEN, mergeable, nie scalono. Kod integracyjny: `bb3b8d54343d5a3286cbe98a1748fc0bce2c2a24`.
Wypchnięto nową gałąź (bez force) i utworzono [PR #6](https://github.com/scaamp/AInvoicer/pull/6)
do `feature/ksef-in-02-persistence`. Merge/auto-merge nie wykonano.
Przy publikacji21:28 wystartował [Ubuntu CI42895580546](https://github.com/scaamp/AInvoicer/actions/runs/42895580546)
na kodzie `bb3b8d5`. Commit dokumentacyjny po nim uruchamia osobny run; wynik końcowy
dla bieżącego HEAD jest w [checks PR](https://github.com/scaamp/AInvoicer/pull/6/checks).

## Implementation / Changed Files

- `cap-java/srv/ksef-service.cds`: jawny read-only `InboundInvoiceOverview`, 20 pól bez asocjacji.
  Typy, źródła i przykład JSON: [kanoniczny kontrakt](../guides/INT_02_INBOUND_FULLSTACK.md).
- `InboundOverviewHandler`: @Before READ, whitelist query, 1 NIP/1 CompanyCode, TEST/BUYER/grant
  w semijoin CQN przed paging/count, walidacja dedicated tenant względem XSUAA bindingu,
  stabilny order z id i NULL last. Standardowy CAP wykonuje projekcję/filtry/agregację w DB.
- `KsefInboundReadConfiguration` oddziela repozytorium od opt-in synchronizacji; zmiana sync wiring
  zachowuje dotychczasowe guardy. `InboundInvoiceOverview` i wewnętrzny mapper uzupełnione o
  buyerName=null, invoiceType i formCode. Publiczny endpoint nie korzysta z workerowego systemUser.
- Testy JWT/HTTP na stosie SAP Security rozbudowane o Inbox; fixtures mają A/B/shared/no-grant,
  SELLER-only, DEMO, konflikt, unsupported i null. Read wiring testowany bez auth/storage/SDK.
- UI5: dodane rzeczywiste statusy stagingu i filtr parseStatus; długości DTO zgodne z CAP,
  zachowana tolerancja niestandardowych mock numerów KSeF. Kwota Decimal18,2 negocjowana jako string.
  Test formattera zaktualizowany: QUARANTINED jest rozpoznanym błędem.
- `prepare-inbox-local.mjs`, `ui5-inbox.yaml`: osobna kopia manifestu lokalnego cap, mock domyślny
  bez zmian. `InboundLocalServer`/fixtures w src/test, nie w produkcyjnym JAR.
- `inbox-e2e.mjs`, dedykowane QUnit/OPA HTTP suites: własne procesy CAP/UI5, principal przez CDP
  wyłącznie na loopback reads, A/B/401/403/404/pusty/sieć/restart; bez stub fetch dla CAP.
- CI: nowy krok `test:inbox:e2e` po Maven/MTAR i regresji UI5.
- Dokumentacja: oba przewodniki źródłowe, STATUS/ROADMAP/README, nowy kontrakt, dziennik i security review.
  Architektura/właściciele danych pozostają bez zmian, nowy ADR nie był potrzebny.

## Commands Executed

```powershell
git fetch origin
git worktree add -b feature/int-02-inbound-fullstack D:/AInvoicer-int-02 origin/main
git merge --no-ff origin/feature/ksef-in-02-persistence
git merge --no-ff origin/feature/ui5-10-inbound-inbox
java -version
mvn -version
mvn -f cap-java/pom.xml test '-Dtest=XsuaaJwtODataContractTest' '-Dcds.cds.skipVersionCheck=true'
mvn -f cap-java/pom.xml clean verify '-Dcds.cds.skipVersionCheck=true'
npm ci --prefix sapui5/ainvoicer
npm run build --prefix sapui5/ainvoicer
npm run build:cf --prefix sapui5/ainvoicer
npm run test:cf-build --prefix sapui5/ainvoicer
npm run test:runner --prefix sapui5/ainvoicer
npm run serve-mock --prefix sapui5/ainvoicer
npm test --prefix sapui5/ainvoicer
npm ci --prefix approuter
npm run test:config --prefix approuter
npm ci --prefix deploy
npm test --prefix deploy
npm run build:btp:smoke --prefix deploy
npm run test:inbox:e2e --prefix sapui5/ainvoicer
git diff --check
node deploy/scripts/check-secrets.mjs
```

Wersje: JDK Microsoft25.0.4.1, Maven3.9.16, CAP5.2.0/Boot4.1.1/SAP Security4.1.2,
SDK3.0.29 bez zmian. Node24.13.0, Maven zarządza Node24.22.0; CLI UI5 3.x i Fiori tooling1.19.6.
Sygnatury CQN/Modifier/Visitor/ParameterInfo/UserInfo sprawdzono w JAR5.2.0 przez javap,
oficjalną dokumentację CAP odczytano online przed implementacją.

## Tests & Results

| Kontrola | Baza źródłowa | Wynik INT-02 |
| --- | --- | --- |
| Maven clean verify | 351 | **368/368 PASS**,0 failures/errors/skips; końcowy clean verify21:27 Europe/Warsaw |
| JWT/OData HTTP | istniejące security | 29/29 PASS z tenant/100 rows i null sorting |
| QUnit | 112 | 112/112,659 asercji PASS |
| OPA mock | 21 | 21/21,150 asercji PASS |
| Rzeczywisty UI5→CAP | brak | **16/16,73 asercje PASS**, ponowione na opublikowanym bb3b8d5 o21:30: adapter + OPA, A/B, 401/403/404, outage/restart |
| UI5 build / build:cf | PASS autora | PASS lokalnie |
| CF build invariants | 13 | 13/13 PASS |
| Browser runner | 4 | 4/4 PASS |
| AppRouter | 8 | 8/8 PASS |
| deploy config | 4 | 4/4 PASS |
| packaged CF JAR | 8 | 8/8 PASS |
| MTAR | brak zintegrowanego | **PASS**,67 511 817B,21:28 Europe/Warsaw; niewdrożony |
| Sekrety / whitespace | — | skan heurystyczny2576 plików i diff-check PASS przed commitem kodu |
| GitHub CI | sukces IN-02 wg zlecenia | odnotowano start run42895580546; wynik dla końcowego HEAD w checks PR, oddzielnie od powyższych wyników lokalnych |
| LIVE CF / KSeF / SAP WRITE | — | NIE WYKONANO, poza zakresem |

HTTP sprawdza status i JSON, nie tylko kompilację: brak JWT401, brak roli403,
obcy UUID404, scope/count i granty przed paging, list/key/select, contains/tolower/startswith,
apostrof, daty, statusy, sort numeryczny, null, wiele grantów i jednoczesne sesje A/B.

## Problems Encountered / Decisions

- CAP eq/ne jest normalizowany do IS/IS_NOT; whitelist uzupełniono po realnym błędzie400.
- Niedozwolony `$apply` powodował500; odrzucany przed wykonaniem, bez maskowania niepełnym200.
- QUnit dostarczony z UI5 nie ma assert.rejects; test HTTP używa jawnego try/catch.
- Nowe statusy stagingu zmieniły świadomie wynik formattera QUARANTINED; test i UI uaktualnione.
- Zachowano długie niestandardowe numery mock zamiast ucinać je do produkcyjnego MaxLength35.
- Windows blokował natywny moduł keyring przy npm ci w działającym UI5. Zatrzymano tylko własne procesy,
  ponowiony cały build MTAR PASS. Runner E2E uruchamiany po pakowaniu, aby nie kolidować z npm ci.
- UI5 lokalnie nasłuchuje localhost/IPv6; readiness runnera poprawiono na localhost zamiast127.0.0.1.
- CAP single-tenant `UserInfo.getTenant()` zwraca null. Zweryfikowany provider XSUAA nadpisuje
  zaufany atrybut `tenant` po xs.user.attributes z `token.getAppTid()` (app_tid/fallback zone_uuid).
  Polityka porównuje ten atrybut z bindingiem; testy ustawiają oba claimy tenanta w syntetycznym JWT.
- Anonimowy Basic mock uruchamiał niewidoczny challenge Chrome, opóźniając fetch do timeout.
  Runner CDP anuluje challenge na jawnym opt-in loopback, zachowując realną odpowiedź401.
  Testy bezpieczeństwa istniejącego runnera ponownie4/4 PASS.
- CI97391e1/run42896083489: Maven368 i MTAR PASS na Ubuntu26.04, potem runner Chrome zgłosił
  `DevTools endpoint not reachable` (2/4 runner FAIL). Przyczyna wewnętrzna Chrome nie była widoczna,
  bo stderr był ignorowany; nie uznano tego za PASS QUnit/OPA/E2E. `ubuntu-latest` wskazało nowy obraz26.04.
  Przypięto kontrolowany baseline `ubuntu-24.04`, dodano ograniczoną diagnostykę stderr/exit i czas30s
  oraz przeniesiono istniejący runner security przed kosztowny build. Bez no-sandbox i bez zmian CSP/auth.
  Lokalne testy runnera po tej zmianie4/4 PASS; wynik kolejnego CI w checks PR.
  [Oficjalny obraz Ubuntu24](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md)
  zawiera odsyłacz do ogłoszenia migracji ubuntu-latest na26.04 w listopadzie2026.
- Lokalny restart UI nie jest dowodem trwałości bazy CF; harness ponownie seeduje syntetyczne H2.
- Zastane74 npm advisories UI5 tooling pozostają; bez nowych zależności, nie są częścią statycznego CF.
  Szczegóły ograniczeń: [security review](../audits/2026-11-10-int-02-security-review.md).

## Current State / Owner Actions / Next Sprint

Implementacja, dokumentacja i pełna lokalna regresja gotowe. Kod wypchnięty, PR #6 otwarty jako stacked PR
i mergeable przy kontrolnym odczycie. Przy publikacji dokumentacji trwał GitHub CI;
bieżący wynik dla każdego HEAD jest w checks PR. Nie wykonano auto-merge ani zmian BTP.

Stan PR opisany powyżej dotyczy momentu publikacji. Wynik późniejszego merge opisano poniżej.
W osobnym zakresie BTP Cockpit: potwierdzić entitlementy HANA/HDI, durable storage i SSO/Role Collections
A/B oraz Destination użytkownika; zmiany kosztowe/aktywacja wymagają osobnego zlecenia.
W H01: kontynuować osobno bramki DCL/principal i autorytatywne przypisanie dokumentu do spółki;
INT-02 nie wymaga SAP WRITE ani nowych IAM zmian. Nie nadawać KSeF InvoiceRead przez samą rolę CAP.

Następny sprint: trwałość INBOUND na docelowej bazie/storage + SSO A/B i globalny budżet API,
następnie kontrolowany import syntetycznych danych KSeF TEST po osobnej autoryzacji.
XML download, księgowanie i CompanyCode mapping pozostają oddzielnymi kontraktami.

## Kontynuacja — merge do main i głównego worktree

2026-11-10, po zleceniu właściciela „możemy to mergować do main i głównego worktree?”:

- Ponownie sprawdzono GitHub, heads, CI i review obu PR. Brak review threads/requests changes;
  oba PR były OPEN/mergeable. IN-02/5659e18: CI run42879497576 SUCCESS.
  INT-02/efc6a91: **CI run42897303774 SUCCESS**, wszystkie kroki, w tym rzeczywiste HTTP/UI5 E2E.
- `git merge-tree --write-tree` przed merge potwierdził brak konfliktów i drzewo
  `dc57847ffcce8f6446e1c7661d43dc837eaa5562`, identyczne z efc6a91.
- PR #5 scalono metodą merge z kontrolą expected head5659e18:
  `3e7420db5ed91b5e17b77b367286a08011853730`.
- PR #6 przestawiono z feature/ksef-in-02-persistence na main, ponownie potwierdzono mergeable
  i identyczne drzewo. Merge z kontrolą expected headefc6a91:
  `21c2e488d9a8c0fc57fac98598f02af054e44f66`.
- Czysty `D:\AInvoicer` przełączono z feature/cap-07c-autonomous-hardening na main;
  `git pull --ff-only origin main` wykonał fast-forward z0297b87 do21c2e48.
  Lokalny HEAD/main i origin/main były zgodne; porównanie tree ponownie potwierdziło dokładną
  zgodność z przetestowaną integracją. Nie usunięto branchy ani worktree i nie zmieniano ich plików.
- `git diff --check` PASS. Zaktualizowano wyłącznie STATUS/ROADMAP i ten wpis o wynik merge;
  nie uruchamiano ponownie lokalnej regresji przy identycznym drzewie kodu. Automatyczny CI main
  po merge i commicie dokumentacyjnym jest osobnym przebiegiem, nie deklaracją kolejnego PASS.

Nie wykonano deployu CF, zmian BTP/H01, SAP WRITE, synchronizacji ani SEND KSeF.
Następny krok pozostaje taki jak wyżej: trwałość oraz SSO A/B w osobno zatwierdzonym zakresie.
