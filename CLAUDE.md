# CLAUDE.md — AInvoicer

## Zasada nadrzędna

**Najpierw przeczytaj i stosuj [AGENTS.md](./AGENTS.md).**
To jedyne źródło prawdy o architekturze, granicach komponentów, bezpieczeństwie KSeF, testach i sposobie pracy. Ten plik zawiera jedynie dodatkowe wskazówki dla Claude Code. Jeżeli coś jest sprzeczne, traktuj AGENTS.md jako nadrzędne, o ile bieżące polecenie użytkownika nie ustala inaczej.

## Kontekst w 30 sekund

- Repo: `scaamp/AInvoicer`; gałąź rozwoju architektury: `java-cap-ksef`.
- Cel: produkcyjnie zaprojektowana aplikacja SAP Fiori do faktur zakupowych/sprzedażowych i KSeF 2.0; najpierw E2E TEST.
- **CAP Java + Spring Boot**: nowy centralny backend i właściciel procesu KSeF; **oficjalne Java SDK MF działa jako biblioteka w tej samej JVM**.
- **ABAP Cloud/RAP** (system H01 na BTP Trial, domyślny pakiet `ZAINVOICER` — szczegóły w AGENTS.md): utrzymuje symulacyjny model faktur `ZAINV_INV_H/I/REF`; obecny eksport RAP wystawia wyłącznie nagłówek przez service definition/binding `ZAPI_AINV_INV_H_O4`. Nie zakładaj tabel ani dokumentów S/4HANA.
- **SAPUI5**: istniejący frontend; docelowo OData V4 z CAP.
- **Node.js/Python**: zachowane dotychczasowe usługi AI.
- **Baza CAP**: lokalnie H2 / SQLite na potrzeby developmentu; docelowo HANA Cloud do operacji, statusów i audytu, bez kopiowania całej tabeli SAP.
- **Cloud Foundry**: docelowe środowisko uruchomieniowe CAP Java.

## Nawigacja po repo

```text
abap/                   # lokalny projekt Eclipse i eksport abapGit w abap/src/
sapui5/ainvoicer/       # istniejący frontend UI5
node/                   # istniejące usługi Node
python/                 # istniejące usługi Python
cap-java/               # rozpoczęty szkielet CAP Java; stan w docs/STATUS.md
```

Istnieje też potwierdzony eksperyment KSeF E2E na oddzielnej gałęzi `feature/ksef-java-e2e-runner`. Możesz go przeanalizować bez modyfikowania bieżącej gałęzi; nie kopiuj bez audytu kontrolerów demo, zwracania JWT ani globalnego stanu sesji.

## Sposób wykonywania zadań

1. Sprawdź branch i zmiany lokalne, a następnie stan konkretnych plików.
2. Przed implementacją przedstaw krótki plan, zakres plików i kryteria akceptacji.
3. Dziel zadanie na małe sprawdzalne kroki. W razie braku dostępu do SAP/BTP/KSeF wykonaj to, co można przetestować lokalnie, i zgłoś blokadę.
4. Kod CAP oprzyj o rzeczywiste wzorce SAP CAP Java (CDS, Event Handlers, CQN, Remote Services, Persistence, Spring Boot). Nie zastępuj CAP zestawem zwykłych kontrolerów REST.
5. Zanim użyjesz klasy/metody KSeF SDK lub nazwy encji OData, zweryfikuj aktualną sygnaturę / `$metadata`. Nie halucynuj kontraktów.
6. Nie przenoś trwałych operacji KSeF do synchronicznego requestu UI; zapisuj zadanie i wykonuj bezpieczne przetwarzanie po commit.
7. Wykonaj testy i podaj dowody; nie oznaczaj niczego jako `SUCCESS`, jeśli nie było prawdziwego sukcesu.
8. Po zadaniu podsumuj: co zmieniono, build/tests PASS/FAIL, ryzyka, kolejny krok.

## Bezpieczeństwo

- Nie wyświetlaj i nie commituj sekretów, JWT, kluczy prywatnych ani certyfikatów produkcyjnych.
- Nigdy nie wystawiaj KSeF JWT do UI5 ani osobnego publicznego endpointu dla tokenów.
- Żadnego PROD i żadnych niespodziewanych wysyłek/ponowień faktur.
- Nie wykonuj automatycznie nieodwracalnych operacji Git, deploymentów ani działań uruchamiających koszty.

## Preferowany pierwszy etap

Kontynuuj istniejący szkielet `cap-java/` według [docs/STATUS.md](docs/STATUS.md): zweryfikuj uruchomienie CAP Java + Spring Boot i H2, następnie przygotuj minimalny model CDS, OData V4, handler i testy. Dopiero później podłącz RAP Remote OData oraz oficjalny KSeF Java SDK.

## Project Knowledge Base & Documentation Workflow

Zasady abapGit i rozdział lokalnego checkoutu, projektu ADT oraz repozytorium SAP opisuje [kanoniczny przewodnik](docs/guides/ABAPGIT_WORKFLOW.md). Bieżący model oraz ograniczenia RAP są w [przewodniku modelu faktur](docs/guides/ABAP_RAP_INVOICE_MODEL.md). Traktuj eksport Git jako źródło do analizy, nie dowód aktywnego runtime SAP.

Na początku każdej sesji obowiązkowo przeczytaj [AGENTS.md](AGENTS.md), [docs/STATUS.md](docs/STATUS.md), [docs/ROADMAP.md](docs/ROADMAP.md) i najnowszy wpis w `docs/journal/`; indeks notatek jest w [docs/README.md](docs/README.md). Dla zadań architektonicznych sprawdź odpowiednie ADR. Zweryfikuj notatki względem Git i kodu.

Po implementacji aktualizuj Project Knowledge Base według workflow w `AGENTS.md`: STATUS, dziennik, w razie potrzeby ROADMAP i ADR. Zapisuj rzeczywiste wyniki oraz brakujące weryfikacje i następny krok. Nie duplikuj tutaj reguł architektury ani bezpieczeństwa; `AGENTS.md` pozostaje nadrzędnym źródłem zasad.

Komunikacja z użytkownikiem i komentarze edukacyjne: **po polsku**. Nazwy API, klas, metod, artefaktów i kontraktów: konsekwentnie po angielsku.

## Project Skills

Pełne instrukcje są w `.agents/skills/`; pliki `.claude/skills/` odsyłają do nich bez duplikowania treści. Przed pracą przeczytaj kanoniczny plik wybranego Skill. Nie uruchamiaj wszystkich Skills przy każdym zadaniu; zachowaj reguły i granice uprawnień z `AGENTS.md`.

| Skill / wywołanie Claude Code | Odwołanie Claude | Kanoniczny Skill | Kiedy używać |
| --- | --- | --- | --- |
| `/ainvoicer-abap-rap` | [SKILL.md](.claude/skills/ainvoicer-abap-rap/SKILL.md) | [SKILL.md](.agents/skills/ainvoicer-abap-rap/SKILL.md) | Zmiany ABAP Cloud/CDS/RAP i analiza rzeczywistych obiektów SAP przez MCP ABAP FS |
| `/ainvoicer-cap-java` | [SKILL.md](.claude/skills/ainvoicer-cap-java/SKILL.md) | [SKILL.md](.agents/skills/ainvoicer-cap-java/SKILL.md) | CDS CAP, handlers, CQN, H2/Maven i Remote OData w `cap-java/` |
| `/ainvoicer-ksef` | [SKILL.md](.claude/skills/ainvoicer-ksef/SKILL.md) | [SKILL.md](.agents/skills/ainvoicer-ksef/SKILL.md) | KSeF 2.0 przez SDK MF: auth, faktury, statusy/UPO, retry i rekoncyliacja |

Przykład: `/ainvoicer-abap-rap Zmień CDS i wystaw dodatkowe pola przez RAP OData`. Do protokołu KSeF dołącz CAP tylko gdy zmieniasz też tę warstwę. Do konsumpcji gotowego RAP wybierz CAP; ABAP dołącz, jeśli trzeba zbadać/zmienić obiekty SAP. Codex używa tych samych nazw z prefiksem `$`, np. `$ainvoicer-ksef`. Wykrywanie/aktywację i instrukcję odświeżenia opisuje [dziennik Skills](docs/journal/2026-10-23-project-skills.md).
