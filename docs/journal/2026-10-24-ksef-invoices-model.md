---
title: "Phase 2.3 — model KsefInvoices i relacja z operacjami"
date: 2026-10-24
type: journal
status: zakonczony
tags: [ainvoicer, journal, cap-java, ksef, cds, odata-v4, security]
---

# Phase 2.3 — model KsefInvoices i relacja z operacjami

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Poprzedni krok: walidacja i autoryzacja](2026-10-24-ksef-operations-validation-authorization.md)

## Date

2026-10-24 (Europe/Warsaw).

## Branch

`java-cap-ksef`; repozytorium było czyste przed rozpoczęciem.

## Goal and Scope

Dodanie `KsefInvoices`, opcjonalnego powiązania operacji z fakturą i bezpiecznego, wyłącznie odczytowego endpointu OData. Bez SDK KSeF, SAP Remote Service, workera/outbox, wysyłki, commit/push i deployu.

## Implementation

- Model `KsefInvoices` używa `cuid` i `managed`; przechowuje kontekst NIP, kierunek, referencję SAP, numer faktury, datę, NIP sprzedawcy/nabywcy, numer KSeF, status biznesowy `UNCONFIRMED` oraz referencje do przyszłego storage.
- Po implementacji typy `KsefOperationDirection` i `KsefInvoiceDirection` scalono — oba pola `direction` korzystają z jednego `KsefDirection` (`INBOUND`/`OUTBOUND`), ponieważ w bieżącym kontrakcie znaczą to samo.
- `KsefOperations.invoice` jest opcjonalną, zarządzaną asocjacją to-one. Wygenerowana kolumna `invoice_ID` nie jest polem publicznego kontraktu. Operacje bez relacji nadal są poprawne.
- `KsefService.KsefInvoices` przyznaje `READ` roli `KsefOperator` z warunkiem NIP = jedna z wartości `$user.taxpayerNips`; `@readonly` odrzuca wszystkie operacje zapisu. Projekcja ukrywa `xmlStorageRef` i `upoStorageRef`. Projekcja operacji wyklucza asocjację.
- Testy OData potwierdzają 401 dla anonimowego użytkownika, 403 bez roli, filtrację danych A/B, blokadę CREATE/PATCH/DELETE, brak sekretów/referencji w odpowiedzi i `$metadata`, brak publicznego `$expand` i odmowę klientowskiego ustawienia FK. Test H2 przez CAP Persistence Service tworzy fakturę i powiązaną operację, a JDBC odczytuje FK.

## Model, H2 and Security Notes

- `Association` opisuje relację i nawigację; nie wyraża własności ani automatycznego kaskadowego usuwania. `Composition` modelowałaby zależny cykl życia. Zachowujemy osobne rekordy historii operacji i używamy `Association`.
- Analogia: CAP `Association` przypomina nawigację `Association` w ABAP CDS. Jest to analogia modelowania; egzekwowanie uprawnień i transakcji pochodzi z osobnych mechanizmów CAP.
- DDL H2 zawiera `invoice_ID`, lecz nie fizyczne `FOREIGN KEY`. Dokumentacja CAP wskazuje, że generowane ograniczenia FK nie są obsługiwane przez H2. Obecny zaufany test potwierdza poprawne powiązanie, nie ochronę przed błędnym ID ani niezgodnym NIP. Backendowa ścieżka, która zacznie wiązać faktury, musi przed zapisem sprawdzić istnienie faktury i równość `taxpayerNip`; trzeba ją dodać przed udostępnieniem takiej ścieżki.
- `businessStatus` faktury jest odrębny od technicznego `KsefOperations.status` i nie jest `status.code` MF. Nie zapisujemy `ACCEPTED` bez odpowiedzi MF. Referencje storage nie zawierają XML/UPO.
- H2 jest in-memory; pełny build uruchomił świeżą persystencję i wszystkie testy. Nie dowodzi to zachowania danych po restarcie JVM.

## Changed Files

- `cap-java/db/schema.cds` — model faktury i asocjacja.
- `cap-java/srv/ksef-service.cds` — odczytowa projekcja z instancyjną autoryzacją NIP oraz ukrytymi relacjami/referencjami.
- `cap-java/srv/src/test/java/customer/cap_java/KsefServiceODataIntegrationTest.java` — testy HTTP/OData, metadata i H2.
- `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/README.md` oraz ten wpis.
- Pliki wygenerowane przez build CDS (klasy, `csn.json`, EDMX, `schema-h2.sql`) są artefaktami generowanymi; nie edytowano ich ręcznie.

## Commands and Versions

Z `D:\AInvoicer\cap-java`: `java -version` → Microsoft OpenJDK `25.0.4.1`; `mvn -version` → Maven `3.9.16`.

- `mvn clean verify` — FAIL podczas kontroli `cds version` przez `spawn EPERM`, zgodnie z wcześniejszym blokerem środowiska.
- `mvn clean verify '-Dcds.cds.skipVersionCheck=true'` — BUILD SUCCESS; CDS build i H2 DDL, JAR-y oraz 20 testów (1 H2 DataSource + 19 OData) PASS.
- Obejście pomija wyłącznie sprawdzanie wersji CDS; nie naprawia `spawn EPERM`.

## Current State and Next Step

Model i publiczny odczyt są ukończone dla CAP/H2 lokalnie. Najmniejszy następny krok: dodać backendową usługę/use case wiązania operacji z fakturą, która waliduje, że rekord istnieje i ma ten sam `taxpayerNip`, z testem odmowy dla rozbieżnego NIP. Przed dalszą integracją utrzymać publiczną projekcję bez nawigacji.
