---
title: "RAP UI — połączenie LIVE z SAPUI5"
date: 2026-10-29
type: guide
status: zablokowany
tags: [ainvoicer, rap, abap, sapui5, iam, odata, abap-05]
---

# RAP UI — połączenie LIVE

[STATUS](../STATUS.md) · [Dziennik i wyniki](../journal/2026-10-29-abap-05-real-rap-ui.md) · [Patch/runbook](../patches/cap05-rap-ui/README.md) · [Kokpit](SAPUI5_KSEF_COCKPIT.md)

**BLOCKED:** binding V4 UI nie istnieje pod nazwą `ZUI_AINV_INV_H_O4` (resolve HTTP 404). Nie ma zweryfikowanego URI UI ani testów dwóch business userów. Domyślny `dataMode` pozostaje `mock`. Nie kierować przeglądarki na Web API używane przez CAP.

## Stan rzeczywisty H01

Inspekcja MCP 2.13.3, połączenie `h01`, pakiet `ZAINVOICER`, `recordChanges=false`:

| Obiekt | Potwierdzony stan |
| --- | --- |
| DDLS `ZC_AINV_INV_H_UI` | aktywny zwykły SELECT z root, `#MANDATORY`, `_Items` i `_References`; bez projekcji behavior |
| DDLS `ZC_AINV_INV_I_UI`, `ZC_AINV_INV_REF_UI` | aktywne, `#MANDATORY`, własna asocjacja `_Parent` do nagłówka UI |
| DCLS o tych samych trzech nazwach | aktywne; nagłówek sprawdza parę NIP/CompanyCode i ACTVT03; dzieci dziedziczą warunek z zamianą root na `_Parent` |
| SRVD `ZUI_AINV_INV_H_O4` | aktywny, `provider contracts odata_v4_ui`; aliasy `Invoices`, **`InvoiceItems`, `InvoiceReferences`** |
| SRVB `ZUI_AINV_INV_H_O4` | nie odnaleziono, HTTP 404; definicja SRVD o tej samej nazwie nie jest bindingiem |
| `Z_AINV_KSF` | aktywny obiekt Cloud; pola ACTVT, ZAINV_NIP, ZAINV_BUK; zawiera aktywności 03 i 16 |
| root DCL `ZR_AINV_INV_H` | nadal `where TRUE`; nie używać jako izolacji UI |

Wszystkie siedem obiektów CDS/DCL/SRVD przeszło ATC `ABAP_CLOUD_DEVELOPMENT_DEFAULT`: 0 ustaleń. Diagnostyka edytora SRVD zwróciła 0 problemów, lecz pusty zbiór diagnostyk nie jest osobnym wykonanym Syntax Check. Nie uruchamiano ABAP Unit, nie zapisywano ani nie aktywowano obiektów SAP w tej sesji. Obiekty UI były zastane, nowsze od eksportu Git.

OUTBOUND używa SellerNip, INBOUND BuyerNip. To odpowiada fakturom sprzedażowym i zakupowym modelu; pozostałe kierunki nie mają gałęzi dopuszczającej dostęp. Warunek tuple zachowuje parę NIP/spółka. Zwykły SELECT nie dziedziczy automatycznie root DCL, dlatego każdy z trzech widoków UI ma własny DCL. ATC i odczyt źródła nie potwierdzają nadań IAM ani efektywnego wyniku zapytania dla użytkownika.

## Najmniejszy krok ADT

1. W `H01_EN`, pakiet `ZAINVOICER`, porównać active/inactive siedmiu istniejących obiektów. Nie tworzyć drugiego zestawu `ZC_AINV_UI_*`. Ponownie sprawdzić blokady i `recordChanges`; przy `false` nie wybierać transportu.
2. W SRVD uzgodnić aliasy dzieci z wymaganym kontraktem `Items`/`References`. [Plik SRVD](../patches/cap05-rap-ui/zui_ainv_inv_h_o4.srvd.srvdsrv) jest przygotowanym docelowym źródłem opartym na istniejących widokach. Różnica względem aktywnego SAP dotyczy aliasów; **patch nie jest aktywowany ani eksportem abapGit**. Nawigacje UI5 `_Items`/`_References` nie zależą od tych aliasów entity set.
3. Po review i Syntax Check aktywować tylko uzgodnioną zmianę. Utworzyć **Service Binding → OData V4 - UI**, `ZUI_AINV_INV_H_O4`, dla istniejącej definicji o tej samej nazwie; aktywować i opublikować lokalny endpoint w H01. MCP create dopuszcza wyłącznie `ODATA`/kategorie V2 — nie tworzyć V2 i nie zgadywać XML SRVB.
4. Skopiować **Service URL** z opublikowanej wersji bindingu. Potwierdzić protokół, wersję i entity sets. Nie wyprowadzać URL z nazwy obiektu ani z Web API.
5. Po testach przygotować eksport siedmiu obiektów i bindingu przez abapGit, kontrolując add/modify/delete. Nie importować starego patcha do SAP. Eksport/push na `java-cap-ksef` wymaga osobnego zlecenia; autoryzacja tego sprintu obejmuje Git wyłącznie własnej gałęzi feature.

SAP opisuje utworzenie bindingu V4 UI i pobranie URL w [Service Binding](https://help.sap.com/docs/abap-cloud/abap-development-tools-for-visual-studio-code/creating-service-binding) oraz [edytorze V4](https://help.sap.com/docs/ABAP_PLATFORM_NEW/f2e545608079437ab165c105649b89db/674c907e0045479cb18496e37a5301cd.html?locale=en-US). Dostępność funkcji SAP ADT nie oznacza obsługi jej przez bieżący MCP.

## IAM — czynności administratora

Nie zmieniono żadnego nadania. Administrator powinien powiązać usługę z właściwą IAM app, business catalog i business role, udostępnić ograniczenia NIP/spółki i przypisać role dwóm uzgodnionym business userom. Nazwy tych obiektów i użytkowników nie są jeszcze potwierdzone. Nadanie odczytu: `Z_AINV_KSF`, ACTVT **03**, konkretne pary ZAINV_NIP/ZAINV_BUK, bez wildcard i bez ACTVT16/CRUD. Sprawdzić sumę wszystkich ról użytkownika, aby inna rola nie rozszerzała zakresu.

Przeglądarka ma działać w kontekście business usera. Nie używać szerokiego technical communication usera, hasła w JS ani tokenu deweloperskiego MCP jako sesji UI. CAP osobno wymaga KsefOperator i zaufanych atrybutów taxpayerNips/companyCodes zgodnych z zakresem RAP. Konfiguracja IAS/XSUAA/approutera nie została zweryfikowana LIVE.

## Test READ — macierz do wykonania po publikacji

Wszystkie poniższe testy **NOT EXECUTED dla UI LIVE**. Testować dwoma niezależnymi sesjami użytkowników na znanych syntetycznych fakturach; przygotować przypadki różniące się wyłącznie NIP oraz wyłącznie CompanyCode.

| Test | Oczekiwanie |
| --- | --- |
| A: dozwolony NIP/spółka, OUTBOUND i INBOUND | widoczne wyłącznie właściwe nagłówki, zgodne UUID i nawigacje |
| A: obcy NIP przy tej samej spółce | brak obcych faktur, także po kluczu |
| A: obca spółka przy tym samym NIP | brak obcych faktur |
| B: inny zakres | inny dozwolony zbiór; brak zakresu A |
| Bezpośrednie `Items` i `References` obcej faktury | brak obcych dzieci, także po UUID i przez `$expand` |
| `$metadata` | aliasy i typy zgodne; brak ApplyKsefResult/ApplyKsefLocked, mutujących akcji i możliwości CRUD w capabilities |
| READ `$filter/$orderby/$top/$skip/$count/$expand` | filtr i sort serwerowy, spójny count i strony bez duplikatów; poprawne dzieci |

DCL zwykle filtruje kolekcję do pustego zbioru; HTTP 200 nie oznacza naruszenia. Dla odczytu po kluczu dopuszczalny jest brak zasobu/odmowa. Nie sprawdzać zakazu CRUD przez wykonanie mutującego HTTP w tym sprincie. Inspekcja metadata nie zastępuje przyszłych, osobno autoryzowanych testów odmowy zapisu.

Przykładowe zapytania względne, doklejane wyłącznie do **rzeczywistego URL bindingu**:

```text
$metadata
Invoices?$select=InvoiceUUID,InvoiceNumber,CompanyCode,Direction,KsefNumber&$orderby=InvoiceNumber,InvoiceUUID&$top=2&$skip=0&$count=true
Invoices?$select=InvoiceUUID,CompanyCode&$filter=CompanyCode eq '<DOZWOLONA_SPOLKA>'&$orderby=InvoiceUUID&$top=2&$skip=2&$count=true
Invoices(<DOZWOLONY_UUID>)?$expand=_Items,_References
Items?$select=ItemUUID,InvoiceUUID&$filter=InvoiceUUID eq <OBCY_UUID>
References?$select=RefUUID,InvoiceUUID&$filter=InvoiceUUID eq <OBCY_UUID>
```

Przed zmianą aliasów ostatnie dwa entity sets nazywają się w aktywnym SRVD InvoiceItems/InvoiceReferences. MCP query obsługuje obecnie tylko select/top i dwa jednopoziomowe expand; nie obsługuje filter/orderby/skip/count ani przełączania użytkownika. Nie raportować tych opcji jako zaliczonych na podstawie odczytu Web API.

## Routing SAPUI5

[ui5-rap.yaml.example](../../sapui5/ainvoicer/ui5-rap.yaml.example) to osobny szablon Fiori tools, bez mockservera. Zbudowano go lokalnie, lecz nie uruchamiano z H01. Obsługę `pathPrefix` potwierdzono w zainstalowanym `@sap-ux/ui5-config` i `fiori-tools-proxy`.

1. Skopiować do ignorowanego `sapui5/ainvoicer/ui5-rap.local.yaml`. Wpisać origin i `pathPrefix` z bindingu (bez końcowego ukośnika); zachować `ignoreCertError=false`.
2. BAS: potwierdzić istnienie destination `abap` oraz rzeczywistą tożsamość użytkownika. Obecne konfiguracje Legacy wskazują inną nazwę `abap-cloud-abap-1ebf3ff5trial-dev`; nie dowodzi to istnienia ani polityki `abap`. Poza BAS samo pole destination nie zapewnia logowania; użyć wspieranego logowania business usera Fiori tools, bez wprowadzania haseł do plików.
3. Sprawdzić `/rap-ui/$metadata` i dozwolone READ w sesji aplikacji. Trasa mapuje prefiks `/rap-ui` na pełną ścieżkę zweryfikowanej usługi; nazwa destination nie zastępuje ścieżki.
4. Zachować `/odata/v4/KsefService` do lokalnego CAP i `/sap` dla Legacy. Docelowy approuter wymaga osobnych tras RAP UI/CAP/Legacy oraz właściwego auth; nie wdrożono go w tym zadaniu. Nie dodano CAP proxy faktur.
5. Dopiero po PASS endpointu i IAM zmienić w kontrolowanym wariancie manifestu `dataMode` na `rap`. W trakcie walidacji READ utrzymać `ainv.ksef.enabled=false`, `ainv.sap-write.enabled=false` i `capabilities.sendInvoice=false`. Uruchomić `npx fiori run --config ui5-rap.local.yaml --port 8086`.
6. W UI sprawdzić listę, refresh, filtr, sort, strony, szczegóły i wynik RAP; CAP pobiera stan wyłącznie dla UUID strony. Brak/niepełny count, wadliwy rekord, obcy UUID lub błąd CAP blokuje Send i zachowuje dane SAP. HTTP 200 requestSend oznacza tylko QUEUED.

AI tłumaczy wyłącznie białą listę pól/operatorów na Filter/Sorter UI5. Edm.Date używa ISO YYYY-MM-DD, kwoty są liczbami; nie wykonuje SQL z LLM. QUnit potwierdza typy/filtry na mock RAP; OPA pokrywa AI/Scanner/Legacy/Voice controls. Rzeczywistych AI/OCR/STT i Legacy H01 nie wywoływano.

## CAP-04D — przygotowane, bez WRITE

Aktualny kod ma jedną Destination `AINV_H01` dla RemoteService `AinvoicerH01`; w tej sesji brak env destinations i VCAP_SERVICES. Nie potwierdzono destination BTP, auth, communication arrangement ani uprawnień communication usera. Dostęp OAuth MCP do Web API tego nie zastępuje.

Inspekcja i lokalna regresja potwierdzają: CSRF enabled, wywołanie statycznej akcji, ExpectedLocalLastChangedAt z migawki, niezmienny WriteRequestId=operationId, atomowy outbox, CAS, idempotencję, VERSION_CONFLICT bez ponownego SEND. Timeouty: connect/pool 5 s, read 20 s; lease 2 min, renew 20 s; nie stanowią twardego limitu całej operacji. Transport SAP pozostaje OFF.

Plan jednego przyszłego E2E (wymaga osobnego zlecenia SEND i HTTP WRITE):

1. Zaliczyć UI READ/IAM powyżej; wskazać jedną syntetyczną fakturę OUTBOUND z poprawnym NIP, spółką, sumami, PartyData, trwałą testową DB i magazynem XML/UPO. Sprawdzić FA(3) bez SEND i utrwalić wersję/hash. Zapewnić principalowi CAP ACTVT16 w tym jednym zakresie, bez nieuzgodnionego CRUD; zweryfikować CSRF i Destination.
2. Dopiero po zgodzie włączyć właściwe transporty TEST w dedykowanym procesie. Jedno UI requestSend → reservation/snapshot/outbox → SDK → końcowe 200 z numerem/hash/ref/NIP. Zapisać operationId, bez sekretów.
3. Potwierdzić commit ACCEPTED przed enqueue, writeback APPLIED/SUCCEEDED i niezależny GET czterech pól RAP. UPO niezależnie pobrane; InvoiceStatus bez zmian. Drugi requestSend ma zwrócić 409 bez drugiego SEND. Sprawdzić restart.

Po VERSION_CONFLICT zachować ACCEPTED/ExpectedVersion/WriteRequestId; wyłącznie read-only diagnose i decyzja operatora. Szczegółowy [runbook WRITE](SAP_WRITE_ACCESS_AND_READ_POLICY.md) pozostaje obowiązujący. Tego E2E **nie wykonano**.
