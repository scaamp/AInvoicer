---
title: "AInvoicer — aktualny stan"
date: 2026-10-23
type: status
status: w-trakcie
tags: [ainvoicer, cap-java, status]
---

# Stan projektu

[Project Home](README.md) · [Roadmapa](ROADMAP.md) · [Dziennik weryfikacji](journal/2026-10-23-cap-java-bootstrap.md)

## Current Phase

Phase 1 — CAP Java Bootstrap, w trakcie. Szkielet istnieje; uruchomienie aplikacji, persystencja H2 i własne endpointy OData nie są jeszcze potwierdzone.

## Architecture

SAPUI5 → OData V4 → CAP Java + Spring Boot → Java SDK KSeF w jednej JVM. SAP przez Remote OData/Destination jest źródłem danych finansowych; CAP przechowuje proces KSeF. H2 lokalnie, SAP HANA Cloud i SAP BTP Cloud Foundry docelowo. Szczegóły: [ADR-0001](decisions/ADR-0001-cap-java-ksef-architecture.md).

## Completed

- Istnieją `AGENTS.md`, `CLAUDE.md` i baza wiedzy `docs/`.
- Istnieje szkielet `cap-java/`: parent POM, moduł `srv`, `Application.java`, konfiguracja Spring, pliki npm/CDS. Inicjalizacja `cds init cap-java --java` — według kontekstu użytkownika.
- W POM: JDK 25, CAP Java 5.2.0, Spring Boot 4.1.1, minimalny Maven 3.9.14; adapter OData V4 i H2 jako zależności runtime.
- Użytkownik potwierdził wcześniejsze `mvn clean verify` zakończone sukcesem oraz pobranie zależności. Lokalnie potwierdzono budowanie CDS i kompilację Javy; pełny build obecnie kończy się błędem pakowania JAR.

## In Progress

- Weryfikacja bootstrapu. Własnych plików `.cds`, definicji usług KSeF, handlerów, testów, SAP Remote Service i Java SDK KSeF jeszcze nie ma.
- H2 ma zależność i `spring.sql.init.platform: h2` w profilu `default`; działanie bazy nieprzetestowane. `cds.data-source.auto-config.enabled: false` nie jest dowodem poprawnej persystencji.

## Blockers

- Pełne `mvn clean verify` nie zostało odtworzone: ostatnia próba kończy się `NoSuchFileException` dla `srv/target/classes/application.yaml` podczas pakowania JAR. Przyczyna nieustalona; wymaga sprawdzenia przed uruchomieniem aplikacji.
- Ograniczenia środowiska agenta: potrzebne JDK 25/Maven w PATH, wykonanie poza sandboxem (`spawn EPERM`) i nieustawione `DEBUG` dla builda (dodatkowy tekst zakłóca odczyt JSON wersji CDS). Szczegóły obejścia w dzienniku; bez zmian konfiguracji systemowej.

## Next Steps

1. Sprawdzić błąd pakowania JAR i możliwe równoległe operacje na `srv/target/`; odtworzyć pełne `mvn clean verify` z JDK 25 i bez `DEBUG`.
2. Uruchomić CAP Java lokalnie i potwierdzić start Spring Boot oraz połączenie z H2.
3. Dodać minimalny model `KsefOperations` / `KsefInvoices` i serwis CDS, a następnie sprawdzić `$metadata`, CRUD oraz walidację handlera.
4. Przed integracją potwierdzić rzeczywisty binding RAP, `$metadata` i dostęp autoryzowany do SAP; nie zgadywać kontraktu `ZFI_ACDOCA`.

## Verification

| Sprawdzenie | Ostatni dowód / ograniczenie |
| --- | --- |
| Inspekcja repozytorium | 2026-10-23: branch `java-cap-ksef`; na wejściu zmieniony `.gitignore`, nieśledzony `cap-java/` |
| Historyczne `mvn clean verify` | Sukces według użytkownika; wcześniejszy błąd Enforcer przy JDK 21 również z relacji użytkownika |
| Bieżące `mvn clean verify` | 2026-10-23: FAIL na pakowaniu JAR (`NoSuchFileException: .../target/classes/application.yaml`); wcześniej Enforcer, CDS i kompilacja PASS. JDK 25.0.4.1 / Maven 3.9.16, poza sandboxem, bez `DEBUG` |
| Testy Java | Maven: `No tests to run`; brak własnych źródeł testowych — nie jest to PASS testów funkcjonalnych |
| Dokumentacja | PASS: YAML w 6 notatkach, 45 lokalnych linków (30 wewnątrz Vault), reguła ignorowania Obsidian i `git diff --check`; bez zmian źródeł aplikacji |
| Spring Boot / H2 / własne OData / CRUD | Nie uruchamiano w tej sesji; brak potwierdzenia działania |
| SAP / KSeF TEST / UPO | Nie testowano live w tej sesji; wcześniejszy eksperyment na osobnej gałęzi nie potwierdza E2E CAP |

## Last Updated

2026-10-23 (Europe/Warsaw). Stan jest migawką; przed kolejną sesją porównaj go z kodem i Git.
