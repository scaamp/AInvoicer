---
title: "KSeF Java SDK — integracja z CAP"
date: 2026-10-29
type: guide
status: aktywny
tags: [ainvoicer, ksef, cap-java, java, sdk]
---

# KSeF Java SDK w CAP

**CAP-05 (2026-10-29):** [ADR-0005](../decisions/ADR-0005-rap-business-status-cap-integration-status.md) zastępuje wcześniejszy brak automatycznego writebacku. `requestSend(invoiceUUID)` pobiera RAP i sprawdza NIP/CompanyCode, rezerwuje `(InvoiceUUID, TEST)` w DB i atomowo zapisuje operację, dokładne bajty FA(3), snapshot `SAP_RAP`, zlecenie i outbox. Publiczny CREATE SEND_INVOICE jest zablokowany. [Audyt i wyniki](../audits/2026-10-29-cap-05-implementation-review.md).

`KsefAcceptedWriteback` działa dopiero po commit ACCEPTED, w nowym ChangeSet; zlecenie i SapResultOutbox powstają razem. Błąd enqueue nie cofa przyjęcia ani UPO. Sweeper przy starcie i okresowo odtwarza brakujące zlecenia (stabilny ID operacji/WriteRequestId), z rotacją niepoprawnych rekordów, aby nie blokowały kolejnych partii. Fixture `UNVERIFIED` nie trafia do SAP.

| Domena | Stan i źródło |
| --- | --- |
| Biznesowy wynik SAP | RAP: KsefNumber/Environment/RecordedAt/WriteRequestId; InvoiceStatus niezależny |
| Integracja | CAP KsefOperations + KsefSubmissions; ACCEPTED zachowuje dowód przy problemie SAP/close/UPO |
| SAP writeback | SapResultWrites: PENDING/PROCESSING/SUCCEEDED/RETRY_EXHAUSTED/RECONCILIATION_REQUIRED |

Worker sprawdza numer KSeF (format i NIP), hash i referencję również przy recovery. Kod faktury **440** i nieznane statusy (np. 500) oznaczają rekoncyliację; nie są podstawą retry SEND. Kontrakt kodów potwierdzono w [wdrożonym TEST OpenAPI](https://api-test.ksef.mf.gov.pl/docs/v2/openapi.json). VERSION_CONFLICT nie pozwala na automatyczne pominięcie CAS ani zmianę oczekiwanej wersji; wewnętrzne `SapKsefResultWriteRecovery.diagnose` wykonuje tylko odczyt. Transport H01 WRITE nadal OFF; ta sesja nie wykonała SEND KSeF ani HTTP WRITE SAP.

[Runbook TEST](KSEF_TEST_RUNBOOK.md) · [Dziennik Agenta A](../journal/2026-10-29-agent-a-ksef-sdk.md)
· [ADR-0004](../decisions/ADR-0004-ksef-test-sdk-workflow.md)

## Wersje i źródła

Zweryfikowano 2026-10-29:

| Element | Wersja / zakres |
| --- | --- |
| Oficjalny SDK MF | `pl.akmf.ksef-sdk:ksef-client:3.0.29`, tag `v3.0.29`, commit `3c3a899541074aa4bd944636b25659aef7d23da7` |
| Wdrożone TEST OpenAPI | API 2.9.0, build `2.9.0-te-20261028.3+5735c2b2cfd958e2d32545be121f90b466ac5b41` |
| CAP / Spring Boot / JDK aplikacji | 5.2.0 / 4.1.1 / Microsoft 25.0.4.1 |
| Build SDK ze źródeł | Gradle wrapper 8.8, Microsoft JDK 21.0.12.101; target biblioteki Java 11 |
| Maven | 3.9.16 |

Źródła: [SDK](https://github.com/CIRFMF/ksef-client-java/tree/v3.0.29),
[auth MF](https://github.com/CIRFMF/ksef-api/blob/main/uwierzytelnianie.md),
[TEST OpenAPI](https://api-test.ksef.mf.gov.pl/docs/v2/openapi.json),
[CAP Event Queues](https://cap.cloud.sap/docs/java/event-queues).

SDK nie wymaga Spring Boot. Aplikacja demonstracyjna MF pozostaje poza projektem.
Budujemy tylko moduł `ksef-client` do lokalnego Maven; alternatywą CI jest GitHub
Packages z zarządzanym `read:packages`. Nie umieszczamy PAT w POM. Wersja SDK jest
przypięta w parent POM. Skrypt `cap-java/tools/Install-KsefSdk.ps1` sprawdza commit
i ustawia tag dla mechanizmu wersjonowania Gradle MF.

## Klasy

| Klasa | Odpowiedzialność |
| --- | --- |
| `KsefSdkConfiguration` | TEST origin wpisany jawnie, timeout connect 5 s / request 20 s, brak redirectów, osobny Jackson 2 dla DTO MF; lazy kryptografia |
| `KsefAuthentication` | NIP z zaufanej konfiguracji, challenge, KSeF token albo jawny XAdES TEST, status, pojedynczy redeem, refresh |
| `KsefOnlineSession` | SDK: FA(3), szyfrowanie, open/send/status/close/UPO, odczytowa rekoncyliacja po hashu |
| `KsefFailure` | Bezpieczny kod i klasyfikacja: niejednoznaczny efekt, retryable odczyt, Retry-After; bez cause/body SDK |
| `KsefSubmissionWorker` | Atomowe enqueue, persistent outbox, claim, checkpointy, historia, potwierdzenie ACCEPTED, UPO |
| `KsefFa3Documents` | Generator B + XSD i ponowna kontrola 12 oryginalnych dużych limitów maxOccurs; walidacja przed enqueue i HTTP |
| `KsefInvoicePreparation` | Zaufany producent: snapshot SAP + PartyData → dokładne bajty B → snapshot CAP → zlecenie/outbox |
| `KsefDocumentStore` | Kontrakt magazynu dokładnych bajtów XML/UPO |
| `LocalTestDocumentStore` | Jawny lokalny katalog eksperymentu, nazwy SHA-256, atomowy zapis i kontrola integralności; odmowa na CF |

Lazy kryptografia ma znaczenie: konstruktor `DefaultCryptographyService` pobiera
certyfikaty MF. Start aplikacji/testy offline nie powinny wykonywać sieci.
SDK wykorzystuje Jackson 2; Boot 4 posiada odrębny mapper. Zależności JSR-310 i JAXB
są jawne, bo publikacja Gradle `implementation` oznacza je w POM jako runtime.

## Uwierzytelnienie i tokeny

`ainv.ksef.nip` wiąże credential z kontekstem. Inny NIP jest odrzucany przed siecią.
Token KSeF pochodzi z `AINV_KSEF_TOKEN`; alternatywą jest `test-xades=true`. TEST
dopuszcza samopodpisaną pieczęć `VATPL-<nip>` generowaną przez SDK. Klucz jest wyłącznie
w pamięci. Nie jest to mechanizm PROD. Sam NIP przesłany przez klienta nie nadaje praw.

Auth: `POST /auth/challenge` → `POST /auth/ksef-token` albo
`POST /auth/xades-signature` → `GET /auth/{ref}` → `POST /auth/token/redeem`.
Status 100 oznacza oczekiwanie, 200 pozwala na jeden redeem, inny status kończy etap.
Polling ma limit prób/czasu i opóźnienie z jitter, działa poza transakcją biznesową.
Timeout redeem wymaga uzgodnienia; ta sama operacja nie jest ponawiana.

`Tokens` jest obiektem jednego wykonania; `toString()` redaguje zawartość. JWT,
refreshToken i AES nie trafiają do DB, OData ani raportów. `refreshIfNeeded` odświeża
token przy mniej niż minucie ważności. Restart workera wykonuje nowy auth dla
odczytów istniejącej sesji, zamiast próbować odzyskać utracony JWT.

## Sesja i dokładne bajty

Form code: `systemCode="FA (3)"`, `schemaVersion="1-0E"`, `value="FA"`.
`DefaultCryptographyService.getEncryptionData()` generuje osobny AES/IV dla sesji
i chroni AES kluczem MF. SDK wykonuje RSA-OAEP SHA-256 i AES-256-CBC z paddingiem.
Nie ma wspólnego pola kontrolera z materiałem szyfrowania.

`send` otrzymuje `byte[]`, liczy metadane i szyfruje bez dekodowania/ponownej
serializacji XML. Hash API jest Base64; migawka przechowuje hex. Worker porównuje
te same 32 bajty SHA-256. Sukces wymaga statusu faktury 200, referencji, numeru KSeF
i zgodnego `invoiceHash`. HTTP 202 oznacza wyłącznie rozpoczęcie przetwarzania.
440 (duplikat) nie jest automatycznie uznawany za sukces.

## Persystencja i transakcje — analogia RAP

CDS `KsefSubmissions` jest odpowiednikiem tabeli trwałego zlecenia, a Java handler
odpowiada roli behavior implementation. `enqueue` zapisuje zlecenie i wiadomość
`KsefSubmissionOutbox` w jednym ChangeSet (analogia LUW). Commit pozwala collectorowi
rozpocząć obsługę. Java nie przenosi jednak automatycznie semantyki blokad RAP.

Worker używa osobnych `RequestContext` i ChangeSet dla claimu oraz każdego etapu.
Wiersz zlecenia ma pięciominutową dzierżawę i token claimu. Aktualizacje wymagają
ważnego tokenu; nieaktualny worker nie nadpisuje nowego właściciela. HTTP jest
pomiędzy tymi transakcjami. Zewnętrzna transakcja dostawy outboxa może być otwarta,
ale nie trzyma blokad wierszy biznesowych — sprawdza to test z drugą sesją H2.

Przebieg: `PREPARED → AUTHENTICATING → OPENING → OPENED → SENDING → POLLING →
ACCEPTED → CLOSING → WAITING_UPO → COMPLETED`. Odrzucenie faktury prowadzi przez
`INVOICE_REJECTED` do zamknięcia i `REJECTED`. Gotowość UPO jest osobnym warunkiem
od statusu sesji 200. Potwierdzenie ACCEPTED zostaje w operacji również po timeout close.

Restart podczas efektu: `RECONCILIATION_REQUIRED`. `reconcile(id)` wykonuje wyłącznie
odczyty istniejącej sesji. Zgodny hash może dostarczyć utraconą referencję; brak
dopasowania zatrzymuje proces. Nie otwieramy drugiej sesji ani nie wysyłamy ponownie.
Znacznik `closeAttempted` chroni także niejednoznaczne zamknięcie. Dane historyczne
zawierają tylko etapy i bezpieczne kody.

Bezpieczne odczyty mają limit, backoff, jitter i trwałe `retryNotBefore`.
`Retry-After` jest odczytywany z nagłówka jako liczba sekund lub data HTTP.
`READ_RETRY_EXHAUSTED` wymaga obsługi operatora; nie uruchamia nowej wysyłki.
CAP może pozostawić wiadomość po wyczerpaniu limitu dostarczeń; stan zlecenia i
historia są podstawą diagnostyki, nie samo istnienie wiadomości w kolejce.

## Storage i granice wdrożenia

Lokalny katalog jest jawnie wybranym magazynem syntetycznego testu. H2 musi być
plikowa przy sprawdzaniu restartu. Na Cloud Foundry wymagane są trwała baza oraz
zarządzany magazyn dokumentów; lokalny adapter odmawia konfiguracji na CF.
Zmiana schematu istniejącej bazy wymaga migracji, nie automatycznego odtwarzania tabel.
Domyślny profil development nadal używa H2 in-memory.

API Java enqueue jest wewnętrzne, przeznaczone dla zaufanego producenta po walidacji
FA(3). Generator Agenta B pozostaje właścicielem mapowania i XSD. Nie ma publicznego endpointu auth/JWT. CAP-05 dodaje akcję zlecenia requestSend i automatyczne wewnętrzne zlecenie writebacku po commit ACCEPTED; transport H01 WRITE pozostaje OFF. OUTBOUND nie aktualizuje KsefInvoices.businessStatus — dowód integracji jest w KsefOperations, wynik biznesowy w RAP.

## Integracja Agenta B

Commit B `7075c01` włączono przez cherry-pick jako `657c285`; kod, testy i zasoby
`ksef/fa3` pozostawiono bez zmian. `KsefInvoicePreparation.enqueue(operationId, sap,
partyData)` łączy mapper/generator B z snapshotem i outboxem. Wywołujący pobiera
autoryzowaną migawkę przez istniejący Remote Adapter przed transakcją, a następnie
w ChangeSet wywołuje producenta. Ten kod nie pobiera SAP ani nie wykonuje KSeF HTTP.

`PartyData` wymaga rzeczywistych adresów i świadomych flag JST/GV. Mapper nie zastępuje
daty dostawy przez PostingDate. Błędy NIP/sum i brak danych zatrzymują przygotowanie.
NIP-y istniejącego seeda H01 mają błędne sumy kontrolne według walidatora B; nie
zmieniano ich ani nie zgadywano danych stron w tej sesji.

Walidator B kompiluje schemat po rozluźnieniu 12 dużych maxOccurs w pamięci
(obejście JDK/Xerces). Granica wysyłki `KsefFa3Documents` przywraca wszystkie te
oryginalne limity strumieniowym licznikiem elementów na rodzica, bez zmiany plików B.
Test porównuje kontrolę każdego limitu z oryginalnym zasobem XSD. Dokument B został
też niezależnie zwalidowany pełnym, nierozluźnionym XSD w lxml. Worker po odczycie
z magazynu ponownie waliduje te same bajty przed HTTP.

Test A+B: prawdziwy generator/XSD, CAP/H2, snapshot i worker, mock transportu;
bajty z generatora, storage i argument send są identyczne. Odrębny test HTTP SDK
potwierdza AES/SHA-256 i odtworzenie dokładnych bajtów po odszyfrowaniu.
LIVE faktura powstała wcześniej z oficjalnego fixture. Nie wysyłano drugiej faktury
z generatora B; jego przyjęcie LIVE pozostaje niepotwierdzone.

## Dowody

Rzeczywisty auth TEST: challenge, XAdES, biznesowe 200, redeem, autoryzowany READ
aktywnych sesji i refresh — PASS 2026-10-29. Tokenów nie zapisano.
Szczegółowe wyniki buildów i wysyłki: [dziennik](../journal/2026-10-29-agent-a-ksef-sdk.md).
Testy lokalne obejmują CAP/H2/outbox, timeouty, restart, kryptografię i HTTP na
lokalnym serwerze. Nie są dowodem wdrożenia CF/HANA ani SAP H01 WRITE.
