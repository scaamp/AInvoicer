---
title: "AInvoicer — CAP Java → SAP H01: Destination i RAP OData V4"
date: 2026-10-27
type: guide
status: "CAP-03: LIVE PASS lokalnie; CF binding do weryfikacji"
tags: [ainvoicer, cap-java, sap-btp, abap-cloud, destination, odata-v4]
---

# CAP Java → SAP H01 przez Destination: instrukcja odtworzenia

> **Co udało się zrobić 27.10.2026:** z lokalnego procesu **CAP Java + RemoteService + CQN** wywołaliśmy rzeczywiste RAP OData V4 w SAP H01, uzyskując **4 faktury, 9 pozycji, 6 referencji** i zgodne `InvoiceUUID`. Test `SapInvoiceRemoteAdapterLiveTest`: **1 PASS, 0 FAIL, BUILD SUCCESS**. Było to połączenie przez lokalną zmienną `destinations` (SAP Cloud SDK); **nie** test wdrożenia CAP na Cloud Foundry.

## 0. Zweryfikowany stan i źródła

- Repo: https://github.com/scaamp/AInvoicer ; branch `java-cap-ksef`.
- Najnowszy sprawdzony commit: [c06da983 — Connection to SAP from CAP](https://github.com/scaamp/AInvoicer/commit/c06da98396c4ccc4f990f314caba9fedef0e13b9) (27.10.2026).
- Poprzednia implementacja: [6bc9da2 — Add H01 CAP remote integration](https://github.com/scaamp/AInvoicer/commit/6bc9da2f2db57ba4cfeca98980e2e7ecc1fef4ce).
- Źródło wyników pracy Claude Code: [dziennik CAP-03](../journal/2026-10-27-cap-03-sap-remote-integration.md), [STATUS](../STATUS.md) i [ROADMAP](../ROADMAP.md).
- Potwierdzenie ręczne: obrazy Communication Scenario/System/Arrangement, Destination w BTP Cockpit oraz wynik `curl` od użytkownika (HTTP 200, `sap-authenticated: true`, 4 faktury).
- Instrukcja powstała z analizy repo i wyników; przy jej tworzeniu **nie powtarzano** testu LIVE.
- Oficjalne referencje: [CAP Java Remote Services](https://cap.cloud.sap/docs/java/cqn-services/remote-services), [SAP Communication Arrangement](https://help.sap.com/docs/abap-cloud/abap-integration-connectivity/communication-arrangement).

### Identyfikatory zastosowane w AInvoicer

| Element | Nazwa |
|---|---|
| System / pakiet SAP | H01 (`H01_EN` w ADT), `ZAINVOICER`, SAP client 100 |
| RAP Service Binding | `ZAPI_AINV_INV_H_O4`, OData V4, wersja `0001` |
| Inbound Service ID | `ZAPI_AINV_INV_H_O4_0001_G4BA` |
| Communication Scenario | `ZCS_AINV_H01` |
| Communication System | `AINV_CAP` |
| Communication User | `AINV_CAP_USER` |
| Communication Arrangement | `ZCS_AINV_H01` (nazwa użyta na H01) |
| Destination w BTP i lokalnym SDK | `AINV_H01` |
| CAP external service | `AinvoicerH01` |
| Adapter Java | `SapInvoiceRemoteAdapter` |

H01 zawiera **symulacyjne** faktury w tabelach `ZAINV_INV_H` / `ZAINV_INV_I` / `ZAINV_INV_REF`, a nie rzeczywiste dane standardowego SAP S/4HANA.

## 1. Schemat połączenia

```text
SAP H01: ZAINV_INV_H/I/REF → CDS/RAP → ZAPI_AINV_INV_H_O4/0001
               ↑        (Inbound HTTPS / Basic Auth)
               │
 Communication Scenario / System / Arrangement
               ↑
       SAP Cloud SDK Destination AINV_H01
               ↑
CAP Java RemoteService (AinvoicerH01)
               ↑
 CQN / SapInvoiceRemoteAdapter.readInvoices()

Lokalnie:  Destination z env "destinations".
Na CF:     Destination z BTP Cockpit + binding usługi do aplikacji.
           (Wariant CF: jeszcze NIE przetestowany).
```

## 2. Przygotowanie RAP OData

W Eclipse ADT na H01 w pakiecie `ZAINVOICER` otwórz Service Binding `ZAPI_AINV_INV_H_O4`. Sprawdź **Published**, typ **OData V4**, wersję **0001** oraz encje:

- `ZC_AINV_INV_H` — nagłówki; klucz `InvoiceUUID`;
- `ZC_AINV_INV_I` — pozycje; klucz `ItemUUID`;
- `ZC_AINV_INV_REF` — referencje; klucz `RefUUID`;
- nawigacje nagłówka `_Items` i `_References`.

Ścieżka API bez hosta:

```text
/sap/opu/odata4/sap/zapi_ainv_inv_h_o4/srvd_a2x/sap/zapi_ainv_inv_h_o4/0001/
```

Adres `localhost:.../testclient/...` ze Swaggera w ADT nie jest endpointem do konfiguracji zewnętrznego CAP. `releaseState=NOT_RELEASED` nie oznaczało braku działającej usługi w H01.

## 3. Eclipse ADT — Communication Scenario

Wybierz pakiet `ZAINVOICER` → **New → Other ABAP Repository Object → Communication Management → Communication Scenario**.

1. Nazwa: `ZCS_AINV_H01`, typ `Customer`.
2. Allowed Instances: w tej konfiguracji `One instance per scenario & communication system`.
3. Dodaj **Inbound Service** (przez Browse):
   - Inbound ID: `0001`;
   - Inbound Service ID: `ZAPI_AINV_INV_H_O4_0001_G4BA`;
   - Service Type: `OData V4`.
4. Zapisz, aktywuj i **Publish Locally**. Sprawdź status `Published`.

CAP **wywołuje** H01, więc z punktu widzenia H01 to **Inbound**.

## 4. Fiori H01 — Communication User i Communication System

### Communication Users

Utwórz `AINV_CAP_USER` z metodą **User ID and Password**. To techniczny użytkownik odczytu RAP OData. Nie używaj konta dialogowego Eclipse/ADT.

**UWAGA: dziennik Claude Code dokumentuje, że podczas diagnostyki linia z hasłem z lokalnego `.env.live` przypadkowo pojawiła się w logu agenta. Hasło `AINV_CAP_USER` należy ZROTOWAĆ, a potem zaktualizować w Destination BTP i lokalnej konfiguracji.**

### Communication Systems

W aplikacji **Communication Systems** utwórz:

- System ID: `AINV_CAP`;
- System Name: `AINV_CAP`;
- preferuj **Inbound Only**;
- w **Users for Inbound Communication** dodaj `AINV_CAP_USER`, `User ID and Password`;
- **Users for Outbound Communication** pozostaw puste.

**Faktycznie napotkany błąd:** na początku użytkownik został podpięty do **Outbound**, więc Inbound wskazywało `No data`. Należało przepiąć na **Inbound**.

W formularzu SAP wymagał Host Name albo konfiguracji Destination Service. W naszym inbound-only systemie użyto technicznie **hosta H01** (bez `https://` i bez ścieżki), a Destination Service po stronie SAP zostawiono wyłączone. Nie oznacza to połączenia outbound H01 → CAP. Cloud Connector nie jest tu potrzebny.

Zapisz system.

## 5. Fiori H01 — Communication Arrangement

Otwórz **osobną aplikację Communication Arrangements** (zakładka w Communication System pokazuje powiązania, ale nie ma tam tworzenia nowego arrangementu).

1. **New**, wybierz scenario `ZCS_AINV_H01`.
2. Nazwa arrangementu przyjęta w H01: `ZCS_AINV_H01`.
3. Communication System: `AINV_CAP`.
4. Inbound User: `AINV_CAP_USER`; Authentication: `User ID and Password`.
5. Inbound Services: `ZAPI_AINV_INV_H_O4` / `OData V4`.
6. **Save** i sprawdź, że nie zostały niezapisane zmiany draftu.
7. Skopiuj **Service URL** (Inbound Services).

W naszym systemie format adresu jest następujący:

```text
https://<H01-ID>.abap.eu20.hana.ondemand.com/sap/opu/odata4/sap/zapi_ainv_inv_h_o4/srvd_a2x/sap/zapi_ainv_inv_h_o4/0001/
```

Rozdziel adres na:
- **Origin**: `https://<H01-ID>.abap.eu20.hana.ondemand.com`;
- **ścieżkę OData**: `/sap/opu/odata4/.../0001/`.

Destination będzie wskazywać tylko **origin**, a CAP Java dołoży ścieżkę.

## 6. SAP BTP Cockpit — Destination

W subaccount `dev`: **Connectivity → Destinations → Create → From Scratch**.

| Property | Wartość |
|---|---|
| Name | `AINV_H01` |
| Type | `HTTP` |
| Proxy Type | `Internet` |
| URL | `https://<H01-ID>.abap.eu20.hana.ondemand.com` — tylko origin! |
| Authentication | `BasicAuthentication` |
| User | `AINV_CAP_USER` |
| Password | aktualne hasło communication usera (bez umieszczania w Git) |
| Use default client trust store | zaznaczone |

Po zapisaniu kliknij **Check Connection**. Wynik ze screena:

> HTTP request (without authentication) to AINV_H01 destination succeeded.

**Interpretacja:** dostępność hosta działa; **Basic Auth i OData nie zostały przez ten test sprawdzone**.

## 7. Test API niezależny od CAP (PowerShell)

```powershell
curl.exe -i --user AINV_CAP_USER 'https://<H01-ID>.abap.eu20.hana.ondemand.com/sap/opu/odata4/sap/zapi_ainv_inv_h_o4/srvd_a2x/sap/zapi_ainv_inv_h_o4/0001/ZC_AINV_INV_H?$top=5'
```

`curl.exe` pyta o hasło. Nie wpisuj hasła w command line, pliku .ps1 ani logu. Cudzysłów **pojedynczy** wokół URL zabezpiecza `$top` / `$expand` przed interpretacją przez PowerShell.

**Rzeczywisty wynik:**

```text
HTTP/1.1 200 OK
sap-authenticated: true
content-type: application/json;odata.metadata=minimal;charset=utf-8
odata-version: 4.0
body: 4 faktury FI_AP, FI_AR, MM_IV, SD_BILL
```

Osobny live odczyt RAP `$expand=_Items,_References` przez MCP/preview potwierdził 4/9/6. Nadal nie był to jeszcze test CAP.

## 8. CAP Java — model zewnętrzny i Maven

W `cap-java/srv/ainv-h01.cds` znajduje się **ręcznie przygotowany** model:

```cds
@cds.external: true
@requires: 'internal-user'
service AinvoicerH01 {
  @cds.persistence.skip
  entity ZC_AINV_INV_H {
    key InvoiceUUID : UUID;
        InvoiceNumber : String(35);
        _Items : Association to many ZC_AINV_INV_I
            on _Items.InvoiceUUID = $self.InvoiceUUID;
        _References : Association to many ZC_AINV_INV_REF
            on _References.InvoiceUUID = $self.InvoiceUUID;
  }
  // Pełen model zawiera też ZC_AINV_INV_I i ZC_AINV_INV_REF
  // oraz wszystkie potrzebne pola: patrz źródło repo.
}
```

**Nie mylić z importem oryginalnego EDMX**: model zbudowano ze znormalizowanych wyników `$metadata` (MCP) i opisów pól. Nie uzyskano surowych bajtów RAP EDMX ani nie potwierdzono `cds import`. Wygenerowany przez CAP EDMX `AinvoicerH01` opisuje model CAP.

W `cap-java/srv/pom.xml` dodano:

```xml
<dependency>
  <groupId>com.sap.cds</groupId>
  <artifactId>cds-feature-remote-odata</artifactId>
  <scope>runtime</scope>
</dependency>
<dependency>
  <groupId>com.sap.cloud.sdk.cloudplatform</groupId>
  <artifactId>scp-cf</artifactId>
  <scope>runtime</scope>
</dependency>
```

W `cap-java/pom.xml` jest SAP Cloud SDK BOM `5.33.0`. Projekt działał z CAP Java `5.2.0`, Spring Boot `4.1.1`, JDK `25` i Maven `3.9.16`.

## 9. CAP Java — poprawna konfiguracja YAML po Claude Code

Ścieżka: `cap-java/srv/src/main/resources/application.yaml`. Część Remote Service:

```yaml
---
# Ten fragment YAML NIE jest ograniczony do profilu default.
cds:
  remote.services:
    AinvoicerH01:
      model: AinvoicerH01
      type: odata-v4
      destination:
        name: AINV_H01
      http:
        service: sap/opu/odata4/sap/zapi_ainv_inv_h_o4/srvd_a2x/sap/zapi_ainv_inv_h_o4/0001
```

Pozostałe ustawienia H2 są w pierwszym dokumencie YAML, aktywnym dla `default`. Fragment Remote Service znajduje się w osobnym dokumencie **bez profilu** — musi działać również w profilu `test`.

**Błąd wykryty przez Claude Code:** pierwotnie `http.suffix` zawierał ścieżkę, `http.service: ""` pozostawiono puste. CAP zignorował pusty `service` i dodał nazwę modelu, dając:

```text
NIEPOPRAWNIE: /0001/AinvoicerH01/ZC_AINV_INV_H
POPRAWNIE:   /0001/ZC_AINV_INV_H
```

Zastosowana w repo poprawka: **pełna ścieżka RAP w `http.service`, a sam host w Destination**.

## 10. Java — rzeczywisty RemoteService i CQN

`SapInvoiceRemoteAdapter.java` wstrzykuje `@Qualifier("AinvoicerH01") CqnService remote` i wykonuje trzy osobne zapytania CQN:
1. `Select.from("AinvoicerH01.ZC_AINV_INV_H")`;
2. pozycje, filtrowane po UUID odczytanych nagłówków;
3. referencje, filtrowane po tych samych UUID.

Wynik jest grupowany w `SapInvoiceSnapshot` bez kopiowania danych SAP do H2 i bez zmiany źródłowych UUID.

**Poprawki Claude Code:**

1. `@requires: 'internal-user'` dawało `Not authenticated` w odczycie bez użytkownika (worker/test). Teraz adapter wykonuje odczyt:

```java
return runtime.requestContext().systemUser()
    .run((RequestContext ctx) -> doReadInvoices());
```

**Bezpieczeństwo:** systemowy użytkownik CAP nie oznacza uprawnień per NIP. Każdy publiczny przepływ korzystający z tego adaptera musi osobno sprawdzać prawa użytkownika/tenanta/CompanyCode.

2. Filtr `InvoiceUUID in (...)` (operator OData 4.01) zastąpiono filtrami `eq` połączonymi `or`:

```java
Predicate filter = invoiceIds.stream()
    .<Predicate>map(id -> get("InvoiceUUID").eq(id))
    .reduce(Predicate::or)
    .orElseThrow();
```

Użyty RAP H01 przyjął tę formę. Dla większych wolumenów potrzeba stronicowania i batchowania.

## 11. Test HTTP Contract: prawdziwy CAP, ale fałszywy SAP

`SapInvoiceRemoteAdapterHttpContractTest.java` używa prawdziwego CAP RemoteService, SAP Cloud SDK Destination i lokalnego serwera HTTP z fikcyjnymi poświadczeniami. Sprawdza składanie URI (3 encje) oraz nagłówek `Authorization: Basic ...`.

To **nie LIVE H01**, ale test ujawnił błędny segment `AinvoicerH01`, którego wcześniejszy mock `CqnService` nie wykrył.

Po poprawkach zwykłe testy:

```powershell
cd cap-java
mvn clean verify '-Dcds.cds.skipVersionCheck=true'
```

Według dziennika: **23 testy PASS**, grupa LIVE pominięta. `skipVersionCheck` jest obejściem problemu z lokalnym `cds version` / `@sap/cds-dk`, nie naprawą tej przyczyny.

## 12. Odtworzenie testu LIVE lokalnie

### 12.1 Bezpieczne przygotowanie

Root `.gitignore` zawiera `.env*`. W katalogu `cap-java` plik `.env.live` **nie jest wersjonowany**, ale zawiera hasło w plaintext. Na potrzeby odtworzenia *istniejącego skryptu* jego format to:

```dotenv
H01_URL=https://<H01-ID>.abap.eu20.hana.ondemand.com
H01_USER=AINV_CAP_USER
H01_PASSWORD=<NOWE_HASŁO_PO_ROTACJI>
```

**Użyj tylko origin**. Sprawdź `git check-ignore cap-java/.env.live`. Nie przekazuj agentowi zawartości pliku, nie wypisuj go w logach, nie udostępniaj zrzutów zawierających sekret. Lepszym kolejnym usprawnieniem jest pobieranie hasła interaktywnie do pamięci procesu lub Secret Manager.

### 12.2 Skrypt utworzony podczas pracy z Claude Code

W repo istnieje **`cap-java/run-live-h01.ps1`**. Skrypt:
- czyta `.env.live`;
- ustawia JSON w `$env:destinations` (name `AINV_H01`, `url`, `username`, `password`);
- ustawia `AINV_H01_LIVE=true`;
- wywołuje Maven oraz w `finally` czyści zmienne środowiskowe.

```powershell
cd cap-java
.\run-live-h01.ps1
```

Wewnętrzna komenda (jedna linia, przydatna do diagnozy):

```powershell
mvn -pl srv -am verify '-Dcds.cds.skipVersionCheck=true' '-Dainv.excludedGroups=none' '-Dtest=SapInvoiceRemoteAdapterLiveTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

`SapInvoiceRemoteAdapterLiveTest` ma `@Tag("live-sap")` oraz `@EnabledIfEnvironmentVariable(named="AINV_H01_LIVE", matches="true")`. Grupa LIVE jest **domyślnie wyłączona** przez `ainv.excludedGroups=live-sap`.

### 12.3 Faktyczny wynik z 27.10.2026

```text
SapInvoiceRemoteAdapterLiveTest
Tests run: 1, Failures: 0, Errors: 0
BUILD SUCCESS

4 nagłówki + 9 pozycji + 6 referencji
4 unikalne InvoiceUUID
każdy child InvoiceUUID = UUID właściwego headera
```

To był test **CAP Java → CqnService → SAP Cloud SDK Destination → SAP RAP**, a nie mock.

## 13. Błąd LIVE: niewłaściwy hostname i HTML przy HTTP 200

Pierwsza próba z Claude Code zakończyła się `ODataDeserializationException: Unable to read OData 4.0 response`. HTTP zwrócił **200**, ale `Content-Type: text/html` i skrypt kierujący do logowania IAS (`.../oauth/authorize`).

**Przyczyna:** użyto hosta **`*.abap-web.eu20.hana.ondemand.com`** (web/browser/SSO), który nie obsługiwał użytego tu Basic Authentication jako API.

**Naprawa:** ustawienie `H01_URL` na host API **`*.abap.eu20.hana.ondemand.com`** z Communication Arrangement. `curl` na tym hoście zwrócił JSON, a drugi test LIVE przeszedł.

Przy podobnym błędzie najpierw sprawdź URL, kod HTTP i **Content-Type**. Nie loguj nagłówka `Authorization`, tokenów ani zawartości `.env.live`.

## 14. Checklista odtworzenia

- [ ] RAP OData `ZAPI_AINV_INV_H_O4`/`0001` Published.
- [ ] Scenario `ZCS_AINV_H01` z inbound `ZAPI_AINV_INV_H_O4_0001_G4BA` Published.
- [ ] User `AINV_CAP_USER` ma aktualne, bezpieczne hasło **po rotacji**.
- [ ] System `AINV_CAP` przypisuje usera jako **Inbound**.
- [ ] Arrangement pokazuje inbound Service URL.
- [ ] Destination BTP `AINV_H01` ma origin hosta `*.abap...`, HTTP/Internet/BasicAuthentication.
- [ ] `curl.exe --user` → 200 + `sap-authenticated: true` + `application/json`.
- [ ] CAP `ainv-h01.cds` odzwierciedla model H01.
- [ ] `application.yaml`: poprawny `http.service` oraz konfiguracja poza profilem `default`.
- [ ] Test HTTP Contract PASS; test LIVE PASS bez mocka.
- [ ] Plik `.env.live` i hasła **nie** trafiły do Git/logów/artefaktów.
- [ ] Przy nowych danych dostosowano asercje 4/9/6 (to stan konkretnego seeda, nie niezmienny kontrakt biznesowy).

## 15. Co dalej — uczciwy stan

**Ukończone:** lokalne CAP Java → H01, Basic Auth, RemoteService/CQN, spójność danych 4/9/6.

**Nieukończone:**
1. **Najpilniejsze:** rotacja hasła użytkownika komunikacyjnego ujawnionego w logu agenta; aktualizacja wpisu BTP i lokalnego sekretu.
2. Deployment CAP Java na **Cloud Foundry** i **powiązanie instancji Destination Service** z aplikacją; test LIVE z procesu CF. Sam wpis `AINV_H01` w BTP Cockpit już istnieje, lecz nie dowodzi tego bindingu.
3. Pobranie **surowego oryginalnego RAP EDMX** i porównanie z ręcznie przygotowanym `ainv-h01.cds`.
4. Docelowa autoryzacja dostępu do faktur per NIP/CompanyCode. Root DCL H01 `where TRUE` jest permisywny; `systemUser()` w CAP nie rozwiązuje kontroli autoryzacji aplikacyjnej.
5. Paginacja i batchowanie CQN przy większej liczbie faktur.
6. Rozwój CAP → oficjalny Java SDK KSeF po przygotowaniu prawidłowych testowych NIP-ów (obecne dane seed zawierają NIP-y z błędnymi cyframi kontrolnymi).

### Lokalizacje w repo

```text
cap-java/run-live-h01.ps1
cap-java/.env.live                               # lokalny, NIE COMMITOWAĆ
cap-java/srv/ainv-h01.cds
cap-java/srv/src/main/resources/application.yaml
cap-java/srv/src/main/java/customer/cap_java/sap/SapInvoiceRemoteAdapter.java
cap-java/srv/src/main/java/customer/cap_java/sap/SapInvoiceSnapshot.java
cap-java/srv/src/test/java/customer/cap_java/sap/SapInvoiceRemoteAdapterIntegrationTest.java
cap-java/srv/src/test/java/customer/cap_java/sap/SapInvoiceRemoteAdapterHttpContractTest.java
cap-java/srv/src/test/java/customer/cap_java/sap/SapInvoiceRemoteAdapterLiveTest.java
docs/journal/2026-10-27-cap-03-sap-remote-integration.md
```

---
**Dokument odtworzeniowy; nie zawiera poświadczeń.** Ślad wykonania i raport z Claude Code pozostają w dzienniku CAP-03. Gdy konfiguracja CAP się zmieni, zaktualizuj tę instrukcję razem z testami.
