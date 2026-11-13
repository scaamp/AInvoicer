---
title: "AInvoicer — plan rozwoju"
date: 2026-11-13
type: roadmap
status: aktywny
tags: [ainvoicer, roadmap, cap-java, ksef]
---

# Plan rozwoju

- [x] **CAP-HANA-02G1 / PR #17 — CI recovery:** katalog CORE14 + LAB offline 48/48;
  recovery Chrome/CDP, runner 8/8; pełny CI kodu `6f67b4a` SUCCESS.
  [Dziennik](journal/2026-11-13-cap-hana-02g1-ci-recovery.md). G1 i LIVE nadal niewykonane.
- [ ] **PR #17 — niezależny review:** końcowe checks w PR, następnie osobna decyzja o merge;
  bez automatycznego merge i bez zgody wykonawczej G1.

- [x] **ORCH-05 (2026-11-12) — integracja R2 + CAP-07E:** niezależny review, poprawka klasyfikatora HTML200 `715ef43` z regresją i zielony CI dokładnych HEAD; scalono wyłącznie PR#15 (`f55d0e8`) i PR#16 (`d4dc204`). [Review i decyzje](journal/2026-11-12-orch-05-hana-security-integration.md).
- [x] **CAP-HANA-02R2 — runner offline:** JAR/JUnit/guardy/integralność, 5/5 testów procesowych; CI `4dd9ce1` SUCCESS. [Przewodnik](guides/CAP_HANA_02_R2_RUNNER.md) · [dziennik](journal/2026-11-12-cap-hana-02r2-isolated-runner.md).
- [ ] **HANA — kolejne bramki:** decyzja A o dokładnym katalogu core14 + owner-led LAB (CDS już w main od `30b6234`); osobne zlecenie zmiany G1, potem zgoda na odczyt. G2 runner staging, G3 check i G4 DML nadal niezatwierdzone/niewykonane. Nie zmieniać liczby tabel wyłącznie dla PASS.
- [x] **CAP-07E — audyt i testy offline:** 6 testów JWT HANA readiness, historyczne anonimowe HTTP38/38 PASS, poprawka narzędzia ORCH-05 z regresją. [Macierz](guides/CAP_07E_SECURITY_ACCEPTANCE.md) · [dziennik](journal/2026-11-12-cap-07e-security-acceptance.md).
- [ ] **Security acceptance:** drugie konto i znane fixtures A/B, Destination, autorytatywny principal H01 i DCL dzieci, logout/expiry, decyzje E-2/E-3 i ponowienie 2 nawigacji po poprawce. Sign-off PENDING; bez aktywacji KSeF.
- [x] **BLOB-LAB-01 — etap właściciela:** model CDS zapisany w Git (`30b6234`); fizyczny BLOB/task#3/COUNT0 potwierdzone relacją i screenshotami właściciela. [Dowody](journal/2026-11-12-blob-lab-01-owner-live-hdi-blob.md).
- [ ] **BLOB-LAB-01 — dalsza nauka owner-led:** zabezpieczony serwis mediów, upload/download, integralność i trwałość plików nadal niewykonane; agenci nie przejmują tych kroków. [Zasady](guides/BLOB_LAB_01_OWNER_LED_HDI.md).

Poniższe wpisy zachowują historię wcześniejszych etapów; aktualne bramki są powyżej i w [STATUS](STATUS.md).

- [x] **INT-03 — MERGED e2696ab po final review/CI PASS:** CAP-07D + storage + HANA as-built, wspólny tenant gate,
  wariant HANA existing-service, storage body deadline, harness opt-in i CI emulatora.
  [Wyniki/PR/CI](journal/2026-11-11-int-03-orchestration.md) · [Runbook HANA](guides/HANA_CLOUD_CAP_DEPLOYMENT.md).
- [x] **CAP-HANA-01 — przygotowanie offline:** branch z aktualnego main, poprawiony harness, świeży CF
  preflight, buildy MTAR i [runbook](guides/CAP_HANA_01_FIRST_DEPLOYMENT_RUNBOOK.md) z testami/rollbackiem.
  [Dowody i ograniczenia](journal/2026-11-11-cap-hana-01-first-persistence.md).
- [x] **HDI provisioning:** właściciel utworzył `ainvoicer-db`; **VERIFIED LIVE** CF `hana/hdi-shared`,
  create succeeded, 0 bindingów, free=true, database_id z brokera. Cloud Central RUNNING/zgodny ID
  **OWNER-REPORTED**. [Pełna historia i ograniczenia PAYG](journal/2026-11-11-hdi-shared-provisioning.md).
- [x] **CAP-HANA-01 — zgoda i wykonanie:** abort30401273… potwierdzony ABORTED; deployment0.7.3
  zatwierdzonego SHA6a92fb78… uruchomiony przez agenta. Deskryptor przyjęty, utworzony deployer/binding HDI.
- [ ] **CAP-HANA-01 LIVE — staging ERROR / HANA NOT DEPLOYED:** operacja d110c171… zakończona ERROR:
  package.json permission denied, Windows ZIP creator10/Go mode000. Naprawiona normalizacja Unix3/0644/0755,
  lokalnie31 PASS/1 Go skipped, Go1.26.10 dodany wyłącznie jako niezależny czytnik ZIP w CI.
  Nowe MTAR z czystego4630f89/verify/Python metadata PASS, hashe w runbooku.
  CI1ea445a SUCCESS (także niezależny Go reader); kolejna zgoda PENDING.
  HDI1 binding do STOPPED deployera, CAP nadal bez HDI.
  Historia poprzedniej naprawy:
  Naprawione mapowanie typów projektu do typów CF,29/29 testów wraz z SAP MBT mtad-gen PASS.
  Odbudowa obu MTAR/nowe SHA/verify PASS z czystego e2df55a; Maven423 PASS/6 skipped,
  gotowy JAR CF10/10, UI5 CF13/13 i AppRouter10/10 PASS. Zakończenie operacji ERROR
  oraz nowa zgoda na konkretny zestaw/SHA pozostają PENDING.
  Poniższe wyniki opisują wcześniejszy preflight:
  MTAR/SHA i CI zweryfikowane offline; propozycja osobnego extension przypina wszystkie moduły do fs4.
  CAP HANA NOT CONNECTED. Wymagana zgoda na konkretny deploy/DDL/bindingi/przerwę;
  runner, DML/restart, staging, LIVE A/B/DCL/principal i Costs and Usage nadal PENDING.
- [x] **CAP-HANA-01R — preflight i weryfikacja artefaktów (PR #14, MERGED be97a20):** niezależny odczyt CF/HANA,
  ponownie zmierzone SHA 0.7.3/rollback/extension, `verifyArchive` PASS, bez odbudowy MTAR.
  [Bramka](guides/CAP_HANA_01_FIRST_DEPLOYMENT_RUNBOOK.md#cap-hana-01r--aktualna-bramka-recovery-1111-2035-cet) ·
  [Integracja ORCH-04](journal/2026-11-11-orch-04-integration.md).
- [x] **CAP-HANA-01R/01S LIVE — CAP na HANA wdrożony (2026-11-11):** po zgodzie abort `d110c171…`; deployment
  `196b9b8c…` (`40b8e964…`) — HDI make SUCCEEDED, staging CAP BuildpackCompileFailed (opakowany JAR), CAP STOPPED.
  Poprawka generatora `fe0a0c1` + regresja, nowe 0.7.3 `54a89984…`; abort `40b8e964…` i deployment `82250a52…`
  **FINISHED**: CAP/AppRouter STARTED, CAP→HDI przez pulę JDBC HANA, anonimowe GET CAP 401.
  [Wynik i akceptacja](guides/CAP_HANA_01_FIRST_DEPLOYMENT_RUNBOOK.md#cap-hana-01s--wynik-live-deployment-82250a52-finished-1111-2140-cet).
- [ ] **CAP-HANA-02 — akceptacja persystencji (osobne zgody):** katalogowy SQL obiektów HDI, syntetyczne DML/SELECT,
  restart i trwałość, CAS/lock/konkurencja, LIVE A/B/DCL/principal H01, security sign-off; analiza WARN handlerów outbox.
  - [x] Faza 1 read-only (2026-11-12): CF/HANA/HDI/CI odczytane, WARN outbox wyjaśnione offline, harness oceniony
    (zgoda read-only + `verify-cleanup`, regresja PASS), task katalogowy read-only przygotowany. [Plan G0–G9](guides/CAP_HANA_02_PERSISTENCE_ACCEPTANCE.md).
  - [ ] G0/G1 — odczyt w sesji właściciela i task katalogowy (GO do zgody).
  - [ ] Runner w sieci CF (wariant R2, osobny etap offline + zgoda) — warunek pierwszego DML (obecnie NO-GO).
  - [ ] G3–G9: check → seed → recover → (restart) → contend → cleanup → verify-cleanup, każda faza osobno.
- [ ] **KSEF-STOR-02 — decyzja właściciela:** [ADR-0008](decisions/ADR-0008-document-storage-dev-test-proposal.md)
  proponuje limitowany BLOB DEV/TEST po CAP-HANA-01; brak implementacji. Object Store nadal OFF bez zgody kosztowej.

- [x] **KSEF-IN-01 — rdzeń offline:** metadata/XML przez SDK 3.0.29, bezpieczny parser FA(3), deduplikacja, planer HWM i kontrakt checkpointów; 77 nowych testów, brak LIVE/DB/schedulera. [Dziennik](journal/2026-11-09-ksef-in-01-inbound.md).
- [x] **INT-01 — LOCAL + CI PASS dla kodu `18ca64e`:** CAP-07C + INBOUND bez konfliktów i utraty historii, 321 Maven oraz pełna regresja UI5/CF smoke; [PR #3](https://github.com/scaamp/AInvoicer/pull/3) i [Ubuntu CI](https://github.com/scaamp/AInvoicer/actions/runs/42685746135), bez merge/deploy/LIVE. Aktualne checks końcowego HEAD w PR. [Dziennik](journal/2026-11-09-int-01-branch-integration.md) · [Review/plan merge](audits/2026-11-09-int-01-integration-review.md).
- [x] **KSEF-IN-02 — lokalny staging/checkpoint persistence:** dokumenty/granty/checkpointy, storage interface, lease/CAS i crash recovery; 351 Maven w sprincie źródłowym, synchronizacja OFF bez schedulera. [Dziennik](journal/2026-11-10-ksef-in-02.md), PR #5 pozostaje otwarty.
- [x] **UI5-10 — lokalny ekran Inbox:** lista/filtry/sort/paging/szczegóły i adapter CAP; [dziennik](journal/2026-11-10-ui5-10-inbound-inbox.md).
- [x] **INT-02 — LOCAL + CI PASS, MERGED do main:** bezpieczny read-only OData + UI5 przez prawdziwe HTTP, historie obu sprintów; Maven368, JWT29, UI5→CAP16/73, QUnit112, OPA21, MTAR PASS. [PR #5](https://github.com/scaamp/AInvoicer/pull/5) scalony jako3e7420d, następnie [PR #6](https://github.com/scaamp/AInvoicer/pull/6) przestawiony na main i scalony jako21c2e48. [CI efc6a91 PASS](https://github.com/scaamp/AInvoicer/actions/runs/42897303774), główny worktree na main zsynchronizowany. [Dziennik](journal/2026-11-10-int-02-inbound-fullstack.md) · [Kontrakt](guides/INT_02_INBOUND_FULLSTACK.md).
- [x] **KSEF-IN-03A — globalny budżet API, komponent offline (PR #13, MERGED 528a973):** okna przesuwne,
  limit in-flight, Retry-After, fail-closed; 39 testów PASS. Bez Spring wiring, HANA, schedulera i LIVE.
  [Przewodnik](guides/KSEF_IN_03A_GLOBAL_API_BUDGET.md) · [Dziennik](journal/2026-11-11-ksef-in-03a-global-api-budget.md) ·
  [Review ORCH-04](journal/2026-11-11-orch-04-integration.md#code-review--pr-13-ksef-in-03a).
- [ ] **INBOUND następny etap:** adapter HANA `ApiBudgetRepository` (czas DB, CAS/lock, recovery/health
  niepewnych permitów) i testy dwóch JVM po CAP-HANA-01; podłączenie budżetu per próba w `ReadRetryPolicy`;
  trwały provider/HANA, SSO A/B oraz autorytatywne mapowanie do CompanyCode; aktywacja CF i LIVE KSeF osobno zatwierdzana.
- [ ] **BLOB-LAB-01 — owner-led:** prowadzi właściciel; agenci nie implementują ani nie wdrażają go autonomicznie.
  [Zasady](guides/BLOB_LAB_01_OWNER_LED_HDI.md).

- [x] **CAP-07C — LOCAL + CI PASS:** generator bezpiecznego MTAR smoke, guardy/testy executable JAR-a CF, fail-closed usług zewnętrznych UI5, CI offline i mocniejszy consent CAP-04D. [PR #2](https://github.com/scaamp/AInvoicer/pull/2), [Ubuntu CI PASS](https://github.com/scaamp/AInvoicer/actions/runs/42671257855) dla kodu `ca3739a`; bez merge/deploy/SAP WRITE/KSeF SEND. Dokładne wyniki regresji/publikacji: [dziennik](journal/2026-11-09-cap-07c-autonomous-hardening.md).
- [x] **CAP-07B.2 — funkcjonalne LIVE SSO (2026-11-10):** XSUAA, rola/Role Collection, `AINV_H01_UI_USER` (`OAuth2UserTokenExchange`), CAP GET i RAP READ 4 faktury z CF, także w odrębnej sesji — **FUNCTIONAL PASS**. [As-built / runbook / dowody](guides/CAP_07B_2_BTP_IDENTITY_SSO_LIVE.md).
- [ ] **CAP-07B.2 — SECURITY ACCEPTANCE:** autorytatywny principal H01 dla konkretnego RAP GET, użytkownik B bez admin/developer, testy 401/403/DCL i izolacji A/B; bez nowego deployu i bez HANA free/HDI nadal blokującej trwały workflow.
- [ ] **CAP-04D — SAP Write Contract LIVE:** dedykowana nowa faktura `AINV-CAP04D-*`, dokładna zgoda/zakres i potwierdzony principal/ACTVT16/CSRF/ETag. Istniejące4 seed chronione; brak LIVE WRITE w CAP-07C.

[Project Home](README.md) · [Aktualny stan i dowody](STATUS.md) · [Decyzja architektoniczna](decisions/ADR-0001-cap-java-ksef-architecture.md)

`[x]` oznacza ukończenie z podanym zakresem potwierdzenia, `[ ]` — pracę do wykonania. IN PROGRESS oznacza zadanie rozpoczęte. Ten plik jest planem; historię opisują [dziennik bootstrapu](journal/2026-10-23-cap-java-bootstrap.md), [weryfikacja Phase 1](journal/2026-10-24-cap-java-bootstrap-verification.md) i [pierwszy eksport abapGit/RAP](journal/2026-10-24-abapgit-rap-initial-export.md).

## Narzędzia projektowe — Agent Skills

- [x] 2026-10-29: scalono ABAP-05 do java-cap-ksef i promowano tę linię do głównej gałęzi `main`; oryginał zachowano na `archive/original-ainvoicer-2026-10-29`. Nowe feature z `main`; konfiguracja SAP abapGit wymaga niezależnego sprawdzenia. [Dziennik migracji gałęzi](journal/2026-10-29-main-promotion.md).

- [x] Trzy kanoniczne Skills ABAP/RAP, CAP Java i KSeF w `.agents/skills/`, odwołania Claude w `.claude/skills/` oraz sekcje `Project Skills` w instrukcjach agentów.
- [x] Walidacja statyczna i ręczna ocena czterech scenariuszy; Codex `skills/list` wykrywa trzy aktywne Skills w zakresie repo bez błędów ładowania.
- [ ] Potwierdzenie automatycznego doboru przez model oraz wykrywania/odczytu kanonicznych instrukcji w sesji Claude Code. Wersje CLI i poprawne pliki nie zastępują tego testu.

Dowody i sposób wywołania: [dziennik Skills](journal/2026-10-23-project-skills.md). Utworzenie Skills nie kończy żadnego etapu implementacji CAP/SAP/KSeF.

## Phase 1 — CAP Java Bootstrap — COMPLETE

- [x] Wygenerowanie projektu CAP Java w `cap-java/` (pliki zweryfikowane; polecenie inicjalizacji z relacji użytkownika).
- [x] Powtarzalny `mvn clean verify`: dwa uruchomienia na JDK 25.0.4.1 i Maven 3.9.16; CDS, kompilacja, zasoby i oba JAR-y zbudowane.
- [x] Poprzedni `NoSuchFileException` sprawdzony: nie odtworzono; `application.yaml` skopiowano do `target/classes` przed pakowaniem. Przyczyna dawnego incydentu pozostaje nieustalona.
- [x] Uruchomienie Spring Boot/CAP Runtime z executable JAR; HTTP Index odpowiada pod `http://localhost:8080/`.
- [x] Test integracyjny H2 DataSource: `SELECT 1` PASS.

Własne API CDS/OData i CRUD nie wchodziły w Phase 1; ich sprawdzenie należy do Phase 2.

## Phase 2 — CAP Domain Model

- [x] Minimalna encja CDS `KsefOperations`: korelacja SAP jako tekst, NIP, kierunek, typ, status NEW, klucz idempotencji, referencje i błąd.
- [x] Encja CDS `KsefInvoices`: kierunek, korelacja SAP, strony, numer KSeF, status biznesowy `UNCONFIRMED` i referencje do magazynu; status operacji pozostaje techniczny.
- [x] Opcjonalna asocjacja `KsefOperations` → `KsefInvoices`; publiczne OData wyklucza nawigację i nie pozwala klientowi ustawiać FK. Referencje XML/UPO są ukryte, encja faktury jest tylko do odczytu i filtrowana per NIP.
- [ ] Zaufana ścieżka backendowa do wiązania operacji z fakturą musi sprawdzać zgodność NIP. H2 nie generuje constraint FK dla asocjacji; obecny test potwierdza poprawne powiązanie, a nie automatyczne odrzucanie błędnego FK.
- [x] `KsefService` wystawia `KsefOperations` przez OData V4, wymaga uwierzytelnionego użytkownika i blokuje zapis statusów/referencji technicznych po stronie konsumenta.
- [x] Minimalny Java `@Before` handler CREATE: NIP z sumą kontrolną, direction, katalog operationType, wymagany idempotencyKey (trim) i status `NEW`.
- [x] Lokalna weryfikacja `$metadata`, OData i persystencji H2; walidacja domenowa oraz autoryzacja per NIP są potwierdzone testami integracyjnymi.
- [x] Trwała encja operacji i unikalny idempotency key `(taxpayerNip, idempotencyKey)`.
- [x] Kontrola konkurencji i worker/outbox po commit — CAP-05 H2/mock; HANA/CF osobno.
- [x] `RECONCILIATION_REQUIRED`, recovery po restarcie i testy niejednoznacznych odpowiedzi — CAP-05 H2/mock.
- [x] Kontrola dostępu do operacji: rola `KsefOperator`, lista atrybutów `$user.taxpayerNips`, izolacja CREATE/READ i blokada UPDATE/DELETE w publicznym serwisie.
- [x] Testy integracyjne OData + Spring Security + CAP + H2: użytkownicy operator A/B, anonimowy/brak roli, walidacja, status, brak UPDATE/DELETE i unikalność idempotencji.
- [ ] Docelowa konfiguracja IAS/XSUAA i mapowanie zaufanych claimów do roli `KsefOperator` oraz atrybutu `taxpayerNips` (przed udostępnieniem serwisu użytkownikom).

## Phase 3 — SAP Remote Integration

Statusy wskazują środowisko i zakres dowodu: lokalny CAP LIVE nie oznacza CAP na Cloud Foundry.

- [x] **CAP-04B (2026-10-28): SAP RAP `ApplyKsefResult` aktywna w H01.** OUTBOUND/TEST, cztery readonly pola, CAS po blokadzie, wyniki idempotencji/konfliktów, odrębne prawa akcji i CRUD. EML 18/18, prototyp blokowania 7/7 (w tym root enqueue między sesjami), ATC dziesięciu obiektów kontraktu 0 ustaleń, surowy runtime EDMX HTTP200. Uprawnienie bez nadania. Bez HTTP WRITE/commit i pełnego testu dwóch równoległych zapisów. [Dowody](journal/2026-10-28-cap-04b-rap-write-contract.md), [ADR-0002](decisions/ADR-0002-sap-ksef-result-write-contract.md).
- [x] **CAP-04C (2026-10-28):** CAP Write Adapter, interpretacja `Outcome`, wersja 100 ns, niezmienne zlecenie, persistent outbox/worker i historia. Testy rollback/konkurencji/restartu z mockiem, lokalny kontrakt HTTP i regresja READ H01 PASS. Transport OFF, brak prawdziwego producenta ACCEPTED i HTTP WRITE. 429/Retry-After oraz wyczerpane retry wymagają obsługi operacyjnej. [Dowody](journal/2026-10-28-cap-04c-sap-result-writeback.md).
- [x] **CAP-04 Hardening (2026-10-29, commit `dfd1ecb`):** claim/finalize, recovery, SHA-256 i unikalność numeru; historyczny build 71/71 PASS H2/mock. [Dziennik](journal/2026-10-29-cap-04-hardening.md).
- [x] **Hardening Fix 2 (2026-10-29, lokalnie zweryfikowany; publikacja Git na zlecenie użytkownika):** rewizja CAS resume/claim, test prawdziwej odmowy CRUD przez CAP 5.2, jawne timeouty Cloud SDK oraz odnawianie lease. **82/82 PASS** w pełnym clean verify (H2/mock, LIVE wykluczone). Dowody i ograniczenia SAP w [kontynuacji dziennika](journal/2026-10-29-cap-04-hardening.md#kontynuacja--hardening-fix-2-i-cap-04d-readiness).
- [ ] **BLOCKED / NOT VERIFIED:** test H01 CBA z globalnym ACTVT02 bez prawa do NIP/spółki rodzica — [przygotowany szkic testu](patches/cap-04-cba-scope-test.md), brak dostępu do include przez MCP. DCL/IAM/arrangement i odmowa rzeczywistym principalem nadal wymagają weryfikacji.
- [ ] **Do decyzji po hardeningu:** polityka odczytu DCL ([propozycja](guides/SAP_WRITE_ACCESS_AND_READ_POLICY.md)), nadanie principalowi zapisu, interfejs operacyjny `resume`, `total etag` dla pozycji, producent migawek/potwierdzeń (Phase 4).
- [ ] **CAP-04D — WYMAGA OSOBNEJ ZGODY:** ograniczone nadanie ACTVT16/NIP/CompanyCode, HTTP WRITE na uzgodnionych danych, persisted ETag/no-op, testy prawdziwym principalem i pełne równoległe zapisy akcji.
- [x] CAP-03 live H01 preview (2026-10-27): aktywny binding OData V4 `ZAPI_AINV_INV_H_O4` / `0001`; `$metadata` HTTP 200 i encje/nawigacje potwierdzone. Autoryzowany read z `$expand=_Items,_References` zwrócił 4/9/6 i UUID zgodne z seedem. To odczyt przez MCP OAuth, nie przez CAP.
- [x] Dodano CDS model zewnętrzny, CAP Remote Service Destination `AINV_H01`, read-only adapter CQN, test kontraktu adaptera i blokadę publicznego dostępu do zewnętrznego modelu; `mvn clean verify -Dcds.cds.skipVersionCheck=true` PASS (22 testy).
- [x] CAP-04B: odczyt rzeczywistego surowego EDMX przez HTTP GET; akcja publiczna, parametry, wynik i readonly pól potwierdzone. Nie importowano modelu do CAP.
- [x] CAP-04C: model CAP z pełnego EDMX, oryginalny namespace i wymaganie `internal-user`; adapter READ zachowany, akcja ma osobny Write Adapter.
- [x] CAP-03 krok 2 (2026-10-27): lokalny test HTTP (fałszywy serwer, **nie LIVE**) przez prawdziwy CAP RemoteService + Cloud SDK Destination potwierdził poprawny URL i Basic Authentication; naprawiono 3 błędy (dodatkowy segment `AinvoicerH01/` w URL, `@requires: internal-user` blokujące odczyt bez użytkownika, filtr `in`). Dodano opcjonalny test LIVE (`@Tag("live-sap")`, domyślnie wyłączony).
- [x] **CAP → SAP H01 READ: LIVE PASS (2026-10-27), lokalny proces CAP + lokalna Destination.** `SapInvoiceRemoteAdapterLiveTest`: 1 test, 0 failures, 0 errors, `BUILD SUCCESS`; 4 nagłówki, 9 pozycji, 6 referencji, UUID zgodne. Destination przekazano przez env `destinations` z Basic Auth. To nie jest CAP na CF ani BTP Destination Service. Historia pierwszego IAS redirectu i błędów technicznych pozostaje w [dzienniku](journal/2026-10-27-cap-03-sap-remote-integration.md).

- [x] Bootstrap Git/abapGit dla pakietu `ZAINVOICER`: branch `java-cap-ksef`, `.abapgit.xml` wskazuje `/abap/src/`, Folder Logic `FULL`; pierwszy eksport potwierdzony commitem `39ab869`.
- [x] Inspekcja repo wykazała symulacyjny model tabel `ZAINV_INV_H`, `ZAINV_INV_I`, `ZAINV_INV_REF` i service definition/binding `ZAPI_AINV_INV_H_O4`.
- [x] Weryfikacja runtime przez read-only MCP H01 (2026-10-27): aktywny binding `ZAPI_AINV_INV_H_O4`, wersja `0001`, published=true; `$metadata` HTTP 200, entity sets i nawigacje potwierdzone. Autoryzowany OData `$expand` zwrócił 4/9/6 i oryginalne UUID. MCP nie zwrócił surowego EDMX ani hosta dla konfiguracji CAP Destination.
- [x] Przygotowano lokalny patch źródeł root/child CDS, behavior, DCL i service definition dla kompozycji Header–Items–References; niepotwierdzony w SAP i bez serializowanych sidecarów nowych obiektów.
- [x] Diagnostyka tylko do odczytu potwierdziła `recordChanges=false` / `ZLOCAL` dla `ZAINVOICER`; usunięto błędny wymóg transportu. Ustalono poprawny typ tworzenia `DDLS/DF` i odtworzono historyczny komunikat deserializacji. W tamtym etapie nie naprawiano generatora MCP; późniejszy wynik v5 opisano niżej.
- [x] Zapisano istniejące obiekty kompozycji według `d3dedf4` (2026-10-24); historia: [dziennik edycji](journal/2026-10-24-abap-rap-composition.md#edycja-istniejacych-obiektow-wedlug-d3dedf4--2026-10-24).
- [x] ABAP-02 live (2026-10-25): utworzono przez poprawione MCP 3 brakujące DDLS, zachowano zastany `ZR_AINV_INV_I`; aktywowano 6 CDS, 2 BDEF i istniejący SRVD. Poprawki H01: brak niedozwolonego `Semantics.unitOfMeasure`, dziedziczenie provider contract w child projections i własny ETag dzieci. Root/binding zachowane. ATC głównego BDEF: 0 ustaleń. [Dowody i różnice z Git](journal/2026-10-24-abap-rap-composition.md#kontynuacja-live-przez-poprawione-mcp--2026-10-25).
- [x] Próba możliwości tworzenia MCP w `ZAINVOICER_DEV` (2026-10-25): `CLAS/OC` utworzyła się, `TABL/DT`, `DDLS/DF` i `DTEL/DE` zwróciły `Unsupported Media Type`; brak częściowych obiektów po kontrolnym wyszukiwaniu. Kontrola statusu ujawniła przypisanie klasy do istniejącego requestu `H01K900018`, mimo odpowiedzi „local — no transport required”; nie zmieniano requestu ani nie aktywowano klasy. Szczegóły: [dziennik](journal/2026-10-25-abapfs-create-probe.md).
- [x] ABAP FS 2.12.4 / fix 5: potwierdzono **4/4** nowych klas/CDS w obu pakietach Cloud, brak nowych requestów i poprawne przypisania SAP; uaktualniono instrukcje. `recordChanges=false` pomija selektor, a „Transport requested” wymaga odczytu przypisania. To nie jest pełny test typów ani test ECC. [Dowody i procedura](journal/2026-10-26-abapfs-v5-instructions.md).
- [x] ABAP-04 — utworzono i aktywowano `ZCL_AINV_TEST_DATA` w H01. Deep EML obejmuje FI_AP, FI_AR, MM_IV i SD_BILL z 9 pozycjami oraz 6 referencjami; diagnostyka bez błędów, ATC bez błędów/ostrzeżeń. [Dziennik](journal/2026-10-26-abap-04-test-data-seed.md).
- [x] ABAP-04 — użytkownik potwierdził H01 Trial i przekazał log uruchomienia; niezależny ABAP SQL SELECT z CDS potwierdził 4 nagłówki, 9 pozycji, 6 referencji, powiązania po UUID, PLN i zgodność sum.
- [x] ABAP-04 — uwierzytelniony runtime OData `$expand=_Items,_References` przez MCP potwierdził 4/9/6 oraz UUID (2026-10-27; szczegóły w [CAP-03](journal/2026-10-27-cap-03-sap-remote-integration.md)).
- [ ] ABAP-04 — wykonać sekwencyjny rerun i potwierdzić brak duplikatów. Brak blokady dla równoległych uruchomień pozostaje ograniczeniem aktywnego modelu.
- [x] DCL/EML — **wynik według raportu użytkownika z 2026-10-26, niepowtórzony w tym audycie:** 6 DCL aktywnych; `ZCL_AINV_RAP_COMP_TEST` 4/4 ABAP Unit PASS; ATC trzech nowych DCL: 0 ustaleń. Historyczny dump `ACM_UNEXPECTED_VALUE`, brakujące role i `Unsupported Media Type` pozostają w dziennikach jako wcześniejszy stan/problemy.
- [x] CAP-04B: odczyt live DCL i implementacji przed zmianą; wdrożono i przetestowano global/instance authorization oraz prechecks zapisu. Szczegółowy zakres testów EML/ATC w dzienniku; historycznego testu kompozycji nie odtwarzano.
- [ ] **NOT VERIFIED:** polityka izolacji odczytu DCL per NIP. Root `where TRUE` pozostawiono; aktywacja DCL i testy zapisu nie potwierdzają izolacji odczytu.
- [x] Po zgodzie użytkownika wyeksportowano 31 plików CAP-04B przez MCP abapGit: `525e48d`, remote refs/status potwierdzone, lokalny fast-forward. Pominięto zastane zmiany spoza zakresu. Nie importowano niczego do H01.
- [x] Wykonać runtime `$metadata`, `$expand` i autoryzowany odczyt syntetycznych danych z SAP przez MCP; wynik live oddzielono od testów CAP/mocks w [CAP-03](journal/2026-10-27-cap-03-sap-remote-integration.md).
- [x] Dodać model zdalny CAP CDS na podstawie runtime kontraktu, konfigurację CAP Java Remote Service i read-only adapter CQN. To model opracowany z normalizowanych metadanych, nie import surowego EDMX.
- [ ] **NOT TESTED:** zweryfikować CAP → SAP H01 READ po wdrożeniu CAP na Cloud Foundry z przypiętą Destination Service. Lokalny test z env `destinations` tego nie potwierdza.
- [x] CAP-04C: wywołanie zapisu przez RAP action, atomowe zlecenie i persistent worker. Walidacja lokalnym HTTP/mocks; **LIVE WRITE NOT TESTED**, transport domyślnie wyłączony. CAP-04D pozostaje osobnym zadaniem.

ABAP Environment jest tu źródłem symulacyjnych danych aplikacyjnych; nie zakładaj standardowych tabel/dokumentów S/4HANA ani modelu `ZFI_ACDOCA`. Bez bezpośredniego SQL do bazy ABAP i bez pozornych JOIN-ów z lokalną bazą CAP. Szczegóły: [model RAP](guides/ABAP_RAP_INVOICE_MODEL.md) i [workflow abapGit](guides/ABAPGIT_WORKFLOW.md).

## Phase 4 — KSeF SDK Integration

**IN PROGRESS — Agent A, `feature/ksef-auth`, 2026-10-29:** SDK 3.0.29,
auth/sesje, trwały worker/checkpointy i rekoncyliacja; **141 testów lokalnych PASS** po integracji A+B.
LIVE auth TEST (XAdES → 200 → redeem → chroniony READ → refresh) PASS.
**LIVE E2E CAP/SDK PASS** na oficjalnym fixture: jedna faktura
`1234563218-20261029-7DC8A1C00000-CD`, UPO 5528 bajtów, RESUME po restarcie.
FA(3) B połączono ze snapshotem i outboxem (`7075c01` → `657c285`), z pełną kontrolą
dużych maxOccurs na granicy wysyłki. Źródło LIVE H01 i LIVE send XML wygenerowanego
przez B pozostają niepotwierdzone (NIP/PartyData; bez drugiej wysyłki).
[Dziennik](journal/2026-10-29-agent-a-ksef-sdk.md).

- [x] SDK MF 3.0.29 przypięte, zbudowane ze źródeł i użyte w JVM CAP/Boot 4; mock/HTTP i LIVE TEST PASS (Agent A).
- [x] TEST lokalnie: XAdES, biznesowe 200, redeem, JWT w pamięci, refresh i autoryzowany READ PASS; zarządzane credentiale BTP przed CF.
- [x] **Poprawny XML FA(3) według oficjalnego XSD (Agent B, 2026-10-29, `feature/ksef-fa3`):** generator podstawowej faktury VAT, walidacja XSD 1-0E offline + domenowa, bajty i SHA-256 dla migawki CAP, adapter SAP. Lokalnie 112/112 PASS; przyjęcie przez KSeF niezweryfikowane. [Przewodnik](guides/FA3_XML_GENERATION.md).
- [x] Sesje i kryptografia SDK; dokładne bajty B → snapshot → storage → send potwierdzone testem A+B; LIVE jedna faktura z oficjalnego fixture.
- [ ] Źródło danych spoza RAP dla FA(3): adres sprzedawcy/nabywcy, JST/GV; poprawne NIP-y w seedzie H01.
- [x] Trwały worker/outbox, checkpointy przed HTTP, lokalne testy rollback/konkurencji/restartu PASS; brak blokad biznesowych podczas sieci.
- [x] TEST lokalnie: status 200/hash/numer, close i UPO; plikowa H2 i jawny lokalny magazyn TEST. Zarządzany storage przed CF pozostaje osobnym zadaniem.
- [x] TEST/H2: bezpieczne odczyty z limitem/backoff/jitter/Retry-After, rekoncyliacja hash/ref i brak ponownego send/close po niejednoznacznej odpowiedzi; CF/HANA osobno.
- [ ] Pierwszy vertical slice: jedna syntetyczna faktura SAP Remote OData → CAP → SDK → KSeF TEST → numer KSeF i UPO; wysyłka tylko po jawnej zgodzie.

HTTP 202 nie oznacza przyjęcia faktury. Eksperyment na `feature/ksef-java-e2e-runner` jest referencją według kontekstu projektu; nie zalicza tego etapu.

## Phase 5 — SAPUI5 + CAP

- [x] **CAP-06.1 lokalny hardening i review (2026-11-08):** globalne bezpieczne ustawienia H2/mock users, opt-in loopback H2, izolacja/redirecty credentiali runnera, walidacja filtrów AI i tras CAP; Maven203, QUnit76/438, OPA13/80, runner3, oba buildy PASS. LIVE READ H011/1 i dual10/63 PASS. **Smoke FLP nadal KNOWN BLOCKER**, npm tooling74 advisories; bez IAS, SAP WRITE, SEND i merge. [Audyt](journal/2026-11-08-cap-06-1-hardening.md).
- [x] **CAP-07 — BTP Identity, OFFLINE (2026-11-08):** XSUAA (ADR-0006), `cds-feature-identity` + resource-server starter, `xs-security.json`, fail-closed par NIP/spółka, `CloudIdentityGuard`, approuter 23.4.0 + `mta.yaml`. Maven 230/230, OFFLINE JWT 10/10, approuter config PASS, UI5 76/13/3 PASS. [Dziennik](journal/2026-11-08-cap-07-btp-identity.md).
- [x] **CAP-07.1 — Deployment readiness, LOCAL (2026-11-08):** HANA/HDI + `CloudPersistenceGuard` (ADR-0007), wariant UI5 `build:cf` (rap, Send OFF, bez testów), wąski redirect XSUAA, MTAR złożony (`mbt module-build`+`assemble`), specyfikacja SSO H01 A/B, runbook. Maven 236/236 + JAR, QUnit 77, OPA 13, approuter 7. [Dziennik](journal/2026-11-08-cap-07-1-deployment-readiness.md) · [Runbook](guides/BTP_FIRST_DEPLOYMENT_RUNBOOK.md).
- [ ] **CAP-07B etap 1 (2026-11-08) — weryfikacja bez deployu:** Git zsynchronizowany z `main` (diagramy zachowane), regresja 236/77/13/7/7 PASS, poprawka testu approutera (CRLF), MTAR 67 MB poprawioną procedurą, H01 4/9/6 LIVE (MCP), API CF EU20 osiągalne, cf CLI 8.19.0 lokalnie; **BLOCKED: login CF właściciela + warunkowa zgoda na deploy (HANA tylko free)**. [Dziennik](journal/2026-11-08-cap-07b-btp-live-deployment.md).
- [x] **CAP-07B etap 2 (2026-11-09) — pierwszy deploy LIVE identity-smoke:** XSUAA + approuter + CAP na CF eu20, negatywne testy LIVE 401/403 PASS, 4 defekty CF naprawione. HANA BLOCKED (brak `hdi-shared`/`hana-free`, tylko plan płatny). [Dziennik](journal/2026-11-08-cap-07b-btp-live-deployment.md#kontynuacja-2026-11-09--pierwszy-deploy-live-identity-smoke-zatwierdzony-przez-właściciela).
- [ ] **CAP-07B — LIVE BTP:** entitlementy xsuaa/destination/HANA Cloud/hdi-shared (NOT VERIFIED), SapMachine 25, `mbt build` + deploy po zgodzie, role collection 5260002008/1000 + drugi użytkownik A/B, Destination `AINV_H01_UI_USER` wg wariantu A/B, testy runbooku §7, weryfikacja HANA (lock/CAS, outbox po restarcie). [Runbook](guides/BTP_FIRST_DEPLOYMENT_RUNBOOK.md).
- [ ] AI/Scanner/Voice na CF: trasy approutera/Destinations zamiast bezpośrednich hostów `cfapps.us10-001` i OpenAI z przeglądarki (osobne zadanie).
- [ ] **Model tuple NIP×CompanyCode:** jeden atrybut par zamiast dwóch list; obecnie fail closed dla ≥2×≥2.
- [ ] CAP-04D nadal BLOCKED na odrębne bramki WRITE (preflight read-only CAP-07: binding/READ/`Z_AINV_KSF` OK; arrangement/ACTVT16/CSRF — admin).

- [x] **CAP-06/UI5-09 — 2026-11-08, zakres lokalny + LIVE H01:** CAP startup/metadata/status READ na świeżej H2, 0 operacji; realny RemoteService READ H01 1/1 (4/9/6, keyed READ i typy). SAPUI5 z RAP LIVE i CAP runtime z lokalnym mock principalem: 10/10 testów przeglądarki. Naprawione OData4.01/count, H2 console OFF, filtry RAP (tolower/MaxLength/waluta). Maven186, QUnit72, OPA13 i oba buildy PASS. [Dziennik](journal/2026-11-08-cap-06-ui5-09.md).
- [ ] **CAP-06 — bramka tożsamości:** IAS/XSUAA dependencies/binding, approuter i trusted taxpayerNips/companyCodes, role collections i test faktycznego business usera. Obecny lokalny mock principal nie zalicza LIVE IAS. [Plan administratora](guides/RAP_UI_LIVE_CONNECTION.md#cap-06--tożsamość-cap-i-plan-btp).
- [ ] **UI5-09 — pozostałe bramki:** izolacja A/B H01 (dodatkowe zatwierdzone dane/user), pełny deploy gate UI DCL na czystym systemie i naprawa serializacji; Legacy `/sap` 503/flex FLP. CAP-04D BLOCKED, bez SAP WRITE/KSeF SEND. Szczegóły niżej są historią wcześniejszych sprintów.

- [ ] **ABAP-08/UI5-08 — 2026-11-07: główny cel LIVE VERIFIED** (Business Role `ZBR_AINV_READ_1000`, READ 4/9/6, SAPUI5 lista z H01 przez `/rap-ui`, Send OFF); **scalone do `main` (`153c3f3`)**. Pozostaje: szczegóły/sort/filtr w UI, izolacja A/B (drugi user lub zgoda na fakturę spoza zakresu), pusty wiersz BUK w IAM App, eksport abapGit, ATC. Osobno: UI5 CLI v4 (odblokuje nowsze `@sap/ux-ui5-tooling`), host trasy Legacy `/sap`. [Dziennik](journal/2026-11-02-abap-08-ui5-08.md). Historia:
- [ ] **ABAP-08/UI5-08 (2026-11-02), IN PROGRESS/BLOCKED:** ABAP-07 zintegrowane na `feature/abap-08-live-integration` (merge `160130c`); Maven 178/178, QUnit 69/69, OPA 13/13, oba buildy PASS (lokalnie/mock). Do zrobienia: Business Role z `ZAINV_BC_READ` (Read=Restricted, Write=No Access, NIP5260002008/BUK1000) i przypisanie business usera, ponowne logowanie MCP, business-user READ i macierz DCL, UI5 w trybie rap (lokalnie, Send OFF), eksport abapGit (puste DCL w diffie MCP), potem osobno CAP-04D (NOT READY). [Dziennik](journal/2026-11-02-abap-08-ui5-08.md). **2026-11-07:** binding UI LIVE VERIFIED, DCL „bez roli” LIVE VERIFIED (UI 0 vs Web API 4), Business Role **nie istnieje** (I_IAMBusinessRole) → do utworzenia przez administratora; regresja 178/69/13 PASS. [Kontynuacja](journal/2026-11-02-abap-08-ui5-08.md#kontynuacja-2026-11-07--logowanie-mcp-działa-business-role-nie-istnieje).

- [x] **ABAP-07/UI5-07 lokalnie (2026-10-30):** branch z origin/main; bounded READ CAP z abortem, obsługa 401/403/timeout/pustej strony i testy stabilnej paginacji. Maven178/178, QUnit69/69, OPA13/13, oba buildy PASS. ATC7/7 bez ustaleń, UI binding i IAM developerskie ponownie sprawdzone; Web API4/9/6, UI developer OAuth200/0. [Dziennik](journal/2026-10-30-abap-07-ui5-07.md).
- [ ] **ABAP-07 LIVE BLOCKED:** potwierdzić w Fiori efektywne przypisanie ograniczonej Business Role; potem business-user READ, macierz izolacji i UI5 RAP. Brak narzędzia MCP do Business Roles, przechwytywanie okna niedostępne; nie potwierdzono braku roli. CAP user IAS/XSUAA i Destination nadal niezweryfikowane. [Procedura](guides/RAP_UI_LIVE_CONNECTION.md).
- [ ] **Eksport ABAP-07:** status33, 30 kandydatów UI/IAM do przeglądu, 3 wpisy poza zakresem; MCP nadal serializuje DCL jako puste. Pełny eksport ADT po review/testach i odrębnym zleceniu publikacji SAP; nie przełączono linked branch. CAP-04D nadal wymaga READ i osobno autoryzowanego WRITE.

- [x] **ABAP-06 kontynuacja — UI binding opublikowany przez użytkownika:** własny MCP READ potwierdza active/V4/published=true/0001 oraz metadata HTTP200; rzeczywisty URI zapisany. UI kolekcje HTTP200/0 w developer OAuth; nie jest to PASS dostępu do danych business usera.
- [x] **IAM — obiekty developerskie w H01:** aktywne domeny CHAR10/CHAR4 i DTEL, RF NIP/spółka, wspólny ZAINV_RT_READ, ZAINV_UI_READ_EXT z OData V4/ACTVT03 i wartościami NIP/spółki, opublikowany ZAINV_BC_READ (Read=true/Write=false/F4=false), assignment ZAINV_BC_READ_0001. Zmiany użytkownika w ADT potwierdzone READ MCP. [Model i zależności](guides/RAP_UI_IAM_MODEL.md).
- [ ] **Następna bramka IAM:** rola biznesowa, efektywne zakresy i przypisanie business usera; pełna macierz READ/izolacji A/B i UI5 LIVE. Najnowszy MCP developer OAuth HTTP200/0 nie zalicza tych testów. Brak nadań przez agenta i brak eksportu snapshotu SAP do Git.

- [x] **ABAP-06 inspekcja (2026-10-30):** H01 connected, aktywne 3 CDS/3 DCL/SRVD i brak wersji inactive; nowe ATC 7/7 bez ustaleń. abapGit nadal java-cap-ksef. Web API READ developer OAuth HTTP200, 4/9/6. [Dziennik](journal/2026-10-30-abap-06-ui5-06.md).
- [x] **UI5-06 lokalnie:** Send domyślnie OFF również po przełączeniu do rap, zachowane aliasy InvoiceItems/InvoiceReferences, poprawiony szablon i runbook. Maven 178/178, QUnit 61/61, OPA 13/13, oba buildy PASS. [E2E-01 przygotowany, nie wykonany](guides/E2E_01_SAP_TO_KSEF_TEST.md).
- [ ] **ABAP-06 LIVE BLOCKED na IAM:** binding V4 UI już active/published (kontynuacja użytkownika), metadata HTTP200. Potwierdzić ograniczone IAM A/B i wykonać READ/UI5 LIVE. Zachować istniejące aliasy; bez migracji SRVD.
- [ ] **Eksport UI SAP → Git:** 17 plików w zakresie; MCP diff zwraca pustą treść 6 plików DCL. Wymagany pełny eksport ADT/abapGit po testach lub naprawa serializacji MCP; brak eksportu do abap/src i brak pushu.

- [x] **CAP-05.1:** retry REJECTED domyślnie OFF, walidacja max-attempts 1..10; Maven clean verify **178/178 PASS** H2/mock. [Dziennik ABAP-05](journal/2026-10-29-abap-05-real-rap-ui.md).
- [x] **ABAP-05 inspekcja LIVE:** zastane aktywne trzy CDS `ZC_AINV_INV_*_UI`, trzy DCL i SRVD; ATC 7/7 bez ustaleń. Nie utworzono/aktywowano obiektów SAP; nie oznacza zaliczenia IAM runtime.
- [x] **UI5-05 lokalnie:** fail closed dla wadliwej/niepełnej historii CAP, szablon osobnego routingu RAP; build i build szablonu PASS, QUnit **59/59**, OPA **13/13**. Domyślny mock zachowany.
- [ ] **ABAP-05 LIVE UI — kontynuacja ABAP-06:** binding V4 UI w ADT, publikacja, ograniczone IAM dwóch business userów, macierz READ i UI5 na rzeczywistym URI. Zachować InvoiceItems/InvoiceReferences. [Instrukcja](guides/RAP_UI_LIVE_CONNECTION.md). Nie tworzyć drugiego zestawu historycznych patchy ZC_AINV_UI_*.

- [x] CAP-05: A+B i UI5 C na `feature/cap-05-integration`; CAP clean verify **173/173 PASS** H2/mock; UI5 build/QUnit/OPA PASS. [Audyt](audits/2026-10-29-cap-05-implementation-review.md).
- [x] `requestSend(invoiceUUID)` — własny odczyt RAP, NIP/CompanyCode, TEST, FA(3)/snapshot, atomowa rezerwacja DB i persistent outbox. Konkurencja dwóch użytkowników, rollback, nieznane stany i controlled retry potwierdzone lokalnie.
- [x] ACCEPTED → SAP writeback po commit + sweeper; brak utraty przyjęcia/UPO przy błędzie enqueue/SAP; tylko SAP_RAP. VERSION_CONFLICT blokuje retry SEND, bez obejścia CAS; diagnostyka read-only.
- [x] Istniejący frontend: named RAP V4 model, ograniczona strona, server paging/filter/sort RAP, CAP stan integracji dla UUID strony i akcje; priorytet numeru RAP. Brak CAP proxy listy.
- [x] Regresja mock: AI Assistant, Scanner, Voice controls i Legacy FI; QUnit i OPA. Nie oznacza LIVE AI/STT ani CAP→SAPUI5 na BTP.
- [ ] **BLOCKED runtime:** CDS/DCL/SRVD UI są już aktywne w H01 i przeszły ATC; binding V4 UI i IAM/testy faktycznymi business userami nadal wymagają [ADT i administratora](patches/cap05-rap-ui/README.md). Stan aktywny był zastany, bez aktywacji w tym sprincie.
- [ ] Approuter i IAS/XSUAA: zgodne trusted claims NIP/CompanyCode w RAP/CAP; trasa `/rap-ui/` do zweryfikowanego bindingu. Domyślny UI pozostaje mock do przejścia bramki.
- [ ] Autoryzowane publiczne pobranie XML/UPO; obecnie worker przechowuje UPO i publikuje upoAvailable, przyciski nadal wyłączone.
- [ ] Globalne filtrowanie po statusie CAP (obecnie jawnie bieżąca strona); HANA/multi-instance i migracja historycznej bazy z uzupełnieniem zaufanego CompanyCode.
- [ ] Osobna ocena biznesowa blokowania UPDATE/DELETE po numerze KSeF i total version kompozycji; patch testów charakteryzacyjnych NOT SAP VERIFIED.
- [ ] Kontrolowany LIVE E2E: poprawne syntetyczne dane RAP + PartyData, jedna wysyłka TEST i writeback, numer w UI/UPO/409 po ponowieniu. Wyłącznie po osobnym zleceniu; w CAP-05 nie wykonano SEND/HTTP WRITE.

## Phase 6 — BTP & Production Hardening

- [ ] Potwierdzenie entitlementów i konfiguracji SAP HANA Cloud; migracja i testy trwałości (deklaracja HDI i guard gotowe — CAP-07.1/ADR-0007).
- [ ] Przygotowanie deploymentu Cloud Foundry; wykonanie wdrożenia wyłącznie w autoryzowanym zakresie.
- [ ] OAuth2/XSUAA lub IAS według konfiguracji; zarządzane sekrety, service bindings i Destination.
- [ ] Restrykcyjny CORS, limity wejścia, bezpieczne błędy i autoryzacja API.
- [ ] Testy worker/outbox, idempotencji i konkurencji przy restartach oraz wielu instancjach CF; monitoring i audyt bez sekretów.
- [ ] Trwały magazyn XML/UPO, rozdział TEST/DEMO/PROD i procedura rekoncyliacji.

Podstawy trwałości, idempotencji i autoryzacji powstają przed wysyłką w Phase 2–4; ten etap sprawdza je w docelowej infrastrukturze. BTP Trial nie jest środowiskiem produkcyjnym. Batch, zakupy, korekty, offline i multi-NIP pozostają poza pierwszym vertical slice.
