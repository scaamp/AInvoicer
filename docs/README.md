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

- [STATUS — aktualny stan i najbliższe kroki](STATUS.md).
- [ROADMAP — plan rozwoju](ROADMAP.md).
- [Dziennik: bootstrap CAP Java i utworzenie bazy wiedzy](journal/2026-10-23-cap-java-bootstrap.md).
- [Dziennik: weryfikacja Phase 1 CAP Java Bootstrap](journal/2026-10-24-cap-java-bootstrap-verification.md).
- [Dziennik: minimalny model CDS KsefOperations](journal/2026-10-24-ksef-operations-model.md).
- [Dziennik: KsefService, OData V4 i lokalny CRUD](journal/2026-10-24-ksef-service-definition.md).
- [Dziennik: walidacja i autoryzacja KsefOperations](journal/2026-10-24-ksef-operations-validation-authorization.md).
- [Dziennik: Phase 2.3 — model KsefInvoices i relacja z operacjami](journal/2026-10-24-ksef-invoices-model.md).
- [Dziennik: lokalna konsola H2 w przeglądarce](journal/2026-10-24-h2-console.md).
- [Dziennik: pierwszy eksport abapGit i model RAP faktur](journal/2026-10-24-abapgit-rap-initial-export.md).
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
| `abap/` | Lokalny projekt Eclipse i eksport abapGit w `abap/src/`; symulacyjny model `ZAINV_INV_H/I/REF`, RAP obecnie tylko dla nagłówka. Nie jest to dowód stanu runtime SAP. |
| `sapui5/ainvoicer/` | Istniejący frontend SAPUI5 |
| `node/`, `python/` | Istniejące funkcje AI |
| `cap-java/` | Rozpoczęty backend CAP Java + Spring Boot |
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
