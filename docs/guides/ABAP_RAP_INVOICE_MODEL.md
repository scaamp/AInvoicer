---
title: "ABAP RAP — model faktur AInvoicer"
date: 2026-10-28
type: guide
status: aktywny
tags: [ainvoicer, abap, rap, cds, odata]
---

# ABAP RAP — model faktur AInvoicer

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Workflow abapGit](ABAPGIT_WORKFLOW.md)

## Kontekst

ABAP Environment jest wykorzystywany jako środowisko symulacyjne. Nie zakładaj tabel standardowych S/4HANA takich jak `ACDOCA`, `BKPF`, `BSEG`, `RBKP` czy `VBRK` ani rzeczywistych dokumentów FI/MM/SD. Poprzedni model `ZFI_ACDOCA` został porzucony jako docelowe źródło danych dla integracji CAP. Aktualny eksport ma własne tabele aplikacyjne.

Opis bazowy wynika z plików `abap/src/` w commicie `39ab869` (`abapGit init`, 2026-10-24), a patch kompozycji z `d3dedf4`. **2026-10-25 potwierdzono live aktywację 6 CDS, obu BDEF oraz SRVD w H01 przez MCP.** Runtime OData V4 potwierdzono 2026-10-27: `$metadata` oraz autoryzowany `$expand` zwróciły Header/Items/References, 4/9/6 i zgodne UUID. Raport użytkownika z 2026-10-26 podaje 6 DCL aktywnych, EML 4/4 PASS i ATC 3 nowych DCL: 0 ustaleń; wyników tych nie powtarzano w tym audycie. [Dowody sesji i porównanie](../journal/2026-10-24-abap-rap-composition.md#kontynuacja-live-przez-poprawione-mcp--2026-10-25).

## Tabele aplikacyjne

**Aktualizacja live H01, CAP-04B (2026-10-28):** `ZAINV_INV_H` zawiera dodatkowo `KsefNumber` CHAR50, `KsefEnvironment` CHAR4, `KsefRecordedAt` timestamp i `KsefWriteRequestId` UUID. Pola są wystawione w root interface/projection, readonly dla standardowego CREATE/UPDATE i zapisywane przez `ApplyKsefResult`. `InvoiceStatus` pozostaje niezależny. Zmiany wykonano wyłącznie w SAP, bez eksportu abapGit. Poniższa tabela opisuje wcześniejszy eksport.

| Tabela | Odpowiedzialność i pola widoczne w eksporcie |
| --- | --- |
| `ZAINV_INV_H` | Nagłówek faktury: UUID, numer, `OriginProcess`, `Direction`, `InvoiceKind`, `InvoiceStatus`, kod spółki, daty, NIP/nazwy stron, sumy netto/podatku/brutto i waluta; zawiera też lokalne pola audytowe RAP. |
| `ZAINV_INV_I` | Pozycje: UUID pozycji i UUID faktury, numer pozycji, materiał/opis, ilość/jednostka, cena jednostkowa, kwoty netto/podatku/brutto, stawka/kategoria VAT, waluta i pola audytowe RAP. |
| `ZAINV_INV_REF` | Powiązanie do źródła symulacji: UUID referencji i faktury, system/moduł źródłowy, rola referencji, numer dokumentu źródłowego, rok obrachunkowy, numer pozycji i pola audytowe RAP. |

Pliki tabel wskazują `SYSUUID_X16` jako typ kluczy UUID. Klucz nagłówka to `INVOICE_UUID`; klucz pozycji to `ITEM_UUID`; klucz referencji to `REF_UUID`. W tabelach pozycji i referencji `INVOICE_UUID` jest polem wymaganym, co wskazuje na logiczne powiązanie do nagłówka. Sam eksport tabeli nie dowodzi istnienia ani egzekwowania fizycznego klucza obcego.

## Warstwy RAP — stan live H01

1. **Persistence:** root CDS `ZR_AINV_INV_H` czyta z tabeli `ZAINV_INV_H`.
2. **Root entity:** `ZR_AINV_INV_H` udostępnia pola nagłówka, UUID jako klucz, semantykę kwot/waluty i audytu oraz kompozycje `_Items` i `_References`.
3. **Child interface CDS:** `ZR_AINV_INV_I` i `ZR_AINV_INV_REF` czytają odpowiednio z tabel pozycji i referencji. Każdy ma `association to parent ZR_AINV_INV_H` po `InvoiceUUID`; ilość ma jednostkę `UnitCode`, a kwoty pozycji odnoszą się do `CurrencyCode`.
4. **Projection:** `ZC_AINV_INV_H` zachowuje `_BaseEntity` i przekierowuje `_Items` oraz `_References` do projekcji dzieci jako `composition child`. Projekcje dzieci przekierowują `_Parent` do `ZC_AINV_INV_H` jako `parent`.
5. **Behavior:** root pozostaje managed, z `InvoiceUUID` managed numbering, lock master i ETag `LocalLastChangedAt`. Root umożliwia create-by-association dla obu dzieci. Dzieci mapują tabele `ZAINV_INV_I`/`ZAINV_INV_REF`, używają managed UUID `ItemUUID`/`RefUUID`, lock/authorization dependent by `_Parent` i nie deklarują standardowego `create`. Każde dziecko ma własny `etag master LocalLastChangedAt`: H01 odrzucił ETag dependent przy identycznej nazwie pola audytowego jak w root. Dzieci mogą być aktualizowane i usuwane. Nie dodano draft.
6. **DCL i autoryzacja:** role dzieci dziedziczą warunki od rodzica przez `_Parent`. Root DCL `where TRUE` nie zapewnia izolacji odczytu. CAP-04B wdrożył global + instance authorization i prechecks zapisu w `ZBP_R_AINV_INV_H`; metoda global authorization nie jest już pusta live. Wymagane nowe prawa `Z_AINV_KSF` pozostają bez nadania na prośbę użytkownika. Testy z kontrolowaną polityką nie zastępują testu rzeczywistego nadania komunikacyjnego.
7. **Usługa:** zachowano binding `ZAPI_AINV_INV_H_O4`; runtime MCP read-only 2026-10-27 potwierdził aktywną publikację, wersję `0001`, protokół OData V4, `$metadata` HTTP 200, entity sets trzech projekcji i nawigacje `_Items` / `_References`. Odczyt autoryzowany z `$expand` potwierdził 4 nagłówki, 9 pozycji, 6 referencji i UUID zgodne z seedem. Dostęp działał przez istniejący same-origin OAuth kontekst ABAP FS. Osobny live test CAP użył prawdziwego H01 hosta i lokalnej env Destination; CAP na Cloud Foundry z bound Destination Service nie został przetestowany.

Projekcje dzieci dziedziczą provider contract od root; jawne `provider contract TRANSACTIONAL_QUERY` w child z `redirected to parent` jest niedozwolone. Widoki pozycji zachowują `Semantics.quantity.unitOfMeasure: 'UnitCode'`, ale usunięto niedozwolone w view entities `Semantics.unitOfMeasure: true`. Reguła dziedziczenia kontraktu: [SAP ABAP Keyword Documentation](https://help.sap.com/docs/abap-cloud/abap-keywords/cds-ddl-provider-contract).

## Zakres i ograniczenia potwierdzone w repo

**Stan po CAP-04B:** szczegółowe listy starszych plików poniżej odnoszą się do wcześniejszej kompozycji. Obecnie H01 jest dalej przed Git: nowa akcja, cztery pola, implementacja/testy, typy i obiekty autoryzacji oraz prototypy nie zostały wyeksportowane. Nie odtwarzaj live SAP z lokalnego snapshotu. Pełna lista obiektów: [dziennik CAP-04B](../journal/2026-10-28-cap-04b-rap-write-contract.md).

- Commit `39ab869` zawierał wyłącznie RAP nagłówka. Lokalny patch 2026-10-24 dodaje źródła CDS, BDEF, DCL i service definition dla kompozycji.
- Patch nie zawiera serializowanych sidecarów metadata nowych CDS/DCL. Zmiany live wykonano przez MCP, bez eksportu abapGit, commita i push. `abap/src/` nadal zawiera starsze wersje czterech źródeł: `ZR_AINV_INV_I` DDLS, `ZC_AINV_INV_I` DDLS, `ZC_AINV_INV_REF` DDLS i `ZR_AINV_INV_H` BDEF; klasa testowa istnieje wyłącznie w SAP. Po walidacji potrzebny kontrolowany eksport, po osobnym zleceniu.
- Ponowny odczyt active potwierdził osiem źródeł CDS/BDEF; cztery pozostałe odpowiadają lokalnym źródłom po pominięciu whitespace i wielkości liter. Nie importować lokalnego patcha na nowszy stan SAP.
- Nie twórz nazw entity setów, URI ani kontraktu OData na podstawie nazw obiektów źródłowych. Wymagają odczytu aktywnego bindingu i `$metadata` w docelowym SAP.
- Metadane serializowanego bindingu zawierają `PUBLISHED=true`, ale wpis usługi wskazuje `RELEASE_STATE=NOT_RELEASED`. To rozbieżność eksportu, nie dowód publikacji lub dostępności runtime. Sprawdź rzeczywisty status w ADT.
- Eksport ani lokalny patch nie potwierdzają autoryzowanego odczytu danych ani stanu aktywacji. DCL root `where TRUE` i pusta metoda global authorization nie są dowodem izolacji tenantów/użytkowników.
- `ZCL_KSEF_HTTP` jest eksperymentalną pozostałością starszego podejścia ABAP → KSeF; to legacy, poza architekturą docelową. Nie traktuj jej jako produkcyjnego klienta KSeF ani nie usuwaj bez osobnego zadania.

## Scenariusze symulacyjne

Model ma wspierać wartości procesu źródłowego `FI_AP`, `FI_AR`, `MM_IV` i `SD_BILL`. To kody scenariuszy, a nie dowód istnienia podłączonych modułów lub rzeczywistych dokumentów FI/MM/SD. Szczegóły słowników dozwolonych wartości i walidacji należy potwierdzić w kodzie aktywnym; sama definicja tabel nie ustanawia wszystkich reguł biznesowych.

## Granica ABAP i CAP Java

ABAP przechowuje symulacyjne dane nagłówków, pozycji i referencji. Runtime OData V4 potwierdził wystawienie Header/Items/References; lokalny eksport abapGit jest starszym snapshotem i nie zastępuje stanu live H01. CAP Java ma konsumować zweryfikowany, autoryzowany Remote OData V4 przez CAP Java Remote Services/Destination. CAP pozostaje właścicielem operacji KSeF, statusów procesu, idempotencji, korelacji, audytu i integracji z oficjalnym Java SDK MF.

Nie używaj bezpośredniego JDBC/SQL do bazy ABAP i nie zakładaj, że encje zdalne łączą się lokalnymi SQL JOIN-ami z CAP CDS.

## CAP-04B — aktualny kontrakt zapisu

Publiczna statyczna `ApplyKsefResult` przyjmuje `InvoiceUUID`, `WriteRequestId`, `KsefNumber`, `Environment`, `TaxpayerNip`, `CompanyCode`, `ExpectedLocalLastChangedAt`. Wyłącznie jedna faktura OUTBOUND/TEST na wywołanie. Wewnętrzna instancyjna `ApplyKsefLocked` uruchamia blokowanie root, a pod blokadą sprawdza zakres, zapisany wynik i wersję. Wynik abstrakcyjny zawiera `Outcome`, UUID i cztery pola wyniku; rozróżnia `APPLIED`, `ALREADY_APPLIED`, `VERSION_CONFLICT`, `IDEMPOTENCY_CONFLICT`, `RESULT_CONFLICT`. Konflikty nie muszą być błędem HTTP. Po save konsument powinien ponownie odczytać root/ETag.

`Z_AINV_KSF` obejmuje ACTVT, własne pola NIP/CompanyCode. Akcja wymaga 16; standardowy CRUD osobnych 01/02/06 i kontroli zakresu. Dotychczasowi konsumenci zapisujący wymagają odpowiedniego nadania; nie rozszerzano praw automatycznie. Przed LOCAL MODE wykonywana jest jawna kontrola praw. Dzieci zachowują zależność autoryzacji od rodzica.

W H01 potwierdzono aktywację/syntax, 18/18 testów kontraktu EML, 7/7 prototypu, ATC dziesięciu obiektów kontraktu 0 ustaleń i rzeczywisty raw EDMX HTTP200 z publiczną akcją oraz czterema polami `Core.Computed`. Test między sesjami potwierdza rzeczywistą blokadę root. Brak HTTP WRITE, commit, persisted ETag/no-op, pełnych dwóch równoległych zapisów oraz nadania komunikacyjnego — testy pozytywne korzystają z polityki testowej i rollback. [Pełna macierz i ograniczenia](../journal/2026-10-28-cap-04b-rap-write-contract.md), [ADR-0002](../decisions/ADR-0002-sap-ksef-result-write-contract.md).

## Wcześniejsze potwierdzenia kompozycji i odczytu

- **Runtime OData H01 — LIVE PASS w potwierdzonym zakresie (2026-10-27):** `$metadata` HTTP 200, entity sets Header/Items/References, nawigacje `_Items`/`_References`, autoryzowany `$expand` 4/9/6 oraz zgodne UUID. Potwierdza endpoint i dane w danym kontekście uwierzytelnienia; nie jest testem izolacji DCL ani CAP na Cloud Foundry.
- **DCL i EML — raport wcześniejszej sesji (2026-10-26), niepowtórzony w audycie:** użytkownik przekazał wynik: 6 DCL aktywnych, `ZCL_AINV_RAP_COMP_TEST` 4/4 ABAP Unit PASS, ATC 3 nowych DCL: 0 ustaleń. Raport jest późniejszy niż dump `ACM_UNEXPECTED_VALUE` z 2026-10-25, wynikający z braku aktywnego DCL `ZR_AINV_INV_I`. Dump pozostaje problemem historycznym.
- **Ograniczenie dowodu:** lokalne `abap/src/` nie zostało zsynchronizowane po wszystkich zmianach live. Root DCL snapshot ma `where TRUE`, a `GET_GLOBAL_AUTHORIZATIONS` w eksporcie jest pusta. Nie wnioskuj z tego snapshotu o aktywnym stanie H01; nie deklaruj też skutecznej izolacji ani autoryzacji zapisu bez odczytu aktywnych reguł i testów negatywnych.
- **ATC:** raport 0 ustaleń dotyczy trzech nowych DCL; wcześniejsze 0 ustaleń głównego BDEF to osobny zakres. Nie jest to pełne ATC pakietu ani test bezpieczeństwa.
- **RAP UPDATE przez CAP:** NOT IMPLEMENTED; model i adapter CAP są read-only.
- **CAP na Cloud Foundry + bound Destination Service:** NOT TESTED; lokalny CAP LIVE z env `destinations` nie potwierdza tego wdrożenia.

Historyczne błędy tworzenia DCL (`Unsupported Media Type`) i testów EML (`ACM_UNEXPECTED_VALUE`, błąd wrappera `toFixed`) pozostają w [dzienniku kompozycji RAP](../journal/2026-10-24-abap-rap-composition.md) oraz [dzienniku ABAP FS v5](../journal/2026-10-26-abapfs-v5-instructions.md).

## Pozostałe weryfikacje

1. Przy kolejnej zmianie SAP sprawdzić w H01 aktualne active DCL, role i `GET_GLOBAL_AUTHORIZATIONS`; przetestować autoryzowane i nieautoryzowane odczyty/zapisy. `where TRUE` w eksporcie nie zapewnia izolacji.
2. Jeżeli wymagane, niezależnie powtórzyć ABAP Unit `ZCL_AINV_RAP_COMP_TEST` i ATC odpowiednim wariantem w H01. Raport 4/4 pozostaje relacją użytkownika z 2026-10-26.
3. Zaktualizować lokalny snapshot przez kontrolowany eksport abapGit po zatwierdzeniu zakresu; nie importować starszych plików do H01.
4. SAP action CAP-04B jest aktywna; CAP → RAP Write Adapter pozostaje niezaimplementowany (CAP-04C). HTTP WRITE/commit/ETag i pełne testy współbieżnych zapisów wymagają osobnej zgody CAP-04D.
5. CAP na Cloud Foundry z bound Destination Service pozostaje nieprzetestowany.

## Powiązane źródła

- [Eksport abapGit i workflow](ABAPGIT_WORKFLOW.md)
- [ROADMAP — Phase 3](../ROADMAP.md#phase-3--sap-remote-integration)
- [Dziennik pierwszego eksportu](../journal/2026-10-24-abapgit-rap-initial-export.md)
