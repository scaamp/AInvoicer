---
title: "AInvoicer — aktualny stan"
date: 2026-10-24
type: status
status: w-trakcie
tags: [ainvoicer, cap-java, status]
---

# Stan projektu

[Project Home](README.md) · [Roadmapa](ROADMAP.md) · [Dziennik bootstrapu](journal/2026-10-23-cap-java-bootstrap.md) · [Weryfikacja Phase 1](journal/2026-10-24-cap-java-bootstrap-verification.md)

## Current Phase

Phase 1 — CAP Java Bootstrap, zakończona dla zakresu szkieletu. Pełny Maven build, uruchomienie CAP/Spring Boot, HTTP Index i połączenie H2 z `SELECT 1` zostały potwierdzone lokalnie. Własny model CDS, endpointy OData i CRUD pozostają zakresem Phase 2.

## Architecture

SAPUI5 → OData V4 → CAP Java + Spring Boot → Java SDK KSeF w jednej JVM. SAP przez Remote OData/Destination jest źródłem danych finansowych; CAP przechowuje proces KSeF. H2 lokalnie, SAP HANA Cloud i SAP BTP Cloud Foundry docelowo. Szczegóły: [ADR-0001](decisions/ADR-0001-cap-java-ksef-architecture.md).

## Completed

- Istnieją `AGENTS.md`, `CLAUDE.md` i baza wiedzy `docs/`.
- Utworzono trzy projektowe Skills w `.agents/skills/` i krótkie odwołania Claude w `.claude/skills/`: ABAP/RAP, CAP Java i KSeF. Codex CLI 0.164.0 potwierdził wszystkie trzy przez `skills/list` jako `enabled: true`, `scope: repo`, bez błędów ładowania. To potwierdza wykrywanie, nie automatyczny dobór przez model. Szczegóły i przykłady: [dziennik Skills](journal/2026-10-23-project-skills.md).
- Istnieje szkielet `cap-java/`: parent POM, moduł `srv`, `Application.java`, konfiguracja Spring, pliki npm/CDS. Inicjalizacja `cds init cap-java --java` — według kontekstu użytkownika.
- W POM: JDK 25, CAP Java 5.2.0, Spring Boot 4.1.1, minimalny Maven 3.9.14; adapter OData V4 i H2 jako zależności runtime.
- Użytkownik potwierdził wcześniejsze `mvn clean verify`; 2026-10-24 wykonano dwa pełne buildy `mvn clean verify` na JDK 25.0.4.1 / Maven 3.9.16 — oba zakończone `BUILD SUCCESS`.
- Uruchomiono executable JAR: CAP Runtime 5.2.0 i Spring Boot 4.1.1 wystartowały na porcie 8080; `http://localhost:8080/` zwrócił HTTP 200. Test integracyjny potwierdził `SELECT 1` przez `JdbcTemplate` na H2 in-memory.

## In Progress

- Phase 2 — model domenowy CDS, OData V4 i CRUD. Własne pliki `.cds` i endpoint `$metadata` jeszcze nie istnieją.
- Skills: statyczna walidacja i ręczna ocena czterech scenariuszy zakończone; automatyczna aktywacja przez model oraz wykrywanie w sesji Claude Code pozostają nieprzetestowane (potwierdzono tylko wersję CLI Claude 2.1.309).

## Blockers

- Brak potwierdzonych blockerów Phase 1. Poprzedni `NoSuchFileException: srv/target/classes/application.yaml` nie odtworzono; oba buildy skopiowały zasoby i spakowały JAR. Sandbox blokuje proces potomny CDS (`spawn EPERM`), więc build wykonano poza nim, z JDK/Maven ustawionymi tylko dla procesu.

## Next Steps

1. Rozpocząć Phase 2: zaprojektować encje `KsefOperations` / `KsefInvoices` i serwis CDS, następnie sprawdzić `$metadata`, CRUD oraz walidację/persistencję H2.
2. W Phase 3 potwierdzić rzeczywisty binding RAP, `$metadata` i dostęp autoryzowany do SAP; nie zgadywać kontraktu `ZFI_ACDOCA`.

Dla narzędzi agentowych: w następnej sesji Claude Code uruchomić `/reload-skills`, sprawdzić trzy komendy `/ainvoicer-*` i odczyt kanonicznych plików; osobno potwierdzić automatyczny dobór w czterech scenariuszach z dziennika. Brak MCP ABAP FS w obecnej sesji nie pozwala potwierdzić workflow SAP live.

## Verification

| Sprawdzenie | Ostatni dowód / ograniczenie |
| --- | --- |
| Inspekcja repozytorium | 2026-10-24: branch `java-cap-ksef`; drzewo początkowo czyste, szkielet CAP śledzony |
| Historyczne `mvn clean verify` | Sukces według użytkownika; wcześniejszy błąd Enforcer przy JDK 21 również z relacji użytkownika |
| Bieżące `mvn clean verify` | PASS 2026-10-24 (2 uruchomienia; drugie po dodaniu testu H2). Enforcer, CDS build/generacja, resources, kompilacja, JAR i Spring Boot repackage PASS. `cap-java.jar` (6,583 B), `cap-java-exec.jar` (57,567,847 B); `target/classes/application.yaml` istnieje. Poprzedni wyjątek nie został odtworzony, więc przyczyna pozostaje nieustalona. |
| Testy Java / H2 | PASS: `H2DataSourceIntegrationTest`, 1 test, 0 błędów; Spring Boot context, JdbcTemplate i `SELECT 1` na H2 in-memory. Nie testuje CRUD encji CDS. |
| Dokumentacja | Wcześniejsze sesje: PASS YAML/linków i `git diff --check`. Weryfikacja Phase 1: dzisiejszy wpis podlinkowany w indeksie; `git diff --check` bez błędów. |
| Project Skills | 2026-10-23: PASS walidacji sześciu `SKILL.md`, odwołań i ręcznej oceny doboru; PASS wykrywania trzech kanonicznych Skills przez Codex `skills/list`. Szczegóły końcowej kontroli linków/YAML w dzienniku Skills |
| Automatyczna aktywacja Skills / Claude runtime | NIE WYKONANO: nie uruchamiano modelu ani interaktywnego selektora `/skills`; samo `claude --version` nie potwierdza wykrywania Skills |
| Runtime / HTTP | PASS: executable JAR; CAP Runtime 5.2.0 i Spring Boot 4.1.1 wystartowały, Tomcat port 8080, `/` HTTP 200 (CAP Index). Adapter mapowany pod `/odata/v4`; bez modelu usług brak własnego `$metadata`. |
| `mvn spring-boot:run` | Nie uruchomiono: `-pl srv` nie spełnia reactor convergence, a `-pl srv -am` blokuje sandbox `spawn EPERM` w CDS. Zamiast tego uruchomiono spakowany executable JAR. |
| Własne OData / CDS CRUD | NIE WYKONANO — brak modelu/usługi, zakres Phase 2. |
| SAP / KSeF TEST / UPO | Nie testowano live w tej sesji; wcześniejszy eksperyment na osobnej gałęzi nie potwierdza E2E CAP |

## Last Updated

2026-10-24 (Europe/Warsaw), po weryfikacji Phase 1 CAP Java Bootstrap. Stan jest migawką; przed kolejną sesją porównaj go z kodem i Git.
