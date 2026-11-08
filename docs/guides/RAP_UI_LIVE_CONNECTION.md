---
title: "RAP UI — połączenie LIVE z SAPUI5"
date: 2026-11-08
type: guide
status: częściowo-zweryfikowany
tags: [ainvoicer, rap, abap, sapui5, iam, odata, abap-06]
---

# RAP UI — połączenie LIVE

## CAP-07 — routing BTP (przygotowany, niewdrożony)

Approuter ([`approuter/xs-app.json`](../../approuter/xs-app.json)) mapuje `/rap-ui/*` na ścieżkę bindingu `ZUI_AINV_INV_H_O4/0001` przez Destination `AINV_H01_UI_USER` z propagacją business usera, a `/odata/v4/KsefService/*` na CAP z tokenem XSUAA. Manifest UI5 nie wymaga zmian (względne `/rap-ui/` i `/odata/v4/KsefService/`). Lokalny reentrance ticket pozostaje bez zmian. **Business-user SSO CF → H01 jest BLOCKED** — mechanizm propagacji musi potwierdzić administrator; nie zastępować go Basic Auth ani communication userem `AINV_H01`. JWT CAP nie daje dostępu do H01. Szczegóły: [BTP_IDENTITY_SETUP](BTP_IDENTITY_SETUP.md#8-business-user-sso-do-h01--blocked), [ADR-0006](../decisions/ADR-0006-btp-identity-xsuaa.md).

## CAP-06.1 — bezpieczne lokalne ustawienia

H2 console jest domyślnie OFF we wszystkich profilach; `/h2-console/` nie jest publiczną usługą. Preferuj narzędzia JDBC developerskie. Wyłącznie w izolowanym procesie lokalnym można jawnie uruchomić konsolę z `--spring.profiles.active=default,h2-console-local --server.address=127.0.0.1 --spring.h2.console.enabled=true`. Bez proxy, tunelu i forwarded; `web-allow-others=false`. CF/cloud/prod/production lub obecność VCAP blokują taki start. Dostęp konsoli jest ograniczony do adresu połączenia loopback, niezależnie od X-Forwarded-For; jej wyjątek CSRF nie obejmuje CAP.

Domyślni mock users są OFF globalnie, nie tylko dla default. Konta lokalne wymagają jawnej ignorowanej konfiguracji i niepustego hasła; cf/cloud/prod/production wyłączają mock auth. Te ustawienia nie zastępują IAS/XSUAA i nie potwierdzają gotowości CF. Standardowe flagi KSeF/SAP WRITE i Send pozostają OFF.

Runner `scripts/browser-test.mjs` z env `AINV_CAP_LOCAL_TEST_USER` / `AINV_CAP_LOCAL_TEST_PASSWORD` dodaje Basic tylko do GET/HEAD dokładnego loopback origin i `/odata/v4/KsefService/`. Każdy redirect (także do CAP) oraz pozostałe kanały mają Authorization usuwane. Logi są redagowane, screenshot przy aktywnym credentialu nie jest zapisywany. Nie używaj tu credentiali SAP ani produkcyjnych. Brak jednej z wymaganych zmiennych kończy test przed uruchomieniem browsera. `npm run test:runner` sprawdza te ograniczenia, również w realnym Chrome/CDP.

Końcowe odczyty CAP/H01 i dual10/63 PASS. Smoke FLP nadal exit1: Legacy503, flex/LREP i MessageBroker preview. Szczegółowe wymagania niezależnej Destination Legacy i bramki **CAP-07 READY TO START / CAP-04D BLOCKED**: [audyt CAP-06.1](../journal/2026-11-08-cap-06-1-hardening.md). Poniższe wyniki opisują wcześniejszą sesję CAP-06.

## CAP-06/UI5-09 — odczyt dwóch backendów, 2026-11-08

**RAP LIVE + CAP uruchomiony lokalnie: PASS w teście przeglądarkowym z lokalnym mock principalem CAP. IAS/XSUAA: BLOCKED.** UI5 działa na8086, CAP na127.0.0.1:8080, mock regresyjny na8085. RAP korzysta z istniejącego reentrance ticket business usera; CAP z osobnej tożsamości lokalnej. Nie przenosimy cookie SAP do CAP ani do Destination systemowej.

- UI i Web API metadata200, odczyt4/9/6. READ roli/przypisania potwierdza `ZBR_AINV_READ_1000` → `CB9980000000`; subject samej sesji proxy nie jest zwracany przez OData.
- CAP `$metadata`, `InvoiceIntegrationStatus?$count=true` i `KsefOperations` zwracają200. Świeża H2 w pamięci:0 operacji. Anonimowy401, bez KsefOperator403, wbudowany privileged401 (wyłączony).
- Test aplikacji:10/10,63 asercje — lista, otwierany dialog, wszystkie dzieci, UUID, wersja, numer/NIP/data/spółka/kwota, sort/count/top/skip i poprzednia strona. CAP401/403/network/timeout są **symulowane**, a RAP w tych przypadkach rzeczywisty.
- UI V4 H01 nie wspiera `tolower()`. Zapytania serwerowe używają dopasowania z uwzględnieniem wielkości liter (tooltip w wyszukiwaniu). Zbyt długi tekst nie trafia do krótszych pól OR, np. numer faktury do NIP(10). Filtr kwoty wymaga jawnej waluty, także gdy kwotę poda AI.
- CAP5.2 bez negocjacji odpowiada4.01 z `@count`; adapter ustawia `OData-MaxVersion: 4.0`, zachowując sprawdzanie kompletności historii `@odata.count`/`@odata.nextLink`.

Powtarzalny test READ po uruchomieniu obu serwerów:

```powershell
# W katalogu sapui5/ainvoicer. Lokalna kopia webapp.rap.local musi zawierać bieżące źródła/test/live,
# a wyłącznie jej manifest ma dataMode=rap oraz sendInvoice=false.
# Użytkownik testowy CAP pochodzi z ignorowanej konfiguracji deweloperskiej, bez kont privileged.
# Dwa poniższe env przekazać bez logowania ich wartości, nigdy nie używać tu credentiali SAP.
$env:AINV_CAP_LOCAL_TEST_USER = '<jawny lokalny mock user CAP>'
# AINV_CAP_LOCAL_TEST_PASSWORD dostarczyć do procesu bez publikowania wartości.
node scripts/browser-test.mjs qunit http://localhost:8086/test/live/dualBackend.qunit.html 60000
Remove-Item Env:AINV_CAP_LOCAL_TEST_USER, Env:AINV_CAP_LOCAL_TEST_PASSWORD
```

Runner przekazuje Basic wyłącznie do `/odata/v4/KsefService/` na tym samym loopback origin. Nie umieszcza credentiali w JavaScript aplikacji, RAP ani CDN. Strona testowa odmawia startu w `dataMode=mock` lub z Send ON. Nie należy jej dodawać do standardowego zestawu offline ani używać jako testu IAS. Zwykła karta przeglądarki nie dziedziczy uwierzytelnienia runnera: bez własnej sesji CAP wyświetli ostrzeżenie401 i zachowa dane RAP.

CAP uruchomiono z wykonywalnego JAR-a na JDK25, profilem `default` i `--spring.config.additional-location=file:.local/cap06/application-local.yaml`. Ten plik jest ignorowany: jawny mock reader z zakresem5260002008/1000, losowe hasło lokalne, H2 `jdbc:h2:mem:cap06-read`, bind127.0.0.1, konsola H2 i sweeper OFF. W każdym przebiegu `ainv.ksef.enabled=false`, retry-after-rejection=false, sap-write.enabled=false. Nie używano plikowej bazy wcześniejszego E2E. Przed `mvn clean verify` zatrzymać ten JAR — Windows blokuje otwarty plik w target. Przed `npm ci` zatrzymać serwery korzystające z tego node_modules.

`ui5-rap.local.yaml` i `webapp.rap.local` pozostają ignorowane, `@sap/ux-ui5-tooling=1.19.6` bez aktualizacji. Smoke FLP exit1: screenshot pokazuje4 faktury/Send OFF, ale Legacy `/sap` na historycznym hoście zwraca503; dodatkowo flex/MessageBroker podglądu. Ten wynik nie jest czystym PASS całej aplikacji. [Pełne dowody](../journal/2026-11-08-cap-06-ui5-09.md).

## CAP-06 — tożsamość CAP i plan BTP

**Do wykonania przez administratora i w osobnej implementacji deploymentu; żadnej usługi ani roli nie utworzono.** W procesie nie było VCAP_SERVICES/bindingu IAS/XSUAA. W JAR brak `cds-feature-identity` i resource-server startera; sam Spring Security oraz SAP java-security nie włączają walidacji tokenów platformy. Zależności i binding są wymagane łącznie przez [CAP Java Security](https://cap.cloud.sap/docs/java/security).

1. Potwierdzić istniejące instancje/entitlementy i wybrać docelowy wariant: XSUAA z federacją IAS albo IAS z obsługiwanym mapowaniem ról/atrybutów CAP. Bez provisioning/deployu w tym sprincie. Do CAP dodać zgodny starter Cloud Foundry albo komplet `cds-feature-identity` + `resourceserver-security-spring-boot-starter` oraz istniejący starter Security; przypiąć autoryzowany binding. Na docelowym profilu mock auth OFF.
2. Dla wariantu XSUAA przygotować deskryptor CDS/XSUAA z rolą/scope `KsefOperator` i wymaganymi wielowartościowymi atrybutami `taxpayerNips`, `companyCodes` typu string. Administrator tworzy role collection dla dokładnego uzgodnionego zakresu (referencyjnie5260002008/1000) i przypisuje właściwego użytkownika. Nie stosować wildcard ani atrybutów z request body/header/query. Dla IAS sprawdzić rzeczywistą reprezentację UserInfo; same custom claims nie dowodzą dostępności `$user.taxpayerNips`.
3. Approuter uwierzytelnia business usera, przekazuje zweryfikowany token do CAP; niezależna trasa RAP zachowuje obsługiwane business-user SSO/DCL. Token CAP nie jest credentialem communication usera SAP. Wygenerować konfigurację routingu po ustaleniu usług/bindingów, bez Basic systemowego w UI.
4. Powtórzyć macierz HTTP na faktycznych sesjach: anonimowy401, brak roli403, obcy NIP/spółka0, obcy klucz404, prawidłowy zakres tylko własne rekordy i count, brak/wadliwe claims fail closed. Dodatni test musi mieć zatwierdzoną historię procesu; pusta baza nie dowodzi izolacji. Obecnie tę macierz pokrywają testy H2/mock, nie LIVE IAS.

Lokalna Destination `AINV_H01` z istniejącego `.env.live` ma LIVE READ PASS przez CAP. Minimalne ustawienia BTP Destination: Name=AINV_H01, Type=HTTP, URL=rzeczywisty origin H01, ProxyType=Internet, uwierzytelnienie communication usera zgodne z arrangement; sekrety zarządzane. URL nie zawiera pełnej ścieżki usługi — tę ustawia istniejący RemoteService `http.service`. BTP Destination Service/CF binding i sam communication arrangement w Fiori **nie były niezależnie odczytane**. Eksport zawiera `ZCS_AINV_H01`, ale nie dowodzi on aktualnego nadania ACTVT16.

Izolacja A/B H01 nadal BLOCKED: wszystkie4 istniejące dokumenty mają jeden zakres. Plan bez wykonania: zatwierdzić drugiego business usera B oraz trzy odrębne dokumenty syntetyczne: dodatni dla NIP_B/BUK_B, krzyżowy NIP_A/BUK_B i krzyżowy NIP_B/BUK_A. Z istniejącym zakresem A umożliwia to test obu osi, dzieci/expand/kluczy/count. Nie utworzono danych ani nie nadano ról.

## Historia wcześniejszych sprintów

## ABAP-08 — stan końcowy 2026-11-07: SAPUI5 → RAP LIVE VERIFIED

Lista faktur z H01 widoczna w SAPUI5 przez `/rap-ui` (4 faktury; CAP wyłączony nie ukrywa danych; Send wyłączony). [Dziennik](../journal/2026-11-02-abap-08-ui5-08.md#kontynuacja-2026-11-07-2--business-role-utworzona-business-user-read-pass-sapui5-live-pass).

Jak uruchomić lokalnie (wymaga roli `ZBR_AINV_READ_1000` lub równoważnej):

1. Ignorowany `sapui5/ainvoicer/ui5-rap.local.yaml`: trasa `/rap-ui` → `url: https://9197a0a5-b602-4c28-84b4-6b63c6daa54d.abap.eu20.hana.ondemand.com` (host **API** `.abap.`), `pathPrefix` bindingu `ZUI_AINV_INV_H_O4/0001`, `authenticationType: reentranceTicket`; `resources.configuration.paths.webapp: webapp.rap.local` (ignorowana kopia `webapp` z `dataMode=rap`, `sendInvoice=false`).
2. **Użytkownik sam** uruchamia `npx fiori run --config ui5-rap.local.yaml --port 8086 --open false` w swoim terminalu. Przy starcie proxy otwiera kartę `…abap-web…/sap/bc/sec/reentrance`, a na zalogowanie (IAS, mail i hasło) jest **60 s**. Po timeoucie błąd jest połykany bez logu i proxy przekazuje 401, przez co przeglądarka pokazuje okno Basic Auth, które w BTP ABAP nie działa. Wtedy trzeba zrestartować serwer.
3. Otworzyć `http://localhost:8086/test/flp.html#app-preview`.

Pułapki potwierdzone 2026-11-07:

- Host `11b69711-….abap.us10…` z `ui5-mock.yaml`/`ui5-local.yaml` to **nie H01**: 503 „System is unknown” (`ICMENOSYSTEMFOUND`). Właściwe hosty zwraca `GET <api>/sap/public/bc/icf/virtualhost` (`API` = `.abap.`, `UI` = `.abap-web.…/ui`).
- `@sap/ux-ui5-tooling` < 1.19.6 otwiera reentrance na hoście API (reguła `-api` dla S/4HANA Cloud). W repo przypięto **1.19.6**; ≥ 1.20.0 wymaga UI5 CLI v4.
- Zawieszony stary proces na porcie 8086 (`EADDRINUSE`) powoduje, że przeglądarka trafia na stary serwer ze starą konfiguracją.

## ABAP-08 — kontynuacja 2026-11-07 (przed utworzeniem roli)

Kanał OData MCP znowu działa. `resolve_service` potwierdza `/sap/opu/odata4/sap/zui_ainv_inv_h_o4/srvd/sap/zui_ainv_inv_h_o4/0001/` (V4, published), a hashe metadata UI i Web API są niezmienione. UI: 0 nagłówków / 0 pozycji / 0 referencji (także przy bezpośrednich odczytach dzieci). Web API: 4 nagłówki w zakresie 5260002008/1000. Business Role z `ZAINV_BC_READ` **nie istnieje** (released `I_IAMBusinessRole`), więc business-user READ jest nadal BLOCKED. `ui5-rap.local.yaml` odtworzony i zbudowany, ale nie uruchomiony. Po utworzeniu i przypisaniu roli oczekiwany odczyt UI to 4/9/6 z UUID zgodnymi z Web API. [Instrukcja i wyniki](../journal/2026-11-02-abap-08-ui5-08.md#kontynuacja-2026-11-07--logowanie-mcp-działa-business-role-nie-istnieje).

## ABAP-08/UI5-08 — stan 2026-11-02

**Bez zmian: business-user READ i SAPUI5 LIVE BLOCKED.** Regresja lokalna po merge ABAP-07: Maven 178/178, QUnit 69/69, OPA 13/13, buildy PASS. Tej sesji `odata_resolve_service` (UI i Web API) → `ODATA_REQUEST_FAILED` (kanał OData MCP wymaga ponownego logowania), ATC bez odpowiedzi, abapGit diff DCLS nadal pusty; SRVB istnieje. Lokalny, ignorowany `ui5-rap.local.yaml` przygotowany (nieuruchomiony). Lokalny `dataMode=rap` ustawiać wyłącznie w niecommitowanej kopii manifestu po PASS READ; Send OFF. CAP-04D: NOT READY (Destination AINV_H01, ACTVT16, osobny UUID syntetyczny — poniżej, bez zmian). [Dziennik i instrukcja dla administratora](../journal/2026-11-02-abap-08-ui5-08.md).

## ABAP-07/UI5-07 — bieżąca bramka, 2026-10-30

**Binding/IAM developerskie VERIFIED; business-user READ i SAPUI5 LIVE BLOCKED.** Nowy READ MCP potwierdza active/published V4/0001, metadata HTTP200 (hash jak poniżej), App/katalog/RT publishStatus=p, zapisane RF i assignment. DCL nagłówka nadal sprawdza ACTVT03 i parę NIP/spółka, dzieci dziedziczą przez _Parent. ATC7/7 bez ustaleń; brak inactive w sprawdzonym zakresie. UI Invoices + expand, InvoiceItems i InvoiceReferences zwracają HTTP200/0 w developer OAuth. Web API zwraca4/9/6. **Nie ustalono przyczyny pustej listy UI ani istnienia/efektywności Business Role.** [Pełny raport](../journal/2026-10-30-abap-07-ui5-07.md).

**Jedna następna czynność administratora:** w H01 Fiori → **Maintain Business Roles** potwierdzić efektywne przypisanie ograniczonej roli odczytu do uzgodnionego business usera. Wykorzystać istniejącą rolę, jeśli jest; nazwa ZBR_AINV_READ_1000 jest tylko propozycją. Sprawdzić katalog ZAINV_BC_READ, zapisany/aktywny stan roli, Read=Restricted, Write=No Access, ZAINV_RF_NIP=5260002008 i ZAINV_RF_BUK=1000 oraz sumę innych ról użytkownika. Brak roli/przypisania wymaga działania upoważnionego administratora, bez wildcard/ACTVT16/CRUD. Nie tworzono ani nie przypisywano roli przez agenta. Po potwierdzeniu: nowy login business usera i READ poniżej.

MCP nie udostępnia API Business Roles/przypisań ani przełączenia principal na business usera; jego query nie wspiera filter/orderby/skip/count/key. Próba odczytu istniejącego okna SAP przez Computer Use zakończyła się dwukrotnym timeoutem przechwytywania. Nie odczytano roli przez UI. Do dowodu potrzebna jest sesja Fiori/business usera; nowy login developer OAuth nie zastępuje jej.

### Status wymaganych testów

| Przypadek | ABAP-07 — aktualny dowód |
| --- | --- |
| UI metadata, aliasy, Edm.Guid/Date/Decimal | LIVE developer OAuth200; typy i nawigacje zgodne |
| Dozwolony NIP/spółka jako business user | BLOCKED — rola, principal i login niezweryfikowane; liczba widocznych faktur nieznana |
| Obcy NIP / obca spółka | BLOCKED — brak zatwierdzonej tożsamości i danych innych zakresów;4 znane nagłówki Web API w jednym zakresie |
| Bez roli; obcy UUID; bezpośrednie obce dzieci; expand | BLOCKED — brak odpowiednich sesji/danych; puste kolekcje developera nie zaliczają izolacji |
| Filter/orderby/top/skip/count business usera | BLOCKED — ograniczenia MCP; lokalny mock paging/count PASS |
| UI bez ApplyKsefResult / CRUD | CDS/SRVD READ: brak behavior/akcji; normalizowane metadata MCP nie raportują akcji/capabilities. Surowy EDMX i odmowy runtime business usera NOT VERIFIED; nie wykonywano mutującego HTTP |
| CAP user / claims / korelacja z RAP | BLOCKED LIVE — IAS/XSUAA, trusted claims i Destination niezweryfikowane; lokalne testy ról/NIP/spółki PASS |
| 401/403, CAP timeout, puste kolekcje | QUnit69/69 PASS mock/stub, bez dowodu IAM LIVE |

### Faktyczny routing lokalny

Nie uruchomiono wariantu RAP ani nie utworzono lokalnych credentiali. Manifest nadal `dataMode=mock`, wszystkie mutujące capabilities=false. `serve-mock` używa `ui5-mock.yaml`: RAP i Legacy są atrapami. W `ui5-local.yaml` `/sap` wskazuje dotychczasowy Legacy destination `abap-cloud-abap-1ebf3ff5trial-dev` (również mock middleware Legacy), CAP ma trasę localhost8080, **brak /rap-ui proxy LIVE**. Szablon `ui5-rap.yaml.example` przewiduje /rap-ui, CAP i osobne /sap, ale ma placeholdery origin/path/destination. Build szablonu PASS nie sprawdza logowania.

Po bramce IAM skopiować szablon do ignorowanego `ui5-rap.local.yaml`, wstawić `pathPrefix=/sap/opu/odata4/sap/zui_ainv_inv_h_o4/srvd/sap/zui_ainv_inv_h_o4/0001` (zweryfikowany ponownie w ABAP-07), origin z bindingu oraz faktycznie działającą destination business usera. BAS `abap`, auth i propagacja nie zostały sprawdzone; nie mylić ich z nazwą destination Legacy. Zachować TLS i niezależną trasę Legacy. Uruchomić osobny wariant z dataMode=rap dopiero po pozytywnym READ; Send OFF. Lokalny plik proxy jest już ignorowany. CAP CSRF jest pokryty testem HEAD/token + jeden POST do atrapy, bez mutacji LIVE.

Regresja ABAP-07: Maven178/178, QUnit69/69 (408), OPA13/13 (80), oba buildy PASS. Timeout odczytu CAP15 s obejmuje JSON i abortuje READ; po timeout lista/szczegóły RAP pozostają, Send zablokowany. AI/Scanner/Voice/Legacy zachowano w regresji mock; ich backendy LIVE nie były wywoływane.

### Eksport — ponowny odczyt ABAP-07

SAP repoId000000000001 nadal `refs/heads/java-cap-ksef`; lokalny branch jest z origin/main6a4e2a5. Status: **33 unstaged,0 staged**, w tym3 poza zakresem: dwa DELETE ZCL_ABAPFS_GIT_PROBE i ADD ZCS_AINV_H01. Pozostałe30 to kandydaci UI/IAM do świadomego review, nie automatyczna lista do pushu. W origin/main brak eksportu UI i nowych IAM; Z_AINV_KSF już istnieje. Nie przełączono brancha SAP ani niczego nie stage'owano.

Kontrolny diff **zc_ainv_inv_h_ui.dcls.asdcls + .dcls.xml** ponownie ma local="" i remote="", mimo niepustego active źródła ADT. Wcześniejszy ABAP-06 wykazał sześć pustych plików; teraz wystarczyło odtworzyć problem na dwóch. Bezpieczna ścieżka ręczna: H01_EN → abapGit Repositories → ZAINVOICER → Stage and Push, potwierdzić linked branch, STARTING_FOLDER=/abap/src/ i FOLDER_LOGIC=FULL, obejrzeć niepuste źródła/metadane trzech DCLS i cały spójny diff UI/IAM (w tym generowane SUSH/SCO2 i zmieniony SIA3). Wykluczyć3 wpisy spoza zakresu. Jeśli ADT też pokazuje pusty DCL, przerwać eksport. Bez ręcznych sidecarów, bez częściowego eksportu. Publikacja do gałęzi SAP wymaga odrębnego jawnego zlecenia; zgoda na własny feature Git nie oznacza publikacji na java-cap-ksef. Po eksporcie kontrolowana integracja do feature/main po review.

### CAP-04D — checklista przygotowana, NOT READY

Nie wykonano HTTP WRITE ani nie włączono `ainv.sap-write.enabled`. Poniższe punkty są planem przyszłego testu; szczegóły i istniejący harness: [polityka WRITE](SAP_WRITE_ACCESS_AND_READ_POLICY.md#5-cap-04d--gotowość-po-fix-2).

1. Zaliczyć business-user RAP UI READ i izolację oraz sprawdzić Destination **AINV_H01** z rzeczywistym communication userem/scenario/arrangement. Bieżący proces nie ma env destinations/VCAP_SERVICES; kod RemoteService wskazuje poprawny Web API path, co nie potwierdza konfiguracji BTP. Jedna destination jest współdzielona przez READ/WRITE; nie podmieniać credentiali działającego procesu.
2. Administrator osobno potwierdza **Z_AINV_KSF / ACTVT16** i jeden konkretny NIP/CompanyCode bez CRUD, z odczytem dla niezależnego GET. ACTVT16 istniejące w definicji SUSO nie dowodzi nadania; nie dodawać go do roli UI. Wybrać **oddzielną syntetyczną OUTBOUND bez numeru KSeF**, inną od faktury pierwszego SEND. Zapisać uzgodniony UUID/numer/zakres; nie wybrano ani nie utworzono jej w ABAP-07.
3. READ przed próbą: cztery pola KSeF, InvoiceStatus i **LocalLastChangedAt**. Payload ma ExpectedLocalLastChangedAt z pełną precyzją100 ns oraz stały **WriteRequestId**; nie odświeżać wersji po konflikcie w celu obejścia CAS. Potwierdzić backendowy CSRF (konfiguracja `csrf.enabled=true`, test lokalny PASS; LIVE principal/token NOT VERIFIED).
4. Po osobnym zleceniu uruchomić kontrolowany harness CAP-04D wyłącznie dla uzgodnionego UUID: odmowy obcego NIP/spółki i CRUD, **APPLIED**, niezależny GET KsefNumber/KsefEnvironment/KsefRecordedAt/KsefWriteRequestId, niezmieniony InvoiceStatus. Ponowienie identycznego payloadu daje **ALREADY_APPLIED** i nie zmienia ETag; konflikty nie nadpisują wyniku. Te kroki obejmują kilka żądań HTTP w jednym kontrolowanym scenariuszu, nie nowy SEND.
5. **VERSION_CONFLICT** testować wyłącznie na osobno uzgodnionej dziewiczej fakturze STALE; pełny wyścig wymaga osobnego UUID RACE i zakresu zgody. Nie przedstawiać pominiętych wariantów jako PASS. Po utracie odpowiedzi GET/rekoncyliacja z zachowaniem payloadu/WriteRequestId; bez uruchamiania od nowa testu generującego nowy numer. Po próbie wyłączyć override, zachować dowody poza Git. UPO jest niezależne i ten test nie łączy się z KSeF.

## Historia ABAP-06 i procedury szczegółowe

[STATUS](../STATUS.md) · [ABAP-06/UI5-06](../journal/2026-10-30-abap-06-ui5-06.md) · [ABAP-05: poprzednie wyniki](../journal/2026-10-29-abap-05-real-rap-ui.md) · [Kokpit](SAPUI5_KSEF_COCKPIT.md) · [E2E-01](E2E_01_SAP_TO_KSEF_TEST.md)

**Bieżąca bramka (2026-10-30): BLOCKED na IAM, binding już opublikowany.** Po utworzeniu/publikacji przez użytkownika własny MCP odczyt potwierdza SRVB active/V4/published=true/0001. Metadata HTTP200, hash fecf05295ba1105454fae8db18b1b0d3fd133625760a0f19492420debf196345, prawidłowe encje i nawigacje. HTTP GET każdej kolekcji UI zwrócił 200/0 rekordów w kontekście deweloperskim. To nie jest potwierdzony READ business usera ani test izolacji. Domyślnie dataMode=mock i capabilities.sendInvoice=false. Początkowe 503/401 i brak bindingu 404 są rozwiązane.

Rzeczywista ścieżka z bindingu UI:

```text
/sap/opu/odata4/sap/zui_ainv_inv_h_o4/srvd/sap/zui_ainv_inv_h_o4/0001/
```

Wcześniejszy audyt: MCP2.13.3, ZAINVOICER recordChanges=false, aktywne 3 CDS/3 DCL/SRVD i ATC7/7 bez ustaleń. Nowy binding opublikował użytkownik, nie agent; nie wykonywano żadnych zmian IAM.

## Stan H01 — ponowna inspekcja 2026-10-30

Poniższe wyniki pochodzą z ABAP-06. Odczytano źródła i metadane active; lista nieaktywnych obiektów UI jest pusta.

| Obiekt | Ostatni potwierdzony stan |
| --- | --- |
| DDLS ZC_AINV_INV_H_UI | active, SELECT z root, #MANDATORY, _Items/_References, bez behavior |
| DDLS ZC_AINV_INV_I_UI / ZC_AINV_INV_REF_UI | active, #MANDATORY, własny _Parent do nagłówka UI |
| Trzy DCLS o tych samych nazwach | active; ACTVT03 + para NIP/CompanyCode; dziedziczenie dzieci przez _Parent |
| SRVD ZUI_AINV_INV_H_O4 | active; provider contracts odata_v4_ui; Invoices, InvoiceItems, InvoiceReferences |
| SRVB ZUI_AINV_INV_H_O4 | active, V4/0001, published=true po działaniu użytkownika; metadata HTTP200 |
| SUSO Z_AINV_KSF | Cloud; ACTVT/ZAINV_NIP/ZAINV_BUK; aktywności 03 i 16 |
| Root DCL ZR_AINV_INV_H | where TRUE; nie jest izolacją UI |

Nowe ATC **ABAP_CLOUD_DEVELOPMENT_DEFAULT: 7/7, zero ustaleń**. Nie wykonywano zmian ani aktywacji SAP; nie deklarujemy osobnego Syntax Check. Aktualny schemat MCP create dopuszcza tylko bindingType=ODATA, bindingCategory=0/1 (V2). Diagnostyka potwierdza wersję 2.13.3; sama lista typów nie dowodzi uprawnień CREATE/UPDATE/ACTIVATE. Nie tworzyć V2.

Web API ZAPI_AINV_INV_H_O4 ponownie published=true, V4/0001, metadata HTTP200 (hash fff24256b62e0d4a6d7fd916d52f8475ba9f55f66d9793defe823fbc173229bb). Ścieżka z bindingu: `/sap/opu/odata4/sap/zapi_ainv_inv_h_o4/srvd_a2x/sap/zapi_ainv_inv_h_o4/0001/`. READ select/top20 + dwa expand top5: HTTP200, 4 nagłówki / 9 pozycji / 6 referencji, zgodne powiązania UUID, bez dalszych stron. To developer OAuth MCP, **nie UI READ ani test business usera lub CAP Destination**.

UI5 czyta /Invoices i nawigacje _Items/_References. Lokalny mock już ma InvoiceItems/InvoiceReferences. **Zachować te aliasy; nie zmieniać na Items/References.** [Referencję SRVD](../patches/cap05-rap-ui/zui_ainv_inv_h_o4.srvd.srvdsrv) ujednolicono z ostatnim odczytem, ale nadal nie jest eksportem SAP. Nie wdrażać równoległych ZC_AINV_UI_*.

## ADT — najmniejszy krok

1. W Eclipse H01_EN otworzyć ZAINVOICER. Porównać active/inactive trzech DDLS, trzech DCLS i SRVD; sprawdzić blokady, wersję języka i recordChanges. Ostatnio false; pomijać transport dopiero po ponownym potwierdzeniu false. Zachować nieaktywne zmiany użytkownika.
2. Sprawdzić #MANDATORY każdego widoku, tuple NIP/spółka/ACTVT03 w DCL nagłówka i dziedziczenie dzieci. OUTBOUND → SellerNip, INBOUND → BuyerNip. Zweryfikować SUSO Z_AINV_KSF i pola ZAINV_NIP/ZAINV_BUK/ACTVT. Zachować źródła, jeśli zmiana nie jest konieczna.
3. Wyszukać **Service Binding** ZUI_AINV_INV_H_O4 (oddzielnie od SRVD). Istniejący binding otworzyć; nie duplikować. Jeśli brak: New → Other ABAP Repository Object → Business Services → Service Binding; pakiet ZAINVOICER, nazwa ZUI_AINV_INV_H_O4, Service Definition ZUI_AINV_INV_H_O4, typ **OData V4 - UI**.
4. Wykonać adekwatne syntax/ATC checks; aktywować tylko binding i własne konieczne zależności. W edytorze wybrać usługę/wersję i **Publish** lokalnego endpointu. Publikacja nie nadaje ról.
5. Skopiować rzeczywisty **Service URL** z opublikowanej wersji, zapisać typ/wersję/published i aliasy. Nie wyprowadzać URI z nazwy ani z Web API CAP.

Źródła SAP: [Service Binding i publikacja](https://help.sap.com/docs/abap-cloud/abap-rap/service-binding), [edytor V4 i Service URL](https://help.sap.com/docs/ABAP_PLATFORM_NEW/f2e545608079437ab165c105649b89db/674c907e0045479cb18496e37a5301cd.html?locale=en-US). Możliwości ADT nie oznaczają obsługi przez MCP.

## IAM — zadania administratora

**Stan potwierdzony po zakończeniu konfiguracji w ADT:** użytkownik utworzył/zmienił obiekty, agent wykonał wyłącznie odczyty. Szczegółowe odpowiedzialności, dwie ścieżki zależności i wyjaśnienie błędu metadanych są w [modelu IAM](RAP_UI_IAM_MODEL.md); historia kolejnych kroków pozostaje w [dzienniku](../journal/2026-10-30-abap-06-ui5-06.md).

| Element | Potwierdzony wynik |
| --- | --- |
| Domeny/DTEL | ZAINV_D_NIP CHAR10 i ZAINV_D_BUK CHAR4 active; DTEL ZAINV_NIP/ZAINV_BUK wskazują domeny |
| Restriction Fields | ZAINV_RF_NIP → ZAINV_NIP, ZAINV_RF_BUK → ZAINV_BUK; supportsRanges=false |
| Restriction Type | ZAINV_RT_READ: oba RF i Z_AINV_KSF |
| IAM App | ZAINV_UI_READ_EXT: OData V4 ZUI_AINV_INV_H_O4, ACTVT03/NIP5260002008/BUK1000; publishStatus=p |
| Catalog | ZAINV_BC_READ: ZAINV_RT_READ, Read=true/Write=false/F4=false; publishStatus=p |
| Assignment | ZAINV_BC_READ_0001: ZAINV_BC_READ ↔ ZAINV_UI_READ_EXT |
| Runtime UI | najnowszy GET Invoices + expand: HTTP200, 0 rekordów, developer OAuth MCP |
| Rola/użytkownik | efektywne nadania i business-user READ nadal niepotwierdzone |

We wcześniejszym odczycie App brakowało usługi i wartości BUK; obecnie są zapisane. Wcześniejszy błąd Missing metadata for authorization field usunięto według relacji użytkownika po oparciu DTEL na domenach; własny READ potwierdza nowe domeny/DTEL i powiązania RF. Nie powtarzać tworzenia obiektów już istniejących.

W instancji App XML pola BUK jest także dodatkowy pusty wpis obok1000. Sprawdzić listę w edytorze podczas finalnego review; nie interpretować go jako wildcard. Dla pól objętych RT wartości w business role mogą zastąpić odpowiadające im wartości App. Ostateczny zakres wymaga świadomego ustawienia roli i testu użytkownika.

**Następna czynność to krok5: Business Role w Fiori H01.** Kroków1–4 poniżej używać do przeglądu lub odtworzenia konfiguracji, z zachowaniem istniejących obiektów. Nazwy roli/użytkowników pozostają propozycją do potwierdzenia. Agent nie nadał żadnych ról.

1. **IAM App w ADT:** ZAINVOICER → New → Other ABAP Repository Object → Cloud Identity and Access Management → IAM App. Proponowana nazwa bazowa `ZAINV_UI_READ`, typ **EXT – External App**; suffix _EXT jest nadawany przez kreator. W Services → Add wybrać **OData V4**, Service Name **ZUI_AINV_INV_H_O4** przez Ctrl+Space. Dla V4 wybieramy nazwę bindingu z listy, bez zgadywania sufiksu _0001. Jeżeli edytor wymaga nieistniejącego FLP App Descriptor, nie wpisywać fikcyjnego BSP/UI5R; konfigurujemy dostęp do istniejącej usługi dla obecnego UI, nie wdrażamy nowej aplikacji FLP.
2. **Authorizations aplikacji:** dodać istniejący obiekt **Z_AINV_KSF**. Aktywna instancja ma obejmować wyłącznie **ACTVT=03 (Display)** i uzgodniony konkretny zakres. Dla pierwszego zakresu z obecnego seeda: ZAINV_NIP=5260002008, ZAINV_BUK=1000. Nie dodawać ACTVT16/01/02/06 ani wildcard. Sprawdzić wszystkie aktywne instancje obiektu po Synchronize; nie pozostawić drugiej instancji z szerszym nadaniem. Jeśli obiektu brak w propozycjach, można dodać go ręcznie w Authorizations; wspólne Default Authorization Values Web API pozostawić bez zmian.
3. **Restriction Fields/Type:** żeby administrator mógł ustawiać NIP i spółkę per rola, wybrać **New → Other ABAP Repository Object → Restriction Field** (Cloud Identity and Access Management). To inny kreator niż **Authorization Field**: ten drugi wymaga Data Element i ogranicza nazwę do 10 znaków; nie tworzyć ponownie istniejących AUTH ZAINV_NIP/ZAINV_BUK. Utworzyć lub wykorzystać dwa **Restriction Fields (SIA5)**: `ZAINV_RF_NIP` i `ZAINV_RF_BUK`; po utworzeniu w ich edytorach przypisać **Authorization Field** odpowiednio ZAINV_NIP i ZAINV_BUK (nie wpisywać tych wartości jako Data Element). Następnie jeden Restriction Type, np. `ZAINV_RT_READ`, zawierający oba pola i Authorization Object **Z_AINV_KSF**. Zachować i aktywować obiekty. Samo istnienie pól autoryzacyjnych AUTH nie tworzy pól ograniczeń SIA5. Dla jednego stałego zakresu można utrzymać konkretne wartości w IAM App; do ograniczeń edytowanych per rola potrzebne są Restriction Fields/Type.
4. **Business Catalog w ADT:** np. `ZAINV_BC_READ`. W Apps dodać faktyczny ID aplikacji (zwykle ZAINV_UI_READ_EXT), kończąc kreator Business Catalog App Assignment. W Restriction Types dodać powyższy typ i umożliwić utrzymywanie obu pól dla **Read** (oraz Value Help, jeśli używana); Write nie jest wymagane. Zachować i wykonać **Publish Locally** aplikacji/katalogu zgodnie z ich edytorami. Brak NIP/spółki w Maintain Restrictions zwykle oznacza brak odpowiedniego Restriction Type/udostępnienia pola w katalogu, a nie potrzebę poluzowania DCL.
5. **Business Role w Fiori H01:** Maintain Business Roles → New, np. `ZBR_AINV_READ_1000`. Assigned Business Catalogs → Add → wskazać powyższy katalog. Maintain Restrictions: Write=No Access, **Read=Restricted**, NIP=5260002008, CompanyCode=1000; ograniczyć także Value Help, jeśli jest udostępniana. Jedna rola testowa powinna obejmować jedną uzgodnioną parę NIP/spółka. Sprawdzić sumę innych ról użytkownika. Assigned Business Users → dodać wskazanego business usera, zapisać/aktywować zgodnie z aplikacją. To role **wewnątrz ABAP H01**, nie role collection BTP Cockpit.
6. **Ponowny READ:** zalogować business usera na nowo i sprawdzić rzeczywisty URL UI oraz Invoices/_Items/_References; zapisać wynik i zakres. MCP może nadal zwracać 0, jeśli korzysta z innej tożsamości niż użytkownik testowy. Dla właściwej tożsamości z zakresem seeda oczekujemy zgodnych faktur, ale nie zakładamy stałej liczby ani pełnego dostępu na podstawie HTTP200.

Źródła SAP: [IAM dla OData V4 i katalog](https://developers.sap.com/tutorials/abap-environment-deploy-fiori-elements-ui/), [pola/typ ograniczeń](https://developers.sap.com/tutorials/abap-environment-authorization), [udostępnienie ograniczeń w katalogu](https://help.sap.com/docs/sap-btp-abap-environment/abap-environment/creating-business-catalog-with-restriction-types?version=Cloud), [utrzymanie ról/użytkowników](https://developers.sap.com/tutorials/abap-environment-access-mgmt). Instrukcje są dostosowane do własnego Z_AINV_KSF i odczytu ACTVT03; przykładowe szerokie prawa innych tutoriali nie są wymaganiami tego projektu.

Obecne cztery faktury należą do jednej pary NIP/spółka. Nie wystarczą do pełnego dowodu izolacji A/B dla obcego NIP i obcej spółki. Do takich testów potrzebne są inne istniejące syntetyczne przypadki albo osobno autoryzowane przygotowanie danych; nie tworzono ich w tym zadaniu.

Po zaliczeniu IAM/READ: skonfigurować ignorowane proxy /rap-ui na powyższą ścieżkę i tożsamość business usera, następnie lokalny tryb rap. Zachować Send OFF. CAP KsefOperator/trusted claims to odrębny etap; nadanie roli ABAP samo nie autoryzuje CAP.

Nie nadano ról ani uprawnień. Administrator powinien:

1. Zidentyfikować lub przygotować IAM app dla faktycznej usługi UI, business catalog i role; zanotować rzeczywiste identyfikatory oraz publikację app/catalog.
2. Skonfigurować ACTVT=03 i konkretne ZAINV_NIP/ZAINV_BUK; sprawdzić pary i sumę wszystkich ról użytkownika. Bez wildcard, ACTVT16 i CRUD dla przeglądarki.
3. Przypisać uzgodnionych business userów A/B do różnych zakresów. Wskazać istniejące syntetyczne faktury: dozwoloną, obcy NIP przy tej samej spółce, obcą spółkę przy tym samym NIP, INBOUND/OUTBOUND. Brak danych do wariantu = BLOCKED; sprint nie tworzy faktur.
4. Sprawdzić tożsamość przekazywaną przez Fiori tools/approuter do RAP i osobno CAP. CAP wymaga KsefOperator i zaufanych taxpayerNips/companyCodes zgodnych z RAP. Developer OAuth MCP ani technical communication user CAP nie zastępują business usera UI.

## Macierz READ w sesjach A/B

Binding i UI metadata są już potwierdzone. Poniższe przypadki dostępu/izolacji **business userów pozostają BLOCKED / NOT EXECUTED LIVE** do uzyskania właściwych nadań i tożsamości. Odczyt MCP HTTP200/0 nie zalicza tej macierzy. Zapisać użytkownika/zakres (bez sekretów), względny URL, HTTP, count i zgodność UUID; nie publikować pełnych danych faktur.

| Test | Oczekiwany wynik |
| --- | --- |
| A: dozwolony NIP/spółka, OUTBOUND/INBOUND | tylko właściwe nagłówki i dzieci |
| A: obcy NIP, ta sama spółka | brak ujawnienia |
| A: obca spółka, ten sam NIP | brak ujawnienia |
| B: inny zakres; użytkownik bez roli | B widzi własny zakres; bez roli brak danych/dostępu |
| Obcy InvoiceUUID po kluczu | brak zasobu lub odmowa |
| Bezpośrednie InvoiceItems/InvoiceReferences, także po kluczu | brak obcych dzieci |
| Obcy nagłówek z $expand | brak ujawnienia |
| $metadata | zgodne typy/klucze/nawigacje; brak ApplyKsefResult/ApplyKsefLocked i mutujących akcji; kontrola capabilities CRUD |
| $select/$filter/$orderby/$top/$skip/$count | filtr/sort serwerowy, count zgodny z zakresem, stabilne strony bez duplikatów |

HTTP200 z pustą kolekcją może być poprawnym wynikiem DCL. Metadata nie zastępuje osobno autoryzowanych testów odmowy zapisu; podczas READ nie wykonywać mutującego HTTP.

Po weryfikacji EDMX doklejać poniższe ścieżki do **rzeczywistego** Service URL; Edm.Guid bez apostrofów, inne typy kluczy dostosować:

```text
$metadata
Invoices?$select=InvoiceUUID,InvoiceNumber,CompanyCode,Direction,KsefNumber&$orderby=InvoiceNumber,InvoiceUUID&$top=2&$skip=0&$count=true
Invoices?$select=InvoiceUUID,CompanyCode&$filter=CompanyCode eq '<DOZWOLONA_SPOLKA>'&$orderby=InvoiceUUID&$top=2&$skip=2&$count=true
Invoices(<DOZWOLONY_UUID>)?$expand=_Items,_References
Invoices(<OBCY_UUID>)?$expand=_Items,_References
InvoiceItems?$select=ItemUUID,InvoiceUUID&$filter=InvoiceUUID eq <OBCY_UUID>&$top=2&$count=true
InvoiceReferences?$select=RefUUID,InvoiceUUID&$filter=InvoiceUUID eq <OBCY_UUID>&$top=2&$count=true
```

Dodatkowo odczytać obce dzieci po kluczach z metadata. MCP preview wspiera select/top i dwa jednopoziomowe expand; nie wspiera filter/orderby/skip/count, ścieżek po kluczu ani zmiany użytkownika. Te warianty wykonać w uwierzytelnionej sesji business usera.

## Routing SAPUI5

> **Aktualna procedura (zweryfikowana 2026-11-07):** sekcja „ABAP-08 — stan końcowy” na górze tego dokumentu. Szablon używa teraz `authenticationType: reentranceTicket` i ma uzupełniony `pathPrefix`. Poniższe kroki z ABAP-06/07 pozostają jako kontekst historyczny.

1. Skopiować [szablon](../../sapui5/ainvoicer/ui5-rap.yaml.example) do ignorowanego ui5-rap.local.yaml. Uzupełnić origin i pathPrefix z bindingu (bez końcowego ukośnika); zachować ignoreCertError=false.
2. Potwierdzić Destination abap i propagację business usera. Poza BAS użyć wspieranego logowania Fiori tools. Osobna trasa /sap Legacy ma własne placeholdery: uzupełnić istniejącym zweryfikowanym origin/Destination Legacy. Nie kierować jej na RAP UI. /odata/v4/KsefService pozostaje trasą do lokalnego CAP 8080. Sekrety i lokalne hosty pozostają w ignorowanym pliku.
3. Sprawdzić /rap-ui/$metadata i macierz READ w sesji aplikacji. Build szablonu nie testuje proxy, Destination ani logowania.
4. Dopiero po PASS endpointu/IAM ustawić dataMode=rap w lokalnym kontrolowanym wariancie manifestu. Zachować capabilities.sendInvoice=false, ainv.ksef.enabled=false, ainv.sap-write.enabled=false i retry-after-rejection=false. Lokalnej zmiany trybu nie dodawać przypadkowo do Git.
5. Uruchomić `npx fiori run --config ui5-rap.local.yaml --port 8086`. Sprawdzić listę, szczegóły _Items/_References, odświeżenie, filtry dat/kwot, sort i strony po 50. CAP odpytuje wyłącznie UUID strony. Numer RAP ma pierwszeństwo; błąd CAP zachowuje dane SAP i blokuje Send.

Regresja 2026-10-30: oba buildy PASS; QUnit 61/61 (354 asercje), OPA 13/13 (80). To mock RAP i fake CAP/AI/OCR/Legacy. Nowy test AI filtruje mock OData V4 po dacie, kwocie i kierunku. LIVE AI/OCR/STT/Legacy i RAP→UI5 pozostają NOT TESTED.

## Eksport SAP → Git

ABAP-06 potwierdza repoId 000000000001, pakiet ZAINVOICER, linked branch refs/heads/java-cap-ksef. Remote refs: java-cap-ksef=a6150d7, main=832c682; lokalny feature HEAD=origin/main=832c682. Promocja main nie przełączyła SAP. Lokalne .abapgit.xml: STARTING_FOLDER=/abap/src/, FOLDER_LOGIC=FULL; ścieżki statusu SAP są zgodne. Odczyt szczegółu repo przez ADT zwrócił „Resource controller does not support method GET”, więc nie deklarujemy dodatkowej niezależnej kontroli konfiguracji folder logic w SAP.

Status SAP wykazał 20 pozycji unstaged: 17 plików UI w zakresie oraz 3 spoza zakresu (dwa pliki klasy ZCL_ABAPFS_GIT_PROBE i ZCS_AINV_H01). Niczego nie stage'owano. Pełny diff 17 plików przekroczył timeout 60 s; odczyt w partiach 4/4/4/4/1 zakończył się, lecz **wszystkie sześć plików DCLS ma local=""**, mimo poprawnych aktywnych źródeł ADT. Przyczyna wewnętrzna adaptera nieustalona. Nie zapisano pustych DCL ani częściowego eksportu do abap/src. Pełny spójny eksport wymaga ADT/abapGit albo naprawy odczytu serializacji MCP.

W H01_EN → abapGit Repositories → ZAINVOICER sprawdzić STARTING_FOLDER=/abap/src/, FOLDER_LOGIC=FULL i kompletny diff. Wybrać tylko trzy DDLS, trzy DCLS, SRVD i SRVB; zachować metadane serializowane przez SAP. Przejrzeć każde ADD/MODIFY/DELETE, bez niepowiązanych usunięć. Commit/push wymaga osobnego jawnego zlecenia. Po autoryzowanym eksporcie integrować do feature wyłącznie sprawdzone pliki, z zachowaniem pracy lokalnej. Nie importować historycznych patchy ani ręcznie tworzyć *.srvb.xml.

Następny etap: IAM i READ business userów → podłączenie UI5 → osobno autoryzowany CAP-04D HTTP WRITE → [E2E-01](E2E_01_SAP_TO_KSEF_TEST.md). Nowego SEND i HTTP WRITE nie wykonano.
