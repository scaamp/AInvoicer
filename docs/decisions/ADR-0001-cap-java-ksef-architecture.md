---
title: "ADR-0001 — CAP Java i Java SDK KSeF w jednej JVM"
date: 2026-10-23
type: adr
status: zaakceptowany
tags: [ainvoicer, architektura, cap-java, ksef]
---

# ADR-0001 — CAP Java + Spring Boot jako backend AInvoicer

[Project Home](../README.md) · [Stan implementacji](../STATUS.md) · [Dziennik bootstrapu](../journal/2026-10-23-cap-java-bootstrap.md)

## Context

AInvoicer ma istniejący frontend SAPUI5 oraz moduły ABAP RAP, Node.js i Python. Potrzebuje utrzymywalnego backendu dla procesów integracji z KSeF 2.0 i praktycznej ścieżki nauki SAP CAP Java, Spring Boot, SAP BTP oraz ABAP Cloud.

Według kontekstu projektu eksperyment na osobnej gałęzi `feature/ksef-java-e2e-runner` potwierdził Node.js → Java SDK MF → KSeF TEST, z numerem KSeF i UPO. Nie powtarzano go w tej sesji. Jest dowodem wykonalności użycia SDK, a nie dowodem gotowości integracji CAP ani kodem produkcyjnym do bezpośredniego przeniesienia.

Potrzebujemy jednego właściciela procesu, statusów, referencji i audytu KSeF. SAP pozostaje źródłem prawdy dla dokumentów finansowych.

## Decision

**Wybraliśmy CAP Java + Spring Boot jako główny backend biznesowy AInvoicer, integrujący oficjalne Java SDK KSeF bezpośrednio w tej samej JVM.**

Przyjmujemy modułowy monolit: CDS / Event Handlers → usługi aplikacyjne i domenowe → adaptery SAP Remote Service, SDK KSeF i magazynu dokumentów. Nie dodajemy osobnej warstwy REST pomiędzy CAP a SDK.

CAP zapewnia model CDS, OData V4, Event Handlers i mechanizmy persystencji. [Dokumentacja CAP Java](https://cap.cloud.sap/docs/java/) jest punktem odniesienia dla implementacji.

ABAP RAP wystawia autoryzowane API danych SAP. CAP konsumuje je przez [Remote Services i Destinations](https://cap.cloud.sap/docs/java/cqn-services/remote-services), po potwierdzeniu rzeczywistego `$metadata`. Node.js i Python zachowują istniejące funkcje AI i nie przejmują KSeF auth.

[Repozytorium SDK MF](https://github.com/CIRFMF/ksef-client-java) rozdziela bibliotekę `ksef-client` i `demo-web-app`. Integrujemy bibliotekę za interfejsem aplikacji; wersja SDK nie została jeszcze wybrana ani dodana do CAP.

CAP utrzymuje własne operacje i metadane KSeF. Akcja OData zapisuje operację, a trwały worker/outbox przetwarza ją po commit. Własna persystencja: H2 lokalnie, docelowo SAP HANA Cloud po potwierdzeniu dostępności. XML FA(3) i UPO wymagają trwałego magazynu dokumentów z referencjami w bazie.

## Alternatives

| Alternatywa | Korzyść | Powód niewybrania |
| --- | --- | --- |
| CAP Node.js + osobny Java Gateway | Zachowanie Node.js przy dostępie do SDK Java | Dwa runtime'y, dodatkowy kontrakt HTTP, deployment i obsługa awarii pomiędzy usługami |
| RAP + Java Gateway | Więcej procesu w ABAP i dostęp do SDK przez usługę | Podział workflow między RAP i Gateway; słabiej realizuje cel nauki i wykorzystania CAP |
| Czysty Spring Boot bez CAP | Jeden runtime Java i pełna kontrola frameworków | Konieczność osobnego zapewnienia funkcji CDS/OData i rezygnacja z przyjętej ścieżki SAP CAP |

## Consequences

- Jeden runtime upraszcza integrację z SDK, lecz oznacza wspólny deployment, skalowanie i obszar awarii komponentów Java.
- CAP nie uzyskuje bezpośredniego SQL do wewnętrznej bazy ABAP Environment. Potrzebne są dostępny kontrakt Remote OData, autoryzacja i odporność na błędy zewnętrznego systemu; nie łączymy zdalnych encji pozornym JOIN-em SQL z bazą CAP.
- CAP wymaga własnej trwałej persystencji procesu KSeF, idempotencji, ograniczeń unikalności, kontroli konkurencji, rekoncyliacji i odtwarzania po restarcie. Nie replikuje bez potrzeby całej tabeli finansowej SAP.
- Długie wywołania i polling pozostają poza transakcją zapisu OData. Po utracie odpowiedzi send/redeem/close wymagany jest stan niejednoznaczny, bez ślepego retry. HTTP 202 nie zastępuje końcowego statusu biznesowego KSeF.
- Credentiale, JWT i kryptografia pozostają w backendzie. Należy autoryzować kontekst podatnika, rozdzielić środowiska i nie ujawniać sekretów w UI, logach ani dokumentacji.
- Zgodność wersji CAP, Spring Boot, JDK i SDK trzeba sprawdzić przed integracją; nie kopiujemy aplikacji demonstracyjnej SDK do produkcyjnego API.

## Status

Zaakceptowany — zapis istniejącej decyzji opisanej w `AGENTS.md`, nie nowa migracja architektury. Implementacja rozpoczęta na `java-cap-ksef`; na dzień 2026-10-23 istnieje szkielet CAP, a SAP Remote Service, SDK i E2E CAP pozostają do wykonania. Bieżące dowody: [STATUS](../STATUS.md); plan: [ROADMAP](../ROADMAP.md).
