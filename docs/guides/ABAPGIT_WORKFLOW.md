---
title: "Workflow abapGit w AInvoicer"
date: 2026-10-26
type: guide
status: aktywny
tags: [ainvoicer, abap, abapgit, eclipse, git]
---

# Workflow abapGit w AInvoicer

[Project Home](../README.md) · [STATUS](../STATUS.md) · [Model RAP faktur](ABAP_RAP_INVOICE_MODEL.md) · [Dziennik pierwszego eksportu](../journal/2026-10-24-abapgit-rap-initial-export.md)

## Cel i granice

abapGit synchronizuje definicje obiektów SAP z repozytorium Git. W tym projekcie eksportuje obiekty pakietu `ZAINVOICER` do `abap/src/` w repozytorium `https://github.com/scaamp/AInvoicer.git`, na branchu `java-cap-ksef`. `cap-java/`, `sapui5/`, `node/` i `python/` pozostają osobnymi modułami monorepo.

Repozytorium SAP, lokalny checkout Git i projekty Eclipse nie są tym samym:

| Element | Rola |
| --- | --- |
| EGit / lokalny checkout | Praca na plikach i historii Git w `D:\AInvoicer`; lokalny projekt Eclipse `abap` wskazuje na folder `D:\AInvoicer\abap`. |
| abapGit w SAP ADT | Serializuje obiekty repozytorium SAP do plików i synchronizuje je z wybranym repozytorium Git. |
| SAP ADT, projekt `H01_EN` | Połączenie z SAP BTP ABAP Environment; służy do tworzenia, aktywacji i weryfikacji obiektów w systemie. |
| Transport / lifecycle SAP | Oddzielny mechanizm przenoszenia zmian między systemami/środowiskami. Git push nie wykonuje transportu ani wdrożenia. |

Projekt Eclipse `abap` jest zwykłym projektem ogólnym wskazującym na lokalne pliki. Nie jest połączeniem z systemem i nie jest projektem ABAP `H01_EN`. Plik `abap/.project` to lokalna konfiguracja Eclipse, ignorowana przez Git.

## Konfiguracja repozytorium

Potwierdzona konfiguracja przekazana dla pierwszego eksportu (24.10.2026):

- Eclipse 2026-09 z SAP ADT i pluginem abapGit;
- system SAP BTP ABAP Environment, projekt ADT `H01_EN`;
- pakiet SAP `ZAINVOICER`;
- repozytorium `https://github.com/scaamp/AInvoicer.git`, branch `java-cap-ksef`;
- status połączenia abapGit w Eclipse: `Linked`; Folder Logic: `FULL`;
- lokalny checkout: `D:\AInvoicer`; lokalny General Project Eclipse: `D:\AInvoicer\abap`.

Konfiguracja śledzona w głównym pliku `.abapgit.xml`:

```xml
<MASTER_LANGUAGE>E</MASTER_LANGUAGE>
<STARTING_FOLDER>/abap/src/</STARTING_FOLDER>
<FOLDER_LOGIC>FULL</FOLDER_LOGIC>
```

Mapuje ona zawartość pakietu wybranego w abapGit do `/abap/src/`. Sama konfiguracja nie dowodzi, że wszystkie obiekty SAP są aktywne ani że service binding działa w runtime.

## Preferowany workflow Codexa: SAP jako środowisko implementacji

Dla ABAP obowiązuje **Codex/VS Code (analiza) → ABAP FS/MCP (edycja live H01, jeśli faktycznie dostępna) → SAP H01 (diagnostyka, aktywacja, testy) → Eclipse ADT/abapGit Stage and Push → GitHub → lokalny Git**. Przy braku zapisu/aktywacji przez MCP korzystaj z ADT; patch lokalny + kontrolowany import Git → SAP to fallback, nie domyślna ścieżka. Szczegóły i reguły bezpieczeństwa: [AGENTS.md](../../AGENTS.md) i [Skill ABAP/RAP](../../.agents/skills/ainvoicer-abap-rap/SKILL.md).

Nie myl trzech stanów: kod aktywny w H01, snapshot w GitHub i pliki `D:\AInvoicer\abap\src\`. `abapGit Pull` nie pobiera niezacommitowanych zmian VS Code. Po `Stage and Push` odśwież lokalny checkout dopiero po sprawdzeniu `git status` (dla czystego drzewa: `git pull --ff-only origin java-cap-ksef`). Przy konflikcie nie nadpisuj kodu w ciemno i nie wykonuj automatycznego Pull.

## Zmiana z SAP do GitHub i narzędzi lokalnych

1. W systemie H01 odczytaj aktualny obiekt, zależności i konfigurację pakietu; transport ustal tylko wtedy, gdy SAP go wymaga. Dla lokalnego `ZAINVOICER` z wyłączonym rejestrowaniem zmian nie wymagaj requestu. Dla istniejących obiektów preferuj Codex + potwierdzone narzędzia ABAP FS/MCP; dla brakujących możliwości MCP użyj ADT. Zapisz, aktywuj i sprawdź kod w SAP zgodnie z zakresem zlecenia.
2. Otwórz połączone repo abapGit dla pakietu `ZAINVOICER` i sprawdź różnice/staged changes. Potwierdź, że dotyczą oczekiwanych obiektów i nie obejmują przypadkowych usunięć lub plików spoza zakresu.
3. Zsynchronizuj eksport przez funkcję `Stage and Push` w pluginie abapGit. Przed zatwierdzeniem sprawdź branch `java-cap-ksef`, listę plików i diff.
4. W lokalnym checkout wykonaj zwykły przegląd Git. Codex/VS Code/Obsidian czytają te same pliki; mogą przygotować zmianę dokumentacji lub kodu, ale nie aktywują jej w SAP.
5. Transport SAP pozostaje osobnym mechanizmem lifecycle, używanym tam, gdzie wymaga go konfiguracja. `Stage and Push` lokalnego pakietu nie wymaga dodania requestu. Nie utożsamiaj transportu z commitem ani push do GitHub.

## Lokalny pakiet ZAINVOICER i tworzenie CDS

Odczyt live `/sap/bc/adt/packages/zainvoicer` z 2026-10-24 potwierdził:

| Właściwość | Wartość / znaczenie |
| --- | --- |
| `adtcore:masterSystem` | `H01` |
| `pak:recordChanges` | `false` — rejestrowanie zmian wyłączone |
| `pak:isRecordChangesEditable` | `false` |
| Superpakiet / komponent oprogramowania | `ZLOCAL` / `ZLOCAL`, opis komponentu: rozwój lokalny |
| Warstwa transportowa | pusta |

To odpowiada potwierdzonemu przez użytkownika komunikatowi Eclipse „No change recording enabled for package ZAINVOICER” i tworzeniu w ADT bez requestu. Nie wymagaj transportu, nie zmieniaj pakietu ani nie przenoś obiektów do `$TMP`. Lokalny pakiet może mieć nazwę `ZAINVOICER`. Przy innym systemie/pakiecie lub zmianie konfiguracji sprawdź te właściwości ponownie. Wynik `not-locked` istniejącego obiektu i pusta lista transportów użytkownika nie zastępują odczytu konfiguracji pakietu.

W `abapfs_create_object` typ tworzenia Data Definition to `DDLS/DF`; podaj `connectionId`, `objectType`, `name`, `description` (maksymalnie 60 znaków) i jawny `packageName: "ZAINVOICER"`. Nie podawaj `additionalOptions.transportRequest` dla tej konfiguracji. Typ wyszukiwania `DDLS` nie zastępuje typu tworzenia. Tworzenie DCL wymaga `DCLS/DL`, metadata extension `DDLX/EX`, a annotation definition `DDLA/ADF`. Zawsze sprawdź aktualny schemat i zależności typu; nie wymyślaj parametrów źródła, szablonu ani wersji języka.

## ABAP FS v5 — tworzenie i diagnostyka

Zweryfikowany punkt odniesienia (2026-10-25): **ABAP FS 2.12.4 / ADT creation fix 5**, [wydanie i VSIX](https://github.com/scaamp/abap-fs-creation-fix/releases/tag/v2.12.4-adt-creation-fix.5). To zmodyfikowane wydanie z repo `scaamp/abap-fs-creation-fix`; sam numer innej dystrybucji nie dowodzi obecności tych zmian. Historyczne błędy opisane niżej dotyczą wcześniejszych prób. Nie oznaczają, że tworzenie CDS lub DCL nadal jest ogólnie niedostępne.

### Przed zapisem

1. Odkryj aktualny schemat narzędzi MCP. Jeśli dostępne, wywołaj `abapfs_get_creation_diagnostics` z `connectionId` i `packageName`, początkowo bez `startLogging`. Odczytaj `extensionId`, `version`, `development`, `logging`, `package.recordChanges` i listę typów. Przy rozbieżności sprawdź, które okno/profil/proces VS Code wystawia MCP; nie reinstaluj ponownie w ciemno. Discovery nie potwierdza uprawnień do tworzenia.
2. Podaj jawny pakiet. `ZAINVOICER` i `ZAINVOICER_DEV` są pakietami Cloud (wersja języka 5); `$TMP` miał inny kontekst języka/komponentu. Odmowy w `$TMP` nie dowodzą odmowy w pakiecie Cloud. Nie przenoś tam testu bez uzasadnienia i zlecenia.
3. Aktualny `recordChanges=false` oznacza pominięcie `additionalOptions.transportRequest`; v5 pomija selektor transportu i tworzenie requestu. Dla `true` przekaż aktualny, modyfikowalny request dopuszczony w zadaniu. Numer z poprzedniego testu nie jest stałą konfiguracją. Dla `null`/braku danych sprawdź metadane pakietu ADT — nie zakładaj `false`. Brak zatwierdzonego requestu ustal z użytkownikiem, nie twórz go samodzielnie. W razie oczekującego wywołania MCP sprawdź, czy VS Code czeka na wybór w oknie; nie uruchamiaj drugiego tworzenia.
4. Przed tworzeniem sprawdź kolizje nazwy. Po wyniku potwierdź metadane obiektu, wersję inactive i transport-info, a przy wskazanym transporcie także zawartość requestu. Wynik „Transport requested … (backend assignment not verified)” opisuje żądany parametr. Brak numeru w odpowiedzi create nie dowodzi lokalności. Quick search może nie wykazywać świeżego obiektu; puste źródło także nie oznacza, że obiekt nie istnieje.

### Ślad HTTP i klasyfikacja błędów

Do śladu nowej, już zleconej próby można użyć `abapfs_get_creation_diagnostics` z `startLogging: true` dla właściwego połączenia. Po zakończeniu odczytaj `creationLog`: Content-Type, XML, status, response body i żądany `corrNr`, jeśli dostępne. Log nie jest dowodem historycznej próby, której nie zarejestrował. Sam brak wpisów nie uzasadnia ponownego zapisu w SAP.

Przywróć początkowy stan logowania. `startLogging: false` lub pominięcie tego pola **nie wyłącza** logowania. Jeśli wcześniej było wyłączone, wyłącz je przez polecenie VS Code `abapfs.deactivateCommLog` (także przez `abapfs_run_vscode_command`, jeśli narzędzie jest dostępne); przy wielu połączeniach wybierz właściwe. Ponowną diagnostyką sprawdź `logging=false`. Nie wyłączaj logowania, które użytkownik miał już włączone.

Rozróżniaj:

- 415 / `Unsupported Media Type`: kontrakt Content-Type danej kolekcji ADT.
- `CLASS_TRANSFORMATION` / `SDDIC_ST_ADT_DDLS`: deserializacja XML konkretnego typu; sama nazwa nie identyfikuje brakującego atrybutu. Nie używaj reprezentacji klasy do DCL/DDIC.
- 403: zachowaj komunikat SAP, sprawdź pakiet, wersję języka i uprawnienia. Samo `S_ABPLNGVS` nie dowodzi globalnego braku uprawnień użytkownika.
- Nieznany wynik lub błąd po POST: odczytaj stan przed ponowną próbą. Nie aktywuj częściowego obiektu ani nie osłabiaj DCL/root CDS jako obejścia.

Nie publikuj Authorization, Cookie, CSRF, credentiali ani poufnych treści XML. Redakcja nagłówków przez logger nie anonimizuje źródeł i metadanych biznesowych.

### Zakres potwierdzenia

Wcześniejsza macierz v4 obejmowała 23 typy: 20 utworzeń i 3 odmowy autoryzacji (DDLA, program, samodzielny include). Test akceptacyjny zainstalowanego v5 potwierdził **4/4** nowych obiektów: klasa i CDS w każdym z pakietów `ZAINVOICER` oraz `ZAINVOICER_DEV`; bez nowych transportów i bez aktywacji. Szczegóły, nazwy obiektów i pochodzenie dowodów: [dziennik aktualizacji instrukcji](../journal/2026-10-26-abapfs-v5-instructions.md).

Nie jest to ponowny pełny test 23 typów na v5, test ECC ani potwierdzenie działania biznesowych DCL/RAP/OData AInvoicer. W nowym systemie sprawdź możliwości i wymagania ponownie. Raport potencjalnych funkcji MCP na GitHub opisuje propozycje, nie dostępne narzędzia.

### OData runtime a ADT i EML

Narzędzia odczytu obiektów ADT nie są ogólnym klientem HTTP. Ich `Invalid Object URL` dla runtime OData nie jest odpowiedzią usługi. Ustal URL z bindingu; osobno sprawdź rzeczywisty status i treść uwierzytelnionego GET `$metadata`, encji oraz `$expand`. ADT, EML i HTTP OData potwierdzają różne warstwy; EML PASS nie potwierdza publikacji, uwierzytelnienia ani kontraktu HTTP.

[Proponowany OData HTTP Executor](https://github.com/scaamp/abap-fs-creation-fix/blob/main/docs/potential-mcp-features.md#2-odata-http-executor) ma obsłużyć ten przypadek bez sterowania przeglądarką. MVP obejmuje odczyty pod zweryfikowanym adresem, właściwe uwierzytelnienie, status/body, limity odpowiedzi i stronicowanie. Nie jest dostępny w v5. Sesja ADT nie gwarantuje autoryzacji biznesowego OData; narzędzie nie może wymagać przekazania sekretów do modelu ani samodzielnie publikować usługi. Zapisy są osobnym zakresem. Gdy pozostaje przeglądarka, timeout przechwytywania okna jest ograniczeniem automatyzacji; odpowiedź bez poświadczeń lub strona logowania nie zastępuje testu uwierzytelnionego.

### CREATION_FAILED / SDDIC_ST_ADT_DDLS

**Historia incydentu, nie opis bieżącego v5.** Rozdziel konfigurację transportów i deserializację XML metadanych DDLS. W incydencie z 2026-10-24 poprawne wywołanie `DDLS/DF` zwróciło `CREATION_FAILED` i komunikat SAP o transformacji `SDDIC_ST_ADT_DDLS`. Próba z `DDLS` zwróciła odrębny błąd `Unknown object type: DDLS`. Edycja/aktywacja istniejącego root CDS nie sprawdza ścieżki tworzenia nowego obiektu.

Wówczas zbadana biblioteka budowała dla DDLS root `ddl:ddlSource`, namespace `http://www.sap.com/adt/ddic/ddlsources` i wysyłała POST do `/sap/bc/adt/ddic/ddl/sources` z `Content-Type: application/*`. Było to ustalenie z kodu, nie przechwycony ślad nieudanego żądania. Odczyt transformacji H01 nie dowodził przyczyny błędu. Późniejszy test v5 zarejestrował poprawne `application/vnd.sap.adt.ddlSource+xml` i HTTP 201; dla klas `application/vnd.sap.adt.oo.classes.v4+xml` i HTTP 200.

1. Zachowaj oczyszczone parametry, wersję ABAP FS i pełny dostępny komunikat. Sprawdź wyszukiwaniem/odczytem, czy obiekt nie powstał częściowo. Nie ponawiaj operacji w ciemno i nie modyfikuj root CDS jako obejścia.
2. Dla dalszej diagnozy porównaj oczyszczone XML/nagłówki i wyjątek ze śladu ADT. Nowa próba musi mieścić się w bieżącym zleceniu; samo zbieranie diagnostyki nie upoważnia do tworzenia. Historyczny wrapper 2.12.0 pomijał szczegóły HTTP; w v5 korzystaj z opisanej wyżej diagnostyki. Samo `CREATION_FAILED` nie wystarcza do ustalenia przyczyny. Nie publikuj credentiali.
3. Gdy tworzenie przez MCP nie działa, użyj w Eclipse `H01_EN` kreatora Data Definition w `ZAINVOICER`, bez requestu przy nadal wyłączonym rejestrowaniu zmian. Przed tym sprawdź brak istniejącego/nieaktywnego obiektu; zachowaj zasadę jednego edytora. Wykonuj tworzenie, zapis i aktywację tylko w autoryzowanym zadaniu implementacyjnym. Diagnostyka nie upoważnia do tego fallbacku.
4. Po utworzeniu w ADT odczytaj stan przez MCP i zweryfikuj osobno źródło, składnię, aktywację oraz testy RAP/OData. Następnie, dopiero po zleceniu, wykonaj kontrolowany abapGit `Stage and Push`. Dla brudnego lokalnego checkout nie wykonuj automatycznego pull.

Dowody i ograniczenia incydentu: [dziennik RAP — diagnostyka tworzenia CDS](../journal/2026-10-24-abap-rap-composition.md#diagnostyka-tworzenia-cds-przez-abap-fs-mcp--2026-10-24). Kontrakt generatora: [kod upstream abap-adt-api](https://github.com/marcellourbani/abap-adt-api/blob/master/src/api/objectcreator.ts); sprawdzaj zgodność z lokalnie zainstalowaną wersją.

## Zmiana z Git do SAP

1. Zmianę plików wykonaj na `java-cap-ksef`, sprawdzając lokalny diff i zakres. Nie edytuj ręcznie serializowanych plików ABAP bez planu importu i porównania z systemem.
2. W abapGit dla tego samego repozytorium i pakietu użyj porównania/staged changes. Przejrzyj każdą zmianę, szczególnie usunięcia, metadane i pliki obiektów.
3. Importuj kontrolowanie przez abapGit zgodnie z dostępnymi uprawnieniami i stanem systemu. Sprawdź wynik oraz zależności; potem aktywuj obiekty w SAP ADT w wymaganej kolejności.
4. Wykonaj odpowiednią diagnostykę/testy i sprawdź usługę w SAP. Dopiero ta weryfikacja potwierdza stan systemu; plik w Git ani poprawna serializacja tego nie potwierdzają.
5. Jeżeli lokalna zmiana koliduje z nowszym stanem SAP albo Git, zatrzymaj synchronizację i rozstrzygnij konflikt na podstawie aktywnego źródła oraz uzgodnionego kierunku zmian. Nie nadpisuj konfliktu automatycznie.

## Gałąź, konflikty i bezpieczny przegląd

- Przed każdą synchronizacją sprawdź branch `java-cap-ksef`, repozytorium zdalne, status lokalnych zmian oraz wybrany pakiet.
- Porównaj staged changes abapGit i `git diff`; sprawdź nazwy obiektów, dodania/zmiany/usunięcia i pliki konfiguracyjne.
- Nie używaj przypadkowego `Pull`, nie akceptuj zbiorczego usuwania obiektów i nie rozwiązuj konfliktu przez bezwarunkowe nadpisanie SAP lub Git.
- Przy konflikcie wstrzymaj push/import, zachowaj obie wersje do porównania i ustal, która zmiana jest właściwa. Po rozwiązaniu ponownie przejrzyj cały diff.
- Nie zmieniaj `.abapgit.xml` bez osobnej, świadomej decyzji; zmiana folderu startowego lub logiki folderów może zmienić zakres synchronizowanych obiektów.

## GitHub PAT i credentiale

Według relacji użytkownika pierwszy eksport HTTPS uwierzytelniono GitHub Personal Access Token (PAT). Wprowadź token wyłącznie do zatwierdzonego, lokalnego mechanizmu przechowywania poświadczeń lub bezpiecznego promptu narzędzia. Nie zapisuj PAT w repozytorium, plikach Eclipse, zrzutach ekranu, logach, dokumentacji ani poleceniach shell. Ogranicz uprawnienia i czas ważności tokenu według wymagań GitHub; przy ujawnieniu unieważnij/rotuj go.

Nie kopiuj do Git także haseł SAP, tokenów KSeF, JWT, kluczy ani certyfikatów prywatnych. Dokumentacja nie zawiera wartości credentiali.

## Ograniczenia eksportu

abapGit serializuje definicje wspieranych obiektów (np. tabele, CDS, behavior, klasy, service definition/binding). Nie należy zakładać, że eksport obejmuje zawartość tabel aplikacyjnych. Definicje struktury i dane biznesowe to osobne zagadnienia; dane syntetyczne/konfiguracyjne wymagają osobnego uzgodnionego mechanizmu migracji.

Pliki eksportu są migawką serializacji. Nie potwierdzają same w sobie aktywacji, publikacji runtime, adresu usługi, kontraktu `$metadata`, autoryzacji ani obecności danych w SAP. Weryfikuj je w połączonym systemie ADT/OData. Zakres obecnego modelu jest opisany w [przewodniku RAP](ABAP_RAP_INVOICE_MODEL.md).

## Eclipse / ADT — uwagi diagnostyczne

Podczas wcześniejszych prób z Eclipse 2026-03/2026-06 wystąpiły problemy z instalacją pluginów oraz konflikt zależności ASM 9.9.1/9.10.1 (kontekst przekazany przez użytkownika). Działająca konfiguracja pierwszego eksportu to Eclipse 2026-09 z SAP ADT i abapGit. Traktuj wcześniejszy problem jako historyczny incydent, nie jako stałe wymaganie wersji.

Przy problemie instalacji lub starcie IDE:

1. zanotuj wersję Eclipse, ADT, abapGit i komunikat błędu;
2. sprawdź kompatybilność źródeł aktualizacji i zależności pluginów, w tym ewentualny konflikt bibliotek ASM;
3. nie kopiuj ręcznie losowych JAR-ów do instalacji i nie mieszaj pluginów z różnych wydań;
4. użyj wspieranej konfiguracji Eclipse/ADT albo czystego, odizolowanego profilu Eclipse i ponownie zweryfikuj połączenie SAP oraz repo abapGit;
5. oddziel problem IDE od błędu aktywacji obiektu lub problemu Git.

## Powiązane pliki

- [Model ABAP RAP faktur](ABAP_RAP_INVOICE_MODEL.md)
- [Stan projektu](../STATUS.md)
- [Plan](../ROADMAP.md)
- [Dziennik pierwszego eksportu](../journal/2026-10-24-abapgit-rap-initial-export.md)
