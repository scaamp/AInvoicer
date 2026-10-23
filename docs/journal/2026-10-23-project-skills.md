---
title: "Project Skills dla Codex i Claude Code"
date: 2026-10-23
type: journal
status: zakonczony
tags: [ainvoicer, journal, skills, abap, rap, cap-java, ksef]
---

# Project Skills dla Codex i Claude Code

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [ADR-0001](../decisions/ADR-0001-cap-java-ksef-architecture.md)

## Date

2026-10-23 (Europe/Warsaw). Zakończono tworzenie plików i wskazane kontrole; status nie oznacza zaliczenia automatycznej aktywacji ani integracji SAP/KSeF.

## Branch

`java-cap-ksef`, potwierdzony przez `git branch --show-current`.

## Goal

Przygotować trzy małe Skills dla AInvoicer: kanoniczne instrukcje w `.agents/skills/`, krótkie odwołania z YAML dla Claude Code, sekcje `Project Skills` w instrukcjach repo i aktualizację bazy wiedzy. Kryteria: poprawny YAML, jednoznaczne zakresy i nazwy, działające odwołania, zgodność z architekturą i rzetelne wyniki wykrywania. Bez skryptów zarządzających, symlinków, instalowania Skills, zmian logiki aplikacji, deploymentu, wysyłki faktur, commit/push.

## Initial State

- Czyste drzewo robocze na początku zadania; `AGENTS.md`, `CLAUDE.md`, STATUS, ROADMAP, dziennik bootstrapu i ADR-0001 przeczytane.
- `.agents/` i `.claude/` z projektowymi Skills nie istniały. Wykonano wyszukiwanie `SKILL.md` z uwzględnieniem ukrytych katalogów i pominięciem zależności/buildów.
- `cap-java/` ma POM parent/srv, `Application.java` i konfigurację H2, bez własnych modeli CDS, usług, handlerów, testów, Remote Service i SDK KSeF. POM: JDK 25, CAP Java 5.2.0, Spring Boot 4.1.1, Maven minimum 3.9.14. To inspekcja plików; nie nowy build.
- `abap/` ma rzeczywisty kod behavior oraz skrótowe pliki definicji CDS/service/binding z wielokropkami. URI RAP jest zapisane w manifeście UI5, lecz nie weryfikowano go w SAP. Nie traktowano tych plików jako aktualnego kontraktu live.
- Inspekcja istniejących modułów nie wykazała implementacji nowego SDK/workflow KSeF na tej gałęzi. Nie pobierano eksperymentu z osobnej gałęzi.
- W udostępnionym katalogu narzędzi tej sesji nie znaleziono MCP ABAP FS; nie wykonywano odczytów ani zmian obiektów SAP.

## Implementation

1. Zastosowano systemowe Skills `skill-creator` oraz `openai-docs`. Sprawdzono oficjalne zasady lokalizacji, YAML, wywołań i wykrywania w Codex oraz dokumentację Claude Code.
2. Przeczytano publiczne materiały ABAP FS o MCP, Clean ABAP, Code Writing Process, SAP Research, ATC i składni. Opracowano własny workflow oparty o faktycznie dostępne możliwości; nie kopiowano instrukcji autorów ani ich założeń o klasycznym SAP do ABAP Cloud.
3. Sprawdzono dokumentację SAP CAP Java, Event Handlers, Remote Services i Event Queues oraz repozytoria MF. Swagger TEST nie zwrócił treści do czytnika; nie potwierdzano wersji wdrożonego API. Skills wymagają ponownej kontroli wersji i kontraktów przed implementacją.
4. Utworzono trzy instrukcje kanoniczne i trzy krótkie odwołania Claude. Opisy YAML są zgodne w parze danego Skill; pełna treść pozostaje tylko w `.agents/skills/`. Zachowano domyślny automatyczny dobór bez dodatkowych plików konfiguracyjnych.
5. Dopisano sekcje `Project Skills` oraz zaktualizowano STATUS, ROADMAP i indeks dzienników. Nie dodawano ADR: architektura i właściciele danych nie zmienili się.
6. Wykonano walidację statyczną i odczyt `skills/list` przez Codex App Server, bez tworzenia wątku modelu i bez `turn/start`.

## Changed Files

Utworzone pliki (oba katalogi zawierają zwykłe pliki, bez symlinków):

```text
.agents/skills/
  ainvoicer-abap-rap/SKILL.md
  ainvoicer-cap-java/SKILL.md
  ainvoicer-ksef/SKILL.md
.claude/skills/
  ainvoicer-abap-rap/SKILL.md
  ainvoicer-cap-java/SKILL.md
  ainvoicer-ksef/SKILL.md
docs/journal/2026-10-23-project-skills.md
```

Zmodyfikowane: `AGENTS.md`, `CLAUDE.md`, `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/README.md`. Brak zmian źródeł i konfiguracji aplikacji w `abap/`, `cap-java/`, `sapui5/`, `node/`, `python/`. Tymczasowe kopie do autoryzowanego przeniesienia plików przechowywano poza repozytorium.

## Commands Executed

Z `D:\AInvoicer`: `git branch --show-current`, `git status --short` / `--porcelain=v1`, `git ls-files`, `git diff --stat` / `--numstat`, `git diff --cached --numstat`, `rg --files --hidden` i wyszukiwania tematyczne, odczyty `Get-Content -Encoding utf8` oraz listowanie `Get-ChildItem`. Sprawdzono launchery przez `Get-Command` i dokumentację poleceń przez `codex --help`, `codex app-server --help`, `claude --help`.

Narzędzia: `codex --version` → **0.164.0**, `claude --version` → **2.1.309**, Python **3.11.9**, PyYAML **6.0.3**. W tej sesji nie uruchamiano `java`, Maven ani builda CAP; wersje backendu powyżej pochodzą z POM.

Sześć plików sprawdzono systemowym `skill-creator/scripts/quick_validate.py` uruchamianym przez `python -X utf8` dla każdego katalogu Skill, a także jednorazową walidacją YAML, linków, nazw i kodowania w Pythonie. Nie dodano skryptu do repo.

Wykrywanie Codex: natywny `codex.exe app-server --listen stdio://`, handshake `initialize` → `initialized`, następnie `skills/list` z `cwds: ["D:\\AInvoicer"]` i `forceReload: true`. Po odczycie zakończono proces testowy. Nie wywoływano `thread/start`, `turn/start` ani modelu.

Końcowe kontrole: `git diff --check`, `git diff --cached --check`, kontrola linków/YAML oraz porównanie ścieżek zmian ze zleconym zakresem. Nie wykonywano `git add`, commit, push, instalacji ani zmian konfiguracji klienta.

## Tests & Results

| Sprawdzenie | Wynik i zakres |
| --- | --- |
| YAML i konwencje sześciu SKILL.md | PASS: walidator `skill-creator`, wymagane `name`/`description`, nazwy zgodne z katalogami |
| Nazwy i opisy | PASS inspekcji: trzy unikalne nazwy w każdym kliencie, to samo `name`/`description` w odpowiedniej parze Codex/Claude, jawne zastosowania i wyłączenia |
| Odwołania Claude i linki | PASS po poprawieniu poziomu ścieżek: 78 lokalnych linków w instrukcjach, Skills i dokumentacji ma istniejące cele; poprawny YAML w 13 plikach (7 notatek i 6 Skills) |
| UTF-8 / brak symlinków | PASS końcowej kontroli; wszystkie sześć plików zgodne z przygotowaną treścią, z zachowaniem polskich znaków |
| Spójność instrukcji | PASS ręcznej inspekcji: zachowana architektura jednej JVM, granice danych SAP/CAP, brak założeń o uprawnieniach MCP i brak domyślnej wysyłki KSeF |
| Codex discovery | PASS rzeczywistego `skills/list`: wszystkie trzy nazwy, prawidłowe ścieżki `.agents/skills/`, `enabled: true`, `scope: repo`, zero błędów ładowania |
| Codex interaktywny `/skills` | NIE WYKONANO; użyto odczytu App Server, nie selektora TUI |
| Claude Code discovery / odczyt kanonicznej treści w sesji | NIE WYKONANO; wersja CLI jest potwierdzona, nie otwierano interaktywnej sesji |
| Automatyczny dobór przez model | NIE WYKONANO w Codex ani Claude; poniższa tabela jest ręczną oceną metadanych |
| Build / testy aplikacji / live SAP/KSeF | NIE WYKONANO; zadanie dokumentacyjne, bez wysyłki faktur i bez zmian kodu aplikacji |
| Whitespace i zakres zmian | PASS końcowych kontroli Git; istniejąca treść AGENTS/CLAUDE zachowana, zmiany ograniczone do instrukcji i dokumentacji |

### Cztery scenariusze doboru

Każdy przykład oceniono względem samych opisów YAML oraz granic kanonicznych instrukcji. To test ręczny projektu doboru, nie dowód automatycznej aktywacji przez klienta/model.

| Zadanie | Dobór z opisów | Wynik oceny |
| --- | --- | --- |
| „Zmień CDS i wystaw dodatkowe pola przez RAP OData” | `ainvoicer-abap-rap`; kontekst RAP rozstrzyga niejednoznaczne „CDS” | PASS; CAP/KSeF nie są potrzebne bez rozszerzenia zakresu |
| „Utwórz encję CAP i Event Handler dla operacji faktury” | `ainvoicer-cap-java` | PASS; samo słowo „faktura” nie włącza protokołu KSeF |
| „Dodaj uwierzytelnianie KSeF i wysyłkę FA(3)” | `ainvoicer-ksef`; dodatkowo CAP przy zmianach modelu/handlerów/persystencji | PASS; implementacja z mockami nie upoważnia do live send |
| „Podłącz CAP Java do istniejącego RAP OData” | `ainvoicer-cap-java`; opcjonalnie ABAP do analizy rzeczywistego serwisu SAP | PASS; sam zdalny odczyt nie wymaga zmiany RAP ani Skill KSeF |

Negatywny przykład ręczny: zmiana samego widoku UI5 lub OCR Python nie wymaga żadnego z tych trzech Skills. Nie wdrożono dodatkowego routera.

### Jawne wywołania i odświeżenie

Przykłady do wpisania w prompt klienta, nie do bezpośredniego wykonania przez PowerShell:

| Skill | Codex CLI / IDE | Claude Code |
| --- | --- | --- |
| ABAP/RAP | `$ainvoicer-abap-rap Zmień CDS i wystaw dodatkowe pola przez RAP OData` | `/ainvoicer-abap-rap Zmień CDS i wystaw dodatkowe pola przez RAP OData` |
| CAP Java | `$ainvoicer-cap-java Utwórz encję CAP i Event Handler operacji faktury` | `/ainvoicer-cap-java Utwórz encję CAP i Event Handler operacji faktury` |
| KSeF | `$ainvoicer-ksef Dodaj auth i wysyłkę FA(3) z mockami; bez wysyłki live` | `/ainvoicer-ksef Dodaj auth i wysyłkę FA(3) z mockami; bez wysyłki live` |

W Codex CLI/IDE otwórz `/skills` lub wybór po `$`. Zgodnie z [dokumentacją Codex](https://developers.openai.com/codex/skills) zmiany są wykrywane automatycznie; restart jest krokiem awaryjnym, jeśli lista się nie odświeży. W tym teście wymuszono odczyt przez `forceReload`, nie potwierdzano odświeżenia bieżącego selektora. W aplikacji ChatGPT sposób wyboru może być inny niż `$` w CLI/IDE.

W Claude Code katalog `.claude/skills/` powstał dopiero podczas tej sesji. [Dokumentacja Claude](https://code.claude.com/docs/en/skills#edit-a-skill-during-a-session) zaleca `/reload-skills` dla nowego katalogu głównego Skills; w razie braku komend otwórz nową sesję. Restart całego systemu ani ponowna instalacja nie są potrzebne do samego utworzenia plików. To instrukcja na podstawie dokumentacji, nie wynik testu tego klienta.

## Problems Encountered

- Sandbox odmówił bezpośredniego zapisu `.agents/skills/`. Przygotowano konkretne pliki tymczasowe, zwalidowano je i przeniesiono do `.agents/skills/` oraz `.claude/skills/` po zgodzie na wymagany zapis poza sandboxem.
- Pierwsze przekazanie JSON w argumencie Pythona nie przeszło cytowania Windows; użyto stdin. Następnie domyślne kodowanie potoku PowerShell zastąpiło polskie znaki. Skorygowano transport do ASCII z sekwencjami Unicode JSON i ponownie zapisano UTF-8. Końcowa walidacja porównuje pełną treść, nie tylko poprawność YAML.
- Pierwsza pełna kontrola linków wykryła nadmiarowy poziom `../` w linkach z katalogów Skills do korzenia repozytorium. Poprawiono te ścieżki we wszystkich sześciu plikach i ponowiono walidację z wynikiem PASS. Linki między sąsiednimi Skills pozostały bez zmian.
- Pierwsza inicjalizacja App Server przez launcher CLI w sandboxie przekroczyła limit 25 s; przyczyna nie została potwierdzona. Natywny proces poza sandboxem po zgodzie poprawnie obsłużył handshake i listę Skills.
- Stan Git zmienił się w trakcie sesji: sześć nowych plików najpierw pojawiło się w indeksie, a następnie HEAD wskazał `077f3a0` (`feat: Add SKILL.md files for ainvoicer-abap-rap, ainvoicer-cap-java, and ainvoicer-ksef`). Agent nie uruchamiał `git add`, commit ani push; nie cofał tej zewnętrznej operacji. Poprawki ścieżek pozostawiono w drzewie roboczym razem z aktualizacją dokumentacji. Końcowy indeks bez różnic względem HEAD.

## Decisions Made

Jedno źródło instrukcji w `.agents/skills/`; odwołania Claude powtarzają tylko metadane potrzebne do doboru i polecenie odczytu. Bez symlinków, skryptów, dodatkowego frameworka, metadanych UI i konfiguracji MCP. Każdy Skill może zostać dobrany automatycznie, lecz jego uruchomienie nie upoważnia do operacji zewnętrznych. Zachowano [ADR-0001](../decisions/ADR-0001-cap-java-ksef-architecture.md), bez nowej decyzji architektonicznej.

## Current State

Pliki i dokumentacja są gotowe; Codex wykrywa trzy Skills. Ręczna ocena czterech scenariuszy zakończona. Automatyczna aktywacja, Claude runtime i workflow ABAP/CAP/KSeF według nowych Skills pozostają nieprzetestowane. Ostatni wynik builda CAP z poprzedniego dziennika nie został zmieniony ani potwierdzony ponownie.

## Next Steps

1. W Claude Code z katalogu repo uruchomić `/reload-skills`, sprawdzić `/ainvoicer-*` i odczyt kanonicznych instrukcji; w Codex sprawdzić interaktywną listę `/skills`.
2. W kontrolowanej sesji sprawdzić automatyczny dobór na czterech przykładach, prosząc wyłącznie o wskazanie Skill i plan, bez zmian SAP i wysyłki KSeF. Zapisać rzeczywiste obserwacje.
3. Wrócić do blokady builda CAP z [STATUS](../STATUS.md); dostęp ABAP FS i live SAP sprawdzać dopiero w odpowiednim zadaniu.

## Źródła

- [Tworzenie i wykrywanie Skills w Codex](https://developers.openai.com/codex/skills) oraz [App Server — Skills](https://learn.chatgpt.com/docs/app-server#skills).
- [Claude Code — Skills](https://code.claude.com/docs/en/skills).
- [ABAP FS — MCP](https://marcellourbani.github.io/vscode_abap_remote_fs/mcp-server/), [Skills](https://marcellourbani.github.io/vscode_abap_remote_fs/ai/skills/), [ATC](https://marcellourbani.github.io/vscode_abap_remote_fs/code-quality/atc-analysis/) i [składnia](https://marcellourbani.github.io/vscode_abap_remote_fs/code-quality/syntax-validation/).
- [CAP Java](https://cap.cloud.sap/docs/java/), [Event Handlers](https://cap.cloud.sap/docs/java/event-handlers/), [Remote Services](https://cap.cloud.sap/docs/java/cqn-services/remote-services) i [Event Queues](https://cap.cloud.sap/docs/java/event-queues).
- [Oficjalny SDK MF](https://github.com/CIRFMF/ksef-client-java), [przewodnik KSeF API](https://github.com/CIRFMF/ksef-api), [Swagger TEST](https://api-test.ksef.mf.gov.pl/docs/v2/index.html).
