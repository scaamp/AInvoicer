---
title: "AInvoicer — plan rozwoju"
date: 2026-10-24
type: roadmap
status: aktywny
tags: [ainvoicer, roadmap, cap-java, ksef]
---

# Plan rozwoju

[Project Home](README.md) · [Aktualny stan i dowody](STATUS.md) · [Decyzja architektoniczna](decisions/ADR-0001-cap-java-ksef-architecture.md)

`[x]` oznacza ukończenie z podanym zakresem potwierdzenia, `[ ]` — pracę do wykonania. IN PROGRESS oznacza zadanie rozpoczęte. Ten plik jest planem; historię opisują [dziennik bootstrapu](journal/2026-10-23-cap-java-bootstrap.md) i [weryfikacja Phase 1](journal/2026-10-24-cap-java-bootstrap-verification.md).

## Narzędzia projektowe — Agent Skills

- [x] Trzy kanoniczne Skills ABAP/RAP, CAP Java i KSeF w `.agents/skills/`, odwołania Claude w `.claude/skills/` oraz sekcje `Project Skills` w instrukcjach agentów.
- [x] Walidacja statyczna i ręczna ocena czterech scenariuszy; Codex `skills/list` wykrywa trzy aktywne Skills w zakresie repo bez błędów ładowania.
- [ ] Potwierdzenie automatycznego doboru przez model oraz wykrywania/odczytu kanonicznych instrukcji w sesji Claude Code. Wersje CLI i poprawne pliki nie zastępują tego testu.

Dowody i sposób wywołania: [dziennik Skills](journal/2026-10-23-project-skills.md). Utworzenie Skills nie kończy żadnego etapu implementacji CAP/SAP/KSeF.

## Phase 1 — CAP Java Bootstrap — COMPLETE

- [x] Wygenerowanie projektu CAP Java w `cap-java/` (pliki zweryfikowane; polecenie inicjalizacji z relacji użytkownika).
- [x] Powtarzalny `mvn clean verify`: dwa uruchomienia na JDK 25.0.4.1 i Maven 3.9.16; CDS, kompilacja, zasoby i oba JAR-y zbudowane.
- [x] Poprzedni `NoSuchFileException` sprawdzony: nie odtworzono; `application.yaml` skopiowano do `target/classes` przed pakowaniem. Przyczyna dawnego incydentu pozostaje nieustalona.
- [x] Uruchomienie Spring Boot/CAP Runtime z executable JAR; HTTP Index odpowiada pod `http://localhost:8080/`.
- [x] Test integracyjny H2 DataSource: `SELECT 1` PASS.

Własne API CDS/OData i CRUD nie wchodziły w Phase 1; ich sprawdzenie należy do Phase 2.

## Phase 2 — CAP Domain Model

- [x] Minimalna encja CDS `KsefOperations`: korelacja SAP jako tekst, NIP, kierunek, typ, status NEW, klucz idempotencji, referencje i błąd.
- [ ] Encja CDS `KsefInvoices` i dalsze pola procesu: status techniczny i biznesowy, referencje oraz audyt.
- [x] `KsefService` wystawia `KsefOperations` przez OData V4, wymaga uwierzytelnionego użytkownika i blokuje zapis statusów/referencji technicznych po stronie konsumenta.
- [ ] Minimalne Java Event Handlers i walidacja wejścia po stronie serwera.
- [x] Lokalna weryfikacja `$metadata`, podstawowego CRUD i persystencji H2; walidacja domenowa i autoryzacja podatnika pozostają do wykonania.
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
