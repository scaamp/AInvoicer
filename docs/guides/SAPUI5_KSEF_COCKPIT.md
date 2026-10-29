---
title: "SAPUI5 — kokpit faktur KSeF"
date: 2026-10-29
type: guide
status: aktywny
tags: [ainvoicer, sapui5, ksef, cap-java, frontend]
---

# SAPUI5 — kokpit faktur KSeF

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Model RAP faktur](ABAP_RAP_INVOICE_MODEL.md) · [Dziennik Agent C](../journal/2026-10-29-agent-c-ui5.md)

Rozszerzenie istniejącej aplikacji `sapui5/ainvoicer` (SAPUI5 freestyle, SAP Horizon) o kokpit faktur zakupowych i sprzedażowych KSeF 2.0. To nie jest nowa aplikacja: AI Assistant, Voice Input, historia rozmów, FilterBar, tabela SAP FI (`ZC_FI_ACDOCA`), Add/Copy/Delete i Scanner działają jak wcześniej.

**Stan (2026-10-29):** kokpit działa w trybie **demo** na lokalnych danych o kształcie rzeczywistych kontraktów RAP/CAP. Nie istnieje jeszcze bezpieczne API CAP z listą faktur dla UI (luka G-01), więc tryb `cap` jest przygotowany, ale nieuruchamiany. Żadna akcja nie wysyła faktury i nie łączy się z KSeF.

## 1. Układ

```text
Page "Invoices"
├── Panel AI Assistant          (bez zmian: contextSelector, aiCommandInput, voice, historia)
├── Panel Filters
│   ├── FilterBar Legacy         (Company Code / Fiscal Year, widoczny w SAP FI (Legacy))
│   └── FilterBar KSeF           (Search, Origin, Company, KSeF Status, Issue Date, kwoty)
├── IconTabHeader: All Invoices (n) | Purchase Invoices (n) | Sales Invoices (n) | SAP FI (Legacy)
├── MessageStrip trybu danych    (Demo / CAP / Legacy)
├── Tabela KSeF (sap.ui.table)   (nagłówki faktur, toolbar akcji, row action → szczegóły)
└── Tabela Legacy (sap.ui.table) (bez zmian: ZC_FI_ACDOCA, Add / Delete / Copy)
```

Domyślny widok to `All Invoices` (`sap.ui5/config/ksefCockpit/defaultView`). Widok Legacy zachowuje własne bindowanie OData V4; przełączenie tylko zmienia widoczność.

## 2. Struktura kodu

| Plik | Rola |
| --- | --- |
| `webapp/controller/Main.controller.js` | Istniejący kontroler. Dołącza mixin KSeF przez `Object.assign`, wywołuje `_initKsefCockpit()`, kieruje AI Search do adaptera KSeF poza widokiem Legacy. |
| `webapp/controller/KsefCockpit.js` | Mixin kontrolera: modele widoku, filtry, selekcja, akcje, szczegóły, personalizacja, integracja AI. |
| `webapp/model/ksef/KsefContract.js` | Stałe: źródła pól, kierunki, procesy, katalog statusów, kolumny, lista biała pól filtrowalnych. |
| `webapp/model/ksef/KsefInvoiceMapper.js` | Czyste mapowanie nagłówka RAP + operacji CAP na wiersz widoku. |
| `webapp/model/ksef/KsefActionRules.js` | Czyste reguły dostępności akcji z kodami przyczyn i luk. |
| `webapp/model/ksef/KsefFilterBuilder.js` | Filtry klienckie z paska filtrów i przełącznika widoku. |
| `webapp/model/ksef/KsefAiFilterAdapter.js` | Bezpieczne tłumaczenie odpowiedzi `/filter` na filtry tabeli KSeF. |
| `webapp/model/ksef/KsefDataAdapter.js` | Źródło danych: `mock` (JSON) lub `cap` (CAP OData V4 przez `fetch` w sesji aplikacji). |
| `webapp/model/formatter.js` | Daty, kwoty, NIP, stany `ObjectStatus`, teksty statusów. |
| `webapp/view/KsefFilterBar.fragment.xml`, `KsefInvoiceTable.fragment.xml` | Fragmenty osadzone w `Main.view.xml`. |
| `webapp/view/KsefInvoiceDetails.fragment.xml`, `KsefColumns.fragment.xml`, `KsefActionAvailability.fragment.xml` | Dialog szczegółów, personalizacja kolumn, popover przyczyn. Ładowane leniwie. |
| `webapp/localService/ksef/ZC_AINV_INV_H.json`, `KsefOperations.json` | Dane demo (opis w sekcji 4). |
| `scripts/browser-test.mjs` | Runner QUnit/OPA/smoke w headless Chrome/Edge (CDP), bez zależności npm. |

## 3. Modele

| Model | Typ | Zawartość |
| --- | --- | --- |
| `""` (domyślny) | OData V4 | Legacy `ZC_FI_ACDOCA` (`/sap/opu/odata4/sap/zui_fi_acdoca_o4/...`). Bez zmian. |
| `ksef` | JSON | `/invoices` — wiersze widoku (sekcja 5). Filtrowanie i sortowanie po stronie klienta. |
| `ksefView` | JSON | Widok (`viewKey`, `legacyMode`), `busy`, `error`, liczniki, kolumny, stan filtrów, listy wartości, `actions/<akcja>/{enabled,tooltip,reason}`. |
| `ksefDetails` | JSON | Dialog szczegółów: `invoice`, `operations`, stany ładowania i błędów. |
| `history`, `search`, `file` | JSON | Istniejące modele AI Assistant i Scanner. Bez zmian. |

## 4. Tryby danych i konfiguracja

`manifest.json → sap.ui5/config/ksefCockpit`:

```json
{
  "dataMode": "mock",
  "targetEnvironment": "TEST",
  "defaultView": "ALL",
  "mock": { "headersPath": "...ZC_AINV_INV_H.json", "operationsPath": "...KsefOperations.json" },
  "cap": {
    "serviceUrl": "/odata/v4/KsefService/",
    "invoiceListUrl": null,
    "invoiceDetailsUrl": null,
    "actions": { "send": null, "sync": null, "xml": null, "upo": null, "duplicate": null }
  },
  "capabilities": { "sendInvoice": false, "syncInbound": false, "viewXml": false, "downloadUpo": false, "checkDuplicate": false }
}
```

- **mock** (domyślny): `ZC_AINV_INV_H.json` ma kształt odpowiedzi `ZC_AINV_INV_H?$expand=_Items,_References` z H01 (`ZAPI_AINV_INV_H_O4/0001`; zweryfikowano z `cap-java/srv/external/AinvoicerH01.edmx`). Cztery rekordy `AINV-TEST-SEED-2026-*` mają wartości z `ZCL_AINV_TEST_DATA` (UUID syntetyczne). Cztery rekordy `AINV-DEMO-*` istnieją tylko w pliku i pokazują stany ACCEPTED, PROCESSING, REJECTED i RECEIVED. `KsefOperations.json` ma kształt `KsefService.KsefOperations`. W tym trybie UI nie zmienia stanu żadnej akcji.
- **cap**: lista wymaga endpointu G-01 (`invoiceListUrl`). Bez niego tabela pokazuje błąd *contract G-01 is missing* zamiast pustych lub fikcyjnych danych. Historia i kontrola statusu korzystają z istniejącego `GET KsefService/KsefOperations?$filter=sapDocumentRef eq '<uuid>'`.
- Akcja zmieniająca stan jest aktywna tylko wtedy, gdy w trybie `cap` jest włączona flaga `capabilities.*` **oraz** skonfigurowany endpoint. Docelowe środowisko inne niż TEST/DEMO zawsze blokuje akcje w UI.
- Przeglądarka używa wyłącznie sesji aplikacji (`credentials: same-origin`, CSRF przez `X-CSRF-Token: Fetch`). Nie ma nagłówka `Authorization`, tokenów KSeF, JWT ani poświadczeń SAP w JS. Wewnętrzny CAP RemoteService `AinvoicerH01` (`@requires: 'internal-user'`) nie jest wywoływany z UI.
- **Przed wdrożeniem produkcyjnym** ustaw `dataMode: "cap"`. Pliki demo trafiają do `dist/`, ale baner wyraźnie oznacza tryb demo.

## 5. Mapowanie pól

| Kolumna | Pole wiersza | Źródło | Pochodzenie / uwagi |
| --- | --- | --- | --- |
| Invoice Number | `InvoiceNumber` | RAP | `ZC_AINV_INV_H.InvoiceNumber` (35) |
| Direction | `Direction` | RAP | `INBOUND` / `OUTBOUND` |
| Origin Process | `OriginProcess` | RAP | `FI_AP`, `FI_AR`, `MM_IV`, `SD_BILL` (kody scenariuszy) |
| Company Code | `CompanyCode` | RAP | ukryta domyślnie |
| Seller | `SellerName`, `SellerNip` | RAP | NIP formatowany wyłącznie do prezentacji |
| Buyer | `BuyerName`, `BuyerNip` | RAP | jw. |
| Issue Date | `IssueDate` | RAP | `Edm.Date` jako `YYYY-MM-DD`, formatowane bez przesunięcia strefy |
| Gross Amount | `GrossAmount` | RAP | `Edm.Decimal` (string) → liczba do sortowania i filtrów; wyświetlane z walutą |
| Currency | `CurrencyCode` | RAP | ukryta domyślnie |
| SAP Status | `InvoiceStatus` | RAP | domena nieopublikowana; stan neutralny, bez logiki akcji |
| KSeF Status | `KsefStatus` | CAP / RAP / UI | najnowsza operacja CAP (`status`) → `RECORDED_IN_SAP` (gdy RAP ma `KsefNumber`) → `NO_OPERATION` |
| KSeF Number | `KsefNumber` | RAP, potem CAP | `ZC_AINV_INV_H.KsefNumber` (writeback `ApplyKsefResult`), w przeciwnym razie `KsefOperations.ksefNumber` |
| KSeF Environment | `KsefEnvironment` | RAP, potem CAP | wyłącznie prezentacja; PROD wyróżniony stanem Error |
| Operation Reference | `OperationReference` | CAP | `KsefOperations.referenceNumber` najnowszej operacji |
| Last Update | `LastUpdate` | UI | najnowszy z `LocalLastChangedAt`, `LastChangedAt`, `KsefRecordedAt` i `modifiedAt` operacji |

Korelacja: `KsefOperations.sapDocumentRef` = RAP `InvoiceUUID` (porównanie bez rozróżniania wielkości liter; zgodnie z `SapKsefResultWriteback`). Najnowsza operacja jest wybierana po `modifiedAt` lub `createdAt`.

Szczegóły zawierają dodatkowo: `InvoiceUUID`, `InvoiceKind`, `PostingDate`, `DueDate`, `NetAmount`, `TaxAmount`, `_Items` (ItemNo, MaterialId, Description, Quantity/UnitCode, UnitPrice, TaxRate/TaxCategory, GrossAmount), `_References` (ReferenceRole, SourceDocNo, SourceSystem/Module), cztery pola wyniku KSeF w SAP (`KsefNumber`, `KsefEnvironment`, `KsefRecordedAt`, `KsefWriteRequestId`) i historię operacji CAP.

### Statusy

| Status | Stan UI | Potwierdzenie |
| --- | --- | --- |
| `NEW` | Information | potwierdzony w kodzie CAP (`KsefOperationsHandler`) |
| `ACCEPTED` | Success | potwierdzony (`SapKsefResultWriteback`) |
| `QUEUED`, `PROCESSING`, `SUBMITTED` | Information / Warning | oczekiwane (Agent A) |
| `REJECTED`, `FAILED` | Error | oczekiwane |
| `RECEIVED` | Success | oczekiwany dla odbioru |
| `RECORDED_IN_SAP`, `NO_OPERATION` | Success / None | wyliczane wyłącznie w UI |
| inny | None | wyświetlany dosłownie; akcje dla nieznanego stanu są blokowane |

## 6. Dostępność akcji

Reguły: `KsefActionRules.evaluateAll(invoice, capabilities, targetEnvironment, viewKey)`. Przyczyna niedostępności jest w tooltipie przycisku i w popoverze *Action availability* (ikona `hint`). Handler sprawdza regułę ponownie przed wykonaniem (`_ksefGuard`).

| Akcja | Warunki | Dziś (mock) |
| --- | --- | --- |
| Refresh | zawsze | ponowny odczyt adaptera |
| Synchronize with KSeF | globalna; nie w widoku Sales; capability `syncInbound` | wyłączona — G-04 |
| Send to KSeF | zaznaczenie; `OUTBOUND`; brak numeru KSeF; brak operacji `SEND_INVOICE` w toku (NEW/QUEUED/PROCESSING/SUBMITTED), `ACCEPTED`, `FAILED` lub nieznanej; po `REJECTED` dozwolona z dodatkowym ostrzeżeniem; target TEST/DEMO; capability `sendInvoice` | wyłączona — G-02 |
| Check Status | zaznaczenie; istnieje operacja; odczyt CAP | **aktywna** — wyłącznie odczyt statusu z CAP |
| View XML | zaznaczenie; istnieje dokument (wysyłka lub numer KSeF); capability `viewXml` | wyłączona — G-05 |
| Download UPO | zaznaczenie; `OUTBOUND`; `ACCEPTED` lub `RECORDED_IN_SAP`; capability `downloadUpo` | wyłączona — G-06 |
| View Details | zaznaczenie (lub row action) | aktywna |
| Operation History | zaznaczenie; odczyt CAP | aktywna (dialog, zakładka History) |
| Check Duplicate (szczegóły → XML/UPO) | zaznaczenie; `INBOUND`; capability `checkDuplicate` | wyłączona — G-08 |

Akcje zmieniające stan po udostępnieniu kontraktu: potwierdzenie (`MessageBox.confirm`, domyślnie *Cancel*), `BusyIndicator`, dokładnie jeden POST bez ponowień po stronie klienta, a następnie komunikat z **statusem zwróconym przez serwer** („This is not a KSeF acceptance”) i odświeżenie listy. UI nigdy nie przedstawia przyjęcia zlecenia jako przyjęcia faktury.

## 7. AI Assistant

- Kontrolki, kontekst, historia, voice input, TTS i link *View filters* bez zmian. Endpoint Python `/filter` i jego kontrakt (`{queryText}` → `{description, filters[], sortBy, limit}`) bez zmian.
- **SAP FI (Legacy):** dotychczasowy mechanizm (bindowanie `/ZC_FI_ACDOCA`, filtry, sort, limit, domyślna waluta PLN).
- **Widoki KSeF:** ta sama odpowiedź przechodzi przez `KsefAiFilterAdapter` i trafia wyłącznie do filtrów klienckich JSON tabeli KSeF:
  - lista biała pól (`KsefContract.FilterableFields`) i operatorów (`EQ NE GT GE LT LE BT Contains StartsWith EndsWith`);
  - walidacja typów: liczba przez `Number()`, więc `"1; DROP…"` jest odrzucane; data musi mieć format `YYYY-MM-DD`; string do 100 znaków; enumy pisane wielkimi literami; `hasOwnProperty` chroni przed `__proto__`;
  - pole `sql` i nieznane pola są ignorowane; nic nie trafia do bazy ani backendu.
  - mapowanie ścieżek Legacy → KSeF: `CompanyCode`, `CurrencyCode`, `PostingDate` → dokładne; `DocumentDate`→`IssueDate`, `AmountDocument`→`GrossAmount`, `Reference`→`InvoiceNumber`, `FiscalYear EQ rrrr`→`IssueDate BT rrrr-01-01..rrrr-12-31` → **przybliżone** (oznaczone w odpowiedzi); `DocumentNo`, `Vendor`, `Customer`, `Account` i pozostałe → ignorowane z informacją.
  - Asystent odpowiada: opis usługi + „Applied to the KSeF table: …” + „Not supported in the KSeF view and ignored: …”. **Obecna usługa nie rozumie pól KSeF (NIP, kierunek, status KSeF)** — adapter jest gotowy na natywne nazwy, gdy usługa zacznie je zwracać (G-09).
  - Filtry AI usuwa przycisk *Clear* na pasku filtrów KSeF.
- Poprawki błędów istniejącego przepływu: konteksty *Data Analysis* / *Help* rzucały `ReferenceError` i zostawiały otwarty `BusyDialog`; błąd sieci lub HTTP również zostawiał `BusyDialog`; podwójne zdarzenie `search` (Enter + change) otwierało drugi, nigdy niezamykany `BusyDialog`. Teraz obowiązuje jedno zapytanie naraz, a odpowiedź lub błąd zawsze trafia do historii.

## 8. Scanner

- `AddInvoice.fragment.xml`, FileUploader (PDF/JPG/PNG), *Analyze*, wywołanie Node `/analyze` (`{image_url}` → `{result}`) i `_fillFormWithAIData` bez zmian merytorycznych.
- W toolbarze KSeF przycisk **Scan Invoice (Legacy FI)** otwiera ten sam dialog. Dialog informuje, że *Save* tworzy pozycję SAP FI w `ZC_FI_ACDOCA`, a **nie** fakturę KSeF. Pola ACDOCA nie są mapowane na model RAP faktur.
- Poprawki: widoczny status analizy; zamknięcie `BusyDialog` przy błędzie HTTP lub parsowania (wcześniej `reject` był poza zasięgiem i powodował `ReferenceError`); odporność na brak `AmountDocument/AmountLocal`; model JSON ustawiany przed `addDependent` (koniec błędów `Failed to read path /CompanyCode`); pole waluty używa typu `String` zamiast typu złożonego `Currency` (`FormatException`, puste pole po analizie AI).

## 9. Backend contract gaps (dla Agenta A)

| ID | Potrzeba UI | Stan dziś | Proponowany kontrakt CAP (do decyzji Agenta A) |
| --- | --- | --- | --- |
| G-01 | Lista nagłówków faktur dla UI | Brak. `AinvoicerH01` jest `internal-user`; publiczny `KsefService` ma tylko `KsefOperations`/`KsefInvoices` (bez danych nagłówka SAP). | Read-only encja np. `KsefCockpitService.Invoices` (`@requires: 'KsefOperator'`) nad Remote RAP `ZC_AINV_INV_H`, filtrowana serwerowo po `SellerNip`/`BuyerNip` ∈ `$user.taxpayerNips` (DCL RAP `where TRUE` nie izoluje NIP), pola jak w sekcji 5, `$top/$skip/$filter/$orderby`. Opcjonalnie wyliczone `KsefStatus` i `LatestOperation*`. |
| G-02 | Zlecenie wysyłki | Brak akcji. Istniejący `CREATE KsefOperations` przyjmuje od klienta `sapDocumentRef`, `taxpayerNip` i `idempotencyKey` bez weryfikacji z SAP, więc UI go **nie używa**. | Bound action `Invoices(<uuid>)/requestSend()` → zwraca `KsefOperations` (NEW/QUEUED). Serwer: odczyt SAP, `OUTBOUND`, środowisko TEST, idempotencja serwerowa, snapshot/FA(3), outbox po commit; HTTP 409 dla operacji w toku. |
| G-03 | Wymuszenie kontroli statusu | Odczyt `KsefOperations` działa (UI z niego korzysta). Brak triggera. | Opcjonalnie `requestStatusCheck()`; inaczej worker CAP odpytuje sam, a UI tylko czyta. |
| G-04 | Synchronizacja zakupów z KSeF | Brak | Unbound action `requestInboundSync(from, to)` → operacja `RECEIVE_INVOICE`; NIP z kontekstu użytkownika. |
| G-05 | Podgląd XML | `xmlStorageRef` celowo nie jest publikowany | `GET Invoices(<uuid>)/xml` (`application/xml`, stream) z autoryzacją NIP. |
| G-06 | Pobranie UPO | `upoStorageRef` nie jest publikowany | `GET Invoices(<uuid>)/upo` (XML/PDF) po `ACCEPTED`. |
| G-07 | Oś czasu operacji | `KsefOperations` READ + `$filter=sapDocumentRef eq …` działa; `SapResultWriteHistory` nie jest publiczna | Opcjonalnie read-only `KsefOperationEvents` (bez danych wrażliwych). |
| G-08 | Duplikaty / dopasowanie zakupów | Brak | `Invoices(<uuid>)/checkDuplicate()` → wynik dopasowania. |
| G-09 | AI Search dla pól KSeF | Python `/filter` zna tylko `ZC_FI_ACDOCA` | Rozszerzenie usługi AI (osobny branch) o katalog pól z sekcji 5; UI jest gotowe na natywne ścieżki. |
| G-10 | Szczegóły z pozycjami i referencjami | Brak publicznego API | `Invoices(<uuid>)?$expand=Items,References` (to samo źródło co G-01). |
| G-11 | Katalog statusów | Potwierdzone tylko `NEW`, `ACCEPTED` | Opublikowany enum/stała statusów operacji i ich terminalności. |
| G-12 | Routing i autoryzacja UI → CAP | Brak approutera/`xs-app.json` dla `/odata/v4/` w `sapui5/` | Trasa approutera do CAP, rola `KsefOperator` i atrybut `taxpayerNips` w XSUAA. |

Po wdrożeniu G-01 i G-02 wystarczy konfiguracja w manifeście (`dataMode: "cap"`, `invoiceListUrl`, `actions.send`, `capabilities.sendInvoice: true`) i ewentualna korekta mapowania w `KsefInvoiceMapper`.

## 10. Uruchamianie

```powershell
cd sapui5/ainvoicer
npm ci
npm run build          # ui5 build → dist/
npm run serve-mock     # http://localhost:8085/index.html (UI5 1.133.3 z ui5.sap.com, legacy OData z mockservera)
npm run start-mock     # jak wcześniej, z FLP sandbox i automatycznym otwarciem przeglądarki
```

`npm start` (proxy do H01) wymaga poświadczeń destination; kokpit KSeF działa wtedy nadal w trybie demo.

## 11. Testy

```powershell
npm run serve-mock     # w osobnym terminalu
npm run test:unit      # QUnit: webapp/test/unit/unitTests.qunit.html
npm run test:opa       # OPA5:  webapp/test/integration/opaTests.qunit.html
node scripts/browser-test.mjs smoke http://localhost:8085/index.html smoke.png
```

Runner potrzebuje Chrome lub Edge (`CHROME_PATH` opcjonalnie) i Node ≥ 22. Kod wyjścia 2 oznacza problem środowiska (brak przeglądarki, timeout), a nie błąd aplikacji.

| Zestaw | Zakres |
| --- | --- |
| Unit `KsefInvoiceMapper` | mapowanie RAP, Decimal, wybór najnowszej operacji, `RECORDED_IN_SAP`, `_Items/_References` |
| Unit `KsefActionRules` | wszystkie akcje, kierunki, statusy, blokada PROD, luki G-xx |
| Unit `KsefFilters` | widoki, wyszukiwanie, zakresy, liczniki; adapter AI: mapowanie, walidacja, odrzucenia, sort/limit |
| Unit `KsefDataAdapter` | mock, `BACKEND_MISSING` bez HTTP, filtr historii CAP, błędy HTTP/sieci, CSRF, jeden POST |
| Unit `formatter`, `Main` | formatowanie; obecność metod Legacy, AI, Scanner i KSeF |
| OPA KSeF | widok domyślny, przełączanie 4 widoków, filtr, sort, dostępność akcji, szczegóły/historia, kolumny, obsługa błędu ładowania |
| OPA AI + Scanner | kontrolki AI, `/filter` → tabela KSeF (atrapa `fetch`), Legacy AI → `ZC_FI_ACDOCA`, nieobsługiwany kontekst, HTTP 500, dialog skanera i mapowanie `/analyze` |

Wyniki konkretnych przebiegów są w [dzienniku](../journal/2026-10-29-agent-c-ui5.md). Testy OPA używają mockservera i atrap usług AI; **to nie jest E2E** z CAP, SAP ani KSeF.
