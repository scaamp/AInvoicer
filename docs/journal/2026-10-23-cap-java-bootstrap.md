---
title: "CAP Java bootstrap i wspólna baza wiedzy"
date: 2026-10-23
type: journal
status: zakonczony
tags: [ainvoicer, journal, cap-java, dokumentacja]
---

# CAP Java bootstrap i wspólna baza wiedzy

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [ADR-0001](../decisions/ADR-0001-cap-java-ksef-architecture.md)

## Date

2026-10-23 (Europe/Warsaw). Status `zakonczony` dotyczy sesji dokumentacyjnej; bootstrap CAP pozostaje w toku.

## Branch

`java-cap-ksef` — potwierdzony przez `git branch --show-current`.

## Goal

Utworzyć lekką bazę wiedzy Markdown współdzieloną przez Codex, Claude Code, VS Code, GitHub i Obsidian. Zapisać rzeczywisty stan CAP, decyzję architektoniczną i procedurę kontynuacji pracy. Zakres: dokumentacja, instrukcje agentów i ignorowanie ustawień Obsidian; bez implementacji nowych funkcji backendu.

## Initial State

Kontekst wcześniejszych czynności przekazany przez użytkownika (nie odtwarzano tych poleceń w tej sesji):

- Utworzono `AGENTS.md` i `CLAUDE.md`; rozpoczęto nowy backend.
- Wykonano `cds init cap-java --java`.
- Pierwsze `mvn clean verify` zatrzymał Maven Enforcer: JDK 21 zamiast wymaganego JDK 25.
- Następnie użytkownik zgłosił udane pełne `mvn clean verify` i pobranie zależności Maven.

Własna inspekcja na początku sesji:

- Git: zmieniony `.gitignore`, nieśledzony `cap-java/`; brak `docs/`. Zachowano istniejącą pracę.
- Istnieją `abap/`, `sapui5/ainvoicer/`, `node/`, `python/` i oba pliki instrukcji agentów.
- CAP zawiera parent POM i moduł `srv`, `Application.java`, `application.yaml`, `.cdsrc.json`, pliki npm i własny `.gitignore`. `db/` nie ma własnego modelu `.cds`; brak własnych usług, handlerów i testów.
- Wersje z POM: JDK 25, CAP Java 5.2.0, Spring Boot 4.1.1; Enforcer wymaga Maven co najmniej 3.9.14. Zależności runtime obejmują H2 i adapter OData V4.
- H2 wskazane w profilu `default`; `cds.data-source.auto-config.enabled: false`. Nie wykonano testu połączenia ani CRUD.
- Istniały wygenerowane artefakty wcześniejszego builda, w tym JAR i model outbox. To nie dowodzi implementacji własnego procesu KSeF.

## Implementation

1. Odczytano instrukcje, konfigurację CAP i strukturę istniejących modułów. Sprawdzono dokumentację SAP CAP Java/Remote Services i repozytorium SDK MF wskazane w Project Home.
2. Utworzono stronę główną, STATUS, ROADMAP, ADR-0001, szablon dziennika i ten wpis. Zastosowano UTF-8, YAML, tagi i względne linki Markdown.
3. Dopisano workflow do `AGENTS.md`, krótkie odsyłacze w `CLAUDE.md` i link do bazy wiedzy w głównym README. Skorygowano nieaktualną sugestię ponownego tworzenia szkieletu CAP w `CLAUDE.md`.
4. Dopisano `docs/.obsidian/` do istniejącego `.gitignore`. Nie instalowano Obsidian ani pluginów.
5. Wykonano trzy próby pełnego builda. Skorygowano wyłącznie środowisko uruchomionych procesów, bez edycji źródeł lub zależności CAP.

## Changed Files

Utworzone w tej sesji:

- `docs/README.md` — nawigacja, architektura docelowa, zasady i instrukcja Obsidian.
- `docs/STATUS.md` — krótki stan bieżący z dowodami i ograniczeniami.
- `docs/ROADMAP.md` — plan etapów, z trwałością i autoryzacją przed wysyłką.
- `docs/journal/2026-10-23-cap-java-bootstrap.md` — rzeczywista historia i wyniki tej sesji.
- `docs/decisions/ADR-0001-cap-java-ksef-architecture.md` — zapis przyjętej architektury i alternatyw.
- `docs/templates/JOURNAL_TEMPLATE.md` — szablon kolejnych sesji.

Zmodyfikowane: `AGENTS.md`, `CLAUDE.md`, `.gitignore`, `README.md` w katalogu głównym. Pliki źródłowe CAP i istniejących backendów/UI nie były edytowane; Maven/npm odtworzyły lokalne, ignorowane artefakty builda i zależności.

## Commands Executed

Z katalogu głównego repozytorium wykonano inspekcję: `git branch --show-current`, `git status --short` (także `--untracked-files=all`), `git diff`, `git ls-files`, `git log -1`, `rg --files`, odczyty `Get-Content` i listowanie plików `Get-ChildItem`. Sprawdzono dostępność narzędzi przez `Get-Command` oraz `java -version` i `mvn -version`; początkowo nie były dostępne w PATH procesu.

Trzy razy uruchomiono `mvn clean verify` z `D:\AInvoicer\cap-java`. Konfiguracja procesu PowerShell użyta dla JDK/Maven:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot'
$env:Path = $env:JAVA_HOME + '\bin;C:\Users\jkupi\AppData\Local\Programs\Apache\apache-maven-3.9.16\bin;' + $env:Path
```

Pierwsza próba w sandboxie, dwie następne poza nim po uzyskaniu zgody. W ostatniej próbie przed `mvn clean verify` ustawiono dodatkowo `$env:DEBUG = $null` tylko w uruchamianym procesie. Nie zmieniano PATH ani JAVA_HOME na poziomie użytkownika/systemu.

Kontrola dokumentacji: jednorazowa walidacja Python/PyYAML (frontmatter i względne linki, bez dodawania skryptu do repozytorium), `git diff --check` oraz `git check-ignore docs/.obsidian/workspace.json docs/.obsidian/app.json`. Wyniki zapisano poniżej.

## Tests & Results

| Sprawdzenie | Wynik i zakres |
| --- | --- |
| Wcześniejszy build po przejściu z JDK 21 na 25 | Sukces według użytkownika; zachowany jako kontekst historyczny |
| Próba 1: `mvn clean verify` w sandboxie | FAIL: `spawn EPERM` w `cds version`; Enforcer PASS |
| Próba 2: to samo poza sandboxem | FAIL: plugin zgłasza `Required @sap/cds-dk not installed, version information not available`, mimo obecności pakietu w wyjściu; tekst debug poprzedza JSON |
| Próba 3: poza sandboxem, bez `DEBUG` | Enforcer, budowanie CDS, generacja i kompilacja Javy PASS; pełny build FAIL przy tworzeniu JAR: `NoSuchFileException: .../srv/target/classes/application.yaml` |
| Faktyczne narzędzia próby 3 | JDK 25.0.4.1, Maven 3.9.16, Node 24.22.0 z cache pluginu, cds-dk 10.2.0 / cds 10.2.1 / compiler 7.2.1 |
| Testy Java | `No tests to run` i brak źródeł testów; brak potwierdzenia działania biznesowego |
| Spring Boot, H2, endpointy OData, CRUD | NIE WYKONANO; konfiguracja i kompilacja nie wystarczają do potwierdzenia działania |
| SAP Remote OData, SDK, KSeF TEST i UPO | NIE WYKONANO live; brak nowej wysyłki i brak deklaracji sukcesu E2E CAP |
| Linki Markdown i YAML | PASS: 6 notatek z poprawnym YAML; 45 lokalnych linków w dokumentacji i instrukcjach, w tym 30 wewnątrz Vault; wszystkie cele istnieją, żaden link z notatek nie wychodzi poza Vault |
| Ignorowanie Obsidian / whitespace | `git check-ignore` potwierdził oba przykładowe pliki; `git diff --check` bez błędów |

Kontrola integralności potwierdziła zachowanie całej dotychczasowej treści `AGENTS.md` i wpisów `.gitignore`, brak zmian źródeł `abap/`, `sapui5/`, `node/`, `python/`, `cap-java/` oraz brak śledzonych artefaktów `target/` i `node_modules/` w CAP. Nie dodano narzędzia do zarządzania notatkami.

## Problems Encountered

- Proces agenta nie odziedziczył dostępnych instalacji JDK/Maven w PATH. Odnaleziono JDK 25 przez konfigurację JAVA_HOME i Maven przez ścieżkę użytkownika; wskazano je w środowisku procesu.
- Sandbox blokował uruchamianie procesu potomnego przez narzędzia CDS. Ponowienie poza sandboxem usunęło `EPERM`.
- Ustawione `DEBUG` powoduje w zainstalowanym `cds-dk/bin/version.js` dodatkowy `console.debug` przed JSON-em. Wyłączenie go w procesie umożliwiło przejście odczytu wersji i kompilacji, bez zmian projektu.
- Następnie pakowanie JAR nie znalazło wygenerowanego `target/classes/application.yaml`. Przyczyna pozostaje nieustalona; możliwe równoległe operacje na `target/` są hipotezą do sprawdzenia, nie diagnozą.
- Podczas sesji zmienił się stan Git: końcowa inspekcja pokazała HEAD `b1d7fe2` (`feat: Initialize CAP Java project with Maven and Spring Boot`), a zastane pliki CAP i wcześniejsze rozszerzenie `.gitignore` były już śledzone. Agent nie wykonywał commit/push; nie cofano tej zmiany.

## Decisions Made

- [ADR-0001](../decisions/ADR-0001-cap-java-ksef-architecture.md) zapisuje istniejącą decyzję CAP Java + Spring Boot + SDK w jednej JVM, bez migracji istniejących modułów.
- STATUS jest migawką sprawdzaną z kodem; ROADMAP planem, a dziennik historią dowodów.
- Podstawy trwałości, idempotencji i autoryzacji są warunkiem pierwszej wysyłki, a nie dopiero końcowego hardeningu BTP.
- Naprawa pakowania JAR i implementacja CAP pozostają następnym zadaniem; ta sesja nie zmienia POM ani kodu backendu.

## Current State

Baza wiedzy utworzona; instrukcje agentów rozszerzone bez zastępowania zasad architektury i bezpieczeństwa. Pełny build zgłoszony wcześniej przez użytkownika nie został obecnie odtworzony, choć CDS i Java się kompilują. Bootstrap pozostaje w toku z blokadą pakowania JAR. Nie uruchamiano aplikacji, bazy ani własnego API. Aktualny plan: [STATUS](../STATUS.md) i [ROADMAP](../ROADMAP.md).

Obsidian Vault przygotowany strukturalnie jako `docs/`; nie otwierano go w aplikacji Obsidian. Nie wykonywano deploymentów, wysyłki faktur, commit ani push.

## Next Steps

1. Sprawdzić brak pliku podczas pakowania JAR (w tym ewentualne równoległe operacje na `target/`) i ponowić pełny build w kontrolowanym środowisku JDK 25, bez `DEBUG`.
2. Po udanym buildzie uruchomić lokalnie Spring Boot/CAP i potwierdzić połączenie H2; zapisać rzeczywiste wyniki.
3. Przygotować pierwszy model CDS procesu KSeF, usługę OData V4 i testy lokalnego CRUD/walidacji.
