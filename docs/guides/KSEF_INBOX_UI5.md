---
title: "SAPUI5 — KSeF Inbox (faktury zakupowe odebrane z KSeF, UI5-10)"
date: 2026-11-10
type: guide
status: aktywny
tags: [ainvoicer, sapui5, ksef, inbound, frontend, mock, int-02]
---

# SAPUI5 — KSeF Inbox

[Project Home](../README.md) · [Kokpit KSeF (UI5)](SAPUI5_KSEF_COCKPIT.md) · [Architektura INBOUND](KSEF_INBOUND_ARCHITECTURE.md) · [Dziennik UI5-10](../journal/2026-11-10-ui5-10-inbound-inbox.md)

> **Stan (2026-11-10):** ekran działa **lokalnie na syntetycznych danych mock**. Brak backendu CAP dla INBOUND,
> brak wywołań KSeF, brak LIVE CAP. Wariant Cloud Foundry ma Inbox **wyłączony** (`dataMode=disabled`, zero HTTP).
> Kontrakt danych jest **roboczy** — do potwierdzenia z `$metadata` w INT-02.

## 1. Domena — czym Inbox NIE jest

| Widok | Źródło | Znaczenie |
| --- | --- | --- |
| Kokpit → zakładka **Purchase Invoices** | SAP RAP (`ZC_AINV_INV_H`, `Direction=INBOUND`) | faktury zakupowe, które **już są w SAP** |
| **KSeF Inbox** (`#/ksef-inbox`) | przyszły staging CAP (KSEF-IN-02) | faktury **odebrane z KSeF**, które mogą jeszcze **nie istnieć w SAP** |

To dwie różne domeny; Inbox nie łączy danych z RAP i nie zmienia kokpitu (ALL/PURCHASE/SALES/LEGACY bez zmian).
Wejście: przycisk **KSeF Inbox** w nagłówku kokpitu. Powrót: przycisk wstecz (historia przeglądarki albo `RouteMain`).

## 2. Struktura kodu

```text
webapp/
├── view/InboundInbox.view.xml               strona: banery, FilterBar (Panel), sap.m.Table, „Load more”
├── view/InboundInvoiceDetails.fragment.xml  dialog szczegółów (formularze + przyszłe funkcje OFF)
├── view/InboundInboxSort.fragment.xml       ViewSettingsDialog (sortowanie z listy białej)
├── controller/InboundInbox.controller.js    stan widoku, ładowanie stron, nawigacja; bez HTTP i adresów
├── model/ksef/inbound/
│   ├── InboundContract.js     tryby, słowniki statusów, pola DTO, sortowanie, akcje przyszłe
│   ├── InboundInvoiceMapper.js DTO → wiersz; kwota jako tekst dziesiętny; jawne null + kody problemów
│   ├── InboundQuery.js         walidacja filtrów, semantyka mock (matches/sortRows) i OData ($filter/$orderby…)
│   ├── InboundInboxAdapter.js  providery: mock | cap (przygotowany, OFF) | disabled
│   └── InboundFormatter.js     teksty/stany statusów, kwota (NumberFormat na stringu), identyfikatory stron
└── localService/ksef/inbound/InboundInvoices.json  26 syntetycznych rekordów (nie trafiają do CF)
```

```text
InboundInbox View ─▶ InboundInbox Controller ─▶ InboundInboxAdapter
                                                   ├── Local Mock Provider (dataMode=mock)
                                                   ├── Future CAP OData V4 Provider (dataMode=cap + cap.entitySet)
                                                   └── Disabled (brak/nieznany dataMode, wariant CF) — bez HTTP
```

Routing: `RouteInboundInbox` (`ksef-inbox`, zdefiniowany przed `RouteMain`), target `InboundInbox` (level 2).

## 3. Konfiguracja (`sap.ui5/config/ksefInbox`)

```json
"ksefInbox": {
  "dataMode": "mock",
  "pageSize": 20,
  "environments": ["TEST", "DEMO"],
  "mock": { "invoicesPath": "ainvoicer/localService/ksef/inbound/InboundInvoices.json" },
  "cap": { "serviceUrl": "/odata/v4/KsefService/", "entitySet": null, "readTimeoutMs": 15000 },
  "capabilities": { "viewXml": false, "checkDuplicate": false, "linkSapDocument": false, "approveAndPost": false }
}
```

- **Fail-closed:** tylko `"mock"` i `"cap"` są rozpoznawane; brak sekcji, literówka (`"MOCK"`) lub `"disabled"` = brak danych i brak HTTP.
- **Brak domyślnego fixture:** bez `mock.invoicesPath` provider mock zwraca `BACKEND_MISSING` (nie zgaduje pliku).
- **Brak fallbacku:** błąd CAP nigdy nie przełącza na mock; `cap` bez `entitySet` = komunikat „backend not connected (INBOX-OVERVIEW)” bez żądania.
- **Akcje przyszłe:** `InboundInboxAdapter.getCapabilities()` zwraca zawsze `false`, niezależnie od manifestu.
- `environments` steruje listą filtra środowiska (tylko TEST/DEMO/PROD z kontraktu).

### Wariant Cloud Foundry (`npm run build:cf`)

`scripts/build-cf.mjs → transformInboxConfig` zastępuje sekcję wersją: `dataMode=disabled`, bez `mock`, `cap.entitySet=null`,
wszystkie capabilities `false`. Źródło z włączoną akcją Inbox jest **odrzucane** (błąd builda). `verifyArtifact` dodatkowo
odrzuca artefakt z: Inbox ≠ disabled, ścieżką `mock`, encją CAP, odniesieniem do `InboundInvoices.json`, `linkSapDocument`/
`approveAndPost: true`. `localService/` nie trafia do `dist-cf/` (jak dotąd). Na CF użytkownik widzi stronę
„KSeF Inbox is not available” — jedynym żądaniem jest statyczna ilustracja UI5.

## 4. Kontrakt danych (roboczy, dla INT-02)

DTO = jeden wiersz `KsefService.InboundInvoiceOverview` (nazwa robocza, **endpoint nie istnieje**):

| Pole | Typ OData (oczekiwany) | Wymagane | Obsługa w UI |
| --- | --- | --- | --- |
| `id` | `Edm.Guid` (klucz) | **tak** | rekord bez poprawnego UUID jest pomijany i liczony („N record(s) were skipped”) |
| `environment` | `Edm.String` (TEST/DEMO/PROD) | nie | nieznana wartość pokazana dosłownie |
| `ksefNumber` | `Edm.String` | nie | skrót wizualny `NIP-…część-CRC`, pełny w tooltipie, polu tylko do odczytu i „kopiuj”; format ≠ 35 znaków = ostrzeżenie |
| `invoiceNumber` | `Edm.String(256)` | nie | zawijany (2 linie), pełny w tooltipie |
| `issueDate` | `Edm.Date` | nie | data kalendarzowa bez przesunięcia strefy |
| `acquisitionDate` | `Edm.DateTimeOffset` | nie | data i czas lokalny |
| `sellerNip`, `sellerName` | `Edm.String` | nie | brak = „—”/„No seller NIP” |
| `buyerIdentifierType` | `Edm.String` (Nip/VatUe/Other/None) | nie | brak NIP nabywcy to normalny przypadek |
| `buyerIdentifierValue`, `buyerName` | `Edm.String` | nie | j.w. |
| `currency` | `Edm.String(3)` ISO | nie | niepoprawny kod pokazany dosłownie + ostrzeżenie (bez upper-case) |
| `grossAmount` | `Edm.Decimal` jako **string** (`IEEE754Compatible=true`) | nie | nigdy `Number`; liczba JSON = oznaczona jako niezaufana; `preserveDecimals` |
| `invoiceType`, `formCode` | `Edm.String` | nie | rozszerzenie opcjonalne (np. `VAT`, `FA (3)`) |
| `ingestionStatus` | `Edm.String` | nie | `DISCOVERED`, `STORED`, `DOWNLOAD_FAILED` |
| `parseStatus` | `Edm.String` | nie | `PENDING`, `PARSED`, `UNSUPPORTED_SCHEMA`, `INVALID` |
| `integrityStatus` | `Edm.String` | nie | `PENDING`, `VERIFIED`, `MISMATCH`, `CONFLICT` |
| `sapPostingStatus` | `Edm.String` | nie | `NOT_POSTED` (+ przewidywane `POSTED`, `POSTING_FAILED`); `null` ≠ `NOT_POSTED` |
| `xmlAvailable` | `Edm.Boolean` | nie | tylko jawne `true` oznacza dostępność |

Status spoza słownika (np. `QUARANTINED`) jest wyświetlany dosłownie ze stanem neutralnym. Wartości niepasujące do
`^[A-Z][A-Z0-9_]{0,39}$` są odrzucane. Kontrakt nie zawiera numeru dokumentu SAP — UI go nie wymyśla
(„No SAP document reference is provided by the backend”) i nie generuje PDF z metadanych.

## 5. Zapytanie OData generowane dla CAP (przygotowane, niezweryfikowane LIVE)

`InboundQuery.toODataQuery` (jedna semantyka z mock):

| Filtr UI | OData |
| --- | --- |
| numer faktury | `contains(tolower(invoiceNumber),'…')` |
| numer KSeF (wielkie litery, `[0-9A-F-]`) | `contains(ksefNumber,'…')` |
| NIP sprzedawcy (separatory usuwane) | 10 cyfr: `sellerNip eq '…'`, prefiks: `startswith(sellerNip,'…')` |
| data wystawienia | `issueDate ge YYYY-MM-DD and issueDate le YYYY-MM-DD` |
| data otrzymania (dni lokalne) | `acquisitionDate ge <ISO> and acquisitionDate lt <ISO dnia+1>` |
| statusy (multi) | `(ingestionStatus eq 'A' or ingestionStatus eq 'B')` itd. |
| środowisko | `environment eq 'TEST'` |

Zawsze: `$select` (lista pól DTO), `$orderby=<pole> asc|desc,id asc`, `$skip`, `$top ≤ 100`, `$count=true`.
Nagłówki: `Accept: application/json;IEEE754Compatible=true`, `OData-MaxVersion: 4.0`; `credentials: same-origin`,
`redirect: error`, timeout 15 s (max 60 s). Odpowiedź bez `value[]`/`@odata.count` lub z więcej niż `$top` wierszami
jest odrzucana (`PARSE`) — nie pokazujemy „pustej” listy. Apostrofy w literałach są podwajane. Błędne filtry są
odrzucane **przed** HTTP z komunikatem i18n.

## 6. UX

- Banery: **DEMO DATA** (ostrzeżenie, tryb mock) oraz informacja o domenie (to nie jest zakładka Purchase).
  Odznaka w nagłówku: *Demo data* / *Read-only* / *Not available*.
- Filtry w zwijanym panelu (rozwinięty na desktopie, zwinięty na telefonie). Tabela `sap.m.Table` z popin
  (telefon/tablet), sticky nagłówek, podświetlenie wiersza: Error (konflikt/niezgodny hash/błąd pobrania/zły XML),
  Warning (nieobsługiwany schemat, niepełne metadane).
- Stany: ładowanie (`busy`), brak danych, brak dopasowań, błąd (`IllustratedMessage` + pasek błędu); błąd czyści
  listę — bez danych nieaktualnych/zastępczych. Odświeżenie czyta listę ponownie (nie kontaktuje KSeF).
- Paginacja: progressive loading „Load more (20 of 26)”, deduplikacja po `id`, limit 1000 wierszy w przeglądarce;
  odpowiedzi starszych żądań są ignorowane.
- Szczegóły: dokument, strony, kwota, statusy pobrania/parsowania/integralności, „XML available”, status SAP,
  ostrzeżenia (konflikt, nieobsługiwany schemat, lista problemów danych). Panel **Planned Functions**: XML Preview,
  Duplicate Check, Link FI/MM Document, Approve and Post — **wyłączone** z powodem w tooltipie.
- Dostępność: nawigacja klawiaturą (`ColumnListItem type=Navigation`), `ariaLabelledBy` tabeli, `labelFor` pól,
  komunikaty tekstowe zamiast samych kolorów.

## 7. Bezpieczeństwo

- Przeglądarka nie łączy się z KSeF, nie zna JWT KSeF, nie ma sekretów w manifeście; adapter ma wyłącznie `GET`.
- Każdy URL (także ścieżka mock) musi mieć **ten sam origin** co aplikacja; `serviceUrl` i `entitySet` walidowane wzorcem.
- Brak `localStorage`/`sessionStorage` dla danych faktur i XML.
- Klucze słowników sprawdzane `hasOwnProperty` (`constructor`/`__proto__` nie trafiają w prototyp).
- **Autoryzacja należy do CAP:** filtr NIP w UI jest wygodą, nie kontrolą dostępu. Faktura widoczna dla jednego NIP-u
  nie może być automatycznie widoczna dla innych — CAP musi filtrować po grantach (`KsefInboundAccessGrants`) i
  tożsamości użytkownika (atrybut XSUAA), zgodnie z [architekturą INBOUND §12](KSEF_INBOUND_ARCHITECTURE.md).

## 8. Podłączenie `KsefService.InboundInvoiceOverview` (instrukcja dla INT-02)

1. **Backend (poza UI5-10):** read-only encja/projekcja w `KsefService`, np. `@readonly entity InboundInvoiceOverview`,
   z polami i typami z §4 (klucz `id: UUID`, `grossAmount: Decimal`, `issueDate: Date`, `acquisitionDate: Timestamp`),
   filtrowana po grantach użytkownika w CAP. Statusy jak w §4 albo jawne mapowanie (pkt 5).
2. **Weryfikacja kontraktu:** pobierz `/odata/v4/KsefService/$metadata` i potwierdź: nazwę zbioru, typ klucza
   `Edm.Guid` (adapter używa literału klucza **bez cudzysłowów** `InboundInvoiceOverview(<uuid>)`), nazwy pól,
   obsługę `contains`/`tolower`/`startswith`, `@odata.count` przy `OData-MaxVersion: 4.0`, `Decimal` jako string przy
   `IEEE754Compatible=true`. Rozbieżność popraw w **jednym** miejscu: `InboundContract.Fields`/`InboundQuery`/`InboundInvoiceMapper`.
3. **Lokalnie (dev):** w manifeście `ksefInbox.dataMode: "cap"` i `ksefInbox.cap.entitySet: "InboundInvoiceOverview"`;
   `ui5-local.yaml` już kieruje `/odata/v4/KsefService` na `http://localhost:8080`. Uruchom `npm run start-local`.
   Domyślny manifest repo zostaw na `mock`, dopóki CAP nie jest zweryfikowany (jak w kokpicie).
4. **Testy kontraktowe:** dodaj QUnit z rzeczywistą (zanonimizowaną) odpowiedzią CAP jako fixture do
   `InboundInboxAdapter`; rozszerz opt-in `test/live/dualBackend.qunit.html` o odczyt Inbox (lista, `$count`,
   szczegóły, 401/403/timeout). Test negatywny: użytkownik bez grantu widzi pustą listę/404, nie cudze faktury.
5. **Statusy:** jeżeli backend użyje innych kodów, dodaj mapowanie w `InboundInvoiceMapper.toRow` (jedno miejsce)
   oraz klucze `inboxStatus_<pole>_<KOD>` w i18n; nieznane kody i tak wyświetlą się dosłownie.
6. **Cloud Foundry (osobna decyzja):** po zweryfikowanym LIVE zmień `transformInboxConfig` w `scripts/build-cf.mjs`
   na `dataMode: "cap"` + `entitySet`, zaktualizuj `verifyArtifact` i testy `build-cf.test.mjs` (obecnie wymuszają
   `disabled`). Trasa approutera `/odata/v4/KsefService/` → `ainvoicer-cap` już istnieje (sprawdzana testem).
7. **Akcje przyszłe** (XML, duplikaty, FI/MM, księgowanie) wymagają osobnych kontraktów CAP, autoryzacji i ADR;
   dopiero wtedy zmień `InboundInboxAdapter.getCapabilities()` (świadomie, z testami) — flaga w manifeście nie wystarczy.

## 9. Uruchamianie i testy

```powershell
npm ci --prefix sapui5/ainvoicer
npm run start-mock --prefix sapui5/ainvoicer            # FLP sandbox, potem przycisk „KSeF Inbox”
npm run serve-mock --prefix sapui5/ainvoicer            # serwer testów (8085)
npm run test:unit --prefix sapui5/ainvoicer             # QUnit
npm run test:opa --prefix sapui5/ainvoicer              # OPA
npm run build --prefix sapui5/ainvoicer
npm run build:cf --prefix sapui5/ainvoicer
npm run test:cf-build --prefix sapui5/ainvoicer
npm run test:runner --prefix sapui5/ainvoicer
```

Jeśli port 8085 jest zajęty przez inny proces, uruchom `npx fiori run --config ./ui5-mock.yaml --port <wolny>` i
`node scripts/browser-test.mjs qunit http://localhost:<port>/test/unit/unitTests.qunit.html`.

Pokrycie: `test/unit/model/InboundInvoiceMapper.js`, `InboundQuery.js`, `InboundFormatter.js`, `InboundInboxAdapter.js`
(tryby, disabled bez HTTP, brak fallbacku, timeout, sieć, HTTP, niekompletna odpowiedź, same-origin, precyzja kwot,
null/niepełne metadane, długie numery, nieobsługiwany schemat, konflikt integralności) oraz OPA
`test/integration/InboundInboxJourney.js` (nawigacja, demo, paginacja, filtry/walidacja/no-match, sortowanie,
szczegóły i wyłączone akcje, błąd odczytu, brak odpowiedzi, CAP bez encji, wariant CF bez HTTP, powrót).
