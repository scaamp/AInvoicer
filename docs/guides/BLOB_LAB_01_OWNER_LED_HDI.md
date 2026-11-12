---
title: "BLOB-LAB-01 — laboratorium CAP Java / HDI / HANA BLOB prowadzone przez właściciela"
date: 2026-11-11
type: guide
status: owner-reserved-not-started
tags: [ainvoicer, education, cap-java, hana, hdi, blob, owner-led, agents]
---

# BLOB-LAB-01 — owner-led: CAP CDS → HDI → HANA BLOB → OData V4

> **AKTUALIZACJA — 12.11.2026 (wyniki właściciela, LIVE):** Laboratorium zostało rozpoczęte w istniejącym AInvoicer / `ainvoicer-db` (wariant B). Właściciel utworzył lokalną encję `KsefDocuments`, zbudował i wdrożył MTAR HANA 0.7.3, potwierdził zadanie HDI #3 `SUCCEEDED`, fizyczną tabelę `CUSTOMER_CAP_JAVA_KSEFDOCUMENTS`, kolumnę `CONTENT BLOB` oraz `SELECT COUNT(*) = 0` jako HDI runtime `_RT`. **Nadal brak:** committed `documents.cds`, OData media service, upload/download, DML, integralność SHA-256 i test restartu. Szczegóły i dowody: [dziennik BLOB-LAB-01 z 12.11](../journal/2026-11-12-blob-lab-01-owner-live-hdi-blob.md). Ten dopisek aktualizuje historyczne statusy „not started” niżej; ograniczenia owner-led nadal obowiązują.


> **REZERWACJA ZADANIA DLA WŁAŚCICIELA (OWNER-LED).**
> Codex, Claude Code i inni agenci **nie mogą autonomicznie zaimplementować, wdrożyć, przetestować LIVE ani „dokończyć” tego laboratorium**. Mogą być mentorami, generować **wyraźnie zamówione fragmenty kodu**, recenzować pracę właściciela i pomagać w diagnostyce. Właściciel chce sam wykonać kroki praktyczne i zrozumieć ich mechanizm.

## 1. Motywacja i decyzja

11 listopada 2026 właściciel zdecydował, że chce osobiście nauczyć się pełnego procesu CAP Java + SAP HANA Cloud: od modelowania encji CDS, poprzez konfigurację kontenera i deployera HDI, po zapis i odczyt małego pliku XML/PDF z relacyjnej kolumny BLOB. Celem jest **nauka przez własne wykonanie**, a nie dostarczenie przez agenta gotowego rozwiązania.

Laboratorium to **odrębne zadanie edukacyjne**, a nie sprint implementacji produkcyjnego magazynu KSeF. Nie jest zatwierdzeniem [ADR-0008](../decisions/ADR-0008-document-storage-dev-test-proposal.md), nie uruchamia KSEF-STOR-02, nie zastępuje istniejącego adaptera Azure/Object Store i nie upoważnia do zmian workflow KSeF.

Aktualny, historycznie potwierdzony kontekst (nie zakładać trwałości stanu bez ponownego odczytu):
- Istnieją `ainvoicer-hana-dev` (HANA Cloud Free Tier) i `ainvoicer-db` (`hana/hdi-shared`, provisioning `Usable / Creation Succeeded`).
- W `main` model biznesowy CAP przechowuje referencje `xmlStorageRef`/`upoStorageRef`; nie ma encji `BlobLabDocuments` ani implementacji adaptera HANA BLOB.
- Ostatni udokumentowany CAP-HANA-01 zakończył staging HDI deployera błędem uprawnień ZIP; naprawa offline jest w `main`, ale LIVE persystencja HANA nie została potwierdzona.
- Przed ćwiczeniem ponownie ustalić status BTP i kodu. Samo `CI PASS` ani `HDI Usable` nie jest testem BLOB.

Źródła stanu: [STATUS](../STATUS.md), [runbook CAP-HANA-01](CAP_HANA_01_FIRST_DEPLOYMENT_RUNBOOK.md), [HDI provisioning](../journal/2026-11-11-hdi-shared-provisioning.md).

## 2. Dwa warianty środowiska — WYBÓR JESZCZE NIEZATWIERDZONY

- **A: odrębny minimalny projekt CAP Java + osobny kontener HDI** na już istniejącej HANA Free Tier. Preferowany dydaktycznie, jeśli potwierdzimy limit i koszt dodatkowej instancji oraz brak ryzyka dla AInvoicer.
- **B: oddzielona encja i usługa edukacyjna w istniejącym projekcie**, z wykorzystaniem `ainvoicer-db`. Prostszą liczbę usług równoważy ryzyko zmiany działającego modelu i deployu.

**Decyzję A/B podejmuje właściciel po porównaniu konsekwencji.** Nie tworzyć nowej HANA, HDI, service key, bindingu, routingu ani płatnego entitlementu bez jego osobnej zgody. Dostępność planu `hdi-shared` nie daje indywidualnej gwarancji kosztu konta PAYG.

## 3. Właściciel samodzielnie wykonuje

1. Wybór wariantu A/B oraz utworzenie/konfigurację projektu ćwiczeniowego.
2. Przygotowanie środowiska i zrozumienie układu `db/`, `srv/` i builda CAP Java.
3. **Własnoręczne zapisanie** modelu encji CDS (np. `BlobLabDocuments`) z UUID, nazwą, MIME type, `LargeBinary` i datami.
4. Własnoręczne zdefiniowanie projekcji/usługi CAP i zrozumienie `@Core.MediaType`.
5. Uruchomienie kompilacji CDS do HANA, odczyt i analiza powstałego `.hdbtable` (mapowanie `LargeBinary` → `BLOB`).
6. **Własnoręczne przygotowanie HDI**: wybór istniejącego lub utworzenie zatwierdzonego nowego kontenera, zrozumienie deployera, ról i bindingów.
7. Uruchomienie zatwierdzonego deploymentu HDI i odczyt logów.
8. Obejrzenie schematu, tabeli, kolumn i danych w SAP HANA Database Explorer.
9. Wykonanie uploadu i downloadu wyłącznie **syntetycznych małych XML/PDF** przez zabezpieczone CAP OData V4.
10. Porównanie SHA-256 bajtów przed uploadem i po pobraniu; restart CAP i kontrola trwałości.
11. Własne podsumowanie wniosków i różnic do ABAP DDIC/CDS/RAP.

Przechodzimy **jedno zadanie naraz**, dopiero po sprawdzeniu wyniku poprzedniego. Wyniki testów własnoręcznych mogą być dokumentowane jako OWNER-REPORTED do czasu niezależnej weryfikacji.

## 4. Dozwolone wsparcie Codex / Claude Code / ChatGPT

Agenci mogą na **konkretne zlecenie w danym kroku**:
- wyjaśnić koncepcje i zależności, zestawić CAP CDS/HDI z ABAP DDIC/RAP;
- podpowiedzieć składnię, komendy i miejsca konfiguracji, nie wykonywać ich;
- wygenerować **mały wskazany snippet** lub test jednostkowy do świadomego wklejenia przez właściciela;
- zrobić review wskazanego pliku, diffu, screenshotu, metadanych lub logu i zasugerować poprawki;
- pomóc diagnozować błąd, najpierw wskazówkami; pełny kod dopiero na wyraźne życzenie;
- na osobną, precyzyjną prośbę edytować *wyliczone pliki* ćwiczenia, przedstawiając diff do review właściciela;
- na osobną zgodę wykonać nieinwazyjne lokalne testy wskazanego fragmentu.

Przykłady poprawnych próśb:
- „Wyjaśnij różnicę `LargeBinary` i `@Core.MediaType`; nie pisz pliku”.
- „Sprawdź mój `BlobLabDocuments.cds` i wskaż błędy, bez edycji”.
- „Wygeneruj tylko anotację content disposition; sam ją wkleję”.
- „Zmień wyłącznie wskazany plik LAB, pokaż diff, nie deployuj”.

**Zgoda na fragment kodu, review lub zmianę jednego pliku nie stanowi zgody na resztę laboratorium.** Nie „domykaj brakujących elementów” opportunistycznie.

## 5. Domyślne zakazy dla agentów w zakresie BLOB-LAB-01

Bez nowej, wyraźnej i **zakresowej** zgody właściciela NIE WOLNO:
- tworzyć pełnego laboratorium, kompletnej encji/projekcji/handlera/testów „za właściciela”;
- modyfikować `cap-java/db/schema.cds`, serwisów, handlerów Java, MTA, pakietu HDI lub `KsefDocumentStore` w celu dodania BLOB-LAB;
- uruchamiać `cf create-service`, `cf bind-service`, `cf deploy`, `cf push`, HDI deployera, restartów, DDL/DML/cleanup na HANA;
- generować service keys, eksportować `VCAP_SERVICES`, credentiale albo dawać `DBADMIN` aplikacji;
- tworzyć publicznej trasy upload/download, wyłączać XSUAA/autoryzację, używać prawdziwych faktur;
- implementować, aktywować lub automatycznie scalać BLOB-LAB-01 lub KSEF-STOR-02 jako „zadanie poboczne” innego sprintu;
- traktować samego dokumentu jako zgody na provisioning, koszt, deployment, dane lub merge.

Zakaz dotyczy **wyłącznie zakresu edukacyjnego BLOB-LAB-01 i przypadkowego przejęcia go przez agentów**. Inne sprinty AInvoicer mogą działać zgodnie ze swoimi zadaniami; w razie kolizji plików lub storage najpierw powiadomić właściciela, nie wdrażać eksperymentu ukradkiem.

Wyraźne polecenie w przyszłości może rozszerzyć zakres jednego kroku, ale agent powinien odnotować: **co zostało zlecone, co wykonał człowiek, a czego nie wykonał agent**.

## 6. Kryteria ukończenia — potwierdzane osobno

| Etap | Dowód |
| --- | --- |
| CDS | właściciel potrafi wyjaśnić model i aktywnie uruchamia jego kompilację |
| HDI build | wygenerowany `.hdbtable` i odczyt mapowania `BLOB` |
| Provisioning/binding | rozpoznane role deployer/runtime i bezpiecznie potwierdzony binding |
| LIVE deployment | deployer `SUCCESS` i fizyczna tabela widoczna w HANA |
| OData Media | zabezpieczony upload i download syntetycznego XML/PDF |
| Integralność | zgodne SHA-256 pobranych i wejściowych bajtów |
| Trwałość | plik odczytany po restarcie procesu CAP |
| Wiedza | właściciel sam opisuje przepływ CDS → HDI → tabela → OData |

Wynik każdego etapu oznaczać `NOT STARTED`, `IN PROGRESS`, `OWNER-REPORTED` lub `VERIFIED LIVE`. Obecnie **LAB NOT STARTED**, bez implementacji i bez zgody na deployment.

## 7. Bezpieczeństwo i rozdzielenie z produkcją

Testujemy małe, odtwarzalne, syntetyczne pliki; brak prawdziwych faktur, sekretów, JWT, certyfikatów i danych osobowych. Konto PAYG i Free Tier wymagają odrębnej weryfikacji przed tworzeniem kolejnych usług. HANA BLOB nie jest SAP HANA Document Store ani produkcyjnym archiwum/backupem. Nie korzystać z nietrwałego dysku Cloud Foundry jako magazynu. Wszystkie SEND/INBOUND/SAP WRITE pozostają poza ćwiczeniem.

## 8. Jak pracować z tym dokumentem

Przed każdym zadaniem dotykającym `BLOB`, `LargeBinary`, `@Core.MediaType`, `BlobLabDocuments`, `KsefDocumentStore`, HDI albo przechowywania XML/PDF, agent sprawdza, czy chodzi o to **zarezerwowane laboratorium**, czy o oddzielnie zatwierdzony sprint. Jeśli to LAB, domyślny tryb to **MENTOR/REVIEW — nie AUTONOMOUS IMPLEMENTATION**. W razie niejasności nie podejmuj zmian LAB i wskaż punkt wymagający decyzji.

Powiązane: [ADR-0008](../decisions/ADR-0008-document-storage-dev-test-proposal.md), [HDI provisioning](../journal/2026-11-11-hdi-shared-provisioning.md), [CAP-HANA-01](CAP_HANA_01_FIRST_DEPLOYMENT_RUNBOOK.md).
