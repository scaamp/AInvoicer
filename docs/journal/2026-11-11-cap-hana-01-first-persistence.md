---
title: "CAP-HANA-01 — final review INT-03 i przygotowanie pierwszej persystencji"
date: 2026-11-11
type: journal
status: approved-deployment-staging-failed-portable-zip-fix-pending
tags: [ainvoicer, int-03, cap-hana-01, hana, hdi, security, deployment]
---

# CAP-HANA-01 — stan i dowody sesji

**Najnowszy stan LIVE:** po nowej zgodzie wykonano abort i deployment poprawionego deskryptora.
Merge przeszedł; operacja d110c171… zakończyła się ERROR przy stagingu przez uprawnienia ZIP Windows.
HDI1 app binding do STOPPED deployera; CAP HANA NOT DEPLOYED. Naprawa przenośnych ZIP w toku walidacji.
Pozostałe akapity wstępu są historią; aktualny zapis jest na końcu.

**Najnowszy stan: NO-GO, pierwsza próba deploymentu odrzucona przy scalaniu deskryptorów.**
Nie doszło do stagingu/bindingu HDI. Wcześniejsze GO i hashe nie są już rekomendacją użycia.
Patrz [naprawa poniżej](#naprawa-po-pierwszej-próbie-deploymentu--2026-11-11).

**Aktualizacja LIVE preflight 11 listopada:** HDI **VERIFIED LIVE**, provisioning wykonany przez
właściciela; CAP→HANA **NOT DEPLOYED**. **GO do zatwierdzenia kontrolowanego DEV**, bez DML.
[Historia HDI i kosztów](2026-11-11-hdi-shared-provisioning.md).
Poniższa wcześniejsza część zachowuje historię przed utworzeniem HDI; aktualne wyniki są na końcu.

## Date / Branch / Goal

11 listopada 2026, Europe/Warsaw. Zlecenie: final review i warunkowy merge INT-03, następnie
`feature/cap-hana-01-first-persistence` z aktualnego main. Przygotować trwałość HANA/HDI oraz wykonywalny
runbook, zatrzymać się przed zmianami BTP i testami DML.
[Runbook](../guides/CAP_HANA_01_FIRST_DEPLOYMENT_RUNBOOK.md) · [STATUS](../STATUS.md) ·
[Audyt INT-03](../audits/2026-11-11-int-03-security-persistence.md).

## Initial State / Final review

Główny worktree `D:\AInvoicer` był czysty na main f093af5; INT-03 w `D:\AInvoicer-int-03` czysty na c195527.
Przeczytano AGENTS, STATUS/ROADMAP/README, dzienniki, ADR0001/0006/0007/0008, skill CAP Java,
runbooki HANA/security, kod/testy i dokumentację SAP CAP/SAP Security4.1.2/HDI oraz manifest Node buildpacka.
GitHub potwierdził PR #8 OPEN/mergeable, CI43025816235 SUCCESS dokładnego c195527, brak reviews/threads.
Main nie zmienił się; PR #7 HEAD2455c3f jest przodkiem INT-03. Nie wykonano niezależnego merge #7.

Review potwierdził dekorację oficjalnego SAP JwtDecoder: najpierw podpis/audience/czas, potem
verified zid zgodny z pojedynczym XSUAA bindingiem, opcjonalne aliasy muszą być zgodne. Brak zaufania
do NIP/spółki/tenant z payloadu. Test37 obejmuje metadata, collections/count/filter/select/expand,
entity keys, requestSend, batch i INBOUND. Filtry NIP/CompanyCode/grant BUYER/TEST nadal obowiązują.
INBOUND CompanyCode pozostaje kontekstem, bez autorytatywnej własności dokumentu. LIVE A/B/principal
H01 i wdrożenie poprawki tenantów na istniejący CF nadal niepotwierdzone.

Model CDS niezmieniony. Wygenerowany model HANA ma rezerwację SEND PK(invoiceUUID,environment),
unikalny indeks operacji(taxpayerNip,idempotencyKey), staging/grant/checkpoint/lease i outbox.
Nie ma lokalnych tabel importowanego RAP. CAP5.2.0, Spring Boot4.1.1, SAP Security4.1.2, SDK MF3.0.29;
JAR ma cds-feature-hana5.2.0 i ngdbc2.30.11. To inspekcja/build, nie potwierdzenie SQL na HANA.

## Implementation / Naprawa przed merge

Final review znalazł dwa powiązane defekty testowego harnessu: seed commitował dane przed rezerwacją
checkpointu, a cleanup kasował lease scope bez dowodu runu. Commit
`368314cdcec0d054f3e6a54b0b814418f94313f7` na PR #8:

- Jeden commit operacji/dokumentu/outbox/checkpointu; unikalność scope wycofuje cały przegrany seed.
- Contend znakuje własny lease po porównaniu tokenu pod blokadą.
- Cleanup w jednej transakcji blokuje i sprawdza niezmieniony checkpoint, marker lease i własne rekordy;
  obcy lease/zmieniony stan przerywa całość. Nie zmieniano schematu produkcyjnego.
- HanaHarnessSafetyTest na prywatnej H2: konflikt z pełnym rollbackiem, odmowa obcego lease, odmowa
  zmienionego checkpointu i zachowanie drugiego runu/scope po cleanup.
- Skorygowano historyczną instrukcję pełnego MTA w Free Tier guide; wymagany generator readiness.

[CI43052235875](https://github.com/scaamp/AInvoicer/actions/runs/43052235875) dla368314c:
**COMPLETED/SUCCESS, wszystkie kroki SUCCESS**, w tym Azurite, QUnit/OPA i rzeczywisty Inbox HTTP.
Final review bez pozostałych blockerów merge; merge-tree daje tree8e9f5d0 identyczne z HEAD.
PR #8 scalono z expected_head_sha368314c metodą merge:
`e2696ab2bee91399423ce9565a7f467194894215`. Główny main zaktualizowano `git pull --ff-only`,
tree identyczne z przetestowaną integracją. Nowy branch CAP-HANA-01 utworzono z tego main.
GitHub po merge oznaczył PR #7 jako closed/merged przez ancestry (merge_commit_sha8b11529);
nie wywoływano merge #7 ani nie tracono jego historii. Nie używano force push/obejścia ochrony.
Automatyczny CI main po merge jest oddzielnym przebiegiem, nie deklaracją jego wyniku.

## Read-only CF preflight

Wykonano portable CF8.19.0 `target`, `services`, `marketplace -e hana`, `marketplace -e hana-cloud`,
`apps`, `stacks`, `buildpacks`, `service ainvoicer-hana-dev`, `plugins`, `space dev --guid` oraz
CAPI GET metadanych apps/droplets/current/processes/service_credential_bindings bez `/details`.
`cf deploy --help` potwierdził obsługę `--version-rule ALL` dla jawnego rollbacku aplikacji;
nie uruchamiano polecenia deploymentu.
Nie pobierano `/env`, `cf env`, VCAP_SERVICES, service keys ani parametrów brokera z credentialami.

| Obszar | Własny odczyt 11 listopada |
| --- | --- |
| Target | EU20 API cf.eu20-001, org National Center for Cryptography_dev-v2xzij9s, space dev |
| HANA | ainvoicer-hana-dev, hana-cloud/hana-free, create succeeded08:57:13Z, brak bindingów; to nie bieżący ping SQL |
| Plan HDI | hana: No service offerings found; global entitlement i koszt dla konta niezweryfikowane |
| HDI | ainvoicer-db nie istnieje; CAP bez bindingu hana |
| Identity | ainvoicer-auth xsuaa/application i ainvoicer-destination destination/lite, obie przypięte do CAP/AppRouter |
| Aplikacje | CAP/AppRouter STARTED1/1; srv1024MB/router256MB; droplety9 listopada niezmienione |
| Stack/buildpack | Obie fs4 DEPRECATED; Java Jakarta2.76.0; Node1.9.6. fs5 ACTIVE, Node1.9.6 fs4/fs5 enabled, Java stack=null |
| Wersje runtime | JRE/Node rzeczywiście uruchomione niewidoczne w detect_output; SapMachine25.+ z historycznej konfiguracji, nie nowy staging |
| H2 | Poprzedni inventory potwierdził identity-smoke/H2; brak nowego HDI i identyczne droplety potwierdzają brak migracji. Efektywne env teraz nieodczytywane |

Checksum dropletu CAP `944f83c7a1d7d8647f6e5c5aab7218bb3364190ea999881a845599d4455462da`,
AppRouter `0fd73ecae12e40bc349fd8c64a6f215450fe4332001e045fed46d049d92aac38`.
SQL HANA4.00.000.00.1793723943, parametry Free Tier i sieć CF-only to wcześniejszy dowód właściciela,
nie ponowny SQL/Cloud Central pomiar tej sesji. Database ID z Central pozostaje do potwierdzenia.
**COST VERIFICATION BLOCKED** dla hdi-shared. Brak BTP CLI/odczytu umowy kosztowej w tej sesji.

## Commands Executed / Tests & Results

Microsoft OpenJDK25.0.4.1, Maven3.9.16, host Node24.13.0, Maven CDS Node24.22.0, MBT1.2.49,
CF CLI8.19.0, multiapps3.12.0. Bez obniżania wymagań POM.

| Polecenie / zakres | Wynik |
| --- | --- |
| mvn test -Dtest=HanaHarnessSafetyTest -Dsurefire.failIfNoSpecifiedTests=false -Dcds.cds.skipVersionCheck=true | 4/4 PASS, prywatna H2 |
| npm ci --prefix deploy; npm test --prefix deploy | 27/27 PASS, 0 npm advisories |
| npm run build:btp:smoke --prefix deploy | PASS: Maven clean verify429 (423 PASS/6 Azurite skipped), CF JAR10, UI5 CF13, AppRouter10 |
| npm run verify:release --prefix deploy | PASS, źródła/payload/checksumy |
| npm run build:btp:hana --prefix deploy | PASS,14 tabel/6 indeksów/4 widoki, bez połączenia z bazą |
| npm run verify:hana --prefix deploy | PASS |
| node deploy/scripts/check-secrets.mjs; git diff --cached --check | PASS,2635 śledzonych plików; heuristic nie zastępuje audytu historii |
| REAL HANA phases, DDL/DML, restart/reconnect, CF deploy, KSeF/SAP | NOT EXECUTED |

Logi lokalne: `D:\AInvoicer-int-03\deploy\build\cap-hana-01-smoke.log`, `cap-hana-01-hana.log`,
`hana-safety-test.log` (ignorowane w Git). Zwykła regresja pomija6 testów emulatora; CI uruchamia je osobno.
Npm UI5 ma zastane74 advisories toolingu; nie przeprowadzano szerokiej migracji zależności.
Ostrzeżenia CDS importu RAP, Mockito self-attach i Node DEP0190 nie przerwały builda; sterowane argumenty
skryptów builda nie zawierają poświadczeń ani danych wejściowych użytkownika.

## Pierwsze artefakty INT-03 — historyczne, niewybierane do nowego deploymentu

Build z **czystego368314c**, workingTreeDirty=false, sourceFilesSha256
`b1343aa72d795f2ba87f9da4942357116f57c462166f649abacafe2dca8ba8af`.

| Artefakt | Bajty | SHA-256 |
| --- | --- | --- |
| ainvoicer_0.7.2.mtar | 67551806 | `4a1164b7f947d456883fef4f8396f37b059b783314f9e72d1c5fca06b4beb501` |
| ainvoicer_0.7.3.mtar | 67563132 | `c7108b044eaa2d13e937818eeccb5aa17fc053f59843a3a70779ea55ff5cf5a0` |
| HANA descriptor | — | `2c5ec8adf4a874ac1ba902a179e825548285d8fb141ea4177ea46a0bb98fffe4` |
| CAP JAR | — | `936b3d476fa77db7038c87a75079ac57f5bbef3ab8cca2628ac5f181cbbd717d` |
| HDI payload | — | `ff79417ffdd3c0feca7eb88d339ae7be97cdfe0a959cdb0354e95929ed8cf336` |
| deploy/btp-dev-eu20.mtaext | — | `4c6afb70c98f3f4bbe851865bdd8eb0c4f2ebd5a28b37d1b31419634fd3405b7` |

Powyższe oryginały w `D:\AInvoicer-int-03\deploy\build`; manifesty `release-evidence.json`,
`hana-release-evidence.json`. Runtime źródła muszą pozostać identyczne przy przenoszeniu paczek;
sourceCommit manifestu pozostaje prawdziwym368314c, a nie późniejszym commitem dokumentacji.
Próba skopiowania paczek do głównego checkoutu: walidacja archiwum PASS, ale obie weryfikacje
wejść **FAIL**. Porównanie wykazało49 plików różniących się wyłącznie LF/CRLF, bez zmiany treści;
surowy sourceFilesSha256 głównego checkoutu to fea6d9825dbcf5621a2865b8f364bed509add2257bb1ed6e8c78218d1478c341.
Nie zmieniano ani nie obchodzono walidatora. Docelowe paczki głównego checkoutu wymagają świeżego
builda z czystego commita. Próba `build:btp:smoke` w D:\AInvoicer na czystym220a512 zakończyła się
**FAIL środowiskowym**: Maven clean nie mógł usunąć zastanego target/cap-java-exec.jar.
Nie zatrzymywano zastanych procesów ani nie obchodzono clean. Utworzono izolowany detached worktree
`D:\AInvoicer-cap-hana-01-build` z tego samego220a512 i uruchomiono pełny pipeline offline tam.
Odrzucone kopie artefaktów w głównym checkoutcie przeniesiono do build/int-03-transfer-rejected;
nie należy ich zatwierdzać ani wdrażać.
Inspekcja archiwów: brak fixture, CSV, hdbtabledata, lokalnych konfiguracji/bindingów/test identity,
node_modules/testów UI oraz auto-undeploy; deployment tylko existing-service. Azure adapter OFF,
brak Object Store i BLOB, outbox persistent/inMemory OFF, SEND/INBOUND/SAP WRITE/sweeper OFF.

## Docelowe wydanie CAP-HANA-01 — OFFLINE PASS

W izolowanym `D:\AInvoicer-cap-hana-01-build` wykonano od nowa wszystkie sześć wymaganych poleceń:
npm ci/test deploy, build:btp:smoke, verify:release, build:btp:hana, verify:hana — **PASS**.
Maven429 (423 PASS/6 emulatora skipped), CF executable JAR10/10, UI5 CF13/13, AppRouter10/10,
deploy27/27; model HANA14 tabel/6 indeksów/4 widoki. Żadnego połączenia z prawdziwą HANA.
Nowy scan2637 plików i diff whitespace PASS. Lokalnych QUnit/OPA nie powtarzano w tej kopii:
ich dowód to CI368314c, obejmujący identyczny kod Git. Faza IT nadal wyłącznie skompilowana.

SourceCommit **`220a512874b1479aa016b30a820735003231502c`**, workingTreeDirty=false;
sourceFilesSha256 **`b9171c48cad43e7b804b2b52042af2b1364c47b09562f3f2d274cd8f4e1d64ed`**.
Zmiany dalszej dokumentacji nie wchodzą do hash wejść runtime; evidence zachowuje prawdziwy commit
budowy zamiast udawać build późniejszego HEAD. Odbudowane paczki i manifesty wyłącznie w izolowanym
worktree; główny target nadal nie jest katalogiem wydania.

| Docelowy artefakt | Bajty | SHA-256 |
| --- | --- | --- |
| ainvoicer_0.7.3.mtar | 67562982 | `be7bdbe6757f80e5498cbce033e560bb49d8c4dcd552bb7c181f8218c5bf2b81` |
| ainvoicer_0.7.2.mtar | 67551656 | `aec601f9283bc92e6151562ea72c76e910eb9e00c813360a40b910f1988c8902` |
| HANA descriptor | — | `2c5ec8adf4a874ac1ba902a179e825548285d8fb141ea4177ea46a0bb98fffe4` |
| CAP JAR | — | `1bf34b316850316f9f1f8e2d7d0e732a5e5e6c5d50b0b66610f76247386f4bd0` |
| HDI payload | — | `bbf5d8ed95b41e5f37709100c488f48be7a69a8cf5f58f844e00f2eb5c327b99` |

Extension pozostaje `4c6afb70c98f3f4bbe851865bdd8eb0c4f2ebd5a28b37d1b31419634fd3405b7`.
Aktualne logi/evidence: `D:\AInvoicer-cap-hana-01-build\deploy\build\cap-hana-01-smoke.log`,
`cap-hana-01-hana.log`, `release-evidence.json`, `hana-release-evidence.json`.
Kolejny dokumentacyjny commit i draft PR CAP-HANA-01 nie zmieniają modeli, guardów ani konfiguracji
runtime. CI main/nowego PR należy odczytywać jako osobne wyniki; nie zastępują zgody LIVE.

## Blockery / Next Steps

- COST: potwierdzenie dostępności i rzeczywistego rozliczenia hana/hdi-shared dla konta.
- INFRA: Database ID/mapowanie/RUNNING oraz osobna zgoda na dedykowany HDI.
- SECURITY: LIVE A/B/DCL/principal, niewdrożona poprawka tenantów i sprawdzenie efektywnych env.
- RUNTIME/PERMISSION: zatwierdzony runner IT z JDK/Maven/Node/SDK w sieci CF i zarządzanymi bindingami;
  produkcyjny MTAR celowo nie zawiera testów/Mavena. Nie wymyślano działającego cf run-task mvn.
- DATABASE: rzeczywiste SQLState/locks/lease/restart/pool recovery i brak podwójnej rezerwacji/wykonania
  workflow wymagają przyszłych dowodów. Obecny harness bada CAS/lease i trwałość rekordu outbox, nie delivery.
- PERMISSION: konkretne zgody na deployment/DDL/bindingi, syntetyczne DML oraz restart/outage/rollback.

**NO-GO LIVE.** Runbook jest przygotowany do review; CAP-HANA LIVE nie został wykonany.
Historyczny następny krok właściciela: potwierdzić hdi-shared/koszt i Database ID; następnie zatwierdzać
osobno HDI, konkretny MTAR i środowisko/testy. Nie zmieniano BTP/H01/sieci/IAM/Destination ani routingu.

## Kontynuacja 2026-11-11 — HDI istnieje, końcowy preflight przed zgodą

### Zakres i GitHub

**VERIFIED OFFLINE:** sesja rozpoczęta na czystym
`feature/cap-hana-01-first-persistence=5f04def0f09988055a7df31681893abb3361e937`.
`git ls-remote` i GitHub PR potwierdzają ten sam HEAD oraz main
`e2696ab2bee91399423ce9565a7f467194894215`. PR #9 OPEN/DRAFT, mergeable, dwa wcześniejsze commity
zmieniały wyłącznie dokumentację (8 plików); brak niezatwierdzonych zmian runtime przy starcie.
GitHub Releases zwróciło pustą listę: MTAR są lokalnymi wydaniami, nie opublikowanymi assets.

**VERIFIED OFFLINE:** [CI43055194601](https://github.com/scaamp/AInvoicer/actions/runs/43055194601)
dla dokładnego HEAD5f04def COMPLETED/SUCCESS, wszystkie kroki SUCCESS, także build HANA,
Azurite, UI5/QUnit/OPA i Inbox HTTP. Zapytanie connectora o workflow runs commita main e2696ab
zwróciło pustą listę; nie przypisujemy mu osobnego PASS. Kod main dziedziczy testowany INT-03,
a PR HEAD ma własny jawny wynik. Zielone CI nie stanowi dowodu LIVE.

### Nowe odczyty CF

**VERIFIED LIVE:** target EU20/org `National Center for Cryptography_dev-v2xzij9s`/space `dev`.
`ainvoicer-db` jest managed hana/hdi-shared, create succeeded16:49:24Z, 0 credential bindings,
plan free=true. Broker HDI zwraca jawne database_id. HANA hana-free: serviceStopped=false,
produkt2026.28.14/build4.00.000.00.1793723943. **OWNER-REPORTED:** właściciel potwierdził w tej sesji
Cloud Central RUNNING i zgodność Database ID. Pełne źródła i rozróżnienie ID: nowy dziennik HDI.

CAP/AppRouter nadal STARTED1/1, identyczne historyczne droplety/checksumy z9 listopada i fs4.
Każda aplikacja ma wyłącznie XSUAA/Destination; HDI nie ma app/key bindings. Odczyt space GUID
usług i aplikacji potwierdził tę samą przestrzeń. Quota FREE_QUOTA4096 MB, procesy1408 MB.
Nie wykonywano nowych mutacji BTP ani testowego SQL.

**VERIFIED LIVE:** whitelisted CAPI `/environment_variables` CAP: cf, identity-smoke=true,
SEND/WRITE/sweeper=false; readiness/INBOUND/storage/sql-init nieobecne w custom env.
Nie stwierdzono kluczy nadpisania JAVA_OPTS/JDK_JAVA_OPTIONS/JAVA_TOOL_OPTIONS, SPRING_DATASOURCE_*,
SPRING_CONFIG_*, CDS_SECURITY_* ani SPRING_APPLICATION_JSON, ani pasujących kluczy credentiali transportów.
Nie jest to audyt wszystkich możliwych sekretów/nadpisań. Nie pobierano systemowego `/env`, VCAP,
credential binding details, service keys ani logów aplikacji. Odpowiedzi brokera filtrowano do
niesekretnych pól; bez surowych danych w Git.

### Weryfikacja wydania i stack

**VERIFIED OFFLINE:** oba oryginalne MTAR są nadal w `D:\AInvoicer-cap-hana-01-build\deploy\build`.
Ponowne Get-FileHash, verify:hana i verify:release PASS przed i po regresji. Nie odbudowano ani
nie kopiowano MTAR. SourceCommit220a512, sourceFilesSha256b9171c48… i hashe z tabeli wcześniejszej
są nadal prawdziwe; dokumentacyjny HEAD5f04def ma identyczne wejścia runtime w worktree wydania.
Po regresji Maven integralność archiwalnego JAR/payloadów nadal PASS.

**VERIFIED OFFLINE:** archiwum ma trzy existing-service auth/destination/db, kolejność HDI→CAP→AppRouter,
no-route deployera, 14 hdbtable /6 hdbindex /4 hdbview (24 artefakty modelu), bez RAP tables/CSV/
hdbtabledata/undeploy. To liczba **wygenerowana**, liczba **wdrożona PENDING**.
Wszystkie wymagane flagi/OFF i outbox persistent/inMemory=false potwierdzone w deskryptorze i JAR.
CAP5.2.0, Boot4.1.1, ngdbc2.30.11, SAP Security4.1.2, SDK MF3.0.29;
AppRouter23.4.0, hdi-deploy5.7.0 z locka. Niczego nie aktualizowano.

**VERIFIED LIVE:** fs4 DEPRECATED, fs5 ACTIVE; Java Jakarta2.76 dostępny bez ograniczenia stack,
Node1.9.6 dostępny na fs4/fs5. **VERIFIED OFFLINE:** manifest upstream1.9.6 zawiera Node24.22.1
na obu stackach, default22.x; SAP Help opisuje konfigurację SapMachine25.+ używaną przez MTA.
To nie dowodzi nowego stagingu. Deployer wymaga Node24; AppRouter dopuszcza22||24 i nie obiecujemy24.

Przygotowano **propozycję** nowego `deploy/btp-dev-eu20-hana-first.mtaext`, tylko dotychczasowy host prefix
i jawny fs4 dla trzech modułów. SHA256
`f11b31967d12cc3f587b55a39aaf385791503b8f2b11af472f2d03bd37cfd14e`.
Oryginalny extension SHA4c6afb70… pozostaje nietknięty do historycznej weryfikacji/rollbacku.
Nowy plik jest odrębnym zatwierdzanym wejściem, nie częścią historycznego sourceHash MTAR.
Nie przenosimy go do worktree wydania i nie udajemy odbudowy na zmienionych wejściach.
Pin fs4 zachowuje stack istniejących aplikacji; świadomy kompromis DEV to pozostanie na deprecated
stacku do osobno zweryfikowanej migracji fs5. Nie zmienia to architektury/ADR.

### Commands Executed / Tests & Results

W worktree wydania, lokalnie JDK Microsoft25.0.4.1, Maven3.9.16, Node24.13.0:

| Weryfikacja | Wynik | Klasa |
| --- | --- | --- |
| npm run verify:hana / verify:release --prefix deploy (ponownie po regresji) | PASS, zachowane oba SHA | VERIFIED OFFLINE |
| npm test --prefix deploy, także w głównym checkoutcie | 27/27 PASS | VERIFIED OFFLINE |
| npm run test:cf-runtime --prefix deploy, gotowy JAR wydania | 10/10 PASS | VERIFIED OFFLINE |
| npm run test:config --prefix approuter | 10/10 PASS | VERIFIED OFFLINE |
| mvn -B -ntp verify '-Dcds.cds.skipVersionCheck=true' '-Dspring-boot.repackage.skip=true' w cap-java | BUILD SUCCESS, 429 testów:423 PASS/6 Azurite skipped, 2:39 min | VERIFIED OFFLINE |
| Osobny przegląd YAML nowego extension | PASS: dokładnie3 nazwy modułów i stacki, bez usług/flag/tras | VERIFIED OFFLINE |
| check-secrets.mjs oraz git diff --check | PASS; scan heurystyczny, nie audyt credentiali | VERIFIED OFFLINE |
| live-security.mjs do istniejącego CAP/AppRoutera, wyłącznie GET | 26 prób,25 PASS,1 FAIL: publiczny indeks CAP / HTTP200 | VERIFIED LIVE |
| HANA JDBC/DDL/DML/constraints/CAS/lease/outbox/restart/cleanup | NIE WYKONANO | PENDING |

Pierwsza próba Maven miała błąd parsowania niecytowanych właściwości `-D` przez PowerShell
(`Unknown lifecycle phase .cds.skipVersionCheck=true`), bez uruchomienia testów. Powtórzenie z
cytowanymi argumentami zakończyło się powyższym BUILD SUCCESS. Nie zmieniano POM ani runtime.
QUnit/OPA/Azurite/Inbox HTTP nie powtarzano lokalnie: mają jawny PASS w CI dokładnego HEAD.
Logi lokalne i oczyszczony raport HTTP pozostają w ignorowanym deploy/build worktree wydania,
z prefiksem `cap-hana-live-preflight-*`; nie dodano ich do Git.

### Security i granice GO

**PENDING:** LIVE A/B/DCL i request-correlated principal H01, tenant isolation rzeczywistymi userami,
nowy staging, CloudIdentityGuard/CloudPersistenceGuard na HANA i autoryzowany READ po zmianie.
Publiczny indeks obecnego starego CAP jest rzeczywistym FAIL, nie zaliczonym testem. Poprawka
indeksu i tenant gate znajdują się w proponowanym JAR; po deployu wymagany ponowny pomiar.
Nie stwierdzono nowego krytycznego defektu przygotowanego wariantu, ale nie ma pełnego LIVE sign-off.

Rekomendacja **GO wyłącznie do zatwierdzenia kontrolowanego DEV readiness**: trzy istniejące usługi,
bez aktywacji transportów, z przerwą sesji i utratą dotychczasowej efemerycznej H2.
Nie jest to akceptacja produkcyjna ani otwarcie workflow. Przy nowych niezgodnościach targetu,
hashy, planu/kosztu, mapowania lub guardów wrócić do NO-GO bez ponawiania w ciemno.

### Changed Files / Current State / Next Steps

Nowe: journal HDI provisioning i osobny stack extension. Aktualizacje: STATUS/ROADMAP/README,
niniejszy dziennik oraz trzy przewodniki CAP-HANA/HANA deployment/Free Tier. Bez zmian Java/CDS/UI5,
tożsamości, Destination, sieci i sekretów. PR #9 pozostaje draft; brak merge.

**NOT DEPLOYED.** HDI deployer nie był uruchamiany, CAP nie korzysta jeszcze z HANA, żadne rekordy
nie przetrwały testowanego restartu — testu jeszcze nie było. Costs and Usage PENDING.
Najmniejszy kolejny krok: osobna zgoda na dokładne polecenie/hashe/extension w runbooku §4.
Po deployu read-only HDI/JDBC/security; potem przygotowanie runnera i oddzielna zgoda na
syntetyczne DML/restart/cleanup. Rollback0.7.2 ma zweryfikowany SHA; wymaga osobnej decyzji,
nie cofa schematu i nie odtwarza H2.

## Naprawa po pierwszej próbie deploymentu — 2026-11-11

**OWNER-REPORTED:** właściciel wykonał polecenie dla oryginalnego0.7.3 i extension fs4.
Operacja `30401273-ddf4-11f1-ab29-eeee0a9dd8f2` z17:20:27Z zakończyła się błędem
`Unsupported module type "approuter.nodejs" for platform type "CLOUD-FOUNDRY"` podczas merge.
**VERIFIED LIVE:** mta-ops wskazuje ERROR/acquiredLock=true; CAPI potwierdza niezmienione GUID-y,
daty i checksumy dropletów z9 listopada, CAP/AppRouter STARTED1/1 oraz0 bindingów HDI.
Nie wykonywano retry, abort, nowego deploymentu ani DML. CAP HANA **NOT DEPLOYED**.

Przyczyna: generator zachowywał typy development descriptor i przekazywał je bez mapowania
do `mbt assemble`, które oczekuje już deployment descriptor. Dotychczasowy walidator sprawdzał
zgodność z własnym generatorem i utrwalał ten sam defekt; zielone CI nie wykryło niezgodności CF.
Stary rollback0.7.2 ma tę samą wadę. Nie jest bezpiecznym kandydatem do ponownego deploymentu.

**VERIFIED OFFLINE:** prawdziwe `mbt mtad-gen -p cf` wersji1.2.49 potwierdza mapowania
java→java.tomcat, approuter.nodejs→javascript.nodejs, hdb→com.sap.xs.hdi. Naprawiono oba generatory,
bez zmiany źródłowego mta.yaml, Java/CDS/UI5/tras/flag/bindingów. Dodano2 testy: porównanie obu
wariantów z prawdziwym SAP MBT wraz z extension fs4 oraz odmowę typów projektowych w obu wariantach.
`npm test --prefix deploy`:29/29 PASS. Oryginalne MTAR i evidence zachowano po sprawdzeniu hashy
w `D:\AInvoicer-cap-hana-01-build\deploy\build\rejected-30401273`, bez transferu między checkoutami.
Nowa odbudowa i checksumy PENDING; dotychczasowe artefakty oznaczone jako odrzucone.

Podczas pierwszego odczytu mta-ops narzędzie wypisało trace HTTP zawierający token sesji CF.
Nie zapisano go w repo ani nowych plikach; wartości nie powtarzamy. Właściciel otrzymał zalecenie
odnowienia logowania. Następne odczyty wykonano z procesowym CF_TRACE=false i projekcją wyłącznie
bezpiecznych pól CAPI. Nie pobierano VCAP ani credentiali bindingów; nie zmieniano ustawień konta.

Źródła: [SAP MBT1.2.49 — mapowanie platform](https://github.com/SAP/cloud-mta-build-tool/blob/v1.2.49/configs/platform_cfg.yaml)
i `mbt mtad-gen --help`. To realny test offline translacji, nadal bez potwierdzenia nowego stagingu CF.
Następny krok: odbudować oba MTAR, przedstawić nowe SHA, po osobnej zgodzie abort starej operacji
i deployment poprawionego zestawu. Nie ponawiać niepoprawnego archiwum przez retry.

### Odbudowa po naprawie — VERIFIED OFFLINE

Worktree wydania `D:\AInvoicer-cap-hana-01-build` był czysty; przełączono detached HEAD na commit
naprawy `e2df55aed2ebdd973fb4a27541401a30a43ee73e`, bez nowej gałęzi/worktree i bez kopiowania MTAR
między checkoutami. Przed zmianą zweryfikowano oba stare SHA i zachowano stare archiwa/evidence
w `deploy/build/rejected-30401273`. Nie są kandydatami deploymentu/rollbacku.

Wykonano npm test29/29, build:btp:smoke, verify:release, build:btp:hana, verify:hana — PASS.
W środku pełne Maven clean verify429 (423 PASS/6 Azurite skipped), CF executable JAR10/10,
UI5 CF13/13 i AppRouter10/10. Ponowny test prawdziwych odrzuconych archiwów potwierdził odmowę
obu przez nowy walidator. Node24.13.0/JDK25.0.4.1/Maven3.9.16/MBT1.2.49 bez zmian.
Nie powtarzano QUnit/OPA/pełnego Azurite lokalnie — zmiana dotyczy generatorów deskryptora.

| Poprawiony MTAR | Bajty | SHA-256 |
| --- | --- | --- |
| ainvoicer_0.7.3.mtar | 67562971 | `6a92fb787ccc77e1abc0b870a30ccbcba420c1d663446b628d7e3ab2ec8d935b` |
| ainvoicer_0.7.2.mtar | 67551642 | `66014221b2219cfed68aa89502750ef67b9b65ebfa4d100fd5cb1299abcd3a69` |

SourceCommit e2df55a, workingTreeDirty=false,
sourceFilesSha256 `fcde76abeace9c305179630af18d68c80980518f95ce90084b7f2188e5459119`.
HANA descriptor `5c31959eed0c6895f5554cb43172747faf2410e9ba31e33a49aaf487d45dffba`,
CAP JAR `2145df4c7eb252b7302e95dd590cd177f39d944e9e8a123116f1df12dd54ad83`,
HDI payload `73e5b4bd4d2ef6b4fd6f22642535f206213283ea84e1cd885380466c9753f852`.
Odczyt mtad.yaml wewnątrz obu archiwów: CAP java.tomcat, AppRouter javascript.nodejs,
HDI com.sap.xs.hdi. Wyłącznie existing-service, niezmienione flagi/routes/stackplan; HANA14/6/4.
Dokładny zewnętrzny extension do nowej zgody pozostaje w D:\AInvoicer\deploy,599B,
SHA `f11b31967d12cc3f587b55a39aaf385791503b8f2b11af472f2d03bd37cfd14e`.
Kopia extension w checkoutcie wydania ma inne zakończenia linii; nie zastępuje wskazanego pliku.

**NOT DEPLOYED / LIVE staging i HANA persistence PENDING.** Naprawa jest gotowa offline;
NO-GO wykonawcze do zatwierdzonego abort operacji ERROR i nowej zgody na zestaw/SHA.
Dokładne przyszłe polecenia są na początku runbooka. Nie wykonano żadnej z tych mutacji.
Logi module-type-fix-tests/smoke/hana.log i nowe release-evidence są ignorowane w Git.

## Zatwierdzony abort i deployment — 2026-11-11

**OWNER-REPORTED:** właściciel odpowiedział „tak zatwierdzam” na konkretne abort30401273…,
nowy MTAR0.7.3/SHA6a92fb787ccc… i ten sam extension f11b3196… . Zgoda nie obejmowała
syntetycznych DML, testowego restartu CAP, zmian usług/entitlementów ani kolejnych zmienionych archiwów.
Przed mutacją ponowiono target/hash/verify:hana/verify:release i CI658dbf2 SUCCESS.
CF potwierdził plan free, właściwy database_id, HANA serviceStopped=false i0 bindingów HDI.

**VERIFIED LIVE:** wykonano abort30401273… o17:51:17Z (18:51:17 CET), stan ABORTED i brak na liście
aktywnych operacji potwierdzono osobnym odczytem. Lokalna pierwsza kontrola targetu odmówiła przez
zbyt ścisły regex końca wiersza space; po poprawie samego warunku i ponownym potwierdzeniu targetu
uruchomiono dokładnie zatwierdzone cf deploy dla MTAR6a92fb78… i extension f11b3196… .

Nowa operacja `d110c171-ddf8-11f1-8764-eeee0a818911`, start17:53:35Z (18:53:35 CET).
Deskryptor został przyjęty, wykryto deployed0.7.1/new0.7.3, utworzono deployer i jego binding HDI.
Staging Node buildpack1.9.6/fs4 pobrał Python3.13.17; przed instalacją Node odmówił odczytu
`/tmp/app/package.json: permission denied`, supply script exit14/staging exit223.
Narzędzie wykonało3 automatyczne ponowienia tej samej fazy; agent nie wywoływał retry.
Proces zakończył się ERROR. Node24/SapMachine25 nowego wariantu nadal niepotwierdzone.

Własny odczyt CAPI po błędzie: CAP/AppRouter STARTED1/1, stare droplety/checksumy, dwa dotychczasowe
bindingi i te same trasy. Deployer STOPPED/fs4, brak tras, jeden app binding HDI; nie uruchomił HDI.
Nie tworzono nowych usług/entitlementów/service keys. Nie wykonywano DML, restartu CAP, rollbacku,
undeploy ani abort nowej operacji. Docelowe0.7.3/HANA **NOT DEPLOYED**; JDBC i persystencja PENDING.

Diagnostyka została ograniczona do CAPI metadata i wybranych linii STG z cf logs --recent,
bez credentiali/VCAP. Oczyszczone logi live-0.7.3-deploy-redacted.log i deployer-staging-redacted.log
są wyłącznie w ignorowanym deploy/build worktree wydania. Poprzednie MTAR6a92fb78…/66014221…
i evidence zachowano po kontroli SHA w build/rejected-d110c171, bez transferu między checkoutami.

Trace pluginu wynika z DEBUG=1 (cf mta-ops --help), więc samo CF_TRACE=false było niewystarczające.
Filtr usuwał Authorization/JWT/cookies przed wyświetleniem; później usunięto oba env z procesu CLI.
Nie zmieniano globalnej konfiguracji użytkownika ani env aplikacji. W dalszych poleceniach usuwać
DEBUG i CF_TRACE, nie wyświetlać raw HTTP i nie archiwizować nagłówków.

### Druga przyczyna pakowania i poprawka offline

W ZIP-ach wszystkich3 modułów stwierdzono creatorOS10 oraz pliki100666, w AppRouterze dodatkowo
katalogi40666. Zewnętrzny MTAR MBT ma creatorUnix3, więc kontrola samego MTAR nie wykrywa tego.
Go archive/zip Mode() rozpoznaje Unix3 oraz wybrane inne identyfikatory; creator10 zwraca000.
To odtwarza utratę uprawnień zgodną z logiem stagingu; nowe staging LIVE nadal wymaga testu.

Dodano portable-zip.mjs: Unix creator3, pliki100644, katalogi40755, bez zmiany zawartości.
Oba generatory normalizują wszystkie payloady przed zapisaniem ZIP, walidator sprawdza je przy
odczycie MTAR. Regresje obejmują Windows creator10, katalog bez traverse, niedozwolone tryby,
niezmienność bajtów oraz niezależny Go archive/zip (CI Go1.26.10, bez nowego runtime produktu).
Lokalnie npm test:32 testy,31 PASS/1 Go skipped, brak Go/WSL Linux; nie instalowano WSL.
CI musi wykonać rzeczywisty czytnik Go. Odbudowa i nowe SHA PENDING.

Źródła: [Go archive/zip](https://github.com/golang/go/blob/master/src/archive/zip/struct.go)
i lokalny adm-zip0.6.1 (EntryHeader wybiera na Windows creator0x0a; attr korzysta ze stat.mode).
Następny krok: odbudować oba MTAR z czystego commita, potwierdzić nagłówki nested ZIP i CI,
przedstawić nowe SHA oraz osobną zgodę na abort d110c171… i deployment. Bez ponawiania starego pakietu.
