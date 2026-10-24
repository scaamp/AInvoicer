---
name: ainvoicer-abap-rap
description: "Rozwijaj i diagnozuj ABAP Cloud, CDS, RAP oraz publikację OData V4 w AInvoicer z użyciem MCP ABAP FS. Używaj przy zmianach obiektów SAP i weryfikacji kontraktu RAP. Nie używaj do samej konsumpcji OData w CAP Java ani do protokołu lub kryptografii KSeF."
---

# AInvoicer — ABAP Cloud i RAP

## Granice i przygotowanie

Pracuj według [AGENTS.md](../../../AGENTS.md) i bieżącego [STATUS](../../../docs/STATUS.md); sprawdź branch, `git status`, ROADMAP i właściwy ADR/dziennik. Linki są względem tego pliku; polecenia Git wykonuj z repozytorium. Przed zmianą podaj mały plan i kryteria akceptacji. Zachowuj zastane zmiany.

ABAP Environment w tym projekcie zawiera symulacyjny model `ZAINV_INV_H/I/REF`; obecny eksport RAP obejmuje tylko nagłówek przez `ZAPI_AINV_INV_H_O4`. Nie zakładaj standardowych tabel ani dokumentów S/4HANA, w tym `ZFI_ACDOCA`, jako modelu docelowego. CAP Java jest właścicielem procesu KSeF. Nie implementuj kryptografii ani nowego klienta KSeF w ABAP. Pliki `abap/src/` są eksportem do analizy, nie dowodem aktywacji, publikacji, autoryzacji lub dostępności OData runtime. Szczegółowy opis repo: [model RAP faktur](../../../docs/guides/ABAP_RAP_INVOICE_MODEL.md); synchronizacja: [workflow abapGit](../../../docs/guides/ABAPGIT_WORKFLOW.md).

## Workflow

### Domyślny tryb: Codex → ABAP FS/MCP → H01 → abapGit → Git

**Kod ABAP rozwijaj przede wszystkim w obiektach SAP H01, nie w lokalnych plikach `abap/src/`.** Codex korzysta z repo do analizy, ale po potwierdzeniu funkcji MCP odczytuje/modyfikuje live źródła obiektów i uruchamia diagnostykę. Jeżeli MCP nie wspiera operacji (zwłaszcza tworzenia obiektów RAP, DDIC, service bindings lub aktywacji), przejdź na Eclipse ADT, zamiast wymyślać działanie narzędzia.

Trzy stany kodu: (1) aktywne/nieaktywne źródło SAP (`H01_EN`, pakiet `ZAINVOICER`); (2) zatwierdzony eksport GitHub (`java-cap-ksef`); (3) lokalny checkout `D:\AInvoicer\abap\src\`. Żaden nie aktualizuje pozostałych automatycznie. `abapGit Pull` pobiera **GitHub**, nie niezacommitowane zmiany plików VS Code.

### Procedura live w SAP


1. **Odkryj możliwości.** Sprawdź, czy klient udostępnia połączony MCP ABAP FS. Odczytaj faktyczną listę narzędzi i ich schematy; przyporządkuj odczyt, wyszukiwanie, diagnostykę, zapis, aktywację, ABAP Unit i ATC. Potwierdź połączenie, system, klienta, środowisko i uprawnienia bez ujawniania credentiali. Funkcja rozszerzenia VS Code nie musi być dostępna przez MCP.
2. **Odczytaj stan SAP.** Pobierz aktualne źródła i właściwą wersję active/inactive. Ustal zależności, where-used, klucze, typy DDIC, asocjacje, projekcje, DCL i autoryzację behavior. Rozróżniaj istniejące zmiany użytkownika od własnych. Nie zapisuj na podstawie starej kopii z Git.
3. **Sprawdź API.** W dokumentacji SAP dla faktycznej wersji ABAP Cloud i metadanych obiektu potwierdź release status, interfejs, sygnatury i ograniczenia języka. Dla OData sprawdź aktywny service definition/binding, adres, `$metadata`, encje, klucze, nawigacje, operacje i autoryzację. Nie przenoś porad dla klasycznego ECC, dostępu do tabel metadanych ani unreleased APIs do ABAP Cloud. Gdy brakuje released API, opisz lukę zamiast wymyślać zamiennik.
4. **Zaplanuj minimalną zmianę.** Wskaż obiekty, wpływ na konsumentów, klucze, nullability i uprawnienia. Sprawdź pakiet, blokady i wymagany transport. Nie twórz ani nie zwalniaj transportu bez zlecenia; wykorzystaj już autoryzowany wybór. Jeśli zapis wymaga nieuzgodnionego transportu, przygotuj lokalny diff i wskaż potrzebną decyzję.
5. **Zaimplementuj.** Stosuj Clean ABAP: czytelne nazwy, krótkie metody o jednym celu, jawne błędy i testowalne zależności. Zachowaj kontrakt RAP i cykl transakcji frameworka; nie dodawaj `COMMIT WORK` w behavior. Istotną logikę integracyjną komentuj krótko po polsku. Zapisuj przez MCP tylko jeśli rzeczywiście wspiera tę operację; sprawdź semantykę zapisu i ponownie odczytaj wynik, w tym wersję inactive.
6. **Zweryfikuj składnię i jakość.** Uruchom dostępną diagnostykę dla właściwej wersji źródła i popraw błędy zależności. ATC uruchom z wariantem odpowiednim dla ABAP Cloud, podaj wariant i ustalenia; nie obchodź problemów automatycznymi wyłączeniami. Uruchom właściwe ABAP Unit. Składnia, ATC i testy to odrębne wyniki.
7. **Aktywuj warunkowo.** Tylko gdy aktywacja jest dostępna przez MCP, mieści się w zleceniu i dotyczy środowiska nieprodukcyjnego, aktywuj wskazane obiekty w kolejności zależności. Nie aktywuj wszystkich nieaktywnych obiektów systemu. Sprawdź odpowiedź, ponowny odczyt active i diagnostykę; zapis pliku nie oznacza aktywacji.
8. **Sprawdź usługę.** Potwierdź rzeczywisty binding, publikację, URL, `$metadata`, entity sets, klucze i nowe pola. Wykonaj autoryzowany odczyt syntetycznych danych; test akcji zapisującej tylko w zleconym zakresie. Sprawdź walidacje, DCL/behavior authorization i regresję kontraktu UI5/CAP. HTTP 200 dla metadanych nie dowodzi działania danych i autoryzacji.
9. **Eksportuj zatwierdzony stan.** Po sprawdzeniu aktywacji/testów wykonaj (po zleceniu użytkownika) `Stage and Push` z `H01_EN → abapGit → ZAINVOICER` na `java-cap-ksef`; wcześniej skontroluj kompletny diff, dodania/usunięcia i `.abapgit.xml`. Po eksporcie sprawdź lokalny `git status`; przy czystym drzewie wykonaj `git pull --ff-only origin java-cap-ksef` (lub EGit). Nie używaj reset/force-push, nie nadpisuj pracy lokalnej.

### Tryb alternatywny: patch lokalny → kontrolowany import

Gdy MCP nie obsługuje zapisu/aktywacji danego typu lub modelowanie wymaga Eclipse ADT, przygotuj niewielką zmianę w ADT albo lokalny patch kodu do review. Dopuszczalne pliki patcha to m.in. `*.clas.abap`, `*.ddls.asddls`, `*.bdef.asbdef`, `*.dcls.asdcls`; **nie edytuj automatycznie metadanych `*.tabl.xml`, `*.srvb.xml` czy `.abapgit.xml`**. Patch Git nie jest kodem aktywnym w SAP: po uzgodnieniu wykonaj Git commit/push, porównanie i bezpieczny abapGit Pull/import, następnie aktywację i test. Wstrzymaj import przy różnicy względem nowszej wersji SAP. Nigdy nie edytuj jednocześnie tego samego obiektu przez MCP i lokalnie; ustal jeden kierunek synchronizacji.

## Brak możliwości i wynik

Bez MCP można przeanalizować repozytorium i przygotować patch; oznacz go jako **niezweryfikowany w SAP**. Bez zapisu, aktywacji, ATC lub debugowania przez MCP podaj brakującą możliwość i najmniejszy krok w ADT/VS Code. Nie zakładaj uprawnień ani nie instaluj połączenia bez zlecenia. Nie wykonuj zmian w PROD.

Podaj zmienione obiekty/pliki, użyte narzędzia i wersje, wynik zapisu/aktywacji, składni, ATC, testów i OData, blokery oraz następny krok. Rozróżnij inspekcję, lokalny patch i live SAP. Aktualizuj STATUS i dziennik według AGENTS.md.

Do konsumpcji RAP przez CAP dołącz [ainvoicer-cap-java](../ainvoicer-cap-java/SKILL.md) tylko gdy zadanie obejmuje też Java/CDS CAP. Nie ładuj wszystkich Skills automatycznie.

## Źródła do sprawdzenia dla używanej wersji

- [ABAP FS: MCP i ograniczenia](https://marcellourbani.github.io/vscode_abap_remote_fs/mcp-server/).
- [ABAP FS: Clean ABAP, Code Writing Process i SAP Research](https://marcellourbani.github.io/vscode_abap_remote_fs/ai/skills/) — inspiracja procesu, nie gwarancja narzędzi ani reguł ABAP Cloud.
- [ABAP FS: ATC](https://marcellourbani.github.io/vscode_abap_remote_fs/code-quality/atc-analysis/) i [diagnostyka składni](https://marcellourbani.github.io/vscode_abap_remote_fs/code-quality/syntax-validation/).
- [SAP Clean ABAP](https://github.com/SAP/styleguides/blob/main/clean-abap/CleanABAP.md) i [SAP Help ABAP Cloud](https://help.sap.com/docs/abap-cloud) — sprawdzaj released APIs dla docelowego systemu.
