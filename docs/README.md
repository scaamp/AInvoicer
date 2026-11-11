---
title: "AInvoicer — Project Home"
date: 2026-10-23
type: home
status: aktywny
tags: [ainvoicer, dokumentacja]
---

# AInvoicer — baza wiedzy projektu

**Owner-led learning:** [BLOB-LAB-01 — samodzielne ćwiczenie CAP Java / HDI / HANA BLOB](guides/BLOB_LAB_01_OWNER_LED_HDI.md). Codex i Claude Code nie mogą realizować tego laboratorium autonomicznie; mogą pomagać wyłącznie w zakresie wskazanym przez właściciela. Laboratorium nie zostało rozpoczęte ani zatwierdzone do LIVE deployu.


## Strategia infrastruktury i bazy danych

- [Infrastructure as Code / Database as Code — pełna strategia, architektura, etapy, ryzyka, backup/DR i przyszły SaaS](architecture/INFRASTRUCTURE_AS_CODE_STRATEGY.md).
- [ADR-0009 — przyjęcie etapowej strategii IaC i DbAC](decisions/ADR-0009-iac-database-as-code-strategy.md).

> Strategia przyjęta; model CAP CDS/HDI oraz walidacja offline istnieją, lecz nie jest to pełne IaC BTP ani mechanizm backupu. Bieżący stan runtime CAP/HANA sprawdzaj w [STATUS](STATUS.md) i [dzienniku CAP-HANA-01](journal/2026-11-11-cap-hana-01-first-persistence.md).


**CAP-HANA-01 LIVE: zatwierdzony deployment przeszedł merge, ale staging deployera zakończył się ERROR.**
Deployer utworzony i przypięty do HDI; CAP/AppRouter pozostają na poprzednim runtime, CAP HANA NOT DEPLOYED.
Naprawa przenośności ZIP Windows→Unix odbudowana i zweryfikowana offline, nowe hashe w runbooku;
CI poprawki1ea445a SUCCESS, nowa zgoda PENDING.
[Najnowszy wynik](journal/2026-11-11-cap-hana-01-first-persistence.md).
Poniższe wpisy opisują wcześniejsze etapy.

**CAP-HANA-01: pierwsza próba deploymentu odrzucona przed stagingiem; NO-GO dla poprzednich MTAR.**
Naprawiono mapowanie typów projektu do typów CF,29/29 testów PASS; oba MTAR odbudowane i verify PASS.
Nowe SHA w dzienniku/runbooku; wymagane zatwierdzenie abort starej operacji ERROR i nowego deploymentu.
[Aktualny wynik i przyczyna](journal/2026-11-11-cap-hana-01-first-persistence.md).
Poniższe GO jest historią preflightu sprzed błędu; CAP HANA nadal NOT DEPLOYED.

CAP-HANA-01 (2026-11-11): [final review, merge INT-03 i dowody przygotowania](journal/2026-11-11-cap-hana-01-first-persistence.md) ·
[runbook pierwszego HDI/deploymentu/testów/rollbacku](guides/CAP_HANA_01_FIRST_DEPLOYMENT_RUNBOOK.md).
PR #8 MERGED e2696ab po CI PASS. [HDI provisioning i koszty](journal/2026-11-11-hdi-shared-provisioning.md):
HDI **VERIFIED LIVE**, Cloud Central RUNNING/Database ID **OWNER-REPORTED**;
CAP→HANA **NOT DEPLOYED**, GO do osobnego zatwierdzenia kontrolowanego deploymentu DEV.

Aktualizacja INT-03 (2026-11-11): [dziennik integracji i testy](journal/2026-11-11-int-03-orchestration.md),
[audyt security/persistence](audits/2026-11-11-int-03-security-persistence.md),
[HANA CAP deployment](guides/HANA_CLOUD_CAP_DEPLOYMENT.md),
[HANA Free Tier as-built](guides/HANA_CLOUD_FREE_TIER_SETUP.md),
[dziennik konfiguracji HANA](journal/2026-11-11-hana-cloud-free-tier.md),
[storage](guides/KSEF_DURABLE_STORAGE.md), [dziennik STOR-01](journal/2026-11-11-ksef-stor-01.md),
[CAP-07D acceptance](guides/CAP_07D_SECURITY_ACCEPTANCE.md), [dziennik CAP-07D](journal/2026-11-11-cap-07d.md),
[propozycja ADR-0008 BLOB](decisions/ADR-0008-document-storage-dev-test-proposal.md).
**HANA Free Tier LIVE / HDI PROVISIONED / CAP HANA NOT CONNECTED / NOT DEPLOYED.**

AInvoicer to aplikacja SAP Fiori do faktur zakupowych i sprzedażowych, rozwijana w kierunku integracji z KSeF 2.0. Repozytorium zachowuje istniejące moduły ABAP, SAPUI5, Node.js i Python; backend powstaje w `cap-java/` na głównej gałęzi `main`. Dotychczasową linię `java-cap-ksef` promowano do `main`; oryginalny projekt zachowano na `archive/original-ainvoicer-2026-10-29`.

## Od czego zacząć

- [CAP-07B.2 — XSUAA, business-user SSO, Destination i RAP H01: konfiguracja LIVE, architektura, testy i luki bezpieczeństwa](guides/CAP_07B_2_BTP_IDENTITY_SSO_LIVE.md) — **FUNCTIONAL PASS / SECURITY ACCEPTANCE PENDING** (2026-11-10).

- [INT-02 — KSeF Inbox Full-Stack: integracja, testy i publikacja](journal/2026-11-10-int-02-inbound-fullstack.md) · [Kontrakt i runner HTTP/UI5](guides/INT_02_INBOUND_FULLSTACK.md) · [Security review](audits/2026-11-10-int-02-security-review.md).
- [KSEF-IN-02 — staging, checkpoint CAS, lease i recovery](journal/2026-11-10-ksef-in-02.md) · [Persystencja](guides/KSEF_INBOUND_PERSISTENCE.md).
- [UI5-10 — ekran KSeF Inbox](journal/2026-11-10-ui5-10-inbound-inbox.md) · [Przewodnik UI5](guides/KSEF_INBOX_UI5.md).

- [INT-01 — integracja CAP-07C + KSEF-IN-01, pełna regresja i publikacja PR](journal/2026-11-09-int-01-branch-integration.md) · [Audyt integracji, konflikt PR #1 i plan merge do main](audits/2026-11-09-int-01-integration-review.md).
- [KSEF-IN-01 — rdzeń metadata/XML, parser FA(3), deduplikacja i HWM; offline](journal/2026-11-09-ksef-in-01-inbound.md) · [Architektura INBOUND](guides/KSEF_INBOUND_ARCHITECTURE.md).

- [CAP-07C — powtarzalny MTAR identity-smoke, regresja executable JAR CF, blokady AI/Scanner/Voice, CI i CAP-04D offline](journal/2026-11-09-cap-07c-autonomous-hardening.md).

- [CAP-07B etap 1 — weryfikacja przed LIVE BTP: regresja PASS, MTAR 67 MB, H01 4/9/6; deploy BLOCKED (login CF + zgoda właściciela)](journal/2026-11-08-cap-07b-btp-live-deployment.md).
- [CAP-07.1 — gotowość wdrożenia CF: HANA/HDI fail-closed, UI5 build CF, MTAR, SSO H01 (LOCAL PASS, LIVE NOT EXECUTED)](journal/2026-11-08-cap-07-1-deployment-readiness.md) · [Runbook pierwszego wdrożenia](guides/BTP_FIRST_DEPLOYMENT_RUNBOOK.md) · [ADR-0007](decisions/ADR-0007-cap-persistence-hana-cloud.md).
- [CAP-07 — BTP Identity: XSUAA, approuter, fail-closed par NIP/spółka (OFFLINE PASS, LIVE BLOCKED)](journal/2026-11-08-cap-07-btp-identity.md) · [ADR-0006](decisions/ADR-0006-btp-identity-xsuaa.md) · [Przewodnik: BTP Identity setup](guides/BTP_IDENTITY_SETUP.md).
- [CAP-06.1 — hardening, pełna regresja i gotowość PR; smoke FLP nadal KNOWN BLOCKER](journal/2026-11-08-cap-06-1-hardening.md).

- [CAP-06/UI5-09 — RAP LIVE + lokalny CAP, poprawki runtime i filtrów; IAS/WRITE nadal BLOCKED](journal/2026-11-08-cap-06-ui5-09.md).

- [Docelowa architektura bezpieczeństwa — dwa diagramy SVG, granice tożsamości i relacje SAP IAM/DCL](architecture/README.md).
- [Referencyjne źródła UI DCL poza eksportem abapGit — ostrzeżenie bezpieczeństwa](abap-dcl-reference/README.md).
- [ABAP-08 stan końcowy 2026-11-07 — Business Role, READ 4/9/6 i lista SAPUI5 z H01 LIVE VERIFIED](journal/2026-11-02-abap-08-ui5-08.md#kontynuacja-2026-11-07-2--business-role-utworzona-business-user-read-pass-sapui5-live-pass).
- [ABAP-08 kontynuacja 2026-11-07 — binding LIVE, DCL „bez roli” LIVE, Business Role do utworzenia](journal/2026-11-02-abap-08-ui5-08.md#kontynuacja-2026-11-07--logowanie-mcp-działa-business-role-nie-istnieje).
- [ABAP-08 + UI5-08 — integracja ABAP-07, regresja 178/69/13, bramka Business Role nadal BLOCKED](journal/2026-11-02-abap-08-ui5-08.md).
- [ABAP-07 + UI5-07 — IAM/readiness H01, timeout CAP i regresja 178/69/13](journal/2026-10-30-abap-07-ui5-07.md).

- [RAP UI IAM — co robią domeny, AUTH, RF/RT, aplikacja, katalog, rola i DCL](guides/RAP_UI_IAM_MODEL.md).

- [ABAP-06 + UI5-06 — audyt H01, regresja READ i blokery ADT/IAM](journal/2026-10-30-abap-06-ui5-06.md).
- [E2E-01 — runbook jednej syntetycznej faktury SAP → KSeF TEST → writeback → UI5 (nie wykonany)](guides/E2E_01_SAP_TO_KSEF_TEST.md).

- [Promocja java-cap-ksef do main i archiwum oryginalnego AInvoicer](journal/2026-10-29-main-promotion.md).

- [ABAP-05 + CAP-05.1 + UI5-05 — ponowiona regresja i inspekcja aktywnego H01](journal/2026-10-29-abap-05-real-rap-ui.md).
- [RAP UI LIVE — binding ADT, IAM, routing i macierz READ](guides/RAP_UI_LIVE_CONNECTION.md).

- [CAP-05 — audyt implementacji Claude Code, poprawki i macierz akceptacji](audits/2026-10-29-cap-05-implementation-review.md).
- [Dziennik CAP-05](journal/2026-10-29-cap-05-integration-review.md) · [ADR-0005](decisions/ADR-0005-rap-business-status-cap-integration-status.md) · [RAP UI: patch i blokery ADT/IAM](patches/cap05-rap-ui/README.md).
- [Agent A — oficjalny SDK KSeF i auth TEST w CAP](journal/2026-10-29-agent-a-ksef-sdk.md).
- [Integracja KSeF Java SDK](guides/KSEF_JAVA_SDK_INTEGRATION.md) i [runbook TEST](guides/KSEF_TEST_RUNBOOK.md).
- [ADR-0004 — SDK MF, checkpointy i rekoncyliacja](decisions/ADR-0004-ksef-test-sdk-workflow.md).

- [STATUS — aktualny stan i najbliższe kroki](STATUS.md).
- [ROADMAP — plan rozwoju](ROADMAP.md).
- [Dziennik: CAP-04C — eksport SAP i trwały writeback CAP → SAP](journal/2026-10-28-cap-04c-sap-result-writeback.md).
- [ADR-0003 — persistent outbox, niezmienny payload i rozstrzygnięcia zapisu](decisions/ADR-0003-cap-sap-result-writeback.md) (aktualizacja hardening 2026-10-29).
- [Dziennik: Agent C — kokpit SAPUI5 faktur KSeF](journal/2026-10-29-agent-c-ui5.md) · [Przewodnik: SAPUI5 KSeF Cockpit](guides/SAPUI5_KSEF_COCKPIT.md).
- [Dziennik: Agent B — komponent FA(3): generator, XSD, snapshot](journal/2026-10-29-agent-b-fa3.md).
- [Przewodnik: generowanie i walidacja XML FA(3)](guides/FA3_XML_GENERATION.md).
- [Dziennik: CAP-04 Hardening — claim/finalize, recovery, integralność](journal/2026-10-29-cap-04-hardening.md).
- [Kontynuacja: Hardening Fix 2 i CAP-04D readiness — CAS resume, odmowa CRUD, timeout/lease](journal/2026-10-29-cap-04-hardening.md#kontynuacja--hardening-fix-2-i-cap-04d-readiness).
- [Szkic testu SAP CBA poza zakresem — BLOCKED / NOT VERIFIED](patches/cap-04-cba-scope-test.md).
- [Przewodnik: dostęp zapisu communication user, polityka DCL (propozycja), integralność XML](guides/SAP_WRITE_ACCESS_AND_READ_POLICY.md).
- [Dziennik: CAP-04B — kontrakt zapisu wyniku KSeF w SAP RAP](journal/2026-10-28-cap-04b-rap-write-contract.md).
- [ADR-0002 — statyczna akcja z blokowaną akcją instancyjną, CAS i autoryzacja](decisions/ADR-0002-sap-ksef-result-write-contract.md).
- [Dziennik: CAP-03 — integracja SAP H01](journal/2026-10-27-cap-03-sap-remote-integration.md).
- [Dziennik: bootstrap CAP Java i utworzenie bazy wiedzy](journal/2026-10-23-cap-java-bootstrap.md).
- [Dziennik: weryfikacja Phase 1 CAP Java Bootstrap](journal/2026-10-24-cap-java-bootstrap-verification.md).
- [Dziennik: minimalny model CDS KsefOperations](journal/2026-10-24-ksef-operations-model.md).
- [Dziennik: KsefService, OData V4 i lokalny CRUD](journal/2026-10-24-ksef-service-definition.md).
- [Dziennik: walidacja i autoryzacja KsefOperations](journal/2026-10-24-ksef-operations-validation-authorization.md).
- [Dziennik: Phase 2.3 — model KsefInvoices i relacja z operacjami](journal/2026-10-24-ksef-invoices-model.md).
- [Dziennik: lokalna konsola H2 w przeglądarce](journal/2026-10-24-h2-console.md).
- [Dziennik: pierwszy eksport abapGit i model RAP faktur](journal/2026-10-24-abapgit-rap-initial-export.md).
- [Dziennik: RAP Composition Header–Items–References](journal/2026-10-24-abap-rap-composition.md).
- [Dziennik: próba tworzenia obiektów przez ABAP FS](journal/2026-10-25-abapfs-create-probe.md).
- [Dziennik: ABAP FS v5 — aktualizacja instrukcji i wyniki testu akceptacyjnego](journal/2026-10-26-abapfs-v5-instructions.md).
- [Dziennik: ABAP-04 — seed danych testowych przez EML](journal/2026-10-26-abap-04-test-data-seed.md).
- [Przewodnik: workflow abapGit w AInvoicer](guides/ABAPGIT_WORKFLOW.md).
- [Przewodnik: model ABAP RAP faktur](guides/ABAP_RAP_INVOICE_MODEL.md).
- [Dziennik: Project Skills — zakresy, wywołania i wyniki walidacji](journal/2026-10-23-project-skills.md).
- [ADR-0001 — wybór CAP Java i integracji SDK KSeF](decisions/ADR-0001-cap-java-ksef-architecture.md).
- [Szablon wpisu dziennika](templates/JOURNAL_TEMPLATE.md).

Ten spis jest indeksem dzienników i decyzji. Przy dodaniu nowego wpisu dodaj tutaj link; aktualny stan utrzymuj w STATUS, a historię w dzienniku.

## Docelowa architektura

```text
SAPUI5 / Fiori ── OData V4 ──► RAP UI (lista, szczegóły, wynik biznesowy; BLOCKED)
        └─────── OData V4 ──► CAP Java + Spring Boot (akcje/monitoring, jedna JVM)
                               ├── CDS / Event Handlers / proces KSeF
                               ├── H2 lokalnie → SAP HANA Cloud docelowo
                               ├── Remote Service / Destination / OAuth2
                               │     └── ABAP Environment / RAP OData V4
                               └── oficjalne Java SDK MF → KSeF API 2.0

SAP BTP Cloud Foundry: docelowy hosting backendu CAP
Node.js / Python: istniejące niezależne funkcje AI
```

To architektura docelowa. Stan wdrożenia opisuje [STATUS](STATUS.md), a uzasadnienie i kompromisy — [ADR-0001](decisions/ADR-0001-cap-java-ksef-architecture.md).

ABAP Environment utrzymuje w tym scenariuszu symulacyjne dane faktur, a CAP ma być właścicielem operacji KSeF, statusów, korelacji i audytu. Nie zakładamy standardowych tabel S/4HANA ani bezpośredniego SQL do bazy ABAP; CAP ma korzystać z autoryzowanego API. Java SDK KSeF działa docelowo jako biblioteka w backendzie. XML FA(3) i UPO wymagają świadomie wybranego trwałego magazynu; lokalny dysk Cloud Foundry nie jest takim magazynem.

## Mapa repozytorium

| Katalog | Rola |
| --- | --- |
| `abap/` | CAP-04B wyeksportowano z H01 w `525e48d`: akcja `ApplyKsefResult`, readonly pól, autoryzacja, testy/prototypy i metadata. Bez HTTP WRITE i bez nadania prawa. Root DCL `where TRUE` nie zapewnia izolacji odczytu. Pominięto zastane zmiany spoza zakresu. |
| `sapui5/ainvoicer/` | Istniejący frontend SAPUI5 |
| `node/`, `python/` | Istniejące funkcje AI |
| `cap-java/` | CAP Java + Spring Boot; READ H01 LIVE PASS, writeback lokalnie przetestowany i domyślnie OFF. `feature/ksef-auth`: SDK MF, LIVE auth i jedna syntetyczna faktura TEST z numerem/UPO; generator B zintegrowany lokalnie, 141 testów PASS. Pełny H01 → KSeF nadal oczekuje. |
| `docs/` | Wspólna baza wiedzy dla ludzi i agentów |

## Zasady dokumentowania

1. Reguły nadrzędne są w `AGENTS.md` w katalogu głównym repozytorium. `CLAUDE.md` odsyła Claude Code do tych samych reguł i notatek.
2. Na początku sesji przeczytaj STATUS, ROADMAP, najnowszy dziennik oraz odpowiednie ADR. Porównaj je z Git i kodem; rozbieżności wymagają weryfikacji i korekty dokumentacji.
3. Po implementacji aktualizuj STATUS i wpis dotyczący zadania, a ROADMAP wtedy, gdy zmienia się postęp lub plan. ADR dodawaj dla istotnych decyzji architektonicznych.
4. Zapisuj fakty i dowody: polecenie, katalog, wersje narzędzi, wynik i zakres testu. Oddzielaj relację użytkownika, inspekcję kodu, mock, lokalny test i integrację live. Udany build nie potwierdza uruchomienia, CRUD ani E2E.
5. Korzystaj z jednego wpisu `YYYY-MM-DD-short-topic.md` dla danej sesji/zadania. Zapisuj problemy i następny krok; nie twórz osobnych notatek dla drobnych operacji.
6. Używaj UTF-8, standardowych linków względnych Markdown, YAML frontmatter i tagów. W treści stosuj polski; nazwy technologii, API i wymagane nagłówki mogą pozostać angielskie.
7. Nie zapisuj sekretów, tokenów, danych wrażliwych ani kompletnych poufnych odpowiedzi API. Dokumentację aktualizuj razem z kodem.

## Obsidian, VS Code i GitHub

W Obsidian wybierz **Open folder as vault / Otwórz folder jako skarbiec**, następnie **Browse / Przeglądaj** i wskaż `D:\AInvoicer\docs` (lub `docs/` w swoim klonie). Otwórz `README.md` jako stronę startową.

To te same pliki, które edytujesz w VS Code i przeglądasz w GitHub. Wystarcza istniejące repozytorium Git; nie potrzeba osobnego repozytorium, płatnej synchronizacji ani community plugins. Lokalne ustawienia Obsidian w `docs/.obsidian/` są ignorowane przez Git. Frontmatter i tagi są opcjonalnymi metadanymi dla czytelnika, bez zależności od pluginów.

## Dokumentacja źródłowa

- [SAP CAP Java](https://cap.cloud.sap/docs/java/) — modele, serwisy i integracja ze Spring Boot.
- [CAP Java — H2 for Development](https://cap.cloud.sap/docs/guides/databases/h2) — H2 in-memory do lokalnego developmentu i testów.
- [CAP Java Remote Services](https://cap.cloud.sap/docs/java/cqn-services/remote-services) — konsumowanie API OData przez CQN i Destinations.
- [CAP Java Event Queues](https://cap.cloud.sap/docs/java/event-queues) — punkt odniesienia dla trwałego przetwarzania po commit.
- [Oficjalne Java SDK MF](https://github.com/CIRFMF/ksef-client-java) — biblioteka `ksef-client` i osobna aplikacja demonstracyjna.
- [Przewodnik KSeF API 2.0](https://github.com/CIRFMF/ksef-api).

Przed implementacją sprawdzaj wersje oraz faktyczny kontrakt API. Link do dokumentacji nie jest dowodem integracji w tym repozytorium.
