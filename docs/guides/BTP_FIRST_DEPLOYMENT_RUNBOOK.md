---
title: "BTP — runbook pierwszego kontrolowanego wdrożenia (CAP-07.1)"
date: 2026-11-08
type: guide
status: przygotowany-niewdrozony
tags: [ainvoicer, btp, cloud-foundry, deployment, xsuaa, hana, approuter, sso, cap-07]
---

# Runbook pierwszego wdrożenia na SAP BTP Cloud Foundry

[Project Home](../README.md) · [ADR-0006 tożsamość](../decisions/ADR-0006-btp-identity-xsuaa.md) · [ADR-0007 trwałość](../decisions/ADR-0007-cap-persistence-hana-cloud.md) · [BTP Identity setup](BTP_IDENTITY_SETUP.md) · [RAP UI LIVE](RAP_UI_LIVE_CONNECTION.md) · [Dziennik CAP-07.1](../journal/2026-11-08-cap-07-1-deployment-readiness.md)

> **Stan 2026-11-08:** artefakty zbudowane i sprawdzone lokalnie (MTAR `ainvoicer_0.7.1.mtar` złożony przez `mbt module-build` + `mbt assemble`). **Nic nie wdrożono.** Żadna usługa, rola, Destination, trust ani Communication Arrangement nie zostały utworzone ani zmienione. Stan subaccount **NOT VERIFIED** — agent nie miał `cf`/`btp` CLI ani dostępu do cockpitu. Każdy krok oznaczony 🔒 wymaga osobnej zgody właściciela.

> **CAP-07B etap 1 (2026-11-08):** nadal **nic nie wdrożono**. API `cf-eu20-001` osiągalne (CC v3 3.233.0, min. CLI 8.0.0); agent ma portable **cf 8.19.0** (`%USERPROFILE%\tools\cf8\x\cf.exe`), ale **bez logowania** — inventory, entitlementy i quota NOT VERIFIED. MTAR odbudowany poprawioną procedurą (§4, 67 MB). Regresja 236/77/13/7/7 PASS. [Dziennik CAP-07B](../journal/2026-11-08-cap-07b-btp-live-deployment.md).

## 0. Zasady bezpieczeństwa wdrożenia

- Pierwsze wdrożenie to **smoke tożsamości i odczytu**: logowanie, role, odczyt RAP i statusów CAP. Bez KSeF SEND, bez SAP WRITE, bez syntetycznych faktur.
- Flagi pozostają: `ainv.ksef.enabled=false`, `ainv.ksef.send.retry-after-rejection=false`, `ainv.sap-write.enabled=false`; MTA ustawia dodatkowo `AINV_KSEF_ENABLED="false"`, `AINV_SAP_WRITE_ENABLED="false"`. UI5 CF: wszystkie capabilities `false` (build kończy się błędem, gdy źródło ma `true`).
- Nie przekazujemy agentowi tokenów, service keys ani haseł. Sekrety wyłącznie w Destination Service / bindingach.
- Środowisko trial/free nie jest produkcyjne; dane wyłącznie syntetyczne.

## 1. Sprawdzenie subaccount i Cloud Foundry (read-only)

BTP Cockpit → Global Account → Subaccounts → właściwy subaccount:

| Co sprawdzić | Gdzie | Oczekiwane / zapisz |
| --- | --- | --- |
| Region subaccountu CF | Overview → Region | np. `us10` (trial) / `eu20` |
| Org i space CF | Overview → Cloud Foundry Environment | nazwa org, space (np. `dev`) |
| Limit pamięci CF | Overview → Cloud Foundry → Quota | ≥ 1,6 GB (srv 1024M + approuter 256M + deployer 256M chwilowo) |
| Gdzie jest H01 | Instances and Subscriptions → ABAP Environment | czy instancja H01 jest w **tym samym** subaccount co CF |
| Trust / IdP | Security → Trust Configuration | IdP, w którym istnieją business userzy H01 (IAS) |

**Potwierdzone przez właściciela (zrzut BTP Cockpit, 2026-11-08):** jeden subaccount zawiera środowisko Cloud Foundry `dev-v2xzij9s_dev` (plan `free`, landscape `cf-eu20-001`) oraz instancję **ABAP environment `dev`** (plan `free`, runtime CF, space `dev`, 1 service key) = H01 (`9197a0a5-….abap.eu20` / `.abap-web.eu20`). Druga instancja: `default_aicore` (SAP AI Core, `free`). Brak instancji xsuaa/destination/HANA. **CF i H01 są w tym samym subaccount → wariant SSO A** (§6.2). Usługi Node/Python na `cfapps.us10-001` należą do innego konta (Legacy). Uwaga: plany są `free` (free tier), a nie trial — opis „BTP Trial” w AGENTS.md jest w tym punkcie nieprecyzyjny.

CLI (wykonuje właściciel; same odczyty): `cf login` → `cf target`, `cf marketplace -e xsuaa`, `cf marketplace -e destination`, `cf marketplace -e hana`, `cf services`, `cf buildpacks | findstr java`.

**Logowanie dla agenta (CAP-07B):** właściciel we **własnym** terminalu (nie w czacie, bez przekazywania passcode):

```powershell
& "$env:USERPROFILE\tools\cf8\x\cf.exe" login -a https://api.cf.eu20-001.hana.ondemand.com --sso -o dev-v2xzij9s_dev -s dev
```

Passcode z przeglądarki trafia wyłącznie do CLI; sesja zapisuje się w `%USERPROFILE%\.cf\config.json` (zawiera tokeny — nie wysyłać, nie commitować). Potem agent wykonuje tylko odczyty: `cf target`, `cf org <org>` (quota), `cf space dev`, `cf services`, `cf apps`, `cf marketplace` (+ `-e xsuaa`, `-e destination`, `-e hana`, `-e hana-cloud`), `cf buildpacks`, `cf curl /v3/service_plans?...` — bez `cf service-key`/`cf env` (sekrety). Wylogowanie po sprincie: `cf logout`.

## 2. Entitlementy — stan

| Usługa / plan | Do czego | Stan 2026-11-08 |
| --- | --- | --- |
| Authorization and Trust Management `xsuaa` / `application` | logowanie, scope `KsefOperator`, atrybuty NIP/spółka | **NOT VERIFIED** |
| Destination `destination` / `lite` | `AINV_H01`, `AINV_H01_UI_USER` | **NOT VERIFIED** |
| SAP HANA Cloud (instancja bazy) | trwałość CAP | **NOT VERIFIED** |
| SAP HANA Schemas & HDI Containers `hana` / `hdi-shared` | kontener `ainvoicer-db` | **NOT VERIFIED** |
| Cloud Foundry Runtime | 3 aplikacje | **AVAILABLE** (środowisko `dev-v2xzij9s_dev`, plan `free`, `cf-eu20-001`, status Created — zrzut właściciela); quota pamięci **NOT VERIFIED** |
| Buildpack `sap_java_buildpack_jakarta` + SapMachine 25 | CAP Java 5.2 / JDK 25 | **NOT VERIFIED** (wzorzec `JBP_CONFIG_SAP_MACHINE_JRE: { version: 25.+ }` zgodny z `cds add mta`, cds-dk 10.2.0) |
| ABAP Environment H01 | RAP UI + Web API | **AVAILABLE** — instancja `dev`, plan `free`, ten sam subaccount co CF (zrzut właściciela); MCP 2026-11-08: oba bindingi published/V4/0001, READ 4 nagłówki |

Brak instancji xsuaa/destination/HANA w subaccount nie przesądza o entitlementach — sprawdź *Entitlements*. W free tier SAP HANA Cloud ma osobny plan bezpłatny z ograniczeniami (**TO VERIFY** w Entitlements: SAP HANA Cloud oraz SAP HANA Schemas & HDI Containers).

Cockpit → Subaccount → **Entitlements** → *Configure Entitlements* — tylko odczyt; dodanie entitlementu 🔒.

**Bramka kosztowa (CAP-07B):** konto jest free tier, nie trial — plan płatny w tym koncie nalicza opłaty. Deploy dopuszczalny wyłącznie, gdy SAP HANA Cloud ma przypisany plan **bezpłatny** (free tier; nazwa w marketplace TO VERIFY, np. `hana-cloud`/`hana-free`) oraz `hana`/`hdi-shared`. Instancję HANA Cloud tworzy właściciel w *SAP HANA Cloud Central* (hasło `DBADMIN` ustala wyłącznie on; agent go nie zna i nie przekazuje w `cf create-service -c`). Brak planu bezpłatnego → STOP, decyzja: plan płatny (osobna zgoda z kwotą) albo identity-smoke (§3).

## 3. Wybór bazy

Decyzja: [ADR-0007](../decisions/ADR-0007-cap-persistence-hana-cloud.md) — **SAP HANA Cloud + kontener HDI `hdi-shared`**. Profil `cf` bez HANA **nie wystartuje** (`CloudPersistenceGuard`, sprawdzone lokalnie na JAR: komunikat `requires durable SAP HANA Cloud persistence (found database 'H2')`). Przed zmianą istniała cicha baza `jdbc:h2:mem:<uuid>`.

Opcja zapasowa **identity-smoke** (tylko gdy HANA niedostępna, 🔒 osobna zgoda): env `AINV_DEPLOYMENT_IDENTITY_SMOKE=true` na `ainvoicer-srv` oraz usunięcie zasobu `ainvoicer-db` i modułu deployera z kopii deskryptora (lub extension descriptor). Aplikacja wtedy startuje na H2 w pamięci tylko przy KSeF/writeback/sweeper OFF i odrzuca `requestSend` oraz zapisy `KsefOperations` kodem `AINV_IDENTITY_SMOKE_READ_ONLY` (409). Tryb nie jest ustawiony w żadnym pliku repo (pilnuje test approutera).

## 4. Wymagane instancje (tworzy `cf deploy` 🔒)

| Instancja | Typ | Źródło konfiguracji |
| --- | --- | --- |
| `ainvoicer-auth` | xsuaa/application | `cap-java/xs-security.json` + `xsappname: ainvoicer-${org}-${space}`, redirect tylko na host approutera |
| `ainvoicer-destination` | destination/lite | puste; Destinations dodaje administrator (§5–6) |
| `ainvoicer-db` | hana/hdi-shared | wymaga instancji HANA Cloud zmapowanej do org/space (HANA Cloud Central → Instance Mapping) |
| aplikacje | `ainvoicer-srv`, `ainvoicer-approuter`, `ainvoicer-db-deployer` (task, kończy się) | `mta.yaml` |

Build (wykonuje osoba z GNU make; agent nie miał make): `mbt build` w katalogu repo → `mta_archives/ainvoicer_0.7.1.mtar`. Bez make: `npm ci --prefix sapui5/ainvoicer && npm run build:cf --prefix sapui5/ainvoicer`, potem `mbt module-build` + `mbt assemble`.

**Uwaga (CAP-07B):** `mbt module-build -g` uruchomione w katalogu repo tworzy `.<repo>_mta_build_tmp` **wewnątrz** projektu, a `mbt assemble` w tym samym katalogu pakuje go drugi raz (MTAR 134 MB z podwójnym JAR). Sprawdzona procedura (plik 67 MB):

```bash
cd <katalog nadrzędny repo>
mbt module-build -s <repo> -m ainvoicer-srv,ainvoicer-db-deployer,ainvoicer-approuter -g   # -> ./mtad.yaml + ./.<repo>_mta_build_tmp/
mkdir asm asm/cap-java && cp mtad.yaml asm/ && cp -r .<repo>_mta_build_tmp asm/ && cp <repo>/cap-java/xs-security.json asm/cap-java/
cd asm && mbt assemble -t ../mtar-out                                                       # -> ainvoicer_0.7.1.mtar
```

Weryfikacja przed deployem: `unzip -l` = 3 moduły + `cap-java/xs-security.json` + `META-INF`; approuter `data.zip` bez `node_modules/`, `test/`, `default-env.json`, `resources/manifest.json` z `dataMode=rap` i `sendInvoice=false`; `mtad.yaml` z `AINV_KSEF_ENABLED/AINV_SAP_WRITE_ENABLED: "false"`; skan `clientsecret|PRIVATE KEY|eyJhbGciOi` = 0.

Deploy (wymaga pluginu MultiApps `cf install-plugin multiapps`): `cf deploy mtar-out/ainvoicer_0.7.1.mtar` 🔒.

## 5. XSUAA i rola KsefOperator (po deployu 🔒)

1. Security → Role Collections → New: `AInvoicer KSeF Operator 5260002008-1000`.
2. Dodaj rolę z szablonu `KsefOperator` aplikacji `ainvoicer-<org>-<space>`: atrybuty **Static** `taxpayerNips=5260002008`, `companyCodes=1000` (dokładnie jedna para; bez wartości z IdP i bez unrestricted).
3. Przypisz kolekcję do business usera A (Security → Users, **ten IdP, którego używa approuter**). Drugi użytkownik B z inną parą — do testu izolacji.
4. Użytkownik bez kolekcji = kontrolny przypadek 403.

Szczegóły i zasada jednej pary NIP×spółka: [BTP_IDENTITY_SETUP §4–5](BTP_IDENTITY_SETUP.md#4-rola-ksefoperator).

## 6. Destinations

### 6.1 Approuter → CAP

`ainvoicer-cap` jest zdefiniowana w env approutera w `mta.yaml` (`url: ~{srv-url}`, `forwardAuthToken: true`). To **jedyna** trasa z przekazaniem tokenu XSUAA (test `xs-app.test.mjs`).

### 6.2 Approuter → H01 RAP UI: `AINV_H01_UI_USER` (business-user SSO)

Wymagania niezależne od wariantu:

- Type `HTTP`, URL = **origin API** H01 `https://9197a0a5-b602-4c28-84b4-6b63c6daa54d.abap.eu20.hana.ondemand.com` (host `.abap.`, bez ścieżki; ścieżkę `ZUI_AINV_INV_H_O4/0001` dokłada `xs-app.json`), ProxyType `Internet`.
- **Bez** `HTML5.ForwardAuthToken` (dokumentacja approutera: nie ustawiać, gdy `Authentication` ≠ `NoAuthentication`) — JWT CAP nie trafia do H01.
- **Bez** Basic / communication usera / hasła. Bez `sap-client` (ABAP Cloud).
- Inny URL niż jakakolwiek inna destination approutera (approuter trzyma cookies backendu per URL w sesji użytkownika; dwie destinations z tym samym URL w jednej sesji są niedozwolone). Legacy `/sap` nie jest routowany.
- Login w H01 = ten sam użytkownik IAS, który loguje się do approutera (mapowanie po e-mail/login — **TO VERIFY**). IAS H01 musi być IdP subaccountu CF (Trust Configuration, OIDC) i approuter powinien logować przez ten IdP.

| Wariant | Kiedy | Destination | Konfiguracja H01 | Stan |
| --- | --- | --- | --- | --- |
| **A** `OAuth2UserTokenExchange` | CF i H01 w **tym samym** subaccount (ta sama strefa XSUAA) | Client ID / Secret + Token Service URL (`…/oauth/token`) z poświadczeń XSUAA instancji ABAP (service key H01, sekcja `uaa`) — **TO VERIFY** w SAP Help *Routing via Destination* | brak dodatkowego trustu; business user H01 z rolą `ZBR_AINV_READ_1000` | wariant najprostszy, jeśli §1 potwierdzi ten sam subaccount |
| **B** `SAMLAssertion` (lub `OAuth2SAMLBearerAssertion`) | CF i H01 w **różnych** subaccountach (wskazówka z §1) | Audience / AuthnContextClassRef / nameIdFormat wg SAP Help *Configure OAuth 2.0 SAML Bearer Assertion Grant*; certyfikat podpisu: Destination Service subaccountu CF → *Download Trust* | Communication System (inbound) z zaufanym certyfikatem/IdP subaccountu CF i odpowiedni Communication Arrangement — scenariusz/ID **TO VERIFY** | wymaga administratora H01; dla `OAuth2SAMLBearerAssertion` approuter wymaga scope `uaa.user` (dokumentacja approutera) → wtedy dodać role-template `Token_Exchange` z `uaa.user` do `xs-security.json` (osobna zmiana + test) |

**Wybór (2026-11-08): wariant A** — CF i H01 w jednym subaccount (§1). Konfiguracja do wykonania przez administratora 🔒:

| Właściwość | Wartość |
| --- | --- |
| Name | `AINV_H01_UI_USER` |
| Type / ProxyType | `HTTP` / `Internet` |
| URL | `https://9197a0a5-b602-4c28-84b4-6b63c6daa54d.abap.eu20.hana.ondemand.com` (host API `.abap.`, **nie** `.abap-web.`) |
| Authentication | `OAuth2UserTokenExchange` |
| Client ID / Client Secret | z istniejącego service key instancji ABAP `dev`, sekcja `uaa` (`clientid`, `clientsecret`) — wpisać tylko w Destination, nigdy w repo/czacie |
| Token Service URL | `<uaa.url z tego service key>/oauth/token` (Token Service URL Type: Dedicated) — **TO VERIFY** z SAP Help *Routing via Destination* |
| Bez | `HTML5.ForwardAuthToken`, `sap-client`, User/Password |

**Weryfikacja wariantu A (CAP-07B, źródła dostępne agentowi):** dokumentacja `@sap/approuter` — `OAuth2UserTokenExchange` nie współpracuje z przekazywaniem tokenów **IAS** (binding `identity`); AInvoicer loguje przez XSUAA, więc warunek spełniony, ale approutera nie wolno przełączać na `identity` bez zmiany wariantu. Tutorial SAP (ABAP env → Fiori na CF) wymaga tego samego subaccount i autoryzacji użytkownika do usługi OData (Business Role). SAP Help *Routing via Destination* nadal nieodczytany. Ostateczny dowód = test §7 #3 i #8 (principal w H01 = business user).

Warunek tożsamości: approuter musi logować użytkownika przez **ten sam IdP (IAS)**, który obsługuje logowanie do H01 (Security → Trust Configuration). Role collection `KsefOperator` przypisz użytkownikowi w tym originie; jeśli aktywny jest też SAP ID service, użytkownik wybiera przy logowaniu IAS (ewentualne `identityProvider` w `xs-app.json` — osobna zmiana po poznaniu nazwy originu). Wariant B pozostaje opisem rezerwowym.

**Pierwotny powód braku wyboru:** oficjalne strony SAP Help (*Routing via Destination*, *Configure OAuth 2.0 SAML Bearer Assertion Grant*, *Application Routes and Destinations*) w sesjach agenta zwracały pustą powłokę JS; przeczytano wyłącznie dokumentację approutera z SAP-docs/btp-cloud-platform. Reguła „ten sam subaccount → `OAuth2UserTokenExchange`, różne → `SAMLAssertion`” pochodzi z opisu zadania i źródeł wtórnych — administrator potwierdza ją z SAP Help dla bieżącej wersji H01 przed utworzeniem Destination.

### 6.3 CAP → H01 Web API: `AINV_H01` (bez zmian)

Type HTTP, origin H01, ProxyType Internet, uwierzytelnienie communication usera z arrangement `ZCS_AINV_H01` (sekrety tylko w Destination Service). Używana wyłącznie przez CAP (`requestSend` READ, przyszły writeback). **Nigdy** do UI READ. W smoke bez KSeF można jej nie tworzyć — `requestSend` i tak zwraca 409 `KSEF_CONTEXT_NOT_CONFIGURED`/smoke.

## 7. Testy po wdrożeniu (wykonuje właściciel lub agent z przekazanym URL approutera)

| # | Scenariusz | Oczekiwane |
| --- | --- | --- |
| 1 | User A loguje się do approutera | strona UI5, sesja `JSESSIONID` HttpOnly/Secure |
| 2 | `/odata/v4/KsefService/InvoiceIntegrationStatus` (A) | 200, tylko 5260002008/1000, poprawny `@odata.count` |
| 3 | `/rap-ui/Invoices?$top=5` (A) | 200, faktury z DCL A (oczekiwane 4 z H01) |
| 4 | Użytkownik bez roli H01 | `/rap-ui/Invoices` 200 z 0 rekordami albo 403 — nigdy dane |
| 5 | Użytkownik bez `KsefOperator` | CAP 403, `requestSend` 403 |
| 6 | Bezpośrednio URL `ainvoicer-srv` bez tokenu / z obcym tokenem | 401 |
| 7 | Dwie role różnych par | CAP 0 rekordów, `requestSend` 403 |
| 8 | Ślad w H01 (Display Security Audit/Log lub `LocalCreatedBy`-podobny odczyt) | żądania UI jako business user, **nie** communication user `AINV_H01` |
| 9 | Dwie przeglądarki A/B równolegle | brak mieszania danych/cookies; wylogowanie `/do/logout` kończy sesję |
| 10 | `requestSend` jako A | 409 (`KSEF_CONTEXT_NOT_CONFIGURED`; w smoke `AINV_IDENTITY_SMOKE_READ_ONLY`) — **brak SEND** |
| 11 | `cf restart ainvoicer-srv` | historia operacji zachowana (HANA) |
| 12 | Start bez bindingu XSUAA/HANA (kontrolnie, osobna aplikacja) | aplikacja nie startuje |

## 8. Rollback

- Aplikacje: `cf undeploy ainvoicer` 🔒 **bez** `--delete-services` (zachowuje HDI i historię). Alternatywnie `cf stop ainvoicer-srv ainvoicer-approuter`.
- Poprzednia wersja: `cf deploy` poprzedniego MTAR. Uwaga: HDI migruje schemat do przodu; MTAR starszy niż schemat może się nie wdrożyć — przed zmianą modelu CDS zrób eksport/backup kontenera.
- Usunięcie usług (`cf delete-service`, `--delete-services`) wyłącznie po osobnej zgodzie i po ocenie danych audytu.

## 9. Kontrola kosztów

- HANA Cloud jest największym kosztem (instancja działa w trybie ciągłym; w trial/free zwykle z ograniczeniami i automatycznym zatrzymaniem — **TO VERIFY**). Zatrzymuj instancję po testach (HANA Cloud Central → Stop).
- CF: 1 instancja `ainvoicer-srv` (1024M), 1 approuter (256M); deployer kończy się po migracji. Po testach `cf stop`.
- Brak multitenancy, brak dodatkowych usług (IAS AMS, Event Mesh, Object Store).

## 10. Zabezpieczenia przed przypadkowym SEND

1. `ainv.ksef.enabled=false` (YAML + env MTA) → `requestSend` 409 przed rezerwacją; brak KSeF NIP/tokenu w środowisku.
2. `ainv.sap-write.enabled=false` → brak HTTP WRITE do `ApplyKsefResult`.
3. UI5 CF: wszystkie capabilities OFF; źródło z `true` przerywa build (`build-cf.test.mjs`).
4. CAP: `requestSend` wymaga `KsefOperator` + zgodnej pary NIP/spółka; operacji `SEND_INVOICE` nie da się utworzyć przez CRUD.
5. Tryb identity-smoke dodatkowo blokuje `requestSend` i zapisy operacji.
6. Włączenie KSeF TEST wymaga osobnego sprintu (E2E-01) i jawnej zgody na jedną syntetyczną fakturę.

## 11. Czynności wymagające zgody właściciela

| Czynność | Kto |
| --- | --- |
| Dodanie entitlementów (xsuaa, destination, HANA Cloud, hdi-shared) i utworzenie instancji HANA Cloud | administrator BTP 🔒 |
| `mbt build` + `cf deploy` (tworzy usługi i aplikacje) | właściciel 🔒 |
| Role collection i przypisania userów A/B | administrator BTP 🔒 |
| Trust IAS w subaccount CF | administrator BTP 🔒 |
| Destination `AINV_H01_UI_USER` (+ ewentualny Communication System/Arrangement w H01 dla wariantu B) | administrator BTP/H01 🔒 |
| Destination `AINV_H01` (communication user) | administrator 🔒 |
| Tryb identity-smoke zamiast HANA | właściciel 🔒 |
| Jakikolwiek KSeF SEND, SAP WRITE, nadanie ACTVT16, syntetyczna faktura | osobne sprinty CAP-04D / E2E-01 🔒 |
