---
title: "AInvoicer — plan rozwoju"
date: 2026-10-27
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
- [ ] Przed pierwszą wysyłką: kontrola konkurencji i worker/outbox po commit.
- [ ] Przed efektami zewnętrznymi: `UNKNOWN/RECONCILIATION_REQUIRED`, odtwarzanie po restarcie oraz testy niejednoznacznych odpowiedzi.
- [x] Kontrola dostępu do operacji: rola `KsefOperator`, lista atrybutów `$user.taxpayerNips`, izolacja CREATE/READ i blokada UPDATE/DELETE w publicznym serwisie.
- [x] Testy integracyjne OData + Spring Security + CAP + H2: użytkownicy operator A/B, anonimowy/brak roli, walidacja, status, brak UPDATE/DELETE i unikalność idempotencji.
- [ ] Docelowa konfiguracja IAS/XSUAA i mapowanie zaufanych claimów do roli `KsefOperator` oraz atrybutu `taxpayerNips` (przed udostępnieniem serwisu użytkownikom).

## Phase 3 — SAP Remote Integration

Statusy wskazują środowisko i zakres dowodu: lokalny CAP LIVE nie oznacza CAP na Cloud Foundry.

- [x] CAP-03 live H01 preview (2026-10-27): aktywny binding OData V4 `ZAPI_AINV_INV_H_O4` / `0001`; `$metadata` HTTP 200 i encje/nawigacje potwierdzone. Autoryzowany read z `$expand=_Items,_References` zwrócił 4/9/6 i UUID zgodne z seedem. To odczyt przez MCP OAuth, nie przez CAP.
- [x] Dodano CDS model zewnętrzny, CAP Remote Service Destination `AINV_H01`, read-only adapter CQN, test kontraktu adaptera i blokadę publicznego dostępu do zewnętrznego modelu; `mvn clean verify -Dcds.cds.skipVersionCheck=true` PASS (22 testy).
- [ ] Import surowego EDMX i sprawdzenie pełnych właściwości CSDL — MCP udostępnił znormalizowany opis runtime `$metadata`, bez surowego XML.
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
- [ ] **NOT TESTED:** ponownie sprawdzić live bieżący stan DCL/EML i ATC w H01 przed zmianą biznesową. Runtime `$metadata`/`$expand` 4/9/6 nie testuje izolacji użytkowników/podatników.
- [ ] **NOT VERIFIED:** określić i przetestować politykę izolacji DCL oraz global authorization dla zapisu. Eksport ma root DCL `where TRUE`, a `GET_GLOBAL_AUTHORIZATIONS` jest pusta; nie deklarować bezpieczeństwa na podstawie samej aktywacji DCL.
- [ ] Po zakończeniu walidacji i osobnym zleceniu wyeksportować stan SAP przez abapGit. Lokalny eksport nadal zawiera 4 starsze źródła CDS/BDEF i nie zawiera nowej klasy testowej; nie importować starego patcha do H01.
- [x] Wykonać runtime `$metadata`, `$expand` i autoryzowany odczyt syntetycznych danych z SAP przez MCP; wynik live oddzielono od testów CAP/mocks w [CAP-03](journal/2026-10-27-cap-03-sap-remote-integration.md).
- [x] Dodać model zdalny CAP CDS na podstawie runtime kontraktu, konfigurację CAP Java Remote Service i read-only adapter CQN. To model opracowany z normalizowanych metadanych, nie import surowego EDMX.
- [ ] **NOT TESTED:** zweryfikować CAP → SAP H01 READ po wdrożeniu CAP na Cloud Foundry z przypiętą Destination Service. Lokalny test z env `destinations` tego nie potwierdza.
- [ ] **NOT IMPLEMENTED:** CAP → RAP UPDATE; obecny model i adapter są read-only.

ABAP Environment jest tu źródłem symulacyjnych danych aplikacyjnych; nie zakładaj standardowych tabel/dokumentów S/4HANA ani modelu `ZFI_ACDOCA`. Bez bezpośredniego SQL do bazy ABAP i bez pozornych JOIN-ów z lokalną bazą CAP. Szczegóły: [model RAP](guides/ABAP_RAP_INVOICE_MODEL.md) i [workflow abapGit](guides/ABAPGIT_WORKFLOW.md).

## Phase 4 — KSeF SDK Integration

- [ ] Wybór i przypięcie wersji oficjalnego SDK MF, audyt zgodności z CAP/Spring Boot; adapter w tej samej JVM.
- [ ] TEST: autoryzacja właściwego kontekstu podatnika, bezpieczne przechowywanie credentiali i obsługa JWT po stronie backendu.
- [ ] Poprawny XML FA(3) według oficjalnego XSD; sesje i kryptografia obsługiwane przez SDK.
- [ ] Wysyłka przez trwały worker; bez długiego pollingu w transakcji żądania OData.
- [ ] Status końcowy i numer KSeF, zamknięcie sesji, UPO oraz metadane/ref. do trwałego magazynu dokumentów.
- [ ] Retry tylko bezpiecznych odczytów i sklasyfikowanych błędów, backoff/jitter, 429/Retry-After oraz rekoncyliacja po utracie odpowiedzi.
- [ ] Pierwszy vertical slice: jedna syntetyczna faktura SAP Remote OData → CAP → SDK → KSeF TEST → numer KSeF i UPO; wysyłka tylko po jawnej zgodzie.

HTTP 202 nie oznacza przyjęcia faktury. Eksperyment na `feature/ksef-java-e2e-runner` jest referencją według kontekstu projektu; nie zalicza tego etapu.

## Phase 5 — SAPUI5 + CAP

- [ ] Stopniowe podłączenie istniejącego frontendu do gotowego API OData CAP.
- [ ] Wyświetlanie faktur i akcji zlecenia wysyłki jako operacji asynchronicznej.
- [ ] Odczyt statusów, numeru KSeF i autoryzowane pobranie UPO.
- [ ] Weryfikacja uprawnień i regresji istniejących ekranów SAPUI5 oraz funkcji AI.

## Phase 6 — BTP & Production Hardening

- [ ] Potwierdzenie entitlementów i konfiguracji SAP HANA Cloud; migracja i testy trwałości.
- [ ] Przygotowanie deploymentu Cloud Foundry; wykonanie wdrożenia wyłącznie w autoryzowanym zakresie.
- [ ] OAuth2/XSUAA lub IAS według konfiguracji; zarządzane sekrety, service bindings i Destination.
- [ ] Restrykcyjny CORS, limity wejścia, bezpieczne błędy i autoryzacja API.
- [ ] Testy worker/outbox, idempotencji i konkurencji przy restartach oraz wielu instancjach CF; monitoring i audyt bez sekretów.
- [ ] Trwały magazyn XML/UPO, rozdział TEST/DEMO/PROD i procedura rekoncyliacji.

Podstawy trwałości, idempotencji i autoryzacji powstają przed wysyłką w Phase 2–4; ten etap sprawdza je w docelowej infrastrukturze. BTP Trial nie jest środowiskiem produkcyjnym. Batch, zakupy, korekty, offline i multi-NIP pozostają poza pierwszym vertical slice.
