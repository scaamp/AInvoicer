**Rekomenduję wariant B: wąską akcję RAP zapisującą potwierdzony wynik KSeF.** CAP zachowuje workflow, retry i historię; SAP otrzymuje numer KSeF, środowisko i metadane zastosowania wyniku. `InvoiceStatus` pozostaje statusem faktury SAP.

Istotne ustalenie techniczne: CAP Java 5.2.0 obsługuje zdalne akcje, lecz inspekcja używanej biblioteki wykazała automatyczne ustawianie `If-Match` dla UPDATE/DELETE, bez analogicznej obsługi w ścieżce akcji. Dlatego poniżej proponuję jawną kontrolę wersji wewnątrz RAP.

**Wykonano wyłącznie odczyty. Nie zmieniono kodu, dokumentacji, konfiguracji ani obiektów SAP.**

## 1. Current State

Stan sprawdzony 28.10.2026.

| Obszar | Potwierdzone fakty |
|---|---|
| Git | Branch `java-cap-ksef`, HEAD `58c2f577c4597d48c3f8a48fdda7a6c30335d108`; czyste drzewo przed analizą i po niej |
| MCP | Działające połączenie `h01`; proces ABAP FS **2.13.3** |
| Pakiet | `ZAINVOICER`, `recordChanges=false` |
| RAP | Aktywne root/projection CDS, oba BDEF, behavior implementation oraz sześć DCL odczytano live |
| Zmiany nieaktywne | Lista dla sprawdzanego zakresu zwróciła 0 obiektów |
| Binding | `ZAPI_AINV_INV_H_O4`, `0001`, OData V4, aktywny i opublikowany; stan wydania `NOT_RELEASED` |
| Runtime | MCP `$metadata`: HTTP 200. Osobny GET oryginalnego EDMX przez API H01: HTTP 200, `application/xml` |
| Operacje | BDEF deklaruje CREATE/UPDATE/DELETE nagłówka, CREATE dzieci przez asocjacje oraz UPDATE/DELETE dzieci |
| Akcje | Oryginalny EDMX zawiera **0 akcji i funkcji** |
| ETag | BDEF: `etag master LocalLastChangedAt`; projection: `use etag`; EDMX potwierdza `OptimisticConcurrency` |
| Odpowiedź danych | GET zwraca rzeczywiste `@odata.etag` oraz `LocalLastChangedAt` |
| Dane nagłówków | Aktualny odczyt potwierdził cztery faktury: FI_AP/MM_IV jako `INBOUND`, FI_AR/SD_BILL jako `OUTBOUND`; wszystkie `InvoiceStatus=NEW` |

**Ważna korekta względem dokumentacji:** osiem sprawdzonych źródeł CDS/BDEF odpowiada obecnie eksportowi Git po pominięciu białych znaków i wielkości liter. Notatka o czterech starszych plikach nie opisuje już tego checkoutu. Dokumentacji nie aktualizowałem.

W bezpieczeństwie live potwierdzono:

- root DCL `where TRUE`;
- dziedziczenie tego warunku przez projekcje i dzieci;
- `authorization master(global)`;
- pustą metodę `GET_GLOBAL_AUTHORIZATIONS`.

To nie ustanawia izolacji podatników ani skutecznej polityki zapisu. Nie wykonywałem prób zapisujących, więc nie stwierdzam, które żądania aktualnego użytkownika technicznego zostałyby faktycznie przyjęte.

Po stronie CAP:

- [ainv-h01.cds](/D:/AInvoicer/cap-java/srv/ainv-h01.cds) zawiera ręcznie opracowany model zewnętrzny bez akcji i adnotacji ETag.
- [SapInvoiceRemoteAdapter.java](/D:/AInvoicer/cap-java/srv/src/main/java/customer/cap_java/sap/SapInvoiceRemoteAdapter.java:25) wykonuje trzy odczyty CQN w `systemUser()`.
- Adapter nie pobiera pól wersji w swoim `$select`.
- [SapInvoiceSnapshot.java](/D:/AInvoicer/cap-java/srv/src/main/java/customer/cap_java/sap/SapInvoiceSnapshot.java) kopiuje mapy danych; nie przenosi kontenera metadanych `CdsData`, w którym CAP przechowuje ETag.
- Obecne testy obejmują mock `CqnService`, lokalny kontrakt HTTP i opcjonalny LIVE READ. Nie testują zapisu ani współbieżności.

Wersje: CAP Java **5.2.0**, Spring Boot **4.1.1**, Cloud SDK BOM **5.33.0**, JDK **25.0.4.1**, Maven **3.9.16**.

CAP-03 LIVE READ 4/9/6 pozostaje wcześniejszym wynikiem z dziennika. W tej analizie nie powtarzałem testu CAP ani odczytu wszystkich dzieci. Nie potwierdzono deploymentu CAP na CF.

## 2. Architecture Decision Matrix

| Kryterium | A — PATCH nagłówka | B — RAP Action | C — osobna encja integracyjna |
|---|---|---|---|
| Zmiany ABAP | Pola, mapowanie, walidacje i ograniczenie zapisywalnych pól | Pola, parametry/wynik akcji, handler i autoryzacja | Tabela, CDS, behavior, DCL, ekspozycja i relacja do faktury |
| Zmiany CAP | CQN UPDATE, model ETag, mapowanie błędów | Model akcji, event context i adapter | Model encji oraz adapter CREATE/UPDATE |
| Powierzchnia zapisu | Wymaga szczególnej ochrony pozostałych pól | Ściśle określone parametry | Ograniczona do danych integracji |
| Autoryzacja | UPDATE plus kontrola pól i instancji | Osobne prawo wykonania oraz kontrola docelowej faktury | Oddzielna polityka operacji i powiązania z fakturą |
| ETag | Natywne `If-Match` przez CQN | Akcja instancyjna wymaga rozwiązania przekazania `If-Match`; proponowany wariant opisano niżej | Własny ETag; nie chroni automatycznie nagłówka |
| Przejścia stanów | Walidacja kompletnego stanu po PATCH | Jedno miejsce egzekwujące semantykę wyniku | Walidacje własnego BO |
| Idempotencja | Potrzebna poza samym PATCH | Naturalna część kontraktu akcji | Dobre możliwości kluczy i ograniczeń unikalności |
| Błędy | Standard OData i komunikaty walidacji | Jawny rezultat i stabilne kody domenowe | Standard CRUD oraz błędy powiązań |
| CAP Java 5.2.0 | UPDATE i obsługa ETag potwierdzone w bibliotekach | Handler zdalnych akcji potwierdzony; nowy kontrakt wymaga testu HTTP | Standardowy CRUD obsługiwany |
| Rozszerzalność | Nagłówek może stopniowo przejąć zbyt wiele procesu | Można rozszerzać semantykę bez otwierania ogólnego UPDATE | Najlepsza dla wielu środowisk, wyników i relacji |
| Utrzymanie | Niskie początkowo; trudniejsze zabezpieczenie ogólnego CRUD | Umiarkowane, przy małym kontrakcie | Najwięcej artefaktów na obecnym etapie |

**Wybór: B.** Potrzebujemy jednej kontrolowanej operacji biznesowej, a nie dowolnej edycji nagłówka. Osobna encja będzie uzasadniona przy wielu wynikach lub środowiskach przypisanych do jednej faktury.

CAP Remote Services używa CQN i SAP Cloud SDK Destinations; wywołanie klienta pozostaje jawne w adapterze. [SAP CAP: Remote Services](https://cap.cloud.sap/docs/java/cqn-services/remote-services).

## 3. Recommended Design

Pierwszy kontrakt powinien obsługiwać wyłącznie **przypisanie potwierdzonego numeru KSeF do istniejącej faktury**.

Do SAP nie trafiają stany `PENDING`, `RETRY`, błędy auth ani odrzucone próby wysyłki. Pozostają w CAP. Nie ma wtedy potrzeby definiowania synchronizowanego katalogu `KsefStatus` w SAP.

Proponuję:

- publiczną **statyczną akcję RAP** `ApplyKsefResult`, odnoszącą się do kolekcji;
- jawny `InvoiceUUID` w parametrach;
- wymagany `ExpectedLocalLastChangedAt`;
- wewnętrzną akcję instancyjną, która uzyskuje blokadę root, ponownie odczytuje stan, autoryzuje fakturę i stosuje wynik.

To świadomy wybór kontroli wersji na poziomie aplikacji. Statyczna akcja nie korzysta automatycznie z ETag konkretnej instancji. Wewnętrzna akcja musi porównać wersję **po uzyskaniu blokady**, przed zmianą danych. Sam odczyt i porównanie przed blokadą byłyby niewystarczające.

SAP dokumentuje, że akcje statyczne same nie blokują instancji, a zwykłe akcje instancyjne domyślnie ją blokują. Zachowanie proponowanego wywołania wewnętrznego należy potwierdzić testem konkurencji w H01 przed LIVE WRITE. [SAP: Action Definition](https://help.sap.com/docs/abap-cloud/abap-rap/action-definition).

Przebieg:

1. Sprawdzenie uprawnienia do `ApplyKsefResult`.
2. Walidacja parametrów i dozwolonego środowiska.
3. Wywołanie wewnętrznej operacji na wskazanym UUID.
4. Blokada root, ponowny odczyt oraz kontrola NIP/CompanyCode/kierunku.
5. Rozpoznanie identycznego, wcześniej zapisanego wyniku.
6. Dla nowego wyniku: kontrola wersji i dopuszczalnego przejścia.
7. Zapis przez managed RAP w jednej LUW.
8. Odpowiedź `APPLIED` albo `ALREADY_APPLIED`.

Bez `COMMIT WORK` w handlerze i bez wywołań KSeF z SAP.

Dla sprzedaży wynik oznacza potwierdzenie przyjęcia faktury przez KSeF. Dla zakupu oznacza przypisanie numeru dokumentu odebranego z KSeF do uzgodnionej faktury SAP. Nie oznacza zatwierdzenia księgowego ani akceptacji faktury przez nabywcę.

Pierwsza implementacja powinna umożliwiać tylko **OUTBOUND/TEST**. INBOUND otrzymuje później osobny scenariusz dopasowania.

## 4. Proposed Data Model

Poniższe nazwy są propozycją.

| Pole w SAP | Proponowany typ | Znaczenie i właściciel |
|---|---|---|
| `KsefNumber` | `abap.char(50)` → `Edm.String` | Numer potwierdzony przez CAP; długość zgodna z obecnym modelem CAP, bez deklarowania jej jako oficjalnego limitu MF |
| `KsefEnvironment` | `abap.char(4)` | `TEST`, `DEMO`, `PROD`; po pierwszym zapisie niezmienne |
| `KsefRecordedAt` | timestamp UTC zgodny z istniejącymi typami audytowymi RAP | Czas pierwszego zastosowania wyniku, ustawiany przez SAP |
| `KsefWriteRequestId` | `sysuuid_x16` → `Edm.Guid` | Identyfikator pierwszego zastosowanego polecenia, generowany i utrwalany w CAP |

Wszystkie cztery pola są **readonly dla standardowego CREATE/UPDATE**. Zmienia je wyłącznie implementacja akcji.

Parametry akcji:

| Parametr | Typ |
|---|---|
| `InvoiceUUID` | Guid |
| `WriteRequestId` | Guid |
| `KsefNumber` | String(50) |
| `Environment` | String(4) |
| `TaxpayerNip` | String(10), sprawdzany względem autoryzacji i faktury |
| `ExpectedLocalLastChangedAt` | DateTimeOffset z zachowaniem precyzji |

Wynik: `Outcome`, UUID faktury, zapisany numer i środowisko, identyfikator zastosowanego polecenia, czas zastosowania oraz aktualny `LocalLastChangedAt`.

Nie proponuję obecnie:

- `KsefStatus` — obecność potwierdzonego numeru wystarcza dla pierwszego kontraktu;
- `KsefLastSyncAt` — identyczne ponowienie nie powinno zmieniać danych ani ETag;
- kopiowania numeru sesji, `referenceNumber`, XML, UPO lub historii prób do SAP.

`ZAINV_INV_REF` pozostaje modelem referencji do źródeł. Jego pola nie zapewniają semantyki środowiska, idempotencji i potwierdzenia wyniku.

Kontekst podatnika wyznaczamy z uzgodnionego przypisania spółki i kierunku: dla OUTBOUND kontrolujemy sprzedawcę, dla INBOUND nabywcę. Dla zakupu NIP wystawcy wynikający z numeru KSeF nie musi być NIP-em podatnika pobierającego dokument.

Jedna faktura SAP otrzymuje w tym wariancie jeden niezmienny wynik w jednym środowisku. Obsługa kilku środowisk dla tego samego UUID wymagałaby wariantu C.

## 5. Security Model

Obecne `where TRUE` i pusta implementacja autoryzacji są **blokerem pierwszego LIVE WRITE**.

Minimalny model:

1. **Uprawnienie do operacji w SAP.**  
   Własny obiekt autoryzacyjny sprawdzany przez `AUTHORITY-CHECK`, z wartościami nadanymi użytkownikowi komunikacyjnemu przez właściwy Communication Scenario/Arrangement.

2. **Odmowa ogólnego CRUD dla użytkownika integracyjnego.**  
   CREATE/UPDATE/DELETE nagłówka, operacje na dzieciach i create-by-association muszą być jawnie zabronione temu użytkownikowi. Samo dodanie akcji nie zamyka istniejących ścieżek.

3. **Kontrola docelowej faktury.**  
   Akcja statyczna nie otrzymuje automatycznie instancyjnej autoryzacji UUID z payloadu. Handler musi sprawdzić dozwolony NIP, spółkę, kierunek i środowisko na podstawie danych SAP.

4. **Izolacja odczytu.**  
   Root DCL musi filtrować dozwolony zakres. Trzeba sprawdzić dziedziczenie również dla bezpośrednich odczytów dzieci.

5. **Ochrona ścieżki wewnętrznej.**  
   `IN LOCAL MODE` może omijać DCL i authorization handlers. Przed takim użyciem potrzebna jest jawna kontrola polityki; samo wywołanie wewnętrzne nie jest dowodem uprawnienia.

SAP rozdziela autoryzację odczytu CDS od kontroli operacji RAP. Uprawnienia użytkowników komunikacyjnych wynikają również ze scenariusza i arrangementu; ograniczeń ról użytkowników biznesowych nie należy automatycznie przenosić na communication usera. [SAP: Authorization Basics](https://help.sap.com/docs/sap-btp-abap-environment/abap-environment/authorization-basics).

Nie potwierdzono aktualnych efektywnych uprawnień `AINV_CAP_USER`. Odczyt MCP odbywa się w innym kontekście OAuth i nie zastępuje testów tego użytkownika.

Przed WRITE potrzebne są też:

- potwierdzenie rotacji hasła opisanego w dzienniku CAP-03 jako ujawnione;
- negatywne testy cudzej faktury i niedozwolonej operacji;
- obsługa CSRF wymagana przez docelowy endpoint, sprawdzona w rzeczywistym kliencie;
- brak publicznego proxy umożliwiającego konsumentowi CAP dowolne wywołanie akcji H01.

`@requires` oraz `systemUser()` w CAP nie ustanawiają uprawnień w SAP.

## 6. ETag & Idempotency Strategy

**ETag chroni wersję zasobu. Idempotencja rozpoznaje wcześniej zastosowany wynik.**

Dla PATCH CAP potrafi przenieść `ETagPredicate` do `If-Match`; dokumentacja opisuje też odczyt ETag z kontenera metadanych danych. Obecny `SapInvoiceSnapshot` tego kontenera nie zachowuje. [SAP CAP: ETag w Remote OData](https://cap.cloud.sap/docs/releases/2024/mar24#etag-in-remote-odata-services).

Dla proponowanej akcji statycznej używamy jawnego porównania `ExpectedLocalLastChangedAt`. Nie parsujemy ani nie rekonstruujemy nieprzezroczystego ETag.

| Scenariusz | Oczekiwane zachowanie |
|---|---|
| Dwa różne wyniki równocześnie | Blokada root serializuje operacje; pierwszy zapis może przejść, drugi kończy się konfliktem |
| Dwa identyczne wyniki | Jeden zapis; drugi `ALREADY_APPLIED` |
| Nieaktualna wersja, wynik jeszcze niezapisany | `VERSION_CONFLICT`, bez zmiany danych |
| Identyczny wynik już zapisany, stara wersja | Po autoryzacji: `ALREADY_APPLIED`; brak mutacji i zmiany ETag |
| Ten sam `WriteRequestId`, zmieniony wynik | `IDEMPOTENCY_CONFLICT`, bez zapisu |
| Inny request ID, identyczny wynik biznesowy | `ALREADY_APPLIED`; zachowanie pierwotnego czasu i request ID |
| Inny numer lub środowisko po potwierdzeniu | `RESULT_CONFLICT`; brak automatycznego zastąpienia |
| Timeout lub utrata odpowiedzi | CAP oznacza zapis jako wymagający rekoncyliacji i odczytuje SAP po UUID |
| Wynik w SAP zgodny | CAP potwierdza zastosowanie |
| Wynik nieobecny | Można ponowić to samo trwałe polecenie po kontroli wersji i zakresu |
| Wynik rozbieżny | Zatrzymanie automatycznego retry |
| Błąd autoryzacji | Brak automatycznych ponowień |
| Błąd walidacji | Trwała informacja o odrzuceniu; korekta wymaga nowej świadomej decyzji |

Dla standardowego PATCH/bound action z warunkiem HTTP niezgodny ETag oznacza 412, a brak wymaganego warunku 428. Dla proponowanego CAS w akcji używamy stabilnego kodu domenowego; mapowanie na HTTP trzeba ustalić i przetestować w RAP. [OData 4.0: If-Match](https://docs.oasis-open.org/odata/odata/v4.0/errata01/os/complete/part1-protocol/odata-v4.0-errata01-os-part1-protocol-complete.pdf).

Dodatkowe granice:

- Obecny root ETag nie jest potwierdzonym numerem wersji całego agregatu. Dzieci mają własne ETag.
- Przed rzeczywistym powiązaniem wyniku wysyłki trzeba wykrywać zmianę danych źródłowych względem wysłanej migawki, także pozycji.
- Idempotency key `KsefOperations` nie zastępuje idempotencji writebacku.
- Awaria zapisu SAP nie może zmienić potwierdzonego wyniku KSeF na „nieprzyjęty” ani uruchomić ponownej wysyłki faktury.

CAP i RAP wykonują odrębne transakcje. Trwałe polecenie writebacku powstaje w CAP, a worker wykonuje je po commit.

## 7. CAP Java Integration Design

Proponowany port aplikacyjny — **projekt API, nie istniejący kod**:

```java
interface SapKsefResultWriter {
    SapWritebackResult apply(ApplyKsefResultCommand command);
}
```

Odpowiedzialności adaptera:

- wywołanie zdalnej akcji;
- mapowanie UUID, timestampów i wyniku;
- bezpieczna klasyfikacja błędów;
- brak samodzielnego zarządzania workflow KSeF i trwałym retry.

Potrzebny będzie także odczyt faktury po UUID z jawną wersją i danymi wyniku integracji.

W używanych bibliotekach potwierdziłem:

- `CqnService.run(CqnUpdate)`;
- `Service.emit(EventContext)`;
- `EventContext.create(...)`;
- handler `handleActionAndFunction` w Remote OData 5.2.0;
- obsługę ETag dla UPDATE/DELETE.

Akcję wywołujemy przez wygenerowany context lub `EventContext` na zdalnym serwisie `AinvoicerH01`. Model musi pochodzić z nowego EDMX i poprawnie zachować namespace, rodzaj bindingu oraz parametry. Samo dodanie nazwy akcji w Javie nie tworzy kontraktu. [SAP CAP: Actions and Functions](https://cap.cloud.sap/docs/java/cqn-services/application-services#actions-and-functions).

Destination `AINV_H01` pozostaje źródłem połączenia i credentiali. Nie należy ustawiać dynamicznego ETag we współdzielonej konfiguracji nagłówków.

W CAP potrzebna jest niewielka trwała encja writebacku, np. `SapResultWritebacks`:

- request ID i korelacja do operacji/faktury CAP;
- system SAP i `InvoiceUUID`;
- NIP, środowisko, numer KSeF;
- oczekiwana wersja;
- stan dostarczenia, termin kolejnej próby i bezpieczny kod błędu.

Trwała unikalność powinna dotyczyć logicznego wyniku dla systemu, faktury i środowiska. Ponowne zlecenie z innymi danymi musi wykrywać konflikt, a nie tworzyć drugi niezależny writeback.

Proponowane testy:

| Warstwa | Zakres |
|---|---|
| Unit | Mapowanie, zgodność NIP/kierunku, idempotencja i klasyfikacja błędów |
| Mock HTTP Contract | Prawdziwy RemoteService, URI akcji kolekcyjnej, namespace, JSON, UUID, precyzja timestampu, CSRF i odpowiedzi błędów |
| Integracyjne CAP | Zapis polecenia po commit, rollback zlecenia, konkurencja, restart i rekoncyliacja |
| ABAP Unit/EML | Autoryzacja, blokada przed CAS, brak nadpisania, identyczne ponowienie i atomowość |
| LIVE WRITE | Jeden wskazany syntetyczny UUID, odczyt przed/po, ponowienie i konflikt |

LIVE WRITE musi mieć oddzielny tag oraz flagę, np. `live-sap-write` i `AINV_H01_LIVE_WRITE=true`. Istniejąca flaga LIVE READ nie powinna uruchamiać zapisu.

## 8. Implementation Plan

### CAP-04B — ABAP RAP Write Contract

**Obiekty do zmiany:**

- `ZAINV_INV_H` — cztery pola wyniku, przez ADT;
- `ZR_AINV_INV_H`, `ZC_AINV_INV_H` — ekspozycja pól;
- root BDEF — readonly, akcja publiczna i wewnętrzna;
- projection BDEF — ekspozycja wyłącznie publicznej akcji;
- `ZBP_R_AINV_INV_H` — autoryzacja, walidacja, CAS i idempotencja;
- nowe abstrakcyjne encje parametrów/wyniku;
- root DCL i zależne reguły wymagające dopasowania;
- obiekt autoryzacyjny oraz ustawienia Communication Scenario;
- testy ABAP.

**Zależności:** akceptacja kontraktu, polityka NIP/CompanyCode, porównanie active/inactive i blokad. `recordChanges` należy ponownie sprawdzić przy implementacji.

**Akceptacja:** aktywacja i adekwatne ATC/ABAP Unit; nowy EDMX; brak zapisu nowych pól przez CRUD; użytkownik integracyjny wykonuje wyłącznie dozwoloną akcję; test konkurencji potwierdza blokadę przed CAS.

**Ryzyka:** wpływ autoryzacji na istniejących konsumentów, precyzja timestampów i zachowanie akcji wewnętrznej.

**Rollback:** wyłączenie dostępu do akcji i przywrócenie zachowania z zachowanego stanu live. Nowe pola można pozostawić nieużywane; rollback nie powinien usuwać potwierdzonych wyników.

### CAP-04C — CAP Java Write Adapter

**Pliki do zmiany:**

- `srv/ainv-h01.cds`;
- nowy port/adapter i DTO w pakiecie `sap`;
- odczyt po UUID z wersją;
- `db/schema.cds` — trwałe polecenia writebacku;
- worker i testy;
- `application.yaml` — wymagane ustawienia CSRF i aktywacji funkcji.

Publiczny `KsefService` nie powinien udostępniać dowolnego polecenia zapisu SAP.

**Zależności:** aktywny kontrakt CAP-04B i jego rzeczywisty EDMX.

**Akceptacja:** test HTTP potwierdza kolekcyjną akcję przez RemoteService 5.2.0; trwałe zlecenie i rekoncyliacja działają; domyślny build nie uruchamia LIVE WRITE.

Planowane polecenie kontrolne:

```powershell
mvn clean verify '-Dcds.cds.skipVersionCheck=true'
```

Obejście kontroli wersji CDS pozostaje istniejącym ograniczeniem środowiska.

**Ryzyka:** namespace/binding akcji, błędy OData, retry workera i utrata odpowiedzi.

**Rollback:** wyłączenie workera/feature flag; zachowanie zleceń i ich stanu do rekoncyliacji.

### CAP-04D — LIVE WRITE Validation

**Zakres:** H01 Trial, jeden jawnie wskazany syntetyczny dokument OUTBOUND/TEST.

**Zależności:** CAP-04B/C zaliczone, rotacja credentiali potwierdzona, osobna zgoda na konkretny zapis.

**Akceptacja:**

- odczyt stanu i wersji przed zapisem;
- pierwszy zapis oraz niezależny GET po nim;
- identyczne ponowienie bez mutacji;
- odrzucenie zmienionego wyniku i starej wersji;
- test konkurencji;
- negatywne testy uprawnień;
- potwierdzenie niezmienionych danych faktury i `InvoiceStatus`.

Syntetyczny wynik testu kontraktu nie będzie dowodem przyjęcia faktury przez KSeF.

**Ryzyka:** częściowo niejednoznaczny rezultat HTTP i trwałe dane testowe.

**Rollback:** zatrzymanie kolejnych zapisów i zachowanie wyniku do analizy. Usunięcie lub korekta potwierdzenia wymaga kontrolowanej procedury; nie może być ogólnodostępną operacją klienta.

## 9. Open Questions

Decyzje wymagające akceptacji przed implementacją:

1. **Zakres pierwszej wersji:** tylko potwierdzony numer, bez synchronizacji błędów i stanów pośrednich — rekomendowane.
2. **Kontrakt:** wariant B ze statycznym wejściem i kontrolą wersji pod blokadą w RAP — rekomendowany ze względu na ustaloną ścieżkę akcji w CAP 5.2.0.
3. **Środowiska:** jeden wynik/środowisko na UUID faktury; jeżeli potrzebne są równoległe wyniki TEST/DEMO/PROD dla jednego UUID, należy od razu wybrać C.
4. **Zakres autoryzacji pierwszego WRITE:** konkretny podatnik, CompanyCode i syntetyczna faktura sprzedażowa.

W tej sesji wykonano inspekcję Git/plików, odczyty MCP, GET EDMX/danych oraz analizę API bibliotek przez `javap`. **Nie uruchamiano buildów, testów ABAP ani operacji zapisujących. LIVE WRITE pozostaje NOT TESTED.** Następny krok to zatwierdzenie powyższego kontraktu dla CAP-04B.