---
title: "RAP UI — połączenie LIVE z SAPUI5"
date: 2026-10-30
type: guide
status: zablokowany
tags: [ainvoicer, rap, abap, sapui5, iam, odata, abap-06]
---

# RAP UI — połączenie LIVE

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

**Własne wyniki po publikacji:** Invoices (top5 + dwa expand), InvoiceItems i InvoiceReferences zwracają HTTP200 i puste rows, bez dalszych stron. Web API zwraca 4 nagłówki; wszystkie mają zakres NIP **5260002008**, CompanyCode **1000** (dla OUTBOUND SellerNip, INBOUND BuyerNip). Dane są syntetyczne; nie są potwierdzeniem poprawności NIP dla przyszłego KSeF SEND. Aktualny DCL nadal wymaga Z_AINV_KSF/ACTVT03 i pary NIP/spółka. Najbardziej prawdopodobny brak to pasujące nadanie, ale nie wykonano trace autoryzacji ani audytu wszystkich efektywnych ról.

Where-used bindingu nie zwróciło obiektów; where-used Z_AINV_KSF zwróciło DCL/BDEF/klasę, bez IAM. Metadane SUSO mają puste sia2References. Nie jest to pełny spis katalogów/ról. Najpierw sprawdzić w ADT istniejące obiekty Cloud Identity and Access Management w ZAINVOICER i użyć właściwych istniejących obiektów zamiast tworzyć duplikaty.

Poniższe nazwy są **propozycją nowych obiektów**, nie stwierdzeniem, że istnieją. Czynności wykonuje deweloper/administrator w swoim autoryzowanym zakresie; agent niczego nie utworzył ani nie nadał.

**Warunek wstępny wykryty przy tworzeniu RF:** użytkownik otrzymał „Missing metadata for authorization field ZAINV_NIP”. Odczyt live AUTH potwierdził istniejące pola, ale puste domname/checkTable; aktywne DTEL ZAINV_NIP i ZAINV_BUK mają typeKind=predefinedAbapType, CHAR10/CHAR4, bez domen. Dokumentacja ADT [Defining Authorization Fields](https://help.sap.com/docs/abap-cloud/abap-development-tools-user-guide/defining-authorization-fields) wymaga elementu danych opartego na domenie. Wcześniejsza instrukcja pominęła ten warunek. To potwierdzony brak modelu DDIC; usunięcie go i ponowna walidacja są konieczne przed uznaniem RF za poprawne. Sam brak check table nie został potwierdzony jako przyczyna i nie uzasadnia automatycznego tworzenia tabel wartości.

Procedura naprawy w ADT, przed krokiem 3: utworzyć domeny (proponowane nazwy `ZAINV_D_NIP` CHAR10 i `ZAINV_D_BUK` CHAR4) w ZAINVOICER po kontroli kolizji, aktywować je; w istniejących **Data Elements** ZAINV_NIP/ZAINV_BUK przełączyć definicję z Predefined Type na Domain i wskazać właściwą domenę, zachowując dotychczasowe typy/długości/etykiety. Aktywować DTEL i ponownie odczytać metadane AUTH. Potem ponownie otworzyć RF, ustawić Authorization Field przez podpowiedź, zapisać i sprawdzić błąd. Nie tworzyć duplikatów AUTH ani nie zmieniać DCL. Nie uznawać poprawki za PASS bez ponownej walidacji RF. Jeśli błąd pozostanie, zbadać value-help/check-table według [SAP KBA3738197](https://userapps.support.sap.com/sap/support/knowledge/en/3738197), z uwzględnieniem właściwego ABAP Cloud; KBA dotyczy S/4HANA Cloud i nie dowodzi przyczyny w H01.

Na moment diagnozy nowy SIA5 ZAINV_RF_NIP miał puste zapisane pfcgFieldName; wyszukiwanie ZAINV*NIP* / ZAINV*BUK* nie wykazało domen. **Późniejsza kontynuacja:** użytkownik zgłosił naprawę NIP i BUK, a własny ponowny READ potwierdza zapisane ZAINV_RF_NIP → ZAINV_NIP oraz ZAINV_RF_BUK → ZAINV_BUK, oba supportsRanges=false. Brak powiązania RF jest już rozwiązany; następny krok to wspólny Restriction Type ZAINV_RT_READ (odczyt wskazanego obiektu: nie odnaleziono). Agent nie zmienił DDIC ani IAM; użytkownik edytuje te obiekty w ADT.

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
