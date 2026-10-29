---
title: "AInvoicer — plan rozwoju"
date: 2026-10-29
type: roadmap
status: aktywny
tags: [ainvoicer, roadmap, cap-java, ksef]
---

# Plan rozwoju

[Project Home](README.md) · [Aktualny stan i dowody](STATUS.md) · [Decyzja architektoniczna](decisions/ADR-0001-cap-java-ksef-architecture.md)

`[x]` oznacza ukończenie z podanym zakresem potwierdzenia, `[ ]` — pracę do wykonania. IN PROGRESS oznacza zadanie rozpoczęte. Ten plik jest planem; historię opisują [dziennik bootstrapu](journal/2026-10-23-cap-java-bootstrap.md), [weryfikacja Phase 1](journal/2026-10-24-cap-java-bootstrap-verification.md) i [pierwszy eksport abapGit/RAP](journal/2026-10-24-abapgit-rap-initial-export.md).

## Narzędzia projektowe — Agent Skills

- [x] Trzy kanoniczne Skills ABAP/RAP, CAP Java i KSeF w `.agents/skills/`, odwołania Claude w `.claude/skills/` oraz sekcje `Project Skills` w instrukcjach agentów.
- [x] Walidacja statyczna i ręczna ocena czterech scenariuszy; Codex `skills/list` wykrywa trzy aktywne Skills w zakresie repo bez błędów ładowania.
- [ ] Potwierdzenie automatycznego doboru przez model oraz wykrywania/odczytu kanonicznych instrukcji w sesji Claude Code. Wersje CLI i poprawne pliki nie zastępują tego testu.

Dowody i sposób wywołania: [dziennik Skills](journal/2026-10-23-project-skills.md). Utworzenie Skills nie kończy żadnego etapu implementacji CAP/SAP/KSeF.

## Phase 1 — CAP Java Bootstrap — COMPLETE

- [x] Wygenerowanie projektu CAP Java w `cap-java/` (pliki zweryfikowane; polecenie inicjalizacji z relacji użytkownika).
- [x] Powtarzalny `mvn clean verify`: dwa uruchomienia na JDK 25.0.4.1 i Maven 3.9.16; CDS, kompilacja, zasoby i oba JAR-y zbudowane.
- [x] Poprzedni `NoSuchFileException` sprawdzony: nie odtworzono; `application.yaml` skopiowano do `target/classes` przed pakowaniem. Przyczyna dawnego incydentu pozostaje nieustalona.
- [x] Uruchomienie Spring Boot/CAP Runtime z executable JAR; HTTP Index odpowiada pod `http://localhost:8080/`.
- [x] Test integracyjny H2 DataSource: `SELECT 1` PASS.

Własne API CDS/OData i CRUD nie wchodziły w Phase 1; ich sprawdzenie należy do Phase 2.

## Phase 2 — CAP Domain Model

- [x] Minimalna encja CDS `KsefOperations`: korelacja SAP jako tekst, NIP, kierunek, typ, status NEW, klucz idempotencji, referencje i błąd.
- [x] Encja CDS `KsefInvoices`: kierunek, korelacja SAP, strony, numer KSeF, status biznesowy `UNCONFIRMED` i referencje do magazynu; status operacji pozostaje techniczny.
- [x] Opcjonalna asocjacja `KsefOperations` → `KsefInvoices`; publiczne OData wyklucza nawigację i nie pozwala klientowi ustawiać FK. Referencje XML/UPO są ukryte, encja faktury jest tylko do odczytu i filtrowana per NIP.
- [ ] Zaufana ścieżka backendowa do wiązania operacji z fakturą musi sprawdzać zgodność NIP. H2 nie generuje constraint FK dla asocjacji; obecny test potwierdza poprawne powiązanie, a nie automatyczne odrzucanie błędnego FK.
- [x] `KsefService` wystawia `KsefOperations` przez OData V4, wymaga uwierzytelnionego użytkownika i blokuje zapis statusów/referencji technicznych po stronie konsumenta.
- [x] Minimalny Java `@Before` handler CREATE: NIP z sumą kontrolną, direction, katalog operationType, wymagany idempotencyKey (trim) i status `NEW`.
- [x] Lokalna weryfikacja `$metadata`, OData i persystencji H2; walidacja domenowa oraz autoryzacja per NIP są potwierdzone testami integracyjnymi.
- [x] Trwała encja operacji i unikalny idempotency key `(taxpayerNip, idempotencyKey)`.
- [x] Kontrola konkurencji i worker/outbox po commit — CAP-05 H2/mock; HANA/CF osobno.
- [x] `RECONCILIATION_REQUIRED`, recovery po restarcie i testy niejednoznacznych odpowiedzi — CAP-05 H2/mock.
- [x] Kontrola dostępu do operacji: rola `KsefOperator`, lista atrybutów `$user.taxpayerNips`, izolacja CREATE/READ i blokada UPDATE/DELETE w publicznym serwisie.
- [x] Testy integracyjne OData + Spring Security + CAP + H2: użytkownicy operator A/B, anonimowy/brak roli, walidacja, status, brak UPDATE/DELETE i unikalność idempotencji.
- [ ] Docelowa konfiguracja IAS/XSUAA i mapowanie zaufanych claimów do roli `KsefOperator` oraz atrybutu `taxpayerNips` (przed udostępnieniem serwisu użytkownikom).

## Phase 3 — SAP Remote Integration

Statusy wskazują środowisko i zakres dowodu: lokalny CAP LIVE nie oznacza CAP na Cloud Foundry.

- [x] **CAP-04B (2026-10-28): SAP RAP `ApplyKsefResult` aktywna w H01.** OUTBOUND/TEST, cztery readonly pola, CAS po blokadzie, wyniki idempotencji/konfliktów, odrębne prawa akcji i CRUD. EML 18/18, prototyp blokowania 7/7 (w tym root enqueue między sesjami), ATC dziesięciu obiektów kontraktu 0 ustaleń, surowy runtime EDMX HTTP200. Uprawnienie bez nadania. Bez HTTP WRITE/commit i pełnego testu dwóch równoległych zapisów. [Dowody](journal/2026-10-28-cap-04b-rap-write-contract.md), [ADR-0002](decisions/ADR-0002-sap-ksef-result-write-contract.md).
- [x] **CAP-04C (2026-10-28):** CAP Write Adapter, interpretacja `Outcome`, wersja 100 ns, niezmienne zlecenie, persistent outbox/worker i historia. Testy rollback/konkurencji/restartu z mockiem, lokalny kontrakt HTTP i regresja READ H01 PASS. Transport OFF, brak prawdziwego producenta ACCEPTED i HTTP WRITE. 429/Retry-After oraz wyczerpane retry wymagają obsługi operacyjnej. [Dowody](journal/2026-10-28-cap-04c-sap-result-writeback.md).
- [x] **CAP-04 Hardening (2026-10-29, commit `dfd1ecb`):** claim/finalize, recovery, SHA-256 i unikalność numeru; historyczny build 71/71 PASS H2/mock. [Dziennik](journal/2026-10-29-cap-04-hardening.md).
- [x] **Hardening Fix 2 (2026-10-29, lokalnie zweryfikowany; publikacja Git na zlecenie użytkownika):** rewizja CAS resume/claim, test prawdziwej odmowy CRUD przez CAP 5.2, jawne timeouty Cloud SDK oraz odnawianie lease. **82/82 PASS** w pełnym clean verify (H2/mock, LIVE wykluczone). Dowody i ograniczenia SAP w [kontynuacji dziennika](journal/2026-10-29-cap-04-hardening.md#kontynuacja--hardening-fix-2-i-cap-04d-readiness).
- [ ] **BLOCKED / NOT VERIFIED:** test H01 CBA z globalnym ACTVT02 bez prawa do NIP/spółki rodzica — [przygotowany szkic testu](patches/cap-04-cba-scope-test.md), brak dostępu do include przez MCP. DCL/IAM/arrangement i odmowa rzeczywistym principalem nadal wymagają weryfikacji.
- [ ] **Do decyzji po hardeningu:** polityka odczytu DCL ([propozycja](guides/SAP_WRITE_ACCESS_AND_READ_POLICY.md)), nadanie principalowi zapisu, interfejs operacyjny `resume`, `total etag` dla pozycji, producent migawek/potwierdzeń (Phase 4).
- [ ] **CAP-04D — WYMAGA OSOBNEJ ZGODY:** ograniczone nadanie ACTVT16/NIP/CompanyCode, HTTP WRITE na uzgodnionych danych, persisted ETag/no-op, testy prawdziwym principalem i pełne równoległe zapisy akcji.
- [x] CAP-03 live H01 preview (2026-10-27): aktywny binding OData V4 `ZAPI_AINV_INV_H_O4` / `0001`; `$metadata` HTTP 200 i encje/nawigacje potwierdzone. Autoryzowany read z `$expand=_Items,_References` zwrócił 4/9/6 i UUID zgodne z seedem. To odczyt przez MCP OAuth, nie przez CAP.
- [x] Dodano CDS model zewnętrzny, CAP Remote Service Destination `AINV_H01`, read-only adapter CQN, test kontraktu adaptera i blokadę publicznego dostępu do zewnętrznego modelu; `mvn clean verify -Dcds.cds.skipVersionCheck=true` PASS (22 testy).
- [x] CAP-04B: odczyt rzeczywistego surowego EDMX przez HTTP GET; akcja publiczna, parametry, wynik i readonly pól potwierdzone. Nie importowano modelu do CAP.
- [x] CAP-04C: model CAP z pełnego EDMX, oryginalny namespace i wymaganie `internal-user`; adapter READ zachowany, akcja ma osobny Write Adapter.
- [x] CAP-03 krok 2 (2026-10-27): lokalny test HTTP (fałszywy serwer, **nie LIVE**) przez prawdziwy CAP RemoteService + Cloud SDK Destination potwierdził poprawny URL i Basic Authentication; naprawiono 3 błędy (dodatkowy segment `AinvoicerH01/` w URL, `@requires: internal-user` blokujące odczyt bez użytkownika, filtr `in`). Dodano opcjonalny test LIVE (`@Tag("live-sap")`, domyślnie wyłączony).
- [x] **CAP → SAP H01 READ: LIVE PASS (2026-10-27), lokalny proces CAP + lokalna Destination.** `SapInvoiceRemoteAdapterLiveTest`: 1 test, 0 failures, 0 errors, `BUILD SUCCESS`; 4 nagłówki, 9 pozycji, 6 referencji, UUID zgodne. Destination przekazano przez env `destinations` z Basic Auth. To nie jest CAP na CF ani BTP Destination Service. Historia pierwszego IAS redirectu i błędów technicznych pozostaje w [dzienniku](journal/2026-10-27-cap-03-sap-remote-integration.md).

- [x] Bootstrap Git/abapGit dla pakietu `ZAINVOICER`: branch `java-cap-ksef`, `.abapgit.xml` wskazuje `/abap/src/`, Folder Logic `FULL`; pierwszy eksport potwierdzony commitem `39ab869`.
- [x] Inspekcja repo wykazała symulacyjny model tabel `ZAINV_INV_H`, `ZAINV_INV_I`, `ZAINV_INV_REF` i service definition/binding `ZAPI_AINV_INV_H_O4`.
- [x] Weryfikacja runtime przez read-only MCP H01 (2026-10-27): aktywny binding `ZAPI_AINV_INV_H_O4`, wersja `0001`, published=true; `$metadata` HTTP 200, entity sets i nawigacje potwierdzone. Autoryzowany OData `$expand` zwrócił 4/9/6 i oryginalne UUID. MCP nie zwrócił surowego EDMX ani hosta dla konfiguracji CAP Destination.
- [x] Przygotowano lokalny patch źródeł root/child CDS, behavior, DCL i service definition dla kompozycji Header–Items–References; niepotwierdzony w SAP i bez serializowanych sidecarów nowych obiektów.
- [x] Diagnostyka tylko do odczytu potwierdziła `recordChanges=false` / `ZLOCAL` dla `ZAINVOICER`; usunięto błędny wymóg transportu. Ustalono poprawny typ tworzenia `DDLS/DF` i odtworzono historyczny komunikat deserializacji. W tamtym etapie nie naprawiano generatora MCP; późniejszy wynik v5 opisano niżej.
- [x] Zapisano istniejące obiekty kompozycji według `d3dedf4` (2026-10-24); historia: [dziennik edycji](journal/2026-10-24-abap-rap-composition.md#edycja-istniejacych-obiektow-wedlug-d3dedf4--2026-10-24).
- [x] ABAP-02 live (2026-10-25): utworzono przez poprawione MCP 3 brakujące DDLS, zachowano zastany `ZR_AINV_INV_I`; aktywowano 6 CDS, 2 BDEF i istniejący SRVD. Poprawki H01: brak niedozwolonego `Semantics.unitOfMeasure`, dziedziczenie provider contract w child projections i własny ETag dzieci. Root/binding zachowane. ATC głównego BDEF: 0 ustaleń. [Dowody i różnice z Git](journal/2026-10-24-abap-rap-composition.md#kontynuacja-live-przez-poprawione-mcp--2026-10-25).
- [x] Próba możliwości tworzenia MCP w `ZAINVOICER_DEV` (2026-10-25): `CLAS/OC` utworzyła się, `TABL/DT`, `DDLS/DF` i `DTEL/DE` zwróciły `Unsupported Media Type`; brak częściowych obiektów po kontrolnym wyszukiwaniu. Kontrola statusu ujawniła przypisanie klasy do istniejącego requestu `H01K900018`, mimo odpowiedzi „local — no transport required”; nie zmieniano requestu ani nie aktywowano klasy. Szczegóły: [dziennik](journal/2026-10-25-abapfs-create-probe.md).
- [x] ABAP FS 2.12.4 / fix 5: potwierdzono **4/4** nowych klas/CDS w obu pakietach Cloud, brak nowych requestów i poprawne przypisania SAP; uaktualniono instrukcje. `recordChanges=false` pomija selektor, a „Transport requested” wymaga odczytu przypisania. To nie jest pełny test typów ani test ECC. [Dowody i procedura](journal/2026-10-26-abapfs-v5-instructions.md).
- [x] ABAP-04 — utworzono i aktywowano `ZCL_AINV_TEST_DATA` w H01. Deep EML obejmuje FI_AP, FI_AR, MM_IV i SD_BILL z 9 pozycjami oraz 6 referencjami; diagnostyka bez błędów, ATC bez błędów/ostrzeżeń. [Dziennik](journal/2026-10-26-abap-04-test-data-seed.md).
- [x] ABAP-04 — użytkownik potwierdził H01 Trial i przekazał log uruchomienia; niezależny ABAP SQL SELECT z CDS potwierdził 4 nagłówki, 9 pozycji, 6 referencji, powiązania po UUID, PLN i zgodność sum.
- [x] ABAP-04 — uwierzytelniony runtime OData `$expand=_Items,_References` przez MCP potwierdził 4/9/6 oraz UUID (2026-10-27; szczegóły w [CAP-03](journal/2026-10-27-cap-03-sap-remote-integration.md)).
- [ ] ABAP-04 — wykonać sekwencyjny rerun i potwierdzić brak duplikatów. Brak blokady dla równoległych uruchomień pozostaje ograniczeniem aktywnego modelu.
- [x] DCL/EML — **wynik według raportu użytkownika z 2026-10-26, niepowtórzony w tym audycie:** 6 DCL aktywnych; `ZCL_AINV_RAP_COMP_TEST` 4/4 ABAP Unit PASS; ATC trzech nowych DCL: 0 ustaleń. Historyczny dump `ACM_UNEXPECTED_VALUE`, brakujące role i `Unsupported Media Type` pozostają w dziennikach jako wcześniejszy stan/problemy.
- [x] CAP-04B: odczyt live DCL i implementacji przed zmianą; wdrożono i przetestowano global/instance authorization oraz prechecks zapisu. Szczegółowy zakres testów EML/ATC w dzienniku; historycznego testu kompozycji nie odtwarzano.
- [ ] **NOT VERIFIED:** polityka izolacji odczytu DCL per NIP. Root `where TRUE` pozostawiono; aktywacja DCL i testy zapisu nie potwierdzają izolacji odczytu.
- [x] Po zgodzie użytkownika wyeksportowano 31 plików CAP-04B przez MCP abapGit: `525e48d`, remote refs/status potwierdzone, lokalny fast-forward. Pominięto zastane zmiany spoza zakresu. Nie importowano niczego do H01.
- [x] Wykonać runtime `$metadata`, `$expand` i autoryzowany odczyt syntetycznych danych z SAP przez MCP; wynik live oddzielono od testów CAP/mocks w [CAP-03](journal/2026-10-27-cap-03-sap-remote-integration.md).
- [x] Dodać model zdalny CAP CDS na podstawie runtime kontraktu, konfigurację CAP Java Remote Service i read-only adapter CQN. To model opracowany z normalizowanych metadanych, nie import surowego EDMX.
- [ ] **NOT TESTED:** zweryfikować CAP → SAP H01 READ po wdrożeniu CAP na Cloud Foundry z przypiętą Destination Service. Lokalny test z env `destinations` tego nie potwierdza.
- [x] CAP-04C: wywołanie zapisu przez RAP action, atomowe zlecenie i persistent worker. Walidacja lokalnym HTTP/mocks; **LIVE WRITE NOT TESTED**, transport domyślnie wyłączony. CAP-04D pozostaje osobnym zadaniem.

ABAP Environment jest tu źródłem symulacyjnych danych aplikacyjnych; nie zakładaj standardowych tabel/dokumentów S/4HANA ani modelu `ZFI_ACDOCA`. Bez bezpośredniego SQL do bazy ABAP i bez pozornych JOIN-ów z lokalną bazą CAP. Szczegóły: [model RAP](guides/ABAP_RAP_INVOICE_MODEL.md) i [workflow abapGit](guides/ABAPGIT_WORKFLOW.md).

## Phase 4 — KSeF SDK Integration

**IN PROGRESS — Agent A, `feature/ksef-auth`, 2026-10-29:** SDK 3.0.29,
auth/sesje, trwały worker/checkpointy i rekoncyliacja; **141 testów lokalnych PASS** po integracji A+B.
LIVE auth TEST (XAdES → 200 → redeem → chroniony READ → refresh) PASS.
**LIVE E2E CAP/SDK PASS** na oficjalnym fixture: jedna faktura
`1234563218-20261029-7DC8A1C00000-CD`, UPO 5528 bajtów, RESUME po restarcie.
FA(3) B połączono ze snapshotem i outboxem (`7075c01` → `657c285`), z pełną kontrolą
dużych maxOccurs na granicy wysyłki. Źródło LIVE H01 i LIVE send XML wygenerowanego
przez B pozostają niepotwierdzone (NIP/PartyData; bez drugiej wysyłki).
[Dziennik](journal/2026-10-29-agent-a-ksef-sdk.md).

- [x] SDK MF 3.0.29 przypięte, zbudowane ze źródeł i użyte w JVM CAP/Boot 4; mock/HTTP i LIVE TEST PASS (Agent A).
- [x] TEST lokalnie: XAdES, biznesowe 200, redeem, JWT w pamięci, refresh i autoryzowany READ PASS; zarządzane credentiale BTP przed CF.
- [x] **Poprawny XML FA(3) według oficjalnego XSD (Agent B, 2026-10-29, `feature/ksef-fa3`):** generator podstawowej faktury VAT, walidacja XSD 1-0E offline + domenowa, bajty i SHA-256 dla migawki CAP, adapter SAP. Lokalnie 112/112 PASS; przyjęcie przez KSeF niezweryfikowane. [Przewodnik](guides/FA3_XML_GENERATION.md).
- [x] Sesje i kryptografia SDK; dokładne bajty B → snapshot → storage → send potwierdzone testem A+B; LIVE jedna faktura z oficjalnego fixture.
- [ ] Źródło danych spoza RAP dla FA(3): adres sprzedawcy/nabywcy, JST/GV; poprawne NIP-y w seedzie H01.
- [x] Trwały worker/outbox, checkpointy przed HTTP, lokalne testy rollback/konkurencji/restartu PASS; brak blokad biznesowych podczas sieci.
- [x] TEST lokalnie: status 200/hash/numer, close i UPO; plikowa H2 i jawny lokalny magazyn TEST. Zarządzany storage przed CF pozostaje osobnym zadaniem.
- [x] TEST/H2: bezpieczne odczyty z limitem/backoff/jitter/Retry-After, rekoncyliacja hash/ref i brak ponownego send/close po niejednoznacznej odpowiedzi; CF/HANA osobno.
- [ ] Pierwszy vertical slice: jedna syntetyczna faktura SAP Remote OData → CAP → SDK → KSeF TEST → numer KSeF i UPO; wysyłka tylko po jawnej zgodzie.

HTTP 202 nie oznacza przyjęcia faktury. Eksperyment na `feature/ksef-java-e2e-runner` jest referencją według kontekstu projektu; nie zalicza tego etapu.

## Phase 5 — SAPUI5 + CAP

- [x] CAP-05: A+B i UI5 C na `feature/cap-05-integration`; CAP clean verify **173/173 PASS** H2/mock; UI5 build/QUnit/OPA PASS. [Audyt](audits/2026-10-29-cap-05-implementation-review.md).
- [x] `requestSend(invoiceUUID)` — własny odczyt RAP, NIP/CompanyCode, TEST, FA(3)/snapshot, atomowa rezerwacja DB i persistent outbox. Konkurencja dwóch użytkowników, rollback, nieznane stany i controlled retry potwierdzone lokalnie.
- [x] ACCEPTED → SAP writeback po commit + sweeper; brak utraty przyjęcia/UPO przy błędzie enqueue/SAP; tylko SAP_RAP. VERSION_CONFLICT blokuje retry SEND, bez obejścia CAS; diagnostyka read-only.
- [x] Istniejący frontend: named RAP V4 model, ograniczona strona, server paging/filter/sort RAP, CAP stan integracji dla UUID strony i akcje; priorytet numeru RAP. Brak CAP proxy listy.
- [x] Regresja mock: AI Assistant, Scanner, Voice controls i Legacy FI; QUnit i OPA. Nie oznacza LIVE AI/STT ani CAP→SAPUI5 na BTP.
- [ ] **BLOCKED:** oddzielna usługa RAP UI, DCL ACTVT03/NIP/CompanyCode, IAM i testy faktycznymi business userami; przygotowane [źródła i czynności ADT](patches/cap05-rap-ui/README.md), bez aktywacji.
- [ ] Approuter i IAS/XSUAA: zgodne trusted claims NIP/CompanyCode w RAP/CAP; trasa `/rap-ui/` do zweryfikowanego bindingu. Domyślny UI pozostaje mock do przejścia bramki.
- [ ] Autoryzowane publiczne pobranie XML/UPO; obecnie worker przechowuje UPO i publikuje upoAvailable, przyciski nadal wyłączone.
- [ ] Globalne filtrowanie po statusie CAP (obecnie jawnie bieżąca strona); HANA/multi-instance i migracja historycznej bazy z uzupełnieniem zaufanego CompanyCode.
- [ ] Osobna ocena biznesowa blokowania UPDATE/DELETE po numerze KSeF i total version kompozycji; patch testów charakteryzacyjnych NOT SAP VERIFIED.
- [ ] Kontrolowany LIVE E2E: poprawne syntetyczne dane RAP + PartyData, jedna wysyłka TEST i writeback, numer w UI/UPO/409 po ponowieniu. Wyłącznie po osobnym zleceniu; w CAP-05 nie wykonano SEND/HTTP WRITE.

## Phase 6 — BTP & Production Hardening

- [ ] Potwierdzenie entitlementów i konfiguracji SAP HANA Cloud; migracja i testy trwałości.
- [ ] Przygotowanie deploymentu Cloud Foundry; wykonanie wdrożenia wyłącznie w autoryzowanym zakresie.
- [ ] OAuth2/XSUAA lub IAS według konfiguracji; zarządzane sekrety, service bindings i Destination.
- [ ] Restrykcyjny CORS, limity wejścia, bezpieczne błędy i autoryzacja API.
- [ ] Testy worker/outbox, idempotencji i konkurencji przy restartach oraz wielu instancjach CF; monitoring i audyt bez sekretów.
- [ ] Trwały magazyn XML/UPO, rozdział TEST/DEMO/PROD i procedura rekoncyliacji.

Podstawy trwałości, idempotencji i autoryzacji powstają przed wysyłką w Phase 2–4; ten etap sprawdza je w docelowej infrastrukturze. BTP Trial nie jest środowiskiem produkcyjnym. Batch, zakupy, korekty, offline i multi-NIP pozostają poza pierwszym vertical slice.
