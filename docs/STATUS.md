---
title: "AInvoicer — aktualny stan"
date: 2026-10-27
type: status
status: w-trakcie
tags: [ainvoicer, cap-java, status]
---

# Stan projektu

[Dziennik CAP-03 — integracja SAP H01](journal/2026-10-27-cap-03-sap-remote-integration.md)

[Project Home](README.md) · [Roadmapa](ROADMAP.md) · [Workflow abapGit](guides/ABAPGIT_WORKFLOW.md) · [Model RAP faktur](guides/ABAP_RAP_INVOICE_MODEL.md) · [Dziennik próby tworzenia ABAP FS](journal/2026-10-25-abapfs-create-probe.md) · [Dziennik kompozycji RAP](journal/2026-10-24-abap-rap-composition.md) · [Dziennik pierwszego eksportu](journal/2026-10-24-abapgit-rap-initial-export.md) · [Dziennik bootstrapu](journal/2026-10-23-cap-java-bootstrap.md) · [Weryfikacja Phase 1](journal/2026-10-24-cap-java-bootstrap-verification.md) · [Model KsefOperations](journal/2026-10-24-ksef-operations-model.md) · [Usługa i CRUD OData](journal/2026-10-24-ksef-service-definition.md) · [Walidacja i autoryzacja KsefOperations](journal/2026-10-24-ksef-operations-validation-authorization.md)

## Current Phase

Phase 3 — SAP Remote Integration: CAP → SAP H01 READ ma **LIVE PASS** w procesie lokalnym z Destination przez env `destinations`; `SapInvoiceRemoteAdapterLiveTest` odczytał 4 nagłówki, 9 pozycji, 6 referencji i zgodność UUID. Runtime OData V4 H01 (`$metadata`, `$expand`) także ma wcześniejsze potwierdzenie live. To nie potwierdza CAP na Cloud Foundry z Destination Service. CAP → RAP UPDATE pozostaje **NOT IMPLEMENTED**. Szczegóły: [CAP-03](journal/2026-10-27-cap-03-sap-remote-integration.md).

## Architecture

SAPUI5 → OData V4 → CAP Java + Spring Boot → Java SDK KSeF w jednej JVM. SAP przez Remote OData/Destination jest źródłem danych finansowych; CAP przechowuje proces KSeF. H2 lokalnie, SAP HANA Cloud i SAP BTP Cloud Foundry docelowo. Szczegóły: [ADR-0001](decisions/ADR-0001-cap-java-ksef-architecture.md).

## Completed

- 2026-10-27: **CAP → SAP H01 READ: LIVE PASS w lokalnym procesie CAP.** `SapInvoiceRemoteAdapterLiveTest`: 1 test, 0 failures, 0 errors, `BUILD SUCCESS`; 4 nagłówki, 9 pozycji, 6 referencji i zgodność `InvoiceUUID`. Destination była dostarczona lokalnie przez env `destinations`. Cloud Foundry + bound Destination Service pozostaje NOT TESTED, CAP → RAP UPDATE — NOT IMPLEMENTED. Pierwsza próba z hostem `abap-web` zwróciła HTML/IAS redirect; poprawny host OData zadziałał z Basic Auth. Szczegóły: [dziennik CAP-03](journal/2026-10-27-cap-03-sap-remote-integration.md).
- 2026-10-27: CAP-03 krok 2 — lokalny test HTTP kontraktu wykrył i naprawił dodatkowy segment URL, blokadę `@requires: internal-user` oraz filtr `in`; poprawiono `http.service` i przeniesiono `cds.remote.services` poza profil `default`. Historyczne usterki i naprawy: [dziennik CAP-03](journal/2026-10-27-cap-03-sap-remote-integration.md#krok-2--test-live-i-weryfikacja-url--basic-auth).
- 2026-10-27: CAP-03 — aktywny binding `ZAPI_AINV_INV_H_O4`, wersja `0001`, opublikowany (`published=true`), odpowiedział na `$metadata` HTTP 200. Runtime kontrakt zawiera entity sets `ZC_AINV_INV_H`, `ZC_AINV_INV_I`, `ZC_AINV_INV_REF`, klucze UUID i nawigacje `_Items`, `_References`. Autoryzowany podgląd OData V4 zwrócił 4/9/6, bez kolejnych stron; UUID z danych zachowują wartości seeda. W CAP dodano zdalny model CDS, konfigurację Destination `AINV_H01`, adapter CQN, test kontraktu oraz test autoryzacji endpointu zewnętrznego. Pełny `mvn clean verify -Dcds.cds.skipVersionCheck=true`: 22 testy PASS. CAP→BTP Destination→H01 nie było testowane; MCP dostarczył znormalizowany opis `$metadata`, nie surowy EDMX. [Dziennik CAP-03](journal/2026-10-27-cap-03-sap-remote-integration.md).
- 2026-10-26: domknięto aktualizację `AGENTS.md`, Skilla ABAP/RAP i workflow abapGit do zweryfikowanego ABAP FS **2.12.4 / fix 5**. Test akceptacyjny z 2026-10-25: klasa + CDS w obu pakietach Cloud, **4/4 PASS**, bez nowych requestów i aktywacji. Dwa obiekty lokalne bez transportu, dwa w `ZAINVOICER_DEV` z potwierdzonym przypisaniem do wcześniej zatwierdzonego requestu. [Dowody i ograniczenia](journal/2026-10-26-abapfs-v5-instructions.md). Nie jest to test biznesowych DCL/RAP.
- 2026-10-26/27: ABAP-04 seed — raport użytkownika i późniejszy runtime OData potwierdzają 4 nagłówki, 9 pozycji, 6 referencji oraz powiązania po UUID. Sekwencyjny rerun pozostaje nieweryfikowany; brak UNIQUE `InvoiceNumber` i ochrony przed równoległym seedem to ograniczenia.
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

## Aktualny zakres i ograniczenia

- **CAP → SAP H01 READ — LIVE PASS:** lokalny CAP Java + SAP Cloud SDK RemoteService + lokalna Destination przez env `destinations` / Basic Auth. Wynik testu 1/1, 0 failures, 0 errors, `BUILD SUCCESS`; 4/9/6 i zgodne UUID. Testu nie powtarzano w tym audycie.
- **SAP H01 runtime OData V4 — LIVE PASS w udokumentowanym zakresie:** aktywny/publikowany binding, `$metadata` HTTP 200 i autoryzowany `$expand` 4/9/6 przez MCP OAuth. To nie jest test izolacji DCL ani CAP.
- **CAP na Cloud Foundry + bound Destination Service — NOT TESTED.** Lokalna env Destination nie stanowi dowodu dla Destination Service na CF.
- **CAP → RAP UPDATE — NOT IMPLEMENTED.** Zewnętrzny model i adapter CAP są read-only.
- **KSeF Java SDK i E2E KSeF w CAP — NOT IMPLEMENTED.** Weryfikacja źródeł/POM nie wykazała zależności ani adaptera SDK; istniejący eksperyment z osobnej gałęzi nie zalicza integracji CAP.
- Surowy EDMX nie został udostępniony; model CDS opracowano na podstawie znormalizowanego runtime `$metadata`.
- **DCL / EML — raport użytkownika z 2026-10-26, niepowtórzony w tym audycie:** 6 DCL aktywnych, `ZCL_AINV_RAP_COMP_TEST` 4/4 ABAP Unit PASS, ATC trzech nowych DCL: 0 ustaleń. Lokalny `abap/src/` to starszy snapshot i root DCL w nim ma `where TRUE`; nie dowodzi aktywnego stanu H01 ani izolacji danych.
- Skuteczność biznesowej izolacji DCL i autoryzacja zapisu przez `GET_GLOBAL_AUTHORIZATIONS` wymagają osobnej weryfikacji. Aktywność DCL nie jest dowodem poprawnej autoryzacji.
- Historyczne błędy URL, IAS redirect, `@requires`, OData `in`, profilu YAML, `ACM_UNEXPECTED_VALUE` i MCP `Unsupported Media Type` pozostają w dziennikach jako wcześniejsze problemy; nowsze wyniki rozwiązują je tylko w opisanym zakresie.

## Blockers

- ABAP-02: historyczny dump `ACM_UNEXPECTED_VALUE` z 2026-10-25 został późniejszym raportem użytkownika z 2026-10-26 zastąpiony raportem 6 aktywnych DCL i EML 4/4 PASS; nie weryfikowano ponownie live w tym audycie. Root DCL snapshot ma `where TRUE`, a `GET_GLOBAL_AUTHORIZATIONS` jest pusta; izolacja i autoryzacja zapisu pozostają do sprawdzenia.

- SAP RAP / ABAP FS: historyczny `Unsupported Media Type` i myląca informacja „local” z [próby starszej wersji](journal/2026-10-25-abapfs-create-probe.md) nie są aktualnym ogólnym blokerem v5. Test v5 potwierdził tworzenie klasy/CDS i transporty w obu pakietach Cloud. Dalsze zmiany biznesowe nadal wymagają sprawdzenia aktualnego pakietu/requestu i zakresu zadania; nie ponawiać historycznych prób w ciemno. Nie potwierdzono pełnego testu 23 typów na v5 ani ECC.
- Poprzedni `NoSuchFileException: srv/target/classes/application.yaml` nie odtworzono. Zwykłe `mvn clean verify` zatrzymuje się przed testami, bo kontrola `cds version` zgłasza `Required @sap/cds-dk not installed`, mimo że raportuje lokalne `@sap/cds-dk 10.2.0`; `-Dcds.cds.skipVersionCheck=true` pozwala kontynuować i pełny build/test przechodzi.

## Next Steps

1. Dokończyć ABAP-04: wykonać sekwencyjny rerun i potwierdzić pominięcie istniejących numerów bez duplikatów; runtime RAP/OData `$expand` jest potwierdzony 2026-10-27.
2. Dodać zaufaną ścieżkę aplikacyjną do łączenia operacji z fakturą, walidującą zgodność NIP przed zapisem; nie udostępniać powiązania przez publiczne OData.
3. Przed kolejną zmianą biznesową porównać bieżące role DCL w SAP z raportem użytkownika i ocenić politykę izolacji oraz `GET_GLOBAL_AUTHORIZATIONS`; w razie potrzeby powtórzyć `ZCL_AINV_RAP_COMP_TEST`.
4. Po walidacji i osobnym zleceniu wyeksportować zmiany abapGit; bez automatycznego pull przy brudnym checkout.

Dla narzędzi agentowych: w następnej sesji Claude Code uruchomić `/reload-skills`, sprawdzić trzy komendy `/ainvoicer-*` i odczyt kanonicznych plików; osobno potwierdzić automatyczny dobór w czterech scenariuszach z dziennika.

## Verification

| Sprawdzenie | Ostatni dowód / ograniczenie |
| --- | --- |
| CAP → SAP H01 READ | LIVE PASS według dziennika 2026-10-27: `SapInvoiceRemoteAdapterLiveTest`, lokalny CAP process + env `destinations`, Basic Auth; 1/1, 0 failures/errors, 4/9/6 i UUID zgodne. Nie powtarzano w audycie. Cloud Foundry + bound Destination Service: NOT TESTED. |
| ABAP-04 seed danych | 2026-10-26/27: raport użytkownika i późniejszy runtime OData potwierdziły 4 nagłówki, 9 pozycji, 6 referencji, UUID i powiązania. Sekwencyjny rerun niezweryfikowany; brak unikalności InvoiceNumber pozostaje. |
| ABAP-02 / RAP composition | 2026-10-25: aktywacja CDS/BDEF/SRVD i ATC głównego BDEF PASS. Raport użytkownika 2026-10-26: 6 DCL aktywnych, EML 4/4 PASS, ATC 3 nowych DCL bez ustaleń; niepowtórzone w tym audycie. Runtime OData 4/9/6 potwierdzono odrębnie 2026-10-27. Izolacja DCL i autoryzacja zapisu niezweryfikowane. |
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
| KSeF TEST / UPO przez CAP | NOT TESTED w CAP; wcześniejszy eksperyment na osobnej gałęzi nie potwierdza E2E CAP. |

## Last Updated

2026-10-27 (Europe/Warsaw): uzgodniono CAP-03 i RAP z dziennikiem CAP-03 oraz raportem użytkownika z 2026-10-26 o DCL/EML. Audyt nie uruchamiał testów CAP ani SAP. Wyniki DCL/EML pochodzą z relacji użytkownika; CAP LIVE PASS jest udokumentowany w dzienniku z 2026-10-27.
