---
title: "ADR-0002 — zapis potwierdzonego wyniku KSeF przez RAP Action"
date: 2026-10-28
type: adr
status: zaakceptowany
tags: [ainvoicer, architektura, abap, rap, ksef, authorization]
---

# ADR-0002 — kontrakt ApplyKsefResult

[Project Home](../README.md) · [STATUS](../STATUS.md) · [Dowody CAP-04B](../journal/2026-10-28-cap-04b-rap-write-contract.md)

## Context

CAP Java jest właścicielem procesu KSeF, retry, operacji, ich idempotencji i historii. SAP ma przechować jedynie potwierdzony wynik dla faktury. Zwykły PATCH wymagałby otwarcia pól technicznych dla ogólnego UPDATE i utrudniałby kontrolę niezmienności wyniku.

Użytkownik zaakceptował wariant B — RAP Action, cztery pola nagłówka, zakres OUTBOUND/TEST oraz brak osobnej encji integracyjnej. Zlecił sprawdzenie blokowania i autoryzacji przed implementacją. Zakres nadania uprawnień pozostawił pusty; testy pozytywne mają używać kontrolowanej polityki testowej.

## Decision

Publiczna statyczna `ApplyKsefResult` przyjmuje UUID faktury, request ID, numer KSeF, TEST, NIP, CompanyCode i oczekiwany `LocalLastChangedAt`. Obsługuje jedną fakturę na wywołanie. Po walidacji i jawnej kontroli prawa wykonuje wewnętrzną instancyjną `ApplyKsefLocked`. Framework blokuje managed root; handler odczytuje nagłówek, potwierdza zakres, sprawdza wynik/idempotencję, następnie wersję i ewentualnie aktualizuje cztery pola w tej samej RAP LUW. Brak commit i zewnętrznych efektów w handlerze.

Pola `KsefNumber`, `KsefEnvironment`, `KsefRecordedAt`, `KsefWriteRequestId` są readonly dla standardowego CREATE/UPDATE. `InvoiceStatus` nie jest zmieniany. SAP nadaje timestamp zastosowania wyniku.

`Outcome` rozróżnia `APPLIED`, `ALREADY_APPLIED`, `VERSION_CONFLICT`, `IDEMPOTENCY_CONFLICT`, `RESULT_CONFLICT`. Identyczny numer/środowisko zwraca no-op również dla nowego request ID; zachowuje pierwotny request ID i timestamp. Ten sam request z innym wynikiem jest konfliktem idempotencji. Istniejący inny wynik nie jest nadpisywany. Brak wyniku pozwala na zapis tylko przy zgodnej niepustej wersji.

Konflikty domenowe nie są automatycznie błędami HTTP. Konsument musi badać `Outcome`, a po udanym zapisie pobrać nowy ETag przez GET. Nie dodajemy globalnej unikalności request ID/numeru ani historii do SAP.

`Z_AINV_KSF` łączy ACTVT, NIP i CompanyCode. ACTVT16 uprawnia wyłącznie do akcji. CRUD wymaga osobnych 01/02/06, kontroli instancji i prechecków chroniących zakres wejściowy/zmianę zakresu. Dzieci zachowują zależność od uprawnień rodzica. Każde użycie LOCAL MODE poprzedza jawna kontrola praw. Own authorization context nie jest nadaniem.

Uprawnienie pozostaje bez nadania zgodnie z decyzją użytkownika. Ewentualne późniejsze nadanie communication user wymaga authorizations właściwego communication scenario i zastosowania arrangement; dla business user obowiązuje ścieżka IAM. Istniejący szeroki odczyt DCL pozostaje odrębnym zagadnieniem, nie potwierdzono izolacji odczytu per NIP.

## Alternatives Considered

- **Publiczna akcja instancyjna:** prostsza orkiestracja i naturalny klucz w URI. Wymaga pogodzenia transportowej kontroli ETag z semantyką ponowienia po utracie odpowiedzi. Statyczne wejście pozwala rozstrzygnąć `ALREADY_APPLIED` przed aplikacyjną kontrolą starej wersji. Nie przeprowadzono porównawczych HTTP WRITE; nie uznajemy statycznej akcji za ogólnie lepszy wzorzec RAP.
- **Ogólny PATCH pól wyniku:** odrzucony ze względu na rozdzielenie praw do akcji i do edycji dokumentu oraz potrzebę jawnych konfliktów.
- **Osobna encja integracji SAP:** odłożona; CAP już posiada proces i historię, a bieżący zakres wymaga tylko potwierdzonego wyniku.

## Evidence and Limits

H01: prototyp 7/7, w tym kolejność blokady przy wewnętrznej akcji i rzeczywisty enqueue root odrzucony w odrębnej sesji do czasu rollback. Kontrakt EML 18/18 na nowych syntetycznych danych z rollback i kontrolowaną testową polityką praw. ATC dziesięciu obiektów kontraktu finalnie bez ustaleń; runtime EDMX HTTP200 ujawnia wyłącznie akcję publiczną i readonly pól.

Brak pełnego testu dwóch równoległych `ApplyKsefResult` z commit. Brak HTTP WRITE, persisted no-op/ETag, pozytywnego testu nadania principalowi komunikacyjnemu. Przesłanki blokowania wystarczyły do implementacji w Trial; nie zastępują końcowych testów CAP-04D. Szczegółowa macierz i obiekty są w dzienniku.

## Consequences

Dotychczasowi konsumenci zapisujący wymagają właściwych praw CRUD i zakresu; automatyczne rozszerzenie nadania nie następuje. Odczyt zachowuje istniejący kontrakt. Model zdalny i adapter CAP nadal są read-only, więc CAP-04C musi dopiero dodać wywołanie akcji, obsługę wyników, precyzyjny timestamp oraz trwałe przetwarzanie. CAP-04D wymaga osobnej zgody na testy HTTP WRITE i nadania testowych praw. Zmiany live SAP wymagają późniejszego kontrolowanego eksportu do Git.
