---
title: "CAP-04 Hardening — transakcje, odzyskiwanie, integralność"
date: 2026-10-29
type: journal
status: zakonczony
tags: [ainvoicer, cap-java, rap, outbox, hardening, security, integrity]
---

# CAP-04 Hardening

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [ADR-0003](../decisions/ADR-0003-cap-sap-result-writeback.md) · [Przewodnik: dostęp zapisu, DCL, integralność](../guides/SAP_WRITE_ACCESS_AND_READ_POLICY.md)

## Date

2026-10-29 (Europe/Warsaw).

## Branch

`java-cap-ksef`, HEAD `e7cbe0c`, drzewo początkowo czyste. Bez commit i push.

## Goal

Audyt i poprawki pięciu problemów CAP-04B/C przed CAP-04D. Bez HTTP WRITE do H01, KSeF, zmian IAM/scenariuszy komunikacyjnych i bez abapGit Pull.

## Ograniczenia sesji

- **MCP ABAP FS nie połączył się (`ECONNREFUSED`).** Stan H01, porównanie H01 ↔ Git, IAM, scenariusze i DCL: **NOT VERIFIED**. Wnioski o ABAP pochodzą z lektury eksportu Git (`525e48d`), nie z runtime; nie zmieniano żadnego obiektu SAP.
- Na maszynie nie było Mavena. Pobrano Apache Maven 3.9.16 z Maven Central do `~/tools` (poza repo), SHA-512 zgodne.
- **Baza referencyjna nie była 46/46:** `SapResultOutboxRecoveryTest` padał deterministycznie (3/3 przebiegi) na niezmienionym HEAD — asercja pustej kolejki wykonywana tuż po `SUCCEEDED`, zanim CAP usunie wiadomość. To wyścig w teście, nie wyciek: po dodaniu oczekiwania na opróżnienie kolejki test przechodzi. Wcześniejszy raport 46/46 nie jest odtwarzalny w tym środowisku.

## Potwierdzenie problemów

| Problem | Czy występował | Dowód |
| --- | --- | --- |
| P1 HTTP pod blokadą | **Tak** | Sonda na kopii HEAD: inna sesja H2 widziała `PENDING`, a `SELECT … FOR UPDATE` wiersza zlecenia kończył się lock timeout (kod 50200) podczas HTTP. |
| P1 wyczerpanie retry | **Tak** | Rzeczywisty collector CAP 5.2 z `maxAttempts=2`: dokładnie 2 próby, wiadomość zostaje z `attempts=2`, `status=null`, nie jest już dostarczana; polecenie zostaje `PENDING`. |
| P2 powiązanie z dokumentem | **Tak** | `expectedVersion` przychodziło argumentem `enqueue`; nic nie wiązało numeru z treścią XML. Dodatkowo (z lektury BDEF) pozycje mają własny ETag, więc wersja nagłówka nie obejmuje zmian pozycji — NOT VERIFIED w runtime. |
| P0 zapis (ACTVT 16) | **Nie znaleziono luki w kodzie** | Lektura `ZBP_R_AINV_INV_H`: globalnie `01/02/06/16`, instancyjnie NIP+spółka, prechecki, kontrola zgodności nagłówka z parametrami (kierunek, spółka, `SellerNip`, UUID, TEST) w akcji wewnętrznej. Wyniki EML 18/18 z CAP-04B są historyczne; w tej sesji nie powtarzano. Brak nadania praw i test rzeczywistym principalem: **NOT VERIFIED**. Autoryzacja create-by-association dla pozycji opiera się na domyślnym zachowaniu RAP (journal CAP-04B: odmowa dla prawa tylko 16); przypadek „prawo 02, obcy NIP” nie ma testu — do dodania. |
| P0 DCL | **Tak** | `where TRUE` na root; ACTVT 03 nieużywane. Implementacja wstrzymana — patrz przewodnik, wymagana decyzja. |
| P1 LIVE WRITE | **Tak (brak testu)** | Brak testu commit/ETag/równoległości; przygotowano bramkowany test LIVE (nie uruchomiony). |

## Implementacja (CAP Java)

### Przetwarzanie w trzech krótkich transakcjach

`SapKsefResultWriteback.process`: **(A)** atomowy claim — warunkowy UPDATE `state/attempts` z tokenem i dzierżawą (`PROCESSING`, `leaseUntil`), commit; **(B)** HTTP do SAP bez żadnej blokady wiersza zlecenia; **(C)** finalizacja warunkowana tokenem. Fazy A i C działają w osobnym `RequestContext`/ChangeSet, więc commitują niezależnie od transakcji wiadomości outboxa (potwierdzone testem, patrz niżej). Wyjątek bezpiecznego kodu zachowuje dotychczasowy sposób retry CAP; nieoczekiwany wyjątek jest zamieniany na `WRITE_UNEXPECTED_ERROR` (bez treści) i zwalnia przejęcie.

- Dwa równoległe workery/podwójna dostawa: jeden claim wygrywa; drugi dostaje `WRITE_CLAIM_ACTIVE` (wiadomość zostaje w kolejce, bo właściciel mógł paść).
- Awaria między A i C: po wygaśnięciu dzierżawy ten sam payload i `WriteRequestId` są wysyłane ponownie; SAP odpowiada `ALREADY_APPLIED`. Spóźniona finalizacja po przejęciu przez innego workera nic nie nadpisuje (`CLAIM_LOST`).
- Limit prób HTTP (`ainv.sap-write.max-attempts`, domyślnie 5) liczy aplikacja; po nim `RETRY_EXHAUSTED` i wiadomość jest konsumowana. Limit CAP podniesiono do 12 jako margines.
- Restartowy sygnał `wakeCollector` obejmuje teraz także `PROCESSING`.

### Stany

`PENDING`, `PROCESSING` (nowy: realny stan przejęcia), `SUCCEEDED`, `RECONCILIATION_REQUIRED`, `RETRY_EXHAUSTED` (nowy: wiarygodnie wykrywalny, bo liczymy próby sami). Stan „osierocony” nie jest osobnym statusem, tylko wynikiem zapytania: `PROCESSING` po terminie dzierżawy lub `PENDING` bez postępu dłużej niż `ainv.sap-write.stale-after` (10 min).

### Obsługa operacyjna (`SapKsefResultWriteRecovery`)

Wyłącznie API Java, bez endpointu HTTP/OData. `needingAttention()`, `status()`, `resume(id, kod)`. Rozróżnienie przyczyn po bezpiecznym kodzie: konflikt domenowy i niespójna odpowiedź **nie** są wznawialne (wymagają rekoncyliacji); awaria/odmowa SAP, wyczerpanie i osierocenie — tak. Wznowienie przed `retryNotBefore` (Retry-After) jest odrzucane. Wznowienie zeruje budżet prób, zachowuje payload/ID, zapisuje `RESUMED:<kod>` i publikuje nową wiadomość; CAS na stanie i liczniku. Nie istnieje ścieżka do KSeF.

### Integralność dokumentu

Patrz przewodnik. Zmiana kontraktu: `enqueue(operationId, companyCode, expectedVersion)` → **`enqueue(operationId)`**; spółka i wersja z migawki, wymagany `snapshot` i zgodny `confirmedXmlSha256`. Wcześniej brak wywołujących w kodzie produkcyjnym, więc wpływ ogranicza się do testów. Dodano `@assert.unique` `(environment, ksefNumber)` dla `SapResultWrites`. Odczyt pozycji z SAP zawiera teraz `LocalLastChangedAt` (niezweryfikowane LIVE; kolumna istnieje w EDMX).

## Zmienione pliki

- `cap-java/db/schema.cds`, `srv/ksef-service.cds` (wykluczenie nowych pól z publicznej projekcji), `pom.xml` (tag `live-sap-write` domyślnie wykluczony), `srv/src/main/resources/application.yaml`.
- Nowe: `SapKsefResultWriteRecovery`, `KsefInvoiceSnapshotRegistry`, `SapWritebackConfiguration` (Clock).
- Zmienione: `SapKsefResultWriteback`, `SapInvoiceRemoteAdapter` (kolumna pozycji).
- Testy: nowe `SapKsefResultWritebackHardeningTest` (15), `SapKsefResultSapContractMockTest` (8), `SapResultOutboxExhaustionTest` (1), `SapKsefResultWriteLiveWriteTest` (4, wyłączony), `WritebackFixtures`; dostosowane `SapKsefResultWritebackTest`, `SapResultOutboxRecoveryTest`, `KsefServiceODataIntegrationTest`.
- Obiekty SAP: **brak zmian**.

## Tests & Results

`mvn clean verify -Dcds.cds.skipVersionCheck=true` (JDK 25.0.4.1, Maven 3.9.16): **71 testów, 0 failures, 0 errors, BUILD SUCCESS** (obejście kontroli wersji CDS nadal wymagane). Testy live domyślnie wykluczone. Osobny przebieg z `-Dainv.excludedGroups=none` bez zmiennych `AINV_H01_*`: test LIVE WRITE **4/4 pominięte**, brak ruchu sieciowego.

Charakter dowodów:

- **H2 + mock RemoteService/HTTP:** brak blokady wiersza podczas HTTP i zacommitowane przejęcie widoczne z niezależnego połączenia; jeden zwycięzca przy 4 równoległych claimach; odzyskanie po „awarii procesu” po wygaśnięciu dzierżawy; spóźniona finalizacja; wyczerpanie → wznowienie z tym samym payloadem; Retry-After; podział przyczyn; osierocony PENDING; integralność i unikalność; public `$metadata` bez nowych pól.
- **Symulator kontraktu ADR-0002 (MOCK, nie SAP):** pierwszy zapis, utrata odpowiedzi po skutecznym zapisie → ALREADY_APPLIED bez zmiany operacji KSeF, trzy konflikty, dwa równoległe żądania HTTP (jeden zwycięzca), odmowa zakresu, brak użycia PATCH/PUT/DELETE.
- **Real collector CAP:** zachowanie po wyczerpaniu limitu i wznowienie.
- **LIVE SAP H01 WRITE: NIE WYKONANO.** Pozycje 2 (niezależny GET po commit), 4 (ETag bez zmiany po no-op), prawa principala i odmowa CRUD są weryfikowalne wyłącznie testem LIVE.

## Problems Encountered

- Mockito: `when(adapter.apply(...))` na już ustawionym mocku wywołuje stub rzucający wyjątek — użyto `doAnswer().when()`.
- Zerwane połączenie CAP 5.2 raportuje jako `SAP_HTTP_500` (ponawialne), nie `SAP_TRANSPORT_UNKNOWN`.
- Symulator zwracający puste łańcuchy zamiast `null` dla brakującego wyniku dawał `SAP_INVALID_RESULT`; poprawiono symulator, adapter bez zmian.

## Decisions Made

Zaktualizowano [ADR-0003](../decisions/ADR-0003-cap-sap-result-writeback.md) (claim/finalize, `RETRY_EXHAUSTED`, migawka dokumentu). ADR-0002 bez zmian.

## Current State / Remaining Risks

- Nie dowiedziono: persisted commit/ETag, równoległości w SAP, praw communication user, HANA, wielu instancji CF, twardej awarii JVM w trakcie HTTP (symulowana wyjątkiem `Error`).
- Lease domyślnie 2 min; HTTP bez ustawionego twardego timeoutu w CAP — dłuższe wywołanie może skutkować drugim wywołaniem SAP (bezpieczne dzięki idempotencji).
- Nie ma jeszcze interfejsu operacyjnego dla `resume` (tylko API Java) ani producenta migawek/potwierdzeń.
- Hash KSeF: format niezweryfikowany. Drift pozycji nie jest jeszcze wywoływany przed wysyłką.
- Ryzyko ABAP (NOT VERIFIED): ETag pozycji niezależny od nagłówka; autoryzacja CBA oparta na domyślnym zachowaniu RAP.

## Next Steps

1. Decyzje: polityka odczytu DCL (wariant A), model nadania principalowi zapisu (NIP/CompanyCode), czy `resume` ma mieć chroniony interfejs operacyjny, `total etag` w BDEF.
2. Odtworzyć połączenie MCP, powtórzyć porównanie H01 ↔ Git i testy EML, dodać test „02 + obcy NIP + CBA”.
3. CAP-04D: po osobnej zgodzie — nadanie 16, dedykowane faktury, uruchomienie `SapKsefResultWriteLiveWriteTest`.
