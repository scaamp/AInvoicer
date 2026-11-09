---
title: "CAP-07C — Autonomous Hardening & E2E Readiness"
date: 2026-11-09
type: journal
status: zakonczony-lokalnie
tags: [ainvoicer, journal, cap-07c, cloud-foundry, security, ci, cap-04d]
---

# CAP-07C — Autonomous Hardening & E2E Readiness

[STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Runbook](../guides/BTP_FIRST_DEPLOYMENT_RUNBOOK.md) · [Identity](../guides/BTP_IDENTITY_SETUP.md)

## Zakres i Git

2026-11-09, Europe/Warsaw. Zlecenie obejmuje kod, lokalne testy, commit/push i stacked PR; wyklucza deploy/restart/usługi BTP, zmiany IAM/Destination/SAP i SAP/KSeF WRITE. Użyto skillu `ainvoicer-cap-java`. Nie zmieniono ADR-0005 ani diagramów bezpieczeństwa.

`git fetch --all --prune`: czyste drzewo, CAP-07B HEAD/origin `9d7a380`, main `552fdb9`; nowy branch `feature/cap-07c-autonomous-hardening` z CAP-07B. Odczyt GitHub PR #1: OPEN, merged=false, mergeable=false (nie zakładamy dawnego clean). Baza stacked PR pozostaje CAP-07B; brak merge/force push.

## Implementacja i zmienione pliki

Przed implementacją sprawdzono oficjalne [CAP Java Security](https://cap.cloud.sap/docs/java/security), [CDS Properties](https://cap.cloud.sap/docs/java/developing-applications/properties), [MBT usage](https://sap.github.io/cloud-mta-build-tool/usage/) i przypięte źródła SDK MF. `mbt assemble` pakuje gotowe artefakty według deskryptora deploymentu; nie traktujemy samego pliku YAML jako wyniku builda.

- `deploy/package*.json`, `scripts/identity-smoke.mjs`, `test/`: deterministyczna transformacja parsowanego `mta.yaml` do osobnego deskryptora deploymentu, ścisła kontrola środowiska, nazw i referencji. Pełny `mta.yaml` nadal HANA/HDI; `deploy/btp-dev-eu20.mtaext` bez zmian.
- Smoke zawiera tylko srv + approuter oraz **existing-service** `ainvoicer-auth` i `ainvoicer-destination`. Nie tworzy i nie aktualizuje usług; bez dostępnych istniejących instancji późniejszy deploy się nie powiedzie. Ten wariant jest celowo przeznaczony dla istniejącego środowiska CAP-07B.
- `mbt assemble` 1.2.49 składa artefakty ze świeżego katalogu tymczasowego z whitelisty plików. Nie potrzeba make, scratchpadów ani przepakowywania MTAR. Archiwum jest ponownie odczytywane i walidowane; błędny artefakt jest usuwany. Deterministyczny jest deskryptor; ZIP/JAR/MTAR zawierają znaczniki czasu i nie obiecujemy identycznego SHA całego MTAR.
- `CloudPersistenceGuard`: kontrola flag smoke również przy HANA (wcześniej `HDB` omijało tę regułę), kontrolowana odmowa H2 bez `SPRING_SQL_INIT_PLATFORM=h2`. `CloudPersistenceGuardTest` i `XsuaaJwtODataContractTest` nie narzucają własnego DataSource w profilu cf. Lokalna H2 jest tworzona rzeczywistą auto-konfiguracją.
- `deploy/runtime/cf-runtime.test.mjs`: spakowany executable JAR, syntetyczne VCAP/XSUAA i zmienne środowiskowe jak w deskryptorze. Testy startu i HTTP bez tokenów BTP; środowisko dziedziczone jest oczyszczane z konfiguracji AINV/CDS/Spring/VCAP/Destinations. HTTP serwowane wyłącznie na loopback.
- UI5: `externalServices` w manifestach, bramki AI/Scanner/Voice/TTS przed dostępem do danych, plików i sieci; niedostępne akcje UX; wariant CF dodatkowo CSP `connect-src 'self'`. Źródła lokalne zachowują jawne opt-in i istniejące endpointy; nie dodano nowych tras AI ani poświadczeń. Testy QUnit i realny Chrome/CDP potwierdzają odmowę przed zewnętrznym POST.
- `.github/workflows/ci.yml`: jeden standardowy runner Ubuntu, `contents: read`, bez zapisanych credentiali checkoutu, Node22/JDK25/Maven3.9.16. Publiczny SDK3.0.29 jest budowany z przypiętego commita na JDK21, bez GitHub Packages/PAT; instalator działa na Windows i Linux. Maven/testy offline, spakowany JAR, MTAR, oba buildy UI5, runner/QUnit/OPA i approuter.
- `LiveWriteConsent*` i harness LIVE: odmowa dla czterech znanych UUID seed, wymagany numer nowej faktury `AINV-CAP04D-*`, zgoda dla każdego UUID, dokładny NIP/spółka, OUTBOUND i pusty numer KSeF. STALE/RACE mają osobne numery w env. Zwykłe Maven verify wyklucza live-sap/live-sap-write/live-ksef.
- Dokumentacja STATUS/ROADMAP/README/runbook/identity/SAP WRITE oraz `.gitignore`: wyniki, procedury i wykluczenie wygenerowanych artefaktów.

## Audyt endpointów UI5

| Miejsce | Faktyczne wywołanie | Dostępność lokalna | CF |
| --- | --- | --- | --- |
| `_processAiQuery` | POST Python us10 `/filter`, kontekst search | jawne `externalServices.ai=true` | OFF przed odczytem modelu + CSP |
| `_sendToNodeJs` / Analyze Scanner | POST Node us10 `/analyze`, treść pliku | scanner=true | OFF przed konwersją pliku + CSP |
| `_sendToOpenAI` | historyczny helper jQuery POST `api.openai.com/v1/chat/completions`; brak aktywnego call-site z Analyze | źródło zachowane, scanner=true | OFF także przy bezpośrednim wywołaniu helpera + CSP |
| `onPlayTTS` | POST Python us10 `/speech` | tts=true | OFF + CSP |
| `onVoiceInput` | Web Speech API (rozpoznawanie przeglądarki, potencjalnie usługa dostawcy) | voice=true | handler OFF; CSP nie zastępuje tej bramki |
| komentarz `127.0.0.1:4000/speech` | zakomentowane, bez wywołania | brak wpływu | brak wpływu |
| manifest `mainService` | historyczny same-origin `/sap/...` | Legacy | preload/earlyRequests/Legacy OFF; brak backend route |
| UI5 resources i przestrzenie XML | zasoby SAP UI5; `www.w3.org` to namespace | zasoby UI | CDN przez `/resources/`, bez forwardAuthToken |

Capabilities frontendowe nie stanowią autoryzacji backendu. CAP wymusza JWT/rolę/zakres, flagi transportu i read-only smoke. CSP jest dodatkową kontrolą przeglądarki, a nie zabezpieczeniem zewnętrznych usług AI. Pełna integracja AI wymaga osobnej konfiguracji backendu i przeglądu bezpieczeństwa; nie wystarczy przestawić flagę.

## Macierz publicznych tras

| Endpoint | Authentication | Authorization | Bez tokenu/sesji |
| --- | --- | --- | --- |
| AppRouter `/`, `/index.html` | XSUAA sesja | logowanie; operacje egzekwuje CAP | redirect/login (XHR401); LIVE login usera niepotwierdzony |
| AppRouter `/resources/*` | anonimowo | tylko publiczne pliki CDN UI5 | 200/404; brak przekazywania JWT |
| AppRouter `/odata/v4/KsefService/*` | XSUAA, forwardAuthToken wyłącznie CAP | CAP `KsefOperator` + NIP/spółka; CSRF approutera | redirect/login lub XHR401 |
| AppRouter `/rap-ui/*` | XSUAA + Destination business-user token exchange | H01 IAM/DCL | redirect/login lub XHR401; SSO nadal NOT EXECUTED |
| AppRouter `/sap/*`, Web API, H2, diagnostyka | catch-all XSUAA do localDir | brak proxy do tych backendów | login/401, po loginie 404 |
| CAP `/`, `/$fiori-preview/*` | brak anonimowej strony w cf | generowanie strony/podglądu OFF | LOCAL401/403/404; LIVE `/`200 (stary build) |
| CAP OData `$metadata`, kolekcje | walidacja XSUAA SAP | authenticated-user + KsefOperator, zakres READ | 401 |
| CAP `requestSend`, CREATE `KsefOperations` | XSUAA | CAP rola/zakres, potem smoke409 | 401/403; operator409 bez zapisu |
| CAP H2, Actuator, Swagger/API docs | brak publicznej ekspozycji | brak świadomie otwartego endpointu | 401/403/404 |

XSUAA4.1.2: testy złego podpisu/audience/wygasłego JWT odmawiają. Sam `iss` nie jest osobnym walidatorem XSUAA; obowiązuje kotwica kluczy bindingu + podpis + audience + czas (ADR-0006). Nie wymyślono oczekiwania401 dla poprawnie podpisanego tokenu ze zmienionym `iss`.

Redirect XSUAA nadal ograniczony do hosta approutera. Brak publicznej trasy do Web API i brak JWT dla `ui5`/RAP. Communication user `AINV_H01` pozostaje wyłącznie CAP → Web API. Konfiguracja Destination/IdP i rzeczywiste cookies użytkownika po loginie wymagają osobnego LIVE acceptance; testy statyczne nie dowodzą konfiguracji administracyjnej.

## CAP-04D — wynik przeglądu offline

Nie przepisano adaptera/outboxa. Istniejące testy lokalnego HTTP potwierdzają CSRF, pełną precyzję wersji100ns w `ExpectedLocalLastChangedAt`, stały WriteRequestId, wyniki APPLIED/ALREADY_APPLIED/VERSION_CONFLICT/RESULT_CONFLICT/IDEMPOTENCY_CONFLICT, timeouty, retry budget i recovery. To statyczna akcja kolekcji RAP: CAS znajduje się w payloadzie; nie należy dodawać fikcyjnego If-Match do akcji kolekcji. LIVE ETag/CSRF pozostają do potwierdzenia.

Po timeout nie ma ukrytego retry HTTP: outbox odzyskuje zlecenie i odtwarza **identyczny** idempotentny command z tą samą oczekiwaną wersją i WriteRequestId; RAP rozstrzyga ALREADY_APPLIED/konflikt. Jest to rekoncyliacja przez kontrakt idempotentnej akcji SAP, nie ponowna wysyłka do KSeF. Nieznany/niespójny wynik200 jest terminalny `SAP_INVALID_RESULT`; konflikt wersji/wyniku wymaga kontroli operatora. Działanie LIVE tego kontraktu nie zostało wykonane.

Wykluczenia tagów i warunek env pozostają; usunięto lukę pozwalającą wybrać seed i opcjonalny fixture bez kontroli OUTBOUND/zakresu. Bez nowego dedykowanego dokumentu i osobnej zgody CAP-04D pozostaje BLOCKED. Harness nie tworzy dokumentów ani nie przydziela ACTVT16.

## Polecenia i wyniki

Narzędzia lokalne: Microsoft JDK25.0.4, JDK21.0.12 (SDK), Maven3.9.16, Node24.13.0, MBT1.2.49, CF8.19.0. CI używa Node22 i Temurin25/21; sam Linux runner nie jest wynikiem lokalnego testu Windows.

| Polecenie / zakres | Wynik | Kategoria |
| --- | --- | --- |
| `npm ci --prefix deploy`, `npm test --prefix deploy`, `npm run validate:smoke --prefix deploy` | 4/4; scenariusze obejmują wszystkie flagi, nieznane env, HANA, hosty, referencje, deterministyczność i uprawnienia CI | LOCAL PASS |
| `Install-KsefSdk.ps1 -SourceDirectory <temp>/ainv-cap07c-sdk -Jdk21 <JDK21>` | publiczny clone v3.0.29 + kontrola SHA, Gradle publishToMavenLocal; powtórzenie exit0 | LOCAL PASS, bez KSeF HTTP |
| `npm run build:btp:smoke --prefix deploy` | exit0, kompletny build i walidacja MTAR, bez CF CLI | LOCAL PASS |
| Maven w powyższym buildzie: `mvn -B -f cap-java/pom.xml clean verify -Dcds.cds.skipVersionCheck=true` | **243/243**, failures0/errors0/skipped0, executable JAR | LOCAL / OFFLINE CONTRACT PASS |
| `npm run test:cf-runtime --prefix deploy` (w buildzie) | **8/8**, rzeczywisty JAR + VCAP/env; wszystkie bramki startup i HTTP | OFFLINE CONTRACT PASS |
| UI5 `npm ci`, `npm run build:cf`, `npm run test:cf-build` | **8/8**, 50 plików; manifest i preload externalServicesOFF, CSP | LOCAL PASS |
| `npm run build` (UI5) | standardowy build developerski, bez usuwania AI/Scanner/Voice | LOCAL PASS |
| `npm run test:runner` (UI5) | **4/4**, realny Chrome/CDP, redirect credentials i CSP external POST0 | LOCAL PASS |
| `ui5 serve --config ui5-mock.yaml --port 8097`; `browser-test.mjs qunit …unit…` / `…integration…` | **QUnit79/79, 446/446 asercji; OPA13/13, 80/80**; exit0 | LOCAL MOCK PASS |
| AppRouter `npm ci`, `npm run test:config` | **8/8** | OFFLINE CONTRACT PASS |
| `npm audit --prefix deploy`; `npm audit --omit=dev --prefix approuter` | 0 / 0 | LOCAL PASS |
| `node deploy/scripts/check-secrets.mjs`, `git diff --check`, review diffu | bez nowych sekretów/wygenerowanych artefaktów w commicie; opisane ograniczenia skanera | LOCAL PASS |
| Runtime XSUAA użytkownika, SSO H01, A/B, HANA | brak sesji business user / konfiguracji / bazy | NOT EXECUTED / BLOCKED |
| GitHub Actions | workflow i lokalne odpowiedniki zweryfikowane; wynik zdalnego runnera wymaga osobnego odczytu po push | NOT EXECUTED na moment zapisu tego wpisu |
| CF redeploy/restart, zmiany BTP/SAP, SAP WRITE, KSeF SEND | nie wykonano | NOT EXECUTED |

OPA raportuje dwa spodziewane `console.error: TTS disabled in tests`: stub odmawia TTS, asercje i exit0 potwierdzone. To nie jest test LIVE TTS ani powód deklarowania działania usług zewnętrznych.

Artefakt skopiowano z izolowanego builda do **`D:/AInvoicer/deploy/build/ainvoicer_0.7.2.mtar`**, 67 295 265 bajtów. SHA-256: `8aa4afc6472eeb0be9813b253e6264bc580230d1bd5362215e228d5ce218949e`. Ponowna walidacja kopii PASS. Odczyt JAR-a: brak lokalnych bindingów, testowych klas i wzorców sekretów w aplikacyjnych klasach/resources; nested zależności SDK/SAP są zależnościami runtime, nie dodatkowymi usługami CF. Artefaktu nie dodano do Git.

Publikacja po review: commit implementacji **`38fea847f82923eee9b211fb04e88b422d632746`**, `git push -u origin feature/cap-07c-autonomous-hardening` PASS, lokalny HEAD zgodny z origin i czyste drzewo. Utworzono **[PR #2](https://github.com/scaamp/AInvoicer/pull/2)** względem `feature/cap-07b-live-btp` (`9d7a380`); odczyt GitHub: OPEN, draft=false, mergeable=true, merged=false. Nie wykonano merge/auto-merge/force push. Późniejszy commit uzupełnia ten zapis publikacji i ogranicza push-trigger CI do main; branche feature są testowane przez PR, aby nie uruchamiać drugiego identycznego builda po każdym push do otwartego PR.

Własny odczyt GitHub po utworzeniu PR: workflow **[42669316555](https://github.com/scaamp/AInvoicer/actions/runs/42669316555)** `queued`, conclusion=null. To potwierdza przyjęcie workflow, **nie CI PASS**. Ostateczny wynik zdalny należy odczytać w PR; wynik lokalny Windows nie dowodzi zakończenia runnera Ubuntu.

Ograniczenia lokalne: zastany CAP8080 blokuje główny JAR; użyto izolowanego worktree `%TEMP%/ainvoicer-cap07c-validation`. Pierwsze wywołanie Maven z niecytowanym `-D` odrzucone przez PowerShell; poprawiono wywołanie. Podczas pierwszej regresji równoległe uruchomienie własnego serwera UI5 zablokowało `npm ci` (native keyring DLL); proces dotyczył wyłącznie izolowanej kopii, zakończono go i kolejne kroki wykonano sekwencyjnie. Nie dotykano zastanych8080/8085/8086/8095; własny serwer8097 zakończono po testach. Pierwsze pakowanie wykryło ścieżkę `node.exe` ze spacjami w shellu; poprawiono uruchamianie plików EXE bez shell. `mbt assemble` nie kompresuje automatycznie katalogów modułów: skrypt buduje jawne `data.zip`, a raw payload jest poza katalogiem assembly (bez duplikatu JAR). Starszy `dist-cf` w drzewie głównym został prawidłowo odrzucony przez nowe reguły, następnie przebudowany i zaliczył8/8. Te błędy pośrednie nie są PASS.

## LIVE READ

`cf target/apps/services/routes` 2026-11-09: org `National Center for Cryptography_dev-v2xzij9s`, space `dev`, obie aplikacje1/1 started, XSUAA application/Destination lite związane z obiema, brak HANA, `myapp` nietknięta. Nie czytano service keys ani `cf env`. Bezpieczny anonimowy GET CAP: `/`200, `$metadata`401, `/h2-console/`401, `/actuator/health`401. To potwierdza stary deployment i nadal niewdrożony fix indeksu. Nie testowano aktywnie usług zewnętrznych AI.

H01: w CAP-07C nie wykonano dodatkowego READ; wynik4/9/6 pochodzi z CAP-07B, nie z tej sesji. SAP HTTP WRITE, KSeF SEND, cf deploy/restart/restage i zmiany administracyjne: **NOT EXECUTED**.

## Ryzyka i następny krok

- LIVE poprawki `/` i UI5/CSP wymagają osobno zatwierdzonego redeployu; obecny CF pozostaje bez zmian.
- H2 smoke jest efemeryczna, historia procesu nie może być w niej tworzona; brak HANA/zarządzanego storage nadal blokuje workflow KSeF.
- Istniejący tooling UI5 ma74 npm advisories (2low/15moderate/54high/3critical). Nie wykonano przypadkowego `npm audit fix --force`; osobny upgrade tooling. Nowe zależności deploy i approuter: audit0 na etapie instalacji.
- Skan sekretów jest heurystyczny i pomija zastany śledzony vendor `node/node-app/node_modules` (przykłady dotenv dawały false positives). Nie jest audytem historii Git ani gwarancją braku sekretów. Repo ma historycznie śledzone zależności vendor, poza zakresem tego sprintu; nowych nie dodano.
- Właściciel: zgoda na redeploy smoke → BTP Role Collection i trust IdP → Destination `AINV_H01_UI_USER` i business-user SSO → acceptance A/B. HANA free/HDI wymaga decyzji bez tworzenia płatnych zasobów. Osobna zgoda i fixture przed CAP-04D.

Rekomendacja: **CAP-07B.2 — LIVE Business User SSO Acceptance**, następnie CAP-04D po jego niezależnych bramkach.
