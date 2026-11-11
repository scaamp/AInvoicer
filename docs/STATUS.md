---
title: "AInvoicer — aktualny stan"
date: 2026-11-11
type: status
status: w-trakcie
tags: [ainvoicer, cap-java, status]
---

# Stan projektu

[Dziennik CAP-04 Hardening](journal/2026-10-29-cap-04-hardening.md) · [Dziennik CAP-04B — kontrakt zapisu RAP](journal/2026-10-28-cap-04b-rap-write-contract.md) · [ADR-0002](decisions/ADR-0002-sap-ksef-result-write-contract.md) · [Dziennik CAP-03 — integracja SAP H01](journal/2026-10-27-cap-03-sap-remote-integration.md)

[Project Home](README.md) · [Roadmapa](ROADMAP.md) · [Workflow abapGit](guides/ABAPGIT_WORKFLOW.md) · [Model RAP faktur](guides/ABAP_RAP_INVOICE_MODEL.md) · [Dziennik próby tworzenia ABAP FS](journal/2026-10-25-abapfs-create-probe.md) · [Dziennik kompozycji RAP](journal/2026-10-24-abap-rap-composition.md) · [Dziennik pierwszego eksportu](journal/2026-10-24-abapgit-rap-initial-export.md) · [Dziennik bootstrapu](journal/2026-10-23-cap-java-bootstrap.md) · [Weryfikacja Phase 1](journal/2026-10-24-cap-java-bootstrap-verification.md) · [Model KsefOperations](journal/2026-10-24-ksef-operations-model.md) · [Usługa i CRUD OData](journal/2026-10-24-ksef-service-definition.md) · [Walidacja i autoryzacja KsefOperations](journal/2026-10-24-ksef-operations-validation-authorization.md)

## Current Phase

**CAP-HANA-01 LIVE — zatwierdzona próba zakończona ERROR przy stagingu (2026-11-11).**
Właściciel wyraźnie zatwierdził abort starej operacji i poprawione0.7.3/SHA6a92fb78… .
**VERIFIED LIVE:** abort30401273… zakończony ABORTED; nowa operacja
`d110c171-ddf8-11f1-8764-eeee0a818911` przyjęła deskryptor, utworzyła deployer i jego binding HDI,
ale staging Node buildpack1.9.6/fs4 zakończył się `package.json: permission denied` (także3 domyślne
ponowienia narzędzia). Deployer STOPPED, bez trasy, HDI1 app binding; CAP/AppRouter pozostały
STARTED1/1 na starych dropletach, CAP bez HDI. Docelowe0.7.3/HANA **NOT DEPLOYED**.
Znaleziono nieprzenośny creator=10 w payloadach ZIP Windows oraz katalogi0666; Go archive/zip
interpretuje creator10 jako mode000. Normalizacja do Unix creator3/pliki0644/katalogi0755
zaimplementowana we wszystkich3 payloadach i walidatorze. Lokalnie31 PASS/1 Go skipped;
niezależny czytnik Go1.26.10 wymagany w CI. Czysta odbudowa4630f89 i verify obu MTAR PASS;
Python potwierdził Unix3/0644/0755 w rzeczywistych payloadach. Nowe0.7.3 SHA256
`196b9b8cc85843f08b200aba9455e6f1c8b725a9ad3b765a3f883ba68b621570`, rollback0.7.2
`00e31c977eb0d29cb86a4f6b7ecbdf115cfaf1a489f247955545b317f847f962`.
CI oraz nowa zgoda na abort d110c171…/nowy deployment PENDING.
Nie wykonano nowego abort/retry/redeploy, DML ani restartu CAP. Stan historyczny poniżej.

**CAP-HANA-01 — NO-GO, deployment odrzucony przed stagingiem (2026-11-11).**
Właściciel uruchomił zatwierdzany zestaw; operacja `30401273-ddf4-11f1-ab29-eeee0a9dd8f2`
zakończyła się ERROR podczas scalania deskryptorów: `Unsupported module type approuter.nodejs`.
**VERIFIED LIVE:** stare droplety/checksumy CAP/AppRoutera bez zmian, obie aplikacje1/1, HDI0 bindingów.
**VERIFIED OFFLINE:** poprawiono mapowanie typów projektu do typów CF w obu generatorach;
29/29 testów PASS, w tym porównanie z prawdziwym SAP MBT1.2.49 `mtad-gen` i regresja odrzucenia
typów projektowych. Oba dotychczasowe MTAR, również rollback0.7.2, są wycofane z rekomendacji:
mają ten sam defekt; zachowano oryginały/evidence w build/rejected-30401273 worktree wydania.
Odbudowa obu MTAR z czystego `e2df55a` i verify PASS (**VERIFIED OFFLINE**).
Nowe0.7.3 SHA256 `6a92fb787ccc77e1abc0b870a30ccbcba420c1d663446b628d7e3ab2ec8d935b`;
rollback0.7.2 `66014221b2219cfed68aa89502750ef67b9b65ebfa4d100fd5cb1299abcd3a69`.
NO-GO wykonawcze do zatwierdzenia abort starej operacji ERROR i nowego zestawu/SHA.
Nie wykonano retry/abort/redeploy ani DML.
Szczegóły: [dziennik](journal/2026-11-11-cap-hana-01-first-persistence.md#naprawa-po-pierwszej-próbie-deploymentu--2026-11-11).
Poniższa ocena GO i stare checksumy opisują preflight sprzed ujawnienia defektu.

**INT-03 MERGED / CAP-HANA-01 przygotowanie offline (2026-11-11).**
[PR #8](https://github.com/scaamp/AInvoicer/pull/8) scalony jako `e2696ab2bee91399423ce9565a7f467194894215`
po final review i [CI43052235875 SUCCESS](https://github.com/scaamp/AInvoicer/actions/runs/43052235875)
HEAD368314c. Poprawiono atomowość seed/cleanup harnessu, dodano4 regresje H2. Lokalnie Maven429
(423 PASS/6 Azurite skipped), CF JAR10, UI5 CF13, AppRouter10, deploy27 i oba MTAR PASS;
CI osobno zaliczył Azurite, QUnit/OPA oraz Inbox HTTP. PR #7 GitHub oznaczył merged przez wspólną historię;
nie wykonywano jego niezależnego merge. Main lokalny zsynchronizowany; nowy branch
`feature/cap-hana-01-first-persistence` bazuje na e2696ab.

**CAP-HANA-01: HDI VERIFIED LIVE / CAP HANA NOT CONNECTED / NOT DEPLOYED.**
Nowy odczyt CF potwierdził `ainvoicer-db`, `hana/hdi-shared`, `create succeeded`, 0 bindingów
(również service keys), `free=true`. Broker HDI zwraca jawny Database ID; właściciel potwierdził
zgodny ID i RUNNING w Cloud Central (**OWNER-REPORTED**). Broker HANA: `serviceStopped=false`.
Plan jest free w CF i SAP Help; Costs and Usage/indywidualna stawka PAYG pozostają **PENDING**.
CAP/AppRouter1/1 nadal na starych dropletach fs4, bez HANA; bieżący JDBC/SQL **PENDING**.
**GO do zatwierdzenia kontrolowanego deploymentu DEV**, bez zgody wykonawczej i bez DML.
Proponowany osobny extension `deploy/btp-dev-eu20-hana-first.mtaext` przypina trzy moduły do fs4;
nie zmienia istniejącego extension ani MTAR. fs4 jest deprecated; staging i migracja fs5 to osobne wyniki.
LIVE A/B/DCL/principal H01 i końcowy security sign-off pozostają **PENDING**.
Runbook zawiera konkretny MTAR/SHA, deployment, testy, rollback i outage.
Harness wymaga osobno zatwierdzonego runnera z toolchain/bindingami w sieci CF; nie jest w produkcyjnym JAR.
Kopia artefaktów między worktree została prawidłowo odrzucona przez hash wejść (49 plików tylko LF/CRLF);
główny build dodatkowo blokuje zastany JAR. Wydanie **PASS** z czystego220a512 w izolowanym worktree
`D:\AInvoicer-cap-hana-01-build`: Maven429/CF10/UI5 CF13/AppRouter10, smoke/HANA build i verify PASS.
HANA MTAR SHA256 `be7bdbe6757f80e5498cbce033e560bb49d8c4dcd552bb7c181f8218c5bf2b81`;
pozostałe manifesty/checksumy i ograniczenia opisano w dzienniku.

[Dziennik CAP-HANA-01](journal/2026-11-11-cap-hana-01-first-persistence.md) ·
[Wykonywalny runbook i bramki](guides/CAP_HANA_01_FIRST_DEPLOYMENT_RUNBOOK.md) ·
[Audyt INT-03](audits/2026-11-11-int-03-security-persistence.md) ·
[Propozycja ADR-0008, bez implementacji](decisions/ADR-0008-document-storage-dev-test-proposal.md).
Właściciel utworzył HDI ręcznie; agent wykonał tylko odczyty CF i testy offline.
[Historia provisioningu i kosztów](journal/2026-11-11-hdi-shared-provisioning.md).
Nie wykonano deploy/restart, DDL/DML HANA, SAP WRITE ani LIVE KSeF; storage i oba outbox OFF.
Następny krok właściciela: zatwierdzić dokładną operację z runbooka (MTAR, nowy extension, fs4,
przerwa aplikacji i jawne ryzyka security), potem osobno runner/testy/DML/restart.
Historyczne wpisy poniżej opisują stan z wcześniejszych dat.

**CAP-07B.2 LIVE (2026-11-10) — FUNCTIONAL PASS / SECURITY ACCEPTANCE PENDING.**
W subaccount CF EU20: XSUAA `ainvoicer-auth` (2 bindingi), dedykowana rola
`KsefOperator_5260002008_1000` i Role Collection przypisana użytkownikowi,
Destination `AINV_H01_UI_USER` z `OAuth2UserTokenExchange`. Approuter/CAP GET działa
(`InvoiceIntegrationStatus` = pusta kolekcja smoke), RAP `$metadata` i UI5 odczytują
4 syntetyczne faktury (2 zakupowe, 2 sprzedażowe) także po nowym logowaniu w osobnej
przeglądarce. H01 `ZBR_AINV_READ_1000` ma READ Restricted dla NIP 5260002008/BUK 1000,
Write No Access. `Bad credentials` / HTTP500 usunięto przez uzupełnienie Client Secret
w Destination z service key ABAP; zielony Check Connection **nie** testował OAuth.
**Otwarte:** bezpośredni principal RAP, A/B i negatywna macierz LIVE, redeploy nowszego
smoke CAP-07C/INT-02; bez HANA, SAP WRITE i KSeF SEND. Szczegóły:
[CAP-07B.2 LIVE — architektura, konfiguracja i dowody](guides/CAP_07B_2_BTP_IDENTITY_SSO_LIVE.md).

**INT-02 (2026-11-10), lokalna integracja INBOUND:** osobny worktree `D:\AInvoicer-int-02`,
branch `feature/int-02-inbound-fullstack`; historia KSEF-IN-02 i UI5-10 zachowana dwoma merge commitami.
Jawny read-only OData, grant-safe CQN przed count/paging, scope 1 NIP/1 CompanyCode + BUYER/TEST,
spółka wyłącznie jako kontekst użytkownika. Odczyt niezależny od synchronizacji; CF Inbox disabled.
Końcowy Maven **368/368 PASS**, QUnit **112/112 (659)**, OPA **21/21 (150)**,
CF build13, runner4, AppRouter8, deploy4 oraz rzeczywisty UI5→CAP16/73 PASS (A/B, 401/403/404, outage/restart).
JWT/OData29/29 obejmuje tenant i limit100 rekordów; status publikacji/CI: [dziennik INT-02](journal/2026-11-10-int-02-inbound-fullstack.md).
[PR #5](https://github.com/scaamp/AInvoicer/pull/5) i [PR #6](https://github.com/scaamp/AInvoicer/pull/6)
**MERGED do main** po zleceniu właściciela: kolejno `3e7420d` i `21c2e48` (merge commits, historia zachowana).
**CI SUCCESS dla efc6a91**: [Ubuntu24.04 run42897303774](https://github.com/scaamp/AInvoicer/actions/runs/42897303774),
włącznie z Maven368, QUnit112, OPA21 i realnym UI5→CAP16/73. Drzewo merge `21c2e48` jest identyczne
z przetestowanym INT-02; `D:\AInvoicer` przełączono na main i zaktualizowano przez `git pull --ff-only`.
Automatyczny CI po merge/późniejszym commicie dokumentacyjnym ma własny wynik w Actions.
MTAR67 511 817B + CF JAR8/8 PASS, bez wdrożenia; bieżące [GitHub checks](https://github.com/scaamp/AInvoicer/pull/6/checks).
[Kontrakt/runbook](guides/INT_02_INBOUND_FULLSTACK.md) · [Security review](audits/2026-11-10-int-02-security-review.md).
Bez deployu, zmian BTP/H01 i LIVE KSeF; trwałość HANA/storage oraz SSO A/B pozostają odrębnymi bramkami.

### Poprzedni stan — INT-01

**INT-01 (2026-11-09), LOCAL + CI PASS dla kodu `18ca64e`:** worktree `D:\AInvoicer-int-01`, merge CAP-07C `e850a69` + KSEF-IN-01 `344a67b` bez konfliktów, historia zachowana. **321/321 Maven** (77 INBOUND + 243 CAP-07C + 1 test izolacji), CF JAR 8/8, QUnit 79/446, OPA 13/80, runner 4/4, UI5 CF 8/8, AppRouter 8/8, deploy 4/4; MTAR **67 366 302 B**, niewdrożony. INBOUND bez beanów/schedulera/endpointów i zmian DB; security review bez blokera integracyjnego. [PR #3](https://github.com/scaamp/AInvoicer/pull/3) OPEN, mergeable przy odczycie; [Ubuntu CI PASS](https://github.com/scaamp/AInvoicer/actions/runs/42685746135). Zmiany dokumentacyjne po tym run mają własne [bieżące checks](https://github.com/scaamp/AInvoicer/pull/3/checks); dokładne SHA/wyniki w [dzienniku](journal/2026-11-09-int-01-branch-integration.md). [Audyt PR #1 i plan merge](audits/2026-11-09-int-01-integration-review.md). Bez merge main, CF deploy, SAP WRITE i LIVE KSeF. Następne: owner review, CAP-07B.2 (SSO) i KSEF-IN-02 (trwały staging/checkpoint).

**CAP-07C (2026-11-09), LOCAL PASS:** powtarzalny `npm run build:btp:smoke --prefix deploy` → MTAR 67 295 265 B (bez HDI i nowych usług); Maven 243/243, executable JAR CF 8/8, QUnit 79 testów/446 asercji, OPA 13/80, runner 4/4, CF artefakt 8/8 i approuter 8/8. AI/Scanner/Voice/TTS w CF OFF + CSP; harness CAP-04D chroni seed i wymaga nowego fixture, consent oraz NIP/spółki. CI zaimplementowane i zweryfikowane lokalnie; zdalny wynik na moment wpisu **NOT EXECUTED**, nie PASS. [Dowody/komendy/ograniczenia](journal/2026-11-09-cap-07c-autonomous-hardening.md). Własny LIVE READ CF: obie aplikacje 1/1 i XSUAA/Destination, brak HANA; obecny CAP `/` 200 (fix niewdrożony), metadata/H2/health 401. **CF redeploy, business-user SSO, SAP WRITE i KSeF SEND NOT EXECUTED.** Następny etap: CAP-07B.2, zgoda na redeploy → Role Collection → Destination/SSO i A/B; CAP-04D nadal osobna bramka.

### Historia CAP-07B

Publikacja CAP-07C: implementacja `38fea84`, poprawki testów do `ca3739a`, [PR #2](https://github.com/scaamp/AInvoicer/pull/2) OPEN/mergeable względem CAP-07B, bez merge. **CI Ubuntu PASS dla `ca3739a`** — [run 42671257855](https://github.com/scaamp/AInvoicer/actions/runs/42671257855): Maven 243/243, executable JAR CF 8/8, MTAR, oba buildy UI5, QUnit 79/446, OPA 13/80, runner 4/4 i AppRouter 8/8. Dwa fixture timestampów (lease i mock Retry-After) mają jawne 100 ns, bez zmiany asercji i kodu produkcyjnego; lokalna regresja obu klas 37/37. To testy offline na prawdziwym runnerze GitHub, nie LIVE BTP/SAP/KSeF.

**CAP-07B LIVE BTP — etap 1 (2026-11-08): BLOCKED na logowaniu CF właściciela i warunkowej zgodzie na deploy; nic nie wdrożono.** Branch `feature/cap-07b-live-btp` z CAP-07.1 `c9a8345` + merge `main` `552fdb9` (`docs/architecture/` i 2 SVG zachowane, konflikt tylko w `docs/README.md`). Inventory BTP: API `cf-eu20-001` osiągalne (CC v3 3.233.0), portable **cf 8.19.0** zainstalowany lokalnie, ale `Not logged in` → subaccount, entitlementy (xsuaa, destination, HANA Cloud free, hdi-shared), quota CF i SapMachine 25 **NOT VERIFIED**; `btp` CLI niezainstalowany (EULA właściciela). Konto free tier (nie trial) → HANA wyłącznie w planie bezpłatnym, inaczej STOP.

Wyniki: **Maven clean verify 236/236** + JAR 75 MB, **QUnit 77/77 (442), OPA 13/13 (80)**, CF build 7/7, runner 3/3, approuter **7/7** po poprawce testu (regex zależny od CRLF dawał fałszywy FAIL na świeżym checkout Windows); MTAR `ainvoicer_0.7.1.mtar` **67 MB** poprawioną procedurą `module-build`/`assemble` (pierwsza próba 134 MB z duplikatem katalogu tymczasowego), zawartość i flagi zweryfikowane, sekrety 0. H01 LIVE (MCP): RAP UI 4/9/6, `ApplyKsefResult` + `etag master LocalLastChangedAt` + `Z_AINV_KSF` w aktywnym BDEF. SSO wariant A spójny z dostępnymi źródłami (approuter: nie łączyć z tokenami IAS), dowód tylko LIVE. XSUAA/AppRouter/SSO/HANA/A/B/negatywne LIVE: **BLOCKED**; drugi użytkownik B nie istnieje. CAP-04D **BLOCKED** (arrangement/ACTVT16/CSRF). SAP WRITE i KSeF SEND **NOT EXECUTED**. [Dziennik](journal/2026-11-08-cap-07b-btp-live-deployment.md).

**2026-11-09 — PIERWSZY DEPLOY LIVE (identity-smoke, zgoda właściciela, koszt 0):** `ainvoicer-srv` i `ainvoicer-approuter` started na `cf-eu20-001` (hosty `ainvoicer-v2xzij9s-*`), XSUAA `ainvoicer-auth` + destination `ainvoicer-destination` (free), bez HANA. SapMachine 25 w `sap_java_buildpack_jakarta` 2.76 PASS. LIVE: CAP anonim/śmieciowy/sfałszowany/`alg=none`/wygasły token/Basic → 401, `requestSend` anonim 403; approuter → strona przekierowania XSUAA eu20, XHR CAP/RAP/`/sap` 401, cookies HttpOnly+Secure. Naprawione defekty wykryte na CF: hosty/xsappname z `${org}` (spacje), `~{srv-url}` w approuterze (crash), strona startowa CAP `/` anonimowo 200 (poprawka w repo, niewdrożona), smoke bez `SPRING_SQL_INIT_PLATFORM`. Następnie: role collection + Destination `AINV_H01_UI_USER` (właściciel) → logowanie i SSO H01.

**Kontynuacja 2026-11-09 (inventory CF, odczyt):** org `National Center for Cryptography_dev-v2xzij9s`/`dev`, `FREE_QUOTA` 4 GB, płatne plany dozwolone. xsuaa/application i destination/lite free; **brak `hana`/`hdi-shared` i `hana-free`** (tylko płatne `hana-cloud`) → deploy z HANA **BLOCKED (entitlement/koszt)**; ścieżka bezkosztowa: entitlement free tier HANA (dostępność w EU20 TO VERIFY) albo identity-smoke po zgodzie.

### Poprzedni stan — CAP-07.1

**CAP-07.1 Deployment Readiness (2026-11-08): artefakty CF zbudowane i zweryfikowane LOKALNIE; deploy, LIVE XSUAA i SSO H01 NOT EXECUTED; entitlementy NOT VERIFIED.** Branch `feature/cap-07-1-deployment-readiness` z CAP-07 `829725b` (stos: PR #1 OPEN/clean → **READY FOR OWNER MERGE** ← CAP-07 ← CAP-07.1; bez merge). Naprawiono: profil `cf` bez bazy startował na cichej `jdbc:h2:mem` → `CloudPersistenceGuard` + `cds-feature-hana` + HDI w MTA ([ADR-0007](decisions/ADR-0007-cap-persistence-hana-cloud.md)); opcjonalny opt-in identity-smoke (KSeF/writeback/sweeper OFF, zapisy 409), nieobecny w deskryptorach. `redirect-uris` XSUAA zawężone z wildcard domeny CF do hosta approutera. UI5: `npm run build:cf` → `dist-cf` (`dataMode=rap`, capabilities OFF, Legacy/flex OFF, bez testów/fixture, weryfikacja automatyczna); manifest developerski nadal `mock`. Issuer: `java-security` 4.1.2 nie waliduje `iss` dla XSUAA, `jku` ignorowany — kotwica `uaadomain`+podpis+audience (ADR-0006 skorygowany).

Wyniki: **Maven clean verify 236/236 + executable JAR** (izolowana kopia; procesy 8080/8085/8086 z poprzednich sesji nietknięte), start JAR `cf` bez XSUAA / bez HANA → odmowa startu, `default` → 401; **QUnit 77/77 (442), OPA 13/13 (80)**, runner 3/3, CF build 7/7, approuter 7/7 + runtime (logowanie/401, 0 wywołań backendów); `mbt build` BLOCKED (brak make) → `mbt module-build` + `assemble` PASS (`ainvoicer_0.7.1.mtar`, bez sekretów). SSO H01: **TECHNICALLY SPECIFIED, wariant A `OAuth2UserTokenExchange`** — właściciel potwierdził jeden subaccount (CF `cf-eu20-001` + ABAP environment `dev` = H01, free tier). CAP-04D READ-ONLY: bindingi published, 4 nagłówki bez zapisów, `Z_AINV_KSF` istnieje; arrangement/ACTVT16/CSRF — admin. SAP WRITE i KSeF SEND **NOT EXECUTED**, flagi bez zmian. [Dziennik](journal/2026-11-08-cap-07-1-deployment-readiness.md) · [Runbook wdrożenia](guides/BTP_FIRST_DEPLOYMENT_RUNBOOK.md).

### Poprzedni stan — CAP-07

**CAP-07 BTP Identity (2026-11-08): XSUAA w CAP + approuter przygotowane i przetestowane OFFLINE; LIVE BTP BLOCKED (admin).** Branch `feature/cap-07-btp-identity` z `ed35935` (PR #1 nadal OPEN, clean → **READY FOR OWNER MERGE**, bez merge). Decyzja [ADR-0006](decisions/ADR-0006-btp-identity-xsuaa.md): XSUAA `application`/`dedicated`, `cds-feature-identity` + `resourceserver-security-spring-boot-starter` 4.1.2, deskryptor `cap-java/xs-security.json` (scope `KsefOperator`, wymagane atrybuty NIP/spółka przypięte do roli). `KsefScopeUserInfoProvider` zamyka dostęp przy ≥2 NIP **i** ≥2 spółkach (para krzyżowa nie jest dozwolona); `CloudIdentityGuard` blokuje start na CF bez jednego bindingu XSUAA lub z mock. Approuter 23.4.0 + `mta.yaml` (niewdrożone).

Wyniki: **Maven 230/230** (+27; build bez repackage JAR, bo działający CAP 8080 blokuje `cap-java-exec.jar`), **OFFLINE JWT CONTRACT 10/10** (prawdziwy stos SAP, syntetyczny binding/klucz), approuter statycznie 4/4 i runtime z syntetycznym XSUAA PASS, UI5 build/runner3/QUnit76 (438)/OPA13 (80) PASS na istniejącym serwerze mock; `npm ci` UI5 NOT EXECUTED. Zaobserwowano: XSUAA `iss` nie jest walidowany przez bibliotekę (kotwica = JKU + podpis + audience). **LIVE XSUAA/IAS, business-user SSO CF → H01, CF deploy: BLOCKED/ADMIN.** CAP-04D preflight read-only: binding/READ/`Z_AINV_KSF` potwierdzone, arrangement/ACTVT16/CSRF niezweryfikowane. SAP WRITE i KSeF SEND **NOT EXECUTED**; flagi bez zmian. [Dziennik](journal/2026-11-08-cap-07-btp-identity.md) · [Przewodnik administratora](guides/BTP_IDENTITY_SETUP.md).

### Poprzedni stan — CAP-06.1

**CAP-06.1 hardening (2026-11-08): lokalny kod gotowy do review main.** H2 console globalnie OFF, opt-in tylko `h2-console-local` na loopback bez CF/PROD/proxy; domyślni privileged/system/authenticated OFF we wszystkich profilach, mock auth OFF dla cf/cloud/prod/production. Dodano regresję ochrony H2 i braku kont, izolację/redirecty Basic Auth runnera, walidację MaxLength/dat/waluty AI i bezpieczne trasy CAP. ADR-0005 i wszystkie transporty/capabilities pozostają bez zmian/OFF.

**Publikacja:** kod `ed66718` wypchnięty na `feature/cap-06-ui5-09-dual-backend`; **[PR #1](https://github.com/scaamp/AInvoicer/pull/1) OPEN, gotowy do review**, bez konfliktów z main141bda4. Późniejszy commit uzupełnia wyłącznie dokumentację publikacji. **Merge i auto-merge NOT EXECUTED.**

Własne wyniki końcowe: **Maven203/203**, **QUnit76/76 (438 asercji)**, **OPA13/13 (80)**, runner3/3, build standardowy i lokalny RAP PASS. **LIVE READ:** CAP RemoteService1/1, RAP metadata200/4/9/6, dual-backend10/10 (63). Runtime CAP metadata/status200,0 operacji, negatywne auth401/403 i H2 OFF PASS. **Smoke FLP FAIL/exit1 — KNOWN BLOCKER** (Legacy503, flex/LREP, MessageBroker), mimo4 faktur i CAP dostępnego z lokalnym principalem. Tooling npm nadal74 advisories. **CAP-07 READY TO START; IAS/XSUAA NOT IMPLEMENTED, CAP-04D BLOCKED, SAP WRITE/KSeF SEND NOT EXECUTED, A/B i serializacja3 UI DCL nadal otwarte.** [Pełny audyt i polecenia](journal/2026-11-08-cap-06-1-hardening.md). Poniższe wyniki CAP-06 są historią wcześniejszej sesji.

**CAP-06/UI5-09 (2026-11-08): odczyt dwóch rzeczywistych backendów potwierdzony lokalnie; CAP user IAS/XSUAA nadal BLOCKED.** SAPUI5 na 8086 czyta H01 przez business-user reentrance proxy `/rap-ui`; CAP Java na 8080 zwraca rzeczywistą, pustą historię z osobnej H2 w pamięci (0 operacji). Odczyt CAP jest uwierzytelniany lokalnym mock principalem `cap06-reader`, nie IAS. Test pełnej aplikacji w przeglądarce: **10/10 (63 asercje)**, w tym dialog szczegółów, filtry numer/NIP/data/spółka/kwota z walutą, sort/paging/count i symulowane awarie CAP. Bez dodatkowego uwierzytelnienia zwykła karta użytkownika nadal otrzyma CAP401; sesja RAP nie nadaje dostępu do CAP.

**LIVE H01:** oba bindingi published/V4/0001 i metadata200; UI i Web API **4/9/6**. Rola `ZBR_AINV_READ_1000` ma jedno przypisanie do `CB9980000000` (własny READ CDS IAM). **CAP RemoteService → H01 LIVE PASS: 1/1**, istniejąca lokalna Destination `AINV_H01` / Basic system-to-system, osobny proces testu; bez cookie użytkownika. Potwierdzono keyed READ, UUID, daty, kwoty, wersję i status SAP. Brak dowodu CF Destination Service, ACTVT16 i izolacji A/B. abapGit linked `main`, 6 unstaged plików trzech UI DCL; serializacja local nadal pusta, bez eksportu/zmiany SAP.

**Poprawki runtime:** filtr konsoli H2 rejestruje się tylko przy włączonej konsoli; domyślni mock users `privileged/system` OFF. Adapter CAP negocjuje OData4.0 (`@odata.count` zamiast domyślnego4.01 `@count`). RAP filtry nie używają nieobsługiwanego `tolower`, pomijają niemożliwe gałęzie z literałem ponad MaxLength, a filtr kwoty wymaga waluty. Brak zmian architektury ADR-0005, domyślny manifest nadal mock, Send i transporty OFF.

**Regresja:** Maven **186/186**, QUnit **72/72 (420)**, OPA **13/13 (80)**, build aplikacji i lokalnego RAP PASS. Smoke FLP **FAIL (exit1)** mimo widocznych 4 faktur i Send OFF: zastany Legacy host `/sap` zwraca503, występują też błędy flex/MessageBroker podglądu. AI/Scanner/Voice/Legacy zaliczone w regresji mock; ich backendy LIVE niezaliczone. CAP-04D **BLOCKED**: administrator musi potwierdzić ograniczone ACTVT16/arrangement i wskazać oddzielną syntetyczną fakturę; WRITE/SEND **NOT EXECUTED**. [Dowody i kolejny krok](journal/2026-11-08-cap-06-ui5-09.md).

### Poprzedni stan — ABAP-08

**ABAP-08 stan końcowy (2026-11-07): rzeczywista lista faktur z H01 widoczna w SAPUI5 przez RAP OData V4 — LIVE VERIFIED, bez wysyłki KSeF.** Business Role `ZBR_AINV_READ_1000` (katalog `ZAINV_BC_READ`, Read=Restricted 5260002008/1000, Write=No Access), utworzona przez użytkownika i przypisana do `CB9980000000`. W modelu IAM „migrated” (H01 2608) restriction type musi być na IAM App `ZAINV_UI_READ_EXT`, nie na katalogu. Business-user READ przez `ZUI_AINV_INV_H_O4`: **4/9/6** (wcześniej 0/0/0 dla tego samego użytkownika). SAPUI5 (`ui5-rap.local.yaml` + ignorowana kopia `webapp.rap.local`, `dataMode=rap`): 4 faktury (2 zakupowe / 2 sprzedażowe); przy wyłączonym CAP ostrzeżenie bez ukrywania danych RAP; Send wyłączony. Poprawki: host H01 `9197a0a5-….abap.eu20` (stary `11b69711…` → 503 „System is unknown”, trasa Legacy `/sap` martwa), `@sap/ux-ui5-tooling` przypięty do **1.19.6** (reentrance ticket na hoście `abap-web` przez `/sap/public/bc/icf/virtualhost`; ≥1.20 wymaga UI5 CLI v4). Regresja: build PASS, QUnit 69/69 (408), OPA 13/13 (80). Nadal: izolacja A/B BLOCKED, szczegóły/sort/filtr w UI NOT EXECUTED, ATC NOT RE-EXECUTED, abapGit export BLOCKED, CAP-04D NOT READY, SAP WRITE / KSeF SEND NOT EXECUTED. **abapGit (2026-11-07):** wyeksportowano 25 plików warstwy RAP UI/IAM (`java-cap-ksef` `e10dc35`, scalone do `main` w `0bdda59`). 3 UI DCL są **poza eksportem**, bo SAP serializuje je pusto; źródła i ostrzeżenie: [docs/abap-dcl-reference](abap-dcl-reference/README.md). Linked branch SAP **przełączony na `main`** (ADT, użytkownik; potwierdzone MCP); status: tylko 3 UI DCL unstaged, nie stage'ować. **Scalone do `main`** (merge `153c3f3`, `--no-ff`, push bez force; repo abapGit w SAP nadal linked `java-cap-ksef`). [Dziennik](journal/2026-11-02-abap-08-ui5-08.md#kontynuacja-2026-11-07-2--business-role-utworzona-business-user-read-pass-sapui5-live-pass).

### Poprzedni stan — ABAP-08 kontynuacja (2026-11-07, przed utworzeniem roli)

**ABAP-08 kontynuacja (2026-11-07): logowanie MCP działa, Business Role NIE ISTNIEJE — jedyna bramka LIVE.** Binding UI `ZUI_AINV_INV_H_O4` **LIVE VERIFIED** (V4/0001/published, metadata 200, hash niezmieniony). UI Invoices/InvoiceItems/InvoiceReferences: HTTP200 **0/0/0**, a Web API pokazuje 4 nagłówki — wszystkie w zakresie NIP5260002008/BUK1000. To potwierdza LIVE negatywny przypadek DCL („bez roli”), ale nie pozytywny READ. Released `I_IAMBusinessRole`/`I_IAMBusinessUserBusinessRole` pokazują tylko `SAP_BR_ADMINISTRATOR` i `SAP_BR_DEVELOPER` (po 1 przypisaniu), brak roli `Z*` → administrator musi **utworzyć** rolę z `ZAINV_BC_READ` (Read=Restricted, Write=No Access, 5260002008/1000) i przypisać użytkownika. Regresja na `c7480cf`: Maven **178/178**, QUnit **69/69 (408)**, OPA **13/13 (80)**, buildy (w tym obu szablonów RAP) PASS. Odtworzono ignorowany `ui5-rap.local.yaml` (nie uruchomiony). abapGit bez zmian (33 unstaged, linked java-cap-ksef); katalog `ZAINV_BC_READ` nie jest serializowany. ATC NOT RE-EXECUTED. Business-user READ, izolacja A/B i SAPUI5 LIVE: **BLOCKED**. CAP-04D **NOT READY**, HTTP WRITE/SEND **NOT EXECUTED**. [Instrukcja utworzenia roli](journal/2026-11-02-abap-08-ui5-08.md#kontynuacja-2026-11-07--logowanie-mcp-działa-business-role-nie-istnieje).

### Poprzedni stan — ABAP-08 (2026-11-02)

**ABAP-08/UI5-08 (2026-11-02): ABAP-07 zintegrowane, regresja lokalna PASS, LIVE nadal BLOCKED na Business Role/logowaniu MCP.** Branch `feature/abap-08-live-integration` z `origin/main=6a4e2a5` + merge `origin/feature/abap-07-ui5-live` (`160130c`), bez push i bez merge do main. **LOCAL TEST PASS:** Maven **178/178**, QUnit **69/69 (408)**, OPA **13/13 (80)**, build i build szablonu RAP PASS (H2/mock; Maven poza PATH, `C:\Users\jkupi\tools\maven-dl\apache-maven-3.9.16`). **BLOCKED:** w tej sesji `odata_resolve_service` dla obu bindingów zwraca `ODATA_REQUEST_FAILED` (brak aktywnego logowania kanału OData MCP), ATC bez odpowiedzi ~27 min; Business Role/przypisanie nadal NOT VERIFIED (MCP nie czyta ról — to nie dowód braku), więc business-user READ, izolacja DCL i SAPUI5 LIVE nie wykonane. SRVB `ZUI_AINV_INV_H_O4` istnieje (ADT). abapGit: linked `java-cap-ksef`, 33 unstaged (30 UI/IAM + 3 obce), diff DCLS ponownie puste → brak eksportu. Utworzono ignorowany `ui5-rap.local.yaml` (reentranceTicket, nieuruchomiony); manifest nadal `dataMode=mock`, Send OFF. CAP-04D **NOT READY**; SAP HTTP WRITE i KSeF SEND **NOT EXECUTED**. [Dziennik z instrukcją dla administratora](journal/2026-11-02-abap-08-ui5-08.md).

### Poprzedni stan — ABAP-07/UI5-07 (2026-10-30)

**ABAP-07/UI5-07 (2026-10-30): regresja lokalna PASS, business-user READ nadal BLOCKED.** Branch `feature/abap-07-ui5-live` utworzony z aktualnego `origin/main=6a4e2a5`, czyste drzewo początkowe; branch ABAP-06 nie istnieje lokalnie ani na origin. Po odświeżeniu logowania własny MCP potwierdził aktywny/opublikowany binding UI V4/0001, metadata HTTP200 i niezmieniony hash. App/katalog/RT mają publishStatus=p, RF i assignment są zapisane; DCL nadal ACTVT03 + para NIP/CompanyCode i dziedziczenie dzieci. Nowe ATC **7/7 bez ustaleń**, brak inactive w zakresie. UI Invoices + expand oraz obie kolekcje dzieci: HTTP200/0 w developer OAuth. Web API: HTTP200, **4/9/6**; to nie jest business-user READ ani Destination CAP.

**Business Role i przypisanie pozostają niezweryfikowane, nie potwierdzono ich braku.** MCP nie udostępnia ich odczytu, a przechwycenie istniejącego okna przeglądarki dwukrotnie zakończyło się timeoutem. Następna czynność: administrator w Maintain Business Roles potwierdza efektywne przypisanie istniejącej roli z ZAINV_BC_READ, Read=Restricted, Write=No Access, NIP5260002008/CompanyCode1000, łącznie z innymi rolami użytkownika. Brakującego nadania nie wykonywano. Nie przełączono UI na RAP bez zaliczenia tej bramki.

**UI5-07:** dodano limit READ CAP (15 s, także treść odpowiedzi, AbortController, bez retry); timeout zachowuje dane RAP i blokuje Send. Maven **178/178**, QUnit **69/69 (408 asercji)**, OPA **13/13 (80)**, build aplikacji i szablonu RAP PASS. Zakres: H2/mock/lokalny HTTP, bez LIVE CAP usera, AI/OCR/STT i Legacy backendu. Domyślne mock/Send OFF i flagi transportów bez zmian. SAP abapGit nadal java-cap-ksef, status33 pliki (3 spoza zakresu); ponownie puste źródło/XML DCL w MCP diff. Brak eksportu SAP. CAP-04D: checklista przygotowana, **NOT READY**, bez SEND i HTTP WRITE. [Pełny raport i blokery](journal/2026-10-30-abap-07-ui5-07.md) · [READ i routing](guides/RAP_UI_LIVE_CONNECTION.md).

### Poprzedni stan — ABAP-06/UI5-06

**ABAP-06/UI5-06 — konfiguracja IAM gotowa do etapu roli biznesowej (2026-10-30).** Użytkownik wykonał zmiany w ADT; własny READ MCP potwierdził aktywne domeny ZAINV_D_NIP CHAR10 / ZAINV_D_BUK CHAR4 i oparte na nich DTEL ZAINV_NIP/ZAINV_BUK, zapisane RF, wspólny ZAINV_RT_READ, aplikację ZAINV_UI_READ_EXT, katalog ZAINV_BC_READ i assignment ZAINV_BC_READ_0001. App ma usługę OData V4 ZUI_AINV_INV_H_O4 i Z_AINV_KSF/ACTVT03/NIP5260002008/BUK1000. Katalog udostępnia RT dla Read=true, Write=false, F4=false; App/katalog mają publishStatus=p. Wartości objęte RT mogą być zastępowane przez wartości roli — nie traktować App jako nieprzekraczalnego ograniczenia.

**Binding UI działa:** active/V4/0001/published=true, ścieżka `/sap/opu/odata4/sap/zui_ainv_inv_h_o4/srvd/sap/zui_ainv_inv_h_o4/0001/`. Metadata wcześniej HTTP200, encje Invoices/InvoiceItems/InvoiceReferences. Najnowszy READ Invoices z dwoma expand przez developer OAuth MCP: **HTTP200, 0 rekordów**. Nie potwierdzono roli biznesowej ani przypisania użytkownika, efektywnej izolacji A/B i SAPUI5 LIVE; **to pozostała bramka**. Nadania ABAP i CAP są odrębne.

**Najbliższy krok:** Maintain Business Roles H01 — potwierdzić/przygotować rolę z katalogiem ZAINV_BC_READ, Read=Restricted, konkretnymi NIP5260002008/spółka1000 i przypisaniem business usera; następnie nowy login i macierz READ. Nie nadawano ról przez agenta, nie wykonywano SEND/HTTP WRITE ani eksportu/pushu SAP. [Model obiektów i zależności](guides/RAP_UI_IAM_MODEL.md) · [Instrukcja i testy LIVE](guides/RAP_UI_LIVE_CONNECTION.md) · [Pełna historia sesji](journal/2026-10-30-abap-06-ui5-06.md).

**Regresja aplikacji z wcześniejszego przebiegu tej sesji:** Maven178/178, QUnit61/61 (354 asercje), OPA13/13 (80), oba buildy PASS; H2/mock. Kontynuacja IAM/dokumentacji nie powtarzała tych testów. Frontend pozostaje dataMode=mock, sendInvoice=false, transporty KSeF i SAP write OFF. Brak pełnego eksportu UI/IAM do abap/src; wcześniejsza serializacja MCP zwracała puste DCL. Dokumenty wyjaśniają aktualny stan SAP, nie są snapshotem abapGit. [E2E-01 przygotowany, nie wykonany](guides/E2E_01_SAP_TO_KSEF_TEST.md).

Poprzednie stany 404, brak domen i niekompletna App są zachowane chronologicznie w dzienniku; zastępują je powyższe własne odczyty. Opis poniżej dotyczy starszych sesji.

### Poprzedni stan — 2026-10-29

Poniższe wyniki opisują poprzednie sesje; bieżące wyniki i decyzja o zachowaniu aliasów znajdują się powyżej.

**Główna gałąź rozwoju: `main` (2026-10-29).** Na zlecenie użytkownika scalono ABAP-05/CAP-05.1/UI5-05 do `java-cap-ksef` (`c21f946`), a tę linię promowano do `main` przez fast-forward, razem z aktualizacją instrukcji. Oryginalny `main` zachowano lokalnie i na origin jako `archive/original-ainvoicer-2026-10-29`, dokładnie `b3e10b0cc4f2f17e0d98e0203303cb941606814c`. Nowe feature zaczynają się z `main`. Nie przełączano gałęzi abapGit w SAP. [Historia operacji i weryfikacja](journal/2026-10-29-main-promotion.md).

**ABAP-05 + CAP-05.1 + UI5-05 (2026-10-29), `feature/abap-05-real-rap-ui`, baza `9e4ac94`:** fetch potwierdził CAP-05 na bieżącym java-cap-ksef. Poprawiono konflikt YAML/ADR: retry po REJECTED domyślnie OFF, jawna konfiguracja nadal działa; max-attempts walidowane 1..10. Pełny Maven clean verify **178/178 PASS** (H2/mock, LIVE tagi wykluczone). UI5 blokuje Send również przy wadliwej odpowiedzi HTTP200 CAP, brakującym/niespójnym count, brakującym stanie operacji i obcych UUID. Build i build szablonu RAP PASS; **QUnit 59/59 (346 asercji), OPA 13/13 (80)**.

**H01 — wynik własnej inspekcji:** istnieją aktywne trzy CDS `ZC_AINV_INV_*_UI`, trzy odpowiadające DCL i SRVD `ZUI_AINV_INV_H_O4`; nie tworzono ani nie aktywowano obiektów SAP. **ATC siedmiu obiektów: 0 ustaleń.** DCL sprawdza ACTVT03/NIP/CompanyCode, także dzieci przez `_Parent`; izolacja dwóch business userów pozostaje NOT TESTED. SRVD ma aliasy InvoiceItems/InvoiceReferences; patch ADT przygotowuje docelowe Items/References. Binding V4 UI: **BLOCKED (404, MCP tworzy tylko V2)**, brak faktycznego URI UI. Web API READ ponownie HTTP200, **4/9/6**, zgodne UUID; nie oznacza CAP Destination ani UI LIVE. Brak env destinations/VCAP_SERVICES; BTP destination `abap`/AINV_H01 i tożsamości niezweryfikowane. `dataMode=mock`, transporty KSeF i SAP write OFF. LIVE SEND/HTTP WRITE/IAM/deploy **NOT EXECUTED**.

**Najbliższy krok:** ADT — reuse aktywnego zestawu, review aliasów i utworzenie/publikacja V4 UI; administrator — ograniczone IAM dwóch użytkowników; następnie READ i routing z [przewodnika połączenia](guides/RAP_UI_LIVE_CONNECTION.md). [Dziennik sprintu, macierz i blokery](journal/2026-10-29-abap-05-real-rap-ui.md). Historyczne patche `ZC_AINV_UI_*` nie są zestawem do ponownego wdrożenia.

### Poprzedni audyt CAP-05

Opis poniżej dokumentuje wcześniejszy przebieg; aktualny stan i ponowione wyniki podano powyżej.

**CAP-05 — audyt i uzupełnienie (2026-10-29), `feature/cap-05-integration` (baza audytu `2e0ee37`) + poprawki audytu:** kod A+B scalony (`f49153b`), runner C przeniesiony (`0cd7440`). Po audycie użytkownik osobno zlecił commit i push gałęzi; wpis publikacji w dzienniku CAP-05. CAP: requestSend z atomową rezerwacją DB per faktura/TEST, role i NIP/CompanyCode, dokładne FA(3)/snapshot/outbox; ACCEPTED commitowany przed osobną transakcją writebacku, sweeper odtwarza brakujące zlecenia tylko dla SAP_RAP. Naprawiono historyczne NEW/FAILED/nieznane SEND, izolację odczytu spółki, duplikat 440, dowody recovery i maskowanie błędów SAP. **Clean verify: 173/173 PASS**, H2/mock, LIVE tagi wykluczone. UI5: named RAP V4 model, paging/filter/sort, priorytet RAP, blokada Send przy braku/ucięciu historii CAP; build i browser testy PASS (szczegóły raportu). AI/Scanner/Legacy: OPA 13/13 PASS.

**Bramka live:** RAP UI / DCL / IAM **BLOCKED**. Przygotowano odrębne read-only CDS/DCL/SRVD i patch testów edycji/usunięcia; nie aktywowano ich w SAP. MCP 2.13.3 nie oferuje tworzenia bindingu V4, a zakres business usera wymaga administratora. Web API H01 READ przez MCP OAuth ponownie HTTP200, 4/9/6; root DCL `where TRUE` nie jest izolacją. Domyślny UI pozostaje mock. Nowy KSeF LIVE SEND i H01 HTTP WRITE **NOT EXECUTED**. [Pełna macierz i blokery](audits/2026-10-29-cap-05-implementation-review.md), [dziennik](journal/2026-10-29-cap-05-integration-review.md), [ADT/IAM](patches/cap05-rap-ui/README.md).

Poniższe wyniki CAP-04 i agentów są historią, nie bieżącym licznikiem testów.

Phase 3 — SAP Remote Integration: **CAP-04B opublikowany i CAP-04C zaimplementowany.** SAP `ApplyKsefResult` aktywna; eksport `525e48d`. CAP ma Write Adapter, niezmienne polecenie, persistent outbox/worker i historię. Pełny build 46/46 PASS; uzupełniający przebieg ochrony modelu 2/2 oraz LIVE READ H01 1/1 PASS (4/9/6). Test restartu plikowej H2 i rzeczywistego collectora PASS z mockiem SAP. **HTTP WRITE nadal nie wykonano**, transport domyślnie wyłączony, prawo SAP bez nadania. [CAP-04C](journal/2026-10-28-cap-04c-sap-result-writeback.md), [ADR-0003](decisions/ADR-0003-cap-sap-result-writeback.md).

CAP → SAP H01 READ ma **LIVE PASS**, ponownie potwierdzony 2026-10-28 po imporcie EDMX: lokalny CAP, Destination przez env `destinations`, 4/9/6 i zgodne UUID. CAP na Cloud Foundry z Destination Service pozostaje nieprzetestowany. Nie testowano dwóch równoległych zapisów akcji SAP, commit/ETag ani praw użytkownika komunikacyjnego do zapisu. CAP-04D wymaga osobnej zgody.

**CAP-04 Hardening (2026-10-29), commit `dfd1ecb`:** claim/finalize bez blokady podczas HTTP, `RETRY_EXHAUSTED`, kontrolowane resume, migawka SHA-256. Historyczny build **71/71 PASS** (H2/mock). Pierwsza sesja miała `ECONNREFUSED`; informacje o stanie SAP były wtedy NOT VERIFIED. Poprawiono historyczne „bez commit” po sprawdzeniu zawartości i metadanych `dfd1ecb`; nie weryfikowano remote.

**FA(3) — Agent B (2026-10-29, `feature/ksef-fa3`):** komponent `customer.cap_java.ksef.fa3` generuje deterministyczny XML podstawowej faktury VAT, waliduje go oficjalnym XSD FA(3) 1-0E (offline) i regułami domenowymi (sumy, NIP) oraz zwraca bajty + SHA-256 dla `KsefInvoiceSnapshotRegistry`. Testy FA(3) 30/30, pełny clean verify **112/112 PASS** (H2/mock). KSeF TEST NOT CALLED. NIP-y seeda H01 mają błędną sumę kontrolną — do FA(3) użyto osobnego fixture. [Przewodnik](guides/FA3_XML_GENERATION.md), [dziennik](journal/2026-10-29-agent-b-fa3.md).

**CAP-04 Hardening Fix 2 (kontynuacja 2026-10-29):** rewizja CAS dla resume/claim, precyzyjny test odmowy CRUD (zdalny HTTP403 + uzgodniony kod SAP), timeouty klienta Cloud SDK i odnawianie lease podczas HTTP. Szczegółowe wyniki i ograniczenia w [kontynuacji dziennika](journal/2026-10-29-cap-04-hardening.md#kontynuacja--hardening-fix-2-i-cap-04d-readiness). Transport WRITE nadal OFF; CAP-04D **IMPLEMENTED / NOT LIVE TESTED**. MCP częściowo działa: sześć CDS i root BDEF active zgodne z Git; dostęp do include/DCL/runtime odrzucony przez automatyczną kontrolę uprawnień. IAM, aktywacja uprawnienia, CBA poza zakresem i runtime WRITE **NOT VERIFIED**. CAP-03 LIVE READ w tej sesji **NOT TESTED** (brak env Destination); lokalna regresja READ objęta buildem. [Procedura CAP-04D](guides/SAP_WRITE_ACCESS_AND_READ_POLICY.md#5-cap-04d--gotowość-po-fix-2).

**SAPUI5 KSeF Cockpit — Agent C (2026-10-29, branch `feature/ainvoicer-ui5-ksef`):** kokpit faktur w istniejącej aplikacji UI5 (All/Purchase/Sales/Legacy, akcje z regułami dostępności, szczegóły, adapter AI) w trybie **demo/mock**; build PASS, QUnit 44/44, OPA 13/13. Integracja z CAP NIE TESTOWANA — wymaga luk G-01/G-02. [Przewodnik](guides/SAPUI5_KSEF_COCKPIT.md), [dziennik](journal/2026-10-29-agent-c-ui5.md).

## Architecture

**2026-10-29, Agent A (`feature/ksef-auth`):** oficjalny SDK MF 3.0.29 w CAP,
auth token/XAdES, sesje FA(3), worker/outbox, checkpointy i rekoncyliacja.
**LIVE auth TEST PASS:** challenge, XAdES, biznesowe 200, redeem, autoryzowany READ
i refresh. **LIVE E2E PASS** na oficjalnym syntetycznym fixture: jedna faktura
`1234563218-20261029-7DC8A1C00000-CD`, COMPLETED, UPO 5528 bajtów i RESUME po restarcie.
Pełny clean verify A+B **141/141 PASS**, 0 błędów (18:03 Europe/Warsaw), H2/mock.
Commit B `7075c01` zintegrowano jako `657c285`; producent łączy generator/snapshot/outbox,
a granica wysyłki przywraca 12 oryginalnych dużych limitów XSD. Bez LIVE H01
READ/WRITE i bez drugiej wysyłki z B. [Dowody](journal/2026-10-29-agent-a-ksef-sdk.md),
[runbook](guides/KSEF_TEST_RUNBOOK.md).

SAPUI5 → RAP UI (dane i wynik biznesowy; live BLOCKED) + CAP (akcje/statusy) → Java SDK KSeF w jednej JVM → trwały writeback CAP → RAP Web API. SAP przez Remote OData/Destination jest źródłem danych finansowych; CAP przechowuje proces KSeF. H2 lokalnie, SAP HANA Cloud i SAP BTP Cloud Foundry docelowo. Aktualny podział: [ADR-0005](decisions/ADR-0005-rap-business-status-cap-integration-status.md).

## Completed

- 2026-10-29: **Hardening Fix 2 — FIXED + TESTED lokalnie:** pełny `mvn clean verify -Dcds.cds.skipVersionCheck=true`: **82 PASS, 0 FAIL, 0 errors**, BUILD SUCCESS (46,603 s). W tym 20 hardening, 15 HTTP adapter/CRUD i 2 konfiguracji HTTP; reszta obejmuje wcześniejsze READ, claim/finalize, snapshot/hash i restart/outbox. Live tagi domyślnie wykluczone. READ LIVE w tej sesji NOT TESTED; szkic testu SAP CBA NOT VERIFIED. Istniejąca trwała baza potrzebuje migracji kolumny `revision` przed użyciem nowego kodu.

- 2026-10-29: **CAP-04 Hardening** — zob. akapit w Current Phase i [dziennik](journal/2026-10-29-cap-04-hardening.md). Zmiana kontraktu wewnętrznego: `enqueue(operationId)` wymaga migawki dokumentu.

- 2026-10-28: **CAP-04C** — import rzeczywistego EDMX, CAP Remote Action, atomowe zlecenie i native persistent outbox, historia, stabilny request ID i wersja 100 ns, autoryzowane wewnętrzne enqueue, retry i rekoncyliacja. Nowe environment operacji readonly TEST. Testy HTTP lokalne, transakcji, równoległego enqueue, restartu, bezpieczeństwa i regresji READ potwierdzone w [dzienniku](journal/2026-10-28-cap-04c-sap-result-writeback.md). SAP WRITE/KSeF/CF poza testami.
- 2026-10-28: publikacja CAP-04B — dokumentacja `8de662f`, eksport SAP `525e48d` (31 plików), potwierdzone remote refs/status i fast-forward lokalnego branchu. Bez niepowiązanych usunięć i scenariusza komunikacyjnego; [kontynuacja](journal/2026-10-28-cap-04b-rap-write-contract.md).
- 2026-10-28: **CAP-04B — live SAP RAP contract.** Cztery pola nagłówka, akcja statyczna z wewnętrzną blokowaną akcją instancyjną, CAS po blokadzie, jawne konflikty/no-op, global + instance authorization i prechecks CRUD. Testy z kontrolowaną polityką praw i rollback, bez nadania principalom. Rzeczywisty read-only EDMX potwierdzony; istniejące 4 nagłówki bez wyników KSeF i bez zmiany ETag. Źródła wyłącznie live SAP, jeszcze bez eksportu abapGit. [Dziennik](journal/2026-10-28-cap-04b-rap-write-contract.md), [ADR-0002](decisions/ADR-0002-sap-ksef-result-write-contract.md).
- 2026-10-27: **CAP → SAP H01 READ: LIVE PASS w lokalnym procesie CAP.** `SapInvoiceRemoteAdapterLiveTest`: 1 test, 0 failures, 0 errors, `BUILD SUCCESS`; 4 nagłówki, 9 pozycji, 6 referencji i zgodność `InvoiceUUID`. Destination była dostarczona lokalnie przez env `destinations`. Cloud Foundry + bound Destination Service pozostaje NOT TESTED, CAP → RAP UPDATE — NOT IMPLEMENTED. Pierwsza próba z hostem `abap-web` zwróciła HTML/IAS redirect; poprawny host OData zadziałał z Basic Auth. Szczegóły: [dziennik CAP-03](journal/2026-10-27-cap-03-sap-remote-integration.md).
- 2026-10-27: CAP-03 krok 2 — lokalny test HTTP kontraktu wykrył i naprawił dodatkowy segment URL, blokadę `@requires: internal-user` oraz filtr `in`; poprawiono `http.service` i przeniesiono `cds.remote.services` poza profil `default`. Historyczne usterki i naprawy: [dziennik CAP-03](journal/2026-10-27-cap-03-sap-remote-integration.md#krok-2--test-live-i-weryfikacja-url--basic-auth).
- 2026-10-27: CAP-03 — aktywny binding `ZAPI_AINV_INV_H_O4`, wersja `0001`, opublikowany (`published=true`), odpowiedział na `$metadata` HTTP 200. Runtime kontrakt zawiera entity sets `ZC_AINV_INV_H`, `ZC_AINV_INV_I`, `ZC_AINV_INV_REF`, klucze UUID i nawigacje `_Items`, `_References`. Autoryzowany podgląd OData V4 zwrócił 4/9/6, bez kolejnych stron; UUID z danych zachowują wartości seeda. W CAP dodano zdalny model CDS, konfigurację Destination `AINV_H01`, adapter CQN, test kontraktu oraz test autoryzacji endpointu zewnętrznego. Pełny `mvn clean verify -Dcds.cds.skipVersionCheck=true`: 22 testy PASS. CAP→BTP Destination→H01 nie było testowane; MCP dostarczył znormalizowany opis `$metadata`, nie surowy EDMX. [Dziennik CAP-03](journal/2026-10-27-cap-03-sap-remote-integration.md).
- 2026-10-26: domknięto aktualizację `AGENTS.md`, Skilla ABAP/RAP i workflow abapGit do zweryfikowanego ABAP FS **2.12.4 / fix 5**. Test akceptacyjny z 2026-10-25: klasa + CDS w obu pakietach Cloud, **4/4 PASS**, bez nowych requestów i aktywacji. Dwa obiekty lokalne bez transportu, dwa w `ZAINVOICER_DEV` z potwierdzonym przypisaniem do wcześniej zatwierdzonego requestu. [Dowody i ograniczenia](journal/2026-10-26-abapfs-v5-instructions.md). Nie jest to test biznesowych DCL/RAP.
- 2026-10-26/27: ABAP-04 seed — raport użytkownika i późniejszy runtime OData potwierdzają 4 nagłówki, 9 pozycji, 6 referencji oraz powiązania po UUID. Sekwencyjny rerun pozostaje nieweryfikowany; brak UNIQUE `InvoiceNumber` i ochrony przed równoległym seedem to ograniczenia.
- Istnieją `AGENTS.md`, `CLAUDE.md` i baza wiedzy `docs/`.
- 2026-10-24: pierwszy eksport abapGit potwierdzony w `39ab869` na `java-cap-ksef`; `.abapgit.xml` kieruje pakiet `ZAINVOICER` do `/abap/src/` przy `FOLDER_LOGIC=FULL`. Repo zawiera trzy tabele symulacyjnego modelu i RAP wyłącznie dla nagłówka. Szczegóły i granice potwierdzenia: [workflow abapGit](guides/ABAPGIT_WORKFLOW.md), [model RAP](guides/ABAP_RAP_INVOICE_MODEL.md) i [dziennik](journal/2026-10-24-abapgit-rap-initial-export.md).
- Utworzono trzy projektowe Skills w `.agents/skills/` i krótkie odwołania Claude w `.claude/skills/`: ABAP/RAP, CAP Java i KSeF. Codex CLI 0.164.0 potwierdził wszystkie trzy przez `skills/list` jako `enabled: true`, `scope: repo`, bez błędów ładowania. To potwierdza wykrywanie, nie automatyczny dobór przez model. Szczegóły i przykłady: [dziennik Skills](journal/2026-10-23-project-skills.md).
- Istnieje szkielet `cap-java/`: parent POM, moduł `srv`, `Application.java`, konfiguracja Spring, pliki npm/CDS. Inicjalizacja `cds init cap-java --java` — według kontekstu użytkownika.
- W POM: JDK 25, CAP Java 5.2.0, Spring Boot 4.1.1, minimalny Maven 3.9.14; adapter OData V4, H2 i Spring Security jako zależności runtime.
- Użytkownik potwierdził wcześniejsze `mvn clean verify`; 2026-10-24 wykonano dwa pełne buildy `mvn clean verify` na JDK 25.0.4.1 / Maven 3.9.16 — oba zakończone `BUILD SUCCESS`.
- Uruchomiono executable JAR: CAP Runtime 5.2.0 i Spring Boot 4.1.1 wystartowały na porcie 8080; `http://localhost:8080/` zwrócił HTTP 200. Test integracyjny potwierdził `SELECT 1` przez `JdbcTemplate` na H2 in-memory.
- `KsefService` wymaga `authenticated-user`; encja dopuszcza wyłącznie CREATE i READ roli `KsefOperator`, ograniczone do NIP z `$user.taxpayerNips`. CAP Java sprawdza też warunek NIP wejściowego przy CREATE. Testowi operatorzy są zdefiniowani wyłącznie w `src/test/resources/application-test.yaml`.
- `KsefOperationsHandler` waliduje sumę kontrolną NIP, kierunek, mały katalog typów operacji, wymagany i przycięty klucz idempotencji oraz wymusza `NEW`. Nie zastępuje handlerami standardowego CRUD.
- Phase 2.3: dodano trwałą encję `KsefInvoices` (status biznesowy `UNCONFIRMED`) oraz opcjonalną asocjację `KsefOperations.invoice`. Obie encje używają wspólnego typu `KsefDirection` (`INBOUND`/`OUTBOUND`). Publiczny `KsefService` wystawia faktury tylko do odczytu z filtrem NIP; nie publikuje asocjacji ani referencji magazynowych XML/UPO. Testy potwierdziły OData `$metadata`, brak `$expand`/ustawiania FK przez klienta i poprawne wewnętrzne powiązanie w H2.
- CAP CDS `Association` opisuje referencję i nie usuwa rekordu docelowego z kaskadą; `Composition` reprezentowałaby własność i cykl życia potomka, więc nie pasuje do historii operacji, którą należy zachować. Asocjacja CAP przypomina `Association` w ABAP CDS jako deklarację nawigacji, lecz nie nadaje automatycznie identycznych reguł persystencji ani autoryzacji. DDL H2 zawiera kolumnę `invoice_ID`, lecz bez ograniczenia FK; lokalna baza in-memory nie zachowuje danych po restarcie.
- `mvn clean verify '-Dcds.cds.skipVersionCheck=true'` PASS: 15 testów, w tym 14 integracyjnych przez losowy port HTTP i 1 test H2. Zwykłe `mvn clean verify` nadal kończy się `spawn EPERM` podczas kontroli `cds version`; obejście nie naprawia problemu Node.

## Aktualny zakres i ograniczenia

- **CAP → SAP H01 READ — LIVE PASS:** lokalny CAP Java + SAP Cloud SDK RemoteService + lokalna Destination przez env `destinations` / Basic Auth. Wynik testu 1/1, 0 failures, 0 errors, `BUILD SUCCESS`; 4/9/6 i zgodne UUID. Testu nie powtarzano w tym audycie.
- **SAP H01 runtime OData V4 — LIVE PASS w udokumentowanym zakresie:** aktywny/publikowany binding, `$metadata` HTTP 200 i autoryzowany `$expand` 4/9/6 przez MCP OAuth. To nie jest test izolacji DCL ani CAP.
- **CAP na Cloud Foundry + bound Destination Service — NOT TESTED.** Lokalna env Destination nie stanowi dowodu dla Destination Service na CF.
- **CAP → RAP action — IMPLEMENTED, transport OFF / LIVE WRITE NOT TESTED.** Write Adapter i worker CAP-04C wywołują wyłącznie `ApplyKsefResult`. Standardowy PATCH nie jest używany. Rzeczywisty HTTP WRITE pozostaje w CAP-04D.
- **KSeF Java SDK i E2E KSeF w CAP — LIVE TEST PASS na `feature/ksef-auth` (2026-10-29).** SDK 3.0.29, auth/JWT/refresh, jedna syntetyczna faktura z oficjalnego fixture, numer i UPO. Źródło SAP zastąpiono lokalną syntetyczną migawką; nie potwierdza pełnego H01 → KSeF ani generatora B.
- **FA(3) XML — IMPLEMENTED + TESTED lokalnie (A+B):** generator, walidacja XSD/domenowa, snapshot/outbox; VAT, PLN, 23/8/5%. Walidator B rozluźnia duże maxOccurs, ale bramka wysyłki A przywraca wszystkie 12 oryginalnych limitów. Niezależna walidacja pełnym XSD/lxml PASS. Przyjęcie XML z generatora B przez KSeF niezweryfikowane.
- Model CAP zaimportowano z rzeczywistego EDMX H01 2026-10-28 z oryginalnym namespace i ochroną `internal-user`. Regresja CAP LIVE READ po imporcie PASS.
- **DCL / EML — raport historyczny 2026-10-26:** 6 DCL aktywnych, `ZCL_AINV_RAP_COMP_TEST` 4/4 ABAP Unit PASS, ATC trzech nowych DCL: 0 ustaleń. Tego historycznego zestawu nie powtarzano. Nowsze testy autoryzacji zapisu opisuje CAP-04B; jego źródła są wyeksportowane w `525e48d`. Root DCL `where TRUE` nadal nie zapewnia izolacji odczytu.
- CAP-04B wdrożył global + instance authorization i prechecks zapisu; testy EML odmów PASS z kontrolowaną polityką. Nowe prawo `Z_AINV_KSF` bez nadania; dotychczasowi konsumenci zapisujący potrzebują 01/02/06 i zakresu, akcja wyłącznie 16. DCL root `where TRUE` nie zapewnia izolacji odczytu per NIP; nie zmieniano go.
- Pełne dwa równoległe zapisy akcji, persisted no-op/ETag oraz uprawnienia rzeczywistego communication user pozostają niezweryfikowane. Nie wykonano HTTP WRITE; te testy wymagają osobnego zlecenia CAP-04D.
- SDK, producent ACCEPTED i generator B są zintegrowane na `feature/ksef-auth`; pełne źródło LIVE SAP nadal oczekuje. Publiczne CREATE wymusza NEW. Domyślna H2 jest in-memory; E2E używa odrębnej plikowej H2. Przed CF wymagany zarządzany storage i trwała baza. Kontrolowane retry/reconciliation są API Java, bez publicznego endpointu. HANA i wiele instancji CF pozostają niezweryfikowane.
- Historyczne błędy URL, IAS redirect, `@requires`, OData `in`, profilu YAML, `ACM_UNEXPECTED_VALUE` i MCP `Unsupported Media Type` pozostają w dziennikach jako wcześniejsze problemy; nowsze wyniki rozwiązują je tylko w opisanym zakresie.

## Blockers

- Pełny LIVE H01 → generator B → KSeF: NIP-y seeda mają błędne sumy kontrolne, RAP nie dostarcza pełnego PartyData (adresy/JST/GV), a w procesie tej sesji brak Destination. Własny E2E CAP/SDK ma LIVE PASS na oficjalnym fixture. Nie zmieniano danych H01 ani nie wysyłano drugiej faktury.

- Przed uruchomieniem zapisu komunikacyjnego: jawny zakres NIP/CompanyCode, nadanie ACTVT16 przez właściwy communication scenario/arrangement i realne testy principalem. Użytkownik zdecydował o pozostawieniu prawa bez nadania w CAP-04B. Bez niego standardowy zapis i akcja są odmawiane. Izolacja odczytu DCL pozostaje osobnym zagadnieniem.

- SAP RAP / ABAP FS: historyczny `Unsupported Media Type` i myląca informacja „local” z [próby starszej wersji](journal/2026-10-25-abapfs-create-probe.md) nie są aktualnym ogólnym blokerem v5. Test v5 potwierdził tworzenie klasy/CDS i transporty w obu pakietach Cloud. Dalsze zmiany biznesowe nadal wymagają sprawdzenia aktualnego pakietu/requestu i zakresu zadania; nie ponawiać historycznych prób w ciemno. Nie potwierdzono pełnego testu 23 typów na v5 ani ECC.
- Poprzedni `NoSuchFileException: srv/target/classes/application.yaml` nie odtworzono. Zwykłe `mvn clean verify` zatrzymuje się przed testami, bo kontrola `cds version` zgłasza `Required @sap/cds-dk not installed`, mimo że raportuje lokalne `@sap/cds-dk 10.2.0`; `-Dcds.cds.skipVersionCheck=true` pozwala kontynuować i pełny build/test przechodzi.

## Next Steps

0. CAP-07: owner merge PR #1, review stacked PR CAP-07; zatrzymać lokalny CAP 8080 i powtórzyć `mvn clean verify` z JAR-em. CAP-07B wg [przewodnika BTP](guides/BTP_IDENTITY_SETUP.md#10-czynności-administratora-kolejno): entitlementy, decyzja DB, deploy po zgodzie, role collection, SSO do H01.
1. CAP-04D po osobnej zgodzie: ograniczone nadanie, HTTP WRITE na uzgodnionych danych, commit/ETag/no-op, autoryzacja rzeczywistym principalem i pełne dwa równoległe wywołania akcji.
2. Uzgodnić poprawne syntetyczne NIP-y i źródło PartyData, wykonać READ H01 oraz lokalne przygotowanie przez zintegrowany generator B. Następny LIVE send to osobny eksperyment; istniejący numer/UPO zachować.
3. Przed wdrożeniem: trwała baza, obsługa operacyjna retry/konfliktów/Retry-After i testy HANA/CF. Domyślna baza development jest in-memory.
4. Decyzje po hardeningu: wariant polityki odczytu DCL, nadanie principalowi zapisu (NIP/CompanyCode), chroniony interfejs `resume`, `total etag` w BDEF. Osobne zaległości: izolacja odczytu DCL, sekwencyjny rerun seeda i kompleksowa ścieżka wiązania operacji z fakturą w CAP. CAP-04C sprawdza zgodność istniejącego powiązania przy enqueue.

Dla narzędzi agentowych: w następnej sesji Claude Code uruchomić `/reload-skills`, sprawdzić trzy komendy `/ainvoicer-*` i odczyt kanonicznych plików; osobno potwierdzić automatyczny dobór w czterech scenariuszach z dziennika.

## Verification

| Sprawdzenie | Ostatni dowód / ograniczenie |
| --- | --- |
| FA(3) generator/XSD | 2026-10-29: `Fa3*Test` + `SapInvoiceFa3MapperTest` 30/30, pełny clean verify 112/112 PASS. Ręcznie: przykład FA(3) z SDK MF przechodzi walidator, FA(2) odrzucony. Golden SHA-256 próbki `24fc8a1e…339afd` (2463 B). KSeF TEST: NOT CALLED. |
| CAP-04 Fix 2 | 2026-10-29: 82/82 PASS H2/mock, pełny clean verify. Równoległe resume: jeden sukces, RESUME_CONFLICT, jedna publikacja i historia, payload bez zmian; rollback publikacji, scheduled lease renewal i rzeczywisty HTTP read timeout PASS. HTTP WRITE/KSeF nie wykonywano. H01: CDS/BDEF częściowo porównane; include/DCL/runtime/IAM/CBA NOT VERIFIED. |
| CAP-04C | 2026-10-28: pełny `mvn clean verify -Dcds.cds.skipVersionCheck=true` 46/46 PASS, dodatkowy test ochrony modelu w przebiegu 2/2 PASS oraz LIVE READ 1/1 PASS. Lokalny HTTP, H2/outbox, rollback, równoległe enqueue i restart z mockiem SAP. HTTP WRITE OFF; [granice dowodu](journal/2026-10-28-cap-04c-sap-result-writeback.md). |
| CAP-04B SAP contract | 2026-10-28: aktywacja/syntax PASS, ATC `ABAP_CLOUD_DEVELOPMENT_DEFAULT` 10 obiektów finalnie 0 ustaleń, EML 18/18, prototyp 7/7, rzeczywisty EDMX HTTP200. Polityka testowa, rollback; root enqueue między sesjami PASS. Bez HTTP WRITE/commit, persisted no-op/ETag i pełnego testu równoległych zapisów. Prawo bez nadania; [szczegóły](journal/2026-10-28-cap-04b-rap-write-contract.md). |
| CAP → SAP H01 READ | LIVE PASS według dziennika 2026-10-27: `SapInvoiceRemoteAdapterLiveTest`, lokalny CAP process + env `destinations`, Basic Auth; 1/1, 0 failures/errors, 4/9/6 i UUID zgodne. Nie powtarzano w audycie. Cloud Foundry + bound Destination Service: NOT TESTED. |
| ABAP-04 seed danych | 2026-10-26/27: raport użytkownika i późniejszy runtime OData potwierdziły 4 nagłówki, 9 pozycji, 6 referencji, UUID i powiązania. Sekwencyjny rerun niezweryfikowany; brak unikalności InvoiceNumber pozostaje. |
| ABAP-02 / RAP composition | 2026-10-25: aktywacja CDS/BDEF/SRVD i ATC głównego BDEF PASS. Raport użytkownika 2026-10-26: 6 DCL aktywnych, EML 4/4 PASS, ATC 3 nowych DCL bez ustaleń; niepowtórzone w tym audycie. Runtime OData 4/9/6 potwierdzono odrębnie 2026-10-27. Izolacja DCL i autoryzacja zapisu niezweryfikowane. |
| Inspekcja repozytorium | 2026-10-24: branch `java-cap-ksef`; drzewo początkowo czyste, szkielet CAP śledzony |
| SAP RAP / MCP ABAP FS | 2026-10-25: połączenie `H01` i pakiet `ZAINVOICER_DEV` istnieją; system-info zwrócił typ/wydanie jako Unknown/N/A. Próba tworzenia klasy zakończyła się sukcesem, ale status transportu wykazał request `H01K900018` (task `H01K900019`); metadane requestu potwierdziły klasę wśród 6 obiektów. Próby `TABL/DT`, `DDLS/DF`, `DTEL/DE` zwróciły `Unsupported Media Type`; wyszukiwania kontrolne bez częściowych obiektów. Bez edycji i aktywacji. Wcześniejsza diagnostyka `ZAINVOICER` / DDLS z 2026-10-24 pozostaje odrębnym wynikiem. |
| Model CDS `KsefOperations` | 2026-10-24: utworzono encję `customer.cap_java.KsefOperations` z `cuid`, `managed`, kierunkiem INBOUND/OUTBOUND, statusem domyślnym NEW oraz unikalnością `(taxpayerNip, idempotencyKey)`. PASS: model wczytany przez API kompilatora CDS w procesie Node; CLI `cds --version` nie działa w sandboxie (`spawn EPERM`), Maven build nieuruchomiony. |
| Usługa CDS / OData V4 | 2026-10-24: `KsefService` wymaga `authenticated-user`; status, referencje KSeF i pola błędu są `@readonly`. PASS: build CDS/Maven, metadata (anonimowy 401, authenticated 200), lokalne CREATE/READ/UPDATE/DELETE na H2. Status utworzył się jako NEW mimo próby podania ACCEPTED; pola techniczne wysłane przez klienta były ignorowane. |
| Phase 2.2 — walidacja i autoryzacja | PASS lokalnie 2026-10-24: `@restrict` CREATE/READ dla `KsefOperator` i `$user.taxpayerNips`; 14 testów OData przez Spring Boot + Spring Security + CAP + H2 potwierdziło izolację, także dla braku atrybutu, walidację, brak UPDATE/DELETE i unikalność. Mock users tylko w profilu testowym. IAS/XSUAA nie skonfigurowano. |
| Historyczne `mvn clean verify` | Sukces według użytkownika; wcześniejszy błąd Enforcer przy JDK 21 również z relacji użytkownika |
| Bieżące `mvn clean verify` | PASS 2026-10-24 (2 uruchomienia; drugie po dodaniu testu H2). Enforcer, CDS build/generacja, resources, kompilacja, JAR i Spring Boot repackage PASS. `cap-java.jar` (6,583 B), `cap-java-exec.jar` (57,567,847 B); `target/classes/application.yaml` istnieje. Poprzedni wyjątek nie został odtworzony, więc przyczyna pozostaje nieustalona. |
| Testy Java / H2 | PASS: `H2DataSourceIntegrationTest`, 1 test, 0 błędów; Spring Boot context, JdbcTemplate i `SELECT 1` na H2 in-memory. Nie testuje CRUD encji CDS. |
| Dokumentacja | 2026-10-24: przewodniki abapGit/RAP, dziennik pierwszego eksportu oraz indeks/status/roadmapa zaktualizowane na podstawie inspekcji repo. PASS: wszystkie lokalne linki Markdown w zmienionych plikach rozwiązują się; `git diff --check` bez błędów (Git zgłosił tylko ostrzeżenia o normalizacji LF/CRLF). |
| Project Skills | 2026-10-23: PASS walidacji sześciu `SKILL.md`, odwołań i ręcznej oceny doboru; PASS wykrywania trzech kanonicznych Skills przez Codex `skills/list`. Szczegóły końcowej kontroli linków/YAML w dzienniku Skills |
| Automatyczna aktywacja Skills / Claude runtime | NIE WYKONANO: nie uruchamiano modelu ani interaktywnego selektora `/skills`; samo `claude --version` nie potwierdza wykrywania Skills |
| Runtime / HTTP | PASS: executable JAR; CAP Runtime 5.2.0 i Spring Boot 4.1.1 wystartowały, Tomcat port 8080; `/odata/v4/KsefService/$metadata` zwrócił 401 anonimowo i 200 dla mock user. CRUD zweryfikowano lokalnie na H2. |
| Runtime Phase 2.2 | PASS 2026-10-24: executable JAR CAP Runtime 5.2.0 / Spring Boot 4.1.1 z testowym profilem uruchomiony na 8081; HTTP CREATE zwrócił 201 i status `NEW` mimo payload `ACCEPTED`, READ zwrócił 200 z operacją przypisaną do `operator-a`. |
| Phase 2.3 — KsefInvoices | PASS lokalnie 2026-10-24: `mvn clean verify '-Dcds.cds.skipVersionCheck=true'`; CDS wygenerował schemat H2 i metadata OData. Testy HTTP potwierdziły READ tylko dla NIP operatora, 401 anonimowo, 403 bez roli, brak CREATE/PATCH/DELETE, brak pól XML/UPO i asocjacji w metadata, odmowę `$expand` oraz odmowę klientowskiego `invoice_ID`. Zaufany test zapisał operację z istniejącą fakturą przez CAP Persistence Service i potwierdził FK przez JDBC w H2. Razem: 20 testów, 0 błędów. H2 DDL nie zawiera constraint FK; nie testowano trwałości po restarcie, bo konfiguracja jest in-memory. |
| `mvn spring-boot:run` | Nie uruchomiono: `-pl srv` nie spełnia reactor convergence, a `-pl srv -am` blokuje sandbox `spawn EPERM` w CDS. Zamiast tego uruchomiono spakowany executable JAR. |
| Konsola H2 w przeglądarce | 2026-10-24: `/h2-console/` HTTP 200; H2 Test Connection dla `jdbc:h2:mem:capjava;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE` zwrócił `Test successful`; OData odczyt encji HTTP 200. Zapytanie SQL z GUI nie wykonano. |
| Własne OData / CDS CRUD | PASS lokalnie 2026-10-24: `mvn clean verify '-Dcds.cds.skipVersionCheck=true'`; OData V4 przez profile mock test, Spring Security i H2. Obejście pomija tylko kontrolę wersji CDS; nie potwierdza naprawy `spawn EPERM`. |
| KSeF TEST / UPO przez CAP | LIVE PASS 2026-10-29, Agent A: auth/XAdES/redeem/refresh, jedna syntetyczna faktura `1234563218-20261029-7DC8A1C00000-CD`, numer/hash, close, UPO 5528 bajtów i RESUME po restarcie. Oficjalny fixture + lokalna migawka, bez H01 i generatora B. |

## Last Updated

2026-10-30 (Europe/Warsaw): opublikowany binding UI i komplet obiektów IAM do katalogu włącznie potwierdzone READ H01. Obecna bramka: rola biznesowa, przypisanie użytkownika i testy READ/izolacji. Dodano model odpowiedzialności i zależności IAM; nie zmieniano SAP ani nadań przez agenta i nie powtarzano buildów aplikacji. Szczegóły oraz wcześniejsze testy: Current Phase i dziennik sesji. Historyczna trwała baza nadal wymaga osobnego kontrolowanego podejścia do migracji.

Historyczny wpis Agenta A:

2026-10-29 (Europe/Warsaw): A+B na `feature/ksef-auth`, 141/141 PASS; LIVE auth i jedna syntetyczna wysyłka TEST z numerem/UPO przez CAP, generator B połączony ze snapshotem/outboxem i niezależnie zwalidowany. Pełny H01 → KSeF nadal oczekuje z powodu danych NIP/PartyData. H01 WRITE i deployment poza zakresem. Historyczne wyniki zachowano powyżej.
