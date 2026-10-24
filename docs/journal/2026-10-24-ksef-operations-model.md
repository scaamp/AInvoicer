---
title: "Minimalny model CDS KsefOperations"
date: 2026-10-24
type: journal
status: zakonczony
tags: [ainvoicer, journal, cap-java, cds, ksef]
---

# Minimalny model CDS KsefOperations

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [ADR-0001](../decisions/ADR-0001-cap-java-ksef-architecture.md)

## Date

2026-10-24 (Europe/Warsaw).

## Branch

`java-cap-ksef` — potwierdzony przez `git status --short --branch`.

## Goal

Dodać `cap-java/db/schema.cds` z minimalną encją `KsefOperations`, używając aspektów CAP `cuid` i `managed`. SAP document reference pozostaje tekstem; zadanie nie dodaje encji SAP ani relacji do niej.

## Initial State

- Branch `java-cap-ksef`, repozytorium początkowo zgłaszało `ahead 1` oraz nieśledzony katalog `cap-java/db/`.
- `cap-java/db/schema.cds` istniał jako pusty, nieśledzony plik.
- W projekcie nie było innych własnych plików CDS; parent POM deklaruje CAP Java `5.2.0`. Lokalnie rozwiązano `@sap/cds-dk 10.2.0`, `@sap/cds 10.2.1` i compiler `7.2.1`.
- Dokumentacja wskazywała Phase 2 jako rozpoczętą, bez modelu domenowego.

## Implementation

- Uzupełniono pusty plik modelu o namespace `customer.cap_java`, zgodny z pakietem Java projektu.
- `KsefOperations` używa `cuid` i `managed`. Dodałem pola korelacji i procesu, w tym typ wyliczeniowy kierunku, rozszerzalny tekstowy status z domyślną wartością `NEW` oraz opcjonalne referencje i bezpieczne pola opisu błędu.
- Unikalność klucza idempotencji jest ograniczona do pary `(taxpayerNip, idempotencyKey)`, co definiuje kontekst podatnika. Nie dodano relacji do SAP.
- Zaktualizowano STATUS, ROADMAP i indeks README. Brak nowej decyzji architektonicznej.

## Changed Files

- `cap-java/db/schema.cds` — model jednej encji.
- `docs/STATUS.md` — bieżący postęp i zakres walidacji.
- `docs/ROADMAP.md` — rozdzielono ukończoną minimalną encję od dalszych prac nad modelem.
- `docs/README.md` — link do tego dziennika.
- Ten wpis — zakres i wyniki sesji.

## Commands Executed

Wszystkie polecenia repozytorium wykonano w PowerShell.

- Inspekcja: `git status --short --branch`, odczyt dokumentacji, ADR, zawartości `cap-java/db/`, POM i `package.json`.
- Wersje: CAP Java `5.2.0` z POM, lokalne `@sap/cds-dk 10.2.0`, `@sap/cds 10.2.1`, CDS compiler `7.2.1`, Node `24.13.0`, npm `11.6.2`.
- `node_modules/.bin/cds.cmd --version` z `D:\AInvoicer\cap-java` — nie powiodło się w sandboxie, ponieważ CLI nie mogło utworzyć procesu potomnego (`spawn EPERM`).
- `node -e` z `D:\AInvoicer\cap-java`, ładując model przez lokalny `@sap/cds-dk/node_modules/@sap/cds` — model załadował się i zwrócił wszystkie 15 oczekiwanych pól oraz metadane ograniczenia unikalności.
- `git diff --check` z katalogu repozytorium — bez błędów whitespace; Git zgłosił tylko ostrzeżenia o normalizacji LF do CRLF dla trzech plików dokumentacji.
- Pełnego `mvn clean verify` nie uruchomiono.

## Tests & Results

| Sprawdzenie | Wynik |
| --- | --- |
| Wczytanie modelu przez API CDS | PASS: wykryto 15 oczekiwanych pól; `@assert.unique.idempotency` zawiera `taxpayerNip` oraz `idempotencyKey`. |
| `cds --version` CLI | NIE POWIODŁO SIĘ: sandbox zgłosił `spawn EPERM`; nie jest to wynik walidacji składni modelu. |
| Maven build / testy / runtime / OData | NIE WYKONANO: zakres tej zmiany nie zawierał usługi CDS; brak `$metadata` i CRUD pozostaje do kolejnego kroku Phase 2. |

## Problems Encountered

CLI CDS nie uruchomiło procesu potomnego w sandboxie. Odczyt modelu przez API biblioteki CDS w tym samym procesie Node zakończył się powodzeniem. Maven build nie był potrzebny do sprawdzenia samych pól, więc nie uruchomiono go.

## Decisions Made

- `taxpayerNip` oraz `idempotencyKey` są wymagane; unikalność obejmuje ich parę.
- Kierunek jest typem wyliczeniowym `INBOUND` / `OUTBOUND`; typ i status operacji pozostają tekstem, by model zachował możliwość rozbudowy.
- `sapDocumentRef` jest wartością tekstową bez asocjacji do fikcyjnej lokalnej encji SAP.

## Current State

Minimalny model `KsefOperations` jest wczytywalny przez lokalną bibliotekę CDS. Nie potwierdzono kompilacji Maven, persystencji, endpointu OData ani CRUD. Etap Phase 2 pozostaje w toku.

## Next Steps

1. Dodać pozostały model domenowy i definicję usługi CDS.
2. Zbudować i uruchomić aplikację, a następnie sprawdzić `$metadata`, persystencję H2 i CRUD.
