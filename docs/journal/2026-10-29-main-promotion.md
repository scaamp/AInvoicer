---
title: "Git — promocja java-cap-ksef do main i archiwum oryginału"
date: 2026-10-29
type: journal
status: zakonczony
tags: [ainvoicer, journal, git, main, archiwum]
---

# Promocja aktualnego AInvoicer do main

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md)

## Zlecenie i stan początkowy

Użytkownik jawnie zlecił scalenie sprintu do java-cap-ksef, zachowanie dotychczasowego main na nowej gałęzi oraz ustawienie aktualnej linii java-cap-ksef jako main. To zlecenie rozszerza wcześniejszy zakres publikacji ograniczony do gałęzi feature.

Po `git fetch origin` drzewo było czyste, lokalne gałęzie zgadzały się z origin:

| Gałąź | HEAD przed operacją |
| --- | --- |
| main | b3e10b0cc4f2f17e0d98e0203303cb941606814c |
| java-cap-ksef | 9e4ac94513100bfeeeedc51c4ee4595c99e72510 |
| feature/abap-05-real-rap-ui | 75d929142d61bfadfc2cd12403c711c43be56c0b |

Git merge-base potwierdził, że main jest przodkiem java-cap-ksef (0 commitów wyłącznie na main, 49 wyłącznie na java-cap-ksef przed scaleniem sprintu). GitHub get_repo oraz `git ls-remote --symref origin HEAD` potwierdziły default branch **main**; nie było potrzeby zmiany ustawień repozytorium. Nie istnieje workflow .github do przestawienia. Nie usuwano pozostałych branchy/worktrees.

## Wykonanie

1. Utworzono lokalną `archive/original-ainvoicer-2026-10-29` dokładnie na dawnym origin/main, wypchnięto ją do origin i potwierdzono `git ls-remote`: **b3e10b0cc4f2f17e0d98e0203303cb941606814c**. Kopia zawiera pełny pierwotny projekt i jego historię. Weryfikacja archiwum poprzedziła aktualizację main.
2. Na java-cap-ksef wykonano `git merge --no-ff feature/abap-05-real-rap-ui`, merge commit **c21f94674062e8dcf6b793be9c038c436b81cdd7**. Bez konfliktów; drzewo merge jest identyczne z przetestowanym 75d9291.
3. Zaktualizowano instrukcje i dokumentację: main jest bazą nowych feature, java-cap-ksef pozostaje zachowany, a archiwum nie służy do rozwoju. Zmiana dotyczy AGENTS.md, CLAUDE.md, README.md, docs/README.md, STATUS.md, ROADMAP.md, ABAPGIT_WORKFLOW.md i tego dziennika.
4. Promocja main odbywa się przez `git merge --ff-only java-cap-ksef`, obejmując merge sprintu i commit dokumentacji. Obie gałęzie publikowane są razem przez `git push --atomic origin java-cap-ksef main`, bez force/reset i bez przepisywania historii. Końcowe SHA i wynik publikacji potwierdza raport operacji Git; hash tego commita dokumentacyjnego nie jest wpisywany do własnej treści.

## Weryfikacja

- Wykonane odczyty: git status/branch/remote/fetch/log/rev-parse/merge-base/rev-list/ls-remote oraz GitHub get_repo/search_branches.
- Archiwum: dokładne wskazanie starego main potwierdzone na origin przed promocją.
- Merge: `git diff --exit-code feature/abap-05-real-rap-ui c21f946` zwrócił 0.
- Aktualizacja instrukcji zmienia wyłącznie Markdown. Kontrola git diff --check i lokalnych linków poprzedza commit; po publikacji sprawdzane są zdalne SHA obu gałęzi, ich zgodność, przynależność sprintu i zachowanie oryginalnego archiwum.
- Buildów/testów aplikacji nie uruchamiano ponownie: nie zmieniono kodu poza już przetestowanym sprintem. Ostatnie własne wyniki z [ABAP-05](2026-10-29-abap-05-real-rap-ui.md): Maven 178/178, QUnit 59/59, OPA 13/13, UI5 build PASS. Są wynikiem poprzedniego przebiegu, nie nowym testem merge.
- Nie zmieniano SAP, konfiguracji abapGit ani BTP; nie wykonano SEND ani HTTP WRITE. Blokery RAP UI/IAM pozostają bez zmian.

## Zasady dalszej pracy

Nowe branche feature twórz z aktualnego **main**. Archiwum oryginalnego projektu pozostaje na niezmienionym b3e10b0. java-cap-ksef zachowano ze względu na historię i dotychczasowy workflow; nie należy traktować go jako drugiej niezależnej głównej linii rozwoju.

Zmiana głównej gałęzi Git nie przełącza linked repository abapGit w H01. Jego ostatnio potwierdzona konfiguracja wskazywała java-cap-ksef. Przed kolejnym eksportem sprawdzić branch w SAP; ewentualne przełączenie na main jest osobnym krokiem konfiguracji, bez automatycznego importu lub aktywacji. Do tego czasu sprawdzony eksport z java-cap-ksef należy integrować do main w autoryzowanym zakresie.

## Następny krok

Kontynuować od main: ADT/IAM — binding OData V4 UI i testy READ dwóch business userów według [przewodnika LIVE](../guides/RAP_UI_LIVE_CONNECTION.md). Przed następną synchronizacją SAP niezależnie zweryfikować linked branch abapGit.
