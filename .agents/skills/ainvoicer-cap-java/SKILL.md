---
name: ainvoicer-cap-java
description: "Implementuj backend AInvoicer w cap-java/: modele i usługi CDS, OData V4, Java Event Handlers, CQN, persystencję i Remote Services do RAP. Używaj dla CAP Java, Spring Boot, H2 i Maven. Nie używaj do samej zmiany obiektów ABAP ani szczegółów protokołu KSeF."
---

# AInvoicer — CAP Java

## Granice i przygotowanie

Przeczytaj [AGENTS.md](../../../../AGENTS.md), [STATUS](../../../../docs/STATUS.md), ROADMAP, właściwy dziennik i [ADR-0001](../../../../docs/decisions/ADR-0001-cap-java-ksef-architecture.md). Sprawdź branch, `git status`, rzeczywiste `cap-java/` i zmiany użytkownika. Linki są względem tego pliku; polecenia poniżej względem repozytorium.

CAP Java + Spring Boot jest backendem biznesowym w jednej JVM; oficjalne SDK MF pozostaje biblioteką za adapterem aplikacji. SAP jest źródłem danych finansowych, CAP przechowuje operacje KSeF, korelacje, referencje, statusy i audyt. Nie replikuj całej `ZFI_ACDOCA`, nie otwieraj JDBC do bazy ABAP ani nie wykonuj pozornych JOIN-ów SQL lokalnych encji ze zdalnym OData. Nie migruj istniejących UI5/Node/Python w ramach zadania CAP.

## Workflow

1. **Sprawdź kod i wersje.** Odczytaj parent i `srv/pom.xml`, npm/lock, profile, `db/`, `srv/`, Javę i testy. Rozróżnij modele własne od wygenerowanych. Nie wykonuj ponownie `cds init` na istniejącym projekcie. Sprawdź `java -version`, `mvn -version`, CAP/CDS/Spring Boot w konfiguracji i buildzie. Nie obniżaj wymagań POM, by ukryć problem środowiska.
2. **Zweryfikuj dokumentację CAP Java.** Dopasuj przykłady, zależności, adnotacje i sygnatury do wersji projektu; nie przenoś API CAP Node.js do Javy. Przed większą zmianą podaj plan, zakres i kryteria, np. kompilacja CDS, wynik handlera i `$metadata`.
3. **Zaprojektuj mały kontrakt.** Określ encje, projekcje usług, klucze, typy, walidację i autoryzację OData V4. Dla KSeF rozdziel `KsefOperations` i `KsefInvoices`, kierunek, stan techniczny/biznesowy, korelację SAP i idempotency key z trwałą unikalnością. Duże XML/UPO wymagają wybranego trwałego magazynu, z metadanymi/referencjami w DB. H2 służy developmentowi; docelowa SAP HANA Cloud wymaga konfiguracji i potwierdzenia usług BTP.
4. **Wykorzystaj CAP.** Zachowaj standardowe CRUD i transakcje frameworka. Handler dodawaj do własnej logiki: `@Before` do walidacji/wzbogacania wejścia, `@On` do obsługi zdarzenia/akcji lub przekazania do adaptera, `@After` do przetwarzania wyniku. Rejestrację `EventHandler`, komponent Spring i powiązanie serwisu/zdarzenia sprawdź dla używanej wersji. Nie zastępuj zwykłego CRUD zbędnym `@On`; przy własnej obsłudze poprawnie ustaw wynik/zakończenie kontekstu. Stosuj konstruktorowe dependency injection, CQN i `PersistenceService` dla danych lokalnych. Nie edytuj wygenerowanych klas. Istotne metody integracyjne komentuj krótko po polsku.
5. **Remote OData, jeśli w zakresie.** Pobierz autoryzowane `$metadata` wdrożonego RAP; potwierdź service definition/binding, URL, entity sets, typy i klucze. Importuj EDMX narzędziem zgodnym z projektem; sprawdź konfigurację i adnotacje encji zewnętrznych, by nie tworzyć ich lokalnych tabel. Konfiguruj Remote Service i SAP Cloud SDK Destination/OAuth2 według realnego środowiska; wstrzykuj właściwy serwis zamiast kierować CQN do lokalnej persystencji. Testuj mapowanie/projekcje i błędy zdalne. Ograniczenia filtrów i asocjacji potwierdzaj z API. Bez kontraktu przygotuj adapter z mockiem i zgłoś brak live; nie wymyślaj metadanych.
6. **Oddziel efekty KSeF od transakcji OData.** Akcja zapisuje operację i trwałe zlecenie, worker/outbox przetwarza je po commit. Samo `@Async`, pamięciowa kolejka lub `@After` nie zapewnia trwałości po restarcie. Sprawdź semantykę i retry Event Queues w używanej wersji; ponowienie workera nie może ponownie wysłać niejednoznacznej operacji. Szczegóły protokołu realizuj z [ainvoicer-ksef](../ainvoicer-ksef/SKILL.md), gdy zadanie ich wymaga.
7. **Sprawdź bezpieczeństwo i testy.** Autoryzuj serwis i kontekst podatnika, waliduj wejście/rozmiary, zachowaj restrykcyjny CORS i bezpieczne błędy. Sekrety/JWT nie należą do modelu publicznego. Testuj zmienione zachowanie handlerów/adapters, CDS, persystencję H2 i OData. Dla workflow sprawdź rollback, unikalność, konkurencję i restart; oddziel mock od autoryzowanego odczytu SAP.
8. **Zbuduj i uruchom w wymaganym zakresie.** Z `cap-java/` wykonaj `mvn clean verify` (lub istniejący wrapper, jeśli repo go ma). Nie uruchamiaj równoległych buildów na tym samym `target/`. Jeśli zadanie wymaga runtime, uruchom aplikację według konfiguracji Maven/Spring i sprawdź faktyczny port, ścieżkę OData, `$metadata`, encję/akcję i H2 na syntetycznych danych. Nie zgaduj endpointu. Udany JAR nie potwierdza runtime; `No tests to run` nie zalicza testów funkcjonalnych.

## Wynik i nauka

Zaktualizuj STATUS, dziennik i w razie zmiany postępu ROADMAP. Podaj pliki, polecenia, wersje, wyniki build/test/runtime, blokery i następny krok. Nie zmieniaj statusu wcześniejszego błędu builda bez nowego dowodu. Wyjaśnij krótko mechanizm CAP i pomocną analogię RAP: CDS entity ↔ ABAP CDS, action ↔ RAP action, event handler ↔ behavior implementation, Persistence Service ↔ ABAP SQL, Remote Service ↔ konsumpcja OData. Wskaż różnice transakcji/autoryzacji wpływające na rozwiązanie; analogie nie oznaczają identycznych API.

Do analizy lub zmiany obiektów SAP dołącz [ainvoicer-abap-rap](../ainvoicer-abap-rap/SKILL.md) tylko jeśli wymaga tego zadanie. Nie aktywuj wszystkich Skills przy każdej zmianie.

## Dokumentacja do sprawdzenia

- [CAP Java](https://cap.cloud.sap/docs/java/) i [Event Handlers](https://cap.cloud.sap/docs/java/event-handlers/).
- [Persistence Services](https://cap.cloud.sap/docs/java/cqn-services/persistence-services) i [Remote Services](https://cap.cloud.sap/docs/java/cqn-services/remote-services).
- [Event Queues](https://cap.cloud.sap/docs/java/event-queues) — dopasuj trwałość i retry do KSeF.
