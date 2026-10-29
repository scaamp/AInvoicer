---
title: "SAP — dostęp zapisu communication user, polityka odczytu DCL i plan integralności XML"
date: 2026-10-29
type: guide
status: propozycja
tags: [ainvoicer, abap, rap, authorization, dcl, communication-user, integrity]
---

# Dostęp zapisu, polityka odczytu DCL i integralność dokumentu

[Project Home](../README.md) · [STATUS](../STATUS.md) · [Dziennik CAP-04 Hardening](../journal/2026-10-29-cap-04-hardening.md) · [ADR-0002](../decisions/ADR-0002-sap-ksef-result-write-contract.md) · [ADR-0003](../decisions/ADR-0003-cap-sap-result-writeback.md)

Ten dokument jest **propozycją i instrukcją dla właściciela SAP**. Nie wykonano opisanych nadań ani zmian DCL. Historyczna sesja hardening miała `ECONNREFUSED`. W kontynuacji Fix 2 MCP odczytał część metadanych i aktywne CDS/BDEF, ale automatyczna kontrola uprawnień zablokowała include, DCL i runtime OData; IAM, scenariusz/arrangement i efektywne prawa principala nadal **NOT VERIFIED**. Aktualna procedura CAP-04D jest w sekcji 5.

## 1. Minimalna konfiguracja principala komunikacyjnego do zapisu (ACTVT 16)

Cel: CAP wywołuje wyłącznie `ApplyKsefResult`, bez zwykłego CRUD, wyłącznie dla uzgodnionego NIP i CompanyCode.

1. **Scenariusz komunikacyjny** (własny, np. `Z_AINV_KSF_WRITE`) z jedną usługą inbound OData V4 opartą o binding `ZAPI_AINV_INV_H_O4` i autoryzacją **Z_AINV_KSF**. W istniejącym stanie projektu otwarty/zablokowany scenariusz komunikacyjny pochodzi od innej zmiany i nie był tu modyfikowany.
2. **Wartości autoryzacji w scenariuszu** (nie w roli biznesowej): `ACTVT = 16`; `ZAINV_NIP = <jeden NIP podatnika>`; `ZAINV_BUK = <jedna spółka>`. **Bez** `01`, `02`, `06`, `03`, bez gwiazdek.
3. **Communication arrangement** dla tego scenariusza z użytkownikiem komunikacyjnym (osobnym od użytkownika odczytu CAP-03). Sekrety wyłącznie w BTP Destination / zarządzanym sekrecie, nie w repo i nie w logach.
4. **Destination `AINV_H01` dla zapisu** — osobna od destination odczytu, jeśli odczyt nie ma pozostać na tym samym principalu. CAP nie zawiera ręcznego Basic Auth; mechanizm to Destination Service.
5. Włączenie transportu w CAP: `ainv.sap-write.enabled=true` dopiero po pozytywnym wyniku testów negatywnych z pkt 2.

Zakres odpowiedzialności: nadanie praw i arrangement wykonuje właściciel SAP. Agent ich nie zmienia.

## 2. Testy rzeczywistym principalem (po nadaniu)

Dane testowe: **dedykowana syntetyczna faktura OUTBOUND bez numeru KSeF** (UUID i numer faktury podane jawnie). Realizuje je `SapKsefResultWriteLiveWriteTest` (`@Tag("live-sap-write")`, wymaga `AINV_H01_LIVE_WRITE=true`, UUID, numeru faktury i `AINV_H01_LIVE_WRITE_CONSENT=APPLY_KSEF_RESULT:<UUID>`; domyślnie wyłączony i **nie uruchomiony** w hardeningu).

| # | Przypadek | Oczekiwanie | Test |
| --- | --- | --- | --- |
| P1 | Akcja z właściwym NIP/CompanyCode, wersją z odczytu | `APPLIED`; niezależny GET pokazuje 4 pola | live |
| P2 | Identyczne ponowienie (ten sam request ID) | `ALREADY_APPLIED`, ETag bez zmian | live |
| P3 | Nowy request ID, ten sam numer | `ALREADY_APPLIED`, pierwotny request ID | live |
| P4 | Ten sam request ID, inny numer | `IDEMPOTENCY_CONFLICT` | live |
| P5 | Nowy request ID, inny numer | `RESULT_CONFLICT`, brak nadpisania | live |
| P6 | Przestarzała wersja (dziewicza faktura STALE) | `VERSION_CONFLICT`, brak zapisu | live (opcjonalna faktura) |
| P7 | Dwa równoległe wywołania (dziewicza faktura RACE) | dokładnie jeden `APPLIED` | live (opcjonalna faktura) |
| N1 | Obcy NIP w parametrach | odmowa (4xx), brak zapisu | live |
| N2 | Obca spółka w parametrach | odmowa (4xx), brak zapisu | live |
| N3 | Faktura INBOUND albo innego podatnika | odmowa; wymaga osobnej faktury testowej | **nie ma jeszcze testu** |
| N4 | Zwykły UPDATE/DELETE/CBA tym principalem | odmowa (`AINV_H01_LIVE_WRITE_CRUD_DENIAL=true`; UPDATE) | live; DELETE i CBA — brak |
| N5 | Principal z ACTVT 16 próbuje odczytu spoza zakresu | zależy od polityki DCL z pkt 3 | po decyzji |

Jeżeli któregoś z negatywnych testów nie da się wykonać, wynik pozostaje `NOT VERIFIED` — nie `PASS`.

## 3. Polityka odczytu DCL — propozycja do akceptacji (implementacja wstrzymana)

**Stan.** Root `ZR_AINV_INV_H` ma `grant select ... where TRUE`; projekcja i dzieci dziedziczą ten warunek. Każdy principal z dostępem do usługi OData widzi wszystkie faktury, w tym principal komunikacyjny CAP-03 (odczyt 4/9/6). Obiekt `Z_AINV_KSF` zawiera aktywność `03`, ale nigdzie nie jest używana ani nadawana.

**Dlaczego nie zmieniono.** Zmiana na `ACTVT 03 + NIP + CompanyCode` natychmiast odcięłaby obecny odczyt CAP-03 (principal nie ma `03`) i nie wiadomo, którzy użytkownicy biznesowi korzystają z ekranów UI5/Fiori. Model uprawnień zapisu nie określa jednoznacznie zakresów odczytu, więc wymagana jest decyzja.

**Proponowana polityka (wariant A, rekomendowany).**

1. Odczyt wymaga `Z_AINV_KSF` z `ACTVT = 03` oraz `ZAINV_NIP` i `ZAINV_BUK` dopasowanymi do wiersza.
2. NIP wiersza zależy od kierunku: `OUTBOUND → SellerNip`, `INBOUND → BuyerNip`. W DCL wyrażone dwoma alternatywnymi warunkami `Direction = 'OUTBOUND' AND (SellerNip, CompanyCode) = aspect pfcg_auth(...)` oraz analogicznie dla `INBOUND` (składnia do sprawdzenia w H01 przed aktywacją).
3. Dzieci (`_Items`, `_References`) dziedziczą warunek rodzica — istniejący `INHERITING CONDITIONS` pozostaje; sprawdzić bezpośredni odczyt dzieci, odczyt po kluczu i `$expand`.
4. Principal komunikacyjny **odczytu** dla CAP: ACTVT `03` i zakres obejmujący NIP/spółki obsługiwane przez CAP (jawna lista, bez gwiazdek). Principal **zapisu** (ACTVT 16) nie dostaje `03`, chyba że CAP ma czytać tym samym kontem.
5. Użytkownicy biznesowi: role IAM z `03` i właściwym zakresem; do ustalenia, którzy użytkownicy dziś korzystają z usługi.

**Kolejność wdrożenia (po akceptacji).** Najpierw nadanie `03` istniejącemu principalowi odczytu CAP w jego scenariuszu → dopiero potem aktywacja DCL → test: odczyt CAP-03 nadal 4/9/6, principal bez `03` widzi 0 wierszy, obcy NIP niewidoczny po kluczu i przez `$expand`. Rollback: przywrócenie `where TRUE`.

**Decyzje wymagane od właściciela:** (a) czy wariant A jest właściwy; (b) lista NIP/CompanyCode dla principala odczytu CAP; (c) lista użytkowników biznesowych z dostępem; (d) czy principal zapisu ma osobne konto od odczytu.

## 4. Integralność wysłanego dokumentu — kontrakt i plan

**Wdrożone w CAP (bez KSeF, bez generatora XML):**

- `KsefInvoiceSnapshots` — niezmienny (tylko INSERT) rekord: `invoiceUUID`, NIP, kierunek, środowisko, spółka, `schemaVersion` (`FA(3)`), `sapHeaderVersion` (100 ns), `itemsFingerprint`, `xmlSha256` (hex SHA-256 dokładnych bajtów XML), `xmlByteLength`. Unikalność `(invoiceUUID, environment, taxpayerNip, xmlSha256)` czyni rejestrację idempotentną.
- `KsefOperations.snapshot` i `confirmedXmlSha256`: operacja musi wskazywać migawkę, a skrót potwierdzony przy akceptacji musi być identyczny ze skrótem migawki — inaczej `enqueue` odmawia (`CONFIRMED_DOCUMENT_HASH_MISMATCH`). Spółka i oczekiwana wersja dla SAP pochodzą **wyłącznie** z migawki, nie z argumentów wywołującego.
- `KsefInvoiceSnapshotRegistry.detectDrift` rozróżnia zmianę nagłówka, pozycji lub obu względem chwili przygotowania XML.

**Ważny wniosek dla SAP (NOT VERIFIED w runtime).** BDEF root ma `etag master LocalLastChangedAt`, a pozycje i referencje mają **własny** `etag master`. Zmiana pozycji nie musi więc zmieniać `LocalLastChangedAt` nagłówka, więc CAS `ExpectedLocalLastChangedAt` chroni treść nagłówka, ale nie dowodzi niezmienności pozycji. Rekomendacja do osobnej zmiany ABAP (wymaga aktywacji, ATC, EML): `total etag` na polu zmienianym przy każdej zmianie kompozycji i użycie go jako wersji CAS, albo świadome pozostanie przy kontroli drift po stronie CAP tuż przed wysyłką. Obecnie CAP wykrywa drift pozycji tylko wtedy, gdy przyszły producent wywoła `detectDrift` na świeżym odczycie przed wysyłką — **nie jest to jeszcze podłączone**.

**Plan integracji z KSeF SDK (nie wykonany):** generator FA(3) zwraca bajty → `register(...)` w tej samej transakcji, co zapis zadania wysyłki → wysyłka dokładnie tych bajtów → przy statusie końcowym zapis `confirmedXmlSha256` z odpowiedzi KSeF → `enqueue`. Format skrótu w odpowiedzi KSeF (Base64 vs hex) trzeba zweryfikować w SDK/OpenAPI i znormalizować przed porównaniem — **NOT VERIFIED**.

**Unikalność numeru KSeF.** Prosty UNIQUE na `KsefNumber` w całej aplikacji byłby błędny: ten sam numer legalnie występuje jako dokument sprzedawcy (OUTBOUND) i nabywcy (INBOUND), także w jednym CAP/SAP przy rozliczeniach wewnątrzgrupowych; numery korekt są odrębne, ale odwołują się do pierwotnego; różne systemy SAP mogą odwoływać się do tego samego numeru. Wdrożono jedynie unikalność `(environment, ksefNumber)` w `SapResultWrites`, bo ta tabela dotyczy wyłącznie zapisu wyniku w fakturze sprzedażowej SAP — numer KSeF może trafić do jednej takiej faktury na środowisko. Dla INBOUND i korekt unikalność należy zaprojektować osobno (np. `(environment, taxpayerNip, ksefNumber, sapSystem)`).

## 5. CAP-04D — gotowość po Fix 2

**IMPLEMENTED / NOT LIVE TESTED.** Wszystkie poniższe polecenia LIVE są planem po osobnej zgodzie, nie historią wykonania. Domyślne `enabled=false` oraz wykluczenie tagów `live-sap,live-sap-write` pozostają. KSeF nie jest wywoływany. Test LIVE sprawdza bezpośrednio CAP RemoteService → RAP Action, nie dowodzi istnienia producenta potwierdzonych ACCEPTED ani pełnego E2E KSeF.

### Odmowa CRUD

`SapCrudDenialAssertions` wymaga rzeczywistego `ODataResponseException` z HTTP403 i strukturalnego `ODataServiceErrorException` z oczekiwanym kodem SAP. CAP 5.2 opakowuje zdalne błędy jako lokalny 500 (`50012005`); helper sprawdza cause oraz strukturalne args, nie tekst. Brak odpowiedzi HTTP, 400/404/500, 2xx i obcy kod (np. CSRF) kończą się FAIL. Kod nie jest zgadywany: właściciel ustala go z autoryzacyjnej odmowy rzeczywistego RAP, podaje przez `AINV_H01_LIVE_WRITE_CRUD_DENIAL_CODE`. Brak kodu blokuje próbę UPDATE jeszcze przed wywołaniem.

Test weryfikuje UUID i numer faktury odczytanej z tej samej usługi, wykonuje UPDATE tego samego `SellerName`, a w `finally` robi niezależny GET i porównuje cały odczytany nagłówek wraz z wersją. Nawet udany UPDATE bez zmiany wartości musi dać FAIL. DELETE nie jest używany.

### Timeouty i lease

CAP 5.2.0 → `RemoteServiceHttpClientProvider` → `HttpClientAccessor` → Apache HttpClient **4.x** (SDK 5.33.0), mimo obecności obu wersji transportu w zależnościach. Potwierdzono sygnaturami i bytecode lokalnych JAR-ów. Domyślne timeouty SDK wynosiły po 120 s (connect, socket, pool), a lease też 120 s. Nowa fabryka zachowuje konfigurację SDK/destination i dla `AINV_H01` nakłada:

| Ustawienie CAP | Wartość | Znaczenie |
| --- | --- | --- |
| `ainv.sap-write.http.connect-timeout` | PT5S | nawiązanie połączenia |
| `ainv.sap-write.http.pool-timeout` | PT5S | oczekiwanie na połączenie z puli |
| `ainv.sap-write.http.read-timeout` | PT20S | bezczynność socketu/odczytu, także socket config |
| automatyczne retry I/O Apache | wyłączone | retry biznesowe pozostaje w outbox |
| `ainv.sap-write.lease` | PT2M | ważność przejęcia |
| `ainv.sap-write.renew-interval` | PT20S | odnowienie podczas całego `adapter.apply` |

Nominalne HEAD CSRF + POST mieszczą się z marginesem w lease, ale suma timeoutów **nie stanowi twardego limitu całej operacji**. DNS, uzyskanie destination/auth, przekierowania i strumień danych mogą trwać dłużej. SDK pobiera CSRF przez HEAD przed żądaniem modyfikującym; inspekcja `ODataRequestGeneric` nie wykazała ogólnej pętli retry biznesowego. Przy nieudanym pobraniu CSRF SDK może jeszcze wykonać właściwe żądanie. Testy lokalne potwierdzają jedną próbę PATCH po zerwaniu połączenia i jedną próbę POST po read timeout. To nie jest dowód konfiguracji BTP Destination Service w H01/CF — brak dostępnej `AINV_H01` w środowisku sesji.

Odnowienie przez scheduler JDK jest krótką transakcją CAP, bez blokady na czas HTTP. Zachowuje tenant workera, sprawdza stan, token i nadal ważny lease. Claim i resume porównują również wcześniej odczytane `leaseUntil`. `renew-interval` musi być dodatni i nie większy niż 1/3 lease; błędna konfiguracja blokuje startup.

Nie gwarantujemy exactly-once HTTP: awaria DB, długi stop JVM albo utrata sieci mogą uniemożliwić odnowienie. Po wygaśnięciu lease można ponowić tylko ten sam WriteRequestId/payload; idempotencja SAP i token finalizacji są ostatnią ochroną. Późna odpowiedź starego workera nie nadpisuje nowszego wyniku. HANA, CF i wiele tenantów pozostają nieprzetestowane LIVE.

Nowa kolumna `revision` wymaga migracji istniejącej trwałej bazy przed użyciem kodu (backfill 0, NOT NULL, BIGINT). Świeże testowe H2 tworzy ją z CDS. Nie wykonano migracji użytkowej H2/HANA ani automatycznego kasowania bazy.

### READ i WRITE — rekomendacja do decyzji właściciela

Rekomendowane osobne konta: READ z ACTVT03 i jawną listą NIP/spółek; WRITE wyłącznie ACTVT16 i jednym uzgodnionym zakresem, bez 01/02/06. READ także nie potrzebuje CRUD. Nie aktywować DCL03, dopóki READ i użytkownicy UI5/Fiori nie mają właściwych nadań. Wdrożenie: nadać prawa → sprawdzić dotychczasowy CAP-03 i UI5 → zmienić DCL w osobnym zleceniu → powtórzyć pozytywne/negatywne odczyty, klucz i expand → potwierdzić brak regresji. Plan cofnięcia DCL uzgodnić przed aktywacją.

**Aktualny kod ma jeden RemoteService `AinvoicerH01` i jedną destination `AINV_H01` dla obu adapterów.** Samo utworzenie drugiego konta nie rozdzieli tras. Docelowy podział wymaga osobnej konfiguracji dwóch RemoteServices/destinations i kwalifikatorów oraz objęcia destination WRITE właściwą fabryką timeoutów. Nie wykonano tego bez decyzji. Do czasu rozdzielenia nie zastępować po cichu credentiali działającego procesu CAP-03; dedykowany proces testu CAP-04D może dostać własną env `AINV_H01` tylko na czas jawnie zatwierdzonej próby. Po przyszłym DCL03 GET testu trzeba skierować na READ albo świadomie przyznać WRITE również 03 w tym samym zakresie.

Właściciel dostarcza: zatwierdzenie wariantu polityki, system/klienta, tożsamości kont i sposób auth, nazwy i stan scenariusza/arrangement, zakresy NIP/spółek, listę konsumentów UI5/Fiori i ich ról, rzeczywisty kod odmowy autoryzacyjnej RAP oraz konkretne UUID/numer faktury testowej. Poświadczenia wyłącznie przez zarządzane destination/sekrety.

### Kolejność pierwszego LIVE WRITE — po osobnym zleceniu

1. Odblokować autoryzowany odczyt include/DCL/runtime przez MCP lub ADT. Porównać active/inactive implementacji akcji, potwierdzić aktywację `Z_AINV_KSF`, DCL root/dzieci i arrangement. Dodać i uruchomić [test CBA poza zakresem](../patches/cap-04-cba-scope-test.md) z rollback; nie aktywować cudzych zmian. Bez tych wyników CAP-04D pozostaje BLOCKED.
2. Właściciel nadaje testowemu principalowi 16 dla jednego NIP/spółki, potwierdza brak CRUD i dostarcza env/BTP Destination `AINV_H01` wskazującą zweryfikowaną usługę. Odrębnie sprawdzić READ dotychczasowym kontem: `mvn -pl srv verify -Dcds.cds.skipVersionCheck=true -Dainv.excludedGroups=live-sap-write -Dtest=SapInvoiceRemoteAdapterLiveTest` z `AINV_H01_LIVE=true` i destination READ. Oczekiwanie: 1 PASS, 4/9/6; SKIP nie jest PASS. Następnie uruchamiać WRITE w osobnym procesie z właściwym kontem.
3. Wskazać dedykowaną dziewiczą syntetyczną fakturę OUTBOUND i jawnie zaakceptować jej UUID/numer, NIP/spółkę oraz zakres testów. Nie używać istniejącej faktury biznesowej. Dodatkowe STALE/RACE wymagają własnych UUID i zgód; pierwszy test nie musi ich uruchamiać.
4. Ustawić poniższe flagi w procesie testowym (placeholdery zastępuje właściciel). Najpierw uruchomić tylko negatywny UPDATE. Kod odmowy ma pochodzić ze sprawdzonej autoryzacji RAP, nie z błędu CSRF:

```powershell
$env:AINV_H01_LIVE_WRITE = 'true'
$env:AINV_H01_LIVE_WRITE_INVOICE_UUID = '<UUID>'
$env:AINV_H01_LIVE_WRITE_INVOICE_NUMBER = '<numer faktury>'
$env:AINV_H01_LIVE_WRITE_CONSENT = 'APPLY_KSEF_RESULT:<UUID>'
$env:AINV_H01_LIVE_WRITE_CRUD_DENIAL = 'true'
$env:AINV_H01_LIVE_WRITE_CRUD_DENIAL_CODE = '<zweryfikowany kod autoryzacji SAP>'
mvn -pl srv verify '-Dcds.cds.skipVersionCheck=true' '-Dainv.excludedGroups=live-sap' '-Dtest=SapKsefResultWriteLiveWriteTest#liveActionOnlyPrincipalCannotUseStandardCrud'
```

5. Dopiero po PASS odmowy, niezależnego GET i potwierdzeniu nadal dziewiczej faktury uruchomić pozytywny test tej konkretnej faktury:

```powershell
mvn -pl srv verify '-Dcds.cds.skipVersionCheck=true' '-Dainv.excludedGroups=live-sap' '-Dtest=SapKsefResultWriteLiveWriteTest#livePersistedResultEtagIdempotencyConflictsAndRace'
```

Oczekiwanie: APPLIED, niezależny GET czterech pól, ALREADY_APPLIED dla identycznego ponowienia i nowego request ID z tym samym wynikiem, ETag bez zmiany dla no-op, jawne konflikty bez nadpisania. Ten konkretny test nie wykonuje jeszcze rzeczywistego wyścigu; służy do niego osobna metoda RACE. Po utracie odpowiedzi **nie uruchamiać ponownie testu z nowym request ID/numerem**: zachować referencję i zrobić GET/rekoncyliację. Po zakończeniu usunąć flagi LIVE z procesu i wyłączyć transport w uruchamianej aplikacji; zapisać oczyszczone wyniki. Zmiany pozostają w syntetycznej fakturze — brak automatycznego rollback HTTP.
