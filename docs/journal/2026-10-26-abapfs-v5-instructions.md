---
title: "ABAP FS v5 — instrukcje projektu i granice testów"
date: 2026-10-26
type: journal
status: zakonczony
tags: [ainvoicer, abap-fs, mcp, skills, transport, odata]
---

# ABAP FS v5 — instrukcje projektu i granice testów

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md)

## Cel i stan początkowy

Aktualizacja rozpoczęta 2026-10-25, domknięta 2026-10-26 (Europe/Warsaw), branch `java-cap-ksef`. Użytkownik zapytał o aktualizację instrukcji po poprawkach wtyczki, następnie zlecił kontynuację. Stare reguły opisywały m.in. MCP 2.12.0 i brak szczegółów HTTP. Zachowano granice SAP/Git oraz rozróżnienie aktywacji i eksportu.

Początkowo zmienione były README, STATUS, ROADMAP, przewodnik modelu RAP i dziennik kompozycji; dziennik create-probe był nieśledzony. Zmiany zastane zachowano. Podczas pracy inny proces zapisał stan repo w `c44d155`; przed końcowymi edycjami ponownie sprawdzono Git. Ta aktualizacja nie wykonuje commit/push.

## Implementacja i zmienione pliki

- `AGENTS.md`: punkt odniesienia v5, kontrola procesu MCP, jawny pakiet i transport, klasyfikacja błędów oraz odróżnienie HTTP OData od ADT/EML.
- `.agents/skills/ainvoicer-abap-rap/SKILL.md`: bieżący kontrakt tworzenia, diagnostyka/logowanie i odsyłacz do szczegółowej procedury.
- `docs/guides/ABAPGIT_WORKFLOW.md`: procedura v5; starszy incydent pozostaje oznaczony jako historia.
- `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/README.md`: aktualne odsyłacze i zakres potwierdzenia.
- Ten dziennik: pochodzenie dowodów i ograniczenia aktualizacji.

CAP Java i KSeF nie wymagają zmiany skilli. `CLAUDE.md` i `.claude/skills/` już odsyłają do kanonicznych instrukcji. Nie dodano równoległej kopii.

## Wcześniejsze dowody — nie nowe próby tej aktualizacji

Odczyt GitHub potwierdził najnowsze stabilne [wydanie 2.12.4 / ADT creation fix 5](https://github.com/scaamp/abap-fs-creation-fix/releases/tag/v2.12.4-adt-creation-fix.5). Przejrzano lokalny kod narzędzia diagnostycznego i transportów. Test v5 z 2026-10-25 w zainstalowanym profilu (`development=false`) opisano wcześniej w lokalnym raporcie `creation-matrix-private/v5-live-20261025.md` w odrębnym workspace poprawki wtyczki. Poniżej utrwalono wynik bez kopiowania surowych odpowiedzi SAP.

| Typ | Obiekt | Pakiet | HTTP | Potwierdzenie SAP |
| --- | --- | --- | --- | --- |
| `CLAS/OC` | `ZCL_FS5_LOCAL_A01` | `ZAINVOICER` | 200 | Istnieje, inactive; brak przypisania transportu |
| `DDLS/DF` | `ZI_FS5_LOCAL_A01` | `ZAINVOICER` | 201 | Istnieje, inactive; brak przypisania transportu |
| `CLAS/OC` | `ZCL_FS5_DEV_A01` | `ZAINVOICER_DEV` | 200 | Istnieje, inactive; przypisanie do zatwierdzonego requestu potwierdzone |
| `DDLS/DF` | `ZI_FS5_DEV_A01` | `ZAINVOICER_DEV` | 201 | Istnieje, inactive; przypisanie do zatwierdzonego requestu potwierdzone |

PASS **4/4** dla tworzenia i transportów. Porównanie modyfikowalnych requestów przed/po: zero nowych numerów. Dla lokalnego pakietu nie przekazywano transportu i nie było `corrNr`; dla DEV jawnie podano istniejący request, potwierdzony także listą jego obiektów. Nie aktywowano ani nie usuwano tych czterech obiektów; pozostawiono je jako fixtures. Logowanie przywrócono do wyłączonego.

To nie był test składni/ATC/ABAP Unit tych fixtures, pełny ponowny test 23 typów v5 ani test ECC. Wcześniejsza macierz v4: 20 utworzeń i 3 odmowy autoryzacji spośród 23 typów; nie daje gwarancji każdego backendu.

## Nowy przypadek użycia OData — relacja użytkownika

Użytkownik wkleił raport innego Codexa: 6 DCL aktywnych, EML 4/4 PASS, ATC 3 nowych ról bez ustaleń oraz nawigacje `_Items`, `_References`, `_Parent` widoczne w ADT. Nie powtarzano tych kontroli live w tej aktualizacji. Raport jest nowszy od zapisanej migawki odłożonych DCL; odnotowano rozbieżność w STATUS/ROADMAP zamiast przepisywać wynik jako własny test.

Czytnik ADT odrzucił runtime URL OData, a automatyzacja przeglądarki miała timeout. To konkretny przypadek dla [proponowanego OData HTTP Executora](https://github.com/scaamp/abap-fs-creation-fix/blob/main/docs/potential-mcp-features.md#2-odata-http-executor): uwierzytelniony GET dokumentu usługi, `$metadata` i danych z `$expand`, z wynikiem status/body, limitami i stronicowaniem. Narzędzie nie jest zaimplementowane w v5. Uwierzytelnienie biznesowego endpointu wymaga osobnego potwierdzenia; połączenie ADT nie daje go automatycznie. EML PASS i nawigacje ADT nie zastępują testu HTTP. Odpowiedź bez poświadczeń/strona logowania także go nie zaliczają.

## Walidacja i ograniczenia

Polecenia: `git status --short --branch`, `git branch --show-current`, `git log -2`, `rg`, odczyt plików UTF-8; odczyt wydania przez konektor GitHub. Do oceny zmian zastosowano `skill-creator` i kanoniczny Skill ABAP/RAP. Wyniki lokalne: `quick_validate.py` Skilla **PASS**, kontrola **93 lokalnych odsyłaczy — 0 brakujących**, `git diff --check` **PASS**. Nie jest to behawioralny test skilla przez model ani test integracji SAP.

Nie zmieniano VSIX/instalacji VS Code ani obiektów SAP, nie wykonywano nowego testu live, aktywacji, eksportu, commit/push. Budowanie CAP lub rozszerzenia nie jest potrzebne do tej zmiany instrukcji.

## Następny krok

Przy kolejnym zadaniu sprawdzić aktualny stan DCL/EML względem nowego raportu i potwierdzić uwierzytelnione HTTP OData. Implementacja OData Executora jest odrębnym zadaniem w repo wtyczki; raport propozycji nie rozszerza aktualnej listy narzędzi.
