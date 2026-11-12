---
title: "Dziennik — CAP-07E LIVE Identity & Security Acceptance"
date: 2026-11-12
type: journal
status: zakonczony
tags: [ainvoicer, journal, security, xsuaa, approuter, rap, hana, cap-07e]
---

# CAP-07E — LIVE Identity & Security Acceptance

[Przewodnik i macierz](../guides/CAP_07E_SECURITY_ACCEPTANCE.md) · [CAP-07D](../guides/CAP_07D_SECURITY_ACCEPTANCE.md) ·
[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md)

## Date

2026-11-12 (Europe/Warsaw), pomiary LIVE 19:32–19:38 CET (18:32–18:38 UTC).

## Branch

`feature/cap-07e-live-security-acceptance` w worktree `D:\AInvoicer-cap-07e`, z `origin/main=e468c76`.

## Goal

Niezależny audyt i akceptacja bezpieczeństwa CAP Java + AppRouter + RAP po CAP-HANA-01S: audyt kodu, weryfikacja
C07, powtórka anonimowych sond, macierz A/B, analiza principal H01, regresje offline i dokumentacja. Bez
deployu, IAM, SAP WRITE, KSeF SEND/INBOUND, BLOB-LAB-01 i bez zmian plików CAP-HANA-02.

## Initial State

Główny worktree na `main` z lokalnymi zmianami właściciela (`.env.example`, nieśledzony `cap-java/db/documents.cds`)
— nietknięte. CF CLI zalogowany (EU20/dev), MCP ABAP FS połączony z `h01`. STATUS opisywał CAP bez restartu od 11.11.

## Implementation

1. Audyt kodu security CAP (polityka tenantów, guardy, `@restrict`, handlery, provider zakresu), AppRoutera,
   RAP UI (SRVD/DCL) i narzędzi LIVE. Ustalono, że wdrożony wariant HANA readiness działa bez
   `IdentitySmokeReadOnlyHandler`, a jedyny test JWT HTTP obejmował tryb smoke.
2. Odczyt CF read-only (`cf-readiness.mjs`, `cf mta`, `cf mta-ops`, whitelisted env, audit event types):
   nowy droplet CAP/AppRouter z 18:12/18:14 UTC, wdrożenie zainicjowane przez użytkownika, MTA 0.7.3.
3. Anonimowe sondy LIVE: plan CAP-07D 26/26 PASS; rozszerzenie narzędzia; po korekcie klasyfikacji 38/38 PASS.
4. H01 read-only: lista trace runs, wyszukiwanie released CDS audytu, odczyt definicji `I_IAMBusinessUserLogonDetails`.
5. Nowy test `HanaReadinessJwtSecurityTest` dla rzeczywistego składu flag; charakteryzacja ryzyka CREATE.

## Changed Files

- `cap-java/srv/src/test/java/customer/cap_java/security/HanaReadinessJwtSecurityTest.java` — nowy (6 testów).
- `deploy/scripts/live-security.mjs` — +12 sond, klasyfikacja przekierowania 302/skryptowego bez zapisu URL.
- `deploy/test/live-security.test.mjs` — 3 nowe testy.
- `deploy/evidence/2026-11-12-cap-07e-public-read.json` — oczyszczony raport 38/38.
- `docs/guides/CAP_07E_SECURITY_ACCEPTANCE.md`, ten dziennik.

Bez zmian kodu produkcyjnego, `cap-java/db/`, MTA/extension, harnessu HANA, STATUS/ROADMAP/README.

## Commands Executed

```text
git worktree add -b feature/cap-07e-live-security-acceptance D:/AInvoicer-cap-07e origin/main
node deploy/scripts/live-security.mjs <CAP> <AppRouter> <scratchpad>.json      (3×)
AINV_CF_CLI=...cf.exe node deploy/scripts/cf-readiness.mjs <scratchpad>.json
cf mta ainvoicer; cf mta-ops; cf curl -X GET /v3/apps/<guid>/{environment_variables,processes/web/stats}; /v3/audit_events
MCP h01: analyze_traces list_runs; search_objects DDLS; get_object_source I_IAMBUSINESSUSERLOGONDETAILS
npm ci --prefix deploy; npm test --prefix deploy; node --test approuter/test/xs-app.test.mjs; node deploy/scripts/check-secrets.mjs
mvn -B -pl srv -am test [-Dtest=...]      (cap-java, Maven 3.9.16, JDK 25.0.4 Microsoft)
```

Z env CAP odczytano wyłącznie nazwy kluczy i wartości jawnych flag; bez VCAP_SERVICES/credentiali.

## Tests & Results

| Zakres | Wynik | Typ |
| --- | --- | --- |
| Anonimowe sondy, plan CAP-07D | 26/26 PASS (18:32:40Z) | LIVE |
| Rozszerzony plan, przed korektą | 36 PASS / 2 FAIL (nawigacja 200) | LIVE |
| Rozszerzony plan, po korekcie | **38/38 PASS** (18:38:16Z) | LIVE |
| Security Maven (4 klasy) | 50/50 PASS | OFFLINE |
| Pełny Maven `srv` | 476, 0 FAIL, 6 skipped (Azurite) | OFFLINE |
| `deploy` | 46: 45 PASS, 1 skipped | OFFLINE |
| AppRouter routing | 10/10 PASS | OFFLINE |
| Skan sekretów | PASS (heurystyka) | OFFLINE |
| A/B, principal H01, logout/expiry | BLOCKED / NOT EXECUTED | — |

CI dla brancha: wynik podany w PR (nie deklarowany tutaj przed uruchomieniem).

## Problems Encountered

- Założenie, że AppRouter odpowie 302 na anonimową nawigację, było błędne: zwraca 200 z małą stroną `no-store`
  przekierowującą skryptem do XSUAA `/oauth/authorize`. Zbadano cechy treści bez jej zapisywania; klasyfikator
  akceptuje wyłącznie tę postać, każdą inną 200 oznacza FAIL (testy negatywne).
- Wariant HANA readiness nie przechodzi `CloudPersistenceGuard` na H2 z profilem `cf` — test odtwarza ustawienia
  profilu jawnie, bez profilu chmurowego (ograniczenie opisane w Javadoc).
- Brak źródeł H01 korelujących żądanie z principalem — BLOCKED.

## Decisions Made

- Nie zmieniono zachowania `KsefOperations` CREATE ani `requestSend`: to decyzje API/KSeF dla właściciela (E-2, E-3);
  ryzyko utrwalono testem charakteryzacyjnym.
- CAP-07D pozostaje bez zmian jako dowód historyczny; nowa macierz w CAP-07E.

## Current State

Anonimowa powierzchnia CAP/AppRouter LIVE: PASS. C07 zamknięty offline w obu składach. C11 FAIL → PASS.
Otwarte: A/B, principal H01, logout/expiry, XSUAA/Destination, commit dropletu 18:12Z, E-2/E-3.

## Next Steps

1. Właściciel potwierdza źródło wdrożenia 18:12Z i treść `AINV_H01_UI_USER`.
2. Drugie konto bez ról → AB2/AB3 i test różnicowy principala w AppRouterze.
3. Decyzja o E-2/E-3 przed aktywacją KSeF; orkiestrator aktualizuje STATUS/ROADMAP/README.
