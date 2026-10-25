---
title: "RAP Composition — Header, Items i References"
date: 2026-10-24
type: journal
status: w-trakcie
tags: [ainvoicer, journal, abap, rap, composition, odata]
---

# RAP Composition — Header, Items i References

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Model RAP](../guides/ABAP_RAP_INVOICE_MODEL.md)

**Korekta późniejszej diagnostyki (2026-10-24):** poniższe wcześniejsze założenie o koniecznym transporcie było błędne. Odczyt live potwierdził lokalny pakiet bez rejestrowania zmian; wyniki i ograniczenia opisuje [diagnostyka tworzenia CDS](#diagnostyka-tworzenia-cds-przez-abap-fs-mcp--2026-10-24). Wcześniejsza inspekcja i późniejsza diagnostyka to odrębne zakresy; historia nie dowodzi braku zapisów we wszystkich sesjach pomiędzy nimi.

## Date

2026-10-24 (Europe/Warsaw).

## Branch

`java-cap-ksef` — potwierdzony przez `git branch --show-current`.

## Goal

Rozszerzyć istniejący Managed RAP BO faktury o kompozycję pozycji i referencji, zachowując root, projekcje i binding. Nie zmieniać CAP Java, SAPUI5 ani integracji KSeF; bez commit/push/deploymentu.

## Initial State

- W repo istniał już lokalny patch źródeł CDS/BDEF/DCL/service definition przygotowany w toku tego zadania; na wejściu miał zmodyfikowane pliki śledzone i nowe źródła. Nie uznano go za aktywny stan SAP.
- Branch to `java-cap-ksef`. Nie wykonano resetu ani nadpisania lokalnych zmian.
- Aktywne obiekty SAP odczytano ponownie przez MCP zamiast zakładać, że eksport `abap/src/` odpowiada runtime.

## Implementation

- Odkryto dostępny zestaw narzędzi MCP ABAP FS. Połączenie `H01` odpowiada; lista skonfigurowanych systemów zawiera `H01`.
- Wyszukiwanie H01 znalazło root CDS `ZR_AINV_INV_H`, projection CDS `ZC_AINV_INV_H`, oba root behavior definitions oraz service binding `ZAPI_AINV_INV_H_O4`. Aktywne źródła root nie zawierały kompozycji. Wyszukiwanie nie znalazło child CDS ani child behavior.
- Odczytano aktywne definicje tabel `ZAINV_INV_H`, `ZAINV_INV_I`, `ZAINV_INV_REF`; ich klucze i pola odpowiadają źródłom wykorzystanym przez patch.
- MCP zgłosił nieaktywne DCL `ZR_AINV_INV_H` i `ZC_AINV_INV_H`, zapisane przez użytkownika `CB9980000000`. Nie odczytano/nie nadpisano ich treści, nie aktywowano ich i nie uruchomiono batchowej aktywacji.
- Zapytanie o transporty bieżącego użytkownika zwróciło zero otwartych requestów. Nie utworzono ani nie wybrano transportu.
- `get_sap_system_info` zwrócił `Unknown` / `N/A`, bez klienta i wydania. Tożsamość docelowego środowiska trzeba potwierdzić przed zapisem.
- W eksporcie root DCL nadal ma `where TRUE`, a globalna implementacja klasy root jest pusta. DCL dzieci z lokalnego patcha nie ustanawia więc izolacji użytkowników/podatników; API nie może być uznane za bezpieczne do użycia produkcyjnego.
- Nie zmieniono źródeł SAP przez MCP, nie utworzono obiektów w SAP i nie aktywowano kodu. Przyczyna: brak uzgodnionego transportu, brak otwartego transportu użytkownika oraz niepełne dane identyfikujące klienta/wydanie. Nieaktywnych DCL innego użytkownika nie dotykano.

## Changed Files

Lokalny patch obecny w drzewie roboczym:

- Zmodyfikowane źródła: `abap/src/zr_ainv_inv_h.ddls.asddls`, `abap/src/zc_ainv_inv_h.ddls.asddls`, `abap/src/zr_ainv_inv_h.bdef.asbdef`, `abap/src/zc_ainv_inv_h.bdef.asbdef`, `abap/src/zapi_ainv_inv_h_o4.srvd.srvdsrv`.
- Nowe źródła child CDS/DCL: `ZR_AINV_INV_I`, `ZR_AINV_INV_REF`, `ZC_AINV_INV_I`, `ZC_AINV_INV_REF` w plikach `.ddls.asddls` i `.dcls.asdcls`.
- Dokumentacja: `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/README.md`, `docs/guides/ABAP_RAP_INVOICE_MODEL.md` oraz ten dziennik.

Nie utworzono ręcznie sidecarów tabel ani service bindingu. Lokalne źródła nie są importem, zapisem ani aktywacją w SAP.

## Commands and Tools

- PowerShell w `D:\AInvoicer`: odczyt `AGENTS.md`, właściwego skillu, `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/README.md`, dziennika eksportu RAP i przewodnika RAP; `git status --short --branch`, `git branch --show-current`, `git diff` oraz inspekcja źródeł lokalnych.
- MCP ABAP FS, połączenie `H01`: `get_connected_systems`, `get_configured_systems`, `get_sap_system_info`, `search_objects`, `get_object_source` (active), `get_object_transport_status`, `manage_transports` (`get_user_transports`) i `activate_object` (`get_inactive_objects`). Operacje wyłącznie odczytowe.

## Tests & Results

| Sprawdzenie | Wynik |
| --- | --- |
| Inspekcja aktywnego root CDS/BDEF i bindingu H01 | PASS dla odczytu źródeł; bez zmian live |
| Wyszukanie child CDS/behavior w H01 | Brak obiektów; nie utworzono ich |
| Kontrola tabel H01 | PASS dla odczytu pól i kluczy trzech tabel |
| Kontrola inactive-object list | Dwa nieaktywne DCL root; pozostawione nietknięte |
| Transport / system info | Blokada zapisu: zero otwartych requestów; typ, wydanie i klient nieustalone |
| Lokalny diff whitespace | `git -c core.whitespace=cr-at-eol diff --check` PASS; uwzględniono CRLF repozytorium (`core.autocrlf=true`). Zwykły `git diff --check` oznacza CR końca linii jako trailing whitespace. |
| Diagnostyka SAP, aktywacja, ATC, ABAP Unit, EML, `$metadata`, `$expand` | NIE WYKONANO |
| E2E SAP / CAP / KSeF | NIE WYKONANO |

## Problems Encountered

MCP działa, ale nie można bezpiecznie zapisać zmian bez uzgodnionego transportu. Dodatkowo odpowiedź system-info nie identyfikuje klienta ani wydania H01. W systemie są dwa nieaktywne DCL root; nie wolno aktywować ich przy okazji, bo aktywacja nie jest częścią tej zmiany.

## Decisions Made

- Zachować lokalny patch jako propozycję do review i nie synchronizować go automatycznie z SAP.
- Pozostawić nieaktywne DCL root poza zakresem zmiany. Potwierdzić ich właściciela/stan i porównać przed późniejszą aktywacją.
- Nie tworzyć transportu ani nie wybrać requestu bez uzgodnienia użytkownika; brak transportu oznacza, że wynik pozostaje lokalnym patchem.
- Nie zmieniać polityki `where TRUE` w tym zadaniu; jawnie zablokować produkcyjne użycie serwisu do czasu wdrożenia i testu skutecznej autoryzacji.

## Current State

Lokalny kod przygotowuje composition root–child i ekspozycję źródeł w istniejącej usłudze. SAP H01 pozostaje bez zmian. Patch nie został poddany diagnostyce ABAP w H01, aktywacji, testom EML ani testom OData.

## Next Steps

1. Potwierdzić system/klienta H01 i podać istniejący transport albo jawnie zatwierdzić utworzenie transportu z opisem.
2. Porównać aktywne i nieaktywne obiekty oraz lokalne źródła; dopiero potem wykonać uzgodnione zapisy.
3. Po zapisaniu uruchomić diagnostykę/aktywację zależności i testy EML oraz OData; zapisać osobno wyniki SAP live i bezpieczeństwa.

## Diagnostyka tworzenia CDS przez ABAP FS MCP — 2026-10-24

### Zakres, środowisko i dowody

Zlecenie: diagnostyka bez modyfikacji SAP, aktywacji, tworzenia obiektów, commit/push. Zmieniono lokalnie instrukcje i dokumentację. Branch `java-cap-ksef`; zastano zmodyfikowane źródła root, nowe pliki child CDS/DCL i zmiany dokumentacji z wcześniejszego zadania. Źródeł `abap/src/` nie zmieniano. Użytkownik podał, że zapis/aktywacja istniejącego root przez MCP działały, wcześniejsze zmiany root przywrócono, a Eclipse tworzy obiekty bez transportu. Własny odczyt aktywnego root potwierdził 45 linii bez kompozycji; nie stanowi testu wcześniejszego zapisu/aktywacji.

Odczytano `AGENTS.md`, kanoniczny Skill ABAP/RAP, STATUS, ROADMAP, przewodnik abapGit i bieżący dziennik. Zbadano schematy dostępnych narzędzi, lokalny bundle zainstalowanego rozszerzenia `murbani.vscode-abap-remote-fs` **2.12.0**, związany z błędem log `ABAP FS.log` i dokładne historyczne wywołania/wyniki z zapisu poprzedniej sesji. Odczyt tych fragmentów ograniczono do diagnostyki incydentu; nie kopiowano logów ani credentiali do repo.

### Połączenie i pakiet — własny odczyt live

| Sprawdzenie | Wynik |
| --- | --- |
| `abapfs_get_connected_systems` | `h01` |
| `abapfs_get_sap_system_info`, `includeComponents=true` | Nazwa prezentowana: `H01`; `currentClient=null`, `sapRelease=""`, `systemType=Unknown`, `softwareComponents=[]`, `timezone=null` |
| Pakiet `/sap/bc/adt/packages/zainvoicer` | `DEVC/K`, `masterSystem=H01`, `recordChanges=false`, `isRecordChangesEditable=false` |
| Superpakiet / software component | `ZLOCAL` / `ZLOCAL`, opis `Software Component for local development`, typ komponentu `J` / `Local Developer Extensibility` |
| Transport layer | Pusta; **request nie jest wymagany w tej konfiguracji** |
| Dodatkowy atrybut pakietu | `isAddingObjectsAllowed=false` odczytany, ale jego wpływu na konkretny POST nie potwierdzono; nie utożsamiono go z wymogiem transportu ani dowodem zakazu tworzenia, skoro użytkownik potwierdza tworzenie w Eclipse |
| Transport status istniejącego root | `not-locked`, pusty request — osobny wynik, sam w sobie nie potwierdza lokalności |
| Root DDLS | `DDLS/DF`, `masterSystem=H01`, `abapLanguageVersion=cloudDevelopment`, `source_type=view entity`; aktywne źródło bez kompozycji |
| Wyszukiwanie `ZR_AINV_INV_I`, typ `DDLS` | Brak wyników; nie wykonano próby utworzenia |

Metadane pakietu/root potwierdzają H01, ale identyfikator połączenia nie dowodzi numeru klienta ani identyczności endpointu/użytkownika z Eclipse `H01_EN`. System-info nie dostarczyło tych danych; brak wartości nie dowodzi braku klienta ani błędu logowania. Brak requestów nie jest blokerem implementacji dla lokalnego pakietu. Nie wykonywano SQL do tabel konfiguracyjnych.

### Rzeczywisty kontrakt tworzenia

W bieżącym schemacie `abapfs_create_object` obowiązkowe są `connectionId`, `objectType`, `name`, `description`. Opcjonalne: `packageName` (domyślnie `$TMP`), `parentName`, `additionalOptions`. Te ostatnie udostępniają wyłącznie `bindingCategory`, `bindingType`, `packageType`, `serviceDefinition`, `softwareComponent`, `transportLayer`, `transportRequest` (`type=new|existing`, opis/numer zależnie od typu). Nie ma parametrów źródła CDS, szablonu, XML, nagłówków, `source_type` ani wersji języka ABAP.

Typ tworzenia Data Definition to **`DDLS/DF`** (limit nazwy w mapie biblioteki: 30; opis w schemacie: maksymalnie 60 znaków). Jawne `packageName=ZAINVOICER` jest konieczne dla zamierzonego pakietu, mimo opcjonalności pola w schemacie. DDLS nie wymaga specyficznych `additionalOptions` ani `parentName` od wywołującego. Biblioteka mapuje także `DCLS/DL`, `DDLX/EX`, `DDLA/ADF`; ich tworzenia w H01 nie testowano.

Historyczne wywołanie z 18:27:46 czasu Europe/Warsaw (czas rozpoczęcia w zapisie sesji), zakończone błędem o 18:28:21:

```json
{"connectionId":"H01","name":"ZR_AINV_INV_I","objectType":"DDLS/DF","description":"AInvoicer Invoice Item","packageName":"ZAINVOICER"}
```

Nie wywołano go ponownie. Wrapper normalizuje `connectionId` do małych liter. Wywołanie spełnia udostępniony kontrakt; brak `additionalOptions.transportRequest` jest prawidłowy dla tego pakietu.

### Pełny dostępny błąd i ograniczenia obserwowalności

Historyczny wynik narzędzia (bez skracania komunikatu):

```text
ABAP Object Creation Failed
Object Type: DDLS/DF
Name: ZR_AINV_INV_I
Error: CREATION_FAILED
Message: An error occurred when deserializing in the simple transformation program SDDIC_ST_ADT_DDLS
```

Log rozszerzenia o 18:28:21.672 zawiera ten sam komunikat poprzedzony `ERROR in createObject: Error:`. `CREATION_FAILED` jest kodem wrappera ABAP FS, nie kodem HTTP ani numerem komunikatu SAP. Kod komendy zachowuje `message` i `stack`, a wrapper narzędzia zwraca przy błędzie jedynie wybrane pola `error`/`message`/nazwa/typ. Badany log nie zawiera response body ani statusu HTTP. **Nie odzyskano wewnętrznego wyjątku, jego przyczyny, numeru komunikatu SAP ani statusu HTTP.** W dostępnej sesji logów nie znaleziono osobnego pliku Communication Log; nie włączano rejestrowania ani nie odtwarzano POST.

Druga historyczna próba z `objectType=DDLS` (18:29:44 w logu) zwróciła `Unknown object type: DDLS`. To niezależny błąd lokalnego doboru typu, a nie naprawa pierwotnego błędu. Nie należy go ponawiać.

### Generator XML i transformacje w H01

Inspekcja lokalnego bundle 2.12.0 i [kodu upstream biblioteki](https://github.com/marcellourbani/abap-adt-api/blob/master/src/api/objectcreator.ts) wskazuje:

- walidacja: POST `/sap/bc/adt/ddic/ddl/validation`;
- tworzenie: POST `/sap/bc/adt/ddic/ddl/sources`, `Content-Type: application/*`; `corrNr` tylko gdy ustawiono transport;
- root XML `ddl:ddlSource`, namespace `http://www.sap.com/adt/ddic/ddlsources`;
- generator wspólny dodaje `adtcore:description`, `name`, `type`, `language`, `masterLanguage`, `responsible` i `packageRef` z nazwą pakietu; `masterSystem` tylko gdy ustawiony;
- opcje języka i odpowiedzialnego ustala rozszerzenie/połączenie. Dla DDLS generator nie dodaje `ddl:source_type`, `source_origin`, `abapLanguageVersion` ani szablonu. Powyższe opisuje kod, **nie przechwycony XML historycznego POST**.

Odczytano live źródło `SDDIC_ST_ADT_DDLS` oraz include `SADT_ABAP_SOURCE_MAIN_OBJECT` i `SADT_MAIN_OBJECT`. Transformacja oczekuje root `ddl:ddlSource`, ma `tt:extensible="deep"` i warunkowe mapowania m.in. `view entity → W`, `projection view → P`, `view → V`; include obsługuje szablon źródła i wersję języka, w tym `cloudDevelopment`. GET root zawiera `source_type="view entity"`, `source_origin="0"` i `abapLanguageVersion="cloudDevelopment"`.

Nie wykazano, że którykolwiek z pomijanych atrybutów jest bezwarunkowo wymagany przy tworzeniu. Warunkowe instrukcje ST i pełne metadane GET nie zastępują kontraktu POST ani inner exception. Nie ma podstaw do twierdzenia, że wystarczy dodać `source_type`, zmienić `EN` na `E`, podać transport albo użyć innego namespace. Root/namespace generatora zgadzają się z odczytaną transformacją; konkretny historyczny XML pozostaje nieznany.

### Fakty, hipotezy i brakujące dowody

| Klasa | Ustalenie | Dowód / ograniczenie |
| --- | --- | --- |
| Fakt live | ZAINVOICER nie rejestruje zmian i jest w ZLOCAL | XML pakietu z H01; zgodne z relacją Eclipse |
| Fakt historyczny | Poprawne `DDLS/DF` wywołano z nazwą, krótkim opisem i pakietem | Zapis wywołania i wynik MCP; bez powtórzenia |
| Fakt historyczny | Błąd dotyczył deserializacji DDLS | Pełny dostępny komunikat `SDDIC_ST_ADT_DDLS` w MCP/logu |
| Fakt implementacji | Samo `DDLS` nie jest typem tworzenia w 2.12.0 | Mapa typów, historyczny `Unknown object type` |
| Fakt implementacji | Warstwa MCP ogranicza szczegóły błędu i nie udostępnia opcji DDLS XML | Schemat oraz kod wrappera/generatora |
| Fakt live | Root aktywny bez kompozycji; brak wyniku dla child I | Ponowny odczyt/wyszukiwanie; nie dowodzi testu zapisu |
| Relacja użytkownika | Eclipse tworzy bez requestu; MCP zapisuje/aktywuje istniejący root; zmiany przywrócone | Nie wykonywano testu zapisu w diagnostyce |
| Hipoteza główna | Niezgodność generowanego XML lub nagłówków biblioteki ABAP FS z kontraktem tworzenia DDLS w H01 | Parametry narzędzia poprawne, błąd ST; brak śladu HTTP uniemożliwia wskazanie atrybutu |
| Hipoteza alternatywna | Różnica obsługi ADT/wydania lub błąd backendu | Wymaga inner exception i porównania z żądaniem Eclipse w tym samym systemie/kliencie |
| Hipoteza bez dowodu | Brak transportu powoduje błąd ST | Nieuzasadniona konfiguracją pakietu i relacją Eclipse; nie traktować jako bloker |
| Brak dowodu | Wewnętrzny wyjątek, HTTP, rzeczywisty endpoint i payload konkretnej próby | Endpoint POST wyprowadzono z kodu; HTTP nie zgadywano |
| Brak dowodu | Numer klienta, dokładne wydanie i zgodność połączenia z Eclipse | `system-info` nie podało wartości; brak porównania endpointów |

Ocena: pierwotne wywołanie nie narusza schematu MCP. Ograniczenie schematu i utrata szczegółów błędu przez wrapper są potwierdzone. Najsilniejsza hipoteza dotyczy generatora `abap-adt-api`/ABAP FS i zgodności z backendem ADT H01; nie udowodniono konkretnej wady ani poprawki. Nie ma dowodu awarii protokołu MCP. Pakiet/transport należy diagnozować osobno.

### Zmiany lokalne i weryfikacja

- `AGENTS.md`, `.agents/skills/ainvoicer-abap-rap/SKILL.md`, `docs/guides/ABAPGIT_WORKFLOW.md`: lokalny pakiet bez requestu, rzeczywisty typ/parametry tworzenia CDS, rozdzielenie błędów, kontrola częściowego utworzenia, fallback do Eclipse i granice zadania diagnostycznego.
- `docs/STATUS.md`, `docs/ROADMAP.md` i ten istniejący dziennik: korekta nieuzasadnionego blokera transportowego, potwierdzone wyniki oraz nieustalone HTTP/inner exception/klient.
- Narzędzia odczytu SAP: `get_connected_systems`, `get_sap_system_info`, `get_object_info`, `get_object_by_uri`, `get_object_transport_status`, `search_objects`, `get_object_source`; dodatkowo odczyt dokumentacji rozszerzenia. Bez narzędzi mutujących SAP.
- Polecenia lokalne: `git status --short`, `git branch --show-current`, `Get-Content -Encoding UTF8`, `rg` i krótkie skrypty Python do selektywnej inspekcji bundle/logu/historycznego wyniku. Przejrzano dokumentację autora ABAP FS oraz kod źródłowy biblioteki przez WWW.
- Testy zapisu/aktywacji, ATC, ABAP Unit, EML i OData: **NIE WYKONANO**. Build aplikacji nie dotyczy zmian dokumentacji.
- Walidacja lokalna: `git -c core.whitespace=cr-at-eol diff --check` dla pięciu zmienionych plików śledzonych — PASS (jedynie ostrzeżenia normalizacji LF/CRLF). Skrypt Python sprawdził UTF-8 i 64 relatywne linki wraz z kotwicami w sześciu plikach — PASS; osobno whitespace i parzystość ogrodzeń kodu, również w zastanym nieśledzonym dzienniku — PASS. `git status --short` potwierdził zachowanie zastanych zmian ABAP.

### Minimalny plan rozwiązania

1. W Eclipse porównać bez ujawniania credentiali system, klienta i endpoint z połączeniem MCP. Zachować lokalny pakiet bez rejestrowania zmian; nie tworzyć requestu.
2. Po osobnym zleceniu implementacji utworzyć `ZR_AINV_INV_I` kreatorem Data Definition w `H01_EN` / `ZAINVOICER`, wcześniej sprawdzając brak częściowego obiektu. Wykonać odczyt MCP, składnię i aktywację w uzgodnionym zakresie. Root pozostawić poza zakresem obejścia.
3. Do naprawy MCP odzyskać istniejący oczyszczony trace albo zaplanować osobno autoryzowaną próbę z rejestracją: metoda/ścieżka, Content-Type/Accept, XML, status i SAP exception chain. Porównać z Eclipse oraz dopiero wtedy poprawić/przetestować generator i zgłosić błąd autorowi. Nie przesyłać zgłoszenia ani logów automatycznie. Żadne ponowne tworzenie nie jest częścią tej diagnostyki.

## Zapis BDEF ZR_AINV_INV_H przez MCP — 2026-10-24

Nowe zlecenie użytkownika: edycja wyłącznie behavior definition `ZR_AINV_INV_H` w H01 według dostarczonego kompletnego kodu. Kryterium zapisu: dokładna zgodność odczytanej wersji inactive z podanym źródłem. Zakres nie obejmował tworzenia brakujących CDS, zmiany root CDS, DCL ani eksportu abapGit.

### Stan początkowy i wykonanie

- Branch `java-cap-ksef`, `git status --short` początkowo pusty. Odczytano instrukcje, Skill ABAP/RAP, STATUS, ROADMAP, indeks i ten dziennik oraz lokalny BDEF wyłącznie do porównania.
- MCP wskazał typ `BDEF/BDO`, pakiet `ZAINVOICER`. Active i inactive zawierały to samo źródło nagłówka (54 linie); lista nieaktywnych obiektów dla tej nazwy zawierała tylko istniejący DCL.
- Odczyt pakietu potwierdził `masterSystem=H01`, `recordChanges=false`, komponent `ZLOCAL`, pustą warstwę transportową. Kontrola dokładnego BDEF: `not-locked`. Konfiguracja połączenia `h01` wskazuje klienta `100`; nie potwierdzono numeru klienta odczytem backendu ani wydania systemu.
- Aktywny root CDS nie ma kompozycji; odczyt child CDS nie znalazł `ZR_AINV_INV_I` ani `ZR_AINV_INV_REF`. Wyszukiwanie `ZR_AINV_INV_*` typu DDLS zwróciło wyłącznie root.
- `replace_string_in_abap_object` zapisał pełne źródło użytkownika (123 linie) w SAP, bez transportu. Powtórny `get_object_source(version=inactive)` i porównanie tekstu w pamięci potwierdziły dokładną zgodność. Nie edytowano lokalnego eksportu `abap/src/`.

### Walidacja live

| Sprawdzenie | Wynik |
| --- | --- |
| Zapis i odczyt inactive | PASS, 123 linie, dokładna zgodność tekstu |
| `get_abap_diagnostics` | FAIL, 4 błędy: linia 23 brak `_Items`; linia 24 brak `_References`; linia 52 nieznany `ZR_AINV_INV_I`; linia 91 nieznany `ZR_AINV_INV_REF` |
| Lista nieaktywnych obiektów po zapisie | BDEF oraz zastany DCL o tej samej nazwie; DCL pozostawiono nietknięty |
| Aktywacja | NIE WYKONANO z powodu błędów zależności; bez wywołania `action=activate` |
| Kontrolny odczyt active | PASS dla zachowania poprzednich 54 linii, tekst identyczny z odczytem początkowym |
| ATC / ABAP Unit / EML / OData | NIE WYKONANO; brak aktywacji, zadanie ograniczone do BDEF |

Użyte narzędzia MCP: `get_configured_systems`, `get_object_info`, `get_object_source` active/inactive, `get_workspace_uri`, `get_object_transport_status`, `get_object_by_uri`, `search_objects`, `replace_string_in_abap_object`, `get_abap_diagnostics`, `activate_object(action=get_inactive_objects)`. Bieżącej wersji rozszerzenia nie odczytywano; wcześniejsza diagnostyka opisywała 2.12.0. Polecenia lokalne: `Get-Content -Encoding UTF8`, `Get-ChildItem`, `git branch --show-current`, `git status --short`; edycja dokumentacji przez `apply_patch`. Sprawdzono referencję [SAP: Defining Composite Business Objects](https://learning.sap.com/courses/building-transactional-apps-with-the-abap-restful-application-programming-model/defining-composite-business-objects); dowodem konkretnych błędów pozostaje diagnostyka H01.

Zmienione pliki lokalne: `docs/STATUS.md`, `docs/ROADMAP.md` i ten dziennik. Brak commita/push, eksportu i zmian innych obiektów SAP.

### Następny krok

W osobnym zakresie uzupełnić brakujące child CDS oraz kompozycje root i asocjacje `_Parent`, porównując active/inactive; następnie ponowić diagnostykę i aktywować uzgodnione zależności oraz BDEF. Dopiero po tym wykonać testy RAP/OData. Obecny rezultat to zapisany nieaktywny BDEF, nie działający model kompozycji.

## Edycja istniejacych obiektow wedlug d3dedf4 — 2026-10-24

### Zakres i stan początkowy

Użytkownik zlecił wykonanie edycji istniejących obiektów, pozostawiając tworzenie nowych sobie. Punktem odniesienia jest commit `d3dedf4423620b75581418b0f035364c96a06bfc`, równy lokalnemu HEAD na `java-cap-ksef`. Zastane zmiany trzech plików dokumentacji zachowano. Odczytano ponownie Skill ABAP/RAP oraz active/inactive obu root DDLS i BDEF. Root BDEF zawierał już wcześniej zapisany kod dzieci; pozostałe definicje nadal dotyczyły nagłówka. Zastane nieaktywne DCL root i projection pozostawiono bez zmian.

### Wykonane zmiany live

Przez MCP zapisano kolejno cztery istniejące obiekty, bez tworzenia i bez transportu:

| Obiekt | Typ | Zmiana | Diagnostyka po zapisie |
| --- | --- | --- | --- |
| `ZR_AINV_INV_H` | DDLS/DF | Kompozycje `_Items`, `_References` i ekspozycja w liście pól | 2 błędy: child źródła nie istnieją lub nie są aktywne |
| `ZC_AINV_INV_H` | DDLS/DF | Przekierowania do projection children | 2 błędy: `_Items`, `_References` nieznane |
| `ZC_AINV_INV_H` | BDEF/BDO | Behavior dzieci, `use association` root i `_Parent` dzieci | 1 błąd: base BDEF `ZR_AINV_INV_H` zawiera błędy |
| `ZAPI_AINV_INV_H_O4` | SRVD/SRV | Ekspozycja `ZC_AINV_INV_I`, `ZC_AINV_INV_REF` | Narzędzie nie zwróciło diagnostyk; nie dowodzi aktywacji ani poprawności runtime |

Dla każdego obiektu uzyskano workspace URI i sprawdzono transport status (`not-locked`), ponownie odczytano aktualny kod i porównano ze stanem początkowym, po czym wykonano pełną zamianę na źródło z commita. Kontrolne odczyty potwierdziły zgodność wszystkich czterech źródeł z commitem po normalizacji CRLF/LF i końcowego newline. Dla DDLS/BDEF odczyt kontrolny używał `version=inactive`; dla SRVD odczytano `source/main`, a metadane i inactive-object list potwierdziły wersję inactive.

Root BDEF nie wymagał ponownego zapisu. Ponowna diagnostyka nadal zwróciła 4 błędy: brak dwóch asocjacji i nieznane dwa typy dzieci. Narzędzie sprawdza zależności względem dostępnego stanu SAP; zapis root CDS bez skutecznej aktywacji nie usuwa tych błędów.

### Weryfikacja i ograniczenia

- PASS: zapis i odczyt czterech obiektów live; kontrolne odczyty active obu DDLS i obu BDEF są identyczne z początkowymi źródłami.
- Lista inactive: 7 obiektów — 2 DDLS, 2 BDEF, 1 SRVD oraz 2 zastane DCL. Nie wykonywano `action=activate`; nie tworzono obiektów, nie zmieniano DCL, klas ani bindingu.
- ATC, ABAP Unit, EML i OData NIE WYKONANO: brakuje dzieci i aktywacji. Brak diagnostyk SRVD nie jest PASS całej integracji.
- Użyto: `get_object_source` active/inactive, `get_object_by_uri`, `get_workspace_uri`, `get_object_transport_status`, `replace_string_in_abap_object`, `get_abap_diagnostics`, `activate_object(action=get_inactive_objects)`. Nie odczytywano ponownie wersji rozszerzenia.
- Polecenia lokalne: `git branch --show-current`, `git status --short`, `git rev-parse HEAD`, `git show d3dedf4:<plik>`, odczyt Skill/STATUS/ROADMAP. Źródeł `abap/src/` nie edytowano, bez commita/push.
- Zmienione lokalnie: STATUS, ROADMAP i ten dziennik. Kontrola whitespace: `git -c core.whitespace=cr-at-eol diff --check`.

### Kolejny krok

Użytkownik utworzy w ADT cztery DDLS i cztery DCLS o nazwach `ZR_AINV_INV_I`, `ZR_AINV_INV_REF`, `ZC_AINV_INV_I`, `ZC_AINV_INV_REF`, według odpowiadających plików commita. Potem należy ponowić diagnostykę, aktywować zależności wraz z istniejącymi root/projection i behavior, sprawdzić DCL oraz usługę. Obecne źródła do edycji są zapisane; nie ma jeszcze działającej kompozycji live.

## Kontynuacja live przez poprawione MCP — 2026-10-25

### Cel, zakres i stan wejściowy

Użytkownik wznowił ABAP-02 z poleceniem doprowadzenia H01 do rezultatu, następnie wykluczył Eclipse i poinformował o wgraniu poprawek narzędzia. Pierwszy odczyt MCP zwracał 401; po wznowieniu połączenie odpowiadało. Próba utworzenia `ZR_AINV_INV_REF` przed poprawką zwróciła `CREATION_FAILED / Unsupported Media Type`, a odczyt i lista inactive potwierdziły brak częściowego obiektu. Ponowienie nastąpiło dopiero po informacji o nowej poprawce, nie w ciemno.

- Branch `java-cap-ksef`, lokalny HEAD `d3dedf4`. Zachowano zastane zmiany dokumentacji, w tym osobny dziennik `2026-10-25-abapfs-create-probe.md`; nie nadpisywano jego wyników.
- H01 / `ZAINVOICER`: ponowny odczyt pakietu potwierdził `masterSystem=H01`, `recordChanges=false`, komponent/superpakiet `ZLOCAL`, pustą warstwę transportową. Klient 100 został później potwierdzony dumpem backendu; dokładnego wydania SAP nie ustalono.
- W H01 istniał już `ZR_AINV_INV_I` z kodem pozycji oraz nieaktywny DCL pozycji. Brakowało trzech pozostałych child DDLS i trzech DCL. Root CDS/projection, oba BDEF i SRVD zawierały wcześniejsze zapisy kompozycji.
- Odczytano definicje trzech tabel, źródła CDS/BDEF, DCL root/projection/item, lokalną implementację behavior, SRVD i binding. Pola i klucze tabel odpowiadają modelowi; nie zmieniano tabel.
- Lokalnie wykryto instalację `ABAP remote filesystem (ADT creation fix 3 candidate)` 2.12.2 oraz wcześniejsze wersje. Numer aktywnego procesu MCP nie został niezależnie odczytany; dowodem poprawy jest udane tworzenie DDLS.

### Wykonane zmiany w SAP

| Obiekt | Typ | Wynik |
| --- | --- | --- |
| `ZR_AINV_INV_REF` | DDLS/DF | Utworzony, zapisany, aktywny |
| `ZC_AINV_INV_I` | DDLS/DF | Utworzony, zapisany, poprawiony, aktywny |
| `ZC_AINV_INV_REF` | DDLS/DF | Utworzony, zapisany, poprawiony, aktywny |
| `ZR_AINV_INV_I` | DDLS/DF | Zastany; poprawiony i aktywowany |
| `ZR_AINV_INV_H` | DDLS/DF | Aktywowano wcześniejszy zapis kompozycji |
| `ZC_AINV_INV_H` | DDLS/DF | Aktywowano wcześniejsze przekierowania; zachowano `_BaseEntity` i pola root |
| `ZR_AINV_INV_H` | BDEF/BDO | Poprawiono ETag dzieci, aktywowano cały managed BO |
| `ZC_AINV_INV_H` | BDEF/BDO | Aktywowano projection behavior dla trzech encji |
| `ZAPI_AINV_INV_H_O4` | SRVD/SRV | Aktywowano ekspozycję trzech projekcji; binding zachowany |
| `ZCL_AINV_RAP_COMP_TEST` | CLAS/OC + testclasses | Utworzona, zapisana i aktywna klasa regresji EML |

Tworzenie wykonywano sekwencyjnie jako `DDLS/DF`, jawnie w `ZAINVOICER`, bez `additionalOptions.transportRequest`. Każdy obiekt odczytano przed zapisem, sprawdzono workspace URI i status transportu. Kontrole nowych CDS i klasy wykazały `not-locked` i pusty request.

**Rozbieżność komunikatów transportowych:** create podawał „Transport requested … (backend assignment not verified)”. Odczyt `get_transport_objects` potwierdził istnienie pustych requestów `H01K900028`, `H01K900030`, `H01K900032` (opis `cds`) oraz `H01K900036` (opis `test`), każdy z jednym taskiem i 0 obiektów. Nie są przypisane do nowych obiektów według kontroli lock status. Nie ustalono w tej sesji czasu ani mechanizmu ich utworzenia; nie wybierano ich jawnie w parametrach, nie zwalniano i nie usuwano. Nie utożsamiać komunikatu wrappera z wymaganiem transportu lokalnego pakietu.

### Poprawki wynikające z rzeczywistej aktywacji

1. H01 odrzucił `@Semantics.unitOfMeasure: true` w view entity. Usunięto tę adnotację z interface/projection pozycji, pozostawiając `@Semantics.quantity.unitOfMeasure: 'UnitCode'`, pole jednostki DDIC oraz semantykę kwot/walut.
2. H01 odrzucił jawny `provider contract TRANSACTIONAL_QUERY` w child z `redirected to parent`. Usunięto go z obu projekcji dzieci; dziedziczą kontrakt root, zgodnie z [SAP: PROVIDER CONTRACT](https://help.sap.com/docs/abap-cloud/abap-keywords/cds-ddl-provider-contract).
3. H01 odrzucił `etag dependent by _Parent`, ponieważ dzieci mają pole `LocalLastChangedAt` o tej samej nazwie co pole ETag root. Dzieci otrzymały własny `etag master LocalLastChangedAt`; root nie zmienił ETag, lock ani numbering. `lock dependent` i `authorization dependent by _Parent` pozostały; nadal jeden BO, bez samodzielnego CREATE dzieci i bez draft.

Po poprawkach aktywacja zakończyła się sukcesem. Ponowny odczyt `version=active` objął sześć CDS i oba BDEF. Końcowa lista inactive dla zakresu wykazała tylko trzy zastane DCL; CDS/BDEF/SRVD/klasa testowa nie pozostały inactive. Pierwsza aktywacja klasy pozostawiła część include'ów inactive; uruchomienie ABAP Unit dokończyło aktywację klasy, co potwierdziła pusta lista inactive dla jej nazwy.

### Testy i wyniki

| Warstwa / sprawdzenie | Wynik i ograniczenie |
| --- | --- |
| SAP live: aktywacja 6 CDS + 2 BDEF + SRVD | PASS; odczyty active i lista inactive potwierdzone |
| Syntax diagnostics głównego BDEF po aktywacji | PASS, 0 diagnostyk; wcześniejsze wyniki przed aktywacją bywały nieodświeżone |
| ATC `ZR_AINV_INV_H` BDEF | PASS, wariant `ABAP_CLOUD_DEVELOPMENT_DEFAULT`, 0 ustaleń; nie jest to ATC całego pakietu |
| Klasa testowa / składnia / aktywacja | PASS; 4 metody testowe, poprawiono składnię EML DELETE na `DELETE FROM VALUE` |
| EML deep create: header + 2 items + 1 reference; RBA | NIEZALICZONE: wykonanie całego zestawu przerwane dumpem, brak pełnych wyników poszczególnych testów |
| EML update/delete dzieci, błędny rodzic, cascade delete root | NIEZALICZONE z tej samej przyczyny; testy przygotowane w aktywnej klasie |
| Wykonanie ABAP Unit przez MCP | Błąd wrappera `Cannot read properties of undefined (reading 'toFixed')`; nie jest wynikiem PASS |
| Diagnostyka backendu ABAP Unit | Dump `ACM_UNEXPECTED_VALUE` w `DELETE_ROOT_CASCADES`, klient 100: `No runtime data for CDS entity ZR_AINV_INV_I ... Access Control Existence: FALSE` przy `#MANDATORY` |
| OData binding live / URL | PASS dla inspekcji ADT: published=true, binding V4, wersja 0001, NOT_RELEASED; brak zmiany bindingu/publikacji |
| OData `$metadata` / `$expand` | NIEZWERYFIKOWANE: MCP odczytu obiektów odrzuca runtime URL jako `Invalid Object URL`; brak wyniku HTTP tych żądań |
| Persystencja po commit, ETag przy równoległych zapisach i izolacja użytkowników | NIE WYKONANO |

Testy używają konsumenckiego EML bez `IN LOCAL MODE`; setup tworzy syntetyczny bufor, teardown wykonuje `ROLLBACK ENTITIES`. Nie mają `COMMIT ENTITIES` ani trwałego fixture w tabelach. Nie osłabiano kontroli DCL, aby uzyskać zielone testy.

### DCL i bezpieczeństwo — świadomie pozostawione

Próba utworzenia `ZR_AINV_INV_REF` jako `DCLS/DL` zwróciła `CREATION_FAILED / Unsupported Media Type`; kontrola obiektu i inactive potwierdziła brak częściowej roli. **Użytkownik wybrał „Zostaw DCL do późniejszego uzupełnienia”.** Nie tworzono kolejnych ról, nie aktywowano ani nie zmieniano zastanych DCL:

- istnieją inactive: `ZR_AINV_INV_H`, `ZC_AINV_INV_H`, `ZR_AINV_INV_I`;
- brakuje: `ZR_AINV_INV_REF`, `ZC_AINV_INV_I`, `ZC_AINV_INV_REF`;
- root DCL nadal `where TRUE`, metoda `GET_GLOBAL_AUTHORIZATIONS` nadal pusta; brak izolacji podatnika/użytkownika, API nie jest gotowe produkcyjnie.

Aktywacja struktury nie oznacza działającego runtime danych przy brakującym DCL. ABAP-02 pozostaje nieukończone w zakresie testów i autoryzacji.

### Porównanie z eksportem abapGit i pliki lokalne

Nie edytowano `abap/src/` równolegle ze zmianami live i nie wykonywano eksportu abapGit, commita ani push. Lokalny HEAD `d3dedf4` zawiera starszy patch. Cztery aktywne źródła odpowiadają eksportowi po pominięciu whitespace i wielkości liter: root interface/projection, interface referencji i projection BDEF. Cztery różnią się zgodnie z poprawkami powyżej: `ZR_AINV_INV_I` DDLS, `ZC_AINV_INV_I` DDLS, `ZC_AINV_INV_REF` DDLS i `ZR_AINV_INV_H` BDEF. Klasa testowa jest nowa wyłącznie w SAP. SRVD odczytany z H01 ma ekspozycję tych samych trzech projekcji.

Zmienione w tej kontynuacji pliki: `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/README.md`, `docs/guides/ABAP_RAP_INVOICE_MODEL.md` oraz ten dziennik. Zachowano historię i wcześniejsze zmiany użytkownika. Modułów CAP/UI5/Node/Python/KSeF nie zmieniano.

Narzędzia: MCP search/get source active/inactive, get by URI, workspace URI, transport status, create, replace, diagnostics, activate, ATC, create test include, ABAP Unit, analyze dumps oraz read-only transport objects. Polecenia lokalne: `git branch --show-current`, `git status --short`, `git log`, `Get-Content -Encoding utf8`, `Get-ChildItem`, `rg`, `git diff --stat`, `git -c core.whitespace=cr-at-eol diff --check`. Źródła SAP odczytywano i porównywano w pamięci, bez publikowania dumpu ani sekretów w repo.

### Najmniejszy następny krok

Końcowa walidacja lokalnej dokumentacji: `git -c core.whitespace=cr-at-eol diff --check` PASS (ostrzeżenia normalizacji LF/CRLF); skrypt Python sprawdził UTF-8, frontmatter, parzystość ogrodzeń kodu oraz 62 relatywne linki i kotwice w pięciu zmienionych plikach — PASS. `git status --short` potwierdził wyłącznie zmiany dokumentacji i zachowany zastany nieśledzony dziennik próby narzędzia.

1. Po wznowieniu zakresu DCL naprawić tworzenie `DCLS/DL` w MCP albo osobno uzgodnić ręczne utworzenie; uzupełnić trzy brakujące role i aktywować komplet po porównaniu.
2. Ponowić `ZCL_AINV_RAP_COMP_TEST`, uzyskać pełny raport ABAP Unit, następnie zweryfikować persystencję i uwierzytelnione OData `$metadata`/`$expand` pod odczytanym z ADT URL. Nadal wymagane testy readonly InvoiceUUID, ETag i autoryzacji.
3. Po walidacji i osobnym zleceniu wykonać kontrolowany eksport abapGit. Nie importować obecnych lokalnych źródeł na nowszy H01 i nie wykonywać pull przy brudnym checkout.
