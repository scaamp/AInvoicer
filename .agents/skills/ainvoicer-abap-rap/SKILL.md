---
name: ainvoicer-abap-rap
description: "Rozwijaj i diagnozuj ABAP Cloud, CDS, RAP oraz publikację OData V4 w AInvoicer z użyciem MCP ABAP FS. Używaj przy zmianach obiektów SAP i weryfikacji kontraktu RAP. Nie używaj do samej konsumpcji OData w CAP Java ani do protokołu lub kryptografii KSeF."
---

# AInvoicer — ABAP Cloud i RAP

## Granice i przygotowanie

Pracuj według [AGENTS.md](../../../AGENTS.md) i bieżącego [STATUS](../../../docs/STATUS.md); sprawdź branch, `git status`, ROADMAP i właściwy ADR/dziennik. Linki są względem tego pliku; polecenia Git wykonuj z repozytorium. Przed zmianą podaj mały plan i kryteria akceptacji. Zachowuj zastane zmiany.

ABAP Environment w tym projekcie zawiera symulacyjny model `ZAINV_INV_H/I/REF`; obecny eksport RAP obejmuje tylko nagłówek przez `ZAPI_AINV_INV_H_O4`. Nie zakładaj standardowych tabel ani dokumentów S/4HANA, w tym `ZFI_ACDOCA`, jako modelu docelowego. CAP Java jest właścicielem procesu KSeF. Nie implementuj kryptografii ani nowego klienta KSeF w ABAP. Pliki `abap/src/` są eksportem do analizy, nie dowodem aktywacji, publikacji, autoryzacji lub dostępności OData runtime. Szczegółowy opis repo: [model RAP faktur](../../../docs/guides/ABAP_RAP_INVOICE_MODEL.md); synchronizacja: [workflow abapGit](../../../docs/guides/ABAPGIT_WORKFLOW.md).

## Workflow

### Synchronizacja abapGit

Rozróżniaj EGit/lokalny checkout, abapGit ADT, SAP ADT i transport SAP. Pakiet `ZAINVOICER` mapuje się przez `.abapgit.xml` do `/abap/src/` (`FOLDER_LOGIC=FULL`); branch repo to `java-cap-ksef`. Przy edycji plików ABAP sprawdź aktywne źródło SAP, a przed importem/eksportem przejrzyj staged changes, branch i zakres obiektów. Nie wykonuj przypadkowego Pull ani zbiorczego usuwania; konflikty rozstrzygaj przez jawne porównanie. Git push nie aktywuje ani nie transportuje obiektów. Nie zapisuj PAT ani credentiali w repo. Pełny proces opisuje przewodnik abapGit.

Eksport pokazuje tabele `ZAINV_INV_H`, `ZAINV_INV_I`, `ZAINV_INV_REF`, ale CDS/behavior/DCL/service obejmują obecnie tylko root nagłówka. Nie zakładaj compositions ani nawigacji do dzieci. W pierwszej kolejności potwierdź ten zakres w bieżących plikach i systemie.

1. **Odkryj możliwości.** Sprawdź, czy klient udostępnia połączony MCP ABAP FS. Odczytaj faktyczną listę narzędzi i ich schematy; przyporządkuj odczyt, wyszukiwanie, diagnostykę, zapis, aktywację, ABAP Unit i ATC. Potwierdź połączenie, system, klienta, środowisko i uprawnienia bez ujawniania credentiali. Funkcja rozszerzenia VS Code nie musi być dostępna przez MCP.
2. **Odczytaj stan SAP.** Pobierz aktualne źródła i właściwą wersję active/inactive. Ustal zależności, where-used, klucze, typy DDIC, asocjacje, projekcje, DCL i autoryzację behavior. Rozróżniaj istniejące zmiany użytkownika od własnych. Nie zapisuj na podstawie starej kopii z Git.
3. **Sprawdź API.** W dokumentacji SAP dla faktycznej wersji ABAP Cloud i metadanych obiektu potwierdź release status, interfejs, sygnatury i ograniczenia języka. Dla OData sprawdź aktywny service definition/binding, adres, `$metadata`, encje, klucze, nawigacje, operacje i autoryzację. Nie przenoś porad dla klasycznego ECC, dostępu do tabel metadanych ani unreleased APIs do ABAP Cloud. Gdy brakuje released API, opisz lukę zamiast wymyślać zamiennik.
4. **Zaplanuj minimalną zmianę.** Wskaż obiekty, wpływ na konsumentów, klucze, nullability i uprawnienia. Sprawdź pakiet, blokady i wymagany transport. Nie twórz ani nie zwalniaj transportu bez zlecenia; wykorzystaj już autoryzowany wybór. Jeśli zapis wymaga nieuzgodnionego transportu, przygotuj lokalny diff i wskaż potrzebną decyzję.
5. **Zaimplementuj.** Stosuj Clean ABAP: czytelne nazwy, krótkie metody o jednym celu, jawne błędy i testowalne zależności. Zachowaj kontrakt RAP i cykl transakcji frameworka; nie dodawaj `COMMIT WORK` w behavior. Istotną logikę integracyjną komentuj krótko po polsku. Zapisuj przez MCP tylko jeśli rzeczywiście wspiera tę operację; sprawdź semantykę zapisu i ponownie odczytaj wynik, w tym wersję inactive.
6. **Zweryfikuj składnię i jakość.** Uruchom dostępną diagnostykę dla właściwej wersji źródła i popraw błędy zależności. ATC uruchom z wariantem odpowiednim dla ABAP Cloud, podaj wariant i ustalenia; nie obchodź problemów automatycznymi wyłączeniami. Uruchom właściwe ABAP Unit. Składnia, ATC i testy to odrębne wyniki.
7. **Aktywuj warunkowo.** Tylko gdy aktywacja jest dostępna przez MCP, mieści się w zleceniu i dotyczy środowiska nieprodukcyjnego, aktywuj wskazane obiekty w kolejności zależności. Nie aktywuj wszystkich nieaktywnych obiektów systemu. Sprawdź odpowiedź, ponowny odczyt active i diagnostykę; zapis pliku nie oznacza aktywacji.
8. **Sprawdź usługę.** Potwierdź rzeczywisty binding, publikację, URL, `$metadata`, entity sets, klucze i nowe pola. Wykonaj autoryzowany odczyt syntetycznych danych; test akcji zapisującej tylko w zleconym zakresie. Sprawdź walidacje, DCL/behavior authorization i regresję kontraktu UI5/CAP. HTTP 200 dla metadanych nie dowodzi działania danych i autoryzacji.

## Brak możliwości i wynik

Bez MCP można przeanalizować repozytorium i przygotować patch; oznacz go jako **niezweryfikowany w SAP**. Bez zapisu, aktywacji, ATC lub debugowania przez MCP podaj brakującą możliwość i najmniejszy krok w ADT/VS Code. Nie zakładaj uprawnień ani nie instaluj połączenia bez zlecenia. Nie wykonuj zmian w PROD.

Podaj zmienione obiekty/pliki, użyte narzędzia i wersje, wynik zapisu/aktywacji, składni, ATC, testów i OData, blokery oraz następny krok. Rozróżnij inspekcję, lokalny patch i live SAP. Aktualizuj STATUS i dziennik według AGENTS.md.

Do konsumpcji RAP przez CAP dołącz [ainvoicer-cap-java](../ainvoicer-cap-java/SKILL.md) tylko gdy zadanie obejmuje też Java/CDS CAP. Nie ładuj wszystkich Skills automatycznie.

## Źródła do sprawdzenia dla używanej wersji

- [ABAP FS: MCP i ograniczenia](https://marcellourbani.github.io/vscode_abap_remote_fs/mcp-server/).
- [ABAP FS: Clean ABAP, Code Writing Process i SAP Research](https://marcellourbani.github.io/vscode_abap_remote_fs/ai/skills/) — inspiracja procesu, nie gwarancja narzędzi ani reguł ABAP Cloud.
- [ABAP FS: ATC](https://marcellourbani.github.io/vscode_abap_remote_fs/code-quality/atc-analysis/) i [diagnostyka składni](https://marcellourbani.github.io/vscode_abap_remote_fs/code-quality/syntax-validation/).
- [SAP Clean ABAP](https://github.com/SAP/styleguides/blob/main/clean-abap/CleanABAP.md) i [SAP Help ABAP Cloud](https://help.sap.com/docs/abap-cloud) — sprawdzaj released APIs dla docelowego systemu.
