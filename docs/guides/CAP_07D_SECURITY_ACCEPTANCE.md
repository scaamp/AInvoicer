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
| C05 | Obce audience (także syntetyczne H01) → 401 | PASS | BLOCKED: nie pozyskiwano realnych obcych tokenów |
| C06 | Obcy tenant + obce audience → 401 | PASS | BLOCKED |
| C07 | Obcy tenant przy zaufanym podpisie i własnym audience → odmowa | Inbox **PASS403**; starsze projekcje **KNOWN GAP** | NOT TESTED |
| C08 | ≥2 NIP i ≥2 spółki → fail closed; inbox wymaga jednej pary | PASS | BLOCKED |
| C09 | Własny/obcy key, `$count`, OR/filter nie rozszerza widoczności | PASS na niepustych fixtures | BLOCKED; puste smoke nie dowodzi izolacji |
| C10 | Prywatny staging/granty/writeback i techniczne API niedostępne | PASS także z operatorem | **PASS10/10** anonimowo |
| C11 | Brak publicznego indeksu CAP | PASS nowego JAR | **FAIL**, `/`200 z kontraktem |
| C12 | Błędy 401/403/404 bez stack trace/bindingów/danych | PASS | Bezpieczne anonimowe odpowiedzi; auth403/404 NOT TESTED |
| R01 | Świeża anonimowa sesja AppRouter → login/401, bez danych CAP/RAP | Test tras PASS | **PASS5/5** (XHR401; bez cookie jar) |
| R02 | CAP JWT wyłącznie do CAP; RAP przez `AINV_H01_UI_USER` | PASS struktury tras/destinations | Runtime config BLOCKED; relacja 10 listopada w as-built |
| R03 | GET RAP obsługiwany przez business principal H01 | Nie do udowodnienia mockiem | **BLOCKED**, patrz niżej |
| R04 | User bez Business Role → 403 lub count0 także dla dzieci | Harness + procedura | **BLOCKED**, brak sesji kontrolnej |
| R05 | A/B, obcy NIP, BUK i bezpośrednie dzieci DCL | CAP JWT A/B PASS; **nie jest testem DCL SAP** | **BLOCKED** |
| R06 | Nowe logowanie w osobnej sesji przeglądarki | Harness przygotowany | NOT EXECUTED; historyczny functional PASS10 listopada |
| R07 | Logout i idle expiry15min → ponowny GET niedostępny | Helper/testy PASS | BLOCKED; nie kończono sesji właściciela |
| F01 | Profil CF, XSUAA/mock/HANA guards, SEND/WRITE/sweeper/auth OFF guards | Testy executable JAR | CF env BLOCKED (wygasłe logowanie) |
| F02 | Inbox runtime nie uruchamia synchronizacji; UI CF disabled | PASS | Starszy smoke, nie INT-02 |
| F03 | cflinuxfs5 + system buildpack/JDK25 w naszym org/space | Kolektor gotowy | **BLOCKED**, nie odczytano aktualnego inventory |

Zbiorczy wynik HTTP: **25 PASS / 1 FAIL**, 11 listopada, ok.08:13 CET.
[Raport z bezpiecznymi request ID](../../deploy/evidence/2026-11-11-public-read.json).
Indeks: `x-vcap-request-id=c6399869-6f56-45fa-54ba-2a0cd3f6d90c`, HTTP200,
`contractExposed=true`. Nie zapisano body, cookie, redirect URL ani Authorization.
Żaden z tych anonimowych testów nie jest dowodem autoryzowanego żądania do H01.

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
Zwraca tylko statusy/liczności/request ID; dane i cookies pozostają w pamięci przeglądarki.

Po **świadomym** logout `/do/logout` albo pełnym idle expiry bez ruchu przez15min wykonać
`await ainvSecurityAcceptance.deniedAfterSessionEnd()` w tej samej sesji/originie.
401 albo opaque redirect potwierdza odmowę AppRoutera. Błąd sieci daje BLOCKED; ponowne200 daje FAIL.
Nie dowodzi to globalnego logout IdP; nowe SSO może ponownie uwierzytelnić użytkownika.
Nie używać auto-refresh/pollingu podczas pomiaru idle expiry. Nie eksportować HAR/cookies/tokenów.

## Principal H01, IAM i negatywna macierz DCL

**Principal H01: BLOCKED.** `abapfs_odata_resolve_service(H01,ZUI_AINV_INV_H_O4)` i odczyt istniejących
trace runs zwróciły503. Dostęp ADT/MCP nie dowodzi tożsamości trasy AppRoutera. CF login wygasł.
Browser host narzędzia nie był dostępny; oba znalezione okna Chrome były zminimalizowane i odczyt
stanu CUA się nie powiódł. Nie uruchamiano formularzy logowania i nie przejmowano credentiali.
[Raport CF BLOCKED](../../deploy/evidence/2026-11-11-cf-readiness.json).

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
| Commit/droplet | Starszy smoke9 listopada wg właściciela; hash dropletu nieodczytany | Źródła main f093af5 + CAP-07D; hashe w release-evidence |
| CAP `/` | **200 z indeksem**, potwierdzone dziś | Wyłączony index/Fiori preview, executable JAR test |
| INT-02 | Niewdrożony wg właściciela | Read model inbox w JAR, prywatny staging niewystawiony |
| UI | Historyczny smoke | CF rap, Inbox disabled, wszystkie capabilities i external services OFF |
| Usługi | XSUAA/Destination wg10 listopada | Tylko dwie `existing-service`, bez create/update XSUAA |
| DB | H2 ephemeral, brak HANA wg właściciela | Jawny smoke/H2, bez HDI/deployera i bez retencji |
| Flagi | Aktualne env BLOCKED | CF, smoke=true; SEND/INBOUND/WRITE/sweeper=false, platform=h2 |
| Stack | cflinuxfs4 wg historycznego as-built | Brak automatycznej migracji; wymagana kontrola inventory i zgoda |

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

### cflinuxfs5 / SAP Java Buildpack / JDK25

Oficjalny komunikat SAP podaje GA cflinuxfs5 na **9 listopada2026** i obsługę systemowych
buildpacków; wskazuje też rollout regionalny. **Nie jest to dowód dostępności w naszym space**.
Źródło: [SAP — migracja do cflinuxfs5](https://community.sap.com/t5/technology-blog-posts-by-sap/keep-your-apps-secure-and-supported-here-s-how-to-get-to-cflinuxfs5/ba-p/14455090).
SAP opisuje `SAPMachineJRE` i `JBP_CONFIG_SAP_MACHINE_JRE: '{ version: 25.+ }`:
[SapMachine w SAP Java Buildpack](https://help.sap.com/docs/btp/sap-business-technology-platform/sapmachine).

Aktualne `cf stacks` i `cf buildpacks` odmówiły dostępu z powodu wygasłego tokena CLI.
Kolektor sprawdza osobno dostępność fs5, włączony system buildpack/stack i rzeczywisty droplet.
Historyczne `sap_java_buildpack_jakarta2.76.0` na fs4 nie dowodzi aktualnej obsługi fs5.
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
**Nie ma pełnego LIVE release sign-off**: C07, principal/A/B i aktualny inventory pozostają otwarte.
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

1. Przywrócić własne logowanie CF CLI/H01, umożliwić read-only inventory i potwierdzić aktualne flagi,
   bindingi, stack/buildpack/JDK25 oraz konfigurację Destination; nie przesyłać credentiali.
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
