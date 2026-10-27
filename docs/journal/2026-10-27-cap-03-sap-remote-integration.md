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

## Krok 2 — test LIVE i weryfikacja URL / Basic Auth

Kontekst (relacja użytkownika, niezależnie niezweryfikowana przez agenta): Communication Arrangement `ZCS_AINV_H01`, Destination BTP `AINV_H01`, użytkownik `AINV_CAP_USER`, bezpośredni HTTP GET → 200, 4 faktury.

**Wynik końcowy: LIVE PASS (patrz „LIVE” niżej).** Wersja z początku kroku: agent nie miał wtedy poświadczeń.

Zrobiono (lokalnie, bez mockowania `CqnService`):
- `SapInvoiceRemoteAdapterHttpContractTest` — Spring Boot + prawdziwy CAP RemoteService + Cloud SDK Destination (zarejestrowane programowo, sztuczne dane) wskazujące lokalny serwer HTTP. Zapisane żądania wykazały błędy, które test z mockiem ukrywał:
  1. URL: `…/0001/AinvoicerH01/ZC_AINV_INV_H` — nadmiarowy segment (pusty `http.service` ignorowany). Poprawka: `http.service` = pełna ścieżka bindingu; wynik `…/0001/ZC_AINV_INV_H`.
  2. `@requires: 'internal-user'` → „Not authenticated” przy odczycie poza żądaniem użytkownika. Poprawka: adapter wykonuje odczyt w `requestContext().systemUser()`; wywołujący odpowiada za autoryzację.
  3. Filtr dzieci generował `InvoiceUUID in (…)` (OData 4.01). Brak dowodu wsparcia w RAP V4 H01 → `eq … or`. Uwaga: przy dużej liczbie faktur URL urośnie; wtedy paginacja/`$expand`.
  4. `cds.remote.services` było w profilu `default`, więc w profilu `test` serwis był lokalny i zwracał pusto. Przeniesiono do dokumentu bez profilu.
  Po poprawkach: ścieżka poprawna, każde żądanie niesie `Authorization: Basic base64(user:pass)` (dane sztuczne).
- Zmienna `destinations`: sprawdzono na fałszywym hoście, że działają klucze `name`, `url`, `username`, `password` (opcjonalnie `authentication: BasicAuthentication`) i powstaje nagłówek Basic.
- `SapInvoiceRemoteAdapterLiveTest` — `@Tag("live-sap")`, `@EnabledIfEnvironmentVariable(AINV_H01_LIVE=true)`; asercje 4 nagłówki, 9 pozycji, 6 referencji, unikalne UUID, zgodność `InvoiceUUID` dzieci. Surefire domyślnie wyłącza grupę (`ainv.excludedGroups=live-sap`).

### Uruchomienie LIVE (PowerShell, hasło nie trafia do repo ani historii)

```powershell
$pw = Read-Host "AINV_CAP_USER password" -AsSecureString
$plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($pw))
$env:destinations = (@(@{ name='AINV_H01'; url='https://<HOST-H01-origin>'; username='AINV_CAP_USER'; password=$plain }) | ConvertTo-Json -Compress -AsArray)
$env:AINV_H01_LIVE = 'true'
cd cap-java
mvn -pl srv -am verify '-Dcds.cds.skipVersionCheck=true' '-Dainv.excludedGroups=none' '-Dtest=SapInvoiceRemoteAdapterLiveTest' '-Dsurefire.failIfNoSpecifiedTests=false'
Remove-Item Env:destinations, Env:AINV_H01_LIVE
```

`url` = sam origin (schemat + host), bez ścieżki usługi. Wymaga PowerShell 7 dla `-AsArray` (w 5.1 zapakuj jednoelementową tablicę ręcznie: `'[' + … + ']'`).

### Testy
`mvn clean verify -Dcds.cds.skipVersionCheck=true` — 23 testy PASS (19 OData, 1 H2, 1 mock adaptera, 1 HTTP kontrakt, 1 autoryzacja zdalnego modelu); test LIVE wyłączony. Nie było commitów ani pushy.

Ryzyka: nieznana obsługa `$filter … or` oraz `$select` przez RAP w H01 (na razie tylko test lokalny); `systemUser()` omija autoryzację CAP dla odczytu SAP.

### LIVE — wynik (19:38–19:4x, Europe/Warsaw)

- Próba 1: `BUILD FAILURE`, `ODataDeserializationException: Unable to read OData 4.0 response` przy HTTP 200. Surowa odpowiedź `curl` (Basic Auth) miała `content-type: text/html` i skrypt przekierowujący do `…authentication.eu20.hana.ondemand.com/oauth/authorize` – host `*.abap-web.eu20…` to wejście przeglądarkowe (IAS/XSUAA), nie przyjmuje Basic Auth.
- Poprawka: `H01_URL` = `https://<uuid>.abap.eu20.hana.ondemand.com`. `curl` zwrócił `application/json` z danymi; poprzednia uwaga w tym dzienniku sugerująca `abap-web` była błędna.
- Próba 2: `SapInvoiceRemoteAdapterLiveTest` – Tests run: 1, Failures: 0, Errors: 0; `BUILD SUCCESS`. Asercje: 4 nagłówki, 9 pozycji, 6 referencji, 4 unikalne UUID, `InvoiceUUID` dzieci = rodzic. To rzeczywiste wywołanie CAP RemoteService (bez mocka `CqnService`).
- Narzędzia: `cap-java/run-live-h01.ps1` (bez sekretów) czyta niewersjonowany `cap-java/.env.live` (`.env*` w `.gitignore`).
- Incydent: podczas diagnostyki błąd powłoki wypisał linię `.env.live` z hasłem `AINV_CAP_USER` do logu sesji agenta. Zalecana rotacja hasła i aktualizacja `.env.live`.

