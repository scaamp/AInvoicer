---
title: "Dziennik — CAP-07F KSeF API Security Hardening (E-2 / E-3)"
date: 2026-11-13
type: journal
status: zakonczony
tags: [ainvoicer, journal, security, cap-java, odata, ksef, cap-07f]
---

# CAP-07F — KSeF API Security Hardening (E-2 / E-3)

[Przewodnik CAP-07F](../guides/CAP_07F_SECURITY_HARDENING.md) · [CAP-07E](../guides/CAP_07E_SECURITY_ACCEPTANCE.md) ·
[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md)

## Date

2026-11-13 (Europe/Warsaw).

## Branch

`feature/cap-07f-security-hardening` w worktree `D:\AInvoicer-cap-07f`, z `origin/main=4d4dbfca48f654f74cef787e6a680f8a79607512`
(HEAD potwierdzony po `git fetch`).

## Goal

Zamknąć offline dwa ustalenia CAP-07E: E-2 (publiczny CREATE `KsefOperations`) i E-3 (`requestSend` wykonuje
SAP READ przed guardem wyłączonego transportu KSeF), bez zmiany XSUAA/JWT, `DedicatedTenantPolicy`, ról, IAM,
Destination, auth KSeF, workerów i BLOB-LAB-01. Bez operacji LIVE. Kryteria: CREATE/`$batch` odrzucone bez
zapisu, READ bez regresji, OFF → 0 wywołań SAP i 0 zapisów, ON bez regresji, idempotencja zachowana.

## Initial State

- `main=4d4dbfc`, główny worktree czysty; nowy worktree z `origin/main`. Worktree Codexa i inne nietknięte.
- Inspekcja: `ksef-service.cds` (grant CREATE+READ na `KsefOperations`), `KsefOperationsHandler` (walidacja CREATE),
  `KsefSendRequestHandler` (UUID → SAP READ → 404/403 → `acceptsContext`), `KsefSubmissionWorker.acceptsContext`,
  `IdentitySmokeReadOnlyHandler`, `KsefScopeUserInfoProvider`, `DedicatedTenantPolicy`, testy JWT/OData/requestSend.
- Konsumenci: SAPUI5 `KsefDataAdapter` — tylko READ i POST `requestSend`; brak POST do kolekcji w UI5/Node/Python/deploy.
- Wewnętrzny zapis operacji wyłącznie przez `PersistenceService` na `customer.cap_java.KsefOperations`.

## Implementation

1. **E-2:** usunięto grant `CREATE`, dodano `@readonly` na projekcji `KsefService.KsefOperations` (wyłącznie CDS).
   `KsefOperationsHandler` pozostawiony jako druga bariera (zaktualizowany komentarz).
2. **E-3:** `KsefSubmissionWorker.transportEnabled()` (wydzielona część `acceptsContext` niezależna od NIP);
   `KsefSendRequestHandler` po `parseUuid` i przed `sap.readInvoice` rzuca 409 `KSEF_CONTEXT_NOT_CONFIGURED`
   (dotychczasowy kod dla OFF, oczekiwany w runbooku).
3. Testy: przepisano testy CREATE na odmowę z kontrolą liczby wierszy (dawne przypadki walidacji zachowane jako
   przypadki odmowy), `$batch`, READ lista/count/klucz, nowy `KsefSendTransportDisabledTest`, liczniki odczytów
   SAP dla ON, test charakteryzacyjny CAP-07E-R1 zastąpiony testem blokady.
4. Dokumentacja: przewodnik CAP-07F, ten dziennik, odsyłacz w CAP-07E.

## Changed Files

- `cap-java/srv/ksef-service.cds` — kontrakt read-only `KsefOperations`.
- `cap-java/srv/src/main/java/customer/cap_java/ksef/KsefSendRequestHandler.java` — guard transportu przed SAP READ.
- `cap-java/srv/src/main/java/customer/cap_java/ksef/KsefSubmissionWorker.java` — `transportEnabled()`.
- `cap-java/srv/src/main/java/customer/cap_java/KsefOperationsHandler.java` — tylko Javadoc (defense-in-depth).
- `cap-java/srv/src/test/java/customer/cap_java/KsefServiceODataIntegrationTest.java` — testy E-2 + READ.
- `cap-java/srv/src/test/java/customer/cap_java/security/HanaReadinessJwtSecurityTest.java` — E-2 w składzie JWT HANA readiness, E-3 z atrapą SAP.
- `cap-java/srv/src/test/java/customer/cap_java/security/XsuaaJwtODataContractTest.java` — smoke: POST 409 → 403.
- `cap-java/srv/src/test/java/customer/cap_java/ksef/KsefSendRequestTest.java` — raw CREATE 403, nowy test ON z licznikami SAP.
- `cap-java/srv/src/test/java/customer/cap_java/ksef/KsefSendTransportDisabledTest.java` — nowy (E-3 OFF).
- `docs/guides/CAP_07F_SECURITY_HARDENING.md` (nowy), `docs/guides/CAP_07E_SECURITY_ACCEPTANCE.md` (odsyłacz), ten dziennik.

Bez zmian: `cap-java/db/`, `documents.cds`, `deploy/`, MTA, runner R2, `STATUS`/`ROADMAP`/`README`.

## Commands Executed

```text
git fetch origin; git worktree add -b feature/cap-07f-security-hardening ../AInvoicer-cap-07f origin/main
mvn -B -q -pl srv -am test -Dtest=<klasy security/requestSend>      (cap-java, kilka iteracji)
mvn -B clean verify                                                  (cap-java)
git diff --check; git diff --cached --check
npm ci --prefix deploy; node deploy/scripts/check-secrets.mjs
npm test --prefix deploy; npm run test:cf-runtime --prefix deploy
```

Maven 3.9.16, OpenJDK 25.0.4.1, CAP Java 5.2.0.

## Tests & Results

| Zakres | Wynik | Typ |
| --- | --- | --- |
| Pierwszy przebieg starych testów na nowym kontrakcie | 12 FAIL w `KsefServiceODataIntegrationTest`, 1 w `HanaReadiness…KnownRisk`, 1 smoke — wszystkie przez 201/400/409 → 403 (dowód, że zmiana działa) | OFFLINE |
| 10 klas security/requestSend (`KsefServiceODataIntegrationTest` 25, `HanaReadinessJwtSecurityTest` 6, `XsuaaJwtODataContractTest` 37, `KsefSendRequestTest` 22, `KsefSendTransportDisabledTest` 3, `KsefSendDefaultPolicyTest` 5, `KsefScopePairODataTest` 7, `InvoiceIntegrationStatusODataTest` 8, `DedicatedTenantPolicyTest` 3, `KsefScopeUserInfoProviderTest` 4) | **120/120 PASS** | OFFLINE (H2, mock users, JWT z testowym JWKS) |
| Pełny `mvn -B clean verify` (`cap-java`) | **BUILD SUCCESS — 486 testów, 0 FAIL, 0 ERROR, 6 skipped** (Azurite, jak na `main`: 476) | OFFLINE |
| `git diff --check` | PASS | OFFLINE |
| Skan sekretów `check-secrets.mjs` (po `git add`, 2674 pliki) | PASS (heurystyka) | OFFLINE |
| `npm test --prefix deploy` | 52: 51 PASS, 1 skipped, 0 FAIL | OFFLINE |
| `npm run test:cf-runtime --prefix deploy` (spakowany JAR z tego `verify`, anonimowy POST `KsefOperations`/`requestSend` → 401/403) | 10/10 PASS | OFFLINE |
| UI5 | NIE WYKONANO — brak zmian w `sapui5/`/`approuter/` | — |
| KSeF TEST, SAP H01, HANA, CF | NIE WYKONANO (zakaz LIVE) | — |
| CI GitHub | wynik w PR | — |

## Problems Encountered

- Pierwszy przebieg po zmianie CDS pokazał spójne 403 `403001` dla każdego publicznego CREATE (autoryzacja CAP
  przed handlerem) — oczekiwane statusy w testach zaktualizowano świadomie, bez usuwania przypadków.
- Bez nagłówka `OData-MaxVersion` CAP zwraca `@count` zamiast `@odata.count` — poprawiono odczyt w teście.
- Anonimowy POST w składzie JWT daje 403 (Spring Security), w mock users 401 — istniejące zachowanie, nie
  zmieniane (zakaz zmian security config); test akceptuje 401/403 jak `XsuaaJwtODataContractTest`.
- `XsuaaJwtODataContractTest.expiredTokenIsRejected` w jednym przebiegu trwał ~500 s (PASS), wcześniej cała klasa
  53 s; przypadek dotyczy GET z wygasłym tokenem, kod niezmieniany. W pełnym `clean verify` cała klasa trwała
  36 s — potwierdza charakter środowiskowy (harness WireMock/JWKS), nie regresję.

## Decisions Made

- E-2 deklaratywnie w CDS (`@readonly` + tylko READ); bez nowego handlera. Handler walidacji zostaje jako druga bariera.
- E-3 przez istniejącą flagę `ainv.ksef.enabled` + skonfigurowany NIP; bez nowej architektury feature flags.
  Kod błędu bez zmian (409 `KSEF_CONTEXT_NOT_CONFIGURED`), więc UI/runbook nie wymagają zmian.
- Semantyka ON (403/404 po SAP READ) zachowana zgodnie z wymaganiem; rezydualne rozróżnienie 404/403 przy ON opisane.

## Current State

E-2 i E-3 zamknięte **offline**. LIVE niezweryfikowane (brak deployu). Security acceptance nadal PENDING
(A/B, principal H01, logout/expiry, Destination, ponowienie 2 nawigacji). STATUS/ROADMAP/README zaktualizuje
orkiestrator.

## Next Steps

1. Review PR i CI; merge wyłącznie po decyzji właściciela.
2. Osobny etap wdrożenia według planu w [przewodniku](../guides/CAP_07F_SECURITY_HARDENING.md#plan-bezpiecznego-wdrożenia-osobny-etap-wymaga-zgody-właściciela),
   w tym read-only sprawdzenie istniejących wierszy z surowego POST i LIVE sondy 403/409.
3. Orkiestrator: wpis CAP-07F w STATUS/ROADMAP/README.
