---
title: "AInvoicer — strategia Infrastructure as Code i Database as Code"
date: 2026-11-11
type: architecture
status: strategia-przyjęta-wdrożenie-etapowe
tags: [ainvoicer, architecture, infrastructure-as-code, database-as-code, sap-btp, sap-cap, hana-cloud, hdi, terraform, disaster-recovery, devops]
---

# AInvoicer — Infrastructure as Code / Database as Code

> **Decyzja strategiczna (2026-11-11):** AInvoicer pozostaje obecnie projektem edukacyjnym i integracyjnym, ale jego architekturę oraz sposób wdrażania rozwijamy z myślą o przyszłym SaaS. Przyjmujemy kierunek **Git jako wersjonowane źródło definicji aplikacji, modelu bazy i docelowo infrastruktury**, z automatyczną walidacją, kontrolowanym deploymentem i regularnym testem odtwarzania. Nie ogłaszamy, że cały krajobraz BTP jest już zarządzany jako IaC.

**Powiązania:** [ADR-0009 — decyzja o strategii](../decisions/ADR-0009-iac-database-as-code-strategy.md) · [ADR-0007 — HANA/HDI i fail-closed](../decisions/ADR-0007-cap-persistence-hana-cloud.md) · [ADR-0008 — proponowany BLOB DEV/TEST](../decisions/ADR-0008-document-storage-dev-test-proposal.md) · [HANA as-built](../guides/HANA_CLOUD_FREE_TIER_SETUP.md) · [HANA deployment](../guides/HANA_CLOUD_CAP_DEPLOYMENT.md) · [STATUS](../STATUS.md).

## 1. Streszczenie dla decyzji

Gdy fizyczna instancja SAP HANA Cloud zniknie, powinniśmy móc **odtworzyć strukturę bazy** z wersjonowanych definicji CAP CDS oraz artefaktów HDI, zamiast ręcznie odtwarzać tabele i widoki. Gdy powstanie nowy subaccount lub space, powinniśmy móc odtworzyć **konfigurację usług i aplikacji** w powtarzalnym, kontrolowanym procesie. Na początku dopuszczamy kroki ręczne, ale dokumentujemy je i sukcesywnie zastępujemy deklaracjami zarządzanymi jako kod.

**Najważniejsze ograniczenie:** odtwarzalność struktury i konfiguracji to nie backup. Git, CDS i HDI **nie przywracają** rekordów operacji KSeF, checkpointów, outbox, XML, PDF, UPO, sekretów, przypisań użytkowników ani stanu zewnętrznych systemów. Utrata tych danych może uniemożliwić ustalenie, czy faktura została już wysłana. Dla produkcji potrzebna będzie oddzielna, przetestowana strategia ochrony danych i rekoncyliacji.

**Zakres przyjętej decyzji:** code-first, wersjonowany model CAP/HDI, powtarzalny build i deploy, kontrola kosztów i security, dokumentowanie infrastruktury. **Nie zatwierdzono automatycznego terraform apply, komercyjnego HANA, włączenia KSeF LIVE ani produkcyjnego storage.**

## 2. Słownik i różnice

| Pojęcie | Co oznacza w AInvoicer | Czego nie oznacza |
| --- | --- | --- |
| **Infrastructure as Code (IaC)** | Deklaratywne i wersjonowane definicje subaccount/space, entitlementów, instancji usług, bindingów, aplikacji, uprawnień i ich konfiguracji tam, gdzie obsługuje to API/provider | Automatycznego odzyskania danych ani uprawnienia do wykonywania zmian bez review |
| **Database as Code (DbAC)** | CAP CDS i artefakty HDI określające docelowy schemat: tabele, kolumny, widoki, indeksy, constraints, zależności | Przechowywania danych biznesowych w Git |
| **HDI** | HANA Deployment Infrastructure: wdraża artefakty design-time do runtime kontenera | Repozytorium wersji, system backupu, engine migracji gwarantujący brak utraty danych |
| **HDI container** | Izolowana przestrzeń schematu i uprawnień na istniejącej bazie HANA | Osobna instancja HANA Cloud; automatyczna izolacja tenantów SaaS |
| **HDI Deployer** | Narzędzie uruchamiane przy zatwierdzonym deploymencie, aktualizujące obiekty w HDI | Usługa do archiwizacji plików lub przywracania rekordów |
| **MTA / MTAR** | Opis modułów/bindingów i wynikowy, sprawdzony pakiet aplikacji | Pełna definicja global account, kosztów, tożsamości użytkowników i wszystkich zasobów |
| **GitOps** | Praktyka uzgadniania rzeczywistego środowiska ze stanem deklarowanym w Git | Na dziś brak ciągłego reconciler/controller i brak pełnego GitOps |
| **Backup / DR** | Oddzielny proces ochrony i przywracania danych oraz operacji zewnętrznych | Sam build i redeploy ze źródeł |

Architektura rozdziela **desired state**, **state/runtime** i **business data**. Pierwszy jest wersjonowany, drugi wymaga bezpiecznego provisioningu i walidacji, trzeci wymaga kopii danych, kontroli spójności i procedur recovery.

## 3. Dlaczego przyjmujemy ten model?

1. **Nauka poprzez realne praktyki SAP BTP:** użytkownik uczy się CAP Java, HANA Cloud, HDI, Cloud Foundry i narzędzi DevOps, ale unika jednorazowych eksperymentów bez ścieżki utrzymania.
2. **Odtwarzalność Free Tier:** HANA Free Tier zatrzymuje się nocą i może zostać usunięta po 30 dniach bez restartu; ma również ograniczenia backup/recovery. Dzięki kodowi można odtworzyć schemat po provisioningu nowej instancji.
3. **Powtarzalne środowiska:** DEV/TEST, a docelowo STAGE/PROD mają powstawać z tych samych sprawdzonych definicji z parametryzacją środowiskową.
4. **Mniejsze ryzyko driftu:** brak ręcznych, nieudokumentowanych zmian w tabelach, rolach, zmiennych środowiskowych czy usługach.
5. **Code review i audyt:** wiadomo, kto i dlaczego zmienił model lub infrastrukturę; na ile to możliwe, build daje dowód, co trafiło do wydania.
6. **Bezpieczne migracje:** zmiana schematu przechodzi analizę wpływu na istniejące rekordy, plan migracji i testy odtwarzania.
7. **Koszt i przenośność:** minimalna liczba płatnych zasobów; można w przyszłości wymienić storage bez przepisywania logiki KSeF.
8. **Przygotowanie SaaS:** standaryzacja provisioningu, bezpieczeństwa, observability i procesu odtwarzania, bez przedwczesnego narzucania multitenancy.

## 4. Architektura i granice systemów

```text
GitHub — wersjonowane źródła i decyzje
│
├── cap-java/                → CDS + CAP Java/Spring + testy
├── deploy/ i mta.yaml       → opis pakowania, release evidence, guardy
├── approuter/ i sapui5/     → aplikacja i routing
├── abap/src/                → eksport obiektów RAP/ADT, nie automatyczna aktywacja
└── docs/                    → ADR, runbooki, stany, dowody, recovery
       │
       ├── CI / offline build / security checks / review
       │       │
       │       ├── cds build --for hana --production
       │       │       └── artefakty HDI → HDI Deployer
       │       │                              └── istniejący HDI na SAP HANA Cloud
       │       └── zweryfikowany MTAR → zatwierdzony CF deploy
       │                                   ├── CAP Java → HANA przez binding
       │                                   ├── AppRouter → XSUAA/Destination
       │                                   └── SAP RAP / KSeF TEST: tylko po osobnych zgodach
       │
       └── docelowo: konfiguracja BTP as code
                 → provider Terraform / API / zatwierdzony pipeline
                 → entitlements, instancje usług, bindings, role i spaces
```

**Granice źródła prawdy:**
- **CAP/HANA:** wersjonowane modele CDS, źródła i konfiguracja HDI; fizyczna struktura powstaje w trakcie deploymentu. Nie tworzymy aplikacyjnych tabel ręcznie przez DBADMIN wewnątrz kontenera.
- **SAP ABAP Cloud:** eksport abapGit nie jest dowodem, że obiekt w H01 został aktywowany, opublikowany i autoryzowany. Na dziś pozostaje osobny workflow ADT / MCP / abapGit i testów LIVE.
- **BTP:** entitlementy, instancje i role są częściowo zarządzane ręcznie. Do czasu wdrożenia pełnego IaC obowiązują udokumentowane instrukcje i inventory read-only.
- **Sekrety:** nie są przechowywane w repo; bindingi, Destination i właściwe mechanizmy zarządzania sekretami pozostają odrębną warstwą.
- **Dane KSeF:** operacje, dokumenty, rezerwacje, audyt i outbox nie są rekonstruowane z samego kodu.

## 5. Stan faktyczny i stopień dojrzałości (11.11.2026)

Poniższa tabela rozdziela stan zweryfikowany w `main`, relację właściciela oraz otwarty branch. Sprawdzaj ponownie przed deploymentem — dane są migawką.

| Obszar | Stan / dowód | Dojrzałość |
| --- | --- | --- |
| CAP Java/CDS w Git | Istnieje kod i model; `main` zawiera INT-03 | **DbAC design-time: wdrożony** |
| HDI readiness | Generator, weryfikacja MTAR/evidence i offline testy na `main` | **Pipeline offline: wdrożony** |
| HANA Free Tier | `ainvoicer-hana-dev` utworzona; SQL potwierdzony wcześniej przez właściciela | **Baza istnieje, status bieżący do sprawdzenia** |
| HDI `ainvoicer-db` | Według potwierdzenia właściciela w BTP Cockpit: `Usable / Creation Succeeded`, `hdi-shared`, CF space `dev` | **Provisioning wykonany ręcznie; potwierdzenie owner-reported** |
| CAP → rzeczywista HANA | PR #9 `feature/cap-hana-01-first-persistence` nadal otwarty; pierwszy `cf deploy` został odrzucony na typie modułu MTA; poprawka została przygotowana i przetestowana offline | **LIVE NOT VERIFIED / deployment NO-GO bez ponownej zgody** |
| Bezpieczny model XSUAA/Destination | Istnieją bindingi i testy offline; LIVE macierz A/B, DCL i principal H01 wymagają pełnych dowodów | **Częściowe** |
| BTP entitlements i provisioning | Wykonane ręcznie przy tworzeniu HANA/HDI; brak kodu deklarującego cały krajobraz | **IaC: planowane** |
| XML/PDF/UPO | Adapter Azure Blob istnieje offline, provider OFF; HANA BLOB DEV/TEST jest propozycją ADR-0008 | **Brak produkcyjnego archiwum** |
| Disaster recovery / odtwarzanie pełne | Brak przetestowanego odtworzenia po skasowaniu HANA, brak backupu Free Tier | **Otwarte** |

**Ważne:** zielony CI i poprawny MTAR nie dowodzą jeszcze, że tabele powstały w HANA. Tak samo `Creation Succeeded` HDI nie oznacza, że CAP jest połączony. Liczby obiektów z aktualnego offline build (INT-03: 14 tabel, 6 indeksów, 4 widoki) są oczekiwaniem do weryfikacji po LIVE deploy, a nie stanem katalogu runtime.

Dalsze postępy i właściwe hashe należy sprawdzać w [PR #9](https://github.com/scaamp/AInvoicer/pull/9) i jego dzienniku. Nie kopiować historycznych SHA MTAR do nowych wdrożeń bez walidacji.

## 6. Zasady projektowe — Database as Code

### 6.1 Źródło definicji

1. Schemat CAP definiujemy w plikach `.cds` (i ręcznych, jawnie wersjonowanych artefaktach HDI tam, gdzie model CDS nie wystarcza).
2. `cds build --for hana --production` generuje artefakty deployera; wynik jest walidowany i pakowany do MTAR.
3. Wdrożenie do `ainvoicer-db` wykonuje HDI Deployer z technicznym bindingiem kontenera; CAP korzysta z bindingu runtime.
4. Nie przechowujemy ręcznie edytowanych tabel aplikacyjnych jako stanu prawdy. Eksperymentalny SQL nie może stawać się ukrytą zależnością deploymentu.
5. Nie włączamy `--auto-undeploy` ani destrukcyjnych operacji bez jawnego review; modyfikacja lub usunięcie artefaktu może mieć konsekwencje dla danych.
6. Typy, constraints, indeksy i polityki autoryzacyjne są testowane także na prawdziwej HANA, gdy środowisko jest przygotowane i właściciel zatwierdzi testy.

### 6.2 Zmiany schematu

**Protokół:**
- Nowe encje i kolumny: najpierw model CDS, test H2 oraz generacja HANA, analiza diffu obiektów.
- Zmiany wymagające migracji: scenariusz expand → migrate/backfill → contract; zachować możliwość działania starszej aplikacji podczas uzgodnionego okna, jeśli docelowe SLA tego wymaga.
- Zmiany destrukcyjne (DROP/rename/type narrowing/not-null na danych): osobny plan migracyjny, test z kopią danych i zgoda.
- Indeksy unikalności i blokady: testowanie rzeczywistego zachowania HANA (CAS, lease, transakcje, deadlock/timeout SQLState).
- Schemat jest versioned, ale operacja rollback aplikacji **nie cofa automatycznie schematu**; należy preferować zgodność wsteczną zmian modelu i plan roll-forward.
- Nie wpisywać credentiali do `.hdbtable`, `.mtaext` ani inicjalizatorów danych.

### 6.3 Zasada fail-closed

Cloud runtime nie może po cichu przełączać się z HANA na H2 w pamięci. W projekcie obowiązuje `CloudPersistenceGuard`; wyjątek identity-smoke jest jawny i nie pozwala uruchomić funkcji zapisu procesu KSeF. Brak/awaria HDI ma zatrzymać start lub chronioną operację, nie utworzyć zastępczej, niewidocznej bazy.

## 7. Zasady projektowe — Infrastructure as Code

### 7.1 Co będziemy deklarować

Docelowo, w granicach możliwości oficjalnych providerów i uprawnień:
- Subaccount, environment/space i niezbędne entitlementy (z jawnie wybranym free tier lub opłaconym planem).
- Instancje XSUAA, Destination, HANA/HDI, bindingi i wybrane ustawienia aplikacji.
- Istotne parametry CF (typ modułu, wersja/stack/buildpack, memory, instances, routes) oraz bezpieczne feature flags.
- Role/role collections i zaufanie IdP w kontrolowanym procesie, po niezależnym security review.
- Wskaźniki i monitoring kosztów oraz kontrole konfiguracji, jeśli API i plan pozwalają.

Wymagania i aktualny inventory muszą być najpierw opisane jako **as-built**. Nie wprowadzać automatyzacji, która zaskoczy właściciela kosztami, zamieni istniejące instancje lub przepnie bazę bez zgody.

### 7.2 Preferowany zestaw narzędzi i odpowiedzialności

| Warstwa | Preferencja | Uzasadnienie |
| --- | --- | --- |
| BTP account / entitlements | **Terraform Provider for SAP BTP** (do pilotażu po weryfikacji możliwości) | Deklaracje + plan/diff przed mutacją; provider nie jest jeszcze używany w projekcie |
| Service-specific API | Odpowiedni provider / BTP CLI / API tylko tam, gdzie właściwa funkcja jest potwierdzona | Nie każdą operację HANA/CF/SAP IAM obsłuży jeden provider |
| CAP/HANA schema | **CAP CDS + HDI** | Oficjalny cykl build/deploy artefaktów DB |
| Pakowanie/CF runtime | **MTA + MTAR**, walidacja SAP MBT, CF MultiApps | Zależności modułów, kolejność HDI → CAP → AppRouter i zarządzane bindingi |
| ABAP Environment | **ADT/MCP + abapGit + zweryfikowana aktywacja/testy** | Eksport Git nie aktywuje obiektów w H01 |
| Sekrety | Zarządzany secret store / service bindings / Destination | Brak sekretów w Git i jawnych outputach planu |
| Ochrona danych | Oddzielny backup/export/DR i rekoncyliacja | IaC nie chroni wartości rekordów |

Terraform jest **kierunkiem oceny, nie przyjętą implementacją gotową do wykonania**. Należy sprawdzić obsługiwane zasoby, sposób importu zastanych instancji, credentiale providera, zakres uprawnień oraz licencję/wsparcie. Brak mechanizmu bezpiecznego zarządzania stanem Terraform oznacza brak zgody na automatyczny `apply`.

### 7.3 Desired state, drift i bezpieczne zmiany

- Każdy PR pokazuje oczekiwaną zmianę konfiguracji oraz możliwe konsekwencje finansowe, bezpieczeństwa i downtime.
- Plan IaC jest weryfikowany przed apply. Plan nie jest dowodem, że operacja się udała.
- Drift: cykliczny odczyt rzeczywistego BTP/CF i porównanie do deklaracji, bez automatycznego usuwania nieznanych zasobów.
- Zastane zasoby należy bezpiecznie zinwentaryzować i ewentualnie **importować do IaC**, zamiast przypadkowo utworzyć duplikaty.
- Stan Terraform, jeżeli zostanie wdrożony: szyfrowane zdalne backend storage, kontrola dostępu, blokada konkurencji, backup wersji stanu; traktować state jako **wrażliwy**, ponieważ może zawierać dane konfiguracji/credentiali.
- Żaden bot/agent nie uzyskuje domyślnego prawa do tworzenia płatnej usługi, zmian IAM, service keys, zmian sieci, danych produkcyjnych ani restartów.
- Przed akceptacją każdej operacji znana jest konkretna nazwa środowiska, plan usługi, region, koszt/limit, blast radius, procedura odtworzenia i sposób anulowania.

## 8. Ścieżka CI/CD i zasady wydania

```text
feature branch → PR → review architektury/bezpieczeństwa
  → testy kodu (Maven / Java)
  → testy UI5 i integracyjne offline
  → generacja CDS/HDI i sprawdzenie artefaktów
  → MBT/MTAR smoke + HANA, validate release evidence/SHA
  → polityki kosztowe/secret scanning/diff
  → jawny approval dla środowiska
  → deployment do zatwierdzonego CF/HDI
  → rzeczywiste testy HANA/JDBC/HTTP/autoryzacji
  → aktualizacja STATUS, dziennika, evidence i runbooka
```

Dla AInvoicer istnieją już skrypty `deploy/`:
- `npm ci --prefix deploy`
- `npm test --prefix deploy`
- `npm run build:btp:smoke --prefix deploy`
- `npm run verify:release --prefix deploy`
- `npm run build:btp:hana --prefix deploy`
- `npm run verify:hana --prefix deploy`

Są to kroki build/walidacji, **nie zgoda na `cf deploy`**. Przy zmianie źródeł lub manifestów wykonywać nowy build oraz kontrolę SHA. Nie pomijać błędu z niezgodnością CRLF/LF, typami modułów MBT, bundlingiem czy brakującymi bindingami przez wyłączenie walidatorów.

Brak LIVE A/B/principal H01 nie powinien być maskowany oznaczeniem `CI PASS`. Każdy wynik należy oznaczać jako `VERIFIED OFFLINE`, `VERIFIED LIVE`, `OWNER-REPORTED` lub `PENDING`.

## 9. Odtworzenie struktury po utracie HANA — procedura koncepcyjna

**Zakładamy wyłącznie środowisko DEV/TEST i dane syntetyczne.** Rzeczywistą próbę odtworzenia przeprowadzamy po osobnym zatwierdzeniu, nie kosztem działającego `ainvoicer-hana-dev`.

1. **Zidentyfikuj awarię:** czy baza jest tylko zatrzymana, czy została skasowana. Nocny stop Free Tier nie wymaga ponownego tworzenia schematu; najpierw uruchom istniejącą instancję.
2. **Sprawdź koszty i uprawnienia:** czy `hana-free` oraz `hana/hdi-shared` są dostępne na właściwym global account/subaccount/space; odrzuć nieplanowaną płatną ofertę.
3. **Odtwórz HANA Cloud:** provisionuj nową bazę we właściwym środowisku i potwierdź Database ID, sieć, wersję oraz stan RUNNING.
4. **Odtwórz HDI:** utwórz dedykowany kontener `ainvoicer-db` z `database_id` nowej bazy; nie myl go z GUID usługi CF; potwierdź `Creation Succeeded`.
5. **Odtwórz powiązania usług:** zweryfikuj XSUAA, Destination, role, bindingi, kontekst org/space i flagi. Nie zakładaj, że IAM/secret material pochodzi z CDS.
6. **Z checkoutu zatwierdzonego SHA** przygotuj i zweryfikuj pakiet MTAR i wariant `existing-service`. Sprawdź jego SHA, rozszerzenie, plan stacków i feature flags.
7. **Za zgodą wykonaj HDI deploy → CAP → AppRouter.** Weryfikuj rzeczywiście utworzone tabele, indeksy, widoki, produkt JDBC HDB, brak H2 fallback i poprawne autoryzacje.
8. **Odrębnie przywróć dane**, jeżeli są dostępne legalne i przetestowane backupy/eksporty. Gdy nie ma kopii, zapisujemy utratę danych i nie wznawiamy automatycznie niejednoznacznych operacji KSeF.
9. **Wykonaj testy recovery:** syntetyczny INSERT/SELECT, unique/CAS/lease, restart procesu i odczyt tych samych rekordów, bez automatycznej wysyłki KSeF.
10. **Udokumentuj dowody:** pierwotną przyczynę, czas rzeczywistego odtworzenia, wynik, nieodtwarzalne elementy oraz koszty.

**Nie obiecujemy RTO ani RPO** przed pomiarem i wdrożeniem backupu. Odtworzenie struktury może być relatywnie szybkie, ale provisioning usług, bezpieczeństwo, sieć, deployment i testy pozostają osobnymi krokami. Odtworzone puste tabele nie oznaczają odtworzonego systemu KSeF.

### Macierz odzyskiwalności

| Artefakt / stan | Z samych plików Git + build | Co jeszcze potrzebne |
| --- | --- | --- |
| Schemat CAP: tabele, kolumny, widoki, indeksy zapisane w modelu | **Tak** | Działająca HANA + HDI + zgodny deploy |
| Konfiguracja modułów CF i feature flags w MTA | **Częściowo** | Zasoby, bindings, uprawnienia, zatwierdzony deploy |
| Subaccount/entitlements/role collections | **Jeszcze nie automatycznie** | As-built, administracja; docelowo IaC |
| ABAP RAP / IAM / DCL | **Eksport częściowo** | ADT/abapGit, aktywacja, publikacja, przypisania i test LIVE |
| XML/PDF/UPO zapisane w HANA BLOB | **Nie** | Oddzielny backup lub oryginalne pliki |
| Checkpointy, outbox, statusy KSeF, numery referencyjne | **Nie** | Backup i ostrożna rekoncyliacja z KSeF/SAP |
| Sekrety, certyfikaty, prywatne klucze, service bindings | **Nie** | Odtworzenie bezpieczną procedurą zarządzania sekretami |
| Rzeczywiste efekty po stronie KSeF i SAP | **Nie** | Idempotencja i API do odczytu/reconciliation |

## 10. XML/PDF: HANA BLOB teraz, object storage później

Dla edukacyjnego DEV/TEST wybieramy kierunkowo **relacyjny BLOB w istniejącej HANA Free Tier**, lecz implementację i aktywację prowadzi osobny, zatwierdzany ADR-0008 / sprint KSEF-STOR-02. Free Tier nie oferuje HANA Document Store — **relacyjny BLOB to inne rozwiązanie**.

- CAP CDS `LargeBinary` → HANA `BLOB`; Java `byte[]` (lub kontrolowany strumień dla mediów).
- XML i UPO mogą być kompresowane GZIP w aplikacji; PDF często nie daje istotnego zysku z ponownej kompresji.
- W bazie zapisujemy oryginalny SHA-256, rozmiar, format, kompresję, scope oraz zasady retencji. Po odczycie weryfikujemy **dokładnie oryginalne bajty**, nie samą poprawność XML.
- Nie przechowujemy BLOB jako tekstowego Base64, gdy dostępny jest typ binarny.
- Nie udostępniamy raw XML/UPO w publicznej encji bez autoryzacji NIP/tenant.
- Nie włączamy automatycznie storage, KSeF SEND/INBOUND, outbox ani SAP WRITE wskutek samego dodania schematu.
- Cloudflare R2 (S3-compatible, wariant EU) pozostaje **opcją przyszłą**, z osobnym ADR kosztowym, DPA/RODO i testem migracji; nie ma aktualnie zatwierdzonych poświadczeń/zasobów R2.

**Zastrzeżenie:** dokumenty BLOB znikną wraz z bazą bez kopii danych. Dla przyszłego SaaS potrzebne będą osobny magazyn i jego disaster recovery oraz backup metadanych HANA. Samo zewnętrzne R2 nie odtworzy automatycznie powiązań, grantów, statusów procesu i checkpointów.

## 11. Bezpieczeństwo, compliance i koszty

### Security by design
- Secrets/JWT KSeF/XSUAA/klucze prywatne/VCAP/Database passwords **nigdy** w Git, state jawnych logach, CI artefaktach ani screenshotach.
- Service binding do HDI zamiast używania `DBADMIN` jako konta aplikacji.
- CAP dedicated tenant JWT i role nie zastępują autoryzacji biznesowej NIP/CompanyCode, a HDI container nie jest mechanizmem autoryzacji per tenant.
- UI5/AppRouter/RAP mają oddzielne granice tożsamości; wymagane są negative LIVE tests.
- Każda propozycja automatyzacji IAM/XSUAA wymaga security review i weryfikacji wpływu na sesje biznesowe.
- Rejestrowanie decyzji o release i audyt zmian jest częścią strategii IaC.

### Cost by design
- Preferujemy `hana-free` z istniejącym `hdi-shared`; nie tworzymy automatycznie płatnej HANA ani BTP Object Store.
- PAYG/Commercial Type=Consumption opisuje umowę, nie stanowi samodzielnego potwierdzenia stawki konkretnej usługi. Sprawdzać obowiązujący kontrakt, katalog planów oraz Costs and Usage.
- Każda nowa deklaracja IaC powinna mieć kontrolę planu i kosztu oraz limit zasobów. Default dla nieznanych/cennikowo niepotwierdzonych usług: **NO-GO**.
- Cloud Foundry runtime, monitoring, storage i transfery mogą podlegać osobnym limitom lub opłatom; „bezpłatne HDI” nie oznacza „całe rozwiązanie kosztuje 0”.
- Plan Terraform z nieoczekiwanym `create`/`replace`/`destroy`, zwłaszcza dla HANA, IAM, DNS, storage, wymaga zatrzymania i jawnej zgody.

### Przyszły SaaS
- Oddzielne środowiska, polityka izolacji danych, provisioning klientów, zarządzanie cyklem życia tenantów, retencja i audyt, backup/restore, RPO/RTO, ochrona dokumentów podatkowych i wymagania RODO.
- Obecny dedicated XSUAA i pojedynczy HDI **nie oznaczają wdrożenia SaaS multitenancy**. Zmiana modelu tenancy (shared/dedicated HDI per tenant, SaaS provisioning, MTX) wymaga osobnego ADR.
- Dane rzeczywistych klientów nie powinny znaleźć się w HANA Free Tier.

## 12. Korzyści, koszty i ograniczenia decyzji

| Zalety | Wady / ryzyka i odpowiedź |
| --- | --- |
| Szybsze odtworzenie schematu po utracie HANA | Nie odtwarza danych; wymaga backupu i testu DR |
| Powtarzalność i mniej ręcznych błędów | Dodatkowa dyscyplina CI/CD, code review i utrzymanie pipeline |
| Wersjonowanie i ślad decyzji | Drift może nadal wystąpić przez ręczne zmiany; inventory/detection |
| Izolacja HDI i minimalne uprawnienia runtime | HDI samo nie izoluje NIP/tenant; osobne polityki CAP/IAM |
| Wczesne wykrywanie błędów na build/test | Offline pass ≠ LIVE pass; osobne testy na HANA/BTP |
| Otwarta droga do pełnego IaC/Terraform i SaaS | Provider może nie wspierać wszystkiego; nie obiecywać automatyzacji 100% |
| Niskokosztowy DEV/TEST na Free Tier | Nocne zatrzymanie, ryzyko usunięcia, brak backupu/produkcyjnego SLA |
| Możliwość zamiany HANA BLOB na Object Storage | Migracja treści i referencji wymaga spójnego protokołu i osobnych testów |

## 13. Roadmapa wdrożenia strategii

| Etap | Zakres | Definicja ukończenia |
| --- | --- | --- |
| **I — TERAZ** | Spisać architekturę, ADR-0009, as-built i źródła prawdy | Dokumenty w Git; rozdzielone VERIFIED/PENDING; bez zmian infrastruktury |
| **II — CAP-HANA-01** | Kontrolowany HDI deployment i CAP HDB JDBC, test DDL/runtime/DML na syntetykach | Rzeczywiste dowody LIVE, restart/read, rolę/tenant negative checks; PR #9 i oddzielne zgody |
| **III — DbAC hardening** | Procedura migration/roll-forward/rollback, kontrakt schematu, test rekonstrukcji na osobnym DEV | Odtworzenie nowego pustego HDI z Git oraz zapis zmierzonego czasu |
| **IV — KSEF-STOR-02** | Po ADR-0008: HANA BLOB + GZIP + SHA-256, storage adapter | Test round-trip exact bytes, limity, scope, restart, błędy i cleanup |
| **V — IaC pilot** | Read-only inventory; deklaracje Terraform dla bezpiecznego małego zakresu; import zasobów, secure state, review plan | Zmiana bez nieplanowanego create/replace/destroy i bez kosztu, wykonana po zgodzie |
| **VI — DR/observability** | Test backup/recovery poza Free Tier, alerty, budżety, procedury outage i KSeF reconciliation | Mierzalne RTO/RPO, exercise i zweryfikowane procedury |
| **VII — SaaS readiness** | Tenancy, security/compliance, zarządzany storage, finansowanie, onboarding/upgrade klientów | Oddzielne ADR-y, niezależne security i legal acceptance |

**Kolejność jest celowa:** najpierw działająca persystencja i test na prawdziwej HANA, potem adapter BLOB, następnie większa automatyzacja provisioningowa. Nie budujemy rozległej infrastruktury Terraform przed zrozumieniem faktycznie potrzebnych zasobów i ryzyka kosztowego.

## 14. Zasady akceptacji i testu odtwarzalności

Minimum dla każdego nowego elementu infra/db:
- [ ] Źródło prawdy w repo i właściciel komponentu.
- [ ] As-built, plan zmian i rollback/roll-forward.
- [ ] Testy build/CI (oraz LIVE przy deklarowaniu statusu LIVE).
- [ ] Brak sekretów/realnych danych w Git, logach, artefaktach i Terraform state przechowywanym jawnie.
- [ ] Akceptacja kosztu, zakresu uprawnień i blast radius.
- [ ] Walidacja izolacji tenant/NIP i brak niezamierzonego KSeF SEND/SAP WRITE.
- [ ] Test rekonstrukcji struktury w nowym pustym kontenerze, bez naruszania działającej bazy.
- [ ] Osobny plan ochrony i odtwarzania danych, jeśli są ważne.
- [ ] Raport z dowodami i wyraźnym rozróżnieniem: offline / live / owner-reported / pending.

**Ćwiczenie „Database from Git”**: w osobnym, zatwierdzonym, pustym HDI deployujemy ten sam model z czystego SHA, porównujemy rzeczywiste obiekty i wykonujemy syntetyczne testy. Nie usuwamy aktualnej HANA w ramach ćwiczenia. Wynik sukcesu oznacza odtworzoną **strukturę**, nie odzyskane **dane**.

## 15. Otwarte pytania / decyzje wymagające kolejnych ADR

1. Jakie zasoby BTP chcemy przejąć pod Terraform i jak zaimportować je bez replacement?
2. Gdzie przechowywać i blokować Terraform state oraz kto może wykonywać apply?
3. Jak testować odtwarzanie usuniętej HANA bez ryzyka utraty działającego DEV?
4. Czy i kiedy zatwierdzamy ADR-0008 dla BLOB, a później R2 EU lub płatny managed object storage?
5. Jakie RPO/RTO i retencja będą wymagane przy prawdziwych fakturach i przyszłym SaaS?
6. Jak zabezpieczyć migracje schematu w deploymentach z ruchem i różnymi wersjami aplikacji?
7. Które elementy ABAP RAP/IAM/DCL nadal wymagają ręcznej aktywacji, nawet jeśli artefakty są w Git?

## 16. Źródła i materiały

### SAP — oficjalna dokumentacja
- [SAP HANA Deployment Infrastructure — Administration](https://help.sap.com/docs/hana-cloud-database/sap-hana-cloud-sap-hana-database-administration-guide/sap-hana-deployment-infrastructure-hdi-administration) — HDI zarządza deployem, ale nie zawiera systemu version control.
- [SAP Learning — Deploying Models](https://learning.sap.com/courses/developing-data-models-with-sap-hana-cloud/deploying-models_d1159ed0-e5f4-492a-bf86-59e2314eae2f) — lifecycle kontenerów HDI i design-time artifacts.
- [CAP — Customizing cds build](https://cap.cloud.sap/docs/guides/deploy/build) — generowanie HANA design-time artifacts.
- [SAP HANA Database License — Free Tier](https://help.sap.com/docs/hana-cloud/sap-hana-cloud-administration-guide/sap-hana-database-license) — nocny stop, 30 dni, brak backup/recovery/snapshotów.
- [Account Administration Using Infrastructure as Code](https://help.sap.com/docs/btp/sap-business-technology-platform/account-administration-using-infrastructure-as-code) — SAP BTP Terraform provider i zakres administracji.
- [SAP Terraform Provider for SAP BTP](https://github.com/SAP/terraform-provider-btp) — kod i dokumentacja providera, do oceny przed wdrożeniem.

### AInvoicer — źródła lokalne
- [AGENTS.md](../../AGENTS.md) — reguły rozwoju i granice systemów.
- [ADR-0007](../decisions/ADR-0007-cap-persistence-hana-cloud.md) — trwałość CAP, binding HDI i fail-closed.
- [ADR-0008](../decisions/ADR-0008-document-storage-dev-test-proposal.md) — propozycja BLOB DEV/TEST, jeszcze niezatwierdzona jako wdrożenie.
- [HANA Cloud Free Tier as-built](../guides/HANA_CLOUD_FREE_TIER_SETUP.md) — utworzenie bazy i koszty.
- [CAP HANA readiness](../guides/HANA_CLOUD_CAP_DEPLOYMENT.md) — build, guards, MTAR, runner.
- [PR #9 — CAP-HANA-01](https://github.com/scaamp/AInvoicer/pull/9) — najnowszy stan deploymentu, błędy, korekty i bramki.

---

**Zasada nadrzędna:** „Infrastructure as Code” ma przyspieszać bezpieczne odtworzenie i ograniczać drift, **nie automatyzować nieodwracalnych błędów**. W AInvoicer traktujemy Git jako źródło definicji, ale rzeczywisty stan BTP/HANA, sekrety i dane wymagają niezależnej walidacji, ochrony i zgody na mutacje.
