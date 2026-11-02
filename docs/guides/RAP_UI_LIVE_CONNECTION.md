---
title: "RAP UI — połączenie LIVE z SAPUI5"
date: 2026-10-30
type: guide
status: zablokowany
tags: [ainvoicer, rap, abap, sapui5, iam, odata, abap-06]
---

# RAP UI — połączenie LIVE

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
