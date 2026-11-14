---
title: "KSEF-IN-03B — audyt INBOUND, scheduler OFF i G1 po BlobService"
date: 2026-11-14
type: journal
status: zakonczony
tags: [ainvoicer, journal, ksef, inbound, scheduler, hdi, g1]
---

# KSEF-IN-03B — worker readiness i G1

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) ·
[Przewodnik i audyt](../guides/KSEF_IN_03B_WORKER_READINESS.md)

## Date

2026-11-14, Europe/Warsaw.

## Branch

`feature/ksef-in-03b-worker-readiness`, worktree `D:\AInvoicer-ksef-in-03b`.

## Goal

Audyt istniejącego pipeline, minimalny scheduler offline OFF, ocena globalnego budżetu API,
ścisły G1 po wdrożeniu BlobService, regresja i osobny otwarty PR do main.
Bez merge, operacji LIVE, zmian CDS/LAB/storage, MTA, IAM/Destination i danych BLOB.

## Initial State

`git status --short` głównego checkoutu: czysty. `git fetch origin main` i oba `rev-parse`:
`7a47ac92d07fa8b66868e7293eb4f4bc2a07a6e0`. Osobny worktree z aktualnego origin/main;
checkout właściciela pozostaje bez zmian. Przeczytano AGENTS, CLAUDE, STATUS/ROADMAP,
ORCH-06, przewodniki IN-01/02/03A, G1 i BLOB-LAB-01, ADR-0001 i ADR-0009.
Zastosowano skills CAP Java i KSeF. Użytkownik wyraźnie zlecił build/regresję offline i PR;
nie rozszerza to zakresu właścicielskiego laboratorium ani KSEF-STOR-03 Claude Code.

Wersje: JDK Microsoft 25.0.4.1, Maven 3.9.16, Node 24.13.0, npm 11.6.2,
CAP Java 5.2.0, Boot 4.1.1, SDK MF 3.0.29. Compiler CDS 7.2.1 / cds-dk 10.2.0 / CAP Node 10.2.1
z lockfile. Terser generatora przypięty 5.44.1. Go lokalnie niedostępne; CI ma przypięte Go 1.26.10.

## Implementation

- Potwierdzono działający warunkowy DI i cały pipeline; nie odtwarzano istniejących komponentów.
- Dodano `KsefInboundSchedulerConfiguration` i `InboundSyncScheduler`: dwa opt-in, initial/fixed delay
  15 min, bramka gotowości aplikacji, AtomicBoolean i wyłącznie stałe kody wyniku.
  `ApplicationReadyEvent` tylko uzbraja zegar. Start/close nie wywołują synchronizacji.
- Zachowano guard CF bez zmian; in-memory budget nadal niepodłączony. Kontrakt wieloinstancyjny
  opisano do osobnego zatwierdzenia; bez nowych encji i bez migracji.
- G1 rozdziela CORE / OWNER LAB / OWNER LAB OData; dokładnie 15/5/6/15. Piąty widok wymaga
  stanu TRUE i dokładnej definicji SQL; porównywanie tokenów zachowuje case cytowanych nazw i schemat.
  Test kompiluje niezmieniony CDS w pamięci i porównuje nazwy, PK, projekcję i media metadata.
- Generator taska minifikuje AST przez Terser przed Brotli, zachowując limit command 4096.
  Runtime taska nadal korzysta wyłącznie z Node+hdb. Zgodny eksport: 70 wierszy, pięć parametrów schematu.
  Console zawsze OWNER-REPORTED, sterownik SCRIPT_MEASURED; żadnego LIVE dowodu nie uzyskano.
- CI instaluje lockfile cap-java przed testem niezależnej kompilacji HDI; bez nowych sekretów/uprawnień.

## Changed Files

- Scheduler i konfiguracja Java, `application.yaml` (wyłącznie default OFF i komentarz),
  test konfiguracji, nowy test schedulera, dodatkowy test konkurencji koordynatorów w CQN/H2.
- `deploy/scripts/hdi-catalog-readonly.cjs`, test G1, `deploy/package*.json` (dev Terser),
  `.github/workflows/ci.yml` (instalacja narzędzia CDS przed testem).
- Przewodnik IN-03B, aktualizacja przewodnika G1, ten dziennik, STATUS/ROADMAP/README.
- **Bez zmian:** documents.cds, blob-service.cds, BlobDocumentsHandler, KsefDocumentStore
  i adaptery storage, modele CDS, MTA, role, IAM, Destination i LAB. Artefakty/logi buildów są ignorowane.

## Commands Executed

W katalogu worktree, poza wskazanymi katalogami podrzędnymi:

```text
git fetch origin main
git rev-parse HEAD / origin/main
git worktree add -b feature/ksef-in-03b-worker-readiness D:/AInvoicer-ksef-in-03b origin/main
npm ci --prefix cap-java --ignore-scripts
npm ci --prefix deploy --ignore-scripts
npm install --save-dev --save-exact terser@5.44.1 --prefix deploy --ignore-scripts
npm rebuild mbt --prefix deploy
npm ci --prefix sapui5/ainvoicer --ignore-scripts
node --test deploy/test/hdi-catalog-readonly.test.mjs
npm test --prefix deploy
npm run validate:smoke --prefix deploy
node deploy/scripts/check-secrets.mjs
mvn -B clean verify '-Dcds.cds.skipVersionCheck=true' (cap-java/)
npm run test:runner --prefix sapui5/ainvoicer
npm run build:btp:smoke --prefix deploy
npm run build:btp:hana --prefix deploy
npm run verify:hana --prefix deploy
node --input-type=module -e "import {assemble,verify} from './deploy/scripts/hana-it-runner.mjs'; assemble(); verify();"
node deploy/scripts/hana-it-runner.mjs test
npm ci --prefix deploy/storage --ignore-scripts
npm test --prefix deploy/storage
npm run build / npm run serve-mock / npm test (sapui5/ainvoicer/)
npm run test:inbox:e2e --prefix sapui5/ainvoicer
git diff --check
```

## Tests & Results

Końcowa regresja zamrożonego kodu, wyłącznie lokalnie i offline:

| Zakres | Wynik offline |
| --- | --- |
| G1 | 63/63 PASS: dokładny piąty widok, obcy szósty, SQL/source/schema/case/kolumny/validity, pełny runtime taska i niezależna kompilacja CDS |
| Deploy | 105 PASS / 1 SKIP (Go lokalnie); smoke validate PASS |
| Browser runner | 8/8 PASS na rzeczywistym lokalnym Chrome |
| Finalny Maven clean verify w smoke | **497 run / 0 failures / 0 errors / 6 skipped**, BUILD SUCCESS; skippy Azurite wykonano osobno z emulatorem |
| W tym INBOUND | Scheduler **4/4**, konfiguracja **11/11**, CQN/H2 **23/23**, budżet **39/39**, recovery dwóch JVM **1/1** PASS |
| Zapakowany CF JAR / UI5 CF / AppRouter | **10/10**, **13/13**, **11/11** PASS |
| MTAR smoke + HANA + verify:hana | **PASS**, source/release hashes PASS; artefakty niewdrożone |
| R2 | assemble/verify PASS i **5/5** testów procesu; wykorzystano finalny JAR zamiast ponawiać pełny Maven z komendy build |
| Azurite | **6/6 PASS**, oficjalny emulator wyłącznie na loopback, bez zewnętrznego storage |
| UI5 | Build PASS; QUnit **112/112** (659 asercji), OPA **21/21** (150 asercji) PASS |
| Inbox na rzeczywistym lokalnym CAP HTTP | **16 testów / 73 asercje PASS**, A/B, odmowy dostępu, błąd i restart |
| Skan / whitespace | Heurystyczny check-secrets **2688 plików PASS**, git diff --check PASS |
| LIVE | NIE WYKONANO: KSeF, SAP WRITE, CF/HANA, G1, deploy, restarty, DDL/DML i service keys |

Lokalny mock UI5 na porcie 8085 uruchomiono tylko na czas testów i zatrzymano po identyfikacji
własnego procesu worktree. Procesy CAP/Inbox/emulatora zakończyły ich harnessy.

Runtime generatora G1 (LF): SHA-256
`6ebdfd14863cc7a69514e3065759712c3485d51053c052b7b9e68a632aad5785`,
command domyślny 4071, core-only 4075 znaki. Przed ewentualną zgodą LIVE przeliczyć ponownie na finalnym commicie.

## Problems Encountered

Pierwsze `mvn clean verify` bez dotychczasowego obejścia projektu zatrzymało się na parsowaniu
`cds version --json`: narzędzie wypisuje dodatkową linię debug mimo dostępnego cds-dk.
Ponowiono z istniejącym w buildzie deploy `-Dcds.cds.skipVersionCheck=true`; nie wyłączono kompilacji,
testów ani kontroli wersji zależności POM. Nie obniżano JDK/SDK/CAP.
Jedna próba flagi bez cytowania w PowerShell została błędnie rozbita na argumenty;
cytowany parametr działa. Ten błąd polecenia nie jest wynikiem testów.

Pierwszy build smoke przeszedł testy i utworzył MTAR, lecz poprawnie odrzucił końcowe release evidence:
w trakcie builda dodano bramkę ApplicationReady i testy. Zmieniono wejścia po ich zahashowaniu.
Powtórzono cały smoke na zamrożonych źródłach: Maven 497 i oba etapy walidacji MTAR/evidence PASS.
Nie użyto starego pakietu jako dowodu finalnego kodu.

Po rozszerzeniu G1 polecenie taska miało 4807 znaków. Zamiast zwiększać limit, dodano minifikację AST
z przypiętym Terser. Testy uruchamiają dokładny wynik minifikacji i kompresji, także negatywne scenariusze.
Istniejące advisories toolchainu npm: cap-java 2 HIGH, UI5 74 (2 low/15 moderate/54 high/3 critical);
deploy po dodaniu Terser audit 0. Nie wykonywano niezleconych aktualizacji toolchainu.

Końcowy review wykrył akceptację niepoprawnego nagłówka `CREATE SELECT` bez `VIEW` w importerze
metadanych. Dodano wymaganie `VIEW` po `CREATE` i scenariusz negatywny, także dla spakowanego runtime.
G1 63/63 i deploy 105 PASS/1 Go SKIP po poprawce. Nie zmienia to modelu ani kodu Java.
Końcowy HEAD ponownie przechodzi CI; wcześniejszy run nie stanowi jego akceptacji.

## Decisions Made

Nie implementowano nowej persystencji budżetu, bo wymaga zmiany schematu/G1 i osobnej decyzji.
Scheduler jest wyłącznie lokalnym opt-in OFF, a nie nowym aktywnym workerem CF.
Istniejący lease zapewnia fencing HWM, ale po expiry nie gwarantuje wyłączności HTTP między JVM;
brak renew/sprawdzenia każdej próby pozostaje jawnie opisanym blockerem. Nowy ADR niepotrzebny:
właściciel danych i architektura nie zmieniają się, kontrakt persystencji jest nadal propozycją IN-03A.

## Current State

Audyt, implementacja i regresja offline zakończone. **GO do niezależnego review; CF NO-GO:** globalny budżet i jego wiring,
lease per dispatch, zatwierdzony adapter HANA BLOB z KSEF-STOR-03, testy HANA/multi-JVM,
security acceptance i odrębna decyzja wdrożeniowa. G1 LIVE PENDING.

## Publikacja i CI

Utworzono i wypchnięto commit implementacji `41b4dd3eba1c12621c72e53697dc935fe281da26`.
[PR #19](https://github.com/scaamp/AInvoicer/pull/19) jest otwarty do `main`, bez merge i auto-merge.
Kolejny commit dokumentuje publikację; późniejszy patch G1 zamyka opisany przypadek `CREATE` bez `VIEW`.
[Checks aktualnego HEAD](https://github.com/scaamp/AInvoicer/pull/19/checks) są odrębnym dowodem CI,
wymaganym przed niezależnym review. Końcowy raport agenta podaje zweryfikowane SHA i wynik tego HEAD.
Zielone CI nie oznacza G1 HANA LIVE ani zgody na aktywację.

Polecenia publikacji: `git add` wyliczonych 17 plików, `git diff --cached --check`, `git commit`,
`git push -u origin feature/ksef-in-03b-worker-readiness`; PR utworzono konektorem GitHub.
Nie zmieniono głównego checkoutu i nie wypchnięto niczego na main.

## Next Steps

1. Niezależny review dokładnego HEAD i jego CI w otwartym PR; bez automatycznego merge.
2. Osobna decyzja właściciela o read-only G1.
3. Oddzielnie zatwierdzić globalny budżet/migrację, lease dispatch i KSEF-STOR-03; scheduler CF nadal OFF.
