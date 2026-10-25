---
title: "AInvoicer — aktualny stan"
date: 2026-10-25
type: status
status: w-trakcie
tags: [ainvoicer, cap-java, status]
---

# Stan projektu

[Project Home](README.md) · [Roadmapa](ROADMAP.md) · [Workflow abapGit](guides/ABAPGIT_WORKFLOW.md) · [Model RAP faktur](guides/ABAP_RAP_INVOICE_MODEL.md) · [Dziennik próby tworzenia ABAP FS](journal/2026-10-25-abapfs-create-probe.md) · [Dziennik kompozycji RAP](journal/2026-10-24-abap-rap-composition.md) · [Dziennik pierwszego eksportu](journal/2026-10-24-abapgit-rap-initial-export.md) · [Dziennik bootstrapu](journal/2026-10-23-cap-java-bootstrap.md) · [Weryfikacja Phase 1](journal/2026-10-24-cap-java-bootstrap-verification.md) · [Model KsefOperations](journal/2026-10-24-ksef-operations-model.md) · [Usługa i CRUD OData](journal/2026-10-24-ksef-service-definition.md) · [Walidacja i autoryzacja KsefOperations](journal/2026-10-24-ksef-operations-validation-authorization.md)

## Current Phase

Phase 2 — CAP Domain Model. `KsefOperations`, `KsefInvoices` i `KsefService` działają przez OData V4/H2. Faktury są tylko do odczytu dla `KsefOperator` w zakresie `taxpayerNips`; pola statusu i numeru KSeF są tylko do odczytu, referencje XML/UPO i nawigacja do faktury są wyłączone z publicznego kontraktu. Testy integracyjne Spring Security + CAP + OData + H2 potwierdzają filtrację oraz blokadę zapisu. H2 nie generuje fizycznego FK dla tej asocjacji, dlatego backendowa ścieżka zapisująca relację musi sprawdzić zgodność NIP. Nie obejmuje to IAS/XSUAA, SAP Remote Service ani KSeF.

## Architecture

SAPUI5 → OData V4 → CAP Java + Spring Boot → Java SDK KSeF w jednej JVM. SAP przez Remote OData/Destination jest źródłem danych finansowych; CAP przechowuje proces KSeF. H2 lokalnie, SAP HANA Cloud i SAP BTP Cloud Foundry docelowo. Szczegóły: [ADR-0001](decisions/ADR-0001-cap-java-ksef-architecture.md).

## Completed

- 2026-10-25: instrukcje `AGENTS.md`, Skill ABAP/RAP i workflow abapGit zaktualizowano do zweryfikowanego ABAP FS **2.12.4 / fix 5**. Wcześniejszy test akceptacyjny tej sesji: klasa + CDS w obu pakietach Cloud, **4/4 PASS**, bez nowych requestów i aktywacji. Dwa obiekty lokalne bez transportu, dwa w `ZAINVOICER_DEV` z potwierdzonym przypisaniem do wcześniej zatwierdzonego requestu. [Dowody i ograniczenia](journal/2026-10-25-abapfs-v5-instructions.md). Nie oznacza to ukończenia biznesowych DCL ani testów RAP.
- Istnieją `AGENTS.md`, `CLAUDE.md` i baza wiedzy `docs/`.
- 2026-10-24: pierwszy eksport abapGit potwierdzony w `39ab869` na `java-cap-ksef`; `.abapgit.xml` kieruje pakiet `ZAINVOICER` do `/abap/src/` przy `FOLDER_LOGIC=FULL`. Repo zawiera trzy tabele symulacyjnego modelu i RAP wyłącznie dla nagłówka. Szczegóły i granice potwierdzenia: [workflow abapGit](guides/ABAPGIT_WORKFLOW.md), [model RAP](guides/ABAP_RAP_INVOICE_MODEL.md) i [dziennik](journal/2026-10-24-abapgit-rap-initial-export.md).
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

- SAP H01 — 2026-10-25: przez poprawione MCP utworzono `ZR_AINV_INV_REF`, `ZC_AINV_INV_I`, `ZC_AINV_INV_REF`; `ZR_AINV_INV_I` już istniał. Aktywne są wszystkie 6 CDS, oba BDEF oraz SRVD `ZAPI_AINV_INV_H_O4`. Usunięto niedozwolone `Semantics.unitOfMeasure` z widoków pozycji, jawny provider contract z projekcji dzieci i zastąpiono ETag dependent własnym `etag master LocalLastChangedAt` dzieci; root zachowuje ETag, numbering i lock master. ATC głównego BDEF: 0 ustaleń. Aktywna klasa `ZCL_AINV_RAP_COMP_TEST` zawiera 4 testy EML, lecz wykonanie przerwał dump braku aktywnego DCL; wrapper MCP dodatkowo zgłosił błąd `toFixed`. DCL odłożono na wyraźną decyzję użytkownika. Szczegóły: [kontynuacja live](journal/2026-10-24-abap-rap-composition.md#kontynuacja-live-przez-poprawione-mcp--2026-10-25).
- Lokalna konsola H2 działa pod `/h2-console` w profilu `default`, z ograniczeniem dostępu H2 do localhost. Test połączenia konsoli do jawnego Spring datasource zwrócił `Test successful`; OData odczytuje tabelę, obecnie pustą po restarcie bazy in-memory. SQL z przeglądarki nie został jeszcze wykonany.
- Phase 2 — model i odczyt `KsefInvoices` ukończone lokalnie. Pozostaje ustalenie backendowej ścieżki tworzącej relację i wymuszającej tę samą wartość NIP w warunkach H2, który nie ma FK dla asocjacji.
- Skills: statyczna walidacja i ręczna ocena czterech scenariuszy zakończone; automatyczna aktywacja przez model oraz wykrywanie w sesji Claude Code pozostają nieprzetestowane (potwierdzono tylko wersję CLI Claude 2.1.309).
- SAP RAP / Git: eksport `abap/src/` nie został zaktualizowany po zmianach live. Cztery źródła wymagają późniejszego eksportu z SAP: interface pozycji, obie projekcje dzieci i root BDEF; nowa klasa testowa nie ma jeszcze eksportu. Nie importować starego patcha do H01. Pakiet nadal ma `recordChanges=false` / `ZLOCAL`; kontrole nowych obiektów wskazały `not-locked` i pusty request, mimo komunikatów create „Transport requested … (backend assignment not verified)”. Klient 100 potwierdzony dumpem backendu; dokładne wydanie nieustalone. Nie wykonywano commit/push ani eksportu abapGit.
- Odczyt requestów wymienionych przez create (`H01K900028`, `H01K900030`, `H01K900032`, `H01K900036`) potwierdził 0 obiektów w każdym; requestów nie zwalniano ani nie usuwano. Mechanizm/czas ich utworzenia nieustalony; nie są dowodem wymogu transportu dla `ZAINVOICER`.
- ABAP FS create probe (2026-10-25): w pakiecie `ZAINVOICER_DEV` utworzyła się klasa `ZCL_AINV_FS_PROBE`; tworzenie `TABL/DT` (`ZAINV_FSPROBE`), `DDLS/DF` (`ZI_AINV_FS_PROBE`) i `DTEL/DE` (`ZDE_AINV_FS_PROBE`) zwróciło `Unsupported Media Type`, a wyszukiwanie nie wykazało częściowych obiektów. Klasa jest szkieletem bez metod. Narzędzie create zgłosiło brak transportu, lecz odczyt statusu i zawartości requestu wykazał przypisanie do `H01K900018` / task `H01K900019`; request zawiera już łącznie 6 obiektów, w tym pakiet i cztery inne klasy. Nie zmieniano ani nie zwalniano requestu, nie aktywowano klasy. Wymaga uzgodnienia właściciela/transportu przed dalszym użyciem. Szczegóły: [dziennik próby](journal/2026-10-25-abapfs-create-probe.md).

## Blockers

- ABAP-02: brak aktywnych DCL dzieci przy `authorizationCheck: #MANDATORY` blokuje EML; dump `ACM_UNEXPECTED_VALUE` wskazał `ZR_AINV_INV_I`, `Access Control Existence: FALSE`. W poprzedniej próbie `DCLS/DL` zwracało `Unsupported Media Type`; nie jest to bieżący test v5. Użytkownik odłożył biznesowe DCL; istniejące DCL root/projection/item pozostawiono inactive, trzech pozostałych brakuje. Nie osłabiano autoryzacji. OData `$metadata`/`$expand` niepotwierdzone: narzędzie odczytu obiektów odrzuca runtime URL jako `Invalid Object URL`.
- SAP RAP / ABAP FS: historyczny `Unsupported Media Type` i myląca informacja „local” z [próby starszej wersji](journal/2026-10-25-abapfs-create-probe.md) nie są aktualnym ogólnym blokerem v5. Test v5 potwierdził tworzenie klasy/CDS i transporty w obu pakietach Cloud. Dalsze zmiany biznesowe nadal wymagają sprawdzenia aktualnego pakietu/requestu i zakresu zadania; nie ponawiać historycznych prób w ciemno. Nie potwierdzono pełnego testu 23 typów na v5 ani ECC.
- Poprzedni `NoSuchFileException: srv/target/classes/application.yaml` nie odtworzono. Zwykły Maven przebieg zatrzymuje się na weryfikacji wersji CDS przez proces potomny (`spawn EPERM`); build z `-Dcds.cds.skipVersionCheck=true` przeszedł.

## Next Steps

1. Dodać zaufaną ścieżkę aplikacyjną do łączenia operacji z fakturą, walidującą zgodność NIP przed zapisem; nie udostępniać powiązania przez publiczne OData.
2. Po wznowieniu zakresu DCL utworzyć brakujące role `ZR_AINV_INV_REF`, `ZC_AINV_INV_I`, `ZC_AINV_INV_REF`, porównać i aktywować wszystkie wymagane DCL; następnie uruchomić `ZCL_AINV_RAP_COMP_TEST`. DCL pozostaje obecnie odłożony decyzją użytkownika.
3. Przetestować autoryzowane OData `$metadata`/`$expand` pod adresem odczytanym z bindingu; sprawdzić izolację i zapis/persystencję. Dopiero po walidacji oraz osobnym zleceniu wyeksportować zmiany abapGit; bez automatycznego pull przy brudnym checkout.

Dla narzędzi agentowych: w następnej sesji Claude Code uruchomić `/reload-skills`, sprawdzić trzy komendy `/ainvoicer-*` i odczyt kanonicznych plików; osobno potwierdzić automatyczny dobór w czterech scenariuszach z dziennika.

## Verification

| Sprawdzenie | Ostatni dowód / ograniczenie |
| --- | --- |
| ABAP-02 live H01 | 2026-10-25: aktywacja 6 DDLS + 2 BDEF + SRVD PASS, ponowny odczyt active 8 źródeł PASS. ATC `ZR_AINV_INV_H` BDEF, wariant `ABAP_CLOUD_DEVELOPMENT_DEFAULT`: 0 ustaleń. Klasa 4 testów aktywna, ale wykonanie EML przerwał dump braku DCL; brak zaliczenia testów. Binding published=true / NOT_RELEASED, rzeczywisty URL odczytany; runtime metadata/expand niezweryfikowane. |
| Inspekcja repozytorium | 2026-10-24: branch `java-cap-ksef`; drzewo początkowo czyste, szkielet CAP śledzony |
| SAP RAP / MCP ABAP FS | 2026-10-25: połączenie `H01` i pakiet `ZAINVOICER_DEV` istnieją; system-info zwrócił typ/wydanie jako Unknown/N/A. Próba tworzenia klasy zakończyła się sukcesem, ale status transportu wykazał request `H01K900018` (task `H01K900019`); metadane requestu potwierdziły klasę wśród 6 obiektów. Próby `TABL/DT`, `DDLS/DF`, `DTEL/DE` zwróciły `Unsupported Media Type`; wyszukiwania kontrolne bez częściowych obiektów. Bez edycji i aktywacji. Wcześniejsza diagnostyka `ZAINVOICER` / DDLS z 2026-10-24 pozostaje odrębnym wynikiem. |
| Model CDS `KsefOperations` | 2026-10-24: utworzono encję `customer.cap_java.KsefOperations` z `cuid`, `managed`, kierunkiem INBOUND/OUTBOUND, statusem domyślnym NEW oraz unikalnością `(taxpayerNip, idempotencyKey)`. PASS: model wczytany przez API kompilatora CDS w procesie Node; CLI `cds --version` nie działa w sandboxie (`spawn EPERM`), Maven build nieuruchomiony. |
| Usługa CDS / OData V4 | 2026-10-24: `KsefService` wymaga `authenticated-user`; status, referencje KSeF i pola błędu są `@readonly`. PASS: build CDS/Maven, metadata (anonimowy 401, authenticated 200), lokalne CREATE/READ/UPDATE/DELETE na H2. Status utworzył się jako NEW mimo próby podania ACCEPTED; pola techniczne wysłane przez klienta były ignorowane. |
| Phase 2.2 — walidacja i autoryzacja | PASS lokalnie 2026-10-24: `@restrict` CREATE/READ dla `KsefOperator` i `$user.taxpayerNips`; 14 testów OData przez Spring Boot + Spring Security + CAP + H2 potwierdziło izolację, także dla braku atrybutu, walidację, brak UPDATE/DELETE i unikalność. Mock users tylko w profilu testowym. IAS/XSUAA nie skonfigurowano. |
| Historyczne `mvn clean verify` | Sukces według użytkownika; wcześniejszy błąd Enforcer przy JDK 21 również z relacji użytkownika |
| Bieżące `mvn clean verify` | PASS 2026-10-24 (2 uruchomienia; drugie po dodaniu testu H2). Enforcer, CDS build/generacja, resources, kompilacja, JAR i Spring Boot repackage PASS. `cap-java.jar` (6,583 B), `cap-java-exec.jar` (57,567,847 B); `target/classes/application.yaml` istnieje. Poprzedni wyjątek nie został odtworzony, więc przyczyna pozostaje nieustalona. |
| Testy Java / H2 | PASS: `H2DataSourceIntegrationTest`, 1 test, 0 błędów; Spring Boot context, JdbcTemplate i `SELECT 1` na H2 in-memory. Nie testuje CRUD encji CDS. |
| Dokumentacja | 2026-10-24: przewodniki abapGit/RAP, dziennik pierwszego eksportu oraz indeks/status/roadmapa zaktualizowane na podstawie inspekcji repo. PASS: wszystkie lokalne linki Markdown w zmienionych plikach rozwiązują się; `git diff --check` bez błędów (Git zgłosił tylko ostrzeżenia o normalizacji LF/CRLF). |
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

2026-10-25 (Europe/Warsaw), po aktywacji kompozycji ABAP-02 w H01 przez MCP; DCL odłożony decyzją użytkownika, testy EML przerwane brakiem DCL, OData runtime niepotwierdzone. Stan jest migawką; przed kolejną sesją porównaj go z kodem i Git.
