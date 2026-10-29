---
title: "AInvoicer — Project Home"
date: 2026-10-23
type: home
status: aktywny
tags: [ainvoicer, dokumentacja]
---

# AInvoicer — baza wiedzy projektu

AInvoicer to aplikacja SAP Fiori do faktur zakupowych i sprzedażowych, rozwijana w kierunku integracji z KSeF 2.0. Repozytorium zachowuje istniejące moduły ABAP, SAPUI5, Node.js i Python; nowy backend powstaje w `cap-java/` na gałęzi `java-cap-ksef`.

## Od czego zacząć

- [Agent A — oficjalny SDK KSeF i auth TEST w CAP](journal/2026-10-29-agent-a-ksef-sdk.md).
- [Integracja KSeF Java SDK](guides/KSEF_JAVA_SDK_INTEGRATION.md) i [runbook TEST](guides/KSEF_TEST_RUNBOOK.md).
- [ADR-0004 — SDK MF, checkpointy i rekoncyliacja](decisions/ADR-0004-ksef-test-sdk-workflow.md).

- [STATUS — aktualny stan i najbliższe kroki](STATUS.md).
- [ROADMAP — plan rozwoju](ROADMAP.md).
- [Dziennik: CAP-04C — eksport SAP i trwały writeback CAP → SAP](journal/2026-10-28-cap-04c-sap-result-writeback.md).
- [ADR-0003 — persistent outbox, niezmienny payload i rozstrzygnięcia zapisu](decisions/ADR-0003-cap-sap-result-writeback.md) (aktualizacja hardening 2026-10-29).
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
SAPUI5 / Fiori ── OData V4 ──► CAP Java + Spring Boot (jedna JVM)
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
