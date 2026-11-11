---
title: "ADR-0009 — Infrastructure as Code i Database as Code jako strategia rozwoju AInvoicer"
date: 2026-11-11
type: adr
status: przyjęta-strategia-wdrożenie-etapowe
tags: [ainvoicer, architecture, infrastructure-as-code, database-as-code, devops, sap-btp, cap-java, hana-cloud, hdi]
---

# ADR-0009 — Infrastructure as Code / Database as Code

## Status

**Przyjęta strategia architektoniczna, implementacja etapowa.** Dokumentuje kierunek przyjęty przez właściciela: dziś projekt edukacyjny prowadzony z dyscypliną produktu, w przyszłości potencjalny SaaS. **Nie** stanowi zgody na utworzenie płatnej usługi, `terraform apply`, `cf deploy`, zmianę IAM, usunięcie danych ani wdrożenie na produkcji.

[Pełny raport i strategia](../architecture/INFRASTRUCTURE_AS_CODE_STRATEGY.md) · [ADR-0007 HANA/HDI](ADR-0007-cap-persistence-hana-cloud.md) · [ADR-0008 BLOB DEV/TEST — propozycja](ADR-0008-document-storage-dev-test-proposal.md).

## Kontekst i problem

AInvoicer ma wersjonowane modele CAP CDS, artefakty HDI, deskryptory MTA i testy, ale część infrastruktury SAP BTP powstawała ręcznie w Cockpit. SAP HANA Cloud Free Tier ma ograniczenia dostępności, retencji i odtwarzania; utrata bazy wymagałaby ponownego provisioningu. Nie chcemy uzależniać możliwości odtworzenia struktury od ręcznie stworzonego schematu ani pamięci administratora.

Z drugiej strony procesy KSeF wymagają bezwzględnej trwałości statusów, idempotency key, outbox i oryginalnych dokumentów. Odtworzenie struktury **nie** równa się odtworzeniu danych lub poprawnemu wznowieniu przetwarzania.

## Decyzja

1. **Database as Code:** model schematu CAP utrzymujemy w wersjonowanym CDS i, gdy potrzebne, jawnych artefaktach HDI; `cds build --for hana` generuje artefakty wdrożeniowe, a zatwierdzony HDI Deployer tworzy/aktualizuje fizyczne obiekty.
2. **Infrastructure as Code — kierunek docelowy:** konfigurację BTP/Cloud Foundry (subaccount, entitlements, usługi, bindingi, aplikacje, niezbędne IAM) stopniowo odwzorowujemy w deklaracjach pod kontrolą Git i review. Najpierw read-only inventory i dokumentacja as-built, następnie pilot oficjalnego Terraform Provider for SAP BTP oraz API/providers dla pozostałych zasobów, **po sprawdzeniu faktycznej obsługi i ryzyk**.
3. **Rozdział desired state / runtime state / business data:** Git jest źródłem wersjonowanych definicji. Rzeczywiste zasoby istnieją w BTP/HANA i wymagają weryfikacji. Dane biznesowe wymagają osobnego backupu, recovery i rekoncyliacji.
4. **Żadnego automatycznego kosztu ani destrukcji:** przed każdą zmianą infrastruktury obowiązują plan/diff, jawna kontrola kosztów, zakres uprawnień, blast radius, rollback/roll-forward i zgoda właściciela. Istniejące instancje wymagają bezpiecznego importu do IaC, nie duplikowania.
5. **Fail-closed i sekrety:** runtime CAP na CF musi używać zatwierdzonego HANA HDI bindingu; nie ma cichego H2 fallback. Sekrety, klucze, JWT oraz Terraform state potencjalnie zawierający dane poufne nie trafiają do repo ani publicznych logów.
6. **Odtworzenie po awarii:** test „Database from Git” należy przeprowadzić na odizolowanej, pustej bazie/kontenerze i odróżnić sukces odtworzenia schematu od odzyskania rekordów, dokumentów oraz stanu KSeF.
7. **Przyszły SaaS:** obecny jeden kontener HDI oraz dedicated XSUAA nie oznaczają multitenancy SaaS. Strategia tenant provisioning, izolacja danych, retencja, backup, RPO/RTO i compliance wymagają oddzielnych decyzji.

## Konsekwencje

**Korzyści:** łatwiejsze odtworzenie schematu po utracie HANA Free Tier, powtarzalność DEV/STAGE/PROD, mniejszy drift, przeglądy zmian, lepsza zgodność bezpieczeństwa i kosztów, podstawa do przyszłego SaaS.

**Koszty/ryzyka:** utrzymanie pipeline i dokumentacji, ograniczenia Terraform/API, wymagane zarządzanie wrażliwym state, ręczne wyjątki dla ABAP/ADT, ryzyko destrukcyjnych zmian schematu, konieczność oddzielnych backupów i przetestowanego DR. Rollback MTAR nie odwraca automatycznie migracji HDI.

## Zakres bieżący i poza zakresem

Na 11.11.2026 według repo `main`: INT-03 jest scalony, model CDS i offline HANA readiness istnieją. Właściciel potwierdził przez BTP Cockpit utworzenie `ainvoicer-db` (`hdi-shared`, `Usable`). Osobny PR #9 CAP-HANA-01 dotyczy kontrolowanego deploymentu HANA; jego pierwszy deployment został odrzucony z powodu nieobsługiwanego typu modułu MTA, poprawka jest przygotowana offline, a LIVE persystencja pozostaje niepotwierdzona. Nie zakładamy zakończenia deploymentu na podstawie zielonego CI.

Wykonanie Terraform, migracja zasobów pod zarządzanie IaC, aktywacja storage BLOB/R2 oraz uruchomienie biznesowych operacji KSeF nie należą do tej decyzji.

## Odrzucone alternatywy

- Ręczne i niewersjonowane zarządzanie schematem jako docelowy model.
- Traktowanie backupu jako zbędnego, ponieważ model CDS można odbudować.
- Natychmiastowe terraformowanie całego BTP bez inventory, uprawnień, kosztów, importu istniejących zasobów i review.
- Bezpośrednia automatyzacja destrukcyjnych zmian albo bezwarunkowy deploy przez agentów/CI.
- Zakładanie, że CAP/CDS automatycznie wdraża obiekty ABAP RAP w zdalnym H01.

## Kryteria weryfikacji strategii

- Definicje schematu i kluczowych zasobów mają właściciela, historię Git i code review.
- Build oraz release evidence walidują konkretny zestaw źródeł i artefaktów.
- Test na nowym, pustym HDI odtwarza zadeklarowane tabele/widoki/constraints bez ręcznego `CREATE TABLE`.
- Przywrócenie danych i operacji KSeF jest opisane oraz testowane osobno, przed uznaniem środowiska za produkcyjne.
- IaC pilot pokazuje diff bez nieplanowanego utworzenia/usunięcia/replacementu, ma bezpieczny state i jasno zatwierdzone koszty.
- Stan realizacji, ograniczenia i dowody LIVE są na bieżąco aktualizowane w STATUS i runbookach.

Zobacz [kompletną strategię, architekturę, plan wdrażania i procedurę DR](../architecture/INFRASTRUCTURE_AS_CODE_STRATEGY.md).
