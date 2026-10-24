---
title: "KsefService — OData V4 i lokalny CRUD"
date: 2026-10-24
type: journal
status: zakonczony
tags: [ainvoicer, journal, cap-java, cds, odata-v4, ksef]
---

# KsefService — OData V4 i lokalny CRUD

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Model CDS](2026-10-24-ksef-operations-model.md)

## Date

2026-10-24 (Europe/Warsaw).

## Branch

`java-cap-ksef` — potwierdzony przez `git status --short --branch`.

## Goal

Wystawić encję `KsefOperations` przez OData V4 w `KsefService`, umożliwić lokalny podstawowy CRUD i ograniczyć zapis statusu oraz danych technicznych KSeF pochodzących z klienta.

## Initial State

- Istniał model `cap-java/db/schema.cds` z encją `customer.cap_java.KsefOperations`; katalog roboczy miał już zmiany dokumentacji i modelu z poprzedniego zadania.
- `cap-java/srv/` nie zawierał definicji usługi CDS.
- `srv/pom.xml` zależał od `cds-starter-spring-boot`, ale nie deklarował `spring-boot-starter-security`, potrzebnego przez CAP Java do mock users.
- `application.yaml` nie definiował własnych kont ani dodatkowej konfiguracji uwierzytelniania.

## Implementation

- Dodano `KsefService` jako prostą projekcję `KsefOperations`; wystawiany jest adapter OData V4 CAP pod ścieżką `/odata/v4/KsefService`.
- Serwis wymaga roli `authenticated-user`. Do `srv/pom.xml` dodano `spring-boot-starter-security`, aby CAP mógł aktywować mock authentication w lokalnym profilu.
- `status`, `referenceNumber`, `ksefNumber`, `errorCode` i `errorMessage` oznaczono `@readonly`. CAP ignoruje podane przez klienta wartości podczas CREATE/UPDATE. Model persystencji zawiera `DEFAULT 'NEW'`, więc nowa operacja zaczyna się od stanu NEW.
- Nie dodano akcji zmiany statusu, API KSeF, ani autoryzacji per NIP; te elementy wymagają późniejszej logiki serwerowej i konfiguracji tożsamości.

## Changed Files

- `cap-java/srv/ksef-service.cds` — projekcja OData i reguły dostępu/pól.
- `cap-java/srv/pom.xml` — Spring Security do mock users w lokalnym development.
- `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/README.md` — status, plan i indeks.
- Ten wpis — decyzje i dowody z sesji.
- `.vscode/settings.json` pojawił się jako zmodyfikowany podczas tej pracy (dodana konfiguracja aktualizacji Java build). Nie edytowałem go; pozostawiam zmianę do oceny właściciela workspace.

## Commands Executed

Windows / PowerShell. Wersje: Microsoft JDK `25.0.4.1`, Maven `3.9.16`, CDS Maven plugin `5.2.0`, CDS DK `10.2.0`, CAP compiler `7.2.1`, Spring Boot `4.1.1`.

- Inspekcja: `git status --short --branch`, odczyt modelu, `application.yaml`, parent i modułowego POM.
- Wczytanie modelu przez lokalne API CDS — PASS; potwierdzono `@requires: authenticated-user` i pięć pól `@readonly`.
- Z `D:\AInvoicer\cap-java`: `mvn clean verify` — FAIL w sandboxie na `spawn EPERM` podczas `cds version`; próba z eskalacją zakończyła się również błędem niedostępnej informacji o wersji.
- Z `D:\AInvoicer\cap-java`: `mvn clean verify '-Dcds.cds.skipVersionCheck=true'` — BUILD SUCCESS. Flaga omija wyłącznie sprawdzenie wersji; cel kompilacji CDS działał, wygenerował OData EDMX, klasy Java i schemat H2. Test H2: 1/1 PASS.
- Uruchomiono `srv/target/cap-java-exec.jar` lokalnie, wykonano HTTP/OData na `localhost:8080` i zatrzymano uruchomiony proces.

## Tests & Results

| Sprawdzenie | Wynik |
| --- | --- |
| OData `$metadata` bez uwierzytelnienia | PASS kontroli dostępu: HTTP 401. |
| OData `$metadata` z mock user `authenticated` | PASS: HTTP 200. |
| CREATE + READ syntetycznej operacji H2 | PASS: zwrócono ID i status `NEW`; próby podania `ACCEPTED`, referencji KSeF, numeru KSeF i błędu przez klienta zostały zignorowane. |
| UPDATE | PASS: `sapDocumentRef` zmienił się, a status i pola techniczne pozostały niezmienione. |
| DELETE | PASS: testowy rekord usunięto. |
| `mvn clean verify '-Dcds.cds.skipVersionCheck=true'` | PASS: build CDS i Java oraz istniejący `H2DataSourceIntegrationTest` (1 test, bez błędów). |
| SAP/KSeF live, walidacja NIP, uprawnienia do kontekstu podatnika | NIE WYKONANO. |

## Problems Encountered

Zwykły przebieg Maven/CDS próbuje wywołać proces potomny przy `cds version`, co w tym środowisku zwraca `spawn EPERM`. Parametr pluginu `cds.cds.skipVersionCheck` pominął tylko kontrolę wersji; właściwy CDS build, generowanie klas oraz deploy dry-run do schematu H2 zakończyły się powodzeniem. Przy pierwszym niecytowanym przekazaniu parametru PowerShell rozdzielił nazwę systemowej właściwości Maven i Maven zgłosił nieznaną fazę; poprawne cytowanie usunęło problem.

## Decisions Made

- Ograniczenie do zalogowanego użytkownika jest deklaratywne w CDS; lokalne żądania wykorzystują wbudowaną mock authentication CAP Java.
- Ochrona pól jest egzekwowana przez runtime `@readonly`, a nie wyłącznie przez wskazówki w OData metadata.
- Serwis jest minimalnym etapem developmentu. Nie należy traktować ogólnej roli `authenticated-user` jako docelowej autoryzacji operacji względem NIP lub organizacji.

## Current State

Lokalny, uwierzytelniony CRUD KsefOperations przez CAP OData V4 i H2 został potwierdzony. Konsument może odczytywać status, lecz nie może ustawiać statusu, numeru/referencji KSeF ani pól błędu. Pełny workflow zmiany stanu i autoryzacja zakresu podatnika nie są zaimplementowane.

## Next Steps

1. Dodać walidację wejścia i serwerową kontrolę, kto może tworzyć/odczytywać operacje danego podatnika.
2. Kontynuować model encji `KsefInvoices` i rozdzielenie stanu biznesowego od technicznego.
