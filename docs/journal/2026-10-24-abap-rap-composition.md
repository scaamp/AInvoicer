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
