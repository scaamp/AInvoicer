---
title: "INT-02 — kontrakt i lokalny KSeF Inbox Full-Stack"
date: 2026-11-10
type: guide
status: aktywny
tags: [ainvoicer, inbound, cap-java, odata, sapui5, security, int-02]
---

# INT-02 — KSeF Inbox przez CAP OData V4

[Dziennik](../journal/2026-11-10-int-02-inbound-fullstack.md) · [Security review](../audits/2026-11-10-int-02-security-review.md)

## Właściciel danych i polityka dostępu

CAP przechowuje staging INBOUND; RAP nadal utrzymuje dokumenty SAP. Odczyt nie wymaga
`ainv.ksef.inbound.enabled=true`, SDK auth, providera XML ani schedulera. Synchronizacja pozostaje OFF.
`KsefInboundReadConfiguration` rejestruje tylko persystencję/read repository; publiczny endpoint używa
projekcji CDS i standardowego wykonania CQN CAP, bez uprzywilejowanego `systemUser`.

V1 wymaga `authenticated-user`, `KsefOperator`, dokładnie jednego poprawnego NIP-u
`$user.taxpayerNips` (wzorzec MF i checksum) i dokładnie jednego CompanyCode `[A-Z0-9]{4}`
z `$user.companyCodes`. Brak/wadliwość/niejednoznaczność atrybutów daje pustą listę/count=0
i 404 przy key GET. CompanyCode jest jednoznacznym kontekstem użytkownika, **nie przypisaniem
faktury do spółki**. Inny pojedynczy CompanyCode z tym samym NIP-em/grantem daje tę samą widoczność.

Zaufana tożsamość pochodzi z istniejącego stosu CAP identity/SAP Security. W XSUAA dedicated
zaufany atrybut `UserInfo.getAttributeValues("tenant")` musi odpowiadać `tenantid`
(fallback `identityzoneid`) bindingu. CAP5.2/XSUAA nadpisuje go przez oficjalne `getAppTid()`
tokenu (`app_tid`, fallback `zone_uuid`), już po odczycie xs.user.attributes.
Potwierdzono to w bytecode providera identity i testach; `UserInfo.getTenant()` w single-tenant
pozostaje null, a sam claim `zid` nie jest tutaj źródłem tenant CAP.
Obcy/brakujący tenant przy bindingu daje 403 `INBOX_TENANT_FORBIDDEN`, także przy poprawnym
podpisie i audience. Bez bindingu dopuszczony jest wyłącznie istniejący lokalny wariant mock;
guard CF nadal wymaga XSUAA. Nie wprowadzono modelu SaaS ani współdzielonej bazy wielu tenantów.

Każdy odczyt ma w DB predykat:

```sql
environment = 'TEST'
AND id IN (SELECT documentId FROM KsefInboundAccessGrants
           WHERE contextNip = :trustedUserNip AND subjectRole = 'BUYER')
AND (:clientFilter)
```

To semijoin, więc wiele grantów nie powiela dokumentów. Filtr jest dokładany przez
`InboundOverviewHandler` przed standardowym READ, key GET, paginacją i count. NIP/rola
przekazane w URL nie wyznaczają zakresu. `KsefOperator` nie nadaje `InvoiceRead` w KSeF.

## Kontrakt HTTP i typy

- `GET /odata/v4/KsefService/$metadata`
- `GET /odata/v4/KsefService/InboundInvoiceOverview`
- `GET /odata/v4/KsefService/InboundInvoiceOverview(<Edm.Guid>)`
- `GET /odata/v4/KsefService/InboundInvoiceOverview/$count`

Jawna projekcja bez asocjacji i bez autoekspozycji stagingu. Key nazywa się `id`, UUID bez cudzysłowów.
CREATE/UPDATE/DELETE/PUT są niedostępne (403/405). Nie ma endpointu XML ani publicznych encji grantów,
checkpointów, lease i konfliktów. `xmlStorageRef`, `sha256`, JWT i sekrety nie należą do DTO.

Odczytane runtime `$metadata`, reprezentatywny fragment (pełny zestaw20 pól w tabeli):

```xml
<EntityType Name="InboundInvoiceOverview">
  <Key><PropertyRef Name="id"/></Key>
  <Property Name="id" Type="Edm.Guid" Nullable="false"/>
  <Property Name="issueDate" Type="Edm.Date"/>
  <Property Name="acquisitionDate" Type="Edm.DateTimeOffset" Precision="7"/>
  <Property Name="buyerName" Type="Edm.String" MaxLength="512"/>
  <Property Name="grossAmount" Type="Edm.Decimal" Precision="18" Scale="2"/>
  <Property Name="xmlAvailable" Type="Edm.Boolean"/>
</EntityType>
```

Non-null: id, environment, ksefNumber, invoiceNumber, sellerNip, buyerIdentifierType oraz cztery statusy.
Pozostałe pola są nullable w EDM. Statusy dziedziczą DefaultValue PENDING/NOT_PARSED/PENDING/NOT_POSTED;
sam odczyt nie zastępuje null ani braków innymi danymi.

| Pole | Typ EDM z kontraktu | Źródło |
| --- | --- | --- |
| `id` | Guid, klucz, non-null | staging `ID` |
| `environment` | String(4) | dokument, scope v1 TEST |
| `ksefNumber` | String(35) | staging |
| `invoiceNumber` | String(256) | staging |
| `issueDate` | Date | staging lub null |
| `acquisitionDate` | DateTimeOffset, precision 7 | staging lub null |
| `sellerNip` | String(10) | staging |
| `sellerName` | String(512) | staging lub null |
| `buyerIdentifierType` | String(16) | Nip/VatUe/Other/None |
| `buyerIdentifierValue` | String(256) | staging lub null |
| `buyerName` | String(512) | **null**, brak kolumny stagingu |
| `currency` | String(3) | staging lub null |
| `grossAmount` | Decimal, precision 18, scale 2 | staging BigDecimal, wyłącznie zwalidowany XML |
| `invoiceType` | String(32) | staging lub null |
| `formCode` | String(64) | `formSystemCode` lub null |
| `ingestionStatus` | String(32) | PENDING/STORAGE_FAILED/STORED/QUARANTINED |
| `parseStatus` | String(32) | NOT_PARSED/PARSED/UNSUPPORTED_SCHEMA/INVALID |
| `integrityStatus` | String(32) | PENDING/VERIFIED/FAILED/CONFLICT |
| `sapPostingStatus` | String(32) | staging, obecnie NOT_POSTED; bez zgadywania dokumentu SAP |
| `xmlAvailable` | Boolean | STORED + VERIFIED + ref + PARSED/UNSUPPORTED_SCHEMA |

`xmlAvailable` opisuje potwierdzony zapis stagingu, nie bieżącą dostępność storage ani prawo do pobrania XML.
UI toleruje dłuższy niestandardowy numer KSeF w syntetycznych mockach (do 64 znaków);
rzeczywisty endpoint ma MaxLength=35. UI nie skraca kontraktowych danych 256/32/64 znaków.

Negocjacja adaptera: `Accept: application/json;IEEE754Compatible=true` i `OData-MaxVersion: 4.0`.
Decimal oraz count mogą być tekstem JSON; adapter count normalizuje, kwoty nigdy nie konwertuje do Number.
Bez IEEE754 klient może otrzymać JSON number i ponosi odpowiedzialność za jego precyzję.

Syntetyczny przykład danych zweryfikowanych przez HTTP (pominięty URL `@odata.context`):

```json
{
  "@odata.count": "5",
  "value": [{
    "id": "10000000-0000-4000-8000-000000000001",
    "environment": "TEST",
    "ksefNumber": "1234567802-20261110-000000000001-AA",
    "invoiceNumber": "FV/O'Brien/INT-02",
    "issueDate": "2026-11-09",
    "acquisitionDate": "2026-11-10T10:00:00Z",
    "sellerNip": "1234567802",
    "sellerName": "Synthetic supplier INT-02",
    "buyerIdentifierType": "Nip",
    "buyerIdentifierValue": "1234567802",
    "buyerName": null,
    "currency": "PLN",
    "grossAmount": "9999999999999999.99",
    "invoiceType": "VAT",
    "formCode": "FA (3)",
    "ingestionStatus": "STORED",
    "parseStatus": "PARSED",
    "integrityStatus": "VERIFIED",
    "sapPostingStatus": "NOT_POSTED",
    "xmlAvailable": true
  }]
}
```

Przykład odpowiada stronie `$top=1&$orderby=grossAmount desc,id`; count opisuje cały uprawniony zbiór.
Fixture mają syntetyczne referencje storage; harness nie tworzy XML i nie dowodzi dostępności providera.

## Query i błędy

`$select` ogranicza publiczne pola; nieznane/prywatne pole daje 400. `$filter` dopuszcza
`id`, `environment`, `invoiceNumber`, `ksefNumber`, `sellerNip`, `issueDate`, `acquisitionDate`,
`ingestionStatus`, `parseStatus`, `integrityStatus`, `sapPostingStatus`, `buyerIdentifierValue`.
Operatory: eq/ne/gt/ge/lt/le, and/or/not; CAP normalizuje eq/ne również do IS/IS_NOT.
`contains`, `tolower` i `startswith` zostały sprawdzone przez realne HTTP/CAP 5.2/H2,
włącznie z apostrofem `O'Brien` i prefiksem NIP. Literały tekstowe maks. 256, maks. 100 odwołań w filtrze.

Sort whitelist: `id`, acquisitionDate, issueDate, invoiceNumber, ksefNumber, sellerNip,
sellerName, grossAmount, currency. Domyślnie acquisitionDate desc, id asc; brakujący tie-breaker
`id asc` dodaje backend. NULL zawsze na końcu (CASE w SQL), także przy sortowaniu rosnącym.
Kwota sortowana numerycznie w DB, nie jako tekst w przeglądarce.

Domyślna strona 20, maksymalna 100; `$top>100` ograniczany do 100 przez CAP/handler;
`$top=0` daje pustą stronę z poprawnym count. `$skip` nie zmienia count. Key GET i `/$count`
używają identycznego scope. `$apply`, `$search`, `$expand`, `$compute`, arytmetyka filtrów,
nieobsługiwane funkcje/sortowanie są odrzucane (400 lub błąd parsera 404/501).

401: brak/nieważny JWT. 403: brak roli lub obcy tenant. 200 z pustą listą: brak jednoznacznych
atrybutów, grantu BUYER lub pasujących danych. 404: obcy/brakujący UUID. UI pokazuje błędy
HTTP/sieci i czyści dane; nie przełącza się na mock. Nieudana walidacja filtrów blokuje HTTP.

## Uruchomienie i powtórzenie lokalnego dowodu

JDK 25, Maven 3.9.16, Node 22/24, Chrome/Edge (`CHROME_PATH` jeśli potrzebny).

```powershell
mvn -f cap-java/pom.xml clean verify '-Dcds.cds.skipVersionCheck=true'
npm ci --prefix sapui5/ainvoicer
npm run test:inbox:e2e --prefix sapui5/ainvoicer
```

Runner odczytuje testowy classpath Surefire po buildzie; uruchamia `InboundLocalServer` z profilu test,
syntetyczne staging/granty A/B oraz serwer UI5 (8092/8087). Tworzy kopię `build/inbox-webapp`
z `dataMode=cap` i rzeczywistą trasą `/odata`. Brak stubowania fetch odpowiedzi CAP.
Lokalne testowe konta CAP są używane tylko przez CDP na odczytach loopback; żadne credentiale
nie są wstrzykiwane do aplikacji ani wysyłane do CDN. Harness i fixture są w `src/test` i nie trafiają do JAR.

Runner sprawdza adapter, UI, filtry, sortowanie, paging/count, szczegóły, dokładną kwotę, pusty wynik,
walidację, A/B, brak grantu, 401/403/404, zatrzymanie CAP i powrót po restarcie. Zamyka własne procesy.
Restart tutaj potwierdza ponowne połączenie UI z nową JVM na syntetycznych danych;
trwałość stagingu przy crash/recovery potwierdzają osobne istniejące testy IN-02 z plikową H2.
Logi trafiają do ignorowanego `sapui5/ainvoicer/build/inbox-e2e/`.

Do ręcznej inspekcji po uruchomieniu testowego backendu można użyć `npm run serve:inbox --prefix
sapui5/ainvoicer` (port 8087). Zwykła karta bez lokalnego principalu dostanie 401; runner dostarcza
testowe uwierzytelnienie osobno. `serve:inbox` sam nie uruchamia CAP i nie nadaje uprawnień.

## Bramki dalszego wdrożenia

`build:cf` bezwarunkowo utrzymuje Inbox disabled, bez encji/mocków i akcji; MTAR pozostaje identity-smoke/H2.
Nie wykonano deployu, zmian usług/ról/Destination, SAP WRITE, synchronizacji lub SEND KSeF.
Następny etap: trwałe HANA/HDI i storage z testami recovery, SSO A/B oraz autorytatywne mapowanie
dokumentu do CompanyCode przed procesem księgowania. Aktywacja CF i KSeF wymaga osobnego zlecenia.

Weryfikowane źródła: [CAP Query API](https://cap.cloud.sap/docs/java/working-with-cql/query-api),
[CAP Security](https://cap.cloud.sap/docs/java/security),
[Application Services](https://cap.cloud.sap/docs/java/cqn-services/application-services).
Sygnatury Modifier/CqnVisitor/ParameterInfo/UserInfo sprawdzono również w lokalnych JAR CAP 5.2.0.
