---
title: "CAP-04B — kontrakt zapisu wyniku KSeF w SAP RAP"
date: 2026-10-28
type: journal
status: zakonczony
tags: [ainvoicer, abap, rap, ksef, cap-04b, authorization, concurrency]
---

# CAP-04B — kontrakt zapisu wyniku KSeF

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [ADR-0002](../decisions/ADR-0002-sap-ksef-result-write-contract.md)

## Date

2026-10-28 (Europe/Warsaw).

## Branch

`java-cap-ksef`, początkowy HEAD `2ff6545cff38809be428d5c9ced94f7aa9fb8616`. Początkowo czyste drzewo. Bez commit, push, abapGit Pull i eksportu źródeł SAP do Git.

## Goal

Zaimplementować minimalną akcję zapisującą wyłącznie potwierdzony wynik OUTBOUND/TEST. Najpierw sprawdzić blokowanie root i kolejność kontroli wersji. CAP pozostaje właścicielem procesu, retry, historii i idempotencji operacji; SAP chroni zastosowanie wyniku do konkretnej faktury. `InvoiceStatus` pozostaje niezależny. CAP-04C, CAP-04D, HTTP WRITE oraz wywołania KSeF poza zakresem.

## Initial State

Odczytano AGENTS, kanoniczny Skill ABAP/RAP, STATUS, ROADMAP, ADR-0001, dzienniki i model RAP. Porównano źródła aktywne H01 z Git przed zmianami; lokalny eksport nie był pełnym obrazem live. H01 Trial, pakiet `ZAINVOICER`, `recordChanges=false`, ABAP FS MCP **2.13.3**. Nie tworzono requestów. Istniejące obce zmiany inactive oraz otwarty i zablokowany scenariusz komunikacyjny pozostawiono bez edycji.

Użytkownik zdecydował: **nowego uprawnienia nie nadawać; testy pozytywne wykonywać z kontrolowaną polityką testową**. Nie zmieniano IAM apps, communication scenarios ani arrangements.

## Implementation

### Etap 1 — blokowanie i wybór rodzaju akcji

Prototyp `ZR_AINV_LOCK_PROBE` / `ZBP_AINV_LOCK_PROBE` porównuje akcję instancyjną oraz statyczną delegującą do wewnętrznej instancyjnej. Instrumentowany handler LOCK potwierdził wywołanie blokady przed handlerem akcji również dla zagnieżdżonego EML `IN LOCAL MODE`; odmowa LOCK zatrzymuje handler. Publiczne wejście prototypu jest domyślnie wyłączone, nie jest publikowane w OData i nie zapisuje danych.

Osobny test używa released `CL_ABAP_PARALLEL` / `IF_ABAP_PARALLEL` i klasy `ZCL_AINV_LOCK_TASK`. Sesja nadrzędna wykonuje `SET LOCKS` na **rzeczywistym managed root `ZR_AINV_INV_H`**, na istniejącym syntetycznym rekordzie. Zadanie w odrębnym kontekście nie uzyskuje tej blokady. Po `ROLLBACK ENTITIES` nadrzędnej sesji kolejne zadanie ją uzyskuje. Test wyklucza przypadkowe wykonanie w tym samym kontekście. Nie zmienia rekordu, nie wykonuje commit.

**Wynik:** zapewniono wystarczającą podstawę do implementacji: wewnętrzna akcja instancyjna uruchamia standardowe blokowanie root; odczyt, sprawdzenie zakresu, idempotencja, CAS i aktualizacja następują w jej handlerze, pod blokadą, w jednej RAP LUW. Nie wykazano pełnego scenariusza dwóch równoległych wywołań `ApplyKsefResult` z zapisem i commit. Test dwóch sesji potwierdza enqueue, a test prototypu — kolejność orkiestracji. Nie deklarujemy pełnego testu współbieżnych zapisów E2E.

Pozostawiono wariant statyczny z wewnętrzną akcją instancyjną, ponieważ pozwala sprawdzić istniejący wynik **przed** odrzuceniem starej wersji. Identyczne ponowienie może zwrócić `ALREADY_APPLIED` z pierwotnym timestampem. Wariant instancyjny jest prostszy, lecz wiąże publiczny kontrakt z kontrolą wersji instancji w warstwie transportowej. Nie przeprowadzono porównawczych zapisów HTTP obu wariantów. Szczegóły kompromisu: ADR-0002.

### Zmienione istniejące obiekty SAP

| Typ | Obiekt | Zmiana |
| --- | --- | --- |
| TABL | `ZAINV_INV_H` | Cztery pola wyniku; edycja definicji live ADT, bez ręcznej edycji abapGit XML |
| DDLS | `ZR_AINV_INV_H`, `ZC_AINV_INV_H` | Pola w interface i projection |
| BDEF | `ZR_AINV_INV_H` | Akcje, readonly, mapowanie, global + instance authorization, prechecks CREATE/UPDATE, own authorization context |
| BDEF | `ZC_AINV_INV_H` | `use action ApplyKsefResult` |
| CLAS | `ZBP_R_AINV_INV_H` | Lokalne handlery, kontrola uprawnień oraz test include |

### Nowe obiekty SAP

| Typ | Obiekt | Rola |
| --- | --- | --- |
| DDLS abstract | `ZA_AINV_KSF_REQ`, `ZA_AINV_KSF_RES` | Parametry i wynik akcji |
| DTEL | `ZAINV_NIP`, `ZAINV_BUK` | Własne typy CHAR10 / CHAR4 dla pól autoryzacji |
| AUTH | `ZAINV_NIP`, `ZAINV_BUK` | Pola autoryzacji zakresu |
| SUSO | `Z_AINV_KSF` | `ACTVT` + NIP + CompanyCode |
| SIA3 | `Z_AINV_KSF` | Towarzyszące mapowanie aktywności; potwierdzone w serializacji abapGit, bez nadania użytkownikowi |
| DDLS / BDEF / CLAS | `ZR_AINV_LOCK_PROBE` (dwa typy), `ZBP_AINV_LOCK_PROBE` | Niepublikowany prototyp kolejności blokowania |
| CLAS | `ZCL_AINV_LOCK_TASK` | Test rzeczywistej blokady root w odrębnej sesji |

### Kontrakt `ApplyKsefResult`

Publiczna akcja jest statyczna, związana z kolekcją root w OData. Jedno polecenie / jedna faktura na wywołanie; masowy EML EXECUTE jest odrzucany. Wewnętrzna `ApplyKsefLocked` nie występuje w publicznym EDMX.

| Parametr | Typ ABAP | Reguła |
| --- | --- | --- |
| `InvoiceUUID` | `sysuuid_x16` | Wymagany istniejący root |
| `WriteRequestId` | `sysuuid_x16` | Wymagany identyfikator zapisu CAP |
| `KsefNumber` | CHAR50 | Niepusty, znaki `[0-9A-Za-z-]`; bez walidacji autentyczności wyniku KSeF |
| `Environment` | CHAR4 | Wyłącznie `TEST` |
| `TaxpayerNip` | CHAR10 | Dziesięć cyfr; zgodny z `SellerNip` dla OUTBOUND i z uprawnieniem |
| `CompanyCode` | CHAR4 | Niepusty; zgodny z nagłówkiem i z uprawnieniem |
| `ExpectedLocalLastChangedAt` | `abp_locinst_lastchange_tstmpl` | Wymagany, porównanie dokładnej wartości po blokadzie |

Akcja nie sprawdza sumy kontrolnej NIP ani protokołowej poprawności numeru KSeF. SAP przyjmuje potwierdzony wynik od zaufanego i autoryzowanego CAP; proces potwierdzenia pozostaje w CAP. UUID i wersja są nullable w wygenerowanym CSDL, lecz handler odrzuca ich wartości initial/null.

Pola persystencji: `KsefNumber` CHAR50, `KsefEnvironment` CHAR4, `KsefRecordedAt` `abp_creation_tstmpl`, `KsefWriteRequestId` `sysuuid_x16`. Timestamp zapisu nadaje SAP. Wszystkie cztery są readonly dla zwykłego CREATE/UPDATE. Akcja aktualizuje wyłącznie te pola.

Wynik `ZA_AINV_KSF_RES` zawiera `Outcome`, `InvoiceUUID` i cztery pola zapisanego wyniku. Nie zawiera ETag po commit; po skutecznym zapisie konsument musi ponownie odczytać root.

Kolejność po autoryzacji i sprawdzeniu zgodności nagłówka:

| Warunek | Outcome | Mutacja |
| --- | --- | --- |
| Ten sam zapisany request ID, ale inny numer lub środowisko | `IDEMPOTENCY_CONFLICT` | Brak |
| Ten sam numer i środowisko, także przy nowym request ID | `ALREADY_APPLIED` | Brak; zachowanie pierwotnego request ID i timestampu |
| Inny zapisany wynik lub częściowo wypełnione metadane bez numeru | `RESULT_CONFLICT` | Brak |
| Brak wyniku, wersja nagłówka różna od oczekiwanej | `VERSION_CONFLICT` | Brak |
| Brak wyniku, wersja zgodna | `APPLIED` | Aktualizacja czterech pól w buforze RAP |

Konflikty domenowe są poprawnymi odpowiedziami akcji z jednoznacznym `Outcome`, mogą mieć HTTP 2xx. CAP musi interpretować wynik, a nie sam kod HTTP. Błędne wejście, odmowa uprawnień i niepowodzenie blokady/wykonania korzystają z `failed`/bezpiecznych komunikatów. Opakowanie statyczne nie ujawnia danych nieautoryzowanego nagłówka. `APPLIED` z EML nie dowodzi commit; wynik HTTP należy uznać dopiero po poprawnym zakończeniu całego żądania/changesetu.

`WriteRequestId` służy do rozpoznania powtórzenia na danej fakturze. Nie dodano globalnego UNIQUE request ID ani numeru KSeF. Globalna idempotencja procesu i historia należą do CAP.

### Autoryzacja i istniejący konsumenci

`Z_AINV_KSF`: ACTVT `16` dla akcji, `01` CREATE, `02` UPDATE, `06` DELETE. Pola zakresu `ZAINV_NIP` i `ZAINV_BUK` sprawdzane łącznie. Globalny handler bada czynność, instance handler bada rzeczywisty zakres nagłówka. CREATE precheck bada wejście; UPDATE precheck bada zakres przed zmianą i po zmianie, także zmianę kierunku i NIP. Dzieci pozostają authorization dependent od rodzica; testy sprawdziły odmowę CBA oraz UPDATE/DELETE dzieci dla prawa tylko `16`.

Przed każdym zastosowanym `IN LOCAL MODE` jest jawna kontrola odpowiedniego uprawnienia. Akcja sprawdza zakres wejścia, a następnie pod blokadą zgodność z rzeczywistym root. Local mode służy wewnętrznej aktualizacji pól readonly po kontroli, nie jest publicznym obejściem uprawnień. Nie włączono privileged mode.

Standardowy CRUD jest teraz domyślnie odmawiany bez nowych praw. **Dotychczasowi konsumenci zapisujący muszą otrzymać odpowiednie 01/02/06 i zakres; samo 16 ich nie zastępuje.** Nie nadawano tych praw automatycznie. Odczyt istniejącej usługi zachowano; root DCL `where TRUE` nie daje izolacji odczytu per NIP. CAP-04B nie rozwiązuje tej osobnej luki polityki odczytu.

Zweryfikowano dostępność własnych AUTH/DTEL/SUSO w H01 oraz kompilację `AUTHORITY-CHECK` w ABAP Cloud. Dokumentacja SAP rozróżnia nadanie dla business user przez IAM app/catalog/role oraz dla communication user przez authorizations w communication scenario i arrangement. `define own authorization context` dokumentuje obiekt, nie nadaje prawa. Binding default authorization values można synchronizować z own context; samo to nie jest testem rzeczywistego nadania. Na prośbę użytkownika nie zmieniano tych konfiguracji i nie wykonano pozytywnego testu pod komunikacyjnym principalem.

## Changed Files

Kod ABAP zmieniono wyłącznie live SAP. `abap/src/` nie zawiera jeszcze eksportu CAP-04B. Lokalne zmiany obejmują ten dziennik, ADR-0002, STATUS, ROADMAP, README oraz aktualizację przewodnika RAP. CAP Java, SAPUI5, Node.js i Python bez zmian.

## Commands Executed

- Git: `git branch --show-current`, `git status --short`, inspekcja HEAD i źródeł przed zmianami.
- MCP: discovery/creation diagnostics, active/inactive source reads, blokady i status transportu, tworzenie obiektów z jawnym `packageName=ZAINVOICER`, zapis/replace źródeł, diagnostyka, aktywacja wybranych zależności.
- MCP `abapfs_run_unit_tests`: `ZBP_AINV_LOCK_PROBE`, `ZBP_R_AINV_INV_H`; test prototypu powtórzony po finalnej zmianie autoryzacji.
- MCP ATC `ABAP_CLOUD_DEVELOPMENT_DEFAULT` dla dziesięciu obiektów kontraktu; po poprawkach powtórzono tylko klasę i oba DTEL.
- Uwierzytelnione HTTP **GET** rzeczywistego `$metadata` i nagłówków przez istniejącą lokalną konfigurację; poświadczenia użyte wyłącznie w pamięci procesu, bez zapisu w repo lub wyświetlenia.
- Kontrola dokumentacji: `git diff --check`, walidacja lokalnych linków i frontmatter zmienionych plików.

## Tests & Results

| Sprawdzenie | Wynik i granica dowodu |
| --- | --- |
| Aktywacja / syntax | PASS dla zmienionych/nowych obiektów; końcowa lista inactive ograniczona do obiektów zadania pusta |
| ATC | 10 obiektów kontraktu: finalnie 0 errors, warnings, infos, exemptions; nie jest to ATC całego pakietu ani prototypów/AUTH/SUSO |
| Prototyp blokowania | **7/7 PASS**, w tym rzeczywisty root enqueue w odrębnej sesji, bez zapisu |
| Kontrakt EML | **18/18 PASS**, syntetyczne nowe faktury w buforze, polityka `TEST-INJECTION`, na końcu rollback |
| Pierwszy wynik | `FIRST_APPLY` PASS; wartości czterech pól w buforze |
| Identyczne ponowienie | `SAME_REQUEST_IS_NOOP` / `SAME_RESULT_NEW_REQUEST` PASS; porównanie całego nagłówka, brak UPDATE i zmiany timestampów w buforze |
| Konflikty | `CHANGED_REQUEST_CONFLICTS`, `DIFFERENT_RESULT_CONFLICTS`, `STALE_VERSION` PASS |
| Autoryzacja / zakres | NIP, CompanyCode, rzeczywisty zakres, INBOUND, PROD, brak prawa, brak wersji, zmiana zakresu przez CRUD — wszystkie PASS |
| Ochrona CRUD/dzieci | Prawo tylko 16 nie pozwala na root CRUD, CBA Items, UPDATE/DELETE Items/References — PASS |
| Readonly czterech pól | `GET PERMISSIONS` potwierdza read-only dla wszystkich; statyczny niedozwolony UPDATE odrzucony przez kompilator. Dynamiczna negatywna próba EML spowodowała oczekiwane `BEHAVIOR_READONLY_FIELD`; nie pozostawiono celowo dumpującego testu w finalnej suite |
| Rzeczywisty OData EDMX | HTTP **200**, XML; publiczna statyczna akcja, parametry, typ wyniku i `Core.Computed` wszystkich czterech pól potwierdzone; wewnętrzna akcja niewystawiona |
| Odczyt root po testach | 4 istniejące nagłówki, 0 zapisanych numerów KSeF, wszystkie 4 ETagi zachowane względem odczytu przed testami |
| Zapis HTTP / commit / równoległe zapisy akcji | **NIE WYKONANO**; CAP-04D wymaga osobnej zgody |
| Rzeczywiste nadanie principalowi komunikacyjnemu | **NIE WYKONANO**, świadomie bez nadania |

Fixture transient nie dostaje automatycznego `LocalLastChangedAt` przed save. Wyłącznie dla nowej faktury testowej, po jawnej kontroli testowego prawa UPDATE, ustawiono timestamp w buforze; potem testowana akcja dostaje niepustą wersję. Testy potwierdzają logikę CAS i brak mutacji, nie generację persisted ETag ani jego niezmienność po powtórnym HTTP żądaniu.

## Problems Encountered

- Propagacja `%fail-cause=locked` przez statyczny handler narusza kontrakt RAP (`WRONG_FAIL_CAUSE`); opakowanie zwraca obecnie bezpieczne `unspecific`. Finalny test odrzucenia blokady PASS.
- Standardowe pole autoryzacji BUKRS nie było dopuszczone do własnego obiektu Cloud. Zastosowano własne AUTH/DTEL; bez osłabiania kontroli.
- Zapisy natywnych XML AUTH/SUSO/DTEL bywały zgłaszane jako niezgodne mimo przyjęcia przez SAP (kanonizacja metadanych). Weryfikowano semantykę niezależnym odczytem, nie ponawiano tworzenia w ciemno.
- Pierwszy ATC wykazał uwagi o EML w pętli i przestarzałych domenach CHAR10/CHAR4. Ograniczono kontrakt do jednego polecenia bez pętli EML, DTEL korzystają teraz z typów wbudowanych. Końcowe ATC 0 ustaleń, bez wyłączeń.
- Negatywny dynamiczny UPDATE readonly wywołał `BEHAVIOR_READONLY_FIELD` i błąd wrappera testów `toFixed`. Dump zidentyfikowano; finalna suite używa bezpiecznej kontroli permissions i przechodzi 18/18.
- Fallback komputerowy do Eclipse: odczyt accessibility częściowo działał, screenshot/capture kończył się timeoutem. Nie wykonywano edycji przez UI i nie zmieniano scenariusza komunikacyjnego.

## Decisions Made

[ADR-0002](../decisions/ADR-0002-sap-ksef-result-write-contract.md): statyczny kontrakt z wewnętrzną blokowaną akcją, jawne wyniki domenowe, CAS po blokadzie, prawa domyślnie bez nadania. Bez nowej encji integracyjnej, historii ani workflow KSeF w ABAP.

## Current State

CAP-04B zaimplementowany i aktywny w H01, z potwierdzeniem EML, ATC i read-only runtime metadata. Nie jest to zaliczenie HTTP WRITE/E2E ani całego testu współbieżnych zapisów. Brak eksportu abapGit jest świadomy: wymaga osobnego zlecenia. Nie rozpoczęto CAP-04C ani CAP-04D.

## Next Steps

1. Po osobnym zleceniu przejrzeć i wyeksportować różnice H01 → abapGit → `java-cap-ksef`, uwzględniając obiekty autoryzacji i prototypy; bez importu starego snapshotu do SAP.
2. CAP-04C: adapter CAP interpretujący `Outcome`, zachowanie identycznego request ID przy retry, precyzja wersji, trwały worker/outbox i historia po stronie CAP.
3. CAP-04D po zgodzie: ograniczone nadanie ACTVT16/NIP/CompanyCode, test odmów prawdziwym principalem, HTTP WRITE na uzgodnionych danych, commit/ETag/no-op i pełne równoległe wywołania akcji.

## Źródła techniczne

- [SAP — Action Definition](https://help.sap.com/docs/abap-cloud/abap-rap/action-definition): domyślne blokowanie akcji instancyjnej i ograniczenie internal.
- [SAP — Lock Definition](https://help.sap.com/docs/abap-cloud/abap-rap/lock-definition): managed root i lock dependencies.
- [SAP — Runtime Frameworks](https://help.sap.com/docs/abap-cloud/abap-rap/runtime-frameworks): orkiestracja blokad i kontroli przed wykonaniem żądania.
- [SAP — ETag Implementation](https://help.sap.com/docs/ABAP_PLATFORM_NEW/fc4c71aa50014fd1b43721701471913d/6598e64cdf4e40bd8563c879b0ac5cc5.html): automatyczna obsługa managed timestampu.
- [SAP — Authorization Basics, ABAP Environment](https://help.sap.com/docs/sap-btp-abap-environment/abap-environment/authorization-basics): własne obiekty, global/instance, kontekst i nadania komunikacyjne.
- [SAP — Access Management](https://help.sap.com/docs/abap-cloud/abap-cloud-background-concepts-and-overview/conceptual-background-access-management): odrębne ścieżki business i communication user.
- [SAP — Editing Authorization Default Values](https://help.sap.com/docs/btp/sap-business-technology-platform/editing-default-authorization-values): synchronizacja own context z wartościami domyślnymi bindingu.

## Kontynuacja — eksport po zgodzie użytkownika

Po zakończeniu powyższej implementacji użytkownik zlecił commit/push i przejście do CAP-04C. Dokumentację opublikowano jako `8de662f`. Po przeglądzie oraz osobnym zatwierdzeniu konkretnego review MCP wyeksportowano **31 plików** SAP jako `525e48d`. Potwierdzono remote refs, brak różnic CAP-04B w statusie abapGit oraz lokalny fast-forward. Wykluczono zastany scenariusz komunikacyjny i usunięcia niezwiązanej klasy. Nie wykonywano abapGit Pull do SAP. [Dalsze prace CAP-04C](2026-10-28-cap-04c-sap-result-writeback.md).
