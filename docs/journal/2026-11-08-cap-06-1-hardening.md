---
title: "CAP-06.1 — hardening i gotowość do review main"
date: 2026-11-08
type: journal
status: zakonczony
tags: [ainvoicer, journal, cap-java, sapui5, security, cap-06-1]
---

# CAP-06.1 — Security Hardening, Integration Review & Main Readiness

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [CAP-06/UI5-09](2026-11-08-cap-06-ui5-09.md) · [ADR-0005](../decisions/ADR-0005-rap-business-status-cap-integration-status.md)

## Date / Branch / Goal / Initial State

2026-11-08, Europe/Warsaw. Branch `feature/cap-06-ui5-09-dual-backend`; wejściowy HEAD `2841d5628f1b630c7025859962d3e381a2f25dfe`, czyste drzewo. `git fetch origin` potwierdził `origin/main=141bda4f8c2230db6e7a89fef7e5a4f7c8fa73ae`, feature 2 ahead / 0 behind, diff 23 plików. Odczytano rzeczywisty diff, konfiguracje, testy, wymagane przewodniki, ADR-0001/0005 i kanoniczny skill CAP Java. Nie tworzono gałęzi ze starego main.

Cel: poprawić bezpieczne ustawienia i regresje CAP/UI5, przygotować feature do review. Gotowość kodu do PR nie oznacza gotowości deploymentu CF/PROD. Bez zmian architektury, API CAP ani obiektów SAP.

## A. Audit Findings

| Ważność | Przyczyna / ustalenie | Naprawa i pozostałe ryzyko |
| --- | --- | --- |
| Wysoka | H2 console domyślnie ON, filtr `permitAll`, profil default nie wykluczał współistnienia CF | Globalnie OFF. Jawny profil `h2-console-local`, literal loopback bind, brak cloud/PROD/VCAP i obsługi forwarded, `web-allow-others=false`. Filtr sprawdza adres połączenia; niepubliczny interfejs nadal wymaga izolowanego procesu developerskiego. Preferowane narzędzia JDBC bez konsoli HTTP. |
| Wysoka | `defaultUsers.enabled=false` działało tylko w default; inne lokalne profile mogły przywrócić privileged/system/authenticated z pustym hasłem | Przeniesione do dokumentu YAML wspólnego dla wszystkich profili. `cf/cloud/prod/production` dodatkowo wyłączają mock auth. Realne IAS/XSUAA pozostaje NOT IMPLEMENTED. |
| Średnia | Runner nie miał regresji granic nagłówka ani redirectów; credential mógł być odbity w błędzie/obrazie | Oddzielny moduł tylko Node/CDP, pełny opt-in env, dokładny loopback origin i ścieżka, wyłącznie GET/HEAD. Każdy redirect i pozostałe kanały są oczyszczane z Authorization. Redakcja logów, brak screenshotów przy aktywnym credentialu, błąd interceptora kończy test. Test z realnym Chrome i dwoma serwerami HTTP. |
| Średnia | Filtry AI sprawdzały ogólny limit100, zamiast MaxLength per pole; daty przyjmowały nieistniejące dni i obcinały dowolny sufiks | Wspólne długości z RAP UI, walidacja obu granic BT, prawdziwych dat i ISO waluty; małe `pln` normalizowane do `PLN`. Server filters/sorters mają whitelistę, a zagnieżdżone nieprawidłowe literały są blokowane przed HTTP. |
| Średnia | Adapter akceptował dowolną bazę CAP; negocjacja4.0 dotyczyła także mock JSON i POST | Wyłącznie trasa same-origin, odmowa literalnego traversal, `redirect:error` dla fetch/CSRF; nagłówek OData-MaxVersion tylko dla CAP GET. Nie zmieniono kontraktu endpointów. |
| Niska | Smoke czekał stałe2,5s po renderze, co dawało obraz pustej tabeli przed końcem READ | Czeka na zakończenie READ/model strony albo błąd, raportuje liczność i dostępność CAP; błędy ładowania nadal oznaczają FAIL. Stosy wyjątków wskazują pochodzenie w UI5/ushell. |
| Do osobnego sprintu | `npm ci`/audit:74 podatności w toolingu developerskim (2low/15moderate/54high/3critical) | Wersje i lockfile bez zmian. Główne pakiety z advisory: UI5 CLI, UX tooling, mockserver. Nie uznajemy tego za usunięte ryzyko produkcyjne; przed deploymentem potrzebny osobny audyt i plan migracji toolingu. |

Runtime `$metadata` UI ponownie200. Ujawniona różnica kontraktów: `CurrencyCode` ma MaxLength5 w UI, a3 w istniejącym EDMX Web API. Nowa mapa UI odzwierciedla5; waluta użyta w filtrze kwoty i AI nadal wymaga3 liter ISO. Nie modyfikowano EDMX Web API ani bindingów.

## B. Code Changes / Configuration Audit

- CAP: `srv/src/main/resources/application.yaml`, `H2ConsoleSecurityConfiguration.java`, komentarz w `srv/pom.xml`; testy `H2ConsoleSecurityConfigurationTest`, `H2ConsoleLocalODataTest`, `UnconfiguredAuthenticationODataTest`.
- UI5: `KsefContract.js`, `KsefFilterBuilder.js`, `KsefAiFilterAdapter.js`, `KsefDataAdapter.js`; QUnit kontrolera, filtrów i adaptera. `scripts/browser-test.mjs`, nowe `scripts/local-cap-auth.mjs` i `.test.mjs`, skrypt npm `test:runner`.
- Dokumentacja: niniejszy dziennik, STATUS, ROADMAP, README i przewodniki kokpitu/połączenia LIVE. Pełną listę wcześniejszych23 plików CAP-06 pokazuje diff PR.
- `Application.java`, publiczny CDS, Destination `AINV_H01`, wersje zależności i lockfile bez zmian. Brak plików ABAP/abapGit, node/Python, wygenerowanych artefaktów, screenshotów, `.env`, lokalnych YAML i credentiali w zmianach Git.
- Sprawdzono wszystkie wersjonowane profile: default (H2 tylko w pamięci), writeback-local (plikowa H2, bind loopback), test (wyłącznie `src/test/resources`, jawne syntetyczne konta), nową bramkę cf/cloud/prod/production i h2-console-local. Bez domyślnych kont w `src/main/resources`.
- Istniejące ignorowane `.env.live` i `.local/cap06/application-local.yaml` sprawdzono bez wypisywania wartości. Dwa lokalne konta mają niepuste hasła i nie są privileged. Transporty lokalne OFF; uruchomiono osobną świeżą H2 `cap061-read`, bez operacji. Nie zmieniano poświadczeń Destination. Destination przekazano tylko do backendu/testu z istniejącej autoryzowanej konfiguracji.
- W Git nadal `ainv.ksef.enabled=false`, `send.retry-after-rejection=false`, `ainv.sap-write.enabled=false`; manifest `dataMode=mock`, wszystkie5 capabilities false. Wrażliwe odpowiedzi RemoteODataClient pozostają z wyłączonym logowaniem. Logi i obrazy są wyłącznie w ignorowanych lokalizacjach.

## C. CAP Java

Wersje: Microsoft OpenJDK25.0.4.1, Maven3.9.16, CAP5.2.0, Boot4.1.1, Cloud SDK5.33.0, MF SDK3.0.29. Bez podnoszenia/obniżania wersji. Użyto [właściwości CAP mock security](https://cap.cloud.sap/docs/java/developing-applications/properties#cds-security), [CAP Security](https://cap.cloud.sap/docs/java/security) oraz [Spring Boot Security](https://docs.spring.io/spring-boot/reference/web/spring-security.html); testy faktycznego runtime są dowodem zachowania przypiętych wersji.

Startup finalnego JAR PASS. HTTP GET metadata200, InvoiceIntegrationStatus200/count0, KsefOperations200/count0. Anonimowy401, wbudowane privileged/system/authenticated z pustym hasłem401, konto bez roli403. `/h2-console/`: anonimowy401, jawny reader404; brak500 i błędów tworzenia SecurityFilterChain. Domyślnie servlet i jego wyjątek CSRF nie istnieją.

Nowe testy w odrębnym profilu bez `default` i bez kont potwierdzają fail closed. Izolacja NIP/spółki, brak/wadliwe atrybuty, obcy klucz404 i ograniczony count: istniejące testy HTTP/H2 PASS. `@requires authenticated-user`, `KsefOperator` i restrykcje CDS pozostają; `requestSend` sam odczytuje NIP/spółkę z RAP i porównuje z UserInfo. Bezpośrednie POST są objęte istniejącą regresją wyłącznie na H2 z atrapą SAP/KSeF; **nie wykonano POST requestSend do uruchomionego runtime ani LIVE**.

Przy lokalnym opt-in H2 rzeczywisty servlet zwraca200 z loopback, a pełny filtr odmawia403 adresowi zewnętrznemu z podrobionym X-Forwarded-For. Bramka odrzuca konfiguracje CF/PROD, VCAP, publiczny bind i forwarded. CSRF jest wyłączony wyłącznie dla ścieżki lokalnej konsoli; brak globalnego wyłączenia bezpieczeństwa.

## D. SAPUI5 / Smoke FLP

Filtry nadal powstają jako `sap.ui.model.Filter`. Nie przekazujemy tekstu LLM do OData; nie generujemy tolower. Wyszukiwanie100 znaków ma niepuste gałęzie SellerName/BuyerName. Złożony OR z polem klienckim jest usuwany z zapytania serwera w całości, aby nie zawężać wyniku; pełna logika kliencka pozostaje. Kwota bez bezwarunkowej waluty blokuje HTTP; data zachowuje Edm.Date, sort/paging wykonuje RAP ze stabilnym UUID. Synchroniczna walidacja zwalnia busy i pokazuje błąd — nowy test kontrolera PASS.

CAP READ negocjuje4.0: serwer zwraca OData-Version4.0 i `@odata.count`. Kontrole count/nextLink/typów i obcych UUID nie zostały poluzowane. Niekompletna historia oraz401/403/network/timeout blokują Send i pozostawiają faktury RAP. Numer zapisany w RAP nadal ma pierwszeństwo nad CAP. Nie ma automatycznego retry mutacji ani podążania za redirectem. Obsługa AI/Scanner/Voice/Legacy zachowana i sprawdzona przez QUnit/OPA; nie jest to LIVE AI/OCR/STT/Legacy.

**Smoke FLP nadal FAIL / exit1 (KNOWN BLOCKER), również z poprawnym lokalnym principalem CAP.** Końcowy raport runnera: invoiceCount4, loadError=false, integrationAvailable=true. Bez credentiala widoczne4 faktury i integracja niedostępna (w Chrome Basic challenge kończy się CAP_READ_TIMEOUT); to dodatkowy lokalny brak tożsamości, a nie błąd autoryzowanego CAP READ. Obraz wariantu bez credentiala obejrzano; wariant z credentialem nie tworzy pliku.

| Źródło błędu | Własny dowód i kwalifikacja |
| --- | --- |
| Legacy backend / konfiguracja | `/sap/.../zui_fi_acdoca_o4/.../$metadata`503. Stary skonfigurowany origin/destination nadal niedostępny przez lokalne proxy; nie potwierdzono działającego zamiennika. Deklaratywne bindingi ukrytego widoku Legacy również inicjują READ. |
| Flex / lokalny preview | Brak changes/flexibility-bundle.json i changes-bundle.json; LrepConnector loadFlexData/loadFeatures503. To nie jest test flex na wdrożonym FLP. |
| MessageBroker / lokalny preview | Client is not connected, flp-app-info; trzy wyjątki promise ze stosami subscribe/publish w `sap/ushell/library-preload.js`. Nie przypisano ich bez dowodu do kodu aplikacji. |
| Rzeczywisty kod / narzędzie projektu | Poprawiono bramki filtrów/AI i przedwczesny screenshot w runnerze. Nie zidentyfikowano nowego wyjątku domeny CAP/UI5 odpowiedzialnego za pozostałe błędy FLP. Pełny smoke pozostaje niezaliczony. |

**Wymagania konfiguracji Legacy:** administrator wskazuje aktualny origin i niezależną Destination dla usługi `ZUI_FI_ACDOCA_O4/0001`; musi potwierdzić uwierzytelnione metadata200 i READ `ZC_FI_ACDOCA` zgodne z używanymi polami. Trasa `/sap` i jej uwierzytelnianie mają być odrębne od `/rap-ui` i AINV_H01. Przed włączeniem flex należy także ustalić działający endpoint LREP albo wspierany lokalny connector preview oraz odtworzyć problem MessageBroker w docelowym wariancie FLP. Nie podmieniono działających poświadczeń H01 i nie wyłączono globalnie flex jako obejścia.

## E. Commands Executed / Tests & Results

Zatrzymano wyłącznie rozpoznane procesy lokalnego CAP oraz dwóch serwerów Fiori z poprzedniej sesji, aby odblokować JAR/keytar dla clean/npm ci. Odtworzono CAP8080, mock8085 i RAP8086. Wygenerowane konfiguracje, PID/log CAP i wyniki browser są w ignorowanym `cap-java/.local/cap061/`; logi Maven/UI5 `.local-cap061-*.log` również poza Git. Nie zmieniano danych wcześniejszej bazy.

| Polecenie / zakres | Rzeczywisty wynik końcowy |
| --- | --- |
| `git fetch origin`, status/branch/log, pełny diff main…feature, diff --check | PASS; baza main bez nowych commitów; początkowo2 ahead/0 behind |
| `cap-java: mvn clean verify '-Dcds.cds.skipVersionCheck=true'` | **203/203**, 0 failures/errors/skips, BUILD SUCCESS; ponowione po końcowej zmianie profili |
| `cap-java: ./run-live-h01.ps1` → SapInvoiceRemoteAdapterLiveTest | **LIVE1/1 PASS**, rzeczywisty CAP RemoteService,4/9/6, keyed READ, daty/kwoty/timestamp; nie CF Destination Service |
| `java -jar srv/target/cap-java-exec.jar` + ignorowany lokalny config, HTTP GET/auth | PASS opisany w części C; świeża H2,0 operacji |
| HTTP GET `/rap-ui/$metadata` i `/rap-ui/Invoices?$count=true&$expand=_Items,_References` | **LIVE HTTP200,4/9/6**, istniejący business-user proxy; odczytano też MaxLength |
| `npm ci` | PASS,74 advisories; bez upgrade/audit fix |
| `npm run build` | PASS, końcowy kod |
| `npm run serve-mock`; `npm run test:unit` | **76/76,438 asercji PASS** |
| `npm run test:opa` | **13/13,80 asercji PASS**; komunikaty TTS disabled in tests są świadomą atrapą |
| `npm run test:runner` | **3/3 PASS**, w tym realny Chrome/CDP: bez nagłówka na RAP, SAP, zasobie CDN-fixture i obcym origin; redirect także do dozwolonej ścieżki CAP nie dostaje credentiala |
| `npx fiori run --config ui5-rap.local.yaml --port8086 --open false` | Serwer działa; istniejący reentrance ticket READ |
| `npx ui5 build --config ui5-rap.local.yaml --clean-dest --dest dist/rap-read` | PASS |
| `node scripts/browser-test.mjs qunit http://localhost:8086/test/live/dualBackend.qunit.html 60000` + jawne env lokalnego readera | **10/10,63 asercje PASS**, RAP LIVE + autentyczny lokalny CAP runtime z mock principalem; końcowy kod ponowiony |
| `browser-test.mjs smoke .../test/flp.html#app-preview ... 60000`, z/bez lokalnego auth | **FAIL/exit1**, known Legacy/flex/MessageBroker; końcowe stany4 faktury, RAP nieukryty |
| IAS/XSUAA, CF, rzeczywista izolacja A/B H01, clean-system deploy DCL | **NOT VERIFIED / NOT IMPLEMENTED**, bez zmiany wcześniejszych bramek |
| LIVE SAP WRITE / ApplyKsefResult / KSeF SEND | **NOT EXECUTED**; brak zmian faktur, IAM, arrangements i abapGit |

Odczyty UI5 obejmują koperty `$batch` z READ. Nie zawierają mutacji. Testy Maven używające POST requestSend mają atrapę SAP/SDK i H2; ich PASS nie zalicza żadnej wysyłki ani HTTP WRITE do SAP.

## F. Git / Review

Przegląd objął kod, testy i dokumentację23 zastanych plików oraz poprawki powyżej. Bez niepowiązanych refaktoringów i aktualizacji zależności. `git diff --check` PASS; nie ma zmian ADR-0005 ani eksportu SAP. Przed publikacją ponowny fetch nadal wskazuje main141bda4 i brak commitów main poza feature — nie ma rozbieżnej historii wymagającej merge/rebase. Wyniki publikacji i konkretne SHA należy czytać w dopisku poniżej oraz w PR; wejściowy HEAD nie jest SHA finalnej zmiany.

## G. Next Sprint Readiness / Next Steps

| Etap | Ocena | Codex | Administrator / użytkownik | Ryzyko / bramka |
| --- | --- | --- | --- | --- |
| CAP-07 — IAS/XSUAA, approuter i business users | **READY TO START**, wdrożenie zależne od administratora | Dodać prawdziwe integracje security/binding, trasy approutera, mapping zaufanych taxpayerNips/companyCodes i regresję401/403/A-B; usunąć zależność testów LIVE od Basic mock | Wybrać faktycznie dostępną usługę IAS/XSUAA/entitlements, zapewnić binding, zaufane claims i ograniczone role collections, konta A/B | Mock nie dowodzi SSO. Nie ufać NIP z payloadu. Destination na CF, HANA/storage i podatności toolingu pozostają bramkami deploymentu. |
| CAP-04D — kontrolowany HTTP WRITE H01 | **BLOCKED** | Przygotować/powtórzyć read-only preflight i po osobnym zleceniu wykonać istniejący scenariusz CSRF/CAS/idempotencji/read-after-write, kontrolować precyzję100ns i no-op | Potwierdzić arrangement/principal i ograniczony ACTVT16/NIP/spółkę; zatwierdzić osobny dziewiczy syntetyczny UUID OUTBOUND, różny od przyszłego SEND, i udzielić osobnej zgody na WRITE | Brak potwierdzonego prawa zapisu i persystowanego wyniku LIVE. Nie używać WRITE jako testu security. |

Trzy UI DCL nadal poza poprawną serializacją abapGit; A/B z rzeczywistymi użytkownikami nadal niepotwierdzone. Nie przypisano im PASS. Dokładne warunki dalszych prac: [CAP/BTP](../guides/RAP_UI_LIVE_CONNECTION.md), [WRITE gate](../guides/SAP_WRITE_ACCESS_AND_READ_POLICY.md), [DCL deploy gate](../abap-dcl-reference/README.md).

Najbliższy krok: review PR, osobna decyzja użytkownika o merge do main; potem CAP-07 z administratorem. CAP-04D i pierwszy SAP → KSeF TEST pozostają osobnymi, jawnie autoryzowanymi zadaniami.
