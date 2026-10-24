---
title: "Pierwszy eksport abapGit i model RAP faktur"
date: 2026-10-24
type: journal
status: zakonczony
tags: [ainvoicer, journal, abap, abapgit, rap, odata]
---

# Pierwszy eksport abapGit i model RAP faktur

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Workflow abapGit](../guides/ABAPGIT_WORKFLOW.md) · [Model RAP](../guides/ABAP_RAP_INVOICE_MODEL.md)

## Date

2026-10-24 (Europe/Warsaw).

## Branch

`java-cap-ksef` — potwierdzony przez `git status --short --branch` i `git branch --show-current`.

## Goal

Udokumentować zakończone przygotowanie Eclipse ADT/abapGit i pierwszy eksport, a stan modelu ABAP RAP ustalić na podstawie bieżącego eksportu Git. Zakres wyłącznie dokumentacyjny; bez zmian kodu ABAP, CAP, SAPUI5 ani `.abapgit.xml`.

## Initial State

- Drzewo robocze było czyste; branch lokalny `java-cap-ksef` wskazywał `origin/java-cap-ksef`.
- `HEAD` i `origin/java-cap-ksef` wskazywały `39ab869` (`abapGit init`, 2026-10-24). Poprzednie commity to `103fc8c` (`chore(abap): configure abapGit`) i `026c984` (`chore(abap): remove legacy backend`).
- Inspekcja `026c984` potwierdziła usunięcie poprzednich ścieżek katalogowych `abap/classes/`, `abap/core_data_services/`, `abap/dictionary/`, `abap/service_bindings/` i `abap/service_definitions/` (13 plików). Nie oznacza to usunięcia eksperymentalnej klasy `ZCL_KSEF_HTTP`, która została ponownie wyeksportowana w `abap/src/`.
- Użytkownik przekazał konfigurację Eclipse 2026-09, SAP ADT `H01_EN`, pakiet `ZAINVOICER`, repozytorium GitHub, branch, Folder Logic `FULL`, lokalne ścieżki oraz informację o wcześniejszych problemach instalacji Eclipse 2026-03/2026-06 i konflikcie ASM 9.9.1/9.10.1. Są to informacje z relacji użytkownika.
- Użytkownik podał, że pierwszy eksport zakończył się sukcesem i HTTPS uwierzytelniano PAT-em. Historia Git potwierdza commit `39ab869` na lokalnym branchu śledzącym `origin/java-cap-ksef`; nie odtwarzano logowania ani operacji eksportu w Eclipse.
- Własna inspekcja `.abapgit.xml` potwierdziła `MASTER_LANGUAGE=E`, `STARTING_FOLDER=/abap/src/`, `FOLDER_LOGIC=FULL`.
- Własna inspekcja `.project` potwierdziła zwykły projekt Eclipse `abap`; `git check-ignore` potwierdził, że `abap/.project` jest ignorowany przez Git.

## Implementation

- Zweryfikowano stat plików i nazwę commit `39ab869`; zmienia on 33 pliki łącznie: 32 pliki eksportu w `abap/src/` oraz aktualizację `.abapgit.xml`.
- Odczytano tabele, CDS, behavior, DCL, service definition i binding. Eksport zawiera `ZAINV_INV_H`, `ZAINV_INV_I`, `ZAINV_INV_REF`, root/projection nagłówka oraz `ZAPI_AINV_INV_H_O4`.
- Lista eksportowanych obiektów widoczna w plikach: tabele `ZAINV_INV_H`, `ZAINV_INV_I`, `ZAINV_INV_REF`; CDS `ZR_AINV_INV_H`, `ZC_AINV_INV_H`; behavior definitions/projection behavior tych CDS; role DCL `ZR_AINV_INV_H`, `ZC_AINV_INV_H`; klasy `ZBP_R_AINV_INV_H`, `ZBP_C_AINV_INV_H`; service definition i binding `ZAPI_AINV_INV_H_O4`; klasa `ZCL_KSEF_HTTP`; pakiet `ZAINVOICER`. Commit zawiera także pliki sidecar/serializacji tych obiektów.
- Potwierdzono z plików, że publikowany model RAP obejmuje tylko nagłówek; brak CDS/behavior dzieci i brak compositions/nawigacji do tabel item/reference.
- Zauważono rozbieżność w metadanych bindingu: `PUBLISHED=true`, ale serwis ma `RELEASE_STATE=NOT_RELEASED`. Nie potraktowano tego jako dowodu aktywnej publikacji runtime.
- Przeskanowano `ZCL_KSEF_HTTP` pod kątem typowych wzorców klucza prywatnego, wbudowanych sekretów/tokenów, Bearer credentiali i URL-i z danymi logowania; skan nie znalazł dopasowań. To kontrola heurystyczna, nie pełny audyt bezpieczeństwa. Klasa zawiera eksperymentalny kod uwierzytelnienia/protokołu i pozostaje legacy.
- Wykonano aktualizację dokumentacji w zakresie opisanym w Changed Files.

## Changed Files

- `docs/guides/ABAPGIT_WORKFLOW.md` — konfiguracja, rozdział EGit/ADT/abapGit/transport, oba kierunki synchronizacji, konflikty, PAT i troubleshooting.
- `docs/guides/ABAP_RAP_INVOICE_MODEL.md` — tabele, UUID, root/projection/behavior/DCL/service oraz braki dzieci i wymagane weryfikacje runtime.
- `docs/journal/2026-10-24-abapgit-rap-initial-export.md` — ten wpis.
- `AGENTS.md`, `CLAUDE.md`, `.agents/skills/ainvoicer-abap-rap/SKILL.md` — kanoniczne zasady, streszczenie i workflow skill.
- `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/README.md`, `README.md` — stan, plan, indeks i krótki opis projektu.

Nie modyfikowano źródeł `abap/src/`, `.abapgit.xml`, plików CAP, UI5, Node ani Python.

## Commands Executed

Windows PowerShell, repozytorium `D:\AInvoicer`:

- `git status --short --branch`, `git log -6 --oneline --decorate`, `git show --stat 39ab869`, `git show --name-status 39ab869` — identyfikacja branchu, czystego stanu i zawartości commita.
- Odczyt wskazanych plików dokumentacji, `.abapgit.xml`, `abap/src/` i `docs/templates/JOURNAL_TEMPLATE.md`.
- `git check-ignore -v abap/.project` — potwierdzono ignorowanie lokalnego projektu Eclipse.
- Skan regex klasy `ZCL_KSEF_HTTP` — 0 dopasowań czterech podstawowych wzorców sekretów; nie jest to pełny audyt.
- Nie uruchamiano SAP ADT/MCP, SAP OData, buildów, testów KSeF, synchronizacji abapGit, Git Pull/Push ani operacji Git zapisujących historię.

## Tests & Results

| Sprawdzenie | Wynik |
| --- | --- |
| Inspekcja Git i commit `39ab869` | PASS — HEAD i origin wskazują commit; 33 zmienione pliki łącznie, w tym 32 pliki eksportu pod `abap/src/`. |
| Struktura modelu i zakres RAP | PASS dla inspekcji plików; root, projection, behavior, DCL i serwis ograniczone do nagłówka. Nie jest to weryfikacja SAP live. |
| Status runtime service bindingu / `$metadata` / autoryzowany odczyt | NIE WYKONANO — wymaga dostępu do SAP; w eksportowanych metadanych występuje rozbieżność `PUBLISHED` vs `NOT_RELEASED`. |
| Heurystyczny skan sekretów klasy legacy | PASS w zakresie czterech regexów; nie znaleziono typowych wbudowanych sekretów. Nie jest to pełny audyt kryptografii ani zachowania logów. |
| Markdown i lokalne linki | PASS — kontrola lokalnych linków w zmienionych plikach nie wykazała brakujących celów. |
| `git diff --check` | PASS — bez błędów whitespace; Git wyświetlił ostrzeżenia o przyszłej normalizacji LF/CRLF dla czterech plików. |
| Build, testy ABAP, ATC, testy CAP/KSeF | NIE WYKONANO — zadanie dokumentacyjne i brak dostępu do SAP/KSeF. |

## Problems Encountered

Zserializowane metadane service bindingu nie są wewnętrznie jednoznaczne: flaga bindingu wskazuje `PUBLISHED=true`, natomiast wpis usługi `RELEASE_STATE=NOT_RELEASED`. Przyczyna nie została ustalona. Wymagana jest kontrola aktywnego stanu w SAP ADT.

Nie przeprowadzono pełnego audytu `ZCL_KSEF_HTTP`; podstawowe skany nie wykazały literalnych credentiali. Klasa nadal może zawierać eksperymentalną logikę uwierzytelnienia i nie jest częścią architektury docelowej.

## Decisions Made

- Źródłem opisu repo jest eksport pod `abap/src/`; status aktywacji, publikacji i OData runtime musi być potwierdzony osobno w SAP.
- `ZAINV_INV_H/I/REF` opisano jako model symulacyjny. Nie przypisuje się mu rzeczywistych tabel S/4HANA.
- `ZCL_KSEF_HTTP` oznaczono jako legacy/experimental; nie usuwano obiektu.
- Nie dodano ADR: aktualizacja dokumentuje wykonanie istniejącego kierunku architektury i stan obiektów, bez nowej decyzji o właścicielu danych.

## Current State

Pierwszy eksport jest potwierdzony historią Git na `39ab869`. Dokumentacja wskazuje nowy model i jego obecny wąski zakres RAP. Nie potwierdzono runtime SAP, metadanych OData, autoryzacji ani odczytu danych.

## Next Steps

1. W SAP ADT zweryfikować aktywny stan `ZAPI_AINV_INV_H_O4`, jego publikację/release i rzeczywisty `$metadata`.
2. Zaprojektować oraz wdrożyć CDS, behavior, autoryzację i nawigacje/compositions dla pozycji i referencji, jeśli są potrzebne w pierwszym kontrakcie.
3. Po potwierdzeniu kontraktu skonfigurować CAP Java Remote Service i wykonać autoryzowany odczyt syntetycznej faktury.
