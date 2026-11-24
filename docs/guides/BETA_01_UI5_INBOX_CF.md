---
title: "BETA-01 — SAPUI5 KSeF Inbox w wariancie Cloud Foundry (opt-in)"
date: 2026-11-23
type: guide
status: aktywny
tags: [ainvoicer, sapui5, ksef, inbound, cloud-foundry, approuter, xsuaa, beta-01]
---

# BETA-01 — SAPUI5 KSeF Inbox w wariancie Cloud Foundry (opt-in)

[Project Home](../README.md) · [KSeF Inbox (UI5-10)](KSEF_INBOX_UI5.md) · [INT-02 kontrakt CAP](INT_02_INBOUND_FULLSTACK.md) · [CAP-07E security](CAP_07E_SECURITY_ACCEPTANCE.md)

> **Stan (2026-11-23):** wariant zbudowany i zweryfikowany **wyłącznie offline/lokalnie**. Brak deploymentu CF,
> brak testu z XSUAA/AppRouterem LIVE, brak KSeF, brak SAP H01, brak HANA. Artefakt beta nie jest zgodą na wdrożenie.

## 1. Cel i zakres

Domyślny artefakt CF (`npm run build:cf` → `dist-cf/`) nadal ma Inbox **wyłączony** (`dataMode=disabled`, zero HTTP).
BETA-01 dodaje **jawny, opt-in** wariant builda, w którym KSeF Inbox czyta read-only
`KsefService.InboundInvoiceOverview` przez istniejącą trasę AppRouter `^/odata/v4/KsefService/(.*)$`
(`authenticationType: xsuaa`, `csrfProtection: true`, destination `ainvoicer-cap`). Kokpit RAP i jego funkcje są bez zmian.

| Element | `build:cf` (domyślny) | `build:cf:inbox-beta` |
| --- | --- | --- |
| Katalog wyniku / staging | `dist-cf/` / `build/cf-webapp/` | `dist-cf-inbox-beta/` / `build/cf-inbox-beta-webapp/` |
| Konfiguracja ui5 | `ui5-cf.yaml` | `ui5-cf-inbox-beta.yaml` |
| `ksefCockpit` | `rap`, wszystkie capabilities `false`, Legacy/AI OFF, flex OFF | **identyczny** |
| `ksefInbox.dataMode` | `disabled` | `cap` |
| `ksefInbox.cap.entitySet` | `null` | `InboundInvoiceOverview` |
| `ksefInbox.cap.readTimeoutMs` | brak (adapter: 15 s) | `15000` (max 60000) |
| `ksefInbox.environments` | ze źródła (`TEST`, `DEMO`) | `["TEST"]` (zakres backendu v1; nigdy PROD) |
| `ksefInbox.capabilities` | wszystkie `false` | wszystkie `false`; `viewXml=true` **tylko** z `--with-xml-download` |
| `mock`, `localService/`, `test/` | brak | brak |
| CSP w `index.html` | `connect-src 'self'; form-action 'self'; base-uri 'self'; object-src 'none'` | identyczna |
| Lista plików | 71 | 71 (te same ścieżki) |

Rzeczywista różnica zbudowanych manifestów (`dist-cf` → `dist-cf-inbox-beta`, 2026-11-23):

```text
/sap.ui5/config/ksefInbox/dataMode         : "disabled" -> "cap"
/sap.ui5/config/ksefInbox/environments/1   : "DEMO"     -> (brak)
/sap.ui5/config/ksefInbox/cap/entitySet    : null       -> "InboundInvoiceOverview"
/sap.ui5/config/ksefInbox/cap/readTimeoutMs: (brak)     -> 15000
```

Manifest domyślnego `dist-cf/` po zmianach jest **bajt w bajt identyczny** z wynikiem sprzed BETA-01
(porównanie `cmp` manifestu, `index.html` i listy 71 plików). Polecenie `build:cf` w `package.json`, `mta.yaml` i
skryptach deploy się nie zmieniło.

## 2. Budowanie

```powershell
npm ci --prefix sapui5/ainvoicer
npm run build:cf:inbox-beta --prefix sapui5/ainvoicer                          # viewXml OFF
npm run build:cf:inbox-beta --prefix sapui5/ainvoicer -- --with-xml-download   # po integracji backendu XML
```

`scripts/build-cf.mjs` przyjmuje wyłącznie `--variant=default|inbox-beta` i `--with-xml-download`. Każdy inny argument,
`--with-xml-download` bez wariantu beta albo nie-boolean `xmlDownload` przerywa build (exit 1). Źródłowy manifest z
włączoną capability Inbox/kokpitu, bez sekcji `ksefInbox` lub z obcym `cap.serviceUrl` jest odrzucany, a nie „naprawiany”.
Transformacja tworzy sekcję Inbox z listy białej pól — `mock` i nieznane klucze nie przechodzą. Naruszenie weryfikacji
usuwa katalog wyniku (brak częściowego artefaktu). Integracja z MTA/AppRouterem (`mta.yaml` kopiuje `dist-cf`) **nie**
została zmieniona; użycie bety w MTAR wymaga osobnej, świadomej decyzji orkiestratora.

## 3. Co sprawdza `verifyArtifact(dir, { variant: "inbox-beta", xmlDownload })`

Wspólne z wariantem domyślnym: brak `test/`, `localService/`, `*.qunit.*`, testsuite/flp, nieoczekiwanych JSON (fixture),
odwołań do `InboundInvoices.json`, wzorców sekretów (klucz prywatny, `sk-…`, Basic, JWT, `clientsecret`), CSP
same-origin w `index.html`, `ksefCockpit.dataMode=rap`, wszystkie capabilities kokpitu `false`, `externalServices` OFF
(manifest i `Component-preload.js`).

Dodatkowo beta (manifest **oraz** manifest osadzony w `Component-preload.js`, parsowany jako JSON):

- `ksefInbox` ma dokładnie klucze `dataMode, pageSize, environments, cap, capabilities` (brak `mock`),
  `dataMode=cap`, `pageSize` 1..100, `environments=["TEST"]`;
- `cap` = dokładnie `{ serviceUrl: "/odata/v4/KsefService/", entitySet: "InboundInvoiceOverview", readTimeoutMs ≤ 60000 }`;
- `capabilities` = dokładnie oczekiwany obiekt (wszystko `false`, `viewXml` zgodne z flagą);
- `"dataMode": "mock"` nigdzie w plikach tekstowych; `"…": true` dla akcji mutujących nigdzie
  (`viewXml` wyłączony z tej reguły wyłącznie przy `--with-xml-download`, a wtedy sprawdzany dokładnie w `ksefInbox`);
- kokpit w preload: `rap` i wszystkie capabilities `false`; brak preload lub nieczytelny manifest = błąd;
- obecny `model/ksef/inbound/InboundInboxAdapter.js`.

Weryfikator domyślny odrzuca artefakt beta (i odwrotnie) — sprawdzone testem.

## 4. Zachowanie UI (stany)

| Stan | Zachowanie |
| --- | --- |
| Ładowanie | `busy` tabeli, przyciski Sort/Refresh nieaktywne, pusty stan „Loading the inbox” |
| Lista | `sap.m.Table`, `$top` = 20 (max 100), „Load more (n of m)” → `$skip`, deduplikacja po `id`, limit 1000 wierszy |
| Filtrowanie / sortowanie | jak UI5-10 (walidacja przed HTTP, sort z listy białej + `id asc`) |
| Pusty Inbox | „No received invoices” (`noData`); brak dopasowań → „No invoices match the filters” |
| 401 lub strona logowania AppRoutera | „You are not signed in or your session has expired. Sign in again…” + link **Sign in again** (przeładowanie strony → XSUAA) |
| 403 | „You are not authorized to read the KSeF Inbox. Ask your administrator for the KSeF operator role.” |
| 404 listy | „The inbox service was not found (HTTP 404)…” (brak usługi, nie „brak faktury”) |
| 5xx | „…temporarily unavailable (HTTP 5xx). No data is shown.” — bez treści błędu serwera |
| Sieć / timeout | „cannot be reached (network error)” / „did not respond in time” |
| Odświeżenie | przycisk **Refresh** (czyta CAP ponownie, nie kontaktuje KSeF) |
| Szczegóły | dialog z dokumentem, stronami, statusami pobrania/parsowania/integralności, „XML Available”, „Original XML” |

Każdy błąd czyści listę; nigdy nie ma przejścia na dane mock. Badge „Read-only”, bez paska „DEMO DATA”.
Adapter wysyła `X-Requested-With: XMLHttpRequest`: AppRouter dla takich żądań odpowiada **401** zamiast strony
przekierowania do XSUAA (zachowanie zmierzone LIVE w CAP-07E/CAP-HANA-01, nie w tym zadaniu). Odpowiedź `text/html` z 2xx jest
i tak klasyfikowana jako `LOGIN_REQUIRED`, nigdy jako dane.

**XSS:** wartości z XML (`invoiceNumber`, `sellerName`, `buyerName`…) są renderowane wyłącznie przez `Text`,
`ObjectIdentifier`, `ObjectStatus`, `Input` i tytuł dialogu — bez `FormattedText`/`core:HTML`. Test QUnit sprawdza źródła
widoków oraz renderowanie złośliwych wartości (`<img onerror>`, `<script>`, `<svg onload>`) w dialogu; test OPA — w tabeli.

i18n: projekt ma jeden bundle `i18n/i18n.properties` (EN). Nowe klucze: `inboxErrorUnauthorized`, `inboxErrorForbidden`,
`inboxErrorServiceNotFound`, `inboxErrorServer`, `inboxSignInAgain`, `inboxDetailsOriginalXml`, `inboxDownloadXmlTooltip`,
`inboxXmlDownloadNotInVersion`, `inboxXmlDownloadNotStored`, `inboxXmlDownloaded`, `inboxXmlDownloadError`,
`inboxXmlErrorNotFound`; `inboxActionViewXml` = „Download XML”. Bundle PL nie istnieje w projekcie (nie dodano).

## 5. Download XML — status

Kontrakt docelowy (backend: Agent B, **niezintegrowany w tej gałęzi**): `GET /odata/v4/KsefService/InboundInvoiceXml(<uuid>)/content`
→ oryginalny XML jako attachment; sesja AppRoutera, GET bez CSRF.

- Przycisk **Download XML** (szczegóły → „Original XML”) jest widoczny i aktywny wyłącznie gdy
  `ksefInbox.capabilities.viewXml === true` (adapter: tylko tryb `cap`) **oraz** wiersz ma `xmlAvailable === true`.
  W pozostałych przypadkach zamiast przycisku jest tekst: „XML download is not available in this version.” albo
  „The original XML is not stored for this invoice…”. Brak placeholdera udającego pobieranie; tryb mock nigdy nie włącza pobrania.
- URL budowany wyłącznie z wewnętrznego `id` walidowanego wzorcem UUID (`InboundInvoiceMapper.UUID_PATTERN`), małe
  litery, literał klucza bez cudzysłowów (jak key GET overview zweryfikowany w INT-02; dla `InboundInvoiceXml` **do potwierdzenia
  z `$metadata` po integracji backendu**). Nigdy ze `storageRef`/innych pól odpowiedzi.
- Pobranie: `fetch` GET same-origin, `redirect: error`, timeout, `Accept: application/xml`; dozwolony typ XML lub
  `application/octet-stream`, niepusty, ≤ 10 MiB; `text/html` = `LOGIN_REQUIRED`; 403/401/404 → komunikat w dialogu.
  Zapis przez tymczasowy URL `blob:` (zwalniany po 10 s), bez `localStorage` i bez renderowania XML w stronie.
  Nazwa pliku: `KSeF-<numer KSeF>.xml` (tylko `[0-9A-Za-z-]`), inaczej `KSeF-inbound-<uuid>.xml`.
- Informacja orkiestratora (2026-11-23, nie weryfikowana w tej gałęzi): backend Agenta B wystawia media stream CAP z
  `Content-Disposition: attachment` i `Cache-Control: no-store`; flaga `ainv.ksef.inbound.xml-download.enabled`
  domyślnie `false` → 404 (UI: „The stored XML was not found or you are not authorized…”), niepodłączony store → 503
  (UI: „…temporarily unavailable (HTTP 503)”). UI akceptuje `Content-Type` XML (`application/xml`, `text/xml`, `*+xml`) lub
  `application/octet-stream`; inny typ = błąd — do sprawdzenia w E2E po integracji.
- Domyślnie **OFF w obu buildach**. Orkiestrator włącza po integracji backendu jedną flagą:
  `npm run build:cf:inbox-beta -- --with-xml-download` (stała w kodzie: opcja `xmlDownload` w `resolveBuildOptions`).

## 6. Testy (wyniki 2026-11-23, worktree `beta-01/ui5-inbox`, Node 24.19.0, Chrome headless)

| Polecenie | Wynik |
| --- | --- |
| `npm ci` | PASS (exit 0) |
| `npm run test:cf-build` | **23/23 PASS** (13 dotychczasowych + 10 BETA-01; w tym realne `dist-cf` i `dist-cf-inbox-beta`) |
| `npm run test:runner` | **8/8 PASS** |
| `npm run build` | PASS |
| `npm run build:cf` | PASS, 71 plików; manifest/index/lista plików identyczne z baseline |
| `npm run build:cf:inbox-beta` | PASS, 71 plików, `viewXml=false` |
| `npm run build:cf:inbox-beta -- --with-xml-download` | PASS, 71 plików, `viewXml=true` (pozostałe `false`) |
| QUnit (`serve-mock` 8085 + `browser-test.mjs`) | **121 testów, 732/732 asercji PASS** (baseline 112) |
| OPA | **24 testy, 193/193 asercji PASS** (baseline 21) |
| `npm run test:inbox:e2e` (prawdziwy CAP/H2 + UI5) | patrz §6.1 |

Nowe testy: adapter (capability `viewXml` tylko CAP + jawne `true`, URL z UUID, odrzucenie niepoprawnych id/`storageRef`,
brak HTTP bez capability, klasyfikacja HTML/401/403/404/typ/rozmiar, nazwa pliku, `X-Requested-With`, 404 listy);
XSS (źródła widoków + render dialogu); OPA (401 + link, strona logowania, 403, 5xx bez szczegółów serwera, pusty Inbox,
XSS w tabeli, przycisk XML: brak przy `viewXml=false`, brak przy `xmlAvailable=false`, pobranie z dokładnym URL, błąd 403 w dialogu).

### 6.1 Lokalny E2E z rzeczywistym CAP

`scripts/prepare-inbox-local.mjs` buduje teraz lokalny profil z **tej samej transformacji `inbox-beta`** (jedyna różnica:
`pageSize=2` dla paginacji 5 rekordów), więc E2E sprawdza konfigurację bety na prawdziwym HTTP CAP/H2. `InboundCapAccess`
dodatkowo sprawdza czytelne komunikaty: anonim (401) → „Sign in again” + link; brak roli (403) → „not authorized to read the KSeF Inbox”.

Wynik 2026-11-23: `mvn -B clean verify '-Dcds.cds.skipVersionCheck=true'` w `cap-java/` (JDK 25.0.4, Maven 3.9.16)
**BUILD SUCCESS, 671 testów, 0 failures, 6 skipped**; następnie `npm run test:inbox:e2e` **exit 0**: **16 testów, 77/77 asercji**
(baseline 16/73; +4 asercje komunikatów 401/403) — A (3/22), B, brak grantu, brak roli (403), anonim (401), outage (2/5),
restart (3/22). Wyłącznie loopback, syntetyczne dane, bez SDK/KSeF/SAP/BTP. Pobranie XML nie jest objęte E2E (brak backendu).

### 6.2 BETA-01.1 — obowiązkowa weryfikacja w GitHub Actions

Workflow `Offline security and smoke build` wymaga kolejno `build:cf`, `build:cf:inbox-beta` oraz
`build:cf:inbox-beta -- --with-xml-download`. Obie odmiany beta zapisują do `dist-cf-inbox-beta/`, więc każda
przechodzi weryfikację przed nadpisaniem. Build zwraca błąd, gdy brakuje `manifest.json`, `index.html` lub
`Component-preload.js` albo gdy ich zawartość nie spełnia reguł wariantu. Verifier kontroluje oba manifesty,
fixture i wzorce sekretów. `sendInvoice`, `syncInbound` i `approveAndPost` są OFF we wszystkich wariantach.
Domyślny Inbox jest `disabled`, a w becie `viewXml` wymaga jawnego przełącznika. Testy `test:cf-build` sprawdzają
weryfikator również na niekompletnych artefaktach. Dowód wykonania znajduje się w checkach PR #29 dla jego HEAD.
[Workflow](../../.github/workflows/ci.yml) · [PR #29](https://github.com/scaamp/AInvoicer/pull/29/checks).

## 7. Czego NIE zweryfikowano

- Brak deploymentu CF, brak MTAR z betą, brak testu z XSUAA/AppRouterem LIVE (401 dla `X-Requested-With`, wygaśnięcie sesji,
  link „Sign in again” → XSUAA) — tylko atrapa w OPA i 401 lokalnego CAP w E2E.
- Test przeglądarkowy downloadu nadal używa atrapy fetch. Zintegrowany CAP potwierdza `$metadata`, bajty XML,
  nagłówki, autoryzację i odrzucenie naruszeń integralności przez lokalne HTTP na H2.
- Brak HANA/trwałego storage XML, SSO A/B na CF, KSeF, SAP H01.
- Przeglądarki inne niż Chrome headless nie były testowane.

## 8. Ryzyka i następne kroki

1. Orkiestrator: po integracji backendu XML — potwierdzić kontrakt, zbudować `-- --with-xml-download`, dopisać E2E pobrania.
2. Decyzja o użyciu `dist-cf-inbox-beta` w MTAR (obecnie `mta.yaml` kopiuje `dist-cf`), następnie test LIVE z XSUAA.
3. Komunikat 403 sugeruje rolę KSeF operator; 403 może też oznaczać obcy tenant (`INBOX_TENANT_FORBIDDEN`) — tekst jest ogólny.
