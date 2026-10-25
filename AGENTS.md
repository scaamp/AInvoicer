# AGENTS.md — AInvoicer

> Instrukcje projektowe dla Codex, Claude Code i innych agentów kodujących.
> Dotyczy całego repozytorium. W razie konfliktu wymagania użytkownika w bieżącym zadaniu mają pierwszeństwo; dokumentuj świadome odstępstwa od ustalonej architektury.

## 1. Cel projektu

AInvoicer to rozwijana aplikacja SAP Fiori do obsługi faktur zakupowych i sprzedażowych, docelowo z integracją KSeF 2.0. To ma być utrzymywalny produkt integracyjny, a nie jednorazowy PoC. Jednocześnie projekt służy praktycznej nauce SAP CAP Java, Spring Boot, SAP BTP i integracji z SAP ABAP Cloud.

Repozytorium: https://github.com/scaamp/AInvoicer
Branch roboczy dla nowej architektury: `java-cap-ksef`.

Główne cele:
1. Zachować istniejącą aplikację SAPUI5 oraz dotychczasowe moduły ABAP, Node.js i Python.
2. Utworzyć nowy backend `CAP Java + Spring Boot` w jednym procesie/JVM, hostowany docelowo na SAP BTP Cloud Foundry.
3. Wykorzystywać oficjalny `CIRFMF/ksef-client-java` jako bibliotekę do protokołu KSeF 2.0, nie uruchamiać osobnego Java Gateway bez uzasadnienia.
4. Pobierać dane faktur SAP z ABAP Environment przez wystawione, autoryzowane API (RAP/OData V4 albo właściwe released APIs).
5. W CAP utrzymywać własne dane procesu KSeF (operacje, statusy, referencje, audyt), nie replikować bez potrzeby całej tabeli finansowej SAP.
6. Wdrożyć bezpieczny przepływ end-to-end: źródło SAP → CAP → SDK KSeF → KSeF TEST → status/numer KSeF/UPO → CAP/SAPUI5.

## 2. Zweryfikowany stan repozytorium a stan docelowy

Istniejące katalogi: `abap/`, `sapui5/ainvoicer/`, `node/`, `python/` i `cap-java/`. W `abap/src/` znajdują się obiekty abapGit dla symulacyjnego modelu faktur `ZAINV_INV_H`, `ZAINV_INV_I`, `ZAINV_INV_REF` oraz RAP nagłówka `ZAPI_AINV_INV_H_O4`. Nie zakładaj dostępności standardowych tabel S/4HANA (np. ACDOCA, BKPF, BSEG, RBKP, VBRK) ani rzeczywistych dokumentów FI/MM/SD w używanym ABAP Environment. Poprzedni model `ZFI_ACDOCA` nie jest docelowym źródłem danych CAP.

Pliki pod `abap/src/` są eksportem repozytorium, a nie dowodem aktualnego stanu aktywacji/publikacji w SAP. Przed integracją sprawdzaj w docelowym systemie definicję i binding, ich aktywny stan, adres, `$metadata`, encje, autoryzację i dostęp do danych. Nazwa `ZAPI_AINV_INV_H_O4` pochodzi z eksportu; nie wymyślaj URL-i ani kontraktu runtime. Aktualny zakres eksportu i luki opisuje [przewodnik modelu RAP](docs/guides/ABAP_RAP_INVOICE_MODEL.md).

Konfigurację lokalnego repo abapGit opisuje [workflow abapGit](docs/guides/ABAPGIT_WORKFLOW.md). Repozytorium Git, projekt Eclipse `abap` i projekt ABAP ADT `H01_EN` to różne elementy; synchronizacja Git nie aktywuje ani nie wdraża obiektów w SAP.

Na osobnej gałęzi `feature/ksef-java-e2e-runner` istnieje sprawdzony eksperyment Node.js → Java SDK → KSeF TEST, z prawdziwym numerem KSeF i UPO. Traktuj go jako referencję/test dowodowy, a NIE jako kod produkcyjny do bezpośredniego skopiowania. Nie zakładaj, że pliki tej gałęzi już znajdują się na bieżącej gałęzi.

Nowy moduł `cap-java/` jest docelową lokalizacją backendu; zanim utworzysz projekt, sprawdź aktualny stan katalogów i konwencje narzędzi. Nie deklaruj istniejących klas/serwisów jako gotowych, dopóki ich nie zweryfikujesz.

## 3. Architektura i granice odpowiedzialności

```text
SAPUI5 / Fiori
      │ OData V4
      ▼
CAP Java + Spring Boot (SAP BTP Cloud Foundry)
      ├── CDS / CAP services, handlers, business workflow
      ├── Persistence: H2/SQLite tylko lokalnie; SAP HANA Cloud docelowo
      ├── SAP Remote Service (Destination / OAuth2)
      │          ▼
      │     ABAP Environment: ZAINV_INV_H/I/REF → CDS/RAP → OData V4
      └── KSeF integration service → oficjalny Java SDK MF → KSeF API 2.0

node/ i python/ = istniejące, niezależne funkcje AI; nie są właścicielami KSeF auth.
```

- **ABAP/RAP**: pozostaje systemowym adapterem do danych SAP, wystawia OData/released APIs, ewentualnie operacje SAP wymagające ABAP. Nie rozwijaj nowej kryptografii ani pełnego workflow KSeF w ABAP.
- **CAP Java**: główny backend biznesowy AInvoicer; model CDS, OData V4, reguły i walidacje, zlecenia wysyłki, statusy, import faktur, audyt, trwałość i odczyt z SAP Remote Services.
- **KSeF SDK w CAP Java**: obsługuje uwierzytelnienie, certyfikaty/token KSeF, JWT, RSA-OAEP SHA-256, AES-256-CBC, sesje, faktury, statusy, UPO. Ukryj szczegóły SDK za interfejsem aplikacji; nie dodawaj warstwy REST pomiędzy CAP Java a SDK w tej samej JVM.
- **SAPUI5**: frontend OData V4; stopniowo przepinany na CAP bez naruszania istniejących ekranów, kiedy odpowiednie API CAP będzie gotowe.
- **Node.js / Python**: zachowaj istniejące OCR/AI/NLP/TTS i inne funkcje. Nie przenoś ich do CAP bez osobnego zadania.

Nie próbuj otwierać JDBC/SQL bezpośrednio do wewnętrznych tabel bazy ABAP Environment. CAP CDS nie daje automatycznie dostępu do tabel ABAP. Importuj zweryfikowany kontrakt z dostępnego API OData i użyj CAP Java Remote Services / SAP Cloud SDK Destinations. Nie wykonuj fikcyjnych JOIN-ów SQL między zdalnym OData a lokalną bazą CAP. Obecny eksport RAP obejmuje tylko nagłówek; pozycje i referencje nie są jeszcze wystawione jako dzieci/nawigacje OData.

## 4. Dane i model domenowy

- ABAP Environment utrzymuje własne dane symulacyjne faktur w `ZAINV_INV_H` (nagłówki), `ZAINV_INV_I` (pozycje) i `ZAINV_INV_REF` (powiązania do symulowanych źródeł). Nie są to standardowe tabele księgowe S/4HANA.
- CAP jest źródłem prawdy dla procesu integracji KSeF: `KsefOperations`, `KsefInvoices`, referencje sesji/faktury, numer KSeF, status, audit trail, idempotency key, korelacja do dokumentu SAP.
- Rozdziel dane faktury i stan operacji, wysyłkę i odbiór, stan techniczny i biznesowy.
- Duże XML FA(3)/UPO przechowuj wyłącznie w świadomie wybranym trwałym magazynie (np. repozytorium dokumentów); w DB trzymaj odpowiednie metadane i referencje. Nie używaj lokalnego dysku Cloud Foundry jako trwałego storage.
- Lokalnie dopuszczalna lekka baza testowa (preferuj H2 dla CAP Java); produkcyjna persystencja wymaga odpowiedniej usługi i sprawdzonych entitlementów, np. SAP HANA Cloud. Nie traktuj BTP Trial jako środowiska produkcyjnego.

## 5. Przepływ i niezawodność KSeF

Dla wysyłki online:
1. Pobierz/zwaliduj dane i przygotuj poprawny XML FA(3), zgodny z aktualnym oficjalnym XSD.
2. Uzyskaj autoryzację właściwego kontekstu podatnika: challenge → XAdES lub token KSeF → polling biznesowego statusu → redeem → JWT/refresh (jeśli potrzebne).
3. Przygotuj materiały kryptograficzne i otwórz sesję; RSA-OAEP SHA-256 do ochrony klucza AES, AES-256-CBC/PKCS#7 do XML, SHA-256 do skrótów.
4. Wyślij zaszyfrowaną fakturę i utrwal jej numer referencyjny.
5. Monitoruj końcowy status biznesowy; HTTP 202 != przyjęcie faktury; dopiero wynik końcowy i numer KSeF potwierdzają przyjęcie.
6. Zamknij sesję i pobierz UPO zgodnie z rzeczywistym statusem/dostępnością.
7. Zapisz wynik i pozwól bezpiecznie wznowić kontrolę statusu po restarcie.

Projektuj asynchronicznie: akcja CAP/OData zapisuje operację do bazy, a przetwarzanie KSeF wykonuje trwały worker/outbox po commit. Nie realizuj długiego pollingu i zewnętrznych efektów ubocznych wewnątrz transakcji zapisu biznesowego.

Zapewnij:
- idempotency key + trwałe, jednoznaczne ograniczenia unikalności + kontrolę konkurencji;
- trwałość statusów i korelację dokument SAP ↔ operacja ↔ sesja ↔ faktura ↔ numer KSeF;
- wyraźny stan `UNKNOWN/RECONCILIATION_REQUIRED` po utracie odpowiedzi na send/redeem/close;
- brak ślepego ponawiania niejednoznacznej operacji; retry wyłącznie bezpiecznych odczytów i świadomie sklasyfikowanych błędów;
- backoff, jitter i obsługę 429/Retry-After, bez agresywnego pollingu;
- brak wspólnych mutowalnych pól kontrolera dla AES/IV, sesji czy JWT; poprawność dla wielu równoległych operacji oraz wielu instancji CF;
- rozdział środowisk TEST/DEMO/PROD, kontekstów NIP i uprawnień.
- Nie utożsamiaj `status.code` KSeF z HTTP status i nie deklaruj sukcesu bez rzeczywistego wyniku.

Pierwszy vertical slice: **jedna syntetyczna faktura w KSeF TEST, od SAP Remote OData przez CAP Java po numer KSeF i UPO**. Batch, zakupy, korekty, offline, multi-NIP i zaawansowane skalowanie rozwijaj etapami.

## 6. Bezpieczeństwo — reguły bez wyjątków

- Nigdy nie dodawaj do Git ani logów: `.env`, kluczy API, KSeF tokenów, JWT, refreshToken, certyfikatów z prywatnym kluczem, PEM/P12/PFX/JKS, zaszyfrowanych sekretów możliwych do odszyfrowania lub realnych danych wrażliwych.
- Używaj zarządzanych credentiali/sekretów BTP (po potwierdzeniu dostępności), service bindings, Destination, OAuth2/XSUAA/IAS według faktycznej konfiguracji.
- JWT KSeF i materiał kryptograficzny pozostają po stronie backendu Java; nie zwracaj ich do SAPUI5 ani nie wystawiaj jako publicznego API.
- Uwierzytelniaj i autoryzuj interfejsy CAP; samo `contextId`/`NIP` w payload nie daje prawa działania w imieniu podatnika.
- Testowy samopodpisany XAdES można używać wyłącznie tam, gdzie pozwala na to oficjalne środowisko TEST; nie zakładaj, że jest poprawnym mechanizmem dla PROD.
- Nowy backend musi mieć restrykcyjny CORS, walidację wejścia, limity rozmiarów, bezpieczne błędy i brak stack trace/sekretów w odpowiedzi.
- Przy zastanych sekretach w historii repo zgłoś problem i zalecaj rotację; nie kopiuj ich do odpowiedzi ani nowych plików.

## 7. Standard implementacji

- Przed zmianą przeczytaj odpowiedni fragment repo, testy, kontrakty oraz aktualną dokumentację SAP CAP i MF. Nie wymyślaj metod API, klas ani adnotacji.
- Trzymaj architekturę modułowego monolitu: CDS/handlers → domain/application services → adapters (SAP Remote Service, KSeF SDK, storage); separuj odpowiedzialności i ułatwiaj unit testy.
- Preferuj released APIs, wzorce CAP Java i Spring Boot, dependency injection, konfigurację środowiskową, wersjonowane zależności SDK i deterministyczny build.
- Nie kopiuj całej aplikacji `demo-web-app` ani nie wystawiaj jej testowych kontrolerów na CF jako produkcyjnego API. Można adaptować wzorce/testy po audycie.
- Unikaj nadmiernej liczby klas, frameworków i pośrednich serwisów. CAP i SDK powinny działać w jednym procesie.
- Każda istotna funkcja/metoda integracyjna powinna mieć krótkie komentarze po polsku: cel, używane API, stany błędów i konsekwencje.
- Nie modyfikuj przypadkowo istniejących RAP/UI5/Node/Python. Zmieniaj tylko pliki potrzebne do zadania; nie commituj wygenerowanych artefaktów ani zależności.
- Zmiany w architekturze lub właścicielu danych najpierw opisz jako decyzję z kompromisami; nie wykonuj cichej migracji.

### Kanoniczny workflow rozwoju ABAP Cloud: MCP-first, abapGit po weryfikacji

**Domyślny schemat:** `Codex + VS Code (analiza) → ABAP FS/MCP (edycja rzeczywistego obiektu H01) → SAP H01 (diagnostyka, aktywacja, testy) → Eclipse ADT + abapGit (Stage and Push) → GitHub java-cap-ksef → lokalny checkout`.

- **Domyślne środowisko SAP:** system **H01** to ABAP Environment na **SAP BTP Trial** (nie PROD; ograniczenia Trial uwzględniaj przy planowaniu), a domyślny pakiet to **`ZAINVOICER`**, o ile zadanie nie wskazuje inaczej.
- **SAP H01 / pakiet `ZAINVOICER`** to źródło prawdy o stanie zapisanym, aktywnym i działającym w systemie. Sprawdzaj active/inactive, blokady i status OData. **GitHub** to zatwierdzony snapshot źródeł po eksporcie. **`D:\AInvoicer\abap\src\`** to lokalna kopia repo — nie live SAP. Te trzy stany nie synchronizują się automatycznie.
- **Preferuj bezpośrednią zmianę w H01 przez MCP**, także tworzenie nowych obiektów, gdy dana operacja jest dostępna i mieści się w zleceniu. Zweryfikowany punkt odniesienia to ABAP FS **2.12.4 / ADT creation fix 5** z repo `scaamp/abap-fs-creation-fix`. Przez `abapfs_get_creation_diagnostics`, jeśli dostępne, sprawdź wersję procesu wystawiającego MCP, połączenie, pakiet i typy tworzenia; instalacja VSIX w innym oknie/profilu VS Code nie potwierdza wersji tego procesu. Lista typów/discovery nie dowodzi uprawnień ani sukcesu w każdym systemie, w tym ECC. Zakres testów i procedura: [workflow abapGit](docs/guides/ABAPGIT_WORKFLOW.md#abap-fs-v5--tworzenie-i-diagnostyka). Przy brakującej możliwości użyj Eclipse ADT w zleconym zakresie.
- **Pakiet i transport:** zawsze podawaj docelowy `packageName`; nie zastępuj pakietu Cloud przez domyślne `$TMP`. Odczyty H01 z 2026-10-24/25 potwierdziły `pak:recordChanges="false"` dla `ZAINVOICER` (`ZLOCAL`, pusta warstwa transportowa), a `true` dla `ZAINVOICER_DEV`. Przy `false` pomijaj `additionalOptions.transportRequest`; v5 pomija wybór/tworzenie requestu. Brak wartości (`null`) nie oznacza `false`. Przy wymaganym transporcie przekaż aktualny, modyfikowalny request dopuszczony w bieżącym zadaniu; jeśli go brak, ustal wybór, bez samodzielnego tworzenia lub zwalniania zlecenia. Nie utrwalaj numeru requestu jako domyślnego w instrukcjach. Po utworzeniu odczytaj przypisanie w SAP: „Transport requested” opisuje parametr, nie potwierdzony wynik. Nazwa pakietu, brak requestów i `not-locked` nie zastępują jego konfiguracji; sprawdzaj ją ponownie po zmianie kontekstu.
- **Tworzenie i błędy:** sprawdź aktualny schemat `abapfs_create_object`; używaj dokładnego typu tworzenia, np. `CLAS/OC`, `DDLS/DF`, `DCLS/DL`, a nie typu wyszukiwania `DDLS`. Rozróżniaj błędny media type (415), deserializację XML (`CLASS_TRANSFORMATION`, `SDDIC_ST_ADT_DDLS`) oraz odmowę 403; przy 403 sprawdź również pakiet i wersję języka, zanim uznasz brak ogólnego uprawnienia. Nie przenoś kontraktu XML klasy na inne typy. Po sukcesie lub niejednoznacznym błędzie potwierdź stan odczytem metadanych/wersji inactive i transportu; samo wyszukiwanie może nie wykazać świeżego obiektu. Nie ponawiaj tworzenia w ciemno ani nie zmieniaj root CDS jako obejścia. Zbieraj oczyszczone szczegóły HTTP w zakresie diagnostyki i przywróć poprzedni stan logowania. Historyczne błędy starszych wersji nie są dowodem awarii v5.
- **Implementacja:** najpierw odczytaj aktualny obiekt live, porównaj z eksportem Git, uzgodnij zakres i zależności oraz transport tylko wtedy, gdy wymaga go pakiet/SAP; potem wykonaj minimalną zmianę w SAP. Nie nadpisuj wersji użytkownika ani cudzych blokad. Zapis przez MCP nie jest aktywacją. Syntax Check, aktywacja, ABAP Unit/ATC i test OData/`$metadata` są odrębnymi rezultatami, które należy potwierdzić; aktywuj tylko w autoryzowanym zakresie i środowisku nieprodukcyjnym.
- **Eksport po SAP:** dopiero po aktywacji i adekwatnych testach sprawdź różnice w `H01_EN → abapGit Repositories → ZAINVOICER → Stage and Push`. Przejrzyj add/modify/delete, branch `java-cap-ksef` i `STARTING_FOLDER=/abap/src/`, `FOLDER_LOGIC=FULL`. Nie wykonuj push/commita bez zlecenia użytkownika. Po eksporcie sprawdź lokalne `git status`; tylko przy czystym drzewie zaktualizuj checkout przez `git pull --ff-only origin java-cap-ksef` lub EGit. Przy rozbieżnej historii/pracy lokalnej wstrzymaj się i porównaj zmiany.
- **Fallback Git → SAP:** jeśli MCP nie obsługuje zapisu/aktywacji, można przygotować recenzowany lokalny patch `*.clas.abap`, `*.ddls.asddls`, `*.bdef.asbdef`, `*.dcls.asdcls`. Taki patch jest **niezweryfikowany w SAP**; nie publikuj go jako gotowego rozwiązania. Po uzgodnieniu kierunku wykonaj commit/push → kontrolowany abapGit Pull/import → aktywacja i testy w H01. Nie importuj automatycznie ani nie akceptuj usunięć bez kontroli. Struktury tabel i metadane `*.tabl.xml`, `*.srvb.xml` edytuj w ADT zamiast ręcznie.
- **Zasada jednego edytora:** nie rozwijaj tego samego obiektu równolegle przez MCP/ADT i lokalne pliki Git. Git push nie jest aktywacją, transportem ani eksportem danych tabel. `abap/.project` to konfiguracja lokalnego projektu Eclipse, nie abapGit; tokeny PAT i credentiale nigdy nie trafiają do repo/logów.

Instrukcje szczegółowe: [Skill ABAP/RAP](.agents/skills/ainvoicer-abap-rap/SKILL.md) oraz [przewodnik abapGit](docs/guides/ABAPGIT_WORKFLOW.md).

## 8. Sposób pracy agenta

1. Sprawdź bieżący branch, `git status`, istniejące pliki i zależności. Zachowuj pracę użytkownika; nie używaj destrukcyjnego resetu, clean ani force-push.
2. Przed większą zmianą wypisz krótki plan, granice zadania i kryteria akceptacji.
3. Pracuj inkrementalnie: mały zakres → implementacja → build → test → obserwacja → korekta.
4. Uruchom adekwatne testy; dla nowego CAP: kompilacja, testy handlers/adapters, testy modelu CDS, test zdalnego OData (jeśli dostępny), test KSeF TEST tylko z jawną zgodą na wysyłkę.
5. Nie deklaruj sukcesu E2E, jeżeli nie masz realnej odpowiedzi API; wyraźnie rozróżniaj mock, lokalny test i integrację live.
6. Na końcu podaj zmienione pliki, uruchomione polecenia, wyniki testów, blokery i kolejny najmniejszy krok.
7. Nie wykonuj deployu na PROD, wysyłki realnej faktury, commita/push ani zmian BTP o możliwych kosztach bez wyraźnej prośby użytkownika.

Komunikuj się po polsku, technicznie i konkretnie. Kod, nazwy klas i publiczne kontrakty w spójnym języku angielskim. Preferuj działające, małe kroki nad dużą niezweryfikowaną generację.

## 9. Dokumentacja odniesienia

- SAP CAP Java: https://cap.cloud.sap/docs/java/
- CAP Java Remote Services: https://cap.cloud.sap/docs/java/cqn-services/remote-services
- CAP Java Event Queues/Outbox: https://cap.cloud.sap/docs/java/event-queues
- Oficjalny SDK MF: https://github.com/CIRFMF/ksef-client-java
- Oficjalny przewodnik KSeF API 2.0: https://github.com/CIRFMF/ksef-api
- KSeF TEST Swagger: https://api-test.ksef.mf.gov.pl/docs/v2/index.html

Sprawdzaj wersję dokumentacji/SDK przed implementacją. Obowiązujący kontrakt to faktyczna wersja wdrożonego API, nie pamięć agenta.

## 10. Project Knowledge Base & Documentation Workflow

Wspólna baza wiedzy projektu znajduje się w [docs/README.md](docs/README.md). Codex, Claude Code, VS Code, Obsidian i GitHub korzystają z tych samych plików Markdown; nie twórz równoległych kopii dokumentacji dla poszczególnych narzędzi.

### Na początku każdej nowej sesji

1. Przeczytaj `AGENTS.md` jako nadrzędne źródło reguł.
2. Przeczytaj [docs/STATUS.md](docs/STATUS.md).
3. Przeczytaj [docs/ROADMAP.md](docs/ROADMAP.md).
4. Sprawdź najnowszy wpis w `docs/journal/` (według daty; przy kilku wpisach z tego samego dnia sprawdź właściwe tematy). Indeks jest w `docs/README.md`.
5. Przeczytaj odpowiednie ADR w `docs/decisions/`, jeżeli zadanie dotyczy architektury.
6. Zweryfikuj branch, `git status` i rzeczywisty kod przed rozpoczęciem pracy.

Nie zakładaj, że `STATUS.md` jest bardziej aktualny od repozytorium. Przy rozbieżnościach sprawdź fakty i skoryguj dokumentację; nie opieraj implementacji na niezweryfikowanej notatce.

### Po zakończeniu zadania implementacyjnego

1. Zaktualizuj `docs/STATUS.md`, w tym datę, blokery i najbliższe kroki.
2. Dopisz dziennik sesji albo zaktualizuj dzisiejszy wpis dotyczący tego samego zadania, korzystając z [JOURNAL_TEMPLATE.md](docs/templates/JOURNAL_TEMPLATE.md). Unikaj wielu wpisów dla drobnych operacji w tej samej sesji.
3. Zaktualizuj `docs/ROADMAP.md`, jeśli zmienił się stan zadania lub plan; roadmapa nie zastępuje historii zmian.
4. Dodaj ADR tylko dla istotnej decyzji architektonicznej. Nowe dzienniki i ADR podlinkuj w `docs/README.md`.
5. Wyraźnie oddziel ukończone zadania od niezweryfikowanych oraz relację użytkownika od własnych wyników. Rozróżniaj inspekcję, mock, test lokalny i integrację live.
6. Podsumuj rzeczywiście wykonane polecenia, wersje narzędzi, wyniki buildów/testów i ograniczenia. Brak testów nie oznacza PASS.
7. Wskaż następny konkretny krok umożliwiający kontynuację przez człowieka lub innego agenta.

Dokumentację aktualizuj razem z implementacją. Pisz po polsku, stosuj standardowe relatywne linki Markdown, YAML frontmatter i tagi tematyczne. Nie umieszczaj sekretów ani poufnych odpowiedzi API. `docs/` ma działać jako Obsidian Vault bez community plugins; lokalnego `docs/.obsidian/` nie dodawaj do Git.

## 11. Project Skills

Kanoniczne instrukcje są w `.agents/skills/`; `.claude/skills/` zawiera wyłącznie krótkie odwołania z frontmatter. Po wybraniu Skill przeczytaj jego pełny kanoniczny `SKILL.md`. Dobieraj Skills do zakresu zadania, nie ładuj wszystkich naraz. Skills nie rozszerzają uprawnień do SAP, KSeF, deploymentu ani Git.

| Skill | Kanoniczny plik (Codex) | Odwołanie Claude Code | Zastosowanie |
| --- | --- | --- | --- |
| `ainvoicer-abap-rap` | [SKILL.md](.agents/skills/ainvoicer-abap-rap/SKILL.md) | [SKILL.md](.claude/skills/ainvoicer-abap-rap/SKILL.md) | ABAP Cloud, CDS/RAP i rzeczywisty kontrakt SAP OData V4 przez dostępne MCP ABAP FS |
| `ainvoicer-cap-java` | [SKILL.md](.agents/skills/ainvoicer-cap-java/SKILL.md) | [SKILL.md](.claude/skills/ainvoicer-cap-java/SKILL.md) | Modele/usługi CDS CAP, handlers, CQN, persystencja i Remote Services w `cap-java/` |
| `ainvoicer-ksef` | [SKILL.md](.agents/skills/ainvoicer-ksef/SKILL.md) | [SKILL.md](.claude/skills/ainvoicer-ksef/SKILL.md) | Oficjalny SDK MF, auth, FA(3), sesje, statusy/UPO i rekoncyliacja |

Codex CLI/IDE: `$ainvoicer-cap-java Utwórz encję CAP i handler operacji faktury`; lista przez `/skills`. Claude Code: `/ainvoicer-cap-java Utwórz encję CAP i handler operacji faktury`. Analogicznie wywołuj pozostałe nazwy. Dla protokołu KSeF z implementacją modeli/handlerów użyj KSeF + CAP; dla konsumpcji istniejącego RAP użyj CAP i opcjonalnie ABAP do analizy SAP. Wyniki walidacji i cztery przykłady doboru: [dziennik Skills](docs/journal/2026-10-23-project-skills.md).
