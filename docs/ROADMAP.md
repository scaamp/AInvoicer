---
title: "AInvoicer — plan rozwoju"
date: 2026-10-23
type: roadmap
status: aktywny
tags: [ainvoicer, roadmap, cap-java, ksef]
---

# Plan rozwoju

[Project Home](README.md) · [Aktualny stan i dowody](STATUS.md) · [Decyzja architektoniczna](decisions/ADR-0001-cap-java-ksef-architecture.md)

`[x]` oznacza ukończenie z podanym zakresem potwierdzenia, `[ ]` — pracę do wykonania. IN PROGRESS oznacza zadanie rozpoczęte. Ten plik jest planem; historia czynności i wyniki są w [dzienniku](journal/2026-10-23-cap-java-bootstrap.md).

## Phase 1 — CAP Java Bootstrap — IN PROGRESS

- [x] Wygenerowanie projektu CAP Java w `cap-java/` (pliki zweryfikowane; polecenie inicjalizacji z relacji użytkownika).
- [x] Pierwsze `mvn clean verify` po zmianie JDK (historyczny sukces potwierdzony przez użytkownika; aktualna próba FAIL przy pakowaniu JAR — patrz STATUS).
- [ ] BLOCKED: powtarzalny pełny build i konfiguracja lokalnego developmentu: JDK 25, Maven zgodny z POM, opis uruchomienia; wyjaśnić brak `target/classes/application.yaml` podczas pakowania.
- [ ] Uruchomienie aplikacji Spring Boot i potwierdzenie startu CAP.
- [ ] Potwierdzenie działania H2 w profilu lokalnym, nie tylko obecności zależności.

Warunek zakończenia: powtarzalny build i uruchomienie z lokalną bazą; własne API zostanie sprawdzone w Phase 2.

## Phase 2 — CAP Domain Model

- [ ] Encje CDS `KsefOperations` i `KsefInvoices`: korelacja SAP, kierunek operacji, status techniczny i biznesowy, referencje i audyt.
- [ ] Service Definitions i OData V4; minimalne Java Event Handlers i walidacja wejścia.
- [ ] Lokalne testy `$metadata`, CRUD, walidacji i persystencji H2.
- [ ] Przed pierwszą wysyłką: trwała operacja, unikalny idempotency key, kontrola konkurencji i worker/outbox po commit.
- [ ] Przed efektami zewnętrznymi: `UNKNOWN/RECONCILIATION_REQUIRED`, odtwarzanie po restarcie oraz testy niejednoznacznych odpowiedzi.
- [ ] Kontrola dostępu do operacji i kontekstu podatnika; bez sekretów w modelu publicznym.

## Phase 3 — SAP Remote Integration

- [ ] Potwierdzenie wdrożonego service definition/binding, adresu, `$metadata` i encji dla danych `ZFI_ACDOCA`.
- [ ] Import kontraktu RAP OData V4, konfiguracja CAP Java Remote Service.
- [ ] Destination / uwierzytelnienie zgodne z faktyczną konfiguracją SAP/BTP.
- [ ] Test adaptera oraz autoryzowany odczyt syntetycznych danych faktury z SAP; osobne wyniki mock i live.

Bez bezpośredniego SQL do bazy ABAP i bez pozornych JOIN-ów z lokalną bazą CAP.

## Phase 4 — KSeF SDK Integration

- [ ] Wybór i przypięcie wersji oficjalnego SDK MF, audyt zgodności z CAP/Spring Boot; adapter w tej samej JVM.
- [ ] TEST: autoryzacja właściwego kontekstu podatnika, bezpieczne przechowywanie credentiali i obsługa JWT po stronie backendu.
- [ ] Poprawny XML FA(3) według oficjalnego XSD; sesje i kryptografia obsługiwane przez SDK.
- [ ] Wysyłka przez trwały worker; bez długiego pollingu w transakcji żądania OData.
- [ ] Status końcowy i numer KSeF, zamknięcie sesji, UPO oraz metadane/ref. do trwałego magazynu dokumentów.
- [ ] Retry tylko bezpiecznych odczytów i sklasyfikowanych błędów, backoff/jitter, 429/Retry-After oraz rekoncyliacja po utracie odpowiedzi.
- [ ] Pierwszy vertical slice: jedna syntetyczna faktura SAP Remote OData → CAP → SDK → KSeF TEST → numer KSeF i UPO; wysyłka tylko po jawnej zgodzie.

HTTP 202 nie oznacza przyjęcia faktury. Eksperyment na `feature/ksef-java-e2e-runner` jest referencją według kontekstu projektu; nie zalicza tego etapu.

## Phase 5 — SAPUI5 + CAP

- [ ] Stopniowe podłączenie istniejącego frontendu do gotowego API OData CAP.
- [ ] Wyświetlanie faktur i akcji zlecenia wysyłki jako operacji asynchronicznej.
- [ ] Odczyt statusów, numeru KSeF i autoryzowane pobranie UPO.
- [ ] Weryfikacja uprawnień i regresji istniejących ekranów SAPUI5 oraz funkcji AI.

## Phase 6 — BTP & Production Hardening

- [ ] Potwierdzenie entitlementów i konfiguracji SAP HANA Cloud; migracja i testy trwałości.
- [ ] Przygotowanie deploymentu Cloud Foundry; wykonanie wdrożenia wyłącznie w autoryzowanym zakresie.
- [ ] OAuth2/XSUAA lub IAS według konfiguracji; zarządzane sekrety, service bindings i Destination.
- [ ] Restrykcyjny CORS, limity wejścia, bezpieczne błędy i autoryzacja API.
- [ ] Testy worker/outbox, idempotencji i konkurencji przy restartach oraz wielu instancjach CF; monitoring i audyt bez sekretów.
- [ ] Trwały magazyn XML/UPO, rozdział TEST/DEMO/PROD i procedura rekoncyliacji.

Podstawy trwałości, idempotencji i autoryzacji powstają przed wysyłką w Phase 2–4; ten etap sprawdza je w docelowej infrastrukturze. BTP Trial nie jest środowiskiem produkcyjnym. Batch, zakupy, korekty, offline i multi-NIP pozostają poza pierwszym vertical slice.
