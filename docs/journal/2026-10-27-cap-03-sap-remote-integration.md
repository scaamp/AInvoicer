---
title: "Dziennik — CAP-03 SAP H01 Remote Integration"
date: 2026-10-27
type: journal
status: w-trakcie
tags: [ainvoicer, cap-java, sap, odata, remote-service]
---

# CAP-03 — SAP H01 Remote Integration

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Model RAP faktur](../guides/ABAP_RAP_INVOICE_MODEL.md)

## Cel i granice

Zweryfikować aktywny kontrakt OData V4 `ZAPI_AINV_INV_H_O4`, wykonać autoryzowany odczyt H01, przygotować CAP Java Remote Service oraz adapter tylko do odczytu. Bez zmian RAP, sekretów, commitów i pushy. Kryterium końcowe wymaga odczytu 4 nagłówków, 9 pozycji i 6 referencji przez CAP oraz zachowania UUID.

## Stan początkowy

- Branch `java-cap-ksef`; repozytorium było czyste.
- CAP Java 5.2.0, Spring Boot 4.1.1, JDK 25.0.4.1, Maven 3.9.16.
- `cap-java/` zawierał lokalny model KSeF, OData V4/H2 i testy Spring. Nie było zdalnego CDS modelu, Remote Service ani adaptera SAP.
- Eksport ABAP w Git był starszy od zmian live. Nie edytowano SAP/RAP.

## Weryfikacja runtime H01 — live przez MCP

- Read-only `resolve_service` znalazł istniejący binding `ZAPI_AINV_INV_H_O4`, usługę `0001`, protokół V4, `published=true` i ścieżkę `/sap/opu/odata4/sap/zapi_ainv_inv_h_o4/srvd_a2x/sap/zapi_ainv_inv_h_o4/0001/`.
- Uwierzytelniony `get_metadata` zwrócił HTTP 200. Entity sets: `ZC_AINV_INV_H` (klucz `InvoiceUUID`), `ZC_AINV_INV_I` (`ItemUUID`) i `ZC_AINV_INV_REF` (`RefUUID`). Root zawiera nawigacje kolekcyjne `_Items` i `_References`; wszystkie klucze są `Edm.Guid`.
- Odczyt kolekcji root z `$expand=_Items,_References` zwrócił 4 nagłówki z 9 pozycjami i 6 referencjami, `hasMore=false`. Każdy child miał `InvoiceUUID` zgodny z rodzicem. UUID odpowiadają seedowi z 2026-10-26. To rzeczywisty autoryzowany odczyt H01 przez MCP i HTTP OData preview, nie odczyt wykonany przez proces CAP.
- Narzędzie metadanych zwraca znormalizowany model CSDL, nie surowe bajty EDMX. Nie zapisano fikcyjnego pliku EDMX ani nie nazwano tego importem EDMX.
- Podgląd korzystał z istniejącego same-origin ADT OAuth kontekstu. Nie potwierdzono pełnego hosta ani osobnego SAP BTP Destination/Communication Arrangement dla aplikacji CAP.

## Implementacja CAP

- Dodano `srv/ainv-h01.cds` jako model zewnętrznej usługi CAP, ze zweryfikowanymi nazwami entity sets, kluczami UUID i nawigacjami. Typy/rozmiary pól oparto na runtime scalar types i eksportowanych definicjach tabel; kompletne właściwości CSDL/EDMX wymagają surowego `$metadata`.
- Dodano wymagane runtime `cds-feature-remote-odata` oraz SAP Cloud SDK `scp-cf` dla BTP Destination Service. Parent POM importuje `sdk-bom` 5.33.0.
- Dodano konfigurację `cds.remote.services.AinvoicerH01`: OData V4, model `AinvoicerH01`, Destination o nazwie `AINV_H01` oraz ścieżkę usługi odczytaną z aktywnego bindingu. Sama nazwa nie tworzy Destination ani nie dostarcza credentiali.
- `SapInvoiceRemoteAdapter` wstrzykuje natywny CAP `CqnService`, wykonuje trzy selekty CQN na nagłówki, pozycje i referencje, ogranicza dzieci do pobranych UUID i grupuje dane po oryginalnym `InvoiceUUID`. Adapter nie zapisuje kopii w H2.
- `SapInvoiceSnapshot` zachowuje klucze z SAP; nie generuje CAP `cuid` dla źródłowych faktur. Kopia map zachowuje nullable pola OData, w tym `null` dla dat.
- Test adaptera podaje kontrolowane wyniki serwisu CQN. Sprawdza 4/9/6 oraz wartości UUID, ale nie jest live ani testem sieciowej Destination.
- Zewnętrzny model CAP jest ograniczony `@requires: 'internal-user'`; test HTTP potwierdza, że anonimowe żądanie `$metadata` dla `AinvoicerH01` dostaje 401. Zewnętrzny model nie ma być publicznym API aplikacji.

## Zmienione pliki

- `cap-java/srv/ainv-h01.cds` — model Remote Service z H01.
- `cap-java/srv/src/main/resources/application.yaml` — OData V4 Remote Service i Destination `AINV_H01`.
- `cap-java/pom.xml`, `cap-java/srv/pom.xml` — BOM SAP Cloud SDK i runtime Remote OData/BTP Destination dependencies.
- `cap-java/srv/src/main/java/customer/cap_java/sap/SapInvoiceRemoteAdapter.java` i `SapInvoiceSnapshot.java` — odczyt i wynik adaptera.
- `cap-java/srv/src/test/java/customer/cap_java/sap/SapInvoiceRemoteAdapterIntegrationTest.java` — test kontraktu adaptera na mocku CQN.
- `cap-java/srv/src/test/java/customer/cap_java/sap/AinvoicerH01RemoteServiceSecurityIntegrationTest.java` — test blokady anonimowego dostępu do zdalnego modelu.
- `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/README.md`, `docs/guides/ABAP_RAP_INVOICE_MODEL.md` — aktualizacja stanu i dowodów.

## Polecenia i wersje

- `git branch --show-current`, `git status --short`, inspekcja `cap-java/`, RAP CDS/bindingu i dokumentacji.
- `mvn clean verify` — FAIL przed testami: istniejący problem uruchamiania `cds version` zgłasza `Required @sap/cds-dk not installed`, mimo że dalszy raport wersji wykazuje lokalne `@sap/cds-dk 10.2.0`.
- `mvn clean verify '-Dcds.cds.skipVersionCheck=true' -q` — PASS na końcowym stanie, JDK 25.0.4.1 / Maven 3.9.16 / CAP Java 5.2.0. Raport Surefire: 22 testy, 0 błędów, 0 failures (19 OData + Spring Security + CAP + H2, 1 H2 datasource, 1 adapter mock, 1 autoryzacja zdalnego modelu).
- Build log npm zgłosił 2 podatności high w drzewie zależności; nie wykonywano `npm audit fix`.
- `git diff --check` — PASS.

## Wynik, ryzyko i następny krok

Kontrakt runtime oraz odczyt autoryzowany H01 przez MCP są potwierdzone live: 4/9/6 z UUID. CDS CAP, wiring Remote Service/Destination, mapowanie CQN i test mock są gotowe. KRYTERIUM CAP LIVE NIEZALICZONE: aplikacja nie wykonała CQN przez SAP BTP Destination do H01. Blokuje brak zweryfikowanego hosta i mechanizmu uwierzytelnienia/Communication Arrangement dla tej aplikacji. Destination `AINV_H01` wymaga konfiguracji w rzeczywistym środowisku CAP; następnie trzeba wykonać ten sam odczyt przez `SapInvoiceRemoteAdapter` i porównać 4/9/6 oraz UUID.

Analogicznie do RAP: zewnętrzny CDS service/entity opisuje kontrakt zbliżony rolą do definicji CDS konsumowanej zdalnie; `CqnService` jest klientem Remote Service i musi zostać jawnie wywołany przez adapter. Nie działa jak lokalny ABAP SQL ani nie uczestniczy automatycznie w lokalnej transakcji H2. CAP nie kopiuje danych do własnej persystencji w tym przepływie.
