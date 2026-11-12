---
title: "CAP-07E — LIVE Identity & Security Acceptance (audyt po CAP-HANA-01S)"
date: 2026-11-12
type: guide
status: security-acceptance-pending
tags: [ainvoicer, security, xsuaa, cloud-foundry, approuter, rap, iam, hana, cap-07e]
---

# CAP-07E — akceptacja tożsamości i bezpieczeństwa

**Werdykt: SECURITY ACCEPTANCE PENDING — brak blokera w anonimowej powierzchni i w kodzie autoryzacji CAP,
ale A/B, principal H01, logout/expiry i pełna konfiguracja XSUAA/Destination pozostają BLOCKED.**
Działające SSO i 38/38 anonimowych sond nie są akceptacją bezpieczeństwa ani zgodą na aktywację KSeF.

[CAP-07D (historyczny, niezmieniony)](CAP_07D_SECURITY_ACCEPTANCE.md) ·
[As-built SSO](CAP_07B_2_BTP_IDENTITY_SSO_LIVE.md) · [RAP IAM](RAP_UI_IAM_MODEL.md) ·
[INT-03 audyt](../audits/2026-11-11-int-03-security-persistence.md) · [ADR-0006](../decisions/ADR-0006-btp-identity-xsuaa.md) ·
[Dziennik CAP-07E](../journal/2026-11-12-cap-07e-security-acceptance.md) ·
[Dowód HTTP](../../deploy/evidence/2026-11-12-cap-07e-public-read.json)

## Legenda dowodów

| Etykieta | Znaczenie |
| --- | --- |
| **VERIFIED LIVE** | Własny pomiar tej sesji na działającym CF/H01 (12.11.2026, 18:32–18:38 UTC) |
| **VERIFIED OFFLINE** | Test lokalny (produkcyjny stos JWT SAP z testowym kluczem/JWKS, H2) albo inspekcja kodu |
| **OWNER-REPORTED** | Relacja właściciela lub as-built z wcześniejszych sesji, nieponowiona teraz |
| **BLOCKED** | Wymaga kont, ról, danych lub uprawnień niedostępnych w tej sesji |
| **NOT EXECUTED** | Możliwe, ale świadomie niewykonane (np. kończenie sesji właściciela) |

## Zakres, proweniencja i granice

- Worktree `D:\AInvoicer-cap-07e`, branch `feature/cap-07e-live-security-acceptance`, baza
  `origin/main=e468c76786b862f2b91f9db80dd4c10decca5f47`. Główny worktree, `documents.cds`, `.env.example`
  właściciela, BLOB-LAB-01, `cap-java/db/`, MTA/extension, harness HANA i budżet INBOUND — **niezmieniane**.
- Wspólnych `STATUS`/`ROADMAP`/`README` nie edytowano (zgodnie ze zleceniem; indeks uzupełni orkiestrator).
- Nie było: logowania jako użytkownik, pobierania tokenów/cookies, deployu/restartu, zmian IAM/ról/użytkowników,
  SAP WRITE, KSeF SEND/INBOUND, nowych obiektów ABAP ani traces.
- Odczyty CF (CAPI GET) zapisały się w audycie CF jako `audit.app.environment(_variables).show` — to skutek
  read-only odczytu, nie zmiana.

### Stan runtime w chwili pomiaru (VERIFIED LIVE, CAPI read-only)

| Element | Wynik |
| --- | --- |
| MTA | `ainvoicer` **0.7.3**, brak aktywnych operacji MTA |
| `ainvoicer-srv` | STARTED 1/1, **nowy droplet 2026-11-12 18:12:47Z** (`678b0142…`), cflinuxfs4, `sap_java_buildpack_jakarta` 2.77.0; bindingi `ainvoicer-auth`, `ainvoicer-destination`, `ainvoicer-db` |
| `ainvoicer-approuter` | STARTED 1/1, **nowy droplet 18:14:39Z** (`8cf754d7…`), nodejs 1.9.6; destinations: `ainvoicer-cap` (forwardAuthToken=true), `ui5` (false); `SESSION_TIMEOUT=15` |
| Flagi CAP (whitelist) | `SPRING_PROFILES_ACTIVE=cf`, `AINV_DEPLOYMENT_HANA_READINESS=true`, `AINV_DEPLOYMENT_IDENTITY_SMOKE=false`, KSeF/INBOUND/SAP_WRITE/SWEEPER=`false`, storage=`off`, `SPRING_SQL_INIT_MODE=never` |
| XSUAA binding | dokładnie jeden na aplikację, `dedicated`, oczekiwana rodzina aplikacji, wspólna strefa |

**Ważne:** audyt CF pokazuje wdrożenie zainicjowane przez użytkownika 18:12 UTC (upload pakietu → staging → start),
po czasie opisanym w STATUS (CAP bez restartu od 11.11). **Commit źródłowy tego dropletu jest dla tej sesji
nieznany** — wyniki LIVE dotyczą działającego artefaktu, a wyniki OFFLINE commita `e468c76`. Nie wolno ich
automatycznie łączyć w twierdzenie „kod e468c76 = runtime”.

Obserwacja (INFO, dla CAP-HANA-02): env CAP zawiera `SPRING_SQL_INIT_PLATFORM=h2`, choć generator
`hana-readiness.mjs` usuwa tę zmienną. Przy `SPRING_SQL_INIT_MODE=never` jest ona bez skutku, ale sygnalizuje
różnicę między wdrożonym deskryptorem a generatorem. Bez wpływu na bezpieczeństwo; do wyjaśnienia przez właściciela.

## Faza A — audyt aktualnego kodu (VERIFIED OFFLINE, commit e468c76)

| # | Obszar | Ustalenie |
| --- | --- | --- |
| 1 | XSUAA JWT | Oficjalny stos `cds-feature-identity` + `java-security`; podpis z JWKS `uaadomain` bindingu, `jku` ignorowane; czas; brak własnej kryptografii. `iss` nie jest walidowany dla XSUAA (kontrakt biblioteki, utrwalony testem) |
| 2 | Audience / tenant / `zid` | Audience = clientid/xsappname bindingu (obce → 401). `DedicatedTenantPolicy` dekoruje każdy bean `JwtDecoder` **po** walidacji SAP: wymaga `zid` = tenant bindingu, a `app_tid`/`zone_uuid`, jeśli obecne, muszą być zgodne; pusty/tablicowy claim → 401 |
| 3 | Dedicated tenant | Binding musi mieć spójny `tenantid`/`identityzoneid`/`zone_uuid`; `tenantmode≠dedicated` → błąd startu. `CloudIdentityGuard`: mock OFF, auth ON, dokładnie jeden XSUAA — inaczej brak startu |
| 4 | `KsefOperator` | Wszystkie dane i `requestSend` wymagają roli; service root/`$metadata` = `authenticated-user` (schemat widoczny bez roli — świadoma decyzja CAP) |
| 5 | NIP/CompanyCode | `@restrict where taxpayerNip=$user.taxpayerNips and companyCode=$user.companyCodes`; `KsefScopeUserInfoProvider` zeruje oba atrybuty przy ≥2×≥2 (fail closed). Inbox: dokładnie 1 NIP + 1 spółka + grant BUYER/TEST |
| 6 | Filtrowanie list | Warunek CAP dołączany do każdego READ (lista, count, key, `$batch`); OR klienta nie rozszerza zakresu |
| 7 | count/key/expand | Obcy key → 404, `$count` z obcym filtrem → 0; `$expand=invoice` → 400 (nawigacje prywatne wykluczone z projekcji); Inbox blokuje `$expand/$apply/$search/$compute` |
| 8 | Encje techniczne | `KsefSubmissions`, `SapResultWrites`, `KsefSendReservations`, snapshoty, inbound staging/granty — poza modelem OData (404); `xmlStorageRef/upoStorageRef` wykluczone |
| 9 | 401/403/404 | Bezpieczne treści (bez stosu, VCAP, JDBC, `clientsecret`) — testy offline; anonimowo także LIVE |
| 10 | Cache/wyciek | Brak cache per-użytkownik w CAP (JWT bezstanowy, brak mutowalnego stanu żądań). AppRouter: strona logowania `no-store`. Nagłówki cache dla **uwierzytelnionych** odpowiedzi OData — **niezweryfikowane LIVE** |
| 11 | AppRouter → CAP | Jedyna trasa `^/odata/v4/KsefService/`, `xsuaa`, CSRF AppRoutera ON, `forwardAuthToken` wyłącznie dla `ainvoicer-cap` (także LIVE w env) |
| 12 | AppRouter → H01 | `/rap-ui/*` → `AINV_H01_UI_USER`, `xsuaa`, bez `forwardAuthToken`; `csrfProtection:false` akceptowalne, bo usługa UI jest read-only (zwykłe widoki SELECT bez BDEF) |
| 13 | Principal przez Destination | Kod/trasy nie mogą tego dowieść; typ Destination `OAuth2UserTokenExchange` = OWNER-REPORTED (as-built 10.11) |
| 14 | Token exchange — ryzyka | Patrz sekcja „Principal H01” |

### C07 po INT-03 — zamknięty w kodzie i testach

Historyczna luka (obcy `zid/app_tid`, zaufany podpis, własny audience → 200 na starszych projekcjach) jest
**zamknięta w kodzie e468c76**: `DedicatedTenantPolicy` odrzuca token przed utworzeniem principal CAP, więc
także `$metadata`, service root, `$batch`, `POST`. Dowody OFFLINE:

- `XsuaaJwtODataContractTest.dedicatedTenantIsRequiredAcrossAllPublicSurfaces` — 4 encje × 6 wariantów
  zapytań, klucze, metadata, root, POST `requestSend`/`$batch`/`KsefOperations` → 401 (tryb smoke).
- **Nowy** `HanaReadinessJwtSecurityTest.foreignTenantIsRejectedOnEveryReadAndWriteSurface` — to samo
  w składzie flag **faktycznie wdrożonego wariantu HANA** (bez handlera smoke), bez nowych wierszy.
- `inconsistentMissingOrSpoofedTenantClaimsFailClosed`, `DedicatedTenantPolicyTest`.

LIVE: **BLOCKED z założenia** — legalny token obcego tenanta podpisany naszym XSUAA nie jest osiągalny.
Nie potwierdzono też, że droplet z 18:12Z zawiera dekorator (commit nieznany); MTA 0.7.3 budowano po INT-03.

## Faza B — anonimowe HTTP LIVE (VERIFIED LIVE)

`node deploy/scripts/live-security.mjs <CAP> <AppRouter>` — wyłącznie GET, bez poświadczeń/cookie, bez
podążania za redirectem, body ≤ 64 KiB nie jest zapisywane; raport zawiera status, klasyfikację i request ID.

| Pomiar | Wynik |
| --- | --- |
| 18:32:40Z — plan CAP-07D (26 sond) | **26/26 PASS**; CAP `/` → 401 (dawny FAIL C11 zamknięty) |
| 18:37:02Z — plan rozszerzony, pierwsza wersja | 36 PASS / **2 FAIL**: nawigacja AppRoutera dała 200 zamiast oczekiwanego 302 |
| analiza 2 FAIL | 200 = 709-bajtowa strona HTML `Cache-Control: no-store`, skrypt `location` → wyłącznie host XSUAA `/oauth/authorize`; bez UI5 bootstrap, bez danych OData. Standardowe przekierowanie AppRoutera; błędne było założenie testu |
| **18:38:16Z — plan rozszerzony po korekcie** | **38/38 PASS**, 0 `sensitiveError`, 0 `contractExposed`, request ID dla 38/38 |

Pokrycie 38 sond: CAP anonim + malformed JWT dla `$metadata`, service root, `InvoiceIntegrationStatus`
(list/count/key), `InboundInvoiceOverview`, **`KsefOperations` (list/count), `KsefInvoices`** → 401 (18/18);
11 prywatnych/technicznych ścieżek (`/`, H2, actuator, OpenAPI, Fiori preview, staging/granty/writeback,
inny service root) → 401; AppRouter XHR (`index`, CAP metadata/inbox, RAP metadata/`Invoices`/**`InvoiceItems`/
`InvoiceReferences`**) → 401 (7/7); nawigacja przeglądarki (`/index.html`, `/rap-ui/Invoices`) → strona
przekierowania do XSUAA (2/2). Na CAP wszystko 401, więc odpowiedź nie rozróżnia istnienia ścieżek.

## Faza C — macierz akceptacji (aktualizacja C/R/F)

| ID | Scenariusz | OFFLINE (e468c76) | LIVE 12.11 | Zmiana vs CAP-07D |
| --- | --- | --- | --- | --- |
| C01 | Anonim CAP metadata/list/count/key/inbox → 401 | VERIFIED | **VERIFIED 9/9** (+root, KsefOperations, KsefInvoices) | szerszy zakres |
| C02 | Malformed JWT → 401 | VERIFIED | **VERIFIED 9/9** | szerszy zakres |
| C03 | Brak `KsefOperator` → 403 (dane i akcje) | VERIFIED (smoke + HANA readiness) | BLOCKED (brak kontrolnego konta) | + skład HANA |
| C04 | Brak NIP/CompanyCode → fail closed | VERIFIED | BLOCKED | — |
| C05 | Obcy audience → 401 | VERIFIED | NOT EXECUTED (CAP-07D: 2/2 PASS tokenem CF, historyczne) | — |
| C06 | Obcy tenant + audience → 401 | VERIFIED | BLOCKED (token nieosiągalny) | — |
| C07 | Obcy tenant, zaufany podpis, własny audience | **VERIFIED — zamknięty** (oba składy) | BLOCKED z założenia | **KNOWN GAP → zamknięty offline** |
| C08 | ≥2 NIP × ≥2 spółki → fail closed | VERIFIED | BLOCKED | — |
| C09 | Własny/obcy key, `$count`, OR | VERIFIED (nowy test OR NIP/spółka/dokument) | BLOCKED | + OR w HANA readiness |
| C10 | Prywatne/techniczne API | VERIFIED (anonim + operator, oba składy) | **VERIFIED 11/11** anonimowo | — |
| C11 | Brak publicznego indeksu CAP | VERIFIED | **VERIFIED** (`/`→401) | **FAIL → PASS** |
| C12 | Bezpieczne 401/403/404 | VERIFIED | anonim **VERIFIED**; 403/404 po auth NOT EXECUTED | — |
| R01 | Świeża anonimowa sesja AppRoutera | VERIFIED (trasy) | **VERIFIED** XHR 7/7 → 401; nawigacja 2/2 → XSUAA | + nawigacja, dzieci RAP |
| R02 | Token CAP tylko do CAP; RAP przez `AINV_H01_UI_USER` | VERIFIED | env AppRoutera **VERIFIED**; treść subaccount Destination OWNER-REPORTED | env potwierdzony LIVE |
| R03 | RAP GET obsługiwany przez business principal | nie do udowodnienia offline | **BLOCKED** | — |
| R04 | User bez Business Role → 403/0 także dzieci | procedura | **BLOCKED** (OWNER-REPORTED: ten sam user bez roli 0/0/0 przez ADT, nie AppRouter) | — |
| R05 | A/B, obcy NIP/BUK, bezpośrednie dzieci DCL | CAP A/B VERIFIED; to nie test DCL | **BLOCKED** | — |
| R06 | Nowe logowanie w osobnej sesji | helper gotowy | NOT EXECUTED (OWNER-REPORTED 10.11) | — |
| R07 | Logout / idle 15 min | helper + testy VERIFIED; `/do/logout` w trasach | `SESSION_TIMEOUT=15` **VERIFIED** w env; zachowanie NOT EXECUTED | env potwierdzony |
| F01 | Profil CF, guardy, flagi efektów OFF | VERIFIED (guardy + nowy skład) | **VERIFIED** (flagi, 1×XSUAA dedicated, HDI) | smoke → HANA readiness |
| F02 | INBOUND nieaktywny; UI CF bez Inbox | VERIFIED (beany inbound niezaładowane) | flaga INBOUND=false **VERIFIED**; UI NOT EXECUTED | — |

## Macierz A/B — gotowa procedura, wynik BLOCKED

Dostępne zasoby (z historycznych odczytów, nie ponawianych w IAM): jedno konto właściciela, które ma w H01
`SAP_BR_ADMINISTRATOR`, `SAP_BR_DEVELOPER` i `ZBR_AINV_READ_1000` (NIP 5260002008/BUK 1000), a w BTP
Role Collection `AInvoicer KSeF Operator 5260002008-1000`. **Nie potwierdzono istnienia drugiego,
least-privilege konta ani obcych fixtures** w H01/CAP (HANA CAP jest prawdopodobnie pusta). Konto A jest
administracyjne, więc nie dowodzi least privilege. Agent nie tworzy kont, ról ani danych.

| # | Przypadek | Wykonanie (właściciel/administrator) | Oczekiwanie | Dowód wymagany |
| --- | --- | --- | --- | --- |
| AB1 | A z Business Role i rolą CAP | helper `run({mode:'operator', rapOwnKey, rapForeignKey…})` | RAP 200 własny zakres; CAP 200 własna para | statusy, liczności, request ID |
| AB2 | B bez Business Role H01 | `mode:'no-h01-role'` | RAP 403 lub count 0 dla nagłówków **i** dzieci | to samo + różnica względem A |
| AB3 | B bez roli CAP | `mode:'no-cap-role'` | CAP dane/akcje 403; metadata może być 200 | — |
| AB4 | B z innym NIP/BUK (zatwierdzona rola) | `operator` z kluczami A jako „foreign” | 0/404 dla kluczy A, także `InvoiceItems/References` | zatwierdzone, istniejące fixtures |
| AB5 | Obcy tenant | tylko offline (C07) | 401 | test offline |
| AB6 | Obcy audience | offline + historyczny token CF | 401 | test offline |
| AB7 | Brak `KsefOperator` | = AB3 | 403 | — |
| AB8 | Własny/obcy key | `capOwnKey/capForeignKey` = **operationId** | 200 / 404 | istniejące niepuste fixtures |
| AB9 | Filtry OR | offline (C09); LIVE wymaga AB4 | brak rozszerzenia | — |
| AB10 | `$count` | helper (count/list) | liczniki po autoryzacji | — |
| AB11 | `$expand=_Items,_References` | ręczny GET w sesji A/B | dzieci tylko w zakresie | — |
| AB12 | Bezpośrednie dzieci | helper `rapItemForeignKey`, `rapReferenceForeignKey` | 403/404 | — |
| AB13 | Logout | `/do/logout`, potem `deniedAfterSessionEnd()` | 401/redirect | nie dowodzi globalnego logout IdP |
| AB14 | Session expiry | 15 min bez ruchu, potem `deniedAfterSessionEnd()` | 401/redirect | bez auto-refresh |

Pusty wynik bez znanego obcego fixture jest nierozstrzygający; losowy UUID z 404 nie jest dowodem izolacji.

## Principal H01 — BLOCKED

**Co sprawdzono (VERIFIED LIVE, MCP read-only, H01):** brak zapisanych trace runs; brak released CDS dla
Security Audit Log (`I_*SECURITYAUDIT*`, `I_*SECAUD*`) i connectivity trace (`I_*CONNECTIVITYTRACE*`,
`I_*HTTP*LOG*`). Jedyny kandydat `I_IAMBusinessUserLogonDetails` (`#CHECK`) ma wyłącznie
`UserLastLogonDate/Time` — nie wiąże się z konkretnym GET, a sesja ADT tego samego usera też go aktualizuje.
**Nie zapytano** tego widoku o dane użytkowników (zbędne i nierozstrzygające).

Dlaczego to nie wystarcza: HTTP 200 z AppRoutera, `CreatedBy`, e-mail w UI, udany token exchange ani czas
ostatniego logowania nie identyfikują principala obsługującego konkretne żądanie. CF `x-vcap-request-id`
nie musi być przekazywany do H01.

**Ryzyka token exchange / Destination (do weryfikacji przez właściciela):**

1. Destination przełączona na `BasicAuthentication`/`OAuth2ClientCredentials` → wspólny principal techniczny;
   wszyscy użytkownicy AppRoutera dostają zakres jednego konta, DCL przestaje rozróżniać osoby.
2. Użycie `AINV_H01` (communication user `AINV_CAP_USER`) dla UI → zakres konta technicznego (ADR-0005 zabrania).
3. Exchange między subaccountami bez ponownej analizy (SAP: `SAMLAssertion` dla różnych subaccountów).
4. Token CAP przekazany do H01 — wykluczone konfiguracją (`forwardAuthToken` tylko CAP; test tras + env LIVE).
5. Mapowanie e-mail XSUAA → business user H01: kolizja/zmiana adresu może przypisać żądanie innemu userowi.

**Najmniejsza diagnostyka (propozycja, bez nowych obiektów ABAP):**

1. **Test różnicowy bez trace (preferowany):** w AppRouterze sesja A i sesja konta bez Business Role (AB2)
   wykonują ten sam `GET /rap-ui/Invoices?$count=true`. A > 0 i B = 0 (oraz dzieci 0) wyklucza wspólny principal
   techniczny. To dowód **pośredni**, nie identyfikacja principala.
2. **Dowód autorytatywny:** właściciel sprawdza w Fiori H01, czy dostępna jest standardowa aplikacja
   śledzenia połączeń przychodzących (np. *Display Connectivity Trace*) lub *Display Security Audit Log* —
   **dostępność i nazwy do potwierdzenia w launchpadzie H01**. Jeśli tak: krótkie, czasowo ograniczone
   śledzenie wyłącznie ścieżki `/sap/opu/odata4/sap/zui_ainv_inv_h_o4/` dla jednego GET A, zapis czasu UTC
   i `X-CorrelationID`, powiązanie wpisu (user, ścieżka, czas) z GET, potem wyłączenie trace. To zmiana stanu
   diagnostyki — wymaga zgody właściciela i nie jest wykonywana przez agenta.
3. Dopiero gdy 1–2 są niedostępne: projekt endpointu diagnostycznego z CAP-07D (review, osobna zgoda na
   obiekt/IAM/aktywację/usunięcie). Nie utworzono ani nie aktywowano niczego w H01.

## Faza D — defekty i poprawki

| ID | Waga | Ustalenie | Stan |
| --- | --- | --- | --- |
| E-1 | MEDIUM (luka testowa) | Jedyny test JWT HTTP (`XsuaaJwtODataContractTest`) działał w trybie smoke z `IdentitySmokeReadOnlyHandler`; wdrożony wariant HANA readiness go nie ma, więc brakowało dowodu dla rzeczywistego składu | **NAPRAWIONE OFFLINE**: `HanaReadinessJwtSecurityTest` (6 testów) |
| E-2 | MEDIUM/LOW (otwarte, decyzja właściciela) | LIVE (HANA readiness) operator może `POST KsefOperations` (`CHECK_STATUS`/`RECEIVE_INVOICE`) we własnym zakresie — trwały wiersz w HANA bez procesora. Obcy zakres i `SEND_INVOICE` → 400, `status/environment` nadaje serwer; UI używa tylko READ i wyprowadza status wyłącznie z `SEND_INVOICE`, więc brak podrobienia statusu. Niepotrzebna powierzchnia zapisu i szum w danych operacyjnych/audycie | Scharakteryzowane testem (`…KnownRisk`); rekomendacja: przed aktywacją KSeF usunąć grant CREATE albo blokować go poza świadomym trybem |
| E-3 | LOW (otwarte) | `requestSend` przy KSeF OFF najpierw czyta fakturę z H01 przez `AINV_CAP_USER`, potem sprawdza NIP/spółkę i dopiero `acceptsContext`. Operator może odróżnić 404 (brak) od 403 (obcy zakres) dla dowolnego UUID oraz wywołuje zbędny odczyt technicznym kontem | Offline: brak rezerwacji/operacji (test). Rekomendacja: przy wyłączonym transporcie odrzucać przed odczytem SAP (zmiana kodu KSeF — osobna decyzja) |
| E-4 | LOW (narzędzie) | `live-security.mjs` nie badał `KsefOperations`/`KsefInvoices`/root ani dzieci RAP; nie weryfikował celu nawigacji | **NAPRAWIONE**: +12 sond, klasyfikacja przekierowania bez zapisu URL, 3 nowe testy |
| E-5 | INFO | Nowy droplet 18:12Z o nieznanym commicie; STATUS opisuje starszy stan | Do potwierdzenia przez właściciela |
| E-6 | INFO | `SPRING_SQL_INIT_PLATFORM=h2` w env HANA (bez skutku przy `MODE=never`) | Przekazane do CAP-HANA-02 |
| E-7 | LOW | Nagłówki cache uwierzytelnionych odpowiedzi OData niezweryfikowane; brak jawnego `no-store` | Sprawdzić w sesji A (DevTools → nagłówki), ewentualnie dodać `Cache-Control: no-store` |

Nie osłabiono `DedicatedTenantPolicy`, guardów ani istniejących testów; żadnej zmiany kodu produkcyjnego.

## Ryzyka przed aktywacją KSeF

1. Brak dowodu principal H01 i izolacji A/B/DCL w AppRouterze (R03–R05).
2. Konto testowe A jest administracyjne — brak least privilege.
3. Otwarty CREATE `KsefOperations` (E-2) i kolejność `requestSend` (E-3).
4. Nieznany commit wdrożonego dropletu (E-5) — przed sign-off wymagany manifest wydania/SHA.
5. Pełny deskryptor XSUAA (scopes, redirecty, atrybuty) i treść Destination nieczytelne przez CAPI (CF120004).
6. Ogólna rola `KsefOperator` bez wartości istnieje w Cockpit — ryzyko omyłkowego przypisania (fail closed w CAP, ale do usunięcia/przeglądu).
7. cflinuxfs4 deprecated; HANA DML/restart/trwałość nadal w CAP-HANA-02.

## Działania właściciela (konkretne)

1. Potwierdzić źródło wdrożenia 18:12 UTC (commit/MTAR SHA) i czy obejmuje INT-03 `DedicatedTenantPolicy`.
2. W Cockpit odczytać `AINV_H01_UI_USER` (Authentication = `OAuth2UserTokenExchange`, brak User/Password) i
   Role Collection konta A; usunąć/zablokować przypisanie ogólnej roli `KsefOperator` bez wartości.
3. Zapewnić drugie konto bez Business Role H01 i bez roli CAP; wykonać AB2/AB3 + test różnicowy principala.
4. Zdecydować o dostępnej aplikacji trace/SAL w H01 i ewentualnym jednorazowym, czasowo ograniczonym śledzeniu.
5. Wykonać AB13/AB14 (logout, 15 min idle) w osobnej sesji i zapisać tylko statusy/request ID.
6. Zdecydować o E-2 i E-3 przed jakąkolwiek aktywacją KSeF.

## Polecenia

```powershell
npm ci --prefix deploy
npm test --prefix deploy
node deploy/scripts/live-security.mjs https://ainvoicer-v2xzij9s-srv.cfapps.eu20-001.hana.ondemand.com https://ainvoicer-v2xzij9s-approuter.cfapps.eu20-001.hana.ondemand.com
cd cap-java; mvn -B -pl srv -am test -Dtest=HanaReadinessJwtSecurityTest,XsuaaJwtODataContractTest
```
