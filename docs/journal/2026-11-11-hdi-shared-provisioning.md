---
title: "HDI hdi-shared — provisioning właściciela i niezależny preflight CF"
date: 2026-11-11
type: journal
status: provisioning-verified-deployment-pending
tags: [ainvoicer, journal, cap-hana-01, hana, hdi, btp, costs, cloud-foundry]
---

# HDI hdi-shared — konfiguracja i dowody

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) ·
[Wydanie i testy CAP-HANA-01](2026-11-11-cap-hana-01-first-persistence.md) ·
[Runbook](../guides/CAP_HANA_01_FIRST_DEPLOYMENT_RUNBOOK.md)

## Date / Branch / Goal

11 listopada 2026, Europe/Warsaw. Branch `feature/cap-hana-01-first-persistence`, PR #9.
Udokumentować ręczne utworzenie HDI przez właściciela i niezależnie odświeżyć inventory.
Celem biznesowym jest trwała historia procesu KSeF po restarcie CAP, zgodnie z ADR-0007.
**CAP-HANA-01: NOT DEPLOYED.** Provisioning HDI nie jest deploymentem schematu ani testem JDBC.

## Initial State — architektura i decyzja właściciela

**OWNER-REPORTED:** właściciel omówił HANA, HDI, deployer, CAP CDS i bindingi oraz wybrał standardowe
CAP + HANA Cloud + HDI zamiast ręcznie zarządzanego schematu SQL. Istniejąca `ainvoicer-hana-dev`
ma plan `hana-free`; HDI wykorzystuje jej zasoby, nie tworzy drugiej bazy.

HANA Cloud to silnik bazy. HDI zarządza izolowanym kontenerem schematu, obiektami i uprawnieniami.
HDI Deployer jest programem wdrażającym wygenerowane z CDS artefakty do tego kontenera.
CAP Java korzysta następnie z tabel przez HANA JDBC i technicznego użytkownika bindingu.
Deployer i aplikacja otrzymują zarządzane credentiale; `DBADMIN` pozostaje wyłącznie administracyjny.
Takie rozdzielenie pozwala utrwalać operacje, rezerwacje, checkpointy i rekordy outbox bez
kopiowania tabel finansowych SAP. XML/UPO i BLOB pozostają poza tym zadaniem.

## Koszty i świadoma decyzja PAYG

**OWNER-REPORTED:** w Cockpit entitlement `SAP HANA Schemas & HDI Containers / hdi-shared`
pochodził z `Pay-As-You-Go for SAP Business Technology Platform`, Commercial Type `Consumption`.
Właściciel zaakceptował utworzenie HDI na podstawie dokumentacji klasyfikującej plan jako bezpłatny.
Nie dodano płatnej HANA ani Object Store. Ogólne ostrzeżenie Cockpit o możliwych kosztach
zależnych od wybranego planu nie wskazywało konkretnej opłaty.

**VERIFIED LIVE:** `cf marketplace -e hana` podaje `hdi-shared / free`; CAPI service plan ma
`free=true`. Istniejąca baza ma `hana-cloud/hana-free`. Quota CF `FREE_QUOTA` wynosi 4096 MB;
bieżące procesy rezerwują 1408 MB (CAP 1024, AppRouter 256, zastane myapp 128).
Przyszły deployer deklaruje 256 MB. MTA wymaga trzech existing-service, bez provisioningu nowych usług.
Nie zmieniano quota ani entitlementów.

Model komercyjny PAYG, przydzielenie entitlementu, provisioning instancji, cena planu i zużycie
bazowej HANA/CF to osobne kwestie. Bezpłatność HDI nie usuwa zużycia pamięci HANA ani CF.
**PENDING:** Costs and Usage oraz indywidualna stawka kontraktu nie zostały sprawdzone.
Nie udzielamy gwarancji kosztu całego konta. Discovery Center jest punktem katalogowym;
jego dynamiczna strona nie dostarczyła czytelnych danych w tym odczycie. Wniosek o planie opiera się
na własnym CF/CAPI i SAP Help, nie na domniemanym odczycie cennika kontraktowego.

## Implementation — sekwencja wykonana w Cockpit

Poniższe kroki są **OWNER-REPORTED**, według przekazanego opisu i relacji o ekranie właściciela.
Agent nie wykonywał tych operacji i nie oglądał ponownie oryginalnego zrzutu.

1. Analiza architektury: HDI na istniejącej HANA, CAP CDS → obiekty HANA, deployer i bindingi.
2. Analiza PAYG/Consumption, dokumentacji bezpłatnego planu i ogólnego ostrzeżenia kosztowego.
3. `Global Account → Entitlements → Entity Assignments → dev → Edit → Add Service Plans`:
   wybór `SAP HANA Schemas & HDI Containers / hdi-shared`, zapis entitlementu. Plan pojawił się
   w Service Marketplace.
4. `SAP HANA Schemas & HDI Containers → Create`: plan `hdi-shared`, runtime `Cloud Foundry`,
   space `dev`, nazwa `ainvoicer-db`.
5. W Parameters omówiono JSON `{"database_id":"<DATABASE_ID_ISTNIEJACEJ_HANA>"}` dla
   `ainvoicer-hana-dev`. Sam opis rozmowy nie dowodzi, co zapisano; odczyt brokera poniżej
   niezależnie potwierdza aktualne `database_id`.
6. Cockpit przeszedł z `Creation in Progress` do `Usable / Creation Succeeded`.
   Końcowy ekran według właściciela: Bound Applications 0, Service Keys 0.

## Commands Executed — niezależna inspekcja Codexa

**VERIFIED LIVE**, odczyt 11 listopada około 17:51–18:00 CET, CF CLI 8.19.0:
`cf target`, `services`, `service ainvoicer-db`, `service ainvoicer-hana-dev`, `apps`, `stacks`,
`buildpacks`, `plugins`, `marketplace -e hana`. CAPI GET metadanych usług/planów/space/org/quota,
aplikacji/procesów/dropletów i `service_credential_bindings` bez `/details`.
Z `/v3/service_instances/<guid>/parameters` wyświetlono wyłącznie wybrane niesekretne pola
`database_id`, `serviceStopped`, wersję produktu; bez credentiali bindingu i bez surowych parametrów.
Z `environment_variables` aplikacji wyświetlono tylko dozwolone flagi i obecność ryzykownych kluczy;
nie odczytywano systemowego `/env`, VCAP ani service keys.

| Parametr | Wynik | Klasa dowodu |
| --- | --- | --- |
| CF API | `https://api.cf.eu20-001.hana.ondemand.com` | VERIFIED LIVE |
| Org / space | `National Center for Cryptography_dev-v2xzij9s` / `dev` | VERIFIED LIVE |
| Subaccount | `dev` według Cockpit; nie odczytywano global-account API | OWNER-REPORTED |
| HDI | `ainvoicer-db`, offering `hana`, plan `hdi-shared`, managed | VERIFIED LIVE |
| Provisioning | `create succeeded`, start/koniec `2026-11-11T16:49:24Z` (17:49:24 CET) | VERIFIED LIVE |
| Bindings HDI | 0 credential bindings, zatem 0 app bindings i 0 service-key bindings | VERIFIED LIVE |
| Cel brokera HDI | `database_id=2161fe76-faf9-40e7-8693-72728fa6d49e` | VERIFIED LIVE |
| HDI service instance GUID | `b9358808-753a-4a39-9b18-859f77178cd1`, inny od Database ID | VERIFIED LIVE |
| HANA CF | `ainvoicer-hana-dev`, `hana-cloud/hana-free`, bez bindingów | VERIFIED LIVE |
| Stan brokera HANA | `serviceStopped=false`, produkt `2026.28.14`, build `4.00.000.00.1793723943` | VERIFIED LIVE |
| Cloud Central | właściciel potwierdził w odpowiedzi w tej sesji RUNNING i zgodny Database ID | OWNER-REPORTED |
| CAP / AppRouter | STARTED 1/1, dwa bindingi XSUAA/Destination, bez HDI | VERIFIED LIVE |
| CAP JDBC / schemat / DML | nie wykonano deploymentu ani połączenia testowego | PENDING |

Zbieżność Database ID z GUID-em usługi HANA nie stanowiła samodzielnego dowodu mapowania.
Źródła to jawny parametr brokera HDI oraz niezależne potwierdzenie właściciela w Cloud Central.
`serviceStopped=false` i historyczne `create succeeded` nie zastępują bieżącego SQL health.

## Bezpieczeństwo i zarządzanie credentialami

Agent nie tworzył usług, kluczy serwisowych, użytkowników DB ani bindingów. Nie używał DBADMIN,
nie kopiował credentiali na laptop ani do Git. Nie dodano screenshotów, loginów kont ani surowych logów.
Identyfikatory infrastruktury w tabeli są niesekretnymi danymi do korelacji.
W przyszłym deploymencie credentiale HDI dostarcza binding platformy wyłącznie backendowi/deployerowi.
Deployer ma `no-route=true`. KSeF, SAP WRITE, storage oraz oba rodzaje outbox pozostają OFF.

## Current State / Next Steps

**VERIFIED LIVE:** provisioning zakończony, blokada `HDI NOT CONFIGURED` jest nieaktualna.
**PENDING / NOT DEPLOYED:** CAP nadal nie ma bindingu HANA; obiekty CAP nie zostały wdrożone.
Nie potwierdzono pustego schematu SQL odczytem katalogu; nowy, dedykowany kontener i brak bindingów
są przesłankami, nie pełnym inventory SQL. Nie uruchamiano żadnej fazy `HanaPersistenceIT`.

1. Zatwierdzić konkretny MTAR/SHA, extension/stack, DDL/bindingi i przerwę CAP/AppRoutera zgodnie
   z [bieżącą bramką](../guides/CAP_HANA_01_FIRST_DEPLOYMENT_RUNBOOK.md).
2. Po udanym deployu sprawdzić HDI/JDBC/guardy/anonimowe odmowy i autoryzowany READ.
3. Przygotować osobno zatwierdzony runner z zarządzanym bindingiem i zgodę na syntetyczne DML/restart;
   testować własny run UUID i jego cleanup. LIVE A/B/DCL/principal H01 pozostają otwarte.

## Źródła SAP i ograniczenia

- [SAP Help — HDI services](https://help.sap.com/docs/hana-cloud-database/sap-hana-cloud-sap-hana-database-developer-guide-for-cloud-foundry-multitarget-applications-sap-business-app-studio/sap-hana-cloud-deployment-infrastructure-services).
- [SAP Help — service plans and resources](https://help.sap.com/docs/HANA_CLOUD_DATABASE_CN/ebd4a88a3ca646748222f5308d31d816/0393ce339acf49bf97b17c671ce92b9f.html?locale=en-US&state=PRODUCTION&version=2025_3_QRC) — opis planu free; nie cena indywidualnego kontraktu.
- [SAP Help — tworzenie HDI i database_id](https://help.sap.com/docs/HANA_CLOUD/db19c7071e5f4101837e23f06e576495/2863434ddda042b8b8011a3f24856281.html).
- [Discovery Center — SAP HANA Cloud](https://discovery-center.cloud.sap/serviceCatalog/sap-hana-cloud?region=all&tab=service_plan) — katalog, nie dowód Costs and Usage.
- [Discovery Center — SAP HANA Schemas & HDI Containers](https://discovery-center.cloud.sap/serviceCatalog/sap-hana-schemas--hdi-containers?region=all&tab=service_plan) — odczyt dynamicznej strony niedostępny; nie przypisujemy jej niezależnie potwierdzonej ceny.
- [CAP Java — Persistence Services](https://cap.cloud.sap/docs/java/cqn-services/persistence-services) i [CAP — Database integration](https://cap.cloud.sap/docs/guides/databases/).
