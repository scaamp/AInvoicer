---
title: "CAP-07F — KSeF API Security Hardening (E-2 / E-3)"
date: 2026-11-13
type: guide
status: offline-verified-live-pending
tags: [ainvoicer, security, cap-java, odata, ksef, requestSend, cap-07f]
---

# CAP-07F — utwardzenie API KSeF (E-2, E-3)

**Werdykt: E-2 i E-3 ZAMKNIĘTE OFFLINE (kod + testy na H2, mock users i produkcyjny stos JWT SAP z testowym
JWKS). LIVE NIEZWERYFIKOWANE — zmiana nie została wdrożona. Security acceptance pozostaje PENDING**
(otwarte pozycje CAP-07E: A/B, principal H01, logout/expiry, Destination, 2 nawigacje AppRoutera).

[CAP-07E — macierz i źródło E-2/E-3](CAP_07E_SECURITY_ACCEPTANCE.md) ·
[Dziennik CAP-07F](../journal/2026-11-13-cap-07f-security-hardening.md) ·
[ORCH-05 — opis E-2/E-3](../journal/2026-11-12-orch-05-hana-security-integration.md) ·
[ADR-0006](../decisions/ADR-0006-btp-identity-xsuaa.md)

## Zakres i granice

- Branch `feature/cap-07f-security-hardening` z `origin/main=4d4dbfc`, osobny worktree `D:\AInvoicer-cap-07f`.
- Zmienione wyłącznie: kontrakt CDS `KsefService.KsefOperations`, guard w `KsefSendRequestHandler`,
  metoda `KsefSubmissionWorker.transportEnabled()`, komentarz `KsefOperationsHandler`, testy i dokumentacja.
- **Bez zmian:** XSUAA/JWT, `DedicatedTenantPolicy`, `KsefScopeUserInfoProvider`, role/atrybuty, IAM/DCL,
  Destination, auth KSeF, retry/worker/outbox/API budget, INBOUND, `cap-java/db/` (w tym `documents.cds`),
  BLOB-LAB-01, runner R2, MTA, `deploy/`, SAP H01, `STATUS`/`ROADMAP`/`README`.
- Bez operacji LIVE: żadnego deployu, KSeF TEST, SAP H01, HANA, CF ani workerów.

## E-2 — publiczny CREATE `KsefOperations`

### Przyczyna

Projekcja `KsefService.KsefOperations` miała w `@restrict` grant `CREATE` dla `KsefOperator` (z warunkiem
NIP/CompanyCode). `KsefOperationsHandler` odrzucał `SEND_INVOICE`, obcy zakres i błędne dane (400), ale
własne `CHECK_STATUS`/`RECEIVE_INVOICE` zapisywał (201, `status=NEW`, `environment=TEST`). Żaden worker nie
przetwarza takich wierszy — trwały szum w danych operacyjnych/audycie, zbędna powierzchnia zapisu.

### Weryfikacja legalnych ścieżek zapisu (przed zmianą)

| Ścieżka | Jak tworzy operację | Wpływ zmiany |
| --- | --- | --- |
| `requestSend` (`KsefSendRequestHandler`) | `PersistenceService` → encja bazowa `customer.cap_java.KsefOperations` | brak — `@restrict`/`@readonly` projekcji nie dotyczą encji bazowej |
| Worker, writeback, sweeper | `PersistenceService` na encjach bazowych (UPDATE/SELECT) | brak |
| SAPUI5 `KsefDataAdapter` | tylko READ `KsefOperations`/`InvoiceIntegrationStatus` + POST `requestSend` | brak — nie ma POST do kolekcji |
| Node/Python, `deploy/` | brak klienta POST `KsefOperations` (tylko sondy oczekujące 401/403) | brak |
| Kod `cds.gen.ksefservice.KsefOperations` na serwisie aplikacyjnym | tylko `KsefOperationsHandler` (walidacja) | handler staje się drugą barierą |

Wniosek: nie istnieje legalna ścieżka biznesowa korzystająca z publicznego POST — brak blokera.

### Zmiana kontraktu (deklaratywna, bez nowego handlera)

```cds
@(restrict: [
  { grant: 'READ', to: 'KsefOperator', where: (taxpayerNip = $user.taxpayerNips and companyCode = $user.companyCodes) }
])
@readonly
entity KsefOperations as projection on db.KsefOperations excluding { invoice, snapshot, confirmedXmlSha256, submission, writeback };
```

`KsefOperationsHandler` pozostaje bez zmian logiki jako defense-in-depth (gdyby grant wrócił omyłkowo).

### Before / after (OFFLINE, H2)

| Żądanie | Przed (`main=4d4dbfc`) | Po (CAP-07F) |
| --- | --- | --- |
| Operator POST własne `CHECK_STATUS`/`RECEIVE_INVOICE` | **201**, nowy wiersz | **403** `403001`, bez wiersza |
| Operator POST obcy NIP / `SEND_INVOICE` / błędny payload | 400 (handler) | 403 (kontrakt CDS, przed handlerem) |
| Operator POST ze sfałszowanym `status`/`environment` | 201, serwer nadpisuje | 403 |
| `$batch` z CREATE w changesecie | nie testowano (ta sama ścieżka autoryzacji/handlera co POST, więc oczekiwanie 201) | batch 200, wewnętrzne **403**, bez wiersza |
| Brak roli `KsefOperator` | 403 | 403 |
| Anonim (mock users / Basic) | 401 | 401 |
| Anonim bez tokenu, skład JWT HANA readiness | 401/403 (Spring Security) | 401/403 — bez zmian, nigdy zapis |
| PATCH/DELETE | 403 | 403 |
| READ lista / `$count` / własny klucz / obcy klucz | 200 / zakres / 200 / 404 | bez zmian |
| `$metadata` (wygenerowany EDMX) | brak adnotacji ograniczeń dla `KsefOperations` | `Capabilities.Insert/Update/DeleteRestrictions = false` (jak `KsefInvoices`) |
| Tryb identity-smoke: POST `KsefOperations` | 409 `AINV_IDENTITY_SMOKE_READ_ONLY` | 403 (wcześniejsza bariera CDS) |
| Wewnętrzny INSERT przez `PersistenceService` | działa | działa (requestSend ON → 200, 1 operacja) |
| UNIQUE `(taxpayerNip, idempotencyKey)` | egzekwowane (przez OData 409) | egzekwowane w bazie (test na `PersistenceService`) |

## E-3 — `requestSend` przed SAP READ

### Przyczyna

Kolejność w `KsefSendRequestHandler.onRequestSend`: rola (CAP) → `parseUuid` → **`sap.readInvoice`
(communication user Destination `AINV_H01`)** → 404 → kontrola NIP/CompanyCode (403) → reguły faktury →
`worker.acceptsContext(nip)` (409). Guard transportu wymaga NIP z faktury, więc stał po odczycie SAP. Przy
KSeF OFF operator wywoływał zbędny techniczny odczyt H01 i mógł odróżnić brakujący UUID (404) od obcego (403).

### Wybór guardu

`acceptsContext(nip)` = `enabled && !configuredNip.isBlank() && configuredNip.equals(nip)` — wymaga NIP z SAP.
Niezależna od NIP część to istniejąca flaga `ainv.ksef.enabled` + skonfigurowany `ainv.ksef.nip`. Wydzielono ją
do `KsefSubmissionWorker.transportEnabled()` (bez nowej architektury feature flags); `acceptsContext` korzysta
z niej, więc semantyka ON jest identyczna.

### Nowa kolejność

1. Uwierzytelnienie + `@requires: 'KsefOperator'` (CAP, bez zmian) → 401/403.
2. Format UUID (`parseUuid`, deserializacja OData) → 400 `KSEF_INVOICE_UUID_INVALID`.
3. **`worker.transportEnabled()` = false → 409 `KSEF_CONTEXT_NOT_CONFIGURED`** — przed SAP, DB i rezerwacją.
4. ON: `sap.readInvoice` → 404 / 502 jak dotąd.
5. NIP/CompanyCode → 403; kierunek, `KsefNumber`, `acceptsContext(nip)` → 422/409.
6. FA(3), rezerwacja/CAS, operacja, migawka, outbox — bez zmian.

Kod 409 `KSEF_CONTEXT_NOT_CONFIGURED` to ten sam kod, który dotąd dostawał operator dla własnej faktury przy
KSeF OFF, i ten oczekiwany w [runbooku](BTP_FIRST_DEPLOYMENT_RUNBOOK.md) (krok 10). Nie ujawnia, czy transport
jest wyłączony, czy skonfigurowany dla innego NIP.

### Before / after przy KSeF OFF (OFFLINE)

| Przypadek | Przed | Po |
| --- | --- | --- |
| Własna istniejąca faktura | SAP READ → 409 | **409, 0 wywołań SAP** |
| Obcy NIP / obca spółka | SAP READ → 403 | **409 identyczne**, 0 wywołań SAP |
| Nieistniejący UUID | SAP READ → 404 | **409 identyczne**, 0 wywołań SAP |
| Błędny UUID | 400 | 400, 0 wywołań SAP |
| Brak roli / anonim | 403 / 401 | 403 / 401, 0 wywołań SAP |
| Zapisy (operacje, rezerwacje, migawki, zlecenia, `cds.outbox.Messages`) | 0 | 0 |

KSeF ON: bez zmian — obcy NIP 403 `KSEF_TAXPAYER_NOT_AUTHORIZED`, brak 404 `KSEF_INVOICE_NOT_FOUND`,
własna 200 `QUEUED`, dokładnie 1 odczyt SAP na żądanie; idempotencja, CAS i rezerwacje — pełny
`KsefSendRequestTest` PASS.

## Testy

| Klasa | Zmiana | Dowód |
| --- | --- | --- |
| `KsefServiceODataIntegrationTest` | 12 przypadków CREATE (w tym wszystkie dawne walidacje) → 403 + liczba wierszy bez zmian; 401/403 dla CREATE; `$batch` CREATE; READ lista/`@count`/własny 200/obcy 404; PATCH/DELETE 403; UNIQUE w bazie | mock users, H2 |
| `HanaReadinessJwtSecurityTest` | test charakteryzacyjny `…KnownRisk` (201) zastąpiony `rawOperationCreateIsRejectedForEveryScopeTypeAndBatch`; requestSend OFF → dokładnie 409 + `verifyNoInteractions(sap)` | skład flag wdrożonego wariantu, JWT |
| `KsefSendTransportDisabledTest` (nowy) | OFF: własna/obca NIP/obca spółka/brakująca → identyczna odpowiedź; malformed 400; 401/403; 0 SAP, 0 KSeF auth/session, 0 zapisów w 5 tabelach | mock users, atrapa `SapInvoiceRemoteAdapter` |
| `KsefSendRequestTest` | raw CREATE `SEND_INVOICE` → 403; nowy test ON: 403/404/200 z liczbą odczytów SAP 1/1/1 | ON, atrapy SAP/KSeF |
| `XsuaaJwtODataContractTest` | smoke: POST `KsefOperations` 409 → 403 (bariera CDS przed handlerem smoke) | JWT, tryb smoke |

Wyniki: patrz [dziennik](../journal/2026-11-13-cap-07f-security-hardening.md#tests--results).

## Co potwierdzono offline, a czego nie

- **OFFLINE VERIFIED:** blokada publicznego CREATE (POST i `$batch`) dla każdego zakresu i typu; READ bez
  regresji; requestSend OFF bez SAP READ i bez zapisów; requestSend ON bez regresji (403/404/200, rezerwacje,
  CAS, idempotencja, ponowienia); wewnętrzny zapis operacji działa.
- **NIEZWERYFIKOWANE LIVE:** zachowanie na CF/HANA (dropletu z tą zmianą nie ma), brak wywołań H01 w
  rzeczywistej Destination, LIVE POST/`$batch`, odczyt LIVE istniejących wierszy, wpływ na AppRouter (CSRF).
- Nie zweryfikowano, czy w HANA LIVE istnieją już wiersze utworzone surowym POST (CAP-07E nie wykonywał LIVE POST).

## Ryzyka i kompatybilność

1. **Klienci API:** jedyny znany konsument (SAPUI5) używa READ i `requestSend`. Zewnętrzny klient korzystający
   z POST `KsefOperations` dostanie 403 — świadoma zmiana kontraktu; nie znaleziono takiego klienta w repo.
2. **`$metadata`:** nowe adnotacje `Capabilities.*Restrictions` (read-only). Odbiorcy READ nie są dotknięci;
   generyczne UI (np. Fiori elements) przestanie oferować tworzenie — zgodnie z celem.
3. **Kody błędów:** CREATE 400 → 403; requestSend przy OFF zawsze 409 (wcześniej 404/403/502/409 zależnie od
   faktury i SAP). Przy ON bez zmian.
4. **Rezydualne (ON, zgodnie z wymaganiem):** przy włączonym transporcie operator nadal odróżnia 404 od 403 dla
   znanego UUID — to zachowany kontrakt ON; ochronę stanowią losowe UUID i rola `KsefOperator`.
5. **Rezydualne (anonim JWT):** bezpośredni anonimowy POST do CAP w składzie JWT daje 401 albo 403 (filtr Spring
   Security) — zachowanie istniejące, niezmieniane w tym zadaniu; w obu przypadkach brak dostępu do CAP.
6. **Obecny deployment:** do czasu wdrożenia LIVE nadal ma grant CREATE i starą kolejność requestSend.
   Flagi KSeF/INBOUND/SAP_WRITE są OFF, więc E-3 dotyczy tylko technicznego READ H01, a E-2 — szumu w HANA.

## Plan bezpiecznego wdrożenia (osobny etap, wymaga zgody właściciela)

1. Merge PR po review i zielonym CI; build MTAR z dokładnego SHA merge, zapis SHA artefaktu.
2. Przed deployem: read-only `SELECT COUNT(*)` operacji z `operationType in (CHECK_STATUS, RECEIVE_INVOICE)`
   (ewentualne wiersze z surowego POST) — decyzja właściciela o ich losie; bez DML w tym etapie.
3. Deploy w oknie uzgodnionym z właścicielem; flagi efektów zewnętrznych pozostają OFF.
4. LIVE (sesja operatora A, przez AppRouter): GET `KsefOperations` 200 w zakresie; POST `KsefOperations` → 403;
   `$batch` CREATE → wewnętrzne 403; `requestSend` (syntetyczny UUID) → 409 `KSEF_CONTEXT_NOT_CONFIGURED`;
   w logach CAP brak wpisu odczytu SAP/Destination dla tego żądania; liczba operacji bez zmian.
5. Zapisać wyniki jako VERIFIED LIVE w CAP-07E/CAP-07F; dopiero wtedy aktualizować macierz security acceptance.

## Polecenia

```powershell
cd cap-java
mvn -B -pl srv -am test "-Dtest=KsefServiceODataIntegrationTest,HanaReadinessJwtSecurityTest,XsuaaJwtODataContractTest,KsefSendRequestTest,KsefSendTransportDisabledTest"
mvn -B clean verify
```
