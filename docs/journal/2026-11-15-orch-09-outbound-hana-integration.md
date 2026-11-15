---
title: "ORCH-09 — review OUTBOUND hardening i HANA release readiness"
date: 2026-11-15
type: journal
status: zakonczony-offline
tags: [ainvoicer, journal, orch-09, outbound, hana, release, security, e2e-01c]
---

# ORCH-09 — integracja kodu i projekt zatwierdzanego snapshotu

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) ·
[Projekt E2E-01C](../guides/E2E_01C_APPROVED_SNAPSHOT_DESIGN.md)

## Date / Branch / Goal

15.11.2026, Europe/Warsaw. Worktree `D:\AInvoicer-orch-09`, branch
`review/orch-09-outbound-hana-integration`, z aktualnego `origin/main`.
Niezależny review PR #26/#27, wspólna regresja offline i kontrolowana integracja po bramkach,
następnie projekt E2E-01C. Bez SAP H01, API KSeF, HANA SQL, CF deploy/restart/tasks,
HDI make/deploy, IAM/XSUAA, service keys, migracji, aktywacji INBOUND/OUTBOUND ani odblokowania SEND.

## Initial State

`D:\AInvoicer`: czysty `main=744836c9dbb5851e5d14b5f23eadd21af31fc897`; fetch potwierdził origin.
Worktree autorów nietknięte. Przeczytano AGENTS/CLAUDE, STATUS/ROADMAP/README, dziennik i plany ORCH-08,
E2E-01A/B, STOR-04R, BLOB-LAB-01 oraz ADR-0001/0005/0008/0010.
Stosowano kanoniczne skills CAP Java i KSeF; bez live MCP SAP.

| PR / autor workstreamu | Dokładny HEAD | CI tego HEAD, sprawdzone przez GitHub |
| --- | --- | --- |
| [#26 — Codex](https://github.com/scaamp/AInvoicer/pull/26) | `4c07d3ea7df39fe4b4d60689db5302a1a92c2ebc` | [43712708987](https://github.com/scaamp/AInvoicer/actions/runs/43712708987) SUCCESS |
| [#27 — Claude Code](https://github.com/scaamp/AInvoicer/pull/27) | `46c8b2f3d5af0017c1db07560ae36617ed87119c` | [43725006129](https://github.com/scaamp/AInvoicer/actions/runs/43725006129) SUCCESS, 24/24 kroków |

#27 początkowo IN_PROGRESS; dopiero późniejszy odczyt potwierdził SUCCESS, nie używano wyniku
starszego `eafc212`. Na obu PR brak istniejących review i inline threads w momencie inspekcji.
Lokalne merge `--no-ff` #26 → #27 bez konfliktów tekstowych i bez zmian branchy autorów.

## Review PR #26 — PASS WITH NOTES

Werdykt dotyczy bezpiecznej integracji **z zachowaniem blokady SEND**, nie gotowości do wysyłki.

| Punkty zlecenia | Dowód / ocena |
| --- | --- |
| A1–3: NEW, OUTBOUND/STANDARD/PLN, zaufany SellerNip/CompanyCode | Produkcyjny `KsefOutboundInvoiceValidator`, READ RAP i backend PartyData; zakres CAP i skonfigurowany NIP przed generacją; UI podaje wyłącznie UUID |
| A4–8: dzieci, kwoty, stawki/jednostki, duplikaty, sumy | Parent UUID, kanoniczne niezerowe ItemUUID, numery ItemNo porównane numerycznie, wersje dzieci, PLN, EA/PC i VAT5/8/23, skale i ilość/cena, netto/VAT/brutto i sumy nagłówka; domena/XSD osobno |
| HALF_UP vs mapper FA(3) | Zgodne z `Fa3DomainValidator`: quantity×price do 2 miejsc, VAT per stawka HALF_UP. Nowy walidator dodatkowo wymaga VAT per pozycja; suma zaokrągleń może nie zgadzać się z VAT od sumy stawki, wtedy świadoma odmowa 422, bez korekty SAP |
| A9–12: SHA, drugi READ, fingerprint, PartyData | SHA dokładnych bajtów sprawdzany w workerze. `SAP-OBSERVATION-v1` obejmuje wszystkie odczytane mapy nagłówka/dzieci/referencji i PartyData, sortowanie i długości tokenów, BigDecimal bez zaokrąglania; czas generowania nie udaje wersji PartyData |
| A13: odrzucenie przed zapisem | Dane/FA(3)/ponowny READ przed reserve/INSERT/storage/outbox; testy negatywne sprawdzają brak efektów; błędy READ maskowane również dla ServiceException |
| A14–15: guard i bypass | PREPARED → rzeczywisty `KsefInitialSendGuard.requireApproved` przed AUTHENTICATING/auth/open/send, bezwarunkowy throw i brak flagi obejścia; sprawdzane op/snapshot/reservation/context/hash/length/version |
| A16–17: recovery i brak drugiego SEND | Guard tylko PREPARED; EFFECTS po restarcie → reconciliation, POLLING/ACCEPTED/UPO/writeback zachowane. Brak nowego SEND po timeout/UNKNOWN, rezerwacja zostaje |
| A18: wycieki | Stałe kody KsefFailure bez SDK cause/body, bezpieczne 422/409/502; brak nowych publicznych pól sekretów lub XML |
| A19: outbox i nieograniczone retry | `invalid` ma retryable=false; process zapisuje RECONCILIATION_REQUIRED/errorCode w osobnym commit, release claim, handler kończy kontekst. Stan jest FINAL; ponowna dostawa no-op. Restart wake nie wybiera tego stanu. Błąd infrastruktury zapisu ma zwykłą obsługę CAP, nie jest policy retry |
| A20: status użytkownika | `KsefOperations` udostępnia status i bezpieczny errorCode; mapper UI wybiera RECONCILIATION_REQUIRED jako rozstrzygający, reguły blokują resend. Status ma charakter wymagającego decyzji, nie nieskończonego QUEUED |

Dodana w ORCH-09 regresja wzmacnia istniejące 4 przypadki rzeczywistego guardu: wywołuje `onSubmit`,
sprawdza completed, autoryzowany GET OData status/errorCode, stałą liczbę outbox/history po duplikacie,
brak auth/session/writeback. Collector pozostaje wyłączony w tym teście; nie jest to pomiar timingu
collectora HANA/CF. Test focused 75/75 PASS; pełny build finalny poniżej.

Uwagi przed SEND: brak atomowego/kompletnego runtime READ kompozycji SAP i ABA; fingerprint obserwacji
nie jest trwałym approval; Header CAS nie chroni dzieci; SDK/JDK retry i długi lease wymagają wire-testów.
Mogą pozostać orphans storage po rollback DB. Cztery niższe suites mockują guard do testowania recovery;
testy z rzeczywistym guardem są osobno. Żadna z tych uwag nie uzasadnia odblokowania SEND.

## Review PR #27 — PASS WITH NOTES

| Punkty zlecenia | Dowód / ocena |
| --- | --- |
| B1–2: G1 nowy/stary | Osobny `hdi-catalog-stor04.cjs` 16/5/6/16; stary `hdi-catalog-readonly.cjs` 15/5/6/15 bez diffu. Default G1 nie został podmieniony |
| B3–7: PK, storage, LAB, dokładność, BlobService | Ścisły manifest nazw/PK i 8 kolumn storage; 9 kolumn LAB kwalifikowanych tabelą. Braki/nadmiar/duplikat/zły schemat/PK/nullability/typ/długość → MISMATCH. Zachowana walidacja dokładnej definicji BLOBSERVICE_DOCUMENTS. Bez wildcard/dual acceptance/MISSING=PASS |
| B8–9: task i runtime hash | Deterministyczny runtime Terser+Brotli; checkedTaskRequest odmawia >4096, bez obcięcia. Stary 4071 i SHA `6ebdfd14863cc7a69514e3065759712c3485d51053c052b7b9e68a632aad5785`; nowy 4055 i SHA `e2d8a6972e4ae305e4aeadca2d5a282769928f6a93681a8e3ccd9e0aee39d0b8`. Pakowany runtime testowany także negatywnie |
| B10–13: baseline, 0.7.3→0.7.4, +1/0/0, undeploy | Wersjonowany manifest wdrożonego MTAR/payloadu, 28 istniejących artefaktów, hash nowego DDL `27cac1a20896f4e7971198c72e2024d48fa3e7f1e5f3d93c2e7ed2a77304de6a`. Verify wymaga dokładnie +1, 0 zmian i 0 usunięć; package/lock zgodne po LF. Zakaz undeploy/data/grants/roles/auto-undeploy |
| B14–16: LAB/BlobService/bean/REAL HANA | Brak zmian CDS LAB, BlobService i jego handlera; nowy overload storage package-private tylko dekoruje SPI, produkcyjna fabryka używa identity. Brak beana/wiring; IT opt-in i suffix IT poza standardowym Maven |
| B17–19: runner, DML, fixture | Pin commitu w runner.jar, dirty tree odmowa taska, TEST/HDI/target/bindingi/OFF/CI guard przed Spring, odrębne zgody faz. Allowlista ≤8 nowych kluczy; roundtrip 3 wiersze, contend 1 (8 prób konkurencyjnych + kontrolowany duplikat, nie 1 instrukcja SQL). Bez UPDATE/DELETE/cleanup. XML jawnie SYNTHETIC-NOT-AN-INVOICE z UUID runu |
| B20: Maven/CI a LIVE | CI wyłącznie offline/loopback Azurite; harness HANA niewybierany domyślnie, dodatkowe zgody i managed bindings. Merge nie uruchamia CF deploy ani SQL/H01/KSeF |

Uwagi: 4096 jest zachowawczym limitem projektu, nie potwierdzoną w tym audycie granicą CAPI.
H2 nie dowodzi HANA JDBC/BLOB/409006/locków. HDI `_RT` ma szersze uprawnienia niż INSERT/SELECT adaptera.
Free Tier nie jest archiwum z backupem/WORM. Stan wdrożonego katalogu/baseline z wcześniejszych dowodów,
bez ponownego pomiaru LIVE w ORCH-09. Release BLOB nie realizuje owner-led laboratorium.

## R-1 — `cf deploy -m`: ustalenia i blokada wykonania

Lokalne `cf plugins` i **wyłącznie help** `cf deploy --help`: MultiApps **3.12.0**;
[oficjalny release](https://github.com/cloudfoundry/multiapps-cli-plugin/releases/tag/v3.12.0).
Sprawdzono oficjalny kod klienta tagu v3.12.0, commit `fdffea57ae42ee7755d00956be544a19def4b75d`,
i publiczny kod kontrolera `f865482a604609ebde3670c821be8ec96ceb00b4` (10.11.2026).
Klony diagnostyczne poza repo; nie wykonano żadnego deployu ani odczytu CF targetu.

- [Dokumentacja SAP](https://help.sap.com/docs/BTP/65de2977205c403bbc107264b8eccf4b/65ddb1b51a0642148c6b468a759a8a2e.html)
  opisuje selekcję modułów/zasobów przez `-m`/`-r`. Klient przekazuje `modulesForDeployment`.
  **Istotna niepewność zasobów:** w tagu v3.12.0 `createMtaElementsCalculator` przy braku `-r`
  ustawia `shouldAddAllResources=true`; `setModulesAndResourcesListParameters` nie wysyła wtedy
  `resourcesForDeployment`. Badany kontroler (`ResourcesCloudModelBuilderContentCalculator`)
  przy wartości null przepuszcza wszystkie zasoby. Nie można zatem obiecać, że samo `-m`
  wyklucza przetwarzanie metadanych zasobów. Zależności/binding istniejącego deployera i zakres
  zasobów trzeba wyjaśnić dla targetu przed zatwierdzeniem polecenia; nie dodano samodzielnie `-r`.
- [ModulesToUndeployCalculator](https://github.com/cloudfoundry/multiapps-controller/blob/f865482a604609ebde3670c821be8ec96ceb00b4/multiapps-controller-process/src/main/java/org/cloudfoundry/multiapps/controller/process/util/ModulesToUndeployCalculator.java)
  kwalifikuje do usunięcia także moduł **nieobecny w pełnym deskryptorze** albo oznaczony skip-deploy.
  Wniosek z kodu: pełny MTAR z srv/AppRouterem, bez skip-deploy, z selekcją wyłącznie deployera,
  pozostawia te aplikacje poza deploymentem i undeploymentem. Sam `-m` nie jest ochroną przy
  obciętym deskryptorze. Nie tworzyć MTA o ID ainvoicer z samym deployerem.
- Metadane są per entity. Wybrany deployer dostaje wersję 0.7.4, nietknięte aplikacje zachowują
  stare metadane/droplety. [MtaMetadataEntityAggregator](https://github.com/cloudfoundry/multiapps-controller/blob/f865482a604609ebde3670c821be8ec96ceb00b4/multiapps-controller-core/src/main/java/org/cloudfoundry/multiapps/controller/core/cf/metadata/entity/processor/MtaMetadataEntityAggregator.java)
  ustawia zbiorczą wersję **null przy różnych wersjach**, a nie gwarantowane 0.7.4.
  [CollectSystemParametersStep](https://github.com/cloudfoundry/multiapps-controller/blob/f865482a604609ebde3670c821be8ec96ceb00b4/multiapps-controller-process/src/main/java/org/cloudfoundry/multiapps/controller/process/steps/CollectSystemParametersStep.java)
  w takim przypadku ostrzega i traktuje operację jako UPGRADE, ignorując zwykłe porównanie wersji.
  Wniosek: `cf mta`/version-rule nie mogą stanowić dowodu zawartości ani ochrony przed downgrade
  po częściowym release; potrzebny jawny inwentarz modułów/SHA/dropletów i historia operacji.
- Błąd/timeout: zatrzymać pracę, ustalić ID/stany operacji/tasków i katalog. `--retries 0` ogranicza
  retry klienta, nie zapewnia rollbacku HDI. Abort wymaga odrębnej decyzji i nie cofa już zapisanej
  tabeli. Bez retry/pełnego redeployu/starego HDI/undeploy/drop jako automatycznego recovery.
  `rollback-mta` z help jest eksperymentalnym mechanizmem backupu blue-green, nie rollbackiem tej DDL.

**R-1: BLOCKED dla zgody wykonawczej LIVE.** Semantyka zachowania pominiętych aplikacji potwierdzona
w dokumentacji i badanym kodzie, ale nie zweryfikowano wersji/zgodności kontrolera EU20 ani bieżącego
pełnego deskryptora i metadanych targetu. Ustalona semantyka mixed-version wymaga zatwierdzonego
planu inwentarza/recovery; nie obiecujemy, że istniejący runbook i sama etykieta 0.7.4 go zastępują.
To bloker **deploymentu**, nie integracji offline PR #27. Do zamknięcia: osobno autoryzowany read-only
preflight kontrolera/pełnego deskryptora/modułów/bindingów/checksum oraz akceptacja mixed-version recovery.

## Commands Executed / Tests & Results

Windows, Node 24.13.0/npm 11.6.2, Microsoft JDK 25.0.4.1, Maven 3.9.16;
CAP 5.2.0/Boot 4.1.1/SDK MF 3.0.29 bez zmiany wersji. CF CLI/MultiApps tylko lokalne plugins/help.
Polecenia w worktree ORCH-09, logi poza Git jako `D:\AInvoicer-orch-09-*.log`.

| Weryfikacja | Wynik |
| --- | --- |
| `git fetch`, `worktree add`, lokalne `merge --no-ff` | PASS; main+#26+#27 bez konfliktów |
| Maven `clean verify` wewnątrz smoke build | 671 run / 0 failures / 0 errors / 6 skipped, BUILD SUCCESS; przed rozszerzeniem 4 testów |
| Focused `mvn -B verify -Dtest=KsefSendRequestTest -Dsurefire.failIfNoSpecifiedTests=false -Dcds.cds.skipVersionCheck=true` | 75/75 PASS, po rozszerzeniu asercji dostawy/OData |
| `npm test --prefix deploy/storage` | Azurite loopback: 6/6 PASS, bez HANA/KSeF |
| `npm run test:runner --prefix sapui5/ainvoicer` | 8/8 PASS |
| Deployment tests po instalacji CDS | 198 PASS / 2 SKIP (Go i jeszcze niezbudowany kandydat MTAR), 0 failures; końcowy run po MTAR poniżej |
| Finalny `npm run build:btp:smoke --prefix deploy` | PASS, także `clean verify` po nowych asercjach: 671/0/0/6, CF JAR security 10/10, MTAR 0.7.2 i dowód źródeł PASS |
| `npm run build:btp:hana`, `verify:hana`, `verify:stor04-release --prefix deploy` | PASS, MTAR 0.7.4; +1 artefakt, 0 zmian/usunięć, bez deployu |
| Końcowe `npm test --prefix deploy` po MTAR | 199 PASS / 1 SKIP Go, 0 failures; obejmuje stary G1 63, nowy 86, release contract 2 i release verifier 5 |
| UI5 `build`, `test:cf-build` | PASS / 13/13 PASS; AppRouter/security także w buildzie smoke/deployment tests |
| QUnit / OPA na własnym serwerze 18096 | 112/659 asercji / 21/150 asercji PASS; mock SAP, bez LIVE |
| `npm run test:inbox:e2e --prefix sapui5/ainvoicer` | 16 testów / 73 asercje PASS, rzeczywisty lokalny CAP/H2/UI5; A/B, role/granty, outage/restart |
| R2 `assemble()` po finalnym clean verify + `hana-it-runner.mjs test` + self-test | 6/6 PASS + `R2_OFFLINE_SELF_TEST=PASS`; packaging bez niepotrzebnego ponownego Maven |
| Secret scan / diff --check | PASS; skan heurystyczny, po stage także nowe dokumenty (końcowa liczba w raporcie) |
| LIVE | NIE WYKONANO |

## Problems Encountered

Pierwsze deployment tests uruchomiono podczas Maven npm-ci: 4 MODULE_NOT_FOUND, 194 PASS / 2 SKIP.
Rerun po instalacji 198 PASS / 2 SKIP. To kolizja narzędzi modyfikujących node_modules, bez poprawki kodu.
Pierwszy smoke zbudował Maven/JAR/runtime/UI5/MTAR, lecz końcowy release evidence prawidłowo odmówił:
w trakcie dodano asercje testu będącego wejściem hasha źródeł. HANA build odmówił zależnego
verify:release. Nie uznano tych prób za PASS; finalny smoke uruchomiono ponownie po ustaleniu źródeł.
Nie zmieniano walidatora dowodu ani nie obchodzono hasha. Nieaktualne artefakty nie są do deploymentu.

## Changed Files / Decisions Made

Zastane zmiany #26 (15 plików) i #27 (20 plików) zachowano w historii. ORCH-09 dodaje ten dziennik,
projekt E2E-01C i uściślenie ADR-0010 (nadal PROPOSED), wzmacnia asercje `KsefSendRequestTest`;
opis R-1 w runbooku odsyła do dowodów powyżej. Bez zmiany wykonywalnego kodu produkcyjnego/CDS.
STATUS/ROADMAP zaktualizowano dopiero po potwierdzonym merge, zgodnie ze zleceniem.
Wygenerowane target/node_modules/MTAR/runner pozostają ignorowane i niewdrożone.

## Current State / Merge

Review obu workstreamów PASS WITH NOTES, brak uwag krytycznych do integracji przy utrzymaniu guardów.
CI dokładnych wejściowych HEAD SUCCESS. Wspólna regresja **OFFLINE PASS**.
Merge wykonano przez GitHub API z `expected_head_sha`, metodą merge, w kolejności #26 → #27.
Bez force, push main, zmiany protection, bypassu ani auto-merge. Workflow zawiera wyłącznie offline build/tests.

| PR | Wynik | Merge commit |
| --- | --- | --- |
| #26 | MERGED | `40e6c1299204f64ecb40a564479571da239fa75a` |
| #27 | MERGED | `8e6a5c6f8f3249ecd4c07358ee4a011c238dcced` |

**Main po integracji workstreamów:** `8e6a5c6f8f3249ecd4c07358ee4a011c238dcced`.
Osobny PR ORCH-09 publikuje dodatkowy test i dokumentację; wymaga własnego CI dokładnego HEAD.
Jego merge nada późniejszy SHA main, którego nie można zapisać we własnej treści commita.
Końcowy SHA i workflow push dokładnie na nim są sprawdzane po merge i podane w raporcie sesji
oraz komentarzu pod PR. Brak wyniku CI nie jest PASS; bez nowego full test run poza CI po samych docs.

**MERGED (workstreamy); OFFLINE PASS; LIVE PENDING; SEND BLOCKED; HANA DEPLOY PENDING; R-1 BLOCKED LIVE.**
Projekt E2E-01C rekomenduje C; właściciel decyduje o gwarancji obserwacji, odwołaniu do arm,
ważności/polityce/principal oraz CAP-only wyniku do czasu SAP atomic composition CAS.

Lokalne, ignorowane artefakty z drzewa review (bez zgody na deployment, evidence dirty=true):

| Artefakt | SHA-256 |
| --- | --- |
| Smoke MTAR 0.7.2 | `b5753ea80a8c7ad774524e2b1f1fd9f6521b6f5669eddf09a28fb58974722b21` |
| HANA MTAR 0.7.4 | `ea3bbb295b7964694724f49007802f54e9b079907dbe488bb5c7bf836cbfb4b1` |
| HANA HDI payload | `ecbeffea149216406b64a6190ab5a10ab759f6651dfa552b7da164df82e0c7b0` |

Pełny MTAR zawiera także nowy CAP/AppRouter. Nie jest zatwierdzonym deploymentem samej tabeli.
Przyszły release wymaga czystego, scalonego SHA, ponownego build/verify i osobnej zgody na dokładny zestaw.

## Next Steps

1. Domknięcie ORCH-09: PR dokumentacji/regresji i sprawdzenie CI dokładnego finalnego main w raporcie sesji.
2. Właściciel: przyjąć/odrzucić proponowaną semantykę ADR-0010/E2E-01C, potem osobno zlecić implementację.
3. Osobny read-only preflight R-1/targetu i CURRENT G1 po konkretnej zgodzie. Dalej osobno: czysty artefakt,
   zgoda HDI/NEW G1, G2 runnera, poszczególne fazy DML/recover. SAP H01 READ/SEND i writeback mają niezależne bramki.
