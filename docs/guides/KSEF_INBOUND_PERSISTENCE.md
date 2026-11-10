---
title: "KSEF-IN-02 — staging i bezpieczna synchronizacja INBOUND"
date: 2026-11-10
type: guide
status: aktywny
tags: [ainvoicer, ksef, inbound, cap-java, persistence, recovery, security]
---

# Staging INBOUND i synchronizacja

[Architektura IN-01](KSEF_INBOUND_ARCHITECTURE.md) · [Dziennik IN-02](../journal/2026-11-10-ksef-in-02.md) · [ADR-0005](../decisions/ADR-0005-rap-business-status-cap-integration-status.md)

Fundament backendowy przetestowany offline: CAP CQN, lokalna H2 i syntetyczny SDK/XML.
Nie wykonano odczytów faktur KSeF, SEND, SAP WRITE, deployu ani operacji BTP.
Nowe encje nie są publikowane w OData. Scheduler nie jest zarejestrowany.

## Własność i decyzje w zakresie sprintu

CAP przechowuje staging dokumentów odebranych z KSeF. Nie oznacza to zaksięgowania w SAP ani
przypisania do CompanyCode. `KsefInvoices`, OUTBOUND i właściciel biznesowego wyniku SAP z ADR-0005
pozostają bez zmian. Jest to realizacja modelu z IN-01, bez migracji istniejących danych.

1. **Jedna persystencja:** istniejący CAP `PersistenceService`, bez JPA ani dodatkowej bazy.
2. **Identyfikacja:** dokument `(environment, ksefNumber)`; grant `(documentId, contextNip, subjectRole)`;
   checkpoint i lease `(environment, contextNip, subjectRole)`. Constrainty są w CDS i wynikowym SQL.
   Stabilne UUID dokumentów/grantów wyliczane są z tych kluczy; dodatkowe UNIQUE chronią także insert
   z innym UUID. Asocjacja grantu jest nawigacją wewnętrzną, nie kontraktem publicznym ani dowodem FK.
3. **HWM bez zaokrąglenia:** `continuationFrom` przechowuje kanoniczny UTC z **9 cyframi ułamka sekundy**
   jako `String(40)`. H2 Timestamp może zaokrąglić datę, a przesunięcie w przód mogłoby pominąć dokument.
   Stała reprezentacja zachowuje porządek dla atomowego predykatu CQN `<`. Java zwraca OffsetDateTime.
4. **Storage i DB bez wspólnego ACID:** rezerwacja → storage → finalizacja. Wymagana jest idempotencja
   `put` po treści oraz trwałość potwierdzenia providera. Osierocony obiekt jest bezpieczniejszy niż
   checkpoint wskazujący utracony XML. Automatyczny garbage collection nie jest wdrożony.
5. **Schemat nieobsługiwany:** zweryfikowane bajty można archiwizować i przesunąć HWM, lecz otrzymują
   `UNSUPPORTED_SCHEMA` i `grossAmount=null`. Brak deklaracji poprawnego parsowania FA(3).
   Niepoprawny FA(3) jest kwarantannowany i zatrzymuje HWM.
6. **Brak automatyzacji CF:** lease nie zastępuje limitera API per kontekst/IP. Konfiguracja odmawia
   aktywacji na CF nawet po dostarczeniu trwałego store, dopóki osobny etap nie domknie tych warunków.

## Komponenty i granice transakcji

`cap-java/db/inbound.cds` definiuje:

| Encja | Rola |
| --- | --- |
| `KsefInboundDocuments` | Metadane, hash, rozmiar, prywatna referencja XML i trwałe statusy |
| `KsefInboundAccessGrants` | Oddzielna obserwacja dostępu kontekstu w konkretnej roli |
| `KsefInboundSyncCheckpoints` | Monotoniczna kontynuacja i wersja CAS |
| `KsefInboundIntegrityConflicts` | Historia append-only: hash oczekiwany/obserwowany, scope, czas, kod |
| `KsefInboundSyncLeases` | Token przejęcia, wygaśnięcie, licznik prób i trwały Retry-After/cooldown |

Pipeline `PersistentInboundInvoiceSink`:

1. Sprawdza zgodność metadanych z zaufanym scope (środowisko/NIP/rola, sprzedawca/nabywca w rolach 1/2).
2. **Transakcja A:** `CqnInboundDocumentRepository.prepare` rezerwuje dokument `PENDING` z hashem metadanych.
   Przy konflikcie zapisuje historię i `integrityStatus=CONFLICT`, następnie zgłasza błąd **po commit**.
   Nie dopisuje nowego grantu. Używa istniejącej `InvoiceDeduplicationPolicy`.
3. Już utrwalony, zgodny dokument wymaga tylko transakcyjnego dopisania brakującego grantu.
   Nie pobiera ponownie XML. Zaufanie do wcześniejszego potwierdzenia storage zakłada jego trwałość.
4. Poza transakcją: istniejące `InvoiceRetrievalService.downloadVerified` (GET i SHA-256 dokładnych bajtów).
   Błąd odczytu/integralności pozostawia bezpieczny kod w stagingu, bez grantu ani zmiany HWM.
5. Poza transakcją: `SecureFa3InvoiceParser`, lokalny `Fa3SchemaValidator`, krzyżowa kontrola XML/metadanych.
   Brak dostępu sieciowego do XSD. Nie zapisujemy komunikatów walidatora cytujących dane faktury.
6. Poza transakcją: `KsefDocumentStore.put`, następnie sprawdzenie identyczności odczytanych bajtów.
7. **Transakcja B:** finalizacja dokumentu i grant. `STORED/PARSED/VERIFIED` wymaga poprawnego XML i XSD;
   `STORED/UNSUPPORTED_SCHEMA/VERIFIED` oznacza wyłącznie archiwizację nieobsługiwanego formatu;
   `QUARANTINED/INVALID/VERIFIED` oznacza zgodny hash, ale niepoprawną treść FA(3).
8. Dopiero po wszystkich dokumentach okna istniejący `InvoiceSyncRunner` pyta istniejący
   `InvoiceSyncWindowPlanner` o HWM/truncation i zapisuje checkpoint w **transakcji C**.

`InboundPersistence.tx` stosuje wzorzec istniejących workerów: osobny RequestContext systemowy i ChangeSet.
Sink i koordynator odmawiają uruchomienia wewnątrz aktywnego CAP ChangeSet lub transakcji Spring.
Nie należy wywoływać ich bezpośrednio z transakcyjnego handlera HTTP/OData.

CAS to `UPDATE WHERE scope AND version=expected AND continuationFrom<next`; przy pierwszym zapisie
INSERT i constraint PK. Wyjątek unikalności CAP **409006** jest obsługiwany poza zakończonym ChangeSet,
pozostałe błędy DB propagują się. Wyścig dokumentu ponawia odczyt w świeżej transakcji. Blokada dokumentu
serializuje finalizację i granty także między różnymi zakresami synchronizacji.

## Recovery i ograniczenia ACID

| Awaria | Stan trwały | Kolejny przebieg |
| --- | --- | --- |
| Po rezerwacji, przed GET/put | PENDING, brak grantu/HWM | Pobiera i zapisuje ten sam dokument |
| GET/hash/cross-check | PENDING + kod; integralność FAILED przy rozbieżności | HWM bez zmian; błąd integralności blokuje scope koordynatora |
| Błąd storage | STORAGE_FAILED, brak grantu/HWM | Ponawia bezpieczny odczyt i idempotentny put |
| Po put, przed finalizacją DB | PENDING i możliwy orphan w store | Put identycznych bajtów zwraca tę samą referencję, finalizacja |
| Po finalizacji, przed checkpointem | Dokument i grant STORED, stary HWM | Deduplikuje i zatwierdza dopiero całe okno |
| Po checkpoint commit, przed zwolnieniem lease | Nowy HWM, stary token lease | Czeka na wygaśnięcie/due, startuje z nowego HWM |
| Lease wygasł podczas I/O | Wcześniejsze zapisy dokumentów mogą istnieć | Stary worker nie zatwierdzi HWM; nowy powtórzy okno |
| Konflikt hash/metadanych | Oryginalne bajty/ref/hash + historia CONFLICT | Brak nadpisania, nowego grantu ani postępu HWM |

Nie deklarujemy exactly-once ani wspólnej transakcji storage+CAP. Historia konfliktów jest append-only
w repozytorium (brak publicznego CRUD); kolejne obserwacje konfliktu mogą tworzyć kolejne wpisy historii.
Oryginał i fingerprint nie są nadpisywane. Zgodny późniejszy odczyt nie usuwa flagi CONFLICT.
Quarantine i konflikty wymagają osobnego, autoryzowanego procesu wyjaśnienia; nie dodano akcji resolve.

Provider CF musi implementować `DurableInboundDocumentStore extends KsefDocumentStore`: identyczne
bajty → identyczna referencja także po restarcie/innej instancji; trwały acknowledgment; izolacja i
szyfrowanie/retencja. W repo **nie ma takiego providera**. Interfejs nie jest dowodem trwałości produktu.
`LocalTestDocumentStore` jest dopuszczony wyłącznie do lokalnych eksperymentów TEST.
Nie jest magazynem CF. Odczyt po put potwierdza bajty, lecz sam w sobie nie dowodzi trwałości.
Retencja, quota i GC orphanów muszą być zaprojektowane z wybranym providerem przed aktywacją.

## Lease, retry, auth i konfiguracja

`InboundSynchronization.runOnce()` nie przyjmuje NIP/środowiska z żądania. Bean otrzymuje z konfiguracji
TEST + `ainv.ksef.nip`, rolę BUYER i jawny `initial-from`. Działa tylko po ręcznym wywołaniu backendowym
w dozwolonym teście; utworzenie beana/start aplikacji nie uruchamia auth, GET ani synchronizacji.

```yaml
ainv:
  ksef:
    inbound:
      enabled: false
      environment: TEST
      initial-from: ""
```

Wiring po opt-in lokalnym wymaga skonfigurowanego store, prawidłowego NIP i daty. Credential wiąże
istniejący `KsefAuthentication`. Adapter `KsefInboundAccess` znajduje się w jego pakiecie i nie zmienia
publiczności accessorów tokenów. Każdy przebieg ma własną sesję; refresh używa istniejącej implementacji.
JWT/refresh nie trafiają do stagingu, DTO ani logów. SDK pozostaje **3.0.29**, CAP **5.2.0**, Boot **4.1.1**.

Koordynator przejmuje lease na 10 minut, utrwala minimum 15 minut odstępu przy przejęciu, a po wyniku
odracza następny przebieg o co najmniej 15 minut + jitter. Dłuższy Retry-After wygrywa i pozostaje w DB.
Bezpośrednie repozytorium lease służy też do testów; koordynator zawsze stosuje powyższą politykę.
Token lease jest sprawdzany przed sinkiem oraz **pod blokadą w tej samej transakcji co CAS checkpointu**.
Wygasły worker może dokończyć idempotentny zapis dokumentu, lecz nie może zwolnić lease następcy ani
zatwierdzić checkpointu. Brak odnawiania lease: okno dłuższe niż 10 minut kończy się bez zatwierdzenia
i wymaga ponownego przebiegu. Lease/due odzyskują się po restarcie bez pamięci poprzedniego procesu.

429 i błędy przejściowych odczytów używają istniejącego `ReadRetryPolicy`, a po wyczerpaniu prób
trwałego odroczenia. 401/403, błędy integralności/kontraktu oraz niejednoznaczny redeem/auth blokują scope
(`lastCode=BLOCKED`). Nie ma ślepego retry redeem. Odblokowanie wymaga przyszłego kontrolowanego workflow.

**Nie ma @Scheduled, listenera startup ani nowej kolejki in-memory/outbox.** Do bezpiecznego schedulera
brakuje globalnego budżetu endpointów per (environment, kontekst, IP), współdzielenia budżetu między
rolami/instancjami/OUTBOUND, kontroli wolumenu stronicowania/GET, operacyjnego resume/monitoringu i
zweryfikowanego trwałego providera. Sam cooldown per scope nie realizuje godzinowych limitów MF.
Wysoki wolumen wymaga eksportu paczek (IN-03). Rozpoznanie CF przez profile lub VCAP odmawia opt-in:
bez providera `KSEF_INBOUND_DURABLE_STORE_REQUIRED`, z providerem `KSEF_INBOUND_CLOUD_ACTIVATION_NOT_READY`.

## Dokładny kontrakt INT-02

Wewnętrzna Java:

```java
List<InboundInvoiceOverview> CqnInboundOverviewRepository.find(
    SyncScope allowedScope, int offset, int limit); // limit 1..250, offset >= 0
```

Scope musi pochodzić z serwerowej autoryzacji principal → dozwolony kontekst/rola. Sam parametr z UI
nie nadaje dostępu. Repozytorium filtruje środowisko oraz grant NIP/rola w DB przed paginacją.
Nie korzysta z CompanyCode i nie wolno wymyślać przypisania spółki. Typowe Inbox używa BUYER.
Nie dodano serwisu CDS/HTTP ani publicznego endpointu XML.

| Pole DTO | Typ JSON docelowy | Źródło/reguła |
| --- | --- | --- |
| `id` | string UUID | ID stagingu |
| `environment` | string | TEST/DEMO/PROD z dokumentu (wiring transportu obecnie tylko TEST) |
| `ksefNumber` | string | Zweryfikowany numer KSeF |
| `invoiceNumber` | string | Metadane, dla PARSED krzyżowo sprawdzone z P_2 |
| `issueDate` | string YYYY-MM-DD lub null | LocalDate |
| `acquisitionDate` | string UTC ISO-8601 lub null | Instant z metadanych; precyzja DB Timestamp |
| `sellerNip` | string | Metadane, dla PARSED sprawdzone z XML |
| `sellerName` | string lub null | XML po PARSED, inaczej opcjonalne metadane |
| `buyerIdentifierType` | string | Dokładnie typ MF: Nip/VatUe/Other/None |
| `buyerIdentifierValue` | string lub null | Identyfikator, null dla None |
| `currency` | string lub null | Metadane; dla PARSED sprawdzone z XML |
| `grossAmount` | **string dziesiętny lub null** | Wyłącznie XML P_15 po parserze i XSD, `BigDecimal.toPlainString()` |
| `ingestionStatus` | string | PENDING/STORAGE_FAILED/STORED/QUARANTINED |
| `parseStatus` | string | NOT_PARSED/PARSED/UNSUPPORTED_SCHEMA/INVALID |
| `integrityStatus` | string | PENDING/VERIFIED/FAILED/CONFLICT |
| `sapPostingStatus` | string | NOT_POSTED; księgowanie poza zakresem |
| `xmlAvailable` | boolean | STORED + VERIFIED + ref + PARSED lub UNSUPPORTED_SCHEMA; false przy konflikcie/quarantine |

Kwoty informacyjne z SDK Double nie stają się autorytatywną kwotą stagingu. Braki pozostają null.
`xmlAvailable` jest informacją o wcześniejszym poprawnym zapisie, nie bieżącym health-checkiem store.
Grant powstaje dopiero przy finalizacji (także quarantine); niedokończona rezerwacja nie jest widoczna
w overview. DTO nigdy nie zwraca XML, `xmlStorageRef`, tokenu ani listy grantów.

### Pliki do integracji przez orkiestratora

- `cap-java/srv/ksef-service.cds`: nowy jawny read-only kontrakt/projekcja InboundInvoices, nullability,
  kwota jako string i zakaz technicznych pól; bez automatycznej ekspozycji całej tabeli stagingu.
- `cap-java/srv/src/main/java/customer/cap_java/handlers/KsefInboundOverviewHandler.java` (nowy):
  autoryzacja principal → NIP/rola, ograniczone filtry/sort/paging/count i mapowanie DTO. Aktualne `find`
  obsługuje offset/limit i stabilny porządek ID; filtrowanie i count dla HTTP wymagają rozszerzenia CQN
  **z zachowaniem predykatu grantów**, bez filtrowania dopiero po paginacji.
- `cap-java/srv/src/main/java/customer/cap_java/ksef/KsefInboundConfiguration.java`: ewentualne wydzielenie
  wiring read modelu od flagi transportu. Obecnie wszystkie INBOUND beans są OFF razem; odczyt stagingu
  w INT-02 nie może wymagać aktywacji pobierania ani obejścia guarda CF.
- Testy backendowe nowego OData: role, izolacja A/B i ról, brak technicznych danych/nawigacji, `$metadata`,
  string kwoty, null i HTTP read-only. Włączyć do istniejącej regresji CF/security.
- `sapui5/ainvoicer/webapp/manifest.json` i pliki Inbox dostarczone przez UI5-10: podłączenie do faktycznie
  opublikowanego kontraktu. Dokładne pliki UI poza manifestem ustalić z końcowego diff UI5-10; tutaj ich
  nie modyfikowano i nie zakładano implementacji równoległego agenta.
- `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/README.md`: scalenie stanu i indeksowanie przewodnika/dziennika.
  Zgodnie ze zleceniem IN-02 nie zmienia współdzielonych plików ani AppRouter/MTA/CI.

Aktywacja schedulera/CF i provider storage to dalszy zakres, a nie warunek ekspozycji bezpiecznego read modelu.
Nie należy włączać INBOUND ani zmieniać BTP w ramach samego podłączenia Inbox.

## Weryfikacja i źródła

Testy: `CqnInboundPersistenceTest` (CQN/H2, UNIQUE, race, granty, CAS, nanosekundy HWM, pipeline i retry),
`InboundProcessRecoveryTest` (dwie rzeczywiste JVM, `Runtime.halt`, plikowa H2 z WRITE_DELAY=0 i lokalne XML),
`KsefInboundConfigurationTest` (OFF, CF/VCAP fail-closed, brak startup I/O, istniejący auth/refresh).
Pełny wynik `mvn clean verify`, wersje i ograniczenia znajdują się w [dzienniku](../journal/2026-11-10-ksef-in-02.md).
Plikowa H2 jest dowodem lokalnego recovery, nie zachowania HANA, CF ani zewnętrznego providera.

Sprawdzono dokumentację [CAP Persistence Services](https://cap.cloud.sap/docs/java/cqn-services/persistence-services),
[ChangeSet Contexts](https://cap.cloud.sap/docs/java/event-handlers/changeset-contexts),
[błędów CAP](https://cap.cloud.sap/docs/java/event-handlers/indicating-errors) oraz
[kod unikalności 409006](https://userapps.support.sap.com/sap/support/knowledge/en/3713221).
Sygnatury CAP potwierdzono także w lokalnych JAR 5.2.0 (`javap`).
Mechanizm HWM/role/limity porównano z [oficjalnym przewodnikiem MF](https://github.com/CIRFMF/ksef-api/blob/main/pobieranie-faktur/przyrostowe-pobieranie-faktur.md).
SDK/XSD i ograniczenia walidatora pozostają jak w IN-01/FA(3): walidator rozluźnia wybrane duże maxOccurs
w pamięci; parser egzekwuje własne limity. Brak testu wdrożonego kontraktu LIVE w tym sprincie.
