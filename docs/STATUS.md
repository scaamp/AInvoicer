---
title: "AInvoicer — aktualny stan"
date: 2026-10-24
type: status
status: w-trakcie
tags: [ainvoicer, cap-java, status]
---

# Stan projektu

[Project Home](README.md) · [Roadmapa](ROADMAP.md) · [Dziennik bootstrapu](journal/2026-10-23-cap-java-bootstrap.md) · [Weryfikacja Phase 1](journal/2026-10-24-cap-java-bootstrap-verification.md) · [Model KsefOperations](journal/2026-10-24-ksef-operations-model.md) · [Usługa i CRUD OData](journal/2026-10-24-ksef-service-definition.md) · [Walidacja i autoryzacja KsefOperations](journal/2026-10-24-ksef-operations-validation-authorization.md)

## Current Phase

Phase 2 — CAP Domain Model. `KsefOperations`, `KsefInvoices` i `KsefService` działają przez OData V4/H2. Faktury są tylko do odczytu dla `KsefOperator` w zakresie `taxpayerNips`; pola statusu i numeru KSeF są tylko do odczytu, referencje XML/UPO i nawigacja do faktury są wyłączone z publicznego kontraktu. Testy integracyjne Spring Security + CAP + OData + H2 potwierdzają filtrację oraz blokadę zapisu. H2 nie generuje fizycznego FK dla tej asocjacji, dlatego backendowa ścieżka zapisująca relację musi sprawdzić zgodność NIP. Nie obejmuje to IAS/XSUAA, SAP Remote Service ani KSeF.

## Architecture

SAPUI5 → OData V4 → CAP Java + Spring Boot → Java SDK KSeF w jednej JVM. SAP przez Remote OData/Destination jest źródłem danych finansowych; CAP przechowuje proces KSeF. H2 lokalnie, SAP HANA Cloud i SAP BTP Cloud Foundry docelowo. Szczegóły: [ADR-0001](decisions/ADR-0001-cap-java-ksef-architecture.md).

## Completed

- Istnieją `AGENTS.md`, `CLAUDE.md` i baza wiedzy `docs/`.
- Utworzono trzy projektowe Skills w `.agents/skills/` i krótkie odwołania Claude w `.claude/skills/`: ABAP/RAP, CAP Java i KSeF. Codex CLI 0.164.0 potwierdził wszystkie trzy przez `skills/list` jako `enabled: true`, `scope: repo`, bez błędów ładowania. To potwierdza wykrywanie, nie automatyczny dobór przez model. Szczegóły i przykłady: [dziennik Skills](journal/2026-10-23-project-skills.md).
- Istnieje szkielet `cap-java/`: parent POM, moduł `srv`, `Application.java`, konfiguracja Spring, pliki npm/CDS. Inicjalizacja `cds init cap-java --java` — według kontekstu użytkownika.
- W POM: JDK 25, CAP Java 5.2.0, Spring Boot 4.1.1, minimalny Maven 3.9.14; adapter OData V4, H2 i Spring Security jako zależności runtime.
- Użytkownik potwierdził wcześniejsze `mvn clean verify`; 2026-10-24 wykonano dwa pełne buildy `mvn clean verify` na JDK 25.0.4.1 / Maven 3.9.16 — oba zakończone `BUILD SUCCESS`.
- Uruchomiono executable JAR: CAP Runtime 5.2.0 i Spring Boot 4.1.1 wystartowały na porcie 8080; `http://localhost:8080/` zwrócił HTTP 200. Test integracyjny potwierdził `SELECT 1` przez `JdbcTemplate` na H2 in-memory.
- `KsefService` wymaga `authenticated-user`; encja dopuszcza wyłącznie CREATE i READ roli `KsefOperator`, ograniczone do NIP z `$user.taxpayerNips`. CAP Java sprawdza też warunek NIP wejściowego przy CREATE. Testowi operatorzy są zdefiniowani wyłącznie w `src/test/resources/application-test.yaml`.
- `KsefOperationsHandler` waliduje sumę kontrolną NIP, kierunek, mały katalog typów operacji, wymagany i przycięty klucz idempotencji oraz wymusza `NEW`. Nie zastępuje handlerami standardowego CRUD.
- Phase 2.3: dodano trwałą encję `KsefInvoices` (status biznesowy `UNCONFIRMED`) oraz opcjonalną asocjację `KsefOperations.invoice`. Obie encje używają wspólnego typu `KsefDirection` (`INBOUND`/`OUTBOUND`). Publiczny `KsefService` wystawia faktury tylko do odczytu z filtrem NIP; nie publikuje asocjacji ani referencji magazynowych XML/UPO. Testy potwierdziły OData `$metadata`, brak `$expand`/ustawiania FK przez klienta i poprawne wewnętrzne powiązanie w H2.
- CAP CDS `Association` opisuje referencję i nie usuwa rekordu docelowego z kaskadą; `Composition` reprezentowałaby własność i cykl życia potomka, więc nie pasuje do historii operacji, którą należy zachować. Asocjacja CAP przypomina `Association` w ABAP CDS jako deklarację nawigacji, lecz nie nadaje automatycznie identycznych reguł persystencji ani autoryzacji. DDL H2 zawiera kolumnę `invoice_ID`, lecz bez ograniczenia FK; lokalna baza in-memory nie zachowuje danych po restarcie.
- `mvn clean verify '-Dcds.cds.skipVersionCheck=true'` PASS: 15 testów, w tym 14 integracyjnych przez losowy port HTTP i 1 test H2. Zwykłe `mvn clean verify` nadal kończy się `spawn EPERM` podczas kontroli `cds version`; obejście nie naprawia problemu Node.

## In Progress

- Lokalna konsola H2 działa pod `/h2-console` w profilu `default`, z ograniczeniem dostępu H2 do localhost. Test połączenia konsoli do jawnego Spring datasource zwrócił `Test successful`; OData odczytuje tabelę, obecnie pustą po restarcie bazy in-memory. SQL z przeglądarki nie został jeszcze wykonany.
- Phase 2 — model i odczyt `KsefInvoices` ukończone lokalnie. Pozostaje ustalenie backendowej ścieżki tworzącej relację i wymuszającej tę samą wartość NIP w warunkach H2, który nie ma FK dla asocjacji.
- Skills: statyczna walidacja i ręczna ocena czterech scenariuszy zakończone; automatyczna aktywacja przez model oraz wykrywanie w sesji Claude Code pozostają nieprzetestowane (potwierdzono tylko wersję CLI Claude 2.1.309).

## Blockers

- Brak potwierdzonych blockerów. Poprzedni `NoSuchFileException: srv/target/classes/application.yaml` nie odtworzono. Zwykły Maven przebieg zatrzymuje się na weryfikacji wersji CDS przez proces potomny (`spawn EPERM`); build z `-Dcds.cds.skipVersionCheck=true` przeszedł.

## Next Steps

1. Dodać zaufaną ścieżkę aplikacyjną do łączenia operacji z fakturą, walidującą zgodność NIP przed zapisem; nie udostępniać powiązania przez publiczne OData.
2. W Phase 3 potwierdzić rzeczywisty binding RAP, `$metadata` i dostęp autoryzowany do SAP; nie zgadywać kontraktu `ZFI_ACDOCA`.

Dla narzędzi agentowych: w następnej sesji Claude Code uruchomić `/reload-skills`, sprawdzić trzy komendy `/ainvoicer-*` i odczyt kanonicznych plików; osobno potwierdzić automatyczny dobór w czterech scenariuszach z dziennika. Brak MCP ABAP FS w obecnej sesji nie pozwala potwierdzić workflow SAP live.

## Verification

| Sprawdzenie | Ostatni dowód / ograniczenie |
| --- | --- |
| Inspekcja repozytorium | 2026-10-24: branch `java-cap-ksef`; drzewo początkowo czyste, szkielet CAP śledzony |
| Model CDS `KsefOperations` | 2026-10-24: utworzono encję `customer.cap_java.KsefOperations` z `cuid`, `managed`, kierunkiem INBOUND/OUTBOUND, statusem domyślnym NEW oraz unikalnością `(taxpayerNip, idempotencyKey)`. PASS: model wczytany przez API kompilatora CDS w procesie Node; CLI `cds --version` nie działa w sandboxie (`spawn EPERM`), Maven build nieuruchomiony. |
| Usługa CDS / OData V4 | 2026-10-24: `KsefService` wymaga `authenticated-user`; status, referencje KSeF i pola błędu są `@readonly`. PASS: build CDS/Maven, metadata (anonimowy 401, authenticated 200), lokalne CREATE/READ/UPDATE/DELETE na H2. Status utworzył się jako NEW mimo próby podania ACCEPTED; pola techniczne wysłane przez klienta były ignorowane. |
| Phase 2.2 — walidacja i autoryzacja | PASS lokalnie 2026-10-24: `@restrict` CREATE/READ dla `KsefOperator` i `$user.taxpayerNips`; 14 testów OData przez Spring Boot + Spring Security + CAP + H2 potwierdziło izolację, także dla braku atrybutu, walidację, brak UPDATE/DELETE i unikalność. Mock users tylko w profilu testowym. IAS/XSUAA nie skonfigurowano. |
| Historyczne `mvn clean verify` | Sukces według użytkownika; wcześniejszy błąd Enforcer przy JDK 21 również z relacji użytkownika |
| Bieżące `mvn clean verify` | PASS 2026-10-24 (2 uruchomienia; drugie po dodaniu testu H2). Enforcer, CDS build/generacja, resources, kompilacja, JAR i Spring Boot repackage PASS. `cap-java.jar` (6,583 B), `cap-java-exec.jar` (57,567,847 B); `target/classes/application.yaml` istnieje. Poprzedni wyjątek nie został odtworzony, więc przyczyna pozostaje nieustalona. |
| Testy Java / H2 | PASS: `H2DataSourceIntegrationTest`, 1 test, 0 błędów; Spring Boot context, JdbcTemplate i `SELECT 1` na H2 in-memory. Nie testuje CRUD encji CDS. |
| Dokumentacja | Wcześniejsze sesje: PASS YAML/linków i `git diff --check`. Weryfikacja Phase 1: dzisiejszy wpis podlinkowany w indeksie; `git diff --check` bez błędów. |
| Project Skills | 2026-10-23: PASS walidacji sześciu `SKILL.md`, odwołań i ręcznej oceny doboru; PASS wykrywania trzech kanonicznych Skills przez Codex `skills/list`. Szczegóły końcowej kontroli linków/YAML w dzienniku Skills |
| Automatyczna aktywacja Skills / Claude runtime | NIE WYKONANO: nie uruchamiano modelu ani interaktywnego selektora `/skills`; samo `claude --version` nie potwierdza wykrywania Skills |
| Runtime / HTTP | PASS: executable JAR; CAP Runtime 5.2.0 i Spring Boot 4.1.1 wystartowały, Tomcat port 8080; `/odata/v4/KsefService/$metadata` zwrócił 401 anonimowo i 200 dla mock user. CRUD zweryfikowano lokalnie na H2. |
| Runtime Phase 2.2 | PASS 2026-10-24: executable JAR CAP Runtime 5.2.0 / Spring Boot 4.1.1 z testowym profilem uruchomiony na 8081; HTTP CREATE zwrócił 201 i status `NEW` mimo payload `ACCEPTED`, READ zwrócił 200 z operacją przypisaną do `operator-a`. |
| Phase 2.3 — KsefInvoices | PASS lokalnie 2026-10-24: `mvn clean verify '-Dcds.cds.skipVersionCheck=true'`; CDS wygenerował schemat H2 i metadata OData. Testy HTTP potwierdziły READ tylko dla NIP operatora, 401 anonimowo, 403 bez roli, brak CREATE/PATCH/DELETE, brak pól XML/UPO i asocjacji w metadata, odmowę `$expand` oraz odmowę klientowskiego `invoice_ID`. Zaufany test zapisał operację z istniejącą fakturą przez CAP Persistence Service i potwierdził FK przez JDBC w H2. Razem: 20 testów, 0 błędów. H2 DDL nie zawiera constraint FK; nie testowano trwałości po restarcie, bo konfiguracja jest in-memory. |
| `mvn spring-boot:run` | Nie uruchomiono: `-pl srv` nie spełnia reactor convergence, a `-pl srv -am` blokuje sandbox `spawn EPERM` w CDS. Zamiast tego uruchomiono spakowany executable JAR. |
| Konsola H2 w przeglądarce | 2026-10-24: `/h2-console/` HTTP 200; H2 Test Connection dla `jdbc:h2:mem:capjava;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE` zwrócił `Test successful`; OData odczyt encji HTTP 200. Zapytanie SQL z GUI nie wykonano. |
| Własne OData / CDS CRUD | PASS lokalnie 2026-10-24: `mvn clean verify '-Dcds.cds.skipVersionCheck=true'`; OData V4 przez profile mock test, Spring Security i H2. Obejście pomija tylko kontrolę wersji CDS; nie potwierdza naprawy `spawn EPERM`. |
| SAP / KSeF TEST / UPO | Nie testowano live w tej sesji; wcześniejszy eksperyment na osobnej gałęzi nie potwierdza E2E CAP |

## Last Updated

2026-10-24 (Europe/Warsaw), po lokalnych testach modelu i autoryzacji KsefInvoices. Stan jest migawką; przed kolejną sesją porównaj go z kodem i Git.
