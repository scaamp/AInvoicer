---
title: "CAP-07D — LIVE security acceptance i gotowość aktualizacji identity-smoke"
date: 2026-11-11
type: guide
status: security-acceptance-pending
tags: [ainvoicer, security, xsuaa, cloud-foundry, approuter, rap, iam, cap-07d]
---

# CAP-07D — akceptacja bezpieczeństwa

**Werdykt: SECURITY ACCEPTANCE PENDING.** Narzędzia, testy i niewdrożony wariant smoke są przygotowane.
Aktualny LIVE backend ujawnia indeks CAP pod `/`. Principal H01 i izolacja A/B pozostają **BLOCKED**.
Build nie stanowi akceptacji bezpieczeństwa ani zgody na wdrożenie.

**Wznowienie LIVE po przywróceniu dostępów (11 listopada, od10:05 CET):** CF i MCP H01 działają.
Potwierdzono inventory/dedicated bindingi, dostępność fs5 oraz RAP UI200/4/9/6. Principal trasy
AppRouter i A/B nadal BLOCKED. Starsze komunikaty o wygasłym CLI/503 poniżej opisują pierwszy pomiar.
Aktualne dowody: [CF](../../deploy/evidence/2026-11-11-cf-live-readiness.json),
[H01](../../deploy/evidence/2026-11-11-h01-followup.json),
[foreign identity401](../../deploy/evidence/2026-11-11-foreign-identity.json),
[powtórka publicznego HTTP](../../deploy/evidence/2026-11-11-public-read-followup.json).

[As-built LIVE](CAP_07B_2_BTP_IDENTITY_SSO_LIVE.md) · [Runbook](BTP_FIRST_DEPLOYMENT_RUNBOOK.md) ·
[ADR-0006](../decisions/ADR-0006-btp-identity-xsuaa.md) · [RAP IAM](RAP_UI_IAM_MODEL.md) ·
[Dziennik CAP-07D](../journal/2026-11-11-cap-07d.md)

## Zakres i proweniencja

Branch `feature/cap-07d-live-security-readiness`, worktree `D:\AInvoicer-cap-07d`, baza
`origin/main=f093af5bd6ed67cf75150b1940959a21f26306f0`. Główny worktree i KSEF-STOR-01 niezmieniane.
Zmiany dotyczą testów security, `deploy/`, testów tras oraz tego przewodnika i dziennika.
Zgodnie ze zleceniem pominięto wspólne STATUS/ROADMAP/README; indeks uzupełni orkiestrator.
Nie zmieniano domeny KSeF, CDS stagingu, document store, obiektów SAP ani konfiguracji BTP.

Relacja właściciela z 10 listopada: XSUAA dwa bindingi, rola 5260002008/1000, `sap.default`,
Destination `OAuth2UserTokenExchange`, CAP READ i cztery faktury RAP przez CF.
Nie jest to ponowna weryfikacja tych ustawień 11 listopada. LIVE poniżej oznacza wyłącznie
własny pomiar HTTP z tej sesji. Testy JWT używają lokalnego JWKS/testowego klucza SAP;
nie pobierają credentiali ani tokenów BTP/KSeF i nie wywołują SAP WRITE/KSeF SEND.

## Macierz akceptacji i wynik

| ID | Scenariusz / oczekiwanie | OFFLINE | LIVE 2026-11-11 |
| --- | --- | --- | --- |
| C01 | Direct CAP anonymous `$metadata`, lista, count, key, inbox → 401 | PASS | **PASS 5/5** |
| C02 | Malformed JWT na tych samych GET → 401 | PASS | **PASS 5/5** |
| C03 | Brak `KsefOperator` → 403 | PASS, podpisany JWT | BLOCKED: brak dostępnej sesji kontrolnego usera |
| C04 | Brak NIP lub CompanyCode → pusta lista/count0, brak key | PASS | BLOCKED: brak kontrolnych ról/sesji |
| C05 | Obce audience (także syntetyczne H01) → 401 | PASS | **PASS2/2**: istniejący token użytkownika CF odrzucony401; nie izoluje samego walidatora audience od podpisu/issuer |
| C06 | Obcy tenant + obce audience → 401 | PASS | BLOCKED |
| C07 | Obcy tenant przy zaufanym podpisie i własnym audience → odmowa | Inbox **PASS403**; starsze projekcje **KNOWN GAP** | NOT TESTED |
| C08 | ≥2 NIP i ≥2 spółki → fail closed; inbox wymaga jednej pary | PASS | BLOCKED |
| C09 | Własny/obcy key, `$count`, OR/filter nie rozszerza widoczności | PASS na niepustych fixtures | BLOCKED; puste smoke nie dowodzi izolacji |
| C10 | Prywatny staging/granty/writeback i techniczne API niedostępne | PASS także z operatorem | **PASS10/10** anonimowo |
| C11 | Brak publicznego indeksu CAP | PASS nowego JAR | **FAIL**, `/`200 z kontraktem |
| C12 | Błędy 401/403/404 bez stack trace/bindingów/danych | PASS | Bezpieczne anonimowe odpowiedzi; auth403/404 NOT TESTED |
| R01 | Świeża anonimowa sesja AppRouter → login/401, bez danych CAP/RAP | Test tras PASS | **PASS5/5** (XHR401; bez cookie jar) |
| R02 | CAP JWT wyłącznie do CAP; RAP przez `AINV_H01_UI_USER` | PASS struktury tras/destinations | Runtime config BLOCKED; relacja 10 listopada w as-built |
| R03 | GET RAP obsługiwany przez business principal H01 | Nie do udowodnienia mockiem | **BLOCKED**: MCP READ200/4/9/6 nie dowodzi kontekstu AppRoutera |
| R04 | User bez Business Role → 403 lub count0 także dla dzieci | Harness + procedura | **BLOCKED**, brak sesji kontrolnej |
| R05 | A/B, obcy NIP, BUK i bezpośrednie dzieci DCL | CAP JWT A/B PASS; **nie jest testem DCL SAP** | **BLOCKED** |
| R06 | Nowe logowanie w osobnej sesji przeglądarki | Harness przygotowany | NOT EXECUTED; historyczny functional PASS10 listopada |
| R07 | Logout i idle expiry15min → ponowny GET niedostępny | Helper/testy PASS | BLOCKED; nie kończono sesji właściciela |
| F01 | Profil CF, XSUAA/mock/HANA guards, SEND/WRITE/sweeper/auth OFF guards | Testy executable JAR | Core smoke env **VERIFIED**; INBOUND env UNSET w starszym runtime; nowy artefakt ma jawne false |
| F02 | Inbox runtime nie uruchamia synchronizacji; UI CF disabled | PASS | Starszy smoke, nie INT-02 |
| F03 | cflinuxfs5 + system buildpack/JDK25 w naszym org/space | Kolektor gotowy | fs5 **AVAILABLE**, Node1.9.6/fs5 enabled, Java2.76.0 enabled bez ograniczenia stack w CAPI; staging kombinacji fs5/JDK25 **NOT TESTED** |

Zbiorczy wynik HTTP: **25 PASS / 1 FAIL**, 11 listopada, ok.08:13 CET.
[Raport z bezpiecznymi request ID](../../deploy/evidence/2026-11-11-public-read.json).
Indeks: `x-vcap-request-id=c6399869-6f56-45fa-54ba-2a0cd3f6d90c`, HTTP200,
`contractExposed=true`. Nie zapisano body, cookie, redirect URL ani Authorization.
Żaden z tych anonimowych testów nie jest dowodem autoryzowanego żądania do H01.
Ponowny pomiar po przywróceniu CF: ponownie **25 PASS / 1 FAIL** (CAP index200).
Osobny test istniejącego tokena użytkownika Cloud Controller dał **2/2 HTTP401** na metadata/liście CAP.
Nie wystawiono nowego tokena ani credentiali. Raport nie zawiera Authorization ani samego tokena.

### Ograniczenie polityki tenantów

`dedicatedTenantGapOutsideInboxIsExplicitlyCharacterized` potwierdza obecny kontrakt ADR-0006:
syntetyczny token ze zmienionymi `zid/app_tid`, podpisany zaufanym kluczem i zachowujący audience
aplikacji, odczytuje `InvoiceIntegrationStatus` (200 i własny zakres), metadata200, inbox403.
Test PASS oznacza utrwalenie **ograniczenia**, nie PASS wymagania C07. Nie dowodzi uzyskania takiego
tokena od realnego XSUAA; obcy audience jest odrzucany. Wspólna kontrola dedicated tenant wymaga
osobnego zadania w polityce backendu i przeglądu ADR, poza obszarem testów/deploy z tego zlecenia.
Nie zmieniano `InboundOverviewHandler` ani właścicielstwa danych.

INBOUND ma dodatkowo świadome ograniczenie INT-02: CompanyCode jest pojedynczym kontekstem
użytkownika, a widoczność dokumentu wynika z grantu BUYER dla NIP/TEST. Nie jest to jeszcze
autorytatywne przypisanie dokumentu INBOUND do spółki. Istniejący test dowodzi, że ten sam NIP
z inną pojedynczą spółką nadal widzi ten sam grant; nie raportujemy tego jako izolacji po spółce.

## Narzędzia read-only

Z katalogu repo, Node22/24:

```powershell
npm ci --prefix deploy
npm test --prefix deploy
node deploy/scripts/live-security.mjs https://ainvoicer-v2xzij9s-srv.cfapps.eu20-001.hana.ondemand.com https://ainvoicer-v2xzij9s-approuter.cfapps.eu20-001.hana.ondemand.com
node deploy/scripts/existing-cf-identity.mjs
$env:AINV_CF_CLI = "$env:USERPROFILE\tools\cf8\x\cf.exe"
npm run readiness:cf --prefix deploy
```

Kolektor CF wymaga istniejącego logowania CLI. Weryfikuje target EU20/org/space przed CAPI.
Wykonuje wyłącznie `cf curl ... -X GET`: stacks, buildpacks, apps/routes, binding metadata,
whitelistę flag env, aktualny droplet i bezpieczne kontrole parametrów XSUAA.
Nie wypisuje `VCAP_SERVICES`, clientid/clientsecret, innych env, logów ani raw błędów CLI.
Parametry usługi nie dowodzą przypisań Role Collection ani zawartości subaccount Destination;
kolektor oznacza tę granicę jawnie. Błąd/brak dostępu daje BLOCKED, a nie pusty inventory PASS.
Nie tworzy OAuth/service keys i nie aktualizuje XSUAA/Destination.

Broker `ainvoicer-auth` zwraca **CF120004 / ServiceFetchInstanceParametersNotSupported**.
To BLOCKED odczytu deskryptora, a nie sześć negatywnych kontroli polityki. Kolektor rozpoznaje
`errors` nawet przy exit0 `cf curl`; nie interpretuje brakujących pól jako false. Odczyt istniejących
bindingów potwierdza dla obu aplikacji jeden XSUAA, `tenantmode=dedicated`, oczekiwaną rodzinę aplikacji
i tę samą aplikację/strefę. Nie dowodzi to scopes, template, atrybutów, redirectów ani przypisań ról.
Wartości credentiali/xsappname/tenantid nie są kopiowane do raportu. Brak detect_output JRE oznacza
wersję UNKNOWN, nie negatywny wynik zgodności Java25.

`existing-cf-identity.mjs` jest osobnym, jawnym testem negatywnym. Po weryfikacji targetu czyta
istniejący AccessToken CF wyłącznie do pamięci i wysyła dwa GET na ustalony własny direct CAP.
Nie wykonuje OAuth grant/redeem, nie ma parametru dowolnego originu, nie podąża za redirectem.
Nie uruchamiać go jako testu uprawnień business usera CAP lub principal propagation H01.

`live-security.mjs` wykonuje tylko bounded GET, bez credentiali i bez redirectów. Wymaga originów
HTTPS bez userinfo/query/path. Wynik FAIL/BLOCKED daje niezerowy exit; raport pozostaje w
ignorowanym `deploy/build/`. Testy obejmują prawdziwy loopback HTTP, redakcję, przekroczenie limitu
oraz brak podążania za redirectem. Narzędzia nie są pakowane do AppRoutera ani CAP.

### Zalogowane sesje A/B bez przekazywania tokenów

W istniejącej sesji AppRoutera otworzyć DevTools i wkleić treść
[`browser-session-acceptance.js`](../../deploy/scripts/browser-session-acceptance.js).
Nie wklejać niczego do strony IdP. Helper działa tylko na ustalonym originie AppRoutera.

```javascript
await ainvSecurityAcceptance.run({ mode: 'operator' })
// W oddzielnych zatwierdzonych kontach: no-cap-role, no-h01-role,
// no-attributes lub ambiguous-attributes. Nie zmieniać ról A na potrzeby testów.
```

Opcjonalne `capOwnKey`, `capForeignKey` są kluczami **operationId**, nie invoiceUUID;
`rapOwnKey`, `rapForeignKey`, `rapItemForeignKey`, `rapReferenceForeignKey` muszą pochodzić
z zatwierdzonych, istniejących niepustych fixtures i faktycznego metadata. Helper akceptuje tylko UUID.
Nie losować obcego klucza i nie traktować takiego404 jako dowodu izolacji. Brak fixtures daje BLOCKED.
Pusta CAP H2 daje `BLOCKED_EMPTY_FIXTURE`; niepusta lista sama daje `OBSERVED_ONLY`.
Helper testuje count/list, metadata, własny/obcy key, foreign filter/count i prywatne entity sets.
Zalogowany użytkownik bez operatora może widzieć sam schemat metadata (`authenticated-user` na
service root); helper oznacza to jako obserwację. Dane nadal muszą zwrócić403.
Zwraca tylko statusy/liczności/request ID; dane i cookies pozostają w pamięci przeglądarki.

Po **świadomym** logout `/do/logout` albo pełnym idle expiry bez ruchu przez15min wykonać
`await ainvSecurityAcceptance.deniedAfterSessionEnd()` w tej samej sesji/originie.
401 albo opaque redirect potwierdza odmowę AppRoutera. Błąd sieci daje BLOCKED; ponowne200 daje FAIL.
Nie dowodzi to globalnego logout IdP; nowe SSO może ponownie uwierzytelnić użytkownika.
Nie używać auto-refresh/pollingu podczas pomiaru idle expiry. Nie eksportować HAR/cookies/tokenów.

## Principal H01, IAM i negatywna macierz DCL

**Principal H01: BLOCKED.** Po odzyskaniu dostępu proces ABAP FS2.13.3 potwierdził pakiet
ZAINVOICER/recordChanges=false oraz aktywny published binding UI V4/0001. Metadata200 ma ten sam hash;
READ z `_Items/_References`: **4/9/6**, poprawne powiązania i oczekiwany zakres syntetyczny.
To kontekst istniejącego ADT OAuth, nie sesja AppRoutera.
Lista istniejących trace runs jest pusta. Discovery `I_SecurityAuditLog*`/`I_*SecurityAudit*` nie znalazło
CDS (nie jest to dowód braku wszystkich funkcji audytu). Brak autorytatywnego request-correlated dowodu
principala w dostępnych odczytach. Nie uruchamiano nowych traces ani diagnostyki ABAP.
Browser host nadal niedostępny; natywna Opera nie zwróciła AX, screenshot zakończył się FrameArrived timeout.
Nie obsługiwano formularzy logowania ani nie przejmowano cookies. Pierwsze503/wygasły CLI pozostają
wyłącznie historycznymi wynikami w [raporcie początkowym](../../deploy/evidence/2026-11-11-cf-readiness.json).

Preferowana procedura administratora po odzyskaniu odczytu:

1. Przejrzeć istniejący *Display Security Audit Log* / dostępny read-only ślad HTTP w H01;
   nie włączać nowego trace ani nie zmieniać audytu w tym zleceniu.
2. Wykonać pojedynczy GET `/rap-ui/Invoices` z sesji A, zapisać czas UTC/CET, correlation UUID
   i rzeczywisty request ID odpowiedzi, bez nagłówków auth/body. Sprawdzić, które ID faktycznie
   przechodzi przez AppRouter/Destination do H01 — CF ID nie musi być ID SAP.
3. Połączyć **konkretny GET** z zapisem H01 zawierającym obsługującego business usera, usługę,
   czas i wspólny identyfikator żądania. Porównać z zatwierdzonym kontem A i wykluczyć `AINV_CAP_USER`.
   Same AU1/login w zbliżonym czasie, `CreatedBy` faktury, email w UI lub Destination OAuth2 nie wystarczają.
4. Powtórzyć dla konta B. Publikować jedynie alias A/B, wynik business/technical, typ źródła i ID
   korelacji po zatwierdzeniu; pełny zapis użytkownika pozostaje w kontrolowanym audycie H01.

**A/B: BLOCKED.** Nie potwierdzono dostępności drugiego least-privilege usera i danych obcego zakresu.
Nie utworzono usera, ról ani faktur. Konto A ma historycznie admin/developer, więc nie dowodzi least privilege.
Aktualny released CDS `I_IAMBusinessUserBusinessRole` po odczycie aktywnej definicji potwierdza
po jednym przypisaniu SAP_BR_ADMINISTRATOR, SAP_BR_DEVELOPER i ZBR_AINV_READ_1000 oraz jednego widocznego
UserID (wyłącznie agregaty, bez identyfikatorów). Widok ma #CHECK, więc nie jest to dowód braku
innych użytkowników w całym systemie ani zastępstwo drugiej sesji.

| Test H01 w dwóch niezależnych profilach | Oczekiwany wynik |
| --- | --- |
| A, własny OUTBOUND SellerNip + CompanyCode | Dozwolony nagłówek, count i dzieci |
| A, własny INBOUND BuyerNip + CompanyCode | Dozwolony nagłówek, count i dzieci |
| A, obcy NIP przy tej samej spółce | count0, key403/404, także bezpośrednie dzieci |
| A, własny NIP przy obcej spółce | count0, key403/404, także bezpośrednie dzieci |
| B bez Business Role | 403 lub count0 nagłówków/pozycji/referencji |
| A/B z różnymi zatwierdzonymi parami równolegle | Każdy wyłącznie własny zakres; powtórzyć po zmianie kolejności żądań |
| Filtr OR, `$expand=_Items,_References`, bezpośrednie dzieci | Nie rozszerza DCL; liczniki po filtrze/autoryzacji |

Dla każdego negatywu administrator musi znać **istniejący** obcy fixture z uprawnionego źródła.
Bez tego wynik pusty jest nierozstrzygający. Ograniczeń DCL nie sprawdza się tokenem CAP ani communication userem.

### Projekt awaryjnej diagnostyki ABAP — wyłącznie do review

Nowy endpoint **nie jest obecnie uzasadniony**, dopóki nie sprawdzono istniejącego audytu po503.
Jeśli istniejące źródła nie dają korelacji, proponowany kontrakt to testowy, uwierzytelniony GET
przez tę samą Destination/UI binding, zwracający jedynie alias/principal bieżącej sesji,
identyfikator żądania i czas serwera. Bez tokena, listy ról, całego security context lub danych faktury.
To rozszerzenie wymaga osobnej zgody na obiekt, IAM, aktywację i czasowe udostępnienie.

Testy wymagane **przed** taką zgodą: anonymous401, brak dedykowanego uprawnienia403, A→A/B→B,
brak communication usera, brak cache wspólnego między userami (`Cache-Control: no-store`),
niezgodny correlation ID odrzuca dowód, brak możliwości zapisu/akcji, brak sekretów w schemacie,
ponowne DCL regressions i plan usunięcia diagnostyki. Dobór released API bieżącego użytkownika
oraz testy ABAP Unit/ATC muszą nastąpić na działającym H01; nie wygenerowano niezweryfikowanych
klas/adnotacji i nie utworzono/aktywowano niczego w H01.

## Release identity-smoke i różnica względem LIVE

| Obszar | LIVE według pomiaru / relacji | Nowy artefakt z aktualnej bazy main |
| --- | --- | --- |
| Commit/droplet | CF potwierdza oba droplety9 listopada, hashe w raporcie; nie zawierają potwierdzonego Git SHA | Źródła main f093af5 + CAP-07D; hashe w release-evidence |
| CAP `/` | **200 z indeksem**, potwierdzone dziś | Wyłączony index/Fiori preview, executable JAR test |
| INT-02 | Niewdrożony wg właściciela | Read model inbox w JAR, prywatny staging niewystawiony |
| UI | Historyczny smoke | CF rap, Inbox disabled, wszystkie capabilities i external services OFF |
| Usługi | Oczekiwane bindingi XSUAA/Destination obu aplikacji VERIFIED; deskryptor brokera BLOCKED | Tylko dwie `existing-service`, bez create/update XSUAA |
| DB | CAP nadal H2 ephemeral; istnieje nieprzypięta usługa ainvoicer-hana-dev/hana-free (nowy odczyt) | Jawny smoke/H2, bez HDI/deployera i bez retencji |
| Flagi | CF/smoke/H2 i SEND/WRITE/sweeperOFF VERIFIED; INBOUND UNSET w starszym smoke | CF, smoke=true; SEND/INBOUND/WRITE/sweeper=false, platform=h2 |
| Stack | Obie aplikacje cflinuxfs4; fs5 AVAILABLE w aktualnym inventory | Brak automatycznej migracji; staging po osobnej zgodzie |

```powershell
npm ci --prefix deploy
npm test --prefix deploy
npm run build:btp:smoke --prefix deploy
npm run verify:release --prefix deploy
```

Wyniki lokalne: `deploy/build/ainvoicer_0.7.2.mtar`, `identity-smoke.mtad.yaml`,
`release-inputs.json`, `release-evidence.json`. Build wykonuje Maven clean verify,
testy executable JAR z profilem CF/syntetycznym bindingiem, UI5 CF, AppRouter i MBT assemble.
Manifest dowodu zawiera commit/tree, dirty flag, hash rzeczywistych wejść przed/po buildzie
oraz SHA256 MTAR/deskryptora/JAR/payloadu AppRoutera. Zmiana wejść w trakcie builda lub podmiana
artefaktu przerywa weryfikację. `workingTreeDirty=true` trzeba wyjaśnić przed zatwierdzeniem;
do wydania preferować czysty checkout wskazanego commita. Hashy różnych buildów nie należy porównywać
jako byte-for-byte reproducibility (timestampy ZIP/JAR). Build nie wykonuje żadnych poleceń CF.

Walidator odrzuca dodatkowe usługi/env, mutating flags, UI SEND/Inbox opt-in, niezgodne routes,
lokalne bindingi, `application-test.yaml` i bibliotekę `java-security-test` w JAR.
Nie pakuje narzędzi diagnostycznych/testów do runtime. Zależności HANA w JAR nie tworzą HANA/HDI.

**Pierwszy zweryfikowany build 11 listopada08:39 CET: PASS**, czysty commit
`ba2f92a57e9230b118a85b3b4db135f7c1fbf213`, `workingTreeDirty=false`, 67 511 674 bajty.
SHA256 MTAR: `6cf4b86f9aa8000dcef0276c2cd4af076ae26a5000362ac65e75abd611411286`.
`verify:release` potwierdził archiwum, payloady i niezmienione wejścia.
Weryfikacja lokalna: Maven373/373, executable CF JAR10/10, deploy19/19,
AppRouter10/10, UI5 CF13/13, runner4/4, QUnit112/659, OPA21/150 i lokalny inbox E2E16/73 PASS.
Pełny [CI dla ba2f92a](https://github.com/scaamp/AInvoicer/actions/runs/42980498482): **SUCCESS**,
łącznie z rzeczywistym loopback HTTP CAP/UI5, negatywami A/B i restartem.
Późniejsze commity wyłącznie dokumentacyjne nie zmieniają tych wejść/payloadu;
manifest lokalnego artefaktu nadal wskazuje rzeczywisty commit jego budowy.
Aktualny wynik CI konkretnego HEAD jest w [checks PR7](https://github.com/scaamp/AInvoicer/pull/7/checks).

Kontrakt CAPI kolektora sprawdzono względem
[Cloud Controller API3.233.0](https://v3-apidocs.cloudfoundry.org/version/3.233.0/index.html):
użyte zasoby są odczytami GET; nie oznacza to ponownej weryfikacji ich uprawnień LIVE.

**Aktualne wydanie po wznowieniu LIVE:** clean build i `verify:release` **PASS** 11 listopada10:24 CET,
źródłowy commit `99edaad3063836c00534035c44e759da198a6eb6`, `workingTreeDirty=false`.
MTAR ma **67 511 694 bajty**, SHA256
`ecec1b22ed8a01dde529a7d4df483a4e0483fd09b8ab01ceecd1e664e7b7ecc1`.
Ponownie Maven373/373, executable JAR CF10/10, AppRouter10/10 i UI5 CF13/13 PASS;
testy narzędzi po korekcie CAPI i dodaniu istniejącej tożsamości CF: **24/24 PASS**.
Podany wcześniej hash ba2f92a jest historyczny; do zatwierdzenia używać aktualnego manifestu wydania.

### cflinuxfs5 / SAP Java Buildpack / JDK25

Oficjalny komunikat SAP podaje GA cflinuxfs5 na **9 listopada2026** i obsługę systemowych
buildpacków; wskazuje też rollout regionalny. **Nie jest to dowód dostępności w naszym space**.
Źródło: [SAP — migracja do cflinuxfs5](https://community.sap.com/t5/technology-blog-posts-by-sap/keep-your-apps-secure-and-supported-here-s-how-to-get-to-cflinuxfs5/ba-p/14455090).
SAP opisuje `SAPMachineJRE` i `JBP_CONFIG_SAP_MACHINE_JRE: '{ version: 25.+ }`:
[SapMachine w SAP Java Buildpack](https://help.sap.com/docs/btp/sap-business-technology-platform/sapmachine).

Po przywróceniu CLI: **cflinuxfs5 AVAILABLE**, `nodejs_buildpack1.9.6` enabled dla fs5 i fs4,
`sap_java_buildpack_jakarta2.76.0` enabled z polem stack=null. To brak ograniczenia stack w metadanych
CAPI, a nie udany staging na fs5. Oba aktualne droplety są nadal na fs4 i uruchomione1/1 od9 listopada.
SapMachine25.+ jest skonfigurowane; detect output dropletu nie ujawnia dokładnego JRE.
Kolektor odróżnia metadane platformy, żądaną konfigurację i rzeczywisty dowód uruchomienia.
Przed rekomendacją migracji: świeży inventory musi pokazać fs5 oraz pasujące systemowe
`sap_java_buildpack_jakarta` i `nodejs_buildpack`; następnie **odrębnie zatwierdzone staging/testy**
obu aplikacji. Lokalny Microsoft JDK25 nie jest testem SapMachine/Linux ABI.
Nie wykonano stagingu, restage, restartu, zmiany stacka ani migracji.

## Checklisty wdrożenia i rollback

### Pre-deploy — wszystkie pozycje przed decyzją właściciela

- [ ] Czysty checkout zatwierdzonego commita; CI PASS dla dokładnego HEAD; build i verify:release PASS.
- [ ] `cf target` zgodny z EU20/org/space; odczyt inventory, routingu, quota i dwóch istniejących usług.
- [ ] Bezpiecznie sprawdzone efektywne flagi/profil/H2 i brak nadpisujących env; brak credentiali KSeF.
- [ ] XSUAA dedicated, wąski redirect, role/atrybuty/bindingi bez zmian; Destination właściwa dla business usera.
- [ ] Zweryfikowany system buildpack/JDK25/stack obu modułów; żadnej niejawnej migracji do fs5.
- [ ] Zachowany poprzedni zatwierdzony **smoke** MTAR + extension, hashe i znane usterki; sam remote droplet nie jest kopią MTAR.
- [ ] Właściciel akceptuje utratę H2 oraz przerwę w sesjach. Brak założenia retencji/audytu/operacji biznesowych.
- [ ] Osobna zgoda na redeploy dokładnego pliku/checksumy i zakres dwóch aplikacji; bez HANA/zmian XSUAA/Destination.

Sam artefakt jest technicznie przygotowany do review kontrolowanego smoke redeployu.
**Nie ma pełnego LIVE release sign-off**: C07, principal/A/B, pełna polityka XSUAA/Destination
i staging nowego wariantu na wybranym stacku pozostają otwarte. Inventory platformy jest już odczytane.
Komenda wdrożenia dopiero po zgodzie: `cf deploy deploy/build/ainvoicer_0.7.2.mtar -e deploy/btp-dev-eu20.mtaext`.
Nie uruchomiono jej w tej sesji.

### Post-deploy — przyszły pomiar, nie wynik tej sesji

- [ ] Odczyt nowego dropletu/stacka/buildpacka, zdrowe dwie aplikacje, właściwe bindingi i routes.
- [ ] Ponowny `live-security.mjs`: CAP `/` już401/403/404, wszystkie unauthenticated probes PASS.
- [ ] Operator GET CAP/RAP, świeża sesja, logout/idle expiry; A/B/DCL i principal według macierzy.
- [ ] UI CF bez Inbox/mutacji/AI i bez fallbacku mock; brak technicznych danych w błędach.
- [ ] Zachować wyłącznie oczyszczony raport/checksumę; brak SAP WRITE/KSeF SEND. Nie testować `requestSend` LIVE.

### Rollback — również wymaga zgody

Przerwać akceptację przy otwartym indeksie, danych poza zakresem, niewłaściwym principal lub
niezgodnych flagach. Po decyzji właściciela ponownie wdrożyć zachowany poprzedni **smoke MTAR**
z tym samym extension; bez `--delete-services`, bez undeploy i bez pełnego MTA z HDI.
Poprzedni smoke ma znany publiczny indeks i starszy UI, więc rollback przywraca też te ograniczenia.
H2 w pamięci znika przy restarcie/restage/nowym droplecie; ponowny deploy jej **nie odtworzy**.
Nie obiecywać odtworzenia operacji, audytu, sesji ani danych INBOUND. Brak zatwierdzonej kopii
poprzedniego MTAR oznacza BLOCKED rollback, nie polecenie losowego downgrade.

## Maksymalnie pięć czynności właściciela

1. Potwierdzić w Cockpit pełny deskryptor/role XSUAA i Destination (FetchInstanceParameters niedostępne)
   oraz planowany stack/JDK25; CF/MCP i podstawowy inventory są już zweryfikowane. Nie przesyłać credentiali.
2. W istniejącym audycie H01 skorelować pojedynczy GET RAP z rzeczywistym business principal;
   jeśli narzędzia nie wystarczą, zrecenzować projekt diagnostyki przed osobną zgodą na zmianę ABAP.
3. Zapewnić zatwierdzone konta A/B bez admin/developer i istniejące fixtures dwóch zakresów;
   administrator wykonuje negatywy bez roli, DCL dzieci i sesje/logout/expiry. Agent nie nadaje ról.
4. Zrecenzować dokładny MTAR/checksumy, otwarte bramki i plan utraty H2; osobno zatwierdzić kontrolowany
   smoke redeploy/rollback oraz ewentualny staging fs5. Nie łączyć tego z SEND/WRITE lub HANA.
5. Po zatwierdzonym wdrożeniu zebrać nowy pomiar HTTP/principal/A/B i zdecydować o dalszym security sign-off;
   wspólną politykę dedicated tenant przekazać jako osobne zadanie backendu przed pełną akceptacją.

Przed PROD pozostają: pełna polityka tenantów, least privilege i LIVE DCL/principal,
trwała baza/storage z retencją/backup/DR, aktualny wspierany stack/buildpack oraz odrębne,
autoryzowane testy KSeF/SAP. Ten smoke nie jest wdrożeniem produkcyjnym.
