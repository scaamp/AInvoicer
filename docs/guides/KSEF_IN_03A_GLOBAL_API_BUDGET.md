---
title: "KSEF-IN-03A — globalny budżet API i współbieżność INBOUND"
date: 2026-11-11
type: guide
status: offline-implemented-live-blocked
tags: [ainvoicer, ksef, inbound, cap-java, concurrency, rate-limit, hana]
---

# KSEF-IN-03A — globalny budżet API

**Komponent offline; nie jest podłączony do synchronizacji ani Spring.** Nie zapewnia obecnie
globalności między JVM. INBOUND/CF pozostają zablokowane dotychczasowym guardem.

[Architektura INBOUND](KSEF_INBOUND_ARCHITECTURE.md) · [Persystencja INBOUND](KSEF_INBOUND_PERSISTENCE.md) ·
[Dziennik IN-02](../journal/2026-11-10-ksef-in-02.md) ·
[Dziennik tej zmiany](../journal/2026-11-11-ksef-in-03a-global-api-budget.md).

## Baza, zakres i świadome odstępstwa

Worktree `D:\AInvoicer-ksef-in-03a`, branch `feature/ksef-in-03a-global-api-budget`,
baza `origin/main=319adbf77941f6f0d9edf4fcb9af08e80ee83bc8` po fetch 11 listopada 2026.
Główny checkout pozostawiono na jego gałęzi. Nowe pliki Java/JUnit i dokumentacja są jedyną zmianą.
Zgodnie ze zleceniem **nie aktualizujemy STATUS/ROADMAP/README**, mimo ogólnej reguły AGENTS/Skills.
Nie zmieniamy CDS, ADR-0008, storage, BLOB-LAB-01, deployera CAP-HANA-01R, MTA ani infrastruktury.

## Audyt rzeczywistej implementacji

Ścieżki poniżej względem `cap-java/srv/src/main/java/customer/cap_java/ksef/`.

| Miejsce | Potwierdzone zachowanie | Ryzyko / granica |
| --- | --- | --- |
| `inbound/KsefInboundClient.queryMetadataPage` | Jedno `sdk.queryInvoiceMetadata` → POST `/v2/invoices/query/metadata` | Brak wspólnego licznika i odstępu między stronami |
| `inbound/KsefInboundClient.downloadInvoice` | Jedno `sdk.getInvoice` → GET `/v2/invoices/ksef/{ksefNumber}` | Każdy nowy XML to osobne żądanie |
| `inbound/InvoiceRetrievalService.fetchSegment` | `retry.execute` osobno dla każdej strony, do końca segmentu / truncation | Szybka pętla stron może wyczerpać limit godzinowy w jednym przebiegu |
| `InvoiceRetrievalService.downloadVerified` | `retry.execute` osobno dla GET XML, potem hash | Deduplikacja ogranicza ponowne GET znanych dokumentów, ale nie rozlicza nowych |
| `inbound/ReadRetryPolicy` | Max 3 próby domyślnie; 1/2 s + jitter do 250 ms bez wskazania serwera, inline do 10 s | Retry nie rezerwuje wspólnego budżetu; jitter nie jest limiterem |
| `KsefInboundClient.retryAfter/classifyHttp` | Sekundy albo RFC1123; 429 bez poprawnej wartości → 60 s; bezpieczny kod błędu | 60 s jest fallbackiem aplikacji, nie gwarancją MF; skrajnie duża liczba sekund może przepełnić `Instant` — istniejącego parsera tu nie zmieniano |
| `inbound/sync/InboundSynchronization.runOnce` | Claim przed auth, wynik/błąd kończy lease; następny przebieg ≥15 min+jitter albo dłuższy Retry-After | Dotyczy wyłącznie bieżącego scope, nie pozostałych ról/NIP |
| `inbound/persistence/CqnInboundSyncLeaseRepository` | Wiersz `(environment,contextNip,role)`, lock/unikalność, losowy token; 10 min lease koordynatora, bez renew | Nie synchronizuje endpointów między rolami; worker po wygaśnięciu może kończyć I/O |
| `leases.fenced` + checkpoint CAS | Własność lease sprawdzana pod blokadą w transakcji checkpointu | Chroni HWM przed starym workerem, nie odbiera już rozpoczętego HTTP; sama paginacja nie sprawdza lease przed każdą stroną |
| `KsefInboundConfiguration` | Jeden kontekst TEST/BUYER z backendu, OFF domyślnie; CF odrzucone także z trwałym store | Brak dzisiejszego aktywnego multi-NIP schedulera; niezależni runnerzy nadal mogą wywoływać wspólny adapter |
| `KsefInboundAccess.open/token` → `KsefAuthentication` | Auth challenge, token KSeF albo XAdES, status polling, redeem; refresh; usługa kryptografii może pobierać certyfikaty | To dodatkowe HTTP poza `ReadRetryPolicy` INBOUND; nie wolno objąć redeem zwykłym retry odczytu |
| `KsefSdkConfiguration` | SDK 3.0.29, TEST, timeout 20 s, Redirect.NEVER, breaker wyłączony | Breaker nie jest budżetem; brak współdzielonego limitera w aplikacji |

Przejrzano testy kontraktu HTTP, paginacji/retry, H2/CAS/lease/recovery oraz opcjonalnej konfiguracji.
Ich dowody dotyczą mocków/loopback/H2, nie limitów wdrożonego MF ani izolacji HANA.
Potencjalne bursty: równoczesne role/NIP, strony i GET w jednym przebiegu, krótkie retry 429/5xx,
ponowne okno po utracie lease, równoczesny auth/refresh po starcie wielu workerów.
Nie znaleziono współdzielonego mechanizmu rozliczania budżetu endpointów.

## Oficjalne źródła i niewiadome

Sprawdzono 2026-11-11 publiczne źródła MF: commit przewodnika
[`ce3670ea042de380039de4c5849fd6455811b292`](https://github.com/CIRFMF/ksef-api/tree/ce3670ea042de380039de4c5849fd6455811b292),
[limity żądań, wersja 14.09.2026](https://github.com/CIRFMF/ksef-api/blob/ce3670ea042de380039de4c5849fd6455811b292/limity/limity-api.md),
[limity techniczne](https://github.com/CIRFMF/ksef-api/blob/ce3670ea042de380039de4c5849fd6455811b292/limity/limity.md)
i [źródło klienta SDK v3.0.29](https://github.com/CIRFMF/ksef-client-java/blob/v3.0.29/ksef-client/src/main/java/pl/akmf/ksef/sdk/api/DefaultKsefClient.java).
SDK w repo pozostaje przypięte do `3c3a899541074aa4bd944636b25659aef7d23da7`.

MF opisuje rozliczenie per **kontekst uwierzytelnienia i IP**, niezależnie od roli faktury.
Progi sekundowy, minutowy i godzinowy obowiązują jednocześnie w oknach przesuwnych.
Tabela PROD: metadane 8/16/20, XML 8/16/64 (sekunda/minuta/godzina).
Domyślny TEST ma mnożnik 10; DEMO odpowiada PROD. Blokada 429 ma dynamiczny Retry-After.
Limity mogą się zmieniać; wartości nocne nie są podane. Nie wykonano żadnego odczytu LIVE API,
więc indywidualne limity, konfiguracja TEST i rzeczywisty egress CF pozostają niepotwierdzone.

**Własne założenia aplikacji:** limit łączny środowiska, `maxConcurrent`, konserwatywna blokada całego
środowiska po Retry-After oraz grupowanie nieznanych wyjść. W kodzie nie ma domyślnych wartości MF
ani mnożnika TEST. Operator musi dostarczyć sprawdzoną konfigurację; testy stosują liczby syntetyczne.
Grupy wyjścia nie służą rotowaniu IP ani obchodzeniu ograniczeń. Przy niepewnym NAT należy użyć
jednej grupy obejmującej wszystkie możliwe wyjścia.

## Decyzja projektowa: dokładne okna przesuwne

Fixed window dopuszcza podwójny burst na granicy minuty. Token bucket wymaga doboru burst/refill
i sam nie odwzorowuje wszystkich progów rolling window. Przy małych budżetach INBOUND prostsza
jest lista prób oraz sprawdzenie wszystkich okien w jednej sekcji atomowej.

```text
konfiguracja backendu + Request(context, operation, egressGroup)
    → InboundApiBudget.acquire
        → ApiBudgetRepository.transact(environment, transition)
            → polityka zgodna? czas monotoniczny? Retry-After?
            → wspólne okna + okna endpointu + liczba in-flight
            → ALLOW z zarezerwowanym permit / DEFER / REJECT
```

Implementacja to zwykła Java bez nowych zależności:

- [InboundApiBudget.java](../../cap-java/srv/src/main/java/customer/cap_java/ksef/inbound/budget/InboundApiBudget.java):
  immutable konfiguracja per środowisko, ALLOW/DEFER/REJECT, testowalny Clock, dwa obecne endpointy.
- [ApiBudgetRepository.java](../../cap-java/srv/src/main/java/customer/cap_java/ksef/inbound/budget/ApiBudgetRepository.java):
  port atomowej zmiany immutable State i wynik zwracany dopiero po potwierdzonym zapisie.
- [InMemoryApiBudgetRepository.java](../../cap-java/srv/src/main/java/customer/cap_java/ksef/inbound/budget/InMemoryApiBudgetRepository.java):
  monitor JVM wspólny dla polityk korzystających z **tego samego obiektu repozytorium**. Nowy obiekt
  lub restart zeruje historię; brak gwarancji między procesami. Brak beana/fallbacku runtime.

Okna wspólne obejmują wszystkie operacje i NIP w środowisku. Endpointy mają osobne okna per
`(environment, NIP, egressGroup, operation)`, bez roli, strony, workera i użytkownika w kluczu.
`maxConcurrent` ogranicza łączną liczbę trwających prób w środowisku.
DEFER nie przyznaje przyszłego slotu: w terminie `retryNotBefore` trzeba ponownie wywołać acquire.
Przy nieznanym czasie zwolnienia slotu/awarii repo termin jest null. Nie wolno interpretować null jako „teraz”.

Każde ALLOW zużywa jedną próbę także po błędzie HTTP lub rezygnacji z HTTP. Zakończenie wymaga
dokładnego permit ID i właściwego środowiska; powtórne/obce complete zwraca false. Retry-After jest
przekazywany jako Instant, a blokada może się tylko wydłużyć. Już trwające HTTP nie jest anulowane.
Przy równoczesnym wyczerpaniu okien wybierany jest najpóźniejszy termin; granica jest dokładna.
Próba in-flight obciąża każde swoje okno bez względu na wiek, a complete przesuwa `chargedAt`
na moment zakończenia. To konserwatywne rozszerzenie względem liczenia samych startów chroni przed
odzyskaniem budżetu przez opóźniony dispatch albo długi HTTP. Przy samych in-flight blokujących
okno termin pozostaje nieznany (null); slot jest oceniany ponownie po complete.

Brak konfiguracji, zera/ujemne wartości, puste/zdublowane okna, nieobsługiwany endpoint/środowisko
→ REJECT. Limity są kopiowane; ich zmiana w istniejącym stanie wymaga osobnej migracji, a nie resetu.
Cofnięcie Clock → DEFER do watermarku. Błąd repo/czasu, w tym przepełnienie Instant → DEFER,
bez wiadomości/cause adaptera w odpowiedzi i bez fallbacku. Duży skok czasu w przód pozostaje
ryzykiem zegara; przed produkcją konieczny jest autorytatywny, monitorowany czas DB.

Nie ma automatycznego TTL dla zajętego slotu. Crash/niepewne potwierdzenie commit mogą trwale
zająć slot; jest to świadoma utrata dostępności na rzecz fail-closed. Kontrolowane odzyskanie wymaga
udowodnienia, że stary worker nie wyśle HTTP. Nie ma obecnie API operatora do takiego odzyskania.

## Przyszły kontrakt HANA — projekt, NIE implementacja

Decyzja o CDS dopiero po CAP-HANA-01 i osobnym review. Proponowane prywatne dane:

| Rekord | Klucz / zawartość |
| --- | --- |
| Coordinator | PK(environment), version, policyDigest/revision, lastObservedAt, blockedUntil, epoch, health |
| Attempt | PK(environment, permitId), contextKey, operation, egressGroup, chargedAt, inFlight, owner/fencing |

NIP to metadane kontekstu, nie credential; alternatywnie stabilny wewnętrzny klucz. Ograniczyć dostęp
do danych i logów; nie używać NIP jako etykiety metryk o wysokiej kardynalności. Żadnych tokenów/XML.

Algorytm adaptera `transact`:

1. Nowy RequestContext/ChangeSet niezależny od transakcji biznesowej. Odczytaj Coordinator i czas DB.
2. Zablokuj wiersz albo użyj CAS po version. Załaduj cały potrzebny stan prób. Brak wiersza wymaga
   INSERT z unikalnym PK; przegrany wyścig robi rollback i odczyt w nowej transakcji.
3. Wylicz transition; atomowo zapisz próby, watermark, cooldown i version. CAS 0 wierszy → rollback,
   świeży odczyt i ograniczone ponowienie; wyczerpany retry → DEFER. Bez HTTP w callbacku/transakcji.
4. Zwróć ALLOW dopiero po commit. Timeout potwierdzenia commit → brak ALLOW, rezerwacji nie zwracaj.
5. Complete aktualizuje dokładnie własny permit oraz `blockedUntil=max(stare,nowe)` pod tym samym
   lock/CAS, a `chargedAt` przesuwa na czas zakończenia z DB. Prune usuwa wyłącznie zakończone próby
   starsze niż najdłuższe okno; nigdy in-flight.

Wspólny coordinator środowiska jest celowym punktem serializacji: pojedynczy reserve nie może
oddzielnie zużyć budżetu aplikacji i MF. Podział po bucketach można rozważyć dopiero po pomiarach,
z transakcją obejmującą wszystkie wymagane wiersze. Oddzielne repozytoria per JVM są niedopuszczalne.
Clock musi pobierać czas DB wewnątrz tej transakcji; konwersja precyzji nie może przyspieszać expiry.
Migracja polityki zachowuje historię dla większego starego/nowego okna i wymaga kontrolowanej rewizji.

Sam TTL lease nie dowodzi zakończenia HTTP. Przyszłe recovery potrzebuje epoch/fencing i stanu
dispatch, wyłączenia starego workera oraz rekoncyliacji niepewnych permitów. W razie niemożności
ustalenia stanu globalnego obowiązuje DEFER i zatrzymanie dispatch, bez przejścia na pamięć lokalną.
Niepewne complete (szczególnie po 429) wymaga bramki health dla wszystkich workerów i ponownego
utrwalenia cooldownu przed wznowieniem, a nie tylko zatrzymania jednego NIP.

## Plan bezpiecznego podłączenia do INBOUND

1. Zatwierdzić schemat i adapter HANA oraz testy poniżej. Guard CF i flaga OFF pozostają do osobnego
   etapu wdrożeniowego. Nie rejestrować repo in-memory jako zamiennika na CF.
2. Zautoryzować konfigurację NIP/środowiska/egress i włączyć wspólny budżet dla wszystkich ról,
   workerów i instancji. Wspólnych endpointów nie mogą omijać inne moduły aplikacji.
3. Rezerwować **każdą próbę strony i GET**, wewnątrz supplier `ReadRetryPolicy`, bezpośrednio przy
   wywołaniu adaptera/SDK. Nigdy raz na segment, przebieg albo na zewnątrz retry.
4. DEFER przerwać bez HTTP, zachować HWM i utrwalić due bez aktywnego oczekiwania; planować ponowną
   ocenę z dodatnim jitterem, nigdy przed due. REJECT wymaga naprawy konfiguracji przez operatora.
5. Complete po faktycznym zakończeniu jednej próby; przekazać Retry-After również z ostatniej próby
   retry. 429 bez poprawnego nagłówka wymaga konserwatywnego fallbacku i monitoringu. Niepewny zapis
   complete zatrzymuje globalny dispatch do rekoncyliacji. Nie przekazywać permitu między żądaniami.
6. Osobno objąć auth/challenge/status/certyfikaty/refresh sprawdzonymi limitami. Redeem/start auth
   zachowują dotychczasowe reguły niejednoznaczności; ten komponent nie daje prawa do ich retry.
7. Dopiero po bramkach HANA/security/storage, osobnym review i jawnym zleceniu rozważyć jeden
   syntetyczny przebieg TEST. Eksport paczek i scheduler to dalsze zadania.

## Scenariusze wieloinstancyjne HANA — NIE WYKONANO

| Scenariusz (co najmniej 2 niezależne JVM) | Oczekiwany dowód |
| --- | --- |
| Jednoczesny pierwszy INSERT i reserve w tym samym środowisku | Jeden coordinator, łączna liczba ALLOW ≤ budżet |
| Wiele NIP/operacji/ról i wspólny egress | Niezależne buckety MF, wspólny limit aplikacji i in-flight |
| Jednoczesne complete 429 z różnym Retry-After | Wspólny cooldown równy maksimum, widoczny w obu JVM |
| CAS conflict/deadlock/timeout/utrata DB | Rollback lub konserwatywne DEFER, bez lokalnego ALLOW |
| Commit reserve potwierdzony w DB, odpowiedź zgubiona | Brak dispatch; zajęty permit nie wraca do puli |
| Crash przed dispatch / w HTTP / przed complete | Brak ślepego wygaśnięcia, restart nie resetuje historii |
| Lease wygasł, stara JVM wraca po pauzie | Stary epoch nie nadaje prawa do nowego dispatch/zwolnienia następcy |
| Rozjazd zegarów JVM, granice sekund/minut/godzin i precyzja DB | Decyzje oparte na czasie DB, brak przedwczesnego expiry |
| Rolling deploy z innymi limitami, restart wszystkich JVM | Wykrycie policy mismatch, zachowanie historii/rezerwacji |
| Awaria complete po 429, powrót DB | Globalna bramka health do potwierdzonego cooldownu |

Kryterium wdrożenia: rzeczywisty test izolacji/transakcji HANA i powyższych awarii, nie samo H2 PASS.
HANA sama nie zna ruchu innych aplikacji używających tej samej pary kontekst/IP. Potrzebna jest
koordynacja wszystkich klientów lub zapas budżetu i respektowanie 429. Exact limit MF nie jest
gwarantowany przez sam moment rezerwacji; integracja musi kontrolować opóźnione dispatch i transport.

## Weryfikacja offline

Testy: [InboundApiBudgetTest.java](../../cap-java/srv/src/test/java/customer/cap_java/ksef/inbound/budget/InboundApiBudgetTest.java).
Clock sterowany atomowo, bariery CountDownLatch i Future z timeoutem; żadnego sleep ani HTTP.
Sprawdzają pierwszą rezerwację, wszystkie okna, NIP/egress/operacje/środowiska, 24 równoległych workerów,
różne instancje polityki ze wspólnym repo, permit ownership, utratę commit acknowledgment,
awarię repo, cofnięcie/przepełnienie czasu, konfigurację, retencję i globalny Retry-After.
Osobny test pokazuje brak gwarancji między niezależnymi repo in-memory.

Wyniki końcowe i wersje narzędzi: [dziennik sesji](../journal/2026-11-11-ksef-in-03a-global-api-budget.md).
Zwykły Maven nie aktywuje profilu `hana-integration`, a tagi LIVE są wykluczone przez POM.
Nie uruchomiono synchronizacji, SEND, SAP WRITE, HANA DML/DDL, provisioning ani deploymentu.

Najmniejszy następny krok: review kontraktu atomowego repozytorium i recovery po niepewnym complete,
następnie osobna decyzja o schemacie po CAP-HANA-01. Do tego czasu używać komponentu tylko offline.
