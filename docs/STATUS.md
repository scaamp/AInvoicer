---
title: "AInvoicer — aktualny stan"
date: 2026-10-29
type: status
status: w-trakcie
tags: [ainvoicer, cap-java, status]
---

# Stan projektu

[Dziennik CAP-04 Hardening](journal/2026-10-29-cap-04-hardening.md) · [Dziennik CAP-04B — kontrakt zapisu RAP](journal/2026-10-28-cap-04b-rap-write-contract.md) · [ADR-0002](decisions/ADR-0002-sap-ksef-result-write-contract.md) · [Dziennik CAP-03 — integracja SAP H01](journal/2026-10-27-cap-03-sap-remote-integration.md)

[Project Home](README.md) · [Roadmapa](ROADMAP.md) · [Workflow abapGit](guides/ABAPGIT_WORKFLOW.md) · [Model RAP faktur](guides/ABAP_RAP_INVOICE_MODEL.md) · [Dziennik próby tworzenia ABAP FS](journal/2026-10-25-abapfs-create-probe.md) · [Dziennik kompozycji RAP](journal/2026-10-24-abap-rap-composition.md) · [Dziennik pierwszego eksportu](journal/2026-10-24-abapgit-rap-initial-export.md) · [Dziennik bootstrapu](journal/2026-10-23-cap-java-bootstrap.md) · [Weryfikacja Phase 1](journal/2026-10-24-cap-java-bootstrap-verification.md) · [Model KsefOperations](journal/2026-10-24-ksef-operations-model.md) · [Usługa i CRUD OData](journal/2026-10-24-ksef-service-definition.md) · [Walidacja i autoryzacja KsefOperations](journal/2026-10-24-ksef-operations-validation-authorization.md)

## Current Phase

Phase 3 — SAP Remote Integration: **CAP-04B opublikowany i CAP-04C zaimplementowany.** SAP `ApplyKsefResult` aktywna; eksport `525e48d`. CAP ma Write Adapter, niezmienne polecenie, persistent outbox/worker i historię. Pełny build 46/46 PASS; uzupełniający przebieg ochrony modelu 2/2 oraz LIVE READ H01 1/1 PASS (4/9/6). Test restartu plikowej H2 i rzeczywistego collectora PASS z mockiem SAP. **HTTP WRITE nadal nie wykonano**, transport domyślnie wyłączony, prawo SAP bez nadania. [CAP-04C](journal/2026-10-28-cap-04c-sap-result-writeback.md), [ADR-0003](decisions/ADR-0003-cap-sap-result-writeback.md).

CAP → SAP H01 READ ma **LIVE PASS**, ponownie potwierdzony 2026-10-28 po imporcie EDMX: lokalny CAP, Destination przez env `destinations`, 4/9/6 i zgodne UUID. CAP na Cloud Foundry z Destination Service pozostaje nieprzetestowany. Nie testowano dwóch równoległych zapisów akcji SAP, commit/ETag ani praw użytkownika komunikacyjnego do zapisu. CAP-04D wymaga osobnej zgody.

**CAP-04 Hardening (2026-10-29), commit `dfd1ecb`:** claim/finalize bez blokady podczas HTTP, `RETRY_EXHAUSTED`, kontrolowane resume, migawka SHA-256. Historyczny build **71/71 PASS** (H2/mock). Pierwsza sesja miała `ECONNREFUSED`; informacje o stanie SAP były wtedy NOT VERIFIED. Poprawiono historyczne „bez commit” po sprawdzeniu zawartości i metadanych `dfd1ecb`; nie weryfikowano remote.

**FA(3) — Agent B (2026-10-29, `feature/ksef-fa3`):** komponent `customer.cap_java.ksef.fa3` generuje deterministyczny XML podstawowej faktury VAT, waliduje go oficjalnym XSD FA(3) 1-0E (offline) i regułami domenowymi (sumy, NIP) oraz zwraca bajty + SHA-256 dla `KsefInvoiceSnapshotRegistry`. Testy FA(3) 30/30, pełny clean verify **112/112 PASS** (H2/mock). KSeF TEST NOT CALLED. NIP-y seeda H01 mają błędną sumę kontrolną — do FA(3) użyto osobnego fixture. [Przewodnik](guides/FA3_XML_GENERATION.md), [dziennik](journal/2026-10-29-agent-b-fa3.md).

**CAP-04 Hardening Fix 2 (kontynuacja 2026-10-29):** rewizja CAS dla resume/claim, precyzyjny test odmowy CRUD (zdalny HTTP403 + uzgodniony kod SAP), timeouty klienta Cloud SDK i odnawianie lease podczas HTTP. Szczegółowe wyniki i ograniczenia w [kontynuacji dziennika](journal/2026-10-29-cap-04-hardening.md#kontynuacja--hardening-fix-2-i-cap-04d-readiness). Transport WRITE nadal OFF; CAP-04D **IMPLEMENTED / NOT LIVE TESTED**. MCP częściowo działa: sześć CDS i root BDEF active zgodne z Git; dostęp do include/DCL/runtime odrzucony przez automatyczną kontrolę uprawnień. IAM, aktywacja uprawnienia, CBA poza zakresem i runtime WRITE **NOT VERIFIED**. CAP-03 LIVE READ w tej sesji **NOT TESTED** (brak env Destination); lokalna regresja READ objęta buildem. [Procedura CAP-04D](guides/SAP_WRITE_ACCESS_AND_READ_POLICY.md#5-cap-04d--gotowość-po-fix-2).

## Architecture

**2026-10-29, Agent A (`feature/ksef-auth`):** oficjalny SDK MF 3.0.29 w CAP,
auth token/XAdES, sesje FA(3), worker/outbox, checkpointy i rekoncyliacja.
**LIVE auth TEST PASS:** challenge, XAdES, biznesowe 200, redeem, autoryzowany READ
i refresh. **LIVE E2E PASS** na oficjalnym syntetycznym fixture: jedna faktura
`1234563218-20261029-7DC8A1C00000-CD`, COMPLETED, UPO 5528 bajtów i RESUME po restarcie.
Pełny clean verify A+B **141/141 PASS**, 0 błędów (18:03 Europe/Warsaw), H2/mock.
Commit B `7075c01` zintegrowano jako `657c285`; producent łączy generator/snapshot/outbox,
a granica wysyłki przywraca 12 oryginalnych dużych limitów XSD. Bez LIVE H01
READ/WRITE i bez drugiej wysyłki z B. [Dowody](journal/2026-10-29-agent-a-ksef-sdk.md),
[runbook](guides/KSEF_TEST_RUNBOOK.md).

SAPUI5 → OData V4 → CAP Java + Spring Boot → Java SDK KSeF w jednej JVM. SAP przez Remote OData/Destination jest źródłem danych finansowych; CAP przechowuje proces KSeF. H2 lokalnie, SAP HANA Cloud i SAP BTP Cloud Foundry docelowo. Szczegóły: [ADR-0001](decisions/ADR-0001-cap-java-ksef-architecture.md).

## Completed

- 2026-10-29: **Hardening Fix 2 — FIXED + TESTED lokalnie:** pełny `mvn clean verify -Dcds.cds.skipVersionCheck=true`: **82 PASS, 0 FAIL, 0 errors**, BUILD SUCCESS (46,603 s). W tym 20 hardening, 15 HTTP adapter/CRUD i 2 konfiguracji HTTP; reszta obejmuje wcześniejsze READ, claim/finalize, snapshot/hash i restart/outbox. Live tagi domyślnie wykluczone. READ LIVE w tej sesji NOT TESTED; szkic testu SAP CBA NOT VERIFIED. Istniejąca trwała baza potrzebuje migracji kolumny `revision` przed użyciem nowego kodu.

- 2026-10-29: **CAP-04 Hardening** — zob. akapit w Current Phase i [dziennik](journal/2026-10-29-cap-04-hardening.md). Zmiana kontraktu wewnętrznego: `enqueue(operationId)` wymaga migawki dokumentu.

- 2026-10-28: **CAP-04C** — import rzeczywistego EDMX, CAP Remote Action, atomowe zlecenie i native persistent outbox, historia, stabilny request ID i wersja 100 ns, autoryzowane wewnętrzne enqueue, retry i rekoncyliacja. Nowe environment operacji readonly TEST. Testy HTTP lokalne, transakcji, równoległego enqueue, restartu, bezpieczeństwa i regresji READ potwierdzone w [dzienniku](journal/2026-10-28-cap-04c-sap-result-writeback.md). SAP WRITE/KSeF/CF poza testami.
- 2026-10-28: publikacja CAP-04B — dokumentacja `8de662f`, eksport SAP `525e48d` (31 plików), potwierdzone remote refs/status i fast-forward lokalnego branchu. Bez niepowiązanych usunięć i scenariusza komunikacyjnego; [kontynuacja](journal/2026-10-28-cap-04b-rap-write-contract.md).
- 2026-10-28: **CAP-04B — live SAP RAP contract.** Cztery pola nagłówka, akcja statyczna z wewnętrzną blokowaną akcją instancyjną, CAS po blokadzie, jawne konflikty/no-op, global + instance authorization i prechecks CRUD. Testy z kontrolowaną polityką praw i rollback, bez nadania principalom. Rzeczywisty read-only EDMX potwierdzony; istniejące 4 nagłówki bez wyników KSeF i bez zmiany ETag. Źródła wyłącznie live SAP, jeszcze bez eksportu abapGit. [Dziennik](journal/2026-10-28-cap-04b-rap-write-contract.md), [ADR-0002](decisions/ADR-0002-sap-ksef-result-write-contract.md).
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
- **CAP → RAP action — IMPLEMENTED, transport OFF / LIVE WRITE NOT TESTED.** Write Adapter i worker CAP-04C wywołują wyłącznie `ApplyKsefResult`. Standardowy PATCH nie jest używany. Rzeczywisty HTTP WRITE pozostaje w CAP-04D.
- **KSeF Java SDK i E2E KSeF w CAP — LIVE TEST PASS na `feature/ksef-auth` (2026-10-29).** SDK 3.0.29, auth/JWT/refresh, jedna syntetyczna faktura z oficjalnego fixture, numer i UPO. Źródło SAP zastąpiono lokalną syntetyczną migawką; nie potwierdza pełnego H01 → KSeF ani generatora B.
- **FA(3) XML — IMPLEMENTED + TESTED lokalnie (A+B):** generator, walidacja XSD/domenowa, snapshot/outbox; VAT, PLN, 23/8/5%. Walidator B rozluźnia duże maxOccurs, ale bramka wysyłki A przywraca wszystkie 12 oryginalnych limitów. Niezależna walidacja pełnym XSD/lxml PASS. Przyjęcie XML z generatora B przez KSeF niezweryfikowane.
- Model CAP zaimportowano z rzeczywistego EDMX H01 2026-10-28 z oryginalnym namespace i ochroną `internal-user`. Regresja CAP LIVE READ po imporcie PASS.
- **DCL / EML — raport historyczny 2026-10-26:** 6 DCL aktywnych, `ZCL_AINV_RAP_COMP_TEST` 4/4 ABAP Unit PASS, ATC trzech nowych DCL: 0 ustaleń. Tego historycznego zestawu nie powtarzano. Nowsze testy autoryzacji zapisu opisuje CAP-04B; jego źródła są wyeksportowane w `525e48d`. Root DCL `where TRUE` nadal nie zapewnia izolacji odczytu.
- CAP-04B wdrożył global + instance authorization i prechecks zapisu; testy EML odmów PASS z kontrolowaną polityką. Nowe prawo `Z_AINV_KSF` bez nadania; dotychczasowi konsumenci zapisujący potrzebują 01/02/06 i zakresu, akcja wyłącznie 16. DCL root `where TRUE` nie zapewnia izolacji odczytu per NIP; nie zmieniano go.
- Pełne dwa równoległe zapisy akcji, persisted no-op/ETag oraz uprawnienia rzeczywistego communication user pozostają niezweryfikowane. Nie wykonano HTTP WRITE; te testy wymagają osobnego zlecenia CAP-04D.
- SDK, producent ACCEPTED i generator B są zintegrowane na `feature/ksef-auth`; pełne źródło LIVE SAP nadal oczekuje. Publiczne CREATE wymusza NEW. Domyślna H2 jest in-memory; E2E używa odrębnej plikowej H2. Przed CF wymagany zarządzany storage i trwała baza. Kontrolowane retry/reconciliation są API Java, bez publicznego endpointu. HANA i wiele instancji CF pozostają niezweryfikowane.
- Historyczne błędy URL, IAS redirect, `@requires`, OData `in`, profilu YAML, `ACM_UNEXPECTED_VALUE` i MCP `Unsupported Media Type` pozostają w dziennikach jako wcześniejsze problemy; nowsze wyniki rozwiązują je tylko w opisanym zakresie.

## Blockers

- Pełny LIVE H01 → generator B → KSeF: NIP-y seeda mają błędne sumy kontrolne, RAP nie dostarcza pełnego PartyData (adresy/JST/GV), a w procesie tej sesji brak Destination. Własny E2E CAP/SDK ma LIVE PASS na oficjalnym fixture. Nie zmieniano danych H01 ani nie wysyłano drugiej faktury.

- Przed uruchomieniem zapisu komunikacyjnego: jawny zakres NIP/CompanyCode, nadanie ACTVT16 przez właściwy communication scenario/arrangement i realne testy principalem. Użytkownik zdecydował o pozostawieniu prawa bez nadania w CAP-04B. Bez niego standardowy zapis i akcja są odmawiane. Izolacja odczytu DCL pozostaje osobnym zagadnieniem.

- SAP RAP / ABAP FS: historyczny `Unsupported Media Type` i myląca informacja „local” z [próby starszej wersji](journal/2026-10-25-abapfs-create-probe.md) nie są aktualnym ogólnym blokerem v5. Test v5 potwierdził tworzenie klasy/CDS i transporty w obu pakietach Cloud. Dalsze zmiany biznesowe nadal wymagają sprawdzenia aktualnego pakietu/requestu i zakresu zadania; nie ponawiać historycznych prób w ciemno. Nie potwierdzono pełnego testu 23 typów na v5 ani ECC.
- Poprzedni `NoSuchFileException: srv/target/classes/application.yaml` nie odtworzono. Zwykłe `mvn clean verify` zatrzymuje się przed testami, bo kontrola `cds version` zgłasza `Required @sap/cds-dk not installed`, mimo że raportuje lokalne `@sap/cds-dk 10.2.0`; `-Dcds.cds.skipVersionCheck=true` pozwala kontynuować i pełny build/test przechodzi.

## Next Steps

1. CAP-04D po osobnej zgodzie: ograniczone nadanie, HTTP WRITE na uzgodnionych danych, commit/ETag/no-op, autoryzacja rzeczywistym principalem i pełne dwa równoległe wywołania akcji.
2. Uzgodnić poprawne syntetyczne NIP-y i źródło PartyData, wykonać READ H01 oraz lokalne przygotowanie przez zintegrowany generator B. Następny LIVE send to osobny eksperyment; istniejący numer/UPO zachować.
3. Przed wdrożeniem: trwała baza, obsługa operacyjna retry/konfliktów/Retry-After i testy HANA/CF. Domyślna baza development jest in-memory.
4. Decyzje po hardeningu: wariant polityki odczytu DCL, nadanie principalowi zapisu (NIP/CompanyCode), chroniony interfejs `resume`, `total etag` w BDEF. Osobne zaległości: izolacja odczytu DCL, sekwencyjny rerun seeda i kompleksowa ścieżka wiązania operacji z fakturą w CAP. CAP-04C sprawdza zgodność istniejącego powiązania przy enqueue.

Dla narzędzi agentowych: w następnej sesji Claude Code uruchomić `/reload-skills`, sprawdzić trzy komendy `/ainvoicer-*` i odczyt kanonicznych plików; osobno potwierdzić automatyczny dobór w czterech scenariuszach z dziennika.

## Verification

| Sprawdzenie | Ostatni dowód / ograniczenie |
| --- | --- |
| FA(3) generator/XSD | 2026-10-29: `Fa3*Test` + `SapInvoiceFa3MapperTest` 30/30, pełny clean verify 112/112 PASS. Ręcznie: przykład FA(3) z SDK MF przechodzi walidator, FA(2) odrzucony. Golden SHA-256 próbki `24fc8a1e…339afd` (2463 B). KSeF TEST: NOT CALLED. |
| CAP-04 Fix 2 | 2026-10-29: 82/82 PASS H2/mock, pełny clean verify. Równoległe resume: jeden sukces, RESUME_CONFLICT, jedna publikacja i historia, payload bez zmian; rollback publikacji, scheduled lease renewal i rzeczywisty HTTP read timeout PASS. HTTP WRITE/KSeF nie wykonywano. H01: CDS/BDEF częściowo porównane; include/DCL/runtime/IAM/CBA NOT VERIFIED. |
| CAP-04C | 2026-10-28: pełny `mvn clean verify -Dcds.cds.skipVersionCheck=true` 46/46 PASS, dodatkowy test ochrony modelu w przebiegu 2/2 PASS oraz LIVE READ 1/1 PASS. Lokalny HTTP, H2/outbox, rollback, równoległe enqueue i restart z mockiem SAP. HTTP WRITE OFF; [granice dowodu](journal/2026-10-28-cap-04c-sap-result-writeback.md). |
| CAP-04B SAP contract | 2026-10-28: aktywacja/syntax PASS, ATC `ABAP_CLOUD_DEVELOPMENT_DEFAULT` 10 obiektów finalnie 0 ustaleń, EML 18/18, prototyp 7/7, rzeczywisty EDMX HTTP200. Polityka testowa, rollback; root enqueue między sesjami PASS. Bez HTTP WRITE/commit, persisted no-op/ETag i pełnego testu równoległych zapisów. Prawo bez nadania; [szczegóły](journal/2026-10-28-cap-04b-rap-write-contract.md). |
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
| KSeF TEST / UPO przez CAP | LIVE PASS 2026-10-29, Agent A: auth/XAdES/redeem/refresh, jedna syntetyczna faktura `1234563218-20261029-7DC8A1C00000-CD`, numer/hash, close, UPO 5528 bajtów i RESUME po restarcie. Oficjalny fixture + lokalna migawka, bez H01 i generatora B. |

## Last Updated

2026-10-29 (Europe/Warsaw): A+B na `feature/ksef-auth`, 141/141 PASS; LIVE auth i jedna syntetyczna wysyłka TEST z numerem/UPO przez CAP, generator B połączony ze snapshotem/outboxem i niezależnie zwalidowany. Pełny H01 → KSeF nadal oczekuje z powodu danych NIP/PartyData. H01 WRITE i deployment poza zakresem. Historyczne wyniki zachowano powyżej.
