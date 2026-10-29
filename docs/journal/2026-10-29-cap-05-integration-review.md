---
title: "Dziennik — CAP-05 audyt Claude Code i uzupełnienie integracji"
date: 2026-10-29
type: journal
status: zakonczony
tags: [ainvoicer, journal, cap-05, cap-java, ksef, sapui5, rap]
---

# CAP-05 — audyt i implementacja braków

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Pełny raport](../audits/2026-10-29-cap-05-implementation-review.md)

## Date / Branch

2026-10-29 (Europe/Warsaw). `feature/cap-05-integration`, HEAD `2e0ee371d5e718457bcb07da3e5ab42aa1d0de1c`. Nie wykonano nowego commita/push.

## Goal / Initial State

Użytkownik przekazał prompt CAP-05 po przerwaniu pracy Claude Code. Zadanie: zweryfikować kompletność/poprawność, poprawić błędy i uzupełnić lokalny/mock przepływ. Bez nowego KSeF SEND, HTTP WRITE H01, nadania IAM i deployu.

Zastano merge auth A+B (`f49153b`), runner UI5 C (`0cd7440`) i commit backendu `2e0ee37`; 15 zmodyfikowanych plików UI5 oraz nowe fixture RAP/test. Zachowano tę pracę. Inne worktrees nie były modyfikowane. Dokumentacja STATUS/ROADMAP/ADR nadal opisywała wcześniejszy stan. Baseline CAP 163/163 PASS; nowe QUnit miały FAIL sortowania Decimal mockservera.

Przeczytano AGENTS, STATUS, ROADMAP, dzienniki A/B/C i CAP-04, audyt statusów, ADR-0002…0005, przewodniki UI5/SDK/orkiestracji i kanoniczne Skills CAP/KSeF/ABAP. Zweryfikowano oficjalną dokumentację CAP, DCL SAP, runtime TEST OpenAPI i tag SDK.

## Implementation / Changed Files

- CAP: companyCode w metadanych lokalnych i restrykcjach OData (NIP + spółka); requestSend ustala scope z RAP. Historyczne nieznane/NEW/FAILED SEND blokują nowe próby. Remote ServiceException maskowany.
- Worker: 440/nieznany status → rekoncyliacja, format i NIP numeru, hash/ref także w recovery; OUTBOUND nie tworzy drugiego wyniku biznesowego w KsefInvoices. Dowód ACCEPTED zachowany w operacji, UPO osobno.
- Sweeper: zachowano dwie granice commit i dodano rotację wadliwych rekordów; test kolejnej partii po 51 błędach. Stabilny operationId/WriteRequestId bez drugiego SEND.
- Testy: dwa różne autoryzowane użytkowniki konkurują o rezerwację; pełny lokalny requestSend→FA(3)→ACCEPTED→UPO→SAP dla APPLIED/ALREADY_APPLIED/VERSION_CONFLICT, odmowa obcej spółki, dowody recovery i safe errors.
- UI5: dokończono named RAP model/adapter i mock fixture, jedna strona w pamięci, Previous/Next, sortowanie RAP i bezpieczne filtry; kontrola uciętej historii CAP; usunięto możliwość CAP proxy listy. Priorytet numeru RAP i blokada Send przy nieznanym stanie. Filtr statusu jawnie bieżącej strony.
- SAP: tylko odczyty MCP. Przygotowano 3 CDS, 3 DCL, SRVD i patch 2 testów mutacji dokumentu w `docs/patches/cap05-rap-ui`; brak aktywacji i nadania. Binding V4 UI niedostępny w schemacie narzędzia tworzenia.
- Dokumentacja: AGENTS, STATUS/ROADMAP/README, ADR-0004/5, guide UI5/SDK/orkiestracja, nowy raport/runbook i ten dziennik.

Dokładny zakres plików i macierz wymagań: [raport](../audits/2026-10-29-cap-05-implementation-review.md). Wygenerowane `target`, `dist`, node_modules, lokalne logi są ignorowane; nie dodano ich do Git. Eksportu `abap/src` nie zmieniano.

## Commands Executed

- Git: branch/status/log/show/worktree, porównanie FA(3) z 7075c01, `git ls-remote --heads origin …`, `git ls-remote` tagu SDK, `git diff --check`, `git apply --check` patcha testów; read-only status innych worktrees.
- `cap-java`: baseline i iteracyjne `mvn [clean] verify '-Dcds.cds.skipVersionCheck=true'`; ostatni pełny clean verify opisany niżej.
- `sapui5/ainvoicer`: `npm ci`, `npm run build`, `npm run serve-mock`, `npm test` (QUnit/OPA, Chrome headless), `npm audit --omit=dev --json`.
- MCP H01: creation diagnostics (bez logowania), search SRVB, source BDEF/CDS, URI include/DCL/testclasses, resolve service, normalized metadata i read z dwoma expand. Bez mutacji SAP.
- Ograniczony skan nowych/zmienionych plików na literalne private keys/PAT/JWT, kontrola śledzonych rozszerzeń credentiali. Bez wypisywania wartości sekretów.

## Tests & Results

| Zakres | Wynik |
| --- | --- |
| CAP clean verify końcowy | **173/173 PASS**, JDK 25.0.4.1/Maven 3.9.16/CAP 5.2.0/SDK 3.0.29; H2/mock, live tagi wykluczone |
| UI5 build | PASS, Node 24.13.0/npm 11.6.2/UI5 1.133.3 |
| QUnit | **57/57 PASS**, 260 asercji, prawdziwy V4 model + mockserver/fake CAP |
| OPA | **13/13 PASS**, 80 asercji; AI/Scanner/Legacy, 2 oczekiwane TTS errors z atrapy |
| H01 Web API READ MCP OAuth | HTTP200, metadata + 4/9/6; nie business user UI ani bieżący CAP Destination |
| RAP UI/DCL/IAM/nowe Unit/ATC | BLOCKED / NOT EXECUTED; przygotowano patch, sprawdzono tylko jego zastosowanie lokalnie |
| KSeF SEND / H01 HTTP WRITE / deploy | NOT EXECUTED |
| npm audit omit dev | 0 vulnerabilities; pełne npm ci raportuje 82 w toolchainie dev |

Logi lokalne: `cap-java/cap05-audit-final4-build.log`, `sapui5/ainvoicer/cap05-audit-final2-ui-build.log`, `cap05-audit-final2-browser.log`. Pełna reprodukcja i źródła kontraktów w raporcie.

## Problems Encountered / Decisions Made

- `npm ci` początkowo EPERM na natywnym module keyring; dwa pozostawione serwery fiori/ui5 trzymały plik. Zatrzymano wyłącznie procesy tego repo i odtworzono zależności; po zmianach fixture/config serwer uruchamiano ponownie z uwagi na cache mockservera. Pozostałe procesy użytkownika zachowano.
- Iteracyjna kompilacja wykryła nadmiarowy nawias w zmienionym filtrze CQN; poprawiono przed kolejną regresją. Żaden nieudany przebieg nie jest raportowany jako PASS.
- QUnit wykazał leksykalne sortowanie string Decimal w mock fixture. Przekształcono fixture na liczby; produkcyjny mapper nadal przyjmuje string Decimal. Rozszerzono diagnostykę runnera o expected/result.
- Przyjęty prompt zatwierdza ADR-0005. Dwa commity transakcyjne (ACCEPTED, potem enqueue) + sweeper są świadomą ochroną przed cofnięciem potwierdzenia. Brak dodatkowego frameworka.
- DCL `where TRUE` i brak blokady UPDATE/DELETE po numerze potwierdzono inspekcją H01. Nie rozszerzano praw ani ograniczeń księgowych. Przygotowano osobne read-only CDS z własnym DCL; nie użyto bindingu V2 jako obejścia braku V4.
- W istniejącej trwałej bazie potrzebna migracja CompanyCode/provenance/rezerwacji. Brak scope oznacza niewidoczność starych rekordów, bez domyślnego nadania. Nie resetowano baz użytkownika.

## Current State / Next Steps

Lokalna implementacja i regresja są ukończone. LIVE E2E pozostaje niepotwierdzony. Na koniec audytu branch miał zastane i nowe niezacommitowane zmiany do przeglądu.

## Publikacja Git po audycie

Użytkownik następnie jawnie zlecił „commit i push”. Zakres publikacji: poprawki CAP-05, dokończone UI5, testy, dokumentacja oraz nieaktywowane źródła RAP UI na `feature/cap-05-integration` do `origin` (repozytorium scaamp/AInvoicer). Nie obejmuje merge do java-cap-ksef/main, deploymentu ani zmian SAP/KSeF. Weryfikacja przed publikacją: właściwa gałąź, przegląd plików i `git diff --check`; brak nowych zmian kodu od opisanej regresji 173/57/13 PASS. Ostateczny hash i wynik push są rejestrowane w historii Git i odpowiedzi końcowej.

1. ADT: aktywacja przygotowanej usługi V4 UI + DCL i uzgodnione IAM, test odczytu dwoma business userami, brak ApplyKsefResult w UI. [Szczegóły](../patches/cap05-rap-ui/README.md).
2. Uzgodnić poprawną syntetyczną fakturę RAP/PartyData i routing RAP/CAP, migrację lokalnej trwałej bazy oraz przyszłe HANA/storage. Globalny filtr CAP i pobranie XML/UPO to kolejne rozszerzenia.
3. Dopiero po osobnym zleceniu: kontrolowana jedna wysyłka KSeF TEST + H01 writeback i 409 po ponowieniu, według scenariusza z raportu. Osobno zatwierdzić publikację Git.
