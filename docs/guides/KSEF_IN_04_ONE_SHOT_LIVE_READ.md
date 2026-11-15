---
title: "KSEF-IN-04 — pojedynczy kontrolowany odczyt KSeF TEST"
date: 2026-11-14
type: guide
status: ready-for-live-approval
tags: [ainvoicer, ksef, inbound, test, auth, security, runbook]
---

# Pierwszy odczyt XML z KSeF TEST

[STATUS](../STATUS.md) · [Dziennik i wyniki](../journal/2026-11-14-ksef-in-04-first-live-read.md) ·
[INBOUND](KSEF_INBOUND_ARCHITECTURE.md) · [Persystencja](KSEF_INBOUND_PERSISTENCE.md) ·
[CF nadal NO-GO](KSEF_IN_03B_WORKER_READINESS.md)

**READY FOR LIVE APPROVAL. Auth i InvoiceRead obecnego principal: NOT VERIFIED.**
Podczas przygotowania odczytano publiczną dokumentację/OpenAPI; nie wykonano auth ani biznesowego
HTTP do KSeF. To nie jest dowód pobrania rzeczywistej faktury.

## Zakres i warunki

Właściciel zatwierdza osobno: dokładny commit, TEST, NIP, metodę TOKEN albo XADES,
scenariusz NUMBER z jednym numerem albo BUYER z konkretnymi granicami UTC, limit żądań
i jednorazowe wykonanie. Brak zgody oznacza zero auth/GET. Nie nadawaj uprawnień, nie twórz
nowego tokena i nie zmieniaj kontekstu w odpowiedzi na odmowę API.

`KsefInboundOneShotLiveTest` jest zwykłym JUnit bez Spring/contextu aplikacji. Używa istniejących
`KsefAuthentication`, fabryki SDK, `KsefInboundClient`, `InvoiceRetrievalService.downloadVerified`
z `ReadRetryPolicy.noRetry`, `SecureFa3InvoiceParser` i `crossCheck`. Nie uruchamia sinka,
`InboundSynchronization` ani runnera synchronizacji, ponieważ te komponenty zapisują stan.
XML/JWT pozostają w pamięci JVM. Harness nie otwiera DB, nie tworzy endpointu ani nie zapisuje pliku XML.
Modele CDS, BlobService, storage, scheduler, HANA, SAP i workstream Claude pozostają poza zakresem.

Scenariusze:

- **NUMBER:** jeden GET wskazanego numeru. Walidacja numeru (wzorzec/CRC) przed auth, niepuste
  bajty, limit 3 MiB, SHA-256 oryginału, parser FA(3), P_2/data/NIP/kwoty, zgodność sprzedawcy
  z prefiksem numeru KSeF i udział zatwierdzonego NIP jako sprzedawcy lub nabywcy.
  `metadataHash=NOT_CHECKED`: bez niezależnego hasha ten scenariusz potwierdza obliczenie skrótu.
- **BUYER:** jedna strona `Subject2`, `PermanentStorage`, Asc, pageOffset=0, pageSize=10 (minimum MF),
  jawne `from/to`, okno najwyżej 7 dni w przeszłości. Wybór pierwszego dokumentu i jeden GET;
  SHA-256/Base64 musi zgadzać się z `invoiceHash`, potem istniejący cross-check numeru/data/NIP/waluta
  i dodatkowe porównanie brutto. `hasMore/isTruncated` są raportowane, bez kontynuacji.
  Brak dokumentów to **EMPTY**, bez rozszerzania dat ani przejścia do SELLER.

Parser sprawdza format pól i bezpiecznie odrzuca DTD/encje; `FA3_BASIC_PASS` **nie oznacza pełnej
walidacji XSD**, księgowania ani akceptacji wszystkich wariantów sumowania VAT. Kwoty netto/VAT
są odczytywane jako BigDecimal; nie sumujemy sekcji korekt/zaliczek/marży. Porównanie brutto
z metadanymi jest fail-closed i podlega ograniczeniu `Double` w SDK. FA(2)/PEF/FA_RR to
`BLOCKED/UNSUPPORTED_SCHEMA` z rozmiarem i hashem, bez wybierania kolejnego dokumentu.
Limit XML jest egzekwowany po zbuforowaniu body przez SDK; timeout requestu to 20 s, połączenia 5 s.

Historyczny numer `1234563218-20261029-7DC8A1C00000-CD` pochodzi z syntetycznej wysyłki
29.10.2026 w kontekście TEST `1234563218`. Nie stanowi dowodu obecnego InvoiceRead ani
dostępności dokumentu, szczególnie w BUYER. Nie ma domyślnego numeru ani NIP w harnessie.

## Twarde bramki i budżet

Tag `live-ksef` jest domyślnie wykluczony z Maven. Dodatkowo test wymaga dwóch flag
`AINV_KSEF_ONESHOT_AUTH_APPROVED=true` i `AINV_KSEF_ONESHOT_READ_APPROVED=true`, środowiska
`TEST`, identycznych `AINV_KSEF_NIP`/`AINV_KSEF_ONESHOT_APPROVED_NIP`, scenariusza, metody auth
i jawnego `AINV_KSEF_ONESHOT_MAX_REQUESTS` w zakresie 1..9. Weryfikacja następuje przed SDK/crypto.
TOKEN wymaga sekretu; XADES odmawia przy zastanym tokenie, aby nie zmienić metody niejawnie.
Flagi są technicznym odwzorowaniem osobnej zgody, same nie zastępują decyzji właściciela.

`OneShotHttpClient` w test classpath dekoruje transport oficjalnego SDK. Rezerwuje budżet przed I/O,
sprawdza stały origin TEST, metodę i ścieżkę, odrzuca pozostałe operacje. Nie implementuje klienta API.

| Żądanie do `/v2` | Maksimum na wykonanie |
| --- | ---: |
| GET `/security/public-key-certificates` | 1, wyłącznie TOKEN (inicjalizacja crypto SDK) |
| POST `/auth/challenge` | 1 |
| POST `/auth/ksef-token` **albo** `/auth/xades-signature` | 1, tylko wybrana metoda |
| GET `/auth/{referenceNumber}` | 3 |
| POST `/auth/token/redeem` | 1 |
| POST `/invoices/query/metadata` | 1, tylko BUYER |
| GET `/invoices/ksef/{ksefNumber}` | 1 |

Łącznie maksymalnie **NUMBER XADES 7 / TOKEN 8; BUYER XADES 8 / TOKEN 9**. Niższy limit
jest dozwolony, ale może zatrzymać przebieg przed XML. Rzeczywista liczba zwykle będzie niższa,
gdy pierwszy status auth to 200. Polling istniejącego auth ma dodatkowo własny deadline;
dekorator nie dopuści czwartego statusu. Nie ma refresh, listy sesji, exports ani testdata.

Pierwsze non-2xx, timeout lub I/O zamyka transport na dalsze żądania, także próby ponowienia
wewnątrz istniejącego auth. 429 zachowuje `retryNotBefore` (sekundy/RFC1123; fallback 60 s).
Nie czekamy do tego terminu ani nie wykonujemy ponownego przebiegu automatycznie.
Redirect.NEVER, HTTP/1.1, circuit breaker OFF. Nowa JVM Surefire musi mieć
`jdk.httpclient.disableRetryConnect=true` oraz `jdk.httpclient.redirects.retrylimit=1`:
limit jednej próby obejmuje również ponowienia wewnątrz JDK. Wartości są sprawdzane przed siecią;
przekazuj je przez `argLine` przy starcie JVM, nie przez późniejsze `System.setProperty`.
Wyciszamy logger JVM na czas LIVE, odrzucamy włączone JDK HTTP/TLS wire logging, nie wypisujemy
wiadomości/przyczyn wyjątków SDK. Zwykły raport Surefire zawiera wyłącznie redagowany wynik.

To limit jednego lokalnego harnessu, nie globalny limiter wielu JVM ani zgoda na CF/scheduler.
Właściciel powinien potwierdzić brak równoległego ruchu w tym samym kontekście/egress.

## Konfiguracja lokalna i PowerShell

JDK 25, Maven 3.9.16, lokalnie zainstalowane przypięte SDK 3.0.29 według
[runbooka SDK](KSEF_TEST_RUNBOOK.md#przygotowanie-sdk-bez-pat). Nie aktualizuj SDK.
Pracuj w nowym lokalnym terminalu w worktree; nie włączaj profilu Spring, CF, INBOUND ani SEND.

Regresja offline, bez opt-in i bez znoszenia wykluczeń LIVE:

```powershell
Set-Location D:\AInvoicer-ksef-in-04\cap-java
mvn -B clean verify '-Dcds.cds.skipVersionCheck=true'
if ($LASTEXITCODE -ne 0) { throw 'Offline verification failed' }
```

**Poniższe ustawienia i wykonanie dopiero po osobnej zgodzie.** Przykład A wykorzystuje historyczny
syntetyczny kontekst; właściciel musi jawnie zatwierdzić właśnie te wartości i metodę XADES.

```powershell
Set-Location D:\AInvoicer-ksef-in-04\cap-java
$env:AINV_KSEF_ONESHOT_ENVIRONMENT = 'TEST'
$env:AINV_KSEF_NIP = '1234563218'
$env:AINV_KSEF_ONESHOT_APPROVED_NIP = '1234563218'
$env:AINV_KSEF_ONESHOT_SCENARIO = 'NUMBER'
$env:AINV_KSEF_ONESHOT_NUMBER = '1234563218-20261029-7DC8A1C00000-CD'
$env:AINV_KSEF_ONESHOT_AUTH_METHOD = 'XADES'
$env:AINV_KSEF_ONESHOT_MAX_REQUESTS = '7'
# XADES wymaga pustego AINV_KSEF_TOKEN; brak fallbacku z TOKEN do XADES.
```

Alternatywa B wymaga **odrębnej zgody na BUYER i konkretne daty**. Po ustawieniu NIP/metody
jak wyżej zastąp scenariusz; daty poniżej są przykładem okna, a nie wykonanym odczytem:

```powershell
$env:AINV_KSEF_ONESHOT_SCENARIO = 'BUYER'
$env:AINV_KSEF_ONESHOT_FROM = '2026-11-07T00:00:00Z'
$env:AINV_KSEF_ONESHOT_TO = '2026-11-14T00:00:00Z'
$env:AINV_KSEF_ONESHOT_MAX_REQUESTS = '8' # XADES; TOKEN wymaga najwyżej 9
```

Jeżeli zatwierdzono **TOKEN**, przekaż istniejący credential TEST bez historii poleceń, `.env`,
plików Maven, argumentów JVM lub czatu. Nie pobieramy sekretów ze źródeł użytkownika w preflight:

```powershell
$env:AINV_KSEF_ONESHOT_AUTH_METHOD = 'TOKEN'
$secret = Read-Host 'Istniejacy token KSeF TEST dla zatwierdzonego NIP' -AsSecureString
try {
    $env:AINV_KSEF_TOKEN = [System.Net.NetworkCredential]::new('', $secret).Password
} finally { $secret.Dispose(); Remove-Variable secret }
$env:AINV_KSEF_ONESHOT_MAX_REQUESTS = '8' # NUMBER; dla BUYER: 9
```

Jedna komenda wykonania dla **jednego** wybranego i zatwierdzonego scenariusza:

```powershell
try {
    $env:AINV_KSEF_ONESHOT_AUTH_APPROVED = 'true'
    $env:AINV_KSEF_ONESHOT_READ_APPROVED = 'true'
    mvn -B test '-Dtest=KsefInboundOneShotLiveTest' `
      '-Dsurefire.failIfNoSpecifiedTests=false' `
      '-Dainv.excludedGroups=live-sap,live-sap-write' `
      '-DargLine=-Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1' `
      '-Dcds.cds.skipVersionCheck=true'
    if ($LASTEXITCODE -ne 0) { throw 'STOP: inspect only the redacted report; do not retry' }
} finally {
    @('AINV_KSEF_ONESHOT_AUTH_APPROVED', 'AINV_KSEF_ONESHOT_READ_APPROVED',
      'AINV_KSEF_ONESHOT_ENVIRONMENT', 'AINV_KSEF_ONESHOT_APPROVED_NIP',
      'AINV_KSEF_ONESHOT_SCENARIO', 'AINV_KSEF_ONESHOT_AUTH_METHOD',
      'AINV_KSEF_ONESHOT_MAX_REQUESTS', 'AINV_KSEF_ONESHOT_NUMBER',
      'AINV_KSEF_ONESHOT_FROM', 'AINV_KSEF_ONESHOT_TO', 'AINV_KSEF_NIP', 'AINV_KSEF_TOKEN') |
      ForEach-Object { Remove-Item -LiteralPath "Env:$_" -ErrorAction SilentlyContinue }
}
```

Zgoda nie obejmuje uruchamiania `KsefTestApiLiveTest` ani `KsefOnlineE2ELiveTest`.
Pierwszy wykonuje dodatkowy challenge/listę sesji/refresh; drugi ma ścieżkę SEND.
Nie używaj `-Dtest=*LiveTest` ani znoszenia wszystkich wykluczeń. Brak flagi READ spowoduje
SKIP przez JUnit, a nie dowód sukcesu; samo `BUILD SUCCESS` bez raportu nie wystarcza.

## Raport i interpretacja

Przykład **fikcyjny**, nie wynik LIVE (hash poniżej to wyłącznie przykładowy format):

```text
KSEF_ONESHOT run=<uuid> environment=TEST scenario=BUYER auth=PASS invoiceRead=CONFIRMED http=200 requests=6 result=ReadResult[outcome=PASS, bytes=4096, sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef, metadataHash=PASS, xml=FA3_BASIC_PASS, metadataCount=1, hasMore=false, truncated=false]
KSEF_ONESHOT run=<uuid> environment=TEST scenario=BUYER auth=PASS invoiceRead=CONFIRMED http=200 requests=5 result=ReadResult[outcome=EMPTY, bytes=0, sha256=NONE, metadataHash=NOT_CHECKED, xml=NOT_CHECKED, metadataCount=0, hasMore=false, truncated=false]
KSEF_ONESHOT run=<uuid> environment=TEST scenario=NUMBER auth=PASS invoiceRead=DENIED http=403 requests=5 outcome=STOP category=AUTHORIZATION transport=HTTP_FAILURE retryNotBefore=null
```

`auth=PASS` oznacza biznesowy status 200, pojedynczy redeem i niewygasły AccessToken
z co najmniej 30 s pozostałej ważności przed READ. Bez refreshu: zbyt krótka ważność → STOP.
`invoiceRead=CONFIRMED` oznacza HTTP200 na wykonanym chronionym odczycie, także pustej liście;
nie jest pełnym audytem nadanych uprawnień. 403 oznacza odmowę, nie dowód nieistnienia faktury
ani pewność co do konkretnej przyczyny odmowy. Numer KSeF, numer P_2, NIP, nazwy i kwoty nie są logowane.

| Wynik | Kryterium |
| --- | --- |
| PASS NUMBER | auth/JWT + GET200 + 1..3145728 bajtów + SHA + podstawowy FA(3); hash metadanych NOT_CHECKED |
| PASS BUYER | auth/JWT + metadata200 + GET200 + hash metadanych + parser/cross-check/brutto PASS |
| EMPTY | metadata200 i zero rekordów; poprawny odczyt listy, brak dowodu XML |
| FAIL/STOP | niepoprawny XML/hash/dane, puste/za duże body; zero dalszych żądań |
| BLOCKED/STOP | brak zgody/credentiala, 401/403, 400+21164/404 w kontekście, 429, timeout, auth pending po 3 statusach, limit albo nieobsługiwany schemat |

401/403/404 są oddzielnymi kategoriami; MF może zwrócić 400+21164 zamiast 404.
`http` raportuje ostatnią otrzymaną odpowiedź, a nie syntetyczny status dla lokalnego błędu/timeoutu.
`retryNotBefore` jest wskazówką najwcześniejszego czasu, nie upoważnieniem do nowej próby.

Po niejednoznacznym auth/redeem/timeout: zatrzymaj proces, zachowaj wyłącznie redagowany raport,
SHA commita i liczbę żądań. Nie uruchamiaj drugiego auth/redeem, nie zmieniaj NIP/scenariusza,
nie próbuj historycznego SEND/RESUME i nie aktywuj schedulera. Właściciel ocenia błąd i zatwierdza
nowy, konkretny zakres; 429 wymaga dodatkowo respektowania terminu. Nie modyfikuj uprawnień automatycznie.

## Preflight kontraktu — 14.11.2026

- Publiczne [OpenAPI wdrożonego TEST](https://api-test.ksef.mf.gov.pl/docs/v2/openapi.json), `info.version=v2`,
  odczytano bez credentiali. Porównano używane operacje i modele z
  [kontraktem MF ce3670e](https://github.com/CIRFMF/ksef-api/blob/ce3670ea042de380039de4c5849fd6455811b292/open-api.json).
  Osiem używanych/analizowanych operacji auth/refresh/READ jest identycznych w obu plikach;
  SHA-256 pobranego publicznego OpenAPI: `57e3e0ff8dc1f1aa590dc323e5f2d3151fc06ebfdbbdb5b74d139459b7cdd8ac`.
  GET XML i metadata wymagają InvoiceRead; pageSize 10..250 i hash SHA-256/Base64.
- [Auth MF](https://github.com/CIRFMF/ksef-api/blob/ce3670ea042de380039de4c5849fd6455811b292/uwierzytelnianie.md):
  challenge → TOKEN/XAdES → status → jednorazowy redeem; refresh używa RefreshToken.
  Auth nie jest samodzielnym potwierdzeniem dostępu do faktur.
  [Samopodpisany XAdES](https://github.com/CIRFMF/ksef-api/blob/main/auth/testowe-certyfikaty-i-podpisy-xades.md) tylko TEST.
  [Token KSeF](https://github.com/CIRFMF/ksef-api/blob/main/tokeny-ksef.md) ma przypisany zestaw uprawnień.
- [Limity MF](https://github.com/CIRFMF/ksef-api/blob/ce3670ea042de380039de4c5849fd6455811b292/limity/limity-api.md):
  metadane 8/s,16/min,20/h; GET 8/s,16/min,64/h w kontrakcie, TEST według przewodnika ma limity
  10× względem PROD. Harness nie korzysta z mnożnika, limituje cały przebieg do 9 prób i zatrzymuje się przy 429.
- [SDK 3.0.29](https://github.com/CIRFMF/ksef-client-java/blob/v3.0.29/RELEASE_NOTES.md): wydanie dla API 2.8.0,
  commit `3c3a899541074aa4bd944636b25659aef7d23da7`. Zweryfikowano źródła przypiętego JAR i metody
  getInvoice/queryInvoiceMetadata/auth oraz buforowanie `byte[]`; bez aktualizacji zależności.
  Nie wykryto niezgodności używanego podzbioru. Pełna zgodność runtime auth/InvoiceRead nadal NOT VERIFIED.
- [Właściwości transportu JDK25](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/module-summary.html)
  i lokalne źródła `MultiExchange`: retrylimit=1 oznacza jedną próbę, nie jedną dodatkową próbę.

Istniejący `KsefTestApiLiveTest` rzeczywiście realizuje challenge/auth/redeem, sprawdzenie terminu tokena,
autoryzowany `getActiveSessions` i refresh. **Nie pobiera faktury i nie dowodzi InvoiceRead.**
Nie uruchomiono go w tej sesji. Historyczne LIVE PASS nie przenosi się na obecnego principal.

## Następne działanie właściciela

Po review PR i CI dokładnego HEAD zatwierdzić jeden konkretny przebieg, np.:

> Zatwierdzam jednorazowy KSEF-IN-04 dla SHA [HEAD], wyłącznie KSeF TEST, NIP 1234563218,
> scenariusz NUMBER, numer 1234563218-20261029-7DC8A1C00000-CD, auth XADES TEST.
> Dozwolone: challenge×1, XAdES×1, auth-status≤3, redeem×1, GET XML×1; łącznie ≤7 żądań.

To przykład oczekiwanej decyzji, **nie istniejąca zgoda**. Dla TOKEN trzeba dopuścić także
certyfikaty MF i wskazać istniejący credential; dla BUYER podać konkretne daty i metadata×1/GET≤1.
