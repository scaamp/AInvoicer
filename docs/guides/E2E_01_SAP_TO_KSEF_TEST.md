---
title: "E2E-01 — SAP RAP → CAP → KSeF TEST → SAPUI5"
date: 2026-10-30
type: guide
status: przygotowany
tags: [ainvoicer, e2e, rap, sapui5, cap-java, ksef, test, writeback]
---

# E2E-01 — jedna syntetyczna faktura z SAP

[STATUS](../STATUS.md) · [Bramka UI READ/IAM](RAP_UI_LIVE_CONNECTION.md) · [FA(3)](FA3_XML_GENERATION.md) · [SDK/runbook TEST](KSEF_TEST_RUNBOOK.md) · [Polityka WRITE](SAP_WRITE_ACCESS_AND_READ_POLICY.md) · [ADR-0005](../decisions/ADR-0005-rap-business-status-cap-integration-status.md)

**Plan przyszłego, osobno autoryzowanego testu. Nie wykonano go w ABAP-06/UI5-06.** Potrzebna zgoda na jedną konkretną fakturę syntetyczną, maksymalnie jeden nowy SEND do KSeF TEST i zapis wyniku przez ApplyKsefResult w jej UUID w H01. Osobne zgody obejmują przygotowanie/zmianę faktury i nadania administratora. Historyczna wysyłka SDK fixture nie zalicza tego scenariusza.

```text
SAPUI5 → RAP UI READ → InvoiceUUID → CAP requestSend
 → RAP Web API READ → FA(3) + walidacja XSD/domeny → snapshot + reservation + outbox
 → SDK/KSeF TEST → trwałe ACCEPTED + numer → osobny writeback → ApplyKsefResult
 → niezależny RAP UI READ → numer w SAPUI5
                     └→ zamknięcie sesji / UPO → magazyn dokumentów
```

RAP UI służy business userowi; CAP czyta Web API przez Destination. Proces nie przesyła danych finansowych z przeglądarki. UPO i writeback są niezależne.

## Warunki rozpoczęcia

| Bramka | Wymagany dowód przed SEND |
| --- | --- |
| UI READ | opublikowany binding V4 UI, rzeczywisty URL, metadata, nagłówek i dzieci, serwerowe filtry/sort/strony |
| IAM | A/B: izolacja NIP i CompanyCode, brak obcych UUID/dzieci; propagacja business usera |
| CAP → H01 | AINV_H01, rzeczywisty Web API URL z bindingu, auth i communication arrangement; autoryzowany READ |
| CAP-04D | oddzielny uzgodniony wynik HTTP WRITE na dedykowanym dokumencie; CSRF/CAS/idempotencja i odmowy w potwierdzonym zakresie |
| CAP auth | KsefOperator i trusted taxpayerNips/companyCodes zgodne z fakturą; auth operatora rekoncyliacji |
| Dane | wskazany InvoiceUUID, numer faktury, SellerNip, BuyerNip, CompanyCode, wersja i syntetyczność |
| Trwałość | nowa dedykowana plikowa H2 poza Git + jawny lokalny magazyn TEST; kopia/ścieżki zachowane na restart |
| Regresja | Maven/UI5/QUnit/OPA PASS na testowanej wersji; brak wcześniejszej rezerwacji/SEND dla UUID |

Brak którejkolwiek bramki = BLOCKED. Nie testować WRITE na fakturze przeznaczonej do dziewiczej wysyłki: CAP-04D zapisuje numer trwale i ta faktura nie nadaje się już do SEND.

## Faktura i PartyData

Potrzebna istniejąca syntetyczna OUTBOUND: podstawowa krajowa VAT, PLN, dodatnie ilości, obsługiwane stawki, zgodne sumy netto/VAT/brutto, bez numeru KSeF. Znane historyczne seedy H01 nie są automatycznie poprawnymi danymi KSeF. W tym sprincie nie zmieniano seedów ani faktur.

Przykład **syntetycznych danych wyłącznie TEST**, które przechodzą sumę kontrolną NIP: sprzedawca `1234563218`, nabywca `1111111111`. Nie przypisuje to żadnych uprawnień. Właściciel testu musi zatwierdzić kontekst sprzedawcy i potwierdzić te wartości w wybranej fakturze H01; nie zastępować ich po cichu przy generowaniu XML.

PartyData pochodzi z konfiguracji backendu (`KsefPartyDataProvider`), nie z UI. Dla tych samych NIP-ów potrzebne są wpisy sprzedawcy i nabywcy. Przykład do osobnego, lokalnego pliku Spring poza Git:

```yaml
ainv:
  ksef:
    party-data:
      sellers:
        "1234563218":
          country-code: PL
          line1: "ul. Syntetyczna 1"
          line2: "00-001 Warszawa"
      buyers:
        "1111111111":
          jst-subordinate-unit: false
          vat-group-member: false
```

Adres sprzedawcy jest obowiązkowy, nabywcy opcjonalny; JST/GV nabywcy wymagają jawnych wartości. Nazwy stron pochodzą z RAP. Przed SEND przygotować XML przez istniejący mapper/FA(3) w kontekście backendu, sprawdzić XSD 1-0E i reguły domenowe, rozmiar oraz SHA-256 dokładnych bajtów. Nie ma publicznej akcji dry-run FA(3); nie używać requestSend jako dry-run. Nie stosować harnessu `KsefOnlineE2ELiveTest` jako zastępstwa: przygotowuje własną operację/fixture, nie przepływ SAPUI5/requestSend/SAP_RAP.

## Konfiguracja wykonania

Repo pozostaje z `ainv.ksef.enabled=false`, `ainv.sap-write.enabled=false`, retry-after-rejection=false i UI sendInvoice=false. Dopiero po osobnym zleceniu operator tworzy dedykowany proces TEST z lokalnymi override, wskazanym NIP, credentialem SDK i docelową bazą/storage. Token/Klucze/JWT nie mogą trafić do historii poleceń ani logów. Sprawdzić efektywną konfigurację, a nie tylko YAML.

CAP wymaga JDK25, CAP5.2/Spring Boot4.1 i przypiętego SDK3.0.29. SDK instaluje się istniejącym skryptem z JDK21, jeśli brak go w lokalnym Maven. Nie aktualizować wersji w trakcie eksperymentu. Uruchomienie gotowego backendu: `java -jar cap-java/srv/target/cap-java-exec.jar` z uprzednio przygotowaną bezpieczną konfiguracją Spring, trwałą bazą i magazynem.

Przed przyszłym wykonaniem ponownie porównać wdrożony kontrakt TEST z oficjalnym [przewodnikiem sesji](https://github.com/CIRFMF/ksef-api/blob/main/sesja-interaktywna.md), [uwierzytelnianiem](https://github.com/CIRFMF/ksef-api/blob/main/uwierzytelnianie.md) i [Swagger TEST](https://api-test.ksef.mf.gov.pl/docs/v2/index.html). W tej sesji odczytano przewodniki MF; Swagger jest stroną dynamiczną i narzędzie nie udostępniło treści kontraktu. Nie wykonywano auth ani wywołań KSeF.

Lokalny storage to `ainv.ksef.local-store` / `AINV_KSEF_LOCAL_STORE`, implementacja LocalTestDocumentStore. Jest celowo zabroniona na CF. Baza domyślna jest pamięciowa — nie spełnia bramki restartu E2E. Dla CF potrzebny jest osobny zweryfikowany adapter trwałego storage i persystencja; ten runbook nie zalicza CF/HANA.

Destination AINV_H01 musi wskazywać właściwy origin H01. Ścieżka RemoteService AinvoicerH01 pochodzi z opublikowanego Web API; nie z UI bindingu. CAP principal potrzebuje odczytu oraz uzgodnionego ACTVT16 dla konkretnego NIP/CompanyCode, bez nieuzgodnionego CRUD. Jedna Destination jest współdzielona przez odczyt i writeback w obecnym kodzie. Sesja business usera UI i principal komunikacyjny są odrębne.

## Jedno uruchomienie i kryteria

1. Zalogować business usera do UI z wcześniej zaliczonym RAP READ. Zapisać InvoiceUUID, cztery pola wyniku RAP i InvoiceStatus przed testem. Potwierdzić brak wcześniejszej operacji/rezerwacji oraz zgodność danych i PartyData. W dedykowanym, autoryzowanym wariancie E2E włączyć transporty i możliwość Send; nie zmieniać domyślnych flag repo.
2. Wykonać **jedno** UI Send z potwierdzeniem. Sieciowo: POST `/odata/v4/KsefService/requestSend`, body `{"invoiceUUID":"<UZGODNIONY_UUID>"}`, sesja użytkownika i CSRF obsługiwane przez adapter. Nie wysyłać XML/NIP/klucza idempotencji z klienta. Zapisać operationId z odpowiedzi QUEUED; HTTP200/QUEUED nie jest ACCEPTED.
3. Odczytywać `InvoiceIntegrationStatus` dla tego UUID z rozsądnym odstępem; nie tworzyć ponownie operacji. Sprawdzić trwały outbox, migawkę SAP_RAP, końcowe ACCEPTED/numer, zgodność NIP/hash/ref i trwałość przyjęcia przed writebackiem. Nie utożsamiać biznesowego status.code=200 z HTTP.
4. Potwierdzić writeback SUCCEEDED i APPLIED lub idempotentne ALREADY_APPLIED. Niezależny GET RAP UI ma zwrócić KsefNumber, KsefEnvironment=TEST, KsefRecordedAt oraz KsefWriteRequestId=operationId. InvoiceStatus ma pozostać bez zmian. Odświeżyć UI; numer pochodzi z RAP, a nie jedynie z CAP ACCEPTED_SAP_SYNC.
5. Potwierdzić COMPLETED/upoAvailable po zamknięciu sesji. Zaufany operator backendu odczytuje `KsefSubmissionWorker.status(operationId)` i dokument przez `KsefDocumentStore.get(upoStorageRef)`; zapisuje artefakt poza Git i sprawdza obecność numeru/hash oraz powiązanie z operacją. Publicznego endpointu pobrania UPO ani aktywnego przycisku Download UPO jeszcze nie ma. Odczyt pliku nie jest weryfikacją podpisu kryptograficznego UPO.
6. W ramach jawnie uzgodnionego testu duplikatu drugi requestSend tego samego UUID ma dać HTTP409 (np. KSEF_ALREADY_RECORDED_IN_SAP lub KSEF_ALREADY_ACCEPTED), bez drugiego wywołania SDK send i bez nowej rezerwacji. Liczbę wywołań potwierdzić audytem backendu, nie samym przyciskiem.
7. Zrestartować ten sam proces na tej samej DB/storage; wynik i UPO mają pozostać. Przy sukcesie zakończyć proces TEST/wyłączyć lokalne override; nie usuwać bazy, dokumentów ani dowodów.

## Timeout i konflikty

| Zdarzenie | Dalsze działanie |
| --- | --- |
| Brak odpowiedzi na requestSend | najpierw GET stanu UUID/operacji; nie klikać ponownie Send ani nie zmieniać UUID |
| Niejednoznaczny send/close | zachować DB/storage/hash/ref; RECONCILIATION_REQUIRED; backend operator używa istniejącego reconcile(operationId), bez ponownego send/close |
| READ_RETRY_EXHAUSTED | po ustaleniu dostępności i retryNotBefore kontrolowane resumeReads(operationId); tylko bezpieczne odczyty |
| ACCEPTED, SAP niedostępny | zachować przyjęcie i UPO; odtworzenie/sweeper writebacku, stały WriteRequestId i payload |
| VERSION_CONFLICT / RESULT_CONFLICT | read-only SapKsefResultWriteRecovery.diagnose(id), ocena operatora; bez zmiany ExpectedVersion i bez nowego SEND |
| Brak dowodu przyjęcia | pozostawić stan niejednoznaczny i rezerwację; nie oznaczać REJECTED dla wygody ponowienia |

Metody recovery to API wewnętrzne backendu, nie publiczne OData actions. Nie wymyślać endpointów administracyjnych. Przed ich użyciem potrzebny kontrolowany dostęp operatora; reconcile może wykonać uwierzytelnienie, a jego odczyty nie wysyłają faktury ponownie.

Raport E2E powinien podać wersję kodu, UUID/operationId, tryb/principal, dowody numeru/UPO/writebacku, wynik duplikatu/restartu oraz liczbę faktycznych SEND. Bez tych dowodów wynik pozostaje częściowy. W ABAP-06: **SEND i HTTP WRITE NOT EXECUTED**.
