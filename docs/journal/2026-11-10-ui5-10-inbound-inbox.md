---
title: "Dziennik — UI5-10 KSeF Inbound Inbox (frontend, dane syntetyczne)"
date: 2026-11-10
type: journal
status: zakonczony
tags: [ainvoicer, journal, sapui5, ksef, inbound, mock, frontend]
---

# Dziennik — UI5-10 KSeF Inbound Inbox

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Przewodnik KSeF Inbox](../guides/KSEF_INBOX_UI5.md) · [Architektura INBOUND](../guides/KSEF_INBOUND_ARCHITECTURE.md)

## Date

2026-11-10 (Europe/Warsaw).

## Branch

`feature/ui5-10-inbound-inbox` w osobnym worktree `D:\AInvoicer-ui5-10`, utworzony z `origin/main` =
`0297b87d2fe2ddb337990cd58e56e8e0ce87c328` (zgodny z SHA podanym w zadaniu).

## Goal

Nowy, osobny ekran SAPUI5 **KSeF Inbox** dla faktur zakupowych odebranych z KSeF (mogą nie istnieć w SAP), działający
lokalnie na syntetycznych danych mock, z adapterem przygotowanym pod przyszły read-only CAP OData
(`KsefService.InboundInvoiceOverview`, INT-02). Wyłącznie frontend; równolegle Codex realizuje KSEF-IN-02 (backend).

Kryteria: lista/filtry/sortowanie/paginacja/szczegóły, dane demo jawnie oznaczone, wszystkie akcje mutujące OFF,
CF bez backendu bez fallbacku do mock i bez HTTP, QUnit/OPA/buildy PASS, brak regresji kokpitu, dokumentacja, PR.

## Initial State

- Repo główne `D:\AInvoicer` na `feature/cap-07c-autonomous-hardening` (czyste) — nieprzełączane.
- Worktree Codexa `D:\AInvoicer-ksef-in-02` (`feature/ksef-in-02-persistence` @ `0297b87`, bez zmian lokalnych) —
  odczyt tylko `cap-java/db/schema.cds` (brak encji inbound); nic nie modyfikowano.
- Przeczytane: `AGENTS.md`, `CLAUDE.md`, manifest, `Main.view.xml`, `Main.controller.js`, `KsefCockpit.js`,
  `KsefContract.js`, `KsefDataAdapter.js`, formatter, QUnit/OPA, `scripts/build-cf.mjs`, przewodniki kokpitu i INBOUND.
  Algorytm numeru KSeF (CRC-8, wielomian 0x07) i wzorcowe NIP-y przejęte z `InboundTestSupport` (cap-java, testy).

## Implementation

1. Kontrakt i model (`model/ksef/inbound/`): `InboundContract` (tryby mock/cap/disabled, słowniki statusów, pola DTO,
   lista biała sortowania, akcje przyszłe), `InboundInvoiceMapper` (DTO → wiersz; `grossAmount` wyłącznie jako tekst
   dziesiętny, porównanie bez `Number`; jawne `null` i kody problemów; skrót numeru KSeF), `InboundQuery` (walidacja
   filtrów przed HTTP, ta sama semantyka dla mock i `$filter/$orderby/$skip/$top/$count/$select`),
   `InboundInboxAdapter` (mock działający; CAP przygotowany: same-origin GET, OData 4.0, `IEEE754Compatible`, timeout,
   walidacja kompletności; disabled bez HTTP; brak fallbacku), `InboundFormatter`.
2. Widok `InboundInbox.view.xml` + kontroler, fragmenty szczegółów i sortowania; routing `ksef-inbox`; przycisk
   „KSeF Inbox” w nagłówku kokpitu (`Main.view.xml`) i `onNavToKsefInbox` (`Main.controller.js`).
3. 26 syntetycznych rekordów (`localService/ksef/inbound/InboundInvoices.json`, generator deterministyczny): poprawne
   CRC numerów KSeF, wzorcowe NIP-y 1111111111…7777777777, przypadki brzegowe (null/niepełne metadane, nieobsługiwany
   schemat FA(2), konflikt integralności, niezgodny hash, błąd pobrania, kwota 98765432109876.54, korekta ujemna, EUR,
   DEMO, VatUe/None nabywcy, nieznany status, niepoprawna kwota/waluta, brak numeru faktury, niestandardowy numer KSeF).
   Brak PROD, brak `POSTED`, brak numerów dokumentów SAP.
4. `manifest.json`: sekcja `ksefInbox` (mock, `cap.entitySet=null`, capabilities OFF), route/target.
5. `scripts/build-cf.mjs`: `transformInboxConfig` (CF = `disabled`, bez mock/encji, capabilities OFF; źródło z akcją ON
   odrzucone) i rozszerzony `verifyArtifact`.
6. UX review w przeglądarce (headless Chrome przez CDP, zrzuty desktop/telefon/szczegóły/błąd/disabled) → poprawki:
   szerokości kolumn (kolumna „SAP Status” wychodziła poza 1600 px), filtry w zwijanym panelu (zwinięte na telefonie,
   potwierdzone z mobilnym UA), neutralna ilustracja dla stanu niedostępnego.

## Changed Files

Nowe (`sapui5/ainvoicer/webapp/`): `view/InboundInbox.view.xml`, `view/InboundInvoiceDetails.fragment.xml`,
`view/InboundInboxSort.fragment.xml`, `controller/InboundInbox.controller.js`, `model/ksef/inbound/{InboundContract,
InboundInvoiceMapper,InboundQuery,InboundInboxAdapter,InboundFormatter}.js`, `localService/ksef/inbound/InboundInvoices.json`,
`test/unit/model/{InboundInvoiceMapper,InboundQuery,InboundFormatter,InboundInboxAdapter}.js`,
`test/integration/InboundInboxJourney.js`, `test/integration/pages/InboundInbox.js`.

Zmienione: `webapp/manifest.json`, `webapp/i18n/i18n.properties` (sekcja `inbox*`), `webapp/view/Main.view.xml`,
`webapp/controller/Main.controller.js`, `webapp/test/unit/AllTests.js`, `webapp/test/integration/AllJourneys.js`,
`scripts/build-cf.mjs`, `scripts/build-cf.test.mjs`.

Dokumentacja: `docs/guides/KSEF_INBOX_UI5.md`, ten dziennik. Wygenerowane i ignorowane: `dist/`, `dist-cf/`, `build/`.

## Commands Executed

Katalog `D:\AInvoicer-ui5-10`, Node v24.13.0, UI5 CLI 3.11.6, SAPUI5 1.133.3 (proxy ui5.sap.com), Chrome headless.

```text
git fetch origin; git worktree add -b feature/ui5-10-inbound-inbox ../AInvoicer-ui5-10 origin/main
npm ci --prefix sapui5/ainvoicer
npx fiori run --config ./ui5-mock.yaml --port 8097      # 8095 zajęty przez obcy proces; 8085 niezajęty, ale wybrano wolny port
node scripts/browser-test.mjs qunit http://localhost:8097/test/unit/unitTests.qunit.html
node scripts/browser-test.mjs qunit http://localhost:8097/test/integration/opaTests.qunit.html
npm run build --prefix sapui5/ainvoicer
npm run build:cf --prefix sapui5/ainvoicer
npm run test:cf-build --prefix sapui5/ainvoicer
npm run test:runner --prefix sapui5/ainvoicer
node <scratchpad>/inbox-shots.mjs, inbox-phone.mjs     # UX review przez CDP (poza repo)
```

## Tests & Results

Wszystko **lokalnie, na danych syntetycznych** (mock + atrapy fetch). Brak LIVE CAP i LIVE KSeF.

| Test | Wynik |
| --- | --- |
| QUnit | **PASS 112/112, 659 asercji** (było 79/446; +33 testy Inbox) |
| OPA | **PASS 21/21, 150 asercji** (było 13/80; +8 journey Inbox), kokpit/AI/Scanner bez regresji |
| `npm run build` | PASS |
| `npm run build:cf` | PASS — 71 plików, `ksefInbox` = `disabled`, bez `mock`, `entitySet=null` |
| `npm run test:cf-build` | **PASS 13/13** (było 8; +5 Inbox) |
| `npm run test:runner` | PASS 4/4 |
| UX w przeglądarce | desktop 1600 px, telefon (UA iPhone 390 px), dialog szczegółów, błąd walidacji, tryb disabled; 0 wyjątków JS; obce żądania: tylko `livereload` serwera dev (localhost:35729); w trybie disabled jedynym żądaniem jest statyczne SVG ilustracji UI5 |

Scenariusze z zadania pokryte testami: ładowanie, lista, filtrowanie, sortowanie, paginacja, szczegóły, brak danych,
błąd odczytu, brak odpowiedzi serwera (timeout), null/niepełne metadane, długie numery KSeF, format kwot, nieobsługiwany
schemat, konflikt integralności, niezaksięgowana faktura, disabled actions, brak wycieku do zewnętrznych hostów,
CF bez backendu nie ładuje mocków.

## Problems Encountered

- Test wykrył realny błąd: `SortableFields["constructor"]` trafiał w prototyp `Object` → wprowadzono `InboundContract.has`
  (`hasOwnProperty`) dla słowników sortowania, statusów i środowisk.
- OPA wykrył realny błąd: `HBox` nie jest dozwoloną treścią `SimpleForm` (`ColumnLayout`) → pole i przycisk kopiowania
  jako dwa elementy z `ColumnElementData`.
- Błędy testów (nie produktu): `iCallController` z matcherem `Interactable` przy otwartym modalnym `ViewSettingsDialog`
  (VSD nie ma `isOpen/close`) → akcja testowa naciska OK wewnętrznego dialogu; asercja kwoty zależna od locale (przeglądarka
  testowa ma PL: `98 765 432 109 876,54`) → porównanie cyfr.
- Teksty frameworka (FilterBar „Start/Wyczyść”, placeholder DateRange) są w języku przeglądarki, a i18n aplikacji jest
  po angielsku — zachowanie zastane w całej aplikacji, poza zakresem.

## Decisions Made

- Osobny widok/route zamiast rozbudowy `Main.controller.js`; szczegóły jako dialog (spójnie z kokpitem).
- `sap.m.Table` z popin i progressive loading („Load more”) zamiast `sap.ui.table` — responsywność na telefonie.
- Fail-closed: nieznany `dataMode` = disabled; brak domyślnej ścieżki mock; brak fallbacku cap → mock.
- Akcje przyszłe twardo OFF w adapterze (manifest ich nie włączy); CF wymusza disabled w `build-cf.mjs`.
- Zgodnie z poleceniem **nie** modyfikowano wspólnych `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/README.md` (odstępstwo od
  workflow AGENTS.md §10 uzgodnione w zadaniu; aktualizację indeksu/STATUS wykona orkiestrator przy integracji).
- Nie zmieniano `cap-java/`, `deploy/`, `mta.yaml`, `approuter/`, GitHub Actions.

## Current State

- Ukończone i zweryfikowane lokalnie: ekran KSeF Inbox na danych syntetycznych, nawigacja, filtry, sortowanie,
  paginacja, szczegóły, stany pusty/błąd/disabled, wariant CF disabled, testy i dokumentacja.
- **Niezweryfikowane:** provider CAP wobec rzeczywistego endpointu (testowany wyłącznie atrapą fetch), obsługa
  `tolower/contains/startswith` w CAP Java, literał klucza `Edm.Guid`, autoryzacja grantów w CAP. LIVE KSeF i LIVE CAP:
  **NOT EXECUTED**.

## Next Steps

1. INT-02: po KSEF-IN-02 wystawić read-only `KsefService.InboundInvoiceOverview` z filtrem grantów i podłączyć adapter
   wg [przewodnika §8](../guides/KSEF_INBOX_UI5.md#8-podłączenie-ksefserviceinboundinvoiceoverview-instrukcja-dla-int-02).
2. Dodać test kontraktowy z rzeczywistą odpowiedzią CAP oraz opt-in test dual-backend dla Inbox (w tym użytkownik bez grantu).
3. Orkiestrator: podlinkować przewodnik i dziennik w `docs/README.md` oraz zaktualizować `STATUS`/`ROADMAP`.
