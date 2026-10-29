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

`java-cap-ksef`, HEAD początkowy pierwszej sesji `e7cbe0c`, drzewo początkowo czyste. Pierwotna notatka „bez commit i push” opisywała moment sporządzenia raportu. Kontynuacja potwierdziła commit `dfd1ecb27690f9db24896d3028cbfe2d1d95a389` z 2026-10-29 13:05 +01:00: obejmuje tę samą pracę CAP-04 Hardening (kod, testy i dokumentację). Lokalny commit nie potwierdza stanu remote. Fix 2 rozpoczęto na `dfd1ecb` z czystym drzewem; w tej kontynuacji bez commit/push.

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

## Kontynuacja — Hardening Fix 2 i CAP-04D readiness

### Stan początkowy i zakres

2026-10-29, Europe/Warsaw. Branch `java-cap-ksef`, HEAD `dfd1ecb27690f9db24896d3028cbfe2d1d95a389`, drzewo początkowo czyste. Potwierdzono zawartość i metadane commita: to zakończenie pracy hardening opisanej powyżej; skorygowano wcześniejsze „bez commit”. W tej kontynuacji bez commit/push, zmian IAM/DCL/arrangement, HTTP WRITE do H01 i wywołań KSeF. Nie zmieniano UI5/Node/Python ani SDK KSeF.

Cel: potwierdzić trzy zgłoszone problemy, poprawić minimalnie kod i testy, wykonać pełną regresję oraz przygotować procedurę CAP-04D. Skills: `ainvoicer-cap-java`, `ainvoicer-abap-rap`; przeczytano AGENTS, STATUS/ROADMAP, ADR-0001/2/3 i przewodnik praw SAP.

### Przyczyny i implementacja

1. **FIXED + TESTED — CAS resume.** Inspekcja potwierdziła: osierocony PENDING po resume pozostawał PENDING z tym samym attempts; oba równoległe UPDATE mogły spełnić dawny predykat. `SapResultWrites.revision` (Integer64, NOT NULL, default 0) wzrasta przy każdym claim i resume. CAS porównuje ID, stan, attempts i revision. Każdy sukces zużywa wcześniejszą rewizję także przy PENDING → PENDING. Chroni to również przed odczytem starego PENDING sprzed pełnego cyklu workera. Nie bazujemy na precyzji timestampów. Dodatkowo porównujemy zaobserwowany leaseUntil, aby claim/resume nie nadpisał odnowienia. Null jest sprawdzany przez IS NULL.
2. Payload, WriteRequestId, InvoiceUUID, KsefNumber, expectedVersion nie są zmieniane przez resume. UPDATE, historia i outbox pozostają w jednym `inNewTransaction`/CAP ChangeSet. Awaria publikacji wycofuje wszystkie trzy elementy. Retry-After i aktywny claim blokują resume; biznesowy konflikt i invalid response są zabronione także przy niespójnym osieroconym stanie.
3. **FIXED + TESTED lokalnie / IMPLEMENTED / NOT LIVE TESTED w H01 — odmowa CRUD.** Dawne `assertThrows(RuntimeException.class)` potwierdzało jedynie błąd, również 404/500/CSRF. Nowy test wymaga zdalnego ODataResponseException HTTP403 i uzgodnionego kodu autoryzacji SAP z ODataServiceErrorException. CAP 5.2.0 opakowuje HTTP remote w 50012005; odczytujemy strukturalne cause/args. Brak poprawnego kodu w env odrzucany jest przed UPDATE. Przed testem kontrola numeru faktury, potem niezależny GET w finally i porównanie nagłówka/wersji. 2xx zawsze daje FAIL, bez DELETE.
4. **FIXED + TESTED lokalnie / IMPLEMENTED / NOT LIVE TESTED w H01 — timeout/lease.** Potwierdzono z bytecode lokalnych JAR-ów CAP 5.2.0/SDK 5.33.0 użycie HttpClientAccessor i Apache HttpClient 4; domyślnie connect/socket/pool po 120 s przy lease 120 s. `SapHttpClientFactory` dla destination AINV_H01: connect 5 s, pool 5 s, socket/read 20 s; automatyczne retry I/O wyłączone. Inne destinations zachowują konfigurację SDK.
5. **Brak twardego overall timeout.** HEAD CSRF i POST, DNS/auth/destination lookup, przekierowania i strumieniowanie nie mają jednego gwarantowanego deadline. Zastosowano odnowienie ważnego lease co 20 s (lease nadal 2 min) wyłącznie podczas adapter.apply, w krótkiej transakcji z tokenem i tenantem właściciela. Scheduler JDK odnawia tylko dzierżawę; dispatch/retry pozostaje w natywnym outbox. Odnowienie nie wskrzesza wygasłego lease i nie zmienia payloadu. Przerwa JVM/DB może dopuścić dwa trwające HTTP po wygaśnięciu: bezpieczeństwo opiera się wtedy na stabilnym WriteRequestId w SAP i tokenie finalizacji. Nie deklarujemy exactly-once HTTP.

Istniejąca trwała H2/HANA wymaga migracji nowej kolumny revision (BIGINT, backfill 0, NOT NULL). Nie migrowano ani nie usuwano bazy użytkownika; świeże H2 i restart tej samej wersji modelu pokryte testami.

### Testy lokalne

Końcowy pełny build: **82 testy PASS, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**, 46,603 s, zakończony 2026-10-29 13:54:47 +01:00. Tagi LIVE wykluczone domyślnie; zero skipped nie oznacza uruchomienia LIVE.

| Zestaw | Liczba PASS | Zakres |
| --- | --- | --- |
| SapKsefResultWritebackHardeningTest | 20 | współbieżne resume, rollback publikacji, claim/lease, odzyskanie, migawki |
| SapKsefResultWriteAdapterHttpTest | 15 | rzeczywisty CAP/SDK i localhost HTTP: akcja, CSRF, CRUD, timeout |
| SapHttpClientFactoryTest | 2 | connect/read/pool w wykonanym request context, zakres AINV_H01, walidacja konfiguracji |
| Pozostała regresja | 45 | OData/auth, READ, snapshot/hash, claim/finalize, recovery i rzeczywisty collector/restart H2 |

`concurrentResumeOfOrphanedPendingHasExactlyOneWinner`: wspólna CyclicBarrier startu oraz druga bariera po rzeczywistym SELECT tej samej rewizji z H2; dokładnie jeden sukces, jeden RESUME_CONFLICT, +1 wiadomość, jeden RESUMED, ten sam command, późniejszy worker SUCCEEDED. Zegar pozostaje stały podczas wyścigu; opóźnienia nie decydują o poprawności CAS. Osobno PASS resume RETRY_EXHAUSTED i wygasłego PROCESSING, odmowa aktywnego claim, Retry-After oraz SUCCEEDED/konflikt/invalid response.

CRUD mock: poprawna ścieżka usługi, CSRF i klucz sprawdzane przez serwer; 403 z oczekiwanym kodem przechodzi. 400/404/500, połączenie bez odpowiedzi, 403 z kodem CSRF i 200/204 nie przechodzą asercji autoryzacji. Zerwane połączenie powoduje jedną próbę PATCH, nie ukryte retry.

Timeout/lease: prawdziwy POST do localhost z opóźnieniem 700 ms przy read timeout 200 ms → błąd i PENDING, brak wiszącego claim; następny POST ma identyczny payload i kończy ALREADY_APPLIED. Scheduler odnowił dzierżawę przy blokującym adapterze; po pierwotnym terminie drugi worker nadal nie wywołał SAP. Po faktycznym wygaśnięciu lease pozostaje ten sam WriteRequestId. Spóźniona finalizacja starego workera nie nadpisuje wyniku nowego (CLAIM_LOST). Mock timeout i symulacja awarii procesu nie są dowodem LIVE H01.

Przebieg ukierunkowany przed pełnym buildem: 34/34 PASS. Podczas implementacji poprawiono: niejednoznaczną lambdę overload CAP, SQL porównujący NULL przez eq zamiast IS NULL, przekazywanie varargs w obserwatorze testowym Mockito (`getRawArguments`) oraz niepoprawny cast wrappera HttpClient SDK w teście konfiguracji (zastąpiony HttpClientContext rzeczywistego requestu). Jeden wadliwy przebieg z nieprawidłowym CAS zatrzymano przez zakończenie wyłącznie własnej JVM testowej. Historyczne błędy z wcześniejszych sekcji pozostają bez zmian; końcowy PASS dotyczy poprawionego kodu.

### SAP H01 — odczyty i blokady

**Częściowa inspekcja LIVE, nie pełna weryfikacja.** Udały się: metadane ZBP_R_AINV_INV_H i Z_AINV_KSF (istnieją, pakiet ZAINVOICER), active source sześciu DDLS root/projection/children i obu BDEF. Sześć CDS i root BDEF porównano z eksportem Git po normalizacji końców linii i spacji końcowych — zgodne. Projection BDEF również ujawnia ApplyKsefResult i dotychczasowe operacje. Aktywna deklaracja akcji została odczytana; aktywnej implementacji include nie odczytano. Obecność metadanych obiektu autoryzacji nie dowodzi jego aktywacji ani efektywnego nadania.

`abapfs_get_object_by_uri` (include), `abapfs_batch_get_lines`, resolve OData service i ponowna diagnostyka otrzymały od automatycznej kontroli: **„MCP tool call requires approval, but approval policy is never”**. Nie obchodzono ograniczeń i nie zmieniano polityki narzędzi. Przyczyną aktualnej luki jest blokada dostępu narzędzi, a nie potwierdzony ECONNREFUSED (ten jest historyczny).

**NOT VERIFIED:** behavior implementation active/inactive vs Git, live DCL root/dzieci, aktywacja/efektywne nadania Z_AINV_KSF, Communication Scenario/Arrangement, runtime metadata/dane przez MCP. ABAP Unit/EML **NOT TESTED**: bez odczytu inactive include nie można bezpiecznie użyć runnera, który domyślnie aktywuje nieaktywne zmiany. **Brak zmian w obiektach SAP.**

**BLOCKED:** wymagany test „globalne ACTVT02, brak prawa do NIP/spółki rodzica, CBA zabronione”. Przygotowano [szkic EML](../patches/cap-04-cba-scope-test.md) dla obu asocjacji, z kontrolą cause-unauthorized, braku mapped/zmian dzieci i rollback bez COMMIT. Nie został zapisany/aktywowany/uruchomiony w H01; nie uznawać go za PASS ani za dowód poprawności autoryzacji CBA.

CAP-03 READ lokalnie MOCK PASS. **CAP-03 LIVE READ NOT TESTED w tej sesji**: w procesie brak `destinations` oraz flag AINV_H01; nie pobierano credentiali z innych lokalizacji. Historyczny LIVE PASS nie jest nowym wynikiem. CAP-04D HTTP WRITE **NOT TESTED**, KSeF nie wywoływano.

### Pliki i wykonane polecenia

- Produkcja: `cap-java/db/schema.cds`; `srv/src/main/java/customer/cap_java/sap/` → `SapKsefResultWriteRecovery.java`, `SapKsefResultWriteback.java`, `SapWritebackConfiguration.java`, nowy `SapHttpClientFactory.java`; `srv/src/main/resources/application.yaml`.
- Testy: `SapKsefResultWritebackHardeningTest.java`, `SapKsefResultWriteAdapterHttpTest.java`, `SapKsefResultWriteLiveWriteTest.java`, nowe `SapCrudDenialAssertions.java`, `SapHttpClientFactoryTest.java` (ten sam pakiet pod src/test).
- Wiedza: STATUS, ROADMAP, README, niniejszy dziennik, ADR-0003 (doprecyzowanie mechanizmu lease), przewodnik praw SAP i szkic testu w docs/patches. `abap/src/` bez zmian.

Narzędzia: Temurin JDK **25.0.4.1**, Apache Maven **3.9.16** (`C:/Users/jkupi/tools/maven-dl/apache-maven-3.9.16/bin/mvn.cmd`, istniejący poza PATH), CAP Java **5.2.0**, Spring Boot **4.1.1**, Cloud SDK **5.33.0**. Nie instalowano nowych narzędzi ani zależności HTTP.

Wykonano: `git status --short`, `git branch --show-current`, `git rev-parse HEAD`, `git log -3 --oneline`, `git show --no-patch --format=fuller dfd1ecb`, `git diff --check`; odczyty plików i wyszukiwania rg; `java -version`, próba `mvn -version` (brak w PATH), inspekcję javap/jar lokalnych bibliotek. Maven z cap-java/: `clean verify -Dcds.cds.skipVersionCheck=true`; przebiegi ukierunkowane `verify -Dcds.cds.skipVersionCheck=true -Dtest=SapKsefResultWritebackHardeningTest,SapKsefResultWriteAdapterHttpTest -Dsurefire.failIfNoSpecifiedTests=false`. Po poprawkach wykonano końcowy pełny clean verify z wynikiem powyżej. Nie uruchamiano live-sap-write ani KSeF. Logi builda lokalne, ignorowane przez Git; wygenerowane target/src-gen nie są częścią zmiany.

Dokumentacja sprawdzona: [CAP Remote Services](https://cap.cloud.sap/docs/java/cqn-services/remote-services), [Event Queues](https://cap.cloud.sap/docs/java/event-queues), [Cloud SDK HttpClient](https://sap.github.io/cloud-sdk/docs/java/features/connectivity/http-client). Dokładne API i domyślne zachowanie dla używanych wersji potwierdzano też lokalnymi JAR-ami, a nie wyłącznie bieżącą dokumentacją WWW.

### Pozostałe blokery i następny krok

1. Odblokować zatwierdzony dostęp do include/DCL/runtime w H01; wykonać porównanie implementacji, aktywacji praw i EML CBA z rollback.
2. Właściciel decyduje o polityce READ/WRITE i dostarcza zakresy, konta, arrangement, kod odmowy RAP oraz dedykowane UUID/numer faktury. Jeden obecny RemoteService/destination nie rozdziela principalów; nie zmieniano tego bez decyzji.
3. Po osobnym zleceniu wykonać [dokładną procedurę CAP-04D](../guides/SAP_WRITE_ACCESS_AND_READ_POLICY.md#5-cap-04d--gotowość-po-fix-2): powtórzyć CAP-03 LIVE READ właściwym kontem, osobnym procesem wykonać negatywny UPDATE i niezależny GET, dopiero po PASS pierwszy ApplyKsefResult i jego GET/idempotencję/ETag. DCL, IAM i runtime HTTP WRITE pozostają osobnymi dowodami. **STOP po lokalnej implementacji, testach i raporcie.**

### Publikacja Git — zlecenie po raporcie

Po zakończeniu implementacji i raporcie 82/82 PASS użytkownik jawnie zlecił „commit + push”. Zgoda dotyczy opisanych 18 plików Hardening Fix 2 na `java-cap-ksef`; wcześniejsze „bez commit/push” opisuje etap przed tym zleceniem. Publikacja nie rozszerza zgody na SAP HTTP WRITE, IAM/DCL ani KSeF. Nie zmieniono kodu po weryfikacji poza komentarzem opisującym wersję transportu HTTP; wyniku testów nie uruchamiano ponownie dla samej publikacji Git.
