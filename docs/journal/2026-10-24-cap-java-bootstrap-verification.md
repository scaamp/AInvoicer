---
title: "CAP Java Bootstrap — weryfikacja Phase 1"
date: 2026-10-24
type: journal
status: zakonczony
tags: [ainvoicer, journal, cap-java, bootstrap, h2]
---

# CAP Java Bootstrap — weryfikacja Phase 1

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [ADR-0001](../decisions/ADR-0001-cap-java-ksef-architecture.md)

## Date

2026-10-24 (Europe/Warsaw). Status dotyczy weryfikacji Phase 1; własny model CDS/OData pozostaje Phase 2.

## Branch

`java-cap-ksef` — potwierdzony przez `git branch --show-current`.

## Goal

Zweryfikować CAP Java + Spring Boot + H2 bez tworzenia encji KSeF, integracji Remote OData ani SDK MF. Kryteria: odtwarzalny `mvn clean verify`, uruchomiony backend i HTTP, działający H2 DataSource potwierdzony zapytaniem SQL oraz aktualna dokumentacja.

## Initial State

- Repozytorium było czyste na `java-cap-ksef`.
- Poprzedni dziennik zgłaszał `NoSuchFileException: srv/target/classes/application.yaml` podczas pakowania JAR; przyczyna była hipotezą, nie diagnozą.
- `cap-java/` zawiera parent POM i moduł `srv`; POM deklaruje JDK 25, CAP Java 5.2.0, Spring Boot 4.1.1, Maven minimum 3.9.14, OData V4 i runtime H2.
- `srv/src/main/resources/application.yaml` zawiera `spring.sql.init.platform: h2` i `cds.data-source.auto-config.enabled: false`. Brak własnych plików CDS/usług przed testem.
- W środowisku procesu nie było `java`/`mvn` w PATH. Dostępne lokalne instalacje: Microsoft JDK 25.0.4.101 i Maven 3.9.16. Node/NPM w PATH: 24.13.0 / 11.6.2; Maven plugin korzystał ze swojego Node 24.22.0.

## Implementation

1. Ustawiono `JAVA_HOME` i PATH wyłącznie dla procesów Maven/Java oraz usunięto `DEBUG` z procesu. Nie zmieniano ustawień systemowych, POM ani konfiguracji aplikacji.
2. Pierwszy sandboxowy `mvn clean verify` zatrzymał się w fazie `cds` na `spawn EPERM`, kiedy `cds version` próbował uruchomić proces potomny. Po ponowieniu poza sandboxem pełny build przeszedł.
3. Pierwotny `NoSuchFileException` nie został odtworzony. W obu udanych buildach goal `resources:resources` skopiował trzy pliki do `target/classes` przed goalem `jar:jar`; następnie Spring Boot `repackage` utworzył executable JAR. Brak wcześniejszego pełnego logu błędu uniemożliwia przypisanie jego przyczyny do procesu równoległego lub konkretnego pluginu.
4. Dodano test integracyjny `H2DataSourceIntegrationTest`, który uruchamia `@SpringBootTest`, wstrzykuje `JdbcTemplate` i sprawdza `SELECT 1`.
5. Uruchomiono spakowaną aplikację `java -jar srv/target/cap-java-exec.jar`, potwierdzono Spring ApplicationContext, CAP Runtime, Tomcat i HTTP. Test `/` dał HTTP 200. Adapter OData jest mapowany na `/odata/v4`, ale nie ma modelu usługi ani `$metadata` — to zakres Phase 2.
6. SAP dokumentuje H2 jako wspieraną dla CAP Java bazę in-memory do lokalnego developmentu i testów, nieprodukcyjną. Rzeczywisty start utworzył połączenie `jdbc:h2:mem:...`, zarejestrował `PersistenceService`, a test SQL przeszedł. Zachowano `cds.data-source.auto-config.enabled: false`; log CAP potwierdził wyłączenie jego automatycznej konfiguracji DataSource przy jednoczesnym utworzeniu H2 przez Spring/Hikari.

## Changed Files

- `cap-java/srv/src/test/java/customer/cap_java/H2DataSourceIntegrationTest.java` — integracyjny test dostępności H2 i prostego SQL.
- `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/README.md` — wyniki i stan Phase 1.
- Ten wpis — polecenia, dowody i ograniczenia.

Wygenerowane lokalnie pliki `target/`, CDS i JAR-y pozostają ignorowanymi artefaktami builda; nie dodano ich do Git. POM, `application.yaml` i zależności projektu nie zmieniono.

## Commands Executed

Wszystkie polecenia repozytorium wykonano na Windows/PowerShell.

- Inspekcja: `git status --short --branch`, `git branch --show-current`, odczyt POM, konfiguracji H2 i plików `srv/`.
- Wersje: JDK `25.0.4.1`, Maven `3.9.16`, Node z PATH `24.13.0`, npm `11.6.2`; CDS Maven plugin: `@sap/cds-dk 10.2.0`, `@sap/cds 10.2.1`, compiler `7.2.1`, bundled Node `24.22.0`; Spring Boot `4.1.1` (POM i runtime banner).
- Z `D:\AInvoicer\cap-java`: dwa pełne `mvn clean verify`, z JDK/Maven w środowisku procesu i bez `DEBUG`. Pierwsza próba sandboxowa zakończyła się wcześniej na `spawn EPERM`; po uzyskaniu wymaganych uprawnień oba pełne przebiegi zakończyły się `BUILD SUCCESS`. Drugi przebieg uwzględniał test H2.
- Próby `mvn -pl srv spring-boot:run` i `mvn -pl srv -am spring-boot:run`: pierwsza nie przeszła reactor convergence; druga zatrzymała się na sandboxowym `spawn EPERM` w CDS. Do kontroli runtime użyto gotowego executable JAR, bez zmian konfiguracji.
- `java -jar srv/target/cap-java-exec.jar`; osobne HTTP GET `http://localhost:8080/` oraz kontrola `/odata/v4/`.
- SAP CAP Java H2 docs: [Using H2 for Development in CAP Java](https://cap.cloud.sap/docs/guides/databases/h2); dokumentacja Persistence Services opisuje H2 jako bazę do nieprodukcyjnego developmentu/testów.

## Tests & Results

| Sprawdzenie | Wynik |
| --- | --- |
| `mvn clean verify` przed testem H2 | PASS: pełny reactor, kompilacja CDS/Java, kopiowanie zasobów, `jar:jar` i Spring Boot `repackage` |
| `mvn clean verify` po dodaniu testu H2 | PASS: `BUILD SUCCESS`; `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`; oba JAR-y ponownie utworzone |
| `application.yaml` | PASS inspekcji artefaktu: znajduje się w `srv/target/classes/application.yaml`; log Maven wskazuje skopiowanie trzech resources przed pakowaniem |
| Spring Boot / CAP Runtime | PASS: logi pokazują CAP 5.2.0, Spring Boot 4.1.1, zakończony `Started Application`, CAP Index URL i serwer Tomcat na 8080 |
| HTTP | PASS: `/` zwrócił 200 i `text/html`; to strona indeksowa CAP, nie API domenowe |
| H2 DataSource / SQL | PASS: Hikari połączył `jdbc:h2:mem:...`; `H2DataSourceIntegrationTest` wykonał `SELECT 1` z wynikiem 1 |
| OData V4 / CDS CRUD | NIE WYKONANO: nie ma własnej usługi ani encji; HTTP `/odata/v4/` nie zwraca usługi domenowej |
| Historyczny `NoSuchFileException` | NIE ODTWORZONO. Udane czyste buildy przeszły bez ręcznych plików `target`; dokładna przyczyna pierwotnego incydentu pozostaje nieustalona. |
| SAP / KSeF live | NIE WYKONANO i nie należy do Phase 1 |

## Problems Encountered

- Sandbox zablokował proces potomny uruchamiany przez CDS (`spawn EPERM`). Zgodnie z wymaganym przebiegiem ponowiono Maven poza sandboxem; to umożliwiło standardowe `mvn clean verify`. Nie jest to przyczyna dawnego `NoSuchFileException`.
- `mvn spring-boot:run` bez modułu parent nie spełnił `reactorModuleConvergence`. Wariant `-pl srv -am` zatrzymał się w CDS na tym samym ograniczeniu sandboxa. Spakowany executable JAR wystartował poprawnie; runtime jest zweryfikowany.
- Wcześniejszy brak `application.yaml` nie powrócił. Bez surowego logu tej awarii nie przypisano jej do race condition ani nie wprowadzono poprawki bez przyczyny.
- `npm ci` w drugim buildzie zgłosił dwa podatności o wysokiej ważności w drzewie zależności; nie uruchamiano `npm audit fix` ani nie zmieniano zależności w ramach bootstrapu.

## Decisions Made

- Nie zmieniać POM ani `application.yaml`: build oraz rzeczywisty DataSource/SQL działają w zadeklarowanym stacku.
- Pozostawić `cds.data-source.auto-config.enabled: false`; CAP nadal zarejestrował `PersistenceService`, a Spring/Hikari skonfigurował H2 w tym projekcie.
- Uznawać test H2 wyłącznie za dowód połączenia i prostego SQL, nie za dowód schematu CDS ani CRUD.

## Current State

Phase 1 bootstrap jest zakończona: dwa czyste buildy, uruchomiony runtime, odpowiedź HTTP i test H2 SQL PASS. Nie stwierdzono regresji ani zmian zależności. Brak własnego `$metadata`, encji i CRUD jest świadomie pozostawiony Phase 2.

## Next Steps

1. Phase 2: dodać minimalne encje CDS `KsefOperations` / `KsefInvoices` i usługę OData V4.
2. Zweryfikować `$metadata`, CRUD i trwałość lokalnych encji H2 przed integracją SAP.
