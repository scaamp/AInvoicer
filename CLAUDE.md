# CLAUDE.md — AInvoicer

## Zasada nadrzędna

**Najpierw przeczytaj i stosuj [AGENTS.md](./AGENTS.md).**
To jedyne źródło prawdy o architekturze, granicach komponentów, bezpieczeństwie KSeF, testach i sposobie pracy. Ten plik zawiera jedynie dodatkowe wskazówki dla Claude Code. Jeżeli coś jest sprzeczne, traktuj AGENTS.md jako nadrzędne, o ile bieżące polecenie użytkownika nie ustala inaczej.

## Kontekst w 30 sekund

- Repo: `scaamp/AInvoicer`; gałąź rozwoju architektury: `java-cap-ksef`.
- Cel: produkcyjnie zaprojektowana aplikacja SAP Fiori do faktur zakupowych/sprzedażowych i KSeF 2.0; najpierw E2E TEST.
- **CAP Java + Spring Boot**: nowy centralny backend i właściciel procesu KSeF; **oficjalne Java SDK MF działa jako biblioteka w tej samej JVM**.
- **ABAP Cloud/RAP**: pozostaje adapterem OData V4 do `ZFI_ACDOCA` i danych SAP, nie nowym silnikiem KSeF.
- **SAPUI5**: istniejący frontend; docelowo OData V4 z CAP.
- **Node.js/Python**: zachowane dotychczasowe usługi AI.
- **Baza CAP**: lokalnie H2 / SQLite na potrzeby developmentu; docelowo HANA Cloud do operacji, statusów i audytu, bez kopiowania całej tabeli SAP.
- **Cloud Foundry**: docelowe środowisko uruchomieniowe CAP Java.

## Nawigacja po repo

```text
abap/                   # istniejące źródła ABAP/CDS/RAP i ZFI_ACDOCA
sapui5/ainvoicer/       # istniejący frontend UI5
node/                   # istniejące usługi Node
python/                 # istniejące usługi Python
cap-java/               # PLANOWANY nowy backend CAP; może jeszcze nie istnieć
```

Istnieje też potwierdzony eksperyment KSeF E2E na oddzielnej gałęzi `feature/ksef-java-e2e-runner`. Możesz go przeanalizować bez modyfikowania bieżącej gałęzi; nie kopiuj bez audytu kontrolerów demo, zwracania JWT ani globalnego stanu sesji.

## Sposób wykonywania zadań

1. Sprawdź branch i zmiany lokalne, a następnie stan konkretnych plików.
2. Przed implementacją przedstaw krótki plan, zakres plików i kryteria akceptacji.
3. Dziel zadanie na małe sprawdzalne kroki. W razie braku dostępu do SAP/BTP/KSeF wykonaj to, co można przetestować lokalnie, i zgłoś blokadę.
4. Kod CAP oprzyj o rzeczywiste wzorce SAP CAP Java (CDS, Event Handlers, CQN, Remote Services, Persistence, Spring Boot). Nie zastępuj CAP zestawem zwykłych kontrolerów REST.
5. Zanim użyjesz klasy/metody KSeF SDK lub nazwy encji OData, zweryfikuj aktualną sygnaturę / `$metadata`. Nie halucynuj kontraktów.
6. Nie przenoś trwałych operacji KSeF do synchronicznego requestu UI; zapisuj zadanie i wykonuj bezpieczne przetwarzanie po commit.
7. Wykonaj testy i podaj dowody; nie oznaczaj niczego jako `SUCCESS`, jeśli nie było prawdziwego sukcesu.
8. Po zadaniu podsumuj: co zmieniono, build/tests PASS/FAIL, ryzyka, kolejny krok.

## Bezpieczeństwo

- Nie wyświetlaj i nie commituj sekretów, JWT, kluczy prywatnych ani certyfikatów produkcyjnych.
- Nigdy nie wystawiaj KSeF JWT do UI5 ani osobnego publicznego endpointu dla tokenów.
- Żadnego PROD i żadnych niespodziewanych wysyłek/ponowień faktur.
- Nie wykonuj automatycznie nieodwracalnych operacji Git, deploymentów ani działań uruchamiających koszty.

## Preferowany pierwszy etap

Utwórz (dopiero na zlecenie) szkielet `cap-java/`: działające CAP Java + Spring Boot, minimalny CDS model `KsefOperations`, OData V4, prosty handler, H2 lokalnie i testy. Dopiero później podłącz RAP Remote OData oraz oficjalny KSeF Java SDK.

Komunikacja z użytkownikiem i komentarze edukacyjne: **po polsku**. Nazwy API, klas, metod, artefaktów i kontraktów: konsekwentnie po angielsku.
