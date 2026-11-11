---
title: "CAP-HANA-01 — final review INT-03 i przygotowanie pierwszej persystencji"
date: 2026-11-11
type: journal
status: przygotowanie-offline-live-zablokowane
tags: [ainvoicer, int-03, cap-hana-01, hana, hdi, security, deployment]
---

# CAP-HANA-01 — stan i dowody sesji

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
Następny najmniejszy krok właściciela: potwierdzić hdi-shared/koszt i Database ID; następnie zatwierdzać
osobno HDI, konkretny MTAR i środowisko/testy. Nie zmieniano BTP/H01/sieci/IAM/Destination ani routingu.
