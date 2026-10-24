---
title: "Workflow abapGit w AInvoicer"
date: 2026-10-24
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

1. W systemie H01 odczytaj aktualny obiekt, zależności, pakiet i wymagany transport. Dla istniejących obiektów preferuj Codex + potwierdzone narzędzia ABAP FS/MCP; dla brakujących możliwości MCP użyj ADT. Zapisz, aktywuj i sprawdź kod w SAP zgodnie z zakresem zlecenia.
2. Otwórz połączone repo abapGit dla pakietu `ZAINVOICER` i sprawdź różnice/staged changes. Potwierdź, że dotyczą oczekiwanych obiektów i nie obejmują przypadkowych usunięć lub plików spoza zakresu.
3. Zsynchronizuj eksport przez funkcję `Stage and Push` w pluginie abapGit. Przed zatwierdzeniem sprawdź branch `java-cap-ksef`, listę plików i diff.
4. W lokalnym checkout wykonaj zwykły przegląd Git. Codex/VS Code/Obsidian czytają te same pliki; mogą przygotować zmianę dokumentacji lub kodu, ale nie aktywują jej w SAP.
5. Transport SAP pozostaje osobnym krokiem lifecycle. Nie utożsamiaj go z commitem ani push do GitHub.

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
