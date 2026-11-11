---
title: "KSEF-IN-03A — globalny budżet API i hardening współbieżności"
date: 2026-11-11
type: journal
status: offline-implemented-live-blocked
tags: [ainvoicer, journal, ksef, inbound, cap-java, concurrency, rate-limit]
---

# KSEF-IN-03A — globalny budżet API

[Audyt, architektura, kontrakt i plan HANA](../guides/KSEF_IN_03A_GLOBAL_API_BUDGET.md).

## Date / Branch / Goal

11 listopada 2026, Europe/Warsaw. `feature/ksef-in-03a-global-api-budget`, osobny worktree
`D:\AInvoicer-ksef-in-03a` (poza worktree Codexa), baza po `git fetch origin main`:
`319adbf77941f6f0d9edf4fcb9af08e80ee83bc8`. Główne `D:\AInvoicer` czyste na zastanym `main=f04fb56`;
bez przełączania gałęzi, pull do tego checkoutu ani ingerencji w pozostałe worktree.
Cel: audyt i testowalna polityka offline, bez aktywacji INBOUND, zmian CDS/infrastruktury i LIVE.
Osobny commit/push/PR do review wynika z kryteriów bieżącego zlecenia; bez merge.

## Initial State

Przeczytano reguły AGENTS i Skills CAP/KSeF, STATUS/ROADMAP/README, dokumentację i dziennik IN-02,
architekturę IN-01, ADR-0001/0004 oraz aktualne notatki CAP-HANA-01 i STOR-01.
Kod i testy potwierdzają: dwa endpointy INBOUND, retry osobno per strona/GET, per-scope lease
i fencing checkpointu. Brak budżetu między rolami/NIP/workerami. Domyślny wiring jednego TEST/BUYER,
OFF; guard CF zachowany. Auth/refresh/certyfikaty to osobne HTTP poza retry INBOUND.

Oficjalne publiczne źródła MF sprawdzono bez wywoływania API KSeF: `git ls-remote` przewodnika
zwrócił `ce3670ea042de380039de4c5849fd6455811b292`; źródła SDK v3.0.29 i limity udokumentowano
w przewodniku. Sprawdzono również dokumentację CAP Persistence Services i Event Queues.
Nie potwierdzono limitów indywidualnych, aktualnego runtime API ani egress CF.

## Implementation

- `InboundApiBudget`: immutable konfiguracja, ALLOW/DEFER/REJECT; jednoczesne okna przesuwne
  aplikacji i endpointu/kontekstu/egress; wspólny limit in-flight, Retry-After i watermark czasu.
- Każda próba atomowo obciąża budżet; in-flight nie wygasa; complete zachowuje opłatę od zakończenia.
  Niepewny commit, niedostępny stan i błędy czasu zamykają bramkę bez fallbacku.
- `ApiBudgetRepository`: kontrakt atomowego transition/commit; immutable State/Attempt/Change.
- `InMemoryApiBudgetRepository`: synchronizacja jednego obiektu repo w JVM, jawnie offline,
  bez trwałości i bez Spring. Różne polityki mogą korzystać z jednego repo, ale różne repo/procesy
  mają niezależny budżet (jawny test ograniczenia).
- JUnit 5: syntetyczne limity, sterowany Clock, bariery 24 workerów, awarie i niepewne commit.
- Przewodnik zawiera audyt, wybór algorytmu, przyszły model/CAS HANA, ryzyka dostępności,
  plan integracji każdej próby wewnątrz retry oraz niewykonane testy wieloinstancyjne.

## Changed Files

Trzy nowe klasy `cap-java/srv/src/main/java/customer/cap_java/ksef/inbound/budget/`,
`InboundApiBudgetTest.java` w analogicznym katalogu testów, przewodnik i ten dziennik.
Brak zmian istniejącego kodu, testów, POM, runtime wiring, CDS i adapterów storage.
Świadome odstępstwo dokumentacyjne: zlecenie zabrania zmian STATUS/ROADMAP/README, więc nie są
aktualizowane ani indeksowane. ADR-0008/BLOB-LAB-01/CAP-HANA-01R pozostają poza zakresem.

## Commands / Toolchain

- `git status --short`, `git worktree list`, `git fetch origin main`,
  `git worktree add -b feature/ksef-in-03a-global-api-budget D:/AInvoicer-ksef-in-03a origin/main`.
  Usunięto początkowy upstream do main, aby push miał jawny feature refspec.
- `java -version`: Microsoft OpenJDK **25.0.4.1**; `mvn -version`: **3.9.16**.
  CAP **5.2.0**, Boot **4.1.1**, SDK MF **3.0.29**, cds-dk **10.2.0**, CDS **10.2.1**,
  Node pluginu **24.22.0**, npm **11.19.1**. Wersje zależności bez zmian.
- `mvn -B test -Dtest=InboundApiBudgetTest -Dsurefire.failIfNoSpecifiedTests=false`:
  pierwsza próba **FAIL przed kompilacją** — plugin nie odczytał wersji cds-dk z wyniku JSON.
- Ponowienie z `-Dcds.cds.skipVersionCheck=true`: **37/37 PASS** w pierwotnej wersji.
  Flaga zgodna z zastanym `deploy/scripts/identity-smoke.mjs` i wcześniejszymi dziennikami;
  nie wyłącza kompilacji modelu ani testów. POM/lock bez zmian.
- `mvn -B clean verify -Dcds.cds.skipVersionCheck=true`: build i regresja pierwotnej wersji PASS;
  po review rozszerzono rozliczanie in-flight/complete i dodano dwie regresje, a następnie
  uruchomiono pełne clean verify ponownie dla końcowej implementacji.

## Test Results

Końcowy `mvn -B clean verify -Dcds.cds.skipVersionCheck=true`: **BUILD SUCCESS**,
**468 testów: 462 PASS, 6 skipped, 0 failures/errors**, w tym **39/39 PASS** nowego komponentu.
Sumy potwierdzono niezależnie z XML Surefire. Sześć pominiętych testów wymaga emulatora Azurite;
nie są testami nowego budżetu. Tagi LIVE nadal wykluczone; profil `hana-integration` niewłączony.
Budowa CDS/H2, kompilacja Java 25, istniejące testy mock/HTTP loopback/H2/recovery i oba JAR PASS.

PowerShell 5.1 przy przekierowaniu `*>` oznaczył stderr Mockito/ByteBuddy jako NativeCommandError
i zwrócił status powłoki 1 mimo BUILD SUCCESS oraz zerowych failures/errors. Dla jednoznacznego
native exit code uruchomiono końcowe polecenie przez `cmd.exe /d /c` z przekierowaniem wewnętrznym
i jawnym odczytem `$LASTEXITCODE`; nie zmieniano konfiguracji/testów z powodu tego ostrzeżenia.
Potwierdzenie końcowe: **MAVEN_EXIT_CODE=0, BUILD SUCCESS, ponownie 468/0 failures/0 errors/6 skipped**.

Weryfikacja względnych linków nowych dokumentów: **10/10 PASS**. `git diff --cached --check`: **PASS**.
Heurystyczny skan wzorców sekretów w 564 śledzonych plikach poza vendor `node_modules`: **0 flag**;
odpowiednik reguł zastanego skanera, wykonany stdlib Python bez instalacji/modyfikacji `deploy/`.
Kontrola staged paths: dokładnie sześć nowych plików, brak zabronionych ścieżek i artefaktów builda.
Kontrola źródeł nowego komponentu: brak HTTP/SDK client, adnotacji Spring/schedulera i `Thread.sleep`.

## Problems / Limits

`npm ci` zgłasza zastane dwa ostrzeżenia high severity; nie wykonywano automatycznego upgrade
ani `npm audit fix --force`. Zmiana zależności wykracza poza zakres tego sprintu.
Build generuje lokalne CDS/JAR, ale artefakty nie trafiają do commita.

Nie ma współdzielonego adaptera HANA ani health/recovery globalnego dispatch po niepewnym complete.
Sam port tego nie dostarcza: integracja musi wstrzymać wszystkich workerów do potwierdzenia stanu.
Brak automatycznego TTL permitów oznacza możliwą trwałą utratę dostępności po crash; celowo nie
odzyskujemy slotu bez dowodu zakończenia starego HTTP. Brak fairness i pomiarów obciążenia HANA.
Testy są deterministyczne co do decyzji/liczby dopuszczeń, a timeouty służą wykryciu deadlocku.
Nie są dowodem rozliczenia ruchu przez MF ani zachowania dwóch JVM/HANA.

## Next Step

Review portu, transakcji HANA i recovery/health po niepewnym complete. Po CAP-HANA-01 osobna
decyzja CDS i implementacja adaptera oraz testy dwóch JVM na HANA. Dopiero potem osobne zlecenie
integracji per próba retry, z zachowaniem guardów i bez automatycznej aktywacji synchronizacji.
