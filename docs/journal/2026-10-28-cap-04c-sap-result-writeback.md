---
title: "CAP-04C — eksport CAP-04B i trwały writeback CAP → SAP"
date: 2026-10-28
type: journal
status: zakonczony
tags: [ainvoicer, cap-java, rap, outbox, cap-04c, integration]
---

# CAP-04C — trwały writeback wyniku

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [ADR-0003](../decisions/ADR-0003-cap-sap-result-writeback.md)

## Date

2026-10-28 (Europe/Warsaw).

## Branch

`java-cap-ksef`. Początkowy HEAD `2ff6545`, lokalnie sześć plików dokumentacji CAP-04B. Użytkownik zlecił commit/push i kontynuację CAP-04C; następnie zatwierdził konkretny przegląd eksportu SAP.

## Goal

Opublikować CAP-04B, a następnie wdrożyć CAP Write Adapter, trwałe zlecenie/worker, idempotencję i historię zastosowania wyniku. Bez HTTP WRITE w H01, nadawania praw, KSeF TEST/PROD, zmian UI5/Node/Python lub wdrożenia BTP. CAP-04D nie został rozpoczęty.

## Initial State

Odczytano AGENTS, Skills ABAP/RAP i CAP Java, STATUS/ROADMAP, ADR i dziennik CAP-04B. H01 zawierał aktywną akcję, a Git wyłącznie starsze źródła. CAP miał read-only adapter i nie miał producenta potwierdzonych wyników KSeF. Nowe prawo SAP pozostawało bez nadania.

## Implementation

### Commit i eksport SAP

- `8de662f`: dokumentacja CAP-04B, commit i push lokalnego Git.
- Przegląd MCP abapGit wskazał 31 plików CAP-04B: kod/metadata, testy, prototypy i autoryzację z mapowaniem aktywności SIA3. Bez usunięć i bez zastanej zmiany `ZCS_AINV_H01`.
- Narzędzie wymagało zatwierdzenia przygotowanego review; użytkownik je zatwierdził. Wykonano pojedynczy combined commit+push, bez automatycznego ponawiania.
- `525e48d`: eksport SAP potwierdzony przez remote refs GitHub, status abapGit i lokalny fetch. Backendowe `accepted` nie zostało potraktowane jako samodzielny dowód publikacji.
- Lokalne pliki rozpoczętego importu EDMX tymczasowo zachowano w dedykowanym stash, wykonano fast-forward na czystym drzewie, następnie przywrócono pliki i usunięto wykorzystany stash.
- W SAP pozostały wyłącznie nieobjęte zadaniem: usunięcia `ZCL_ABAPFS_GIT_PROBE` i nowy scenariusz komunikacyjny. Nie importowano niczego do H01 ani nie zmieniano tam kodu w CAP-04C.

### Model i adapter

Uwierzytelniony GET rzeczywistego `$metadata` HTTP200 posłużył do importu `srv/external/AinvoicerH01.edmx` → `.cds` z `--keep-namespace`. Model posiada publiczną collection-bound akcję i parametry zgodne z CAP-04B; wewnętrzna akcja nie jest publikowana. Companion CDS wymaga `internal-user`. Dotychczasowy adapter READ korzysta z nowego namespace i dodatkowo odczytuje wersję oraz cztery pola wyniku.

`SapKsefResultCommand` jest niezmiennym payloadem OUTBOUND/TEST z wymaganymi UUID, NIP, CompanyCode i wersją o precyzji do 100 ns. `SapKsefResultWriteAdapter` emituje wygenerowany kontekst `ApplyKsefResult` przez CAP Remote Service. Klient CAP/Cloud SDK obsługuje Destination i CSRF. Nie używamy PATCH ani ręcznego klienta Basic Auth w kodzie aplikacji.

`Outcome` jest interpretowany także dla HTTP200. Sukces musi wskazywać właściwy UUID, numer, TEST, timestamp i request ID; nieznana lub niespójna odpowiedź wymaga rekoncyliacji. Ten sam request i wersja są zachowane przy retry. Nie wykonujemy automatycznego GET nowej wersji, który obszedłby CAS.

### Trwałe przetwarzanie

`SapKsefResultWriteback.enqueue` jest wyłącznie wewnętrznym API Java dla aktywnego kontekstu systemowego i transakcji. Źródłem jest potwierdzona operacja `ACCEPTED / SEND_INVOICE / OUTBOUND / TEST`, z numerem i UUID SAP. Powiązana faktura CAP, jeśli istnieje, musi zgadzać się co do NIP/kierunku/korelacji. Publiczny CREATE nadal wymusza NEW; nowe readonly `environment` jest ustawiane na TEST.

Dodano lokalne, niewystawione encje `SapResultWrites` i `SapResultWriteHistory`. UUID operacji stanowi klucz zlecenia i stabilny RAP WriteRequestId. Wiersz operacji jest blokowany podczas enqueue; klucz DB zapewnia trwałą unikalność. Zapis polecenia, historii QUEUED i wpisu `SapResultOutbox` jest atomowy. Powtórne enqueue identycznego polecenia nie tworzy nowego wpisu; zmieniony payload jest odrzucany.

Worker korzysta z natywnego persistent outboxa CAP, uruchamianego po commit. Sukces i konflikty są zapisywane lokalnie z historią. Przejściowa awaria bez Retry-After powoduje retry tego samego polecenia, maksymalnie pięć nieudanych publikacji. 429 i jawny Retry-After wstrzymują automat; `retryNotBefore` przechowuje termin do kontrolowanego wznowienia. Nie ma publicznego endpointu retry.

Zdalne wywołanie ma osobny ChangeSet, aby odmowa SAP nie wycofała lokalnego rozstrzygnięcia workera. Błędy zapisują wyłącznie bezpieczny kod. Wyłączono kategorię CAP 5.2 logującą surowe body zdalnego błędu. Odczyt Retry-After korzysta z publicznego typu błędu Cloud SDK, wersjonowanego przez istniejący BOM.

W testowanym single tenant/H2 cicha kolejka nie wznawiała się po samym restarcie collectora. `ApplicationReadyEvent` sprawdza własne PENDING i w razie potrzeby publikuje pusty sygnał `wakeCollector` przez publiczne API outboxa. Nie modyfikuje rekordów wewnętrznej kolejki ani jej liczników. Test restartu i rzeczywistego collectora potwierdził późniejsze przetwarzanie.

Transport SAP domyślnie wyłączony przez `ainv.sap-write.enabled=false`. Opcjonalny profil `writeback-local` pozwala na plikową H2. Domyślna baza development nadal jest in-memory — ten profil nie zapewnia trwałości po restarcie. Nie zmieniano istniejących uprawnień SAP.

## Changed Files

- CAP: import EDMX/CDS, companion `srv/ainv-h01.cds`, zdalna konfiguracja i READ adapter.
- CAP: `SapKsefResultCommand`, `SapKsefResultWriteAdapter`, `SapKsefResultWriteback`.
- CDS: środowisko operacji, dwie encje wewnętrzne; publiczna projekcja zachowuje ochronę pól.
- Konfiguracja: persistent outbox, bramka transportu, bezpieczne logowanie, profil plikowej H2 i ignore lokalnej bazy.
- Testy: lokalny kontrakt HTTP, transakcje/konkurencja workera, restart H2/CAP, regresja OData i ochrona zewnętrznego modelu.
- Dokumentacja: ten dziennik, ADR-0003, STATUS, ROADMAP, README, aktualizacja przewodnika RAP i dopisek eksportu CAP-04B.
- Nie dodawano wygenerowanego Java/CSN/SQL/JAR, zależności `node_modules`, baz, credentiali ani logów. Importowane EDMX/CDS są źródłowym kontraktem, nie artefaktem buildu.

## Commands Executed

- Git: inspekcja/status/diff, `git fetch`, jawny `git add` dokumentacji, commit/push, później `git pull --ff-only` po potwierdzonym eksporcie SAP.
- MCP: `abapfs_abapgit_repositories`, capabilities, status, diff, prepare_commit, zatwierdzony commit_and_push, remote_refs; read-only resolve aktywnego bindingu.
- Runtime H01: wyłącznie uwierzytelniony GET `$metadata` do importu. Credentiale pozostały w istniejącej lokalnej konfiguracji/pamięci procesu, nie w argumentach narzędzi ani repo.
- `node node_modules/@sap/cds-dk/bin/cds.js import srv/external/AinvoicerH01.edmx --as cds --no-save --no-copy --keep-namespace --force`.
- Weryfikacja rzeczywistych API przez wygenerowane interfejsy i `javap` bibliotek CAP/Cloud SDK.
- Z `cap-java/`: iteracyjne testy wskazanych klas, następnie `mvn clean verify -Dcds.cds.skipVersionCheck=true`.
- Wersje: JDK 25.0.4.1, Maven 3.9.16, CAP Java 5.2.0, Spring Boot 4.1.1, Cloud SDK BOM 5.33.0; build raportuje CDS 10.2.1 / compiler 7.2.1 / dk 10.2.0.

## Tests & Results

- **Pełny build PASS:** `mvn clean verify -Dcds.cds.skipVersionCheck=true`, **46 testów, 0 failures/errors**; kompilacja CDS/Java, H2, JAR i repackage PASS. Późniejszą dodatkową asercję ochrony importowanego modelu sprawdzono osobnym przebiegiem.
- **Lokalny HTTP:** rzeczywisty CAP Remote Service/Cloud SDK do lokalnego serwera; URI kolekcji z właściwym namespace, Basic Auth z fikcyjnymi danymi, CSRF, 7 cyfr wersji, identyczny payload retry, wszystkie konflikty przy HTTP200, nieznany wynik, 403/503, Retry-After jako sekundy/data HTTP i wyłączony transport.
- **Transakcje H2:** atomowe polecenie/outbox/historia, rollback, dwa równoległe enqueue, niezmienność payloadu, odmowa niepotwierdzonego/INBOUND/PROD/niewłaściwego typu, odmowa zwykłemu użytkownikowi, duplikat dostarczenia, konflikty, retry po niejednoznacznym błędzie, zapis odmowy i terminu Retry-After.
- **Restart:** dwie rzeczywiste instancje kontekstu Spring/CAP i ta sama plikowa H2 w katalogu tymczasowym; kolejka przetrwała restart, rzeczywisty collector wykonał retry, mock SAP zwrócił ALREADY_APPLIED; ID i pełna wersja bez zmian. Nie jest to test osobnych JVM, twardej awarii w trakcie HTTP, HANA ani wielu instancji CF.
- **Profil `writeback-local`:** dodatkowy przebieg `SapResultOutboxRecoveryTest` z rzeczywiście aktywnym profilem i nadpisaniem ścieżki bazy na katalog tymczasowy — **1/1 PASS**. Nie utworzono bazy z danymi w repozytorium.
- **Decimal po imporcie:** uzupełniona asercja lokalnego testu READ potwierdza zachowanie `123.45` jako BigDecimal mimo ostrzeżenia importera o zmiennej skali — **1/1 PASS**. Nie zaokrąglano ręcznie modelu importowanego.
- **Publiczne OData:** istniejące testy izolacji/uprawnień pozostają PASS. Writeback, historia i akcja SAP nie są wystawione w KsefService. Klientowski environment=PROD przy CREATE kończy się environment=TEST i status=NEW.
- **Uzupełniający build PASS:** `verify` z wyborem `SapInvoiceRemoteAdapterLiveTest,AinvoicerH01RemoteServiceSecurityIntegrationTest`, tag LIVE jawnie włączony. **3/3 PASS**: dwa testy ochrony modelu (w tym nowy test skompilowanego `internal-user` i odmowy business user) oraz jeden rzeczywisty READ H01. Wynik READ: 4 nagłówki, 9 pozycji, 6 referencji, zgodne UUID. Żaden test nie wywołuje HTTP WRITE w H01 ani KSeF.

## Problems Encountered

- `ServiceException` CAP mapuje zdalny 403 na swój HTTP500. Wersja 5.2 przechowuje rzeczywisty kod w strukturalnym argumencie komunikatu; poprawiono klasyfikację. Oryginalny wyjątek HTTP jest dostępny w cause, stamtąd pobieramy Retry-After.
- Błąd Remote Service anuluje jego ChangeSet. Wyodrębniono zdalną granicę; test potwierdza możliwość późniejszego commit lokalnego wyniku 403.
- `getProvidedUserInfo()` nie reprezentuje aktywnego nadpisanego kontekstu systemowego. Kontrola używa obecnie `RequestContext.getCurrent(runtime).getUserInfo()`, bez osłabiania wymogu systemowego i aktywnej transakcji.
- Pierwsze testy restartu pozostawiały PENDING bez wywołania workera. Samo wydłużenie oczekiwania i sprawdzenie schedulera nie rozwiązało problemu. Dopiero sygnał przez publiczne API po ApplicationReady uruchomił istniejącą kolejkę; finalny test PASS.
- Import EDMX zgłasza ostrzeżenia o zmiennej skali Decimal i niektórych adnotacjach słowników SAP. Nie zmieniano ręcznie importowanych typów; nie tworzą lokalnych tabel. Build nie zgłasza błędów kompilacji.
- `npm ci` zgłosił dwie istniejące podatności high w narzędziach development. Nie wykonywano `audit fix --force` ani nie zmieniano locka/zależności poza zadaniem.

Kontrola końcowa: UTF-8/frontmatter, lokalne linki Markdown, parsowanie XML i wzorce sekretów PASS; `git diff --check` PASS (tylko ostrzeżenia LF/CRLF). Kontrola rzeczywistego hasła z lokalnej konfiguracji względem logów zadania nie wykazała jego obecności; wartości nie wyświetlano. Pliki credentiali i lokalnej bazy są ignorowane przez Git.

## Decisions Made

[ADR-0003](../decisions/ADR-0003-cap-sap-result-writeback.md): natywny persistent outbox, jeden niezmienny payload per potwierdzona operacja, CAS ze snapshotu, jawne rozstrzygnięcia, transport domyślnie wyłączony.

## Current State

CAP-04B opublikowany w Git; CAP-04C zaimplementowany i zweryfikowany lokalnie oraz przez LIVE READ w podanym zakresie. SDK/producent prawdziwych ACCEPTED nadal nie istnieje — fixture nie jest dowodem potwierdzenia KSeF. H01 pozostaje bez nadania nowego prawa. Nie wykonano HTTP WRITE, zmian danych SAP, KSeF ani deployu. Końcowy commit CAP-04C obejmuje kod i ten raport; identyfikator publikacji znajduje się w historii `java-cap-ksef`.

## Next Steps

1. CAP-04D dopiero po osobnej zgodzie: ograniczone prawa i dane testowe, HTTP WRITE, commit/no-op/ETag i dwa równoległe zapisy akcji.
2. Przed uruchomieniem procesu: zaufany producent ACCEPTED przez SDK, trwała baza docelowa i obsługa operacyjna konfliktów, wyczerpanych retry i Retry-After. KSeF send nie jest częścią obecnego workera.
3. Przed CF/multitenancy: osobne testy HANA, lease po twardej awarii i współbieżnego dispatch wielu instancji. Lokalny dysk CF nie jest trwałą bazą.

## Lokalny profil trwałości

Opcjonalnie, z katalogu `cap-java/`, po buildzie i na **pustej** lokalnej bazie:

```powershell
java -jar srv/target/cap-java-exec.jar --spring.profiles.active=writeback-local --spring.sql.init.mode=always
```

Przy kolejnych uruchomieniach pomiń `--spring.sql.init.mode=always`; profil zachowuje istniejącą bazę. Nie jest to mechanizm migracji schematu istniejących danych. Pliki `.local/` są ignorowane przez Git. Profil nie włącza transportu SAP i nie jest konfiguracją Cloud Foundry.

## Źródła

- [CAP Java Remote Services](https://cap.cloud.sap/docs/java/cqn-services/remote-services).
- [CAP Java Event Queues](https://cap.cloud.sap/docs/java/event-queues).
- [CAP Java ChangeSet Contexts](https://cap.cloud.sap/docs/java/event-handlers/changeset-contexts).
- Kontrakt runtime H01 i interfejsy z lokalnych wersji bibliotek; pełne wyniki SAP w [CAP-04B](2026-10-28-cap-04b-rap-write-contract.md).
