---
title: "E2E-01A — SAP RAP → FA(3), przygotowanie offline bez SEND"
date: 2026-11-15
type: journal
status: zakonczony
tags: [ainvoicer, journal, e2e, rap, cap-java, ksef, fa3, preflight]
---

# E2E-01A — preflight SAP i FA(3)

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) ·
[Raport, mapping i runbook](../guides/E2E_01A_SAP_FA3_PREFLIGHT.md)

## Date

2026-11-15, Europe/Warsaw.

## Branch

`feature/e2e-01a-sap-fa3-preflight`, osobny worktree `D:\AInvoicer-e2e-01a`.

## Goal

Audyt kontraktu RAP → FA(3), dry-run bez zapisów/rezerwacji/outboxa i efektów zewnętrznych,
regresja CAP, raport oraz PR do main z CI. Bez SEND/open/close, SAP WRITE/aktywacji/tworzenia danych,
CF deploymentu, HANA SQL i merge. Bez zmian gałęzi KSEF-IN-04/KSEF-STOR-04.

## Initial State

`git fetch origin main`; oba `rev-parse` = `c5041bdd934638b99587c757588d706868e16633`, drzewo czyste.
Przeczytano AGENTS, STATUS, ROADMAP, najnowszy dziennik IN-03B, ADR-0001/0005,
runbook E2E-01 i przewodnik FA(3), zasady BLOB-LAB-01. Zastosowano Skills CAP Java, KSeF i ABAP/RAP.
MCP ABAP FS nie jest udostępniony; nie wykonywano alternatywnego dostępu do H01 ani nie używano
credentiali z innych sesji. Własne wyniki są offline; historyczny READ 4/9/6 ma osobne oznaczenie.

Wersje: Microsoft JDK25.0.4.1, Maven3.9.16, CAP Java5.2.0, Boot4.1.1, SDK3.0.29.
Tag SDK v3.0.29 sprawdzony read-only przez ls-remote: `3c3a899541074aa4bd944636b25659aef7d23da7`.
Oficjalna dokumentacja SAP Remote Services, sesji MF i XSD odczytana; Swagger dynamiczny bez
czytelnej treści kontraktu. Nie zmieniano zależności ani plików produkcyjnych.

## Implementation

- Testowy `SapFa3Preflight`: jawny UUID/zakres/wersje, autoryzacja przed READ, SELECT historii/rezerwacji,
  rzeczywisty adapter i generator, PartyData backendu, kontrola domeny/XSD/sum, drugi READ i SELECT.
- Zależności wyłącznie SAP READ, PersistenceService SELECT, PartyData i FA(3); bez endpointu i DI produkcyjnego.
- Testy rzeczywistego adaptera CQN z mock RemoteService oraz generatora z przypiętym zegarem.
  Oddzielne testy H2 sprawdzają rzeczywiste zapytania rezerwacji/historii i brak nowych rekordów.
- Raport z mapowaniem RAP → FA(3), brakami danych, minimalną propozycją faktury dla właściciela,
  wymaganym PartyData i szczegółowym runbookiem przyszłego pojedynczego SEND.
- Audyt wskazuje luki: brak ponownego SAP READ w workerze przed SEND, kontrola kompozycji dopiero
  w diagnostyce, brak kontroli biznesowego statusu w requestSend i niepełna walidacja kwot/waluty pozycji
  w mapperze. Dodatkowe kontrole harnessu nie zmieniają tych właściwości produkcyjnego workflow.
- Odczyt oficjalnego XSD MF: 183798 bajtów, SHA-256 `b646b6b525f51adf1bb2545f111fc8ca6e7aa6dd2f98948f1667d3695c06d958`,
  zgodny bajtowo z repo. Nie modyfikowano XSD ani importów.

## Changed Files

- `cap-java/srv/src/test/java/customer/cap_java/ksef/SapFa3Preflight.java` — wewnętrzny harness, poza JAR.
- `SapFa3PreflightTest.java` w tym samym katalogu — pipeline adapter/generator/XSD i negatywne przypadki.
- `SapFa3PreflightPersistenceTest.java` — rzeczywista lokalna H2, blokady i brak zapisów dry-run.
- Raport `docs/guides/E2E_01A_SAP_FA3_PREFLIGHT.md`, ten dziennik, STATUS/ROADMAP/README.

Logi i wygenerowane target/gen/node_modules są ignorowane; nie dodawano XML ani credentiali do Git.
BLOB-LAB-01, storage, CDS, ABAP, UI5, MTA, IAM/Destination i domyślne flagi pozostawiono bez zmian.

## Commands Executed

Z worktree, poza wskazanymi katalogami:

```text
git fetch origin main
git rev-parse HEAD origin/main
git status --short
git worktree add -b feature/e2e-01a-sap-fa3-preflight D:/AInvoicer-e2e-01a origin/main
java -version
mvn -version
git ls-remote https://github.com/CIRFMF/ksef-client-java.git refs/tags/v3.0.29
mvn -B verify -Dcds.cds.skipVersionCheck=true -Dtest=SapFa3PreflightTest -Dsurefire.failIfNoSpecifiedTests=false (cap-java/)
mvn -B clean verify -Dcds.cds.skipVersionCheck=true (cap-java/)
jar tf srv/target/cap-java.jar (cap-java/, kontrola braku harnessu)
npm ci --prefix deploy --ignore-scripts
node deploy/scripts/check-secrets.mjs
git diff --check
```

Dodatkowo odczyt HTTPS XSD MF do pamięci i SHA-256 przez .NET, bez zapisu pobranego XML/XSD.
Publikacja Git/PR i kontrola workflow wyłącznie dla tej gałęzi; bez merge i bez działań LIVE.

## Tests & Results

- Focused harness: **27/27 PASS**, domena/XSD/maxOccurs, **1719 bajtów**, SHA-256
  `d7824c805b244622c0c8779f1f2577c4cb930d8d5146f228b47f304a55a82105`.
  Tylko fixture OFFLINE `00000000-0000-4000-8000-00000000001a`, nie dokument H01.
- Pierwsza pełna regresja: **524 testy, 0 failures, 0 errors, 6 skipped**, BUILD SUCCESS (2:21).
  Po niej dodano testy rzeczywistej H2. Końcowa pełna regresja: **527 testów, 0 failures,
  0 errors, 6 skipped**, BUILD SUCCESS (2:22), w tym **30 nowych przypadków PASS** (27 pipeline + 3 H2).
- Skan wzorców sekretów i `git diff --check`: PASS; skan jest heurystyką, nie audytem credentiali.
- Harness nie znalazł się w produkcyjnym JAR (`jar tf`). XML nowego dry-run pozostaje w pamięci.
- H01 READ / SAP WRITE / KSeF / HANA / CF: **NOT EXECUTED**.
- CI: przy publikacji dokumentu wynik należy sprawdzić dla **dokładnego HEAD** w
  [workflow gałęzi](https://github.com/scaamp/AInvoicer/actions/workflows/ci.yml?query=branch%3Afeature%2Fe2e-01a-sap-fa3-preflight).
  Końcowe PR/SHA/run i wynik podano również w opisie PR oraz raporcie końcowym sesji;
  lokalny PASS nie jest zamiennikiem CI.

## Problems Encountered

Pierwsza kompilacja testu używała nieistniejącego Row.create; poprawiono zgodnie z istniejącymi
testami adaptera na mock Row. Pierwsze wykonanie testów wykazało zagnieżdżone stubbing Mockito;
przygotowanie Result przeniesiono przed stubbing przez doReturn. Ponowny focused run: 27/27 PASS.
Te błędy dotyczyły nowego kodu testowego, bez wpływu na runtime/SAP.
Skan sekretów początkowo wymagał brakującego lokalnego pakietu yaml; po `npm ci --prefix deploy
--ignore-scripts` PASS. Próbę inspekcji JAR w trakcie `clean verify` powtórzono po zbudowaniu artefaktu;
brak pliku w trakcie builda nie został potraktowany jako wynik kontroli.

Brak narzędzi SAP i zatwierdzonego kandydata H01 blokuje realny snapshot/FA(3) z H01.
Seed w eksporcie ma niepoprawne kontrolnie NIP; walidator odrzuca go lokalnie.
Nie obchodzono walidacji ani guardu transportu, nie zmieniano danych SAP.

## Decisions Made

Harness pozostaje testowy, zamiast dodawać publiczny endpoint lub zmieniać requestSend.
Powtórny READ daje dowód driftu podczas próby, bez deklaracji blokady po zakończeniu.
Hash offline nie zatwierdza przyszłego requestSend (nowy DataWytworzeniaFa zmieni bajty).
Nie wprowadzano nowej decyzji architektonicznej ani migracji; luki produkcyjne opisano jawnie.

## Current State

Preflight offline gotowy; **SEND NO-GO**. Brak dowodu gotowości konkretnego dokumentu SAP.
Poza nowymi testami nie zmieniano produkcyjnej ścieżki, danych ani infrastruktury.

## Next Steps

1. Właściciel wskazuje/oddzielnie tworzy syntetyczny UUID H01 i zatwierdza read-only kontrolę danych/principal.
2. Rozstrzygnąć drift kompozycji i kontrole statusu/pozycji; zatwierdzić runtime/storage i ponowić preflight
   z rzeczywistym PartyData, stanem DB i wersjami. Zrealizować oddzielne bramki bezpieczeństwa.
3. Dopiero po osobnej zgodzie przejść runbook jednego SEND; osobny plan SAP writebacku i rekoncyliacji.
