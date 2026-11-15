---
title: "E2E-01A — SAP RAP → FA(3), preflight bez SEND"
date: 2026-11-15
type: guide
status: offline-pass-live-blocked
tags: [ainvoicer, e2e, rap, cap-java, ksef, fa3, preflight]
---

# E2E-01A — przygotowanie jednej faktury

[STATUS](../STATUS.md) · [Dziennik i wyniki CI](../journal/2026-11-15-e2e-01a-sap-fa3-preflight.md) ·
[E2E-01](E2E_01_SAP_TO_KSEF_TEST.md) · [Generator FA(3)](FA3_XML_GENERATION.md) ·
[Security acceptance](CAP_07E_SECURITY_ACCEPTANCE.md)

**Decyzja: NO-GO do SEND.** Generator przeszedł walidację dla jawnego fixture offline.
Nie wybrano ani nie zatwierdzono dokumentu H01. Brakuje aktualnego READ H01, potwierdzenia principal,
braku wcześniejszej wysyłki/rezerwacji w docelowej bazie oraz akceptacji runtime/storage.
Istnieje również luka kontroli zmiany dokumentu pomiędzy przygotowaniem a SEND, opisana poniżej.
Nie wykonano SEND, open/close session, SAP WRITE, tworzenia faktury, aktywacji, CF deploy,
HANA SQL ani uruchomienia outboxa LIVE. Wszystkie nowe wywołania były offline.

## 1. Wersja i zakres dowodów

15.11.2026: `git fetch origin main`, lokalny `main` i `origin/main` =
`c5041bdd934638b99587c757588d706868e16633`. Początkowe drzewo czyste.
Worktree `D:\AInvoicer-e2e-01a`, branch `feature/e2e-01a-sap-fa3-preflight`.
KSEF-IN-04 i KSEF-STOR-04 pozostawiono bez zmian. Bez zmiany kodu produkcyjnego,
modeli CDS, adapterów storage i BLOB-LAB-01.

| Warstwa | Własny wynik tej sesji |
| --- | --- |
| Eksport ABAP, EDMX i CDS Remote Service | INSPEKCJA; źródła/kontrakt zapisany w repo, bez dowodu aktualnego H01 |
| Adapter SAP → generator FA(3) | OFFLINE PASS; prawdziwy adapter CQN i generator, mock RemoteService i DB |
| CAP RemoteService → HTTP | Regresja lokalnego HTTP contract testu; serwer loopback zastępuje SAP |
| H01 active/binding/metadata/odczyt danych i IAM | NOT VERIFIED; narzędzia ABAP FS/MCP nie są dostępne w tej sesji; brak osobnego wykonania LIVE |
| Historyczny H01 READ | 4/9/6 w dziennikach CAP-03/CAP-06; nie jest pomiarem z 15.11 ani dowodem poprawnych NIP/PartyData |
| KSeF | NOT EXECUTED; historyczne fixture SDK z 29.10 nie zalicza SAP → KSeF |

Wersje lokalne: Microsoft JDK **25.0.4.1**, Maven **3.9.16**, CAP Java **5.2.0**,
Spring Boot **4.1.1**, SDK MF **3.0.29**. `git ls-remote` oficjalnego SDK potwierdził
tag `v3.0.29` → `3c3a899541074aa4bd944636b25659aef7d23da7`, zgodny z installerem repo.
Nie aktualizowano zależności.

Przed implementacją sprawdzono [SAP Remote Services](https://cap.cloud.sap/docs/java/cqn-services/remote-services),
[przewodnik sesji MF](https://github.com/CIRFMF/ksef-api/blob/main/sesja-interaktywna.md)
i [oficjalny XSD MF](https://github.com/CIRFMF/ksef-api/blob/main/faktury/schemy/FA/schemat_FA(3)_v1-0E.xsd).
Swagger TEST zwrócił stronę dynamiczną bez czytelnego kontraktu; nie zaliczono weryfikacji wersji wdrożonego API.
To pozostaje bramką przed przyszłym SEND.

## 2. Audyt kontraktu SAP READ

Źródła: `abap/src/zc_ainv_inv_h.ddls.asddls`, `zr_ainv_inv_h.bdef.asbdef`,
`zcl_ainv_test_data.clas.abap`, `cap-java/srv/external/AinvoicerH01.edmx` i `.cds`,
`SapInvoiceRemoteAdapter`, `application.yaml`.

CAP używa **Web API**, nie usługi UI: RemoteService `AinvoicerH01`, Destination `AINV_H01`,
model `com.sap.gateway.srvd_a2x.zapi_ainv_inv_h_o4.v0001`.
Zapisana i sprawdzana lokalnym HTTP kontraktem ścieżka:
`sap/opu/odata4/sap/zapi_ainv_inv_h_o4/srvd_a2x/sap/zapi_ainv_inv_h_o4/0001`.
Origin H01 pochodzi z Destination. Nie skonstruowano nowego adresu ani nie potwierdzono bindingu LIVE.

`readInvoice(UUID)` wykonuje trzy SELECT CQN: nagłówek po `InvoiceUUID`, pozycje
`ZC_AINV_INV_I` i referencje `ZC_AINV_INV_REF` po rodzicu. Pusty nagłówek → Optional.empty;
więcej niż jeden/inny klucz → `SAP_INVOICE_KEY_AMBIGUOUS`.
W runtime adapter podnosi kontekst do `systemUser`, aby użyć modelu internal-user;
SAP HTTP wykonuje principal Destination. **Nie oznacza to izolacji per business user CAP.**
Odpowiedzialność za kontrolę NIP/CompanyCode pozostaje po stronie wywołującego.

| RAP / typ z kontraktu | Zastosowanie / FA(3) |
| --- | --- |
| `InvoiceUUID` / Guid | korelacja i rezerwacja CAP; nie jest numerem KSeF ani P_2 |
| `InvoiceNumber` / String(35) | `Fa/P_2` |
| `IssueDate` / Date | `Fa/P_1` |
| `SellerNip`, `BuyerNip` / String(10) | `Podmiot1/2/DaneIdentyfikacyjne/NIP`; wymagane poprawne sumy kontrolne |
| `SellerName`, `BuyerName` | `Podmiot1/2/.../Nazwa` |
| `CompanyCode` / String(4) | autoryzacja/korelacja CAP; nie jest polem XML |
| `Direction`, `InvoiceKind` | tylko OUTBOUND / STANDARD → `RodzajFaktury=VAT` |
| `InvoiceStatus` | tylko odczyt; dla tej próby wymagamy NEW; writeback nie zmienia go |
| `CurrencyCode` / String(3) | `Fa/KodWaluty`; obsługiwane PLN |
| `NetAmount`, `TaxAmount`, `GrossAmount` / Decimal | nagłówek kontra sumy pozycji; `P_13_x`, `P_14_x`, `P_15` |
| `LocalLastChangedAt` / DateTimeOffset precision 7 | zapamiętana wersja nagłówka; bez obcinania do ms |
| `KsefNumber`, `KsefEnvironment`, `KsefRecordedAt`, `KsefWriteRequestId` | biznesowy wynik SAP; numer musi być pusty przed pierwszą wysyłką |
| `_Items`: `ItemUUID`, `ItemNo` | `UU_ID`, kolejność po ItemNo, `NrWierszaFa` |
| `Description`, `Quantity`, `UnitPrice`, `NetAmount` | `P_7`, `P_8B`, `P_9A`, `P_11` |
| `UnitCode` | EA/PC → `P_8A=szt.`; inne odrzucane |
| `TaxRate` + `TaxCategory` | pary 23/VAT23, 8/VAT8, 5/VAT5 → `P_12`; inne odrzucane |
| pozycje: `TaxAmount`, `GrossAmount`, `CurrencyCode` | sumy VAT, kontrola netto+VAT=brutto i PLN |
| pozycje: `InvoiceUUID`, `LocalLastChangedAt` | zgodność rodzica i fingerprint UUID+wersja |
| `_References` | korelacja źródła; brak mapowania do podstawowej FA(3) |
| `PostingDate`, `DueDate` | nie mapujemy do P_6; brak wdrożonego mappingu płatności |

Eksport CDS deklaruje `authorizationCheck: #MANDATORY`, a model behavior zawiera ETag
`LocalLastChangedAt`. Sama obecność DCL/ETag nie potwierdza efektywnych uprawnień ani wersji
całej kompozycji. Potrzebny nowy, autoryzowany READ `$metadata`, nagłówka oraz dzieci
z właściwego bindingu i identyfikacja principal/zakresu przez administratora.

## 3. Dokument testowy i minimalne dane dla właściciela

**Brak kandydata H01 zatwierdzonego do SEND.** Eksport historycznego seeda SD zawiera
`AINV-TEST-SEED-2026-SD-BILL`, CompanyCode 1000, OUTBOUND/STANDARD/NEW, kwoty
2400.00 / 552.00 / 2952.00 PLN. NIP-y 5260002008 / 5210003008 nie przechodzą domenowej
sumy kontrolnej; potwierdza to istniejący test `h01SeedNipsFailChecksumSoSeedNeedsSyntheticFixture`.
Nie zakładamy, że H01 nadal zawiera te same wartości. Nie podmieniamy NIP w snapshotach LIVE.

Minimalna propozycja **do osobnego utworzenia/zatwierdzenia przez właściciela**, bez wykonania:

| Pole | Propozycja syntetyczna TEST |
| --- | --- |
| InvoiceUUID | UUID przydzielony przez RAP; następnie jawnie wskazany przez właściciela |
| InvoiceNumber | unikalny `AINV-E2E-01A-TEST-001` |
| OriginProcess / Direction / InvoiceKind / InvoiceStatus | SD_BILL / OUTBOUND / STANDARD / NEW |
| CompanyCode | 1000, po potwierdzeniu uprawnień CAP i SAP |
| SellerNip / BuyerNip | 1234563218 / 1111111111 — poprawne kontrolnie, wyłącznie propozycja TEST; bez nadawania praw |
| SellerName / BuyerName | AInvoicer Synthetic Seller / AInvoicer Synthetic Buyer |
| IssueDate | data jawnie zatwierdzona przez właściciela; nie wymuszamy daty seeda |
| Currency / netto / VAT / brutto | PLN / 900.00 / 207.00 / 1107.00 |
| Jedna pozycja | 000010, syntetyczna usługa, 2.000 EA × 450.00; netto 900.00; 23.00/VAT23; VAT 207.00; brutto 1107.00 PLN |
| Wersje | nagłówka i pozycji z rzeczywistego READ, precision 7 |
| Wynik KSeF i historia CAP | puste pola wyniku; zero historycznych SEND i rezerwacji TEST w rzeczywistej DB |

Testowy kontekst JWT KSeF musi odpowiadać **SellerNip z RAP**. Zmiana propozycji NIP wymaga
spójnej konfiguracji principal, PartyData i SDK; sam wpis w tabeli/żądaniu nie daje uprawnień.

## 4. PartyData spoza RAP

`KsefPartyDataProvider` czyta wyłącznie backendowe `ainv.ksef.party-data`:
sprzedawca po SellerNip, nabywca po BuyerNip. Brak wpisu → brak PartyData, bez domysłów.

- Adres sprzedawcy: `country-code=PL`, `line1`, opcjonalne `line2` — obowiązkowy.
- Nabywca: jawne `jst-subordinate-unit=false` i `vat-group-member=false` dla zwykłego fixture;
  brak wartości nie oznacza false. Adres nabywcy opcjonalny.
- Nazwy i NIP pochodzą z RAP; nie zastępuje ich konfiguracja adresowa.
- DataWytworzeniaFa pochodzi z zegara backendu; harness offline ma stały czas
  `2026-11-15T10:00:00Z` dla powtarzalnego hash.

Brak w SAP adresu sprzedawcy i flag JST/GV blokuje przygotowanie, dopóki nie ma konfiguracji backendu.
Nie zapisano zewnętrznego pliku XML; przyszły eksport wymaga uzgodnionej ścieżki poza repo i ACL.

## 5. Kontrolowany dry-run i evidence

Harness `SapFa3Preflight` jest w `srv/src/test/java/.../ksef/`, bez komponentu Spring,
akcji OData, flag aktywacyjnych ani main(). Nie wchodzi do JAR aplikacji.
`SapFa3PreflightTest` używa rzeczywistego `SapInvoiceRemoteAdapter(remote, null)`;
mockowany jest CqnService, a nie snapshot zwracany przez adapter. Generator i PartyDataProvider są rzeczywiste.

Kolejność: prawa operatora i zatwierdzony zakres → SELECT rezerwacji/historii → keyed SAP READ
→ porównanie UUID/NIP/spółki/wersji/statusu/numeru → kontrola pozycji → PartyData
→ `KsefFa3Documents.prepare` (mapper/domena/XSD/maxOccurs) → `verify` dokładnych bajtów
→ drugi READ i porównanie snapshotu → ponowny SELECT rezerwacji/historii → evidence w pamięci.
Nie wywołuje `requestSend`, `enqueue`, registry.register, workera, SDK ani magazynu dokumentów.
Metody statyczne registry służą jedynie do obliczenia wersji/fingerprintu/hash.

Fixture **wyłącznie offline**, nie dokument H01 ani dokument proponowany do SEND:

| Evidence | Wynik |
| --- | --- |
| ID w mock RAP | `00000000-0000-4000-8000-00000000001a` |
| Numer | `AINV-E2E-01A-OFFLINE` |
| SellerNip / BuyerNip / CompanyCode | 1234567802 / 9876543210 / 1000 |
| Wersja nagłówka i pozycji | `2026-10-27T08:15:30.123456700Z` (Instant zachowuje precyzję; to samo 100 ns co `.1234567Z`) |
| Sumy | 900.00 + 207.00 = 1107.00 PLN; jedna pozycja 23% |
| Domena / XSD / limity maxOccurs | PASS / PASS / PASS |
| Wzór | FA(3), `1-0E`, namespace `http://crd.gov.pl/wzor/2025/06/25/13775/` |
| Rozmiar UTF-8 | **1719 bajtów** |
| SHA-256 dokładnych bajtów | `d7824c805b244622c0c8779f1f2577c4cb930d8d5146f228b47f304a55a82105` |
| Efekty zależności udanego dry-run | 6 SELECT RemoteService + 4 SELECT DB; `verifyNoMoreInteractions` wyklucza inne wywołania |
| XML fixture | wyłącznie pamięć testu; brak zapisu przez nowy harness |

Pobrany read-only oficjalny XSD MF: **183798 bajtów**, SHA-256
`b646b6b525f51adf1bb2545f111fc8ca6e7aa6dd2f98948f1667d3695c06d958`,
zgodny bajtowo z kopią w repo. Przypięte importy i ich hash sprawdza `Fa3SchemaResourcesTest`.
Walidator kompiluje duże maxOccurs jako unbounded ze względu na limit Xerces;
`KsefFa3Documents` dodatkowo egzekwuje 12 oryginalnych limitów strumieniowo.
XSD nie sprawdza arytmetyki ani kontrolnej NIP — te kontrole wykonuje domena.
PASS nie oznacza przyjęcia przez KSeF.

## 6. Audyt bezpieczeństwa i idempotencji

| Pytanie | Wynik z kodu / testu offline |
| --- | --- |
| Kwoty/XML z UI? | Nie. Akcja ma tylko `invoiceUUID: UUID`; dodatkowe pola są odrzucane przez OData |
| NIP i spółka? | Z własnego READ RAP; handler porównuje trusted taxpayerNips/companyCodes |
| Rola/principal? | CAP wymaga authenticated-user + KsefOperator. Provider zamyka niejednoznaczny iloczyn wielu NIP × wielu spółek. Rzeczywisty XSUAA/Destination/H01 nadal do potwierdzenia |
| Obcy dokument? | NIP/CompanyCode poza zakresem → 403 przed generowaniem/zapisem; przy OFF nie ma nawet SAP READ |
| Transport OFF? | `KSEF_CONTEXT_NOT_CONFIGURED` 409 przed SAP READ/DB; testy Basic mock oraz offline JWT |
| Snapshot związany z SAP? | SAP_RAP, sapHeaderVersion, itemsFingerprint, SHA-256 i liczba bajtów; rejestracja w ChangeSet |
| Zmiana po przygotowaniu? | Harness wykrywa ją drugim READ. **Worker nie odczytuje ponownie RAP przed SEND.** `detectDrift` jest diagnostyką recovery; CAS writebacku sprawdza wersję nagłówka dopiero po ACCEPTED |
| Zmiana tylko pozycji? | Fingerprint wykrywa ją w diagnostyce, lecz obecny writeback nie egzekwuje fingerprintu kompozycji; sam header ETag nie wystarcza |
| Rezerwacja duplikatu? | PK (InvoiceUUID, TEST), unikalny idempotencyKey, sprawdzenie starszych SEND; rezerwacja, operacja, snapshot i outbox atomowe. RECONCILIATION_REQUIRED nie zwalnia rezerwacji |
| Status i kwoty pozycji? | Harness wymaga NEW, spójnego rodzica, PLN i netto+VAT=brutto pozycji. Produkcyjny handler nie sprawdza InvoiceStatus; mapper nie sprawdza wszystkich tych pól pozycji |
| Bezpieczny dry-run przy OFF? | Tak, wewnętrzny harness z jawnym zatwierdzonym UUID/zakresem/wersjami, bez zmiany publicznego guardu i flag |

Podwójny READ i SELECT nie tworzą blokady rozproszonej. Pomiędzy ostatnim READ a późniejszym
requestSend dokument może się zmienić lub otrzymać rezerwację. **Hash tego preflightu nie zatwierdza
bajtów przyszłego requestSend**: PartyDataProvider użyje nowego DataWytworzeniaFa, więc hash będzie inny.
Przed SEND trzeba ponownie zatwierdzić dane/wersje oraz sprawdzić rzeczywisty hash utrwalonego snapshotu.
Nie zmieniano workflow produkcyjnego w ramach audytu. Ochrona przed edycją po przygotowaniu wymaga
osobnego rozwiązania technicznego lub jawnie zatwierdzonej procedury zamrożenia dokumentu na czas próby.

## 7. Testy i odtworzenie offline

Z `cap-java/`, JDK25/Maven3.9.16 (bez zmiennych LIVE i bez wyłączania excludedGroups):

```powershell
mvn -B verify '-Dcds.cds.skipVersionCheck=true' '-Dtest=SapFa3PreflightTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -B clean verify '-Dcds.cds.skipVersionCheck=true'
```

Harness: **30 testów PASS** (27 pipeline i 3 rzeczywistej H2). Pokrycie: poprawny SAP snapshot, brak faktury, obcy NIP/spółka,
nieobsługiwana stawka, błędne sumy nagłówka/pozycji, brak PartyData/flag, niepoprawne XSD,
istniejący numer, rezerwacja, wcześniejszy SEND, rezerwacja w trakcie próby, zmiana wersji nagłówka/pozycji,
drift w czasie odczytu, brak auth/roli/zakresu, niejednoznaczny zakres oraz brak efektów przy OFF.
Pełna regresja obejmuje też `KsefSendRequestTest`, `KsefSendTransportDisabledTest`,
`HanaReadinessJwtSecurityTest`, testy FA(3), snapshotu i mock writebacku.
Pełna regresja: **527 testów, 0 failures, 0 errors, 6 skipped**, BUILD SUCCESS.
Szczegóły regresji i kontrola CI: [dziennik](../journal/2026-11-15-e2e-01a-sap-fa3-preflight.md).
Istniejące testy mogą zapisywać **syntetyczne** XML do ignorowanego target/; nie są eksportem danych H01.

## 8. Bramki i dokładny plan przyszłego pojedynczego SEND

Poniższych kroków wysyłkowych **nie wykonano**. Obecny raport nie jest zgodą na ich wykonanie.

1. Właściciel wskazuje dokładny InvoiceUUID syntetycznego H01. Jeśli brak faktury,
   osobno zatwierdza jej utworzenie według sekcji 3. Bez edycji istniejącego seeda przez agenta.
2. Osobno zatwierdzić read-only H01. Z rzeczywistego bindingu pobrać metadata i keyed READ
   z pozycjami/referencjami; potwierdzić aktywny kontrakt, NIP, spółkę, daty, status NEW,
   puste cztery pola wyniku oraz wersje nagłówka/pozycji. Potwierdzić principal Destination,
   role CAP, trusted claims i izolację od obcych UUID/dzieci. Historyczny READ nie wystarcza.
3. W docelowym runtime wykonać kontrolowane SELECT historii SEND, rezerwacji i snapshotów
   dla **dokładnego UUID/TEST**, również starych/REJECTED/nieznanych prób. Jakakolwiek próba
   blokuje scenariusz „pierwszy SEND” do ręcznej oceny. Pusta nowa lokalna DB nie dowodzi
   braku wcześniejszego SEND w innym runtime ani bezpośrednio w KSeF.
4. Zatwierdzić runtime i trwały storage XML/UPO (konkretne wersje adapterów, DB, ścieżki/bindings,
   restart i bezpieczny odczyt). CF/HANA wymaga własnych bramek persystencji/security/storage;
   obecność encji KsefDocuments lub wdrożonego LAB nie zalicza magazynu KSeF.
   Lokalny TEST z plikową H2 i LocalTestDocumentStore możliwy tylko jako jawnie wybrany wariant
   poza CF, z uzgodnionymi ścieżkami/ACL poza repo. Bez deployu w tym etapie.
5. Uzgodnić ochronę przed edycją dokumentu od finalnego odczytu przez SEND; rozstrzygnąć lukę
   wersji kompozycji i dodatkowe kontrole pozycji/statusu z sekcji 6. Bez tego NO-GO.
6. Skonfigurować PartyData per rzeczywiste NIP z RAP. Uruchomić wewnętrzny, read-only dry-run
   z zatwierdzonym UUID i wersjami (wzorzec `SapFa3Preflight.run`); sprawdzić domenę, XSD,
   maxOccurs, sumy, rozmiar/hash. Harness testowy nie jest gotowym publicznym narzędziem LIVE:
   podłączenie do zatwierdzonego runtime/principal wymaga kontrolowanego testu, bez startupu workerów.
7. Zweryfikować wdrożony kontrakt KSeF TEST i credential/auth/JWT właściwego SellerNip po stronie
   backendu; nie przekazywać tokenów do UI, plików Git, parametrów shell ani logów.
   Potwierdzić faktyczne uprawnienia do wysyłki i odczytu statusów/UPO.
8. Uzyskać **osobną zgodę właściciela na jeden SEND wskazanego UUID do TEST**.
   Osobno zatwierdzić SAP writeback i jego zakres/CSRF/ACTVT16/CAS według CAP-04D.
   Automatyczne enqueue writebacku następuje już po ACCEPTED, przed close/UPO; jeśli WRITE
   pozostaje OFF, musi być zatwierdzony plan późniejszego wznowienia writebacku, bez nowego SEND.
9. Operator uruchamia uzgodniony proces TEST z zatwierdzoną trwałą DB/storage i lokalnymi flagami
   transportu; bez zmiany domyślnych flag repo. Ostatni READ UUID/wersji i stanu CAP,
   potwierdzenie właściciela, następnie **jedno** requestSend z body tylko
   `{"invoiceUUID":"<ZATWIERDZONY_UUID>"}`. Zapisać jeden operationId, bez drugiego kliknięcia.
10. HTTP200/202 lub QUEUED oznacza przyjęcie zlecenia, **nie ACCEPTED**. Odczytywać
    `InvoiceIntegrationStatus` dla UUID/operationId z backoff. Sprawdzić SAP_RAP, utrwalony
    XML SHA-256, sesję/reference, biznesowy status faktury 200 oraz zgodny hash/reference i numer KSeF.
11. Potwierdzić trwałe ACCEPTED przed osobną transakcją writebacku. Worker zamyka sesję raz,
    czeka na jej status i dostępność UPO, zapisuje je w zatwierdzonym storage.
    Brak UPO lub problem SAP nie uzasadnia ponownego SEND. Potwierdzić COMPLETED/upoAvailable,
    powiązanie numer/hash i odczyt dokumentu przez zaufanego operatora; bez publicznego eksportu XML.
12. Potwierdzić osobny SAP writeback SUCCEEDED/APPLIED albo idempotentne ALREADY_APPLIED.
    Niezależny RAP READ ma zwrócić KsefNumber, TEST, recordedAt i requestId=operationId;
    InvoiceStatus pozostaje bez zmiany. Potwierdzić dane po restarcie tej samej DB/storage.
    Jeśli WRITE zablokowany, wynik raportować jako KSeF ACCEPTED / SAP PENDING.

### Ochrona przed drugą wysyłką

| Niejednoznaczny wynik | Obowiązkowe działanie |
| --- | --- |
| requestSend bez odpowiedzi / HTTP timeout | READ historii i rezerwacji UUID/TEST; zachować DB/storage; żadnego ponownego requestSend |
| przerwanie open/send/redeem/close | RECONCILIATION_REQUIRED, zachować rezerwację i hash/ref; nie kasować stanu ani nie zmieniać idempotencyKey |
| znana sesja/reference | Kontrolowane `reconcile(operationId)`: bez ponownego SEND/close, potwierdzenie numeru i hash w tej samej sesji |
| brak reference/dowodu | Pozostawić NO-GO i ręczną rekoncyliację; brak wyniku wyszukiwania nie dowodzi niewysłania |
| wyczerpane bezpieczne READ | Po usunięciu przyczyny i retryNotBefore kontrolowane `resumeReads`; bez nowej operacji |
| ACCEPTED, błąd SAP/UPO | Odzyskać osobne etapy istniejącej operacji; żadnego SEND |
| VERSION_CONFLICT / RESULT_CONFLICT | READ diagnostyki recovery, bez nadpisania ExpectedVersion i bez nowego SEND |

Metody reconcile/resumeReads/diagnose są wewnętrzne, nie są nowymi endpointami OData.
Nie wykonywać testu „drugiego kliknięcia” podczas tej próby: wystarcza regresja offline duplikatu;
jakikolwiek dodatkowy requestSend wymaga osobnego zakresu. Nie resetować DB ani rezerwacji dla retry.

## 9. Kryteria zamknięcia

- [x] Syntetyczny fixture offline wskazany; blocker wyboru H01 opisany.
- [x] Adapter READ i walidacja FA(3) offline, SHA-256 i rozmiar.
- [x] Domena/XSD PASS dla fixture; historyczny seed ma dokładnie opisany problem NIP.
- [x] Nowy dry-run nie tworzy operacji, snapshotów, rezerwacji ani outboxa.
- [x] Brak SEND i SAP WRITE; brak zmian LIVE i flag repo.
- [x] Runbook pojedynczego SEND oraz rekoncyliacji przygotowany.
- [ ] Bieżący autoryzowany H01 READ i zatwierdzenie dokumentu/principal/wersji.
- [ ] Rozstrzygnięcie luki drift/status/pozycji oraz zatwierdzony runtime/storage.
- [ ] Osobna zgoda na SEND i plan SAP writebacku.

PR/SHA/CI i wynik regresji podaje dziennik. **GO dotyczy wyłącznie kontynuacji przygotowania;
SEND pozostaje NO-GO.**
