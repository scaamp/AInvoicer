---
title: "BETA-01 — gotowość AInvoicer Beta 0.1 (KSeF INBOUND)"
date: 2026-11-23
type: guide
status: offline-candidate-live-blocked
tags: [ainvoicer, beta, inbound, ksef, hana, sapui5, security, release]
---

# BETA-01 — od prototypu do kandydata Beta 0.1 (KSeF INBOUND)

[Project Home](../README.md) · [STATUS](../STATUS.md) · [BETA_ROADMAP](../BETA_ROADMAP.md) ·
[Dziennik](../journal/2026-11-23-beta-01-inbound-readiness.md) ·
[KSEF-IN-05 — storage/import/download](KSEF_IN_05_PRIVATE_INBOUND_STORAGE.md) ·
[UI5 Inbox CF beta](BETA_01_UI5_INBOX_CF.md) · [STOR-04R release](KSEF_STOR_04R_HANA_RELEASE_READINESS.md)

> **Werdykt 23.11.2026:** techniczny kandydat Beta 0.1 **OFFLINE**. CF PRECHECK **BLOCKED_AUTH**, R-1 **R1_BLOCKED**.
> Beta 0.1 **NIE jest READY FOR LIVE ACCEPTANCE** i **NIE jest USER-READY**. Nie wykonano żadnej operacji LIVE.

Branch `feature/beta-01-inbound-readiness` z `origin/main=603e171b5467f6d1600193eed666a91e9297b3a9`
(po `git fetch` nadal najnowszy; CI tego SHA, run 43739912629, sprawdzone przez API GitHub: **SUCCESS**).
Prace równoległe w osobnych worktree: Agent B (`beta-01/in-05-storage`), Agent C (`beta-01/ui5-inbox`), scalone
`--no-ff` do brancha feature po review orkiestratora.

## 1. Co zostało rzeczywiście zaimplementowane?

| Obszar | Implementacja | Domyślnie |
| --- | --- | --- |
| Wiring prywatnego storage | `KsefInboundHanaStorageConfiguration` → bean `InboundDocumentStorage` (scope `INBOUND/TEST`, `allowedEnvironments={TEST}`), celowo **nie** typu `KsefDocumentStore` → brak kolizji z `LocalTestDocumentStore`/OUTBOUND. HANA rozpoznawana po produkcie JDBC `HDB`; fail-closed: `KSEF_INBOUND_STORAGE_HANA_REQUIRED`, `…_HANA_REQUIRED_ON_CLOUD` (H2 w chmurze nawet z `allow-local-h2`), `…_ENVIRONMENT_UNSUPPORTED`, `…_DATABASE_UNREACHABLE`. | `ainv.ksef.inbound.storage.hana.enabled=false` |
| Pipeline INBOUND | Bez zmian semantyki: `KsefInboundConfiguration` używa dedykowanego magazynu priorytetowo. Guard `KSEF_INBOUND_CLOUD_ACTIVATION_NOT_READY` **nietknięty** (test: także z magazynem, schedulerem i importem na CF). | sync OFF, scheduler OFF |
| Manual import | `InboundManualImport.importOne(ksefNumber)` — wewnętrzny serwis Java (bez akcji OData, bez schedulera): walidacja numeru/okna przed auth, lease wspólny z synchronizacją, metadata ×1 z filtrem `ksefNumber`, GET ×1 bez retry, trwały status w `KsefInboundDocuments`/`KsefInboundSyncLeases`, checkpoint sync nietknięty, niejednoznaczny auth → `BLOCKED`. | `ainv.ksef.inbound.manual-import.enabled=false` (+ wymaga `inbound.enabled`) |
| XML Download (backend) | `GET /odata/v4/KsefService/InboundInvoiceXml(<uuid>)/content` — media stream CAP (encja `@cds.persistence.skip`, handler `@On READ`), wspólny `InboundGrantScope` z Overview. | `ainv.ksef.inbound.xml-download.enabled=false` (→ 404) |
| Chmura | `CloudHanaReadinessGuard` odrzuca start przy jawnym `true` dla `storage.hana.enabled` lub `xml-download.enabled`. | fail-closed |
| UI5 opt-in build | `npm run build:cf:inbox-beta` (→ `dist-cf-inbox-beta/`), opcjonalnie `-- --with-xml-download`. Domyślne `build:cf` bajtowo identyczne z baseline (manifest, index, 71 plików). | Inbox disabled w `build:cf` |
| UI5 UX | 401/strona logowania (`LOGIN_REQUIRED`, link ponownego logowania), 403, 404 usługi, 5xx bez treści serwera, pusty Inbox, XSS (tekst, nie HTML), przycisk Download tylko przy `viewXml && xmlAvailable`, URL wyłącznie z UUID. | `viewXml=false` w obu buildach |

Szczegóły: [KSEF-IN-05](KSEF_IN_05_PRIVATE_INBOUND_STORAGE.md), [UI5 Inbox CF beta](BETA_01_UI5_INBOX_CF.md).
Bez zmian: `db/*.cds` (G1), pliki LAB/BlobService, `approuter/`, `mta.yaml`, OUTBOUND (`KsefInitialSendGuard`).

## 2. Co zostało zweryfikowane offline?

Wyniki z pełnej regresji orkiestratora na zintegrowanym branchu — [§11](#11-czy-wykonano-wszystkie-testy).
Dodatkowo test łączony orkiestratora `InboundPipelineToODataTest`: atrapa SDK KSeF → `PersistentInboundInvoiceSink`
→ bean `InboundDocumentStorage` (adapter `HanaKsefDocumentStore` na **H2**) → staging/grant/checkpoint →
lokalny CAP HTTP Overview (`xmlAvailable=true`, bez referencji) → download bajt w bajt (`attachment`, `no-store`);
obcy podatnik: pusta lista i 404; utrata checkpointu i odtworzenie okna: brak drugiego GET i duplikatu.

## 3. Czy CF PRECHECK zakończył się PASS?

**Nie — BLOCKED_AUTH.** Lokalny CF CLI 7.8.0 (z ostrzeżeniem o przestarzałej wersji) jest uwierzytelniony wobec API
**US10** (`api.cf.us10-001…`), bez targetu org/space i z „No orgs found”; plugin **MultiApps nie jest zainstalowany**
w tym środowisku. Docelowe API EU20 wymaga `cf login` — zgodnie z poleceniem **nie** logowano automatycznie i nie
proszono o credentiale. Nie wykonano `cf env`, `CF_TRACE`, service keys. Inwentarz aplikacji/usług/bindingów/MTA EU20,
stan HANA i rozbieżności względem 0.7.3 pozostają **NOT VERIFIED**. Lokalny `cf target` nie jest dowodem runtime.

## 4. Czy R-1 został zamknięty?

**R1_BLOCKED.** Analiza z ORCH-09 (MultiApps 3.12.0, kod klienta i kontrolera) pozostaje aktualna:

| Pytanie R-1 | Ustalenie | Dowód LIVE |
| --- | --- | --- |
| Zakres `cf deploy -m ainvoicer-db-deployer` | Pełny MTAR + selekcja modułu: pominięte `srv`/AppRouter nie są deployowane ani undeployowane (`ModulesToUndeployCalculator`) | brak (wersja kontrolera EU20 niezweryfikowana) |
| Zasoby bez `-r` | Klient nie wysyła `resourcesForDeployment` → kontroler przetwarza **wszystkie** zasoby (metadane/bindingi) | brak |
| srv/AppRouter | Bez zmiany dropletów i metadanych | brak |
| Mixed-version metadata | Agregat wersji `null`, operacja traktowana jako UPGRADE; etykieta `cf mta` nie dowodzi zawartości | brak |
| Undeploy | Ryzyko tylko przy obciętym deskryptorze (nigdy MTA `ainvoicer` z samym deployerem) | — |
| Zmiana bindingów | Możliwa przez przetwarzanie zasobów bez `-r`; wymaga odczytu deskryptora i bindingów targetu | brak |
| Recovery | STOP, `cf mta-ops`, stan taska, stary → nowy G1; bez retry/abort automatycznego | — |
| Target vs lokalny MTAR | **Niezweryfikowane** (BLOCKED_AUTH) | brak |

Nowe w BETA-01: Beta 0.1 wymaga **także** nowego `srv` (wiring/download) i UI (build beta), więc release Beta to
pełne MTA, a nie sam deployer. R-1 dotyczy wyłącznie kroku HDI; pełny deploy ma własną bramkę (smoke, rollback).

## 5. Czy CURRENT G1 pozostaje gotowy do osobnego zatwierdzenia?

**Tak (offline).** W izolowanym worktree `npm ci --prefix deploy` i `npm ci --prefix cap-java` usunęły dawne
61/63 (`terser`, a następnie brak `cap-java/node_modules` dla testu niezależnej kompilacji). Stary G1 **63/63 PASS**,
STOR-04 G1 **86/86 PASS**. `hdi-catalog-readonly.cjs --task-json`: task `cap-hana-02-hdi-catalog-readonly`,
`runtimeSourceSha256=6ebdfd14863cc7a69514e3065759712c3485d51053c052b7b9e68a632aad5785`, `commandLength=4071`
(≤ 4096), 256 MB / 512 MB — identycznie z baseline; kontrakt 15/5/6/15 bez zmian. **Task nie został wykonany.**
Kompilacja HANA po BETA-01: 27 artefaktów, 16/5/6 — encja downloadu `@cds.persistence.skip` nie dodaje widoku.

## 6. Czy private HANA storage jest poprawnie podłączony?

**Tak w kodzie i offline (H2), nie na HANA.** Wiring jest jawny, domyślnie OFF, TEST-only, fail-closed bez HANA i
w chmurze na H2, bez kolizji beanów i bez I/O przy starcie. Tabela `CUSTOMER_CAP_JAVA_KSEFSTOREDDOCUMENTS` nadal
**niewdrożona** — włączenie flagi na HANA przed HDI deploy skończy się błędami 5xx/`STORAGE_FAILED`. Na CF flaga jest
dodatkowo zablokowana przez `CloudHanaReadinessGuard` (świadoma decyzja do zdjęcia przy release Beta).

## 7. Czy XML → storage → metadata → Inbox działa end-to-end offline?

**Tak, offline:** test łączony (sekcja 2) + Inbox E2E na prawdziwym lokalnym CAP/H2 i UI5 (konfiguracja beta).
Ograniczenie: przycisk Download w UI5 testowany atrapą `fetch` (QUnit/OPA) — kontrakt URL jest ten sam co w testach
HTTP backendu, ale wspólnego testu przeglądarki z rzeczywistym downloadem nie ma (backlog).

## 8. Czy wariant CF Inbox jest gotowy?

**Artefakt offline — tak; deployment — nie.** `build:cf:inbox-beta` buduje i weryfikuje manifest oraz
`Component-preload.js` (dataMode `cap`, `InboundInvoiceOverview`, TEST, brak mock/fixture, cockpit RAP z
capabilities false, CSP `connect-src 'self'`). Trasa AppRouter `^/odata/v4/KsefService/(.*)$` (xsuaa, CSRF) obejmuje
Overview i download bez zmian. `mta.yaml` nadal pakuje `dist-cf` (domyślny) — MTAR z betą wymaga osobnej decyzji.

## 9. Czy download XML jest bezpieczny i zaimplementowany?

**Zaimplementowany za flagą OFF; bezpieczeństwo udowodnione offline.** Kolejność: 401 → 403 (`KsefOperator`) →
flaga (404) → wyłącznie bezpośredni GET `/content` (`$batch`/encja → 403; lista/filtr/`$count` → 400) → magazyn
podłączony (inaczej 503) → **grant w jednym CQN przed odczytem BLOB** (`ID`, TEST, `(NIP, BUYER)`, STORED/VERIFIED,
referencja z DB) → `store.get` w scope INBOUND/TEST + ponowne SHA-256/długość. Odpowiedź: oryginalne bajty,
`application/xml`, `Content-Disposition: attachment`, `Cache-Control: no-store`, `nosniff`. Obcy/nieistniejący →
jednakowe 404; awaria → 5xx bez szczegółów; logi tylko z kodami. Brak publicznego URL do HANA i HTTP do KSeF.
Niezweryfikowane: AppRouter/XSUAA LIVE (nagłówki po proxy, CSRF), HANA BLOB stream.

## 10. Czy działa izolacja dwóch użytkowników?

**Tak, offline (lokalny CAP HTTP, użytkownicy mock z atrybutami jak XSUAA):** A widzi swoje, B nie widzi A (404
nieodróżnialne), brak roli 403, brak uwierzytelnienia 401, obcy/wieloznaczny/błędny NIP i CompanyCode fail-closed,
obcy tenant 401 (profil cf, offline JWT), zgadywanie ID, `storageRef`/`sha256` jako klucz/filtr, `$batch`, mutacje,
XSS jako dane, brak XML/referencji/credentiali w logach. **Uczciwa semantyka:** CompanyCode jest kontekstem sesji,
nie własnością dokumentu INBOUND — ten sam NIP z inną spółką widzi te same dokumenty. Test LIVE w BTP: brak.

## 11. Czy wykonano wszystkie testy?

Wszystko **offline** na zintegrowanym branchu (Windows, Node 24.19.0, JDK 25.0.4.1, Maven 3.9.16), kolejność jak w CI;
logi poza Git. Standardowy build nie wykonuje żadnej operacji LIVE.

| Weryfikacja | Wynik |
| --- | --- |
| Maven `clean verify` (w `build:btp:smoke`) — w tym CAP security, storage, pipeline INBOUND, checkpoint/lease, integralność XML, download, A/B | **707 run / 0 failures / 0 errors / 6 skipped** (6 = Azurite bez emulatora w tym biegu), BUILD SUCCESS; baseline `main` 671 |
| Test łączony `InboundPipelineToODataTest` (focused) | 1/1 PASS |
| CF JAR security (pakowany JAR, fail-closed) | 10/10 PASS |
| Identity-smoke MTAR 0.7.2 + `validate:smoke` + release evidence | PASS (CF build `dist-cf`, `dataMode=rap`, capabilities OFF) |
| HANA MTAR `build:btp:hana` / `verify:hana` / `verify:stor04-release` | PASS / PASS / `STOR04_RELEASE_ARTIFACT=PASS hdiAdded=1 hdiChanged=0 hdiRemoved=0` |
| R2 runner `hana-it-runner.mjs build` + `test` + `runner.jar --self-test` | PASS, 6/6, `R2_OFFLINE_SELF_TEST=PASS` |
| Azure Storage emulator (loopback) | 6/6 PASS |
| Deployment tests `npm test --prefix deploy` (stary G1 63, STOR-04 G1 86, release, AppRouter, CI) | **199 PASS / 1 SKIP (Go) / 0 fail** |
| UI5 `build`, `build:cf`, `build:cf:inbox-beta` | PASS |
| `test:cf-build` (default + beta, także zbudowane artefakty) | 23/23 PASS (pierwszy bieg 22+1 skip przed zbudowaniem bety; powtórzony po buildzie) |
| `test:runner` | 8/8 PASS |
| QUnit / OPA (mock server) | **121 testów / 732 asercje** / **24 testy / 193 asercje** PASS (baseline 112 / 21) |
| Inbox E2E — prawdziwy lokalny CAP/H2 + UI5 w konfiguracji beta | **16 testów / 77 asercji** PASS (baseline 16/73) |
| Secret scan `check-secrets.mjs` | PASS (2745 plików, heurystyka) |
| `git diff --check` | PASS |
| GitHub Actions | wynik dokładnego HEAD PR — [dziennik](../journal/2026-11-23-beta-01-inbound-readiness.md) |
| LIVE (CF/HANA/KSeF/H01) | **NIE WYKONANO** |

Artefakty lokalne (ignorowane, `workingTreeDirty=true`, **nie do deploymentu**): smoke MTAR
`ea2138ed…09bb`, CAP JAR `5a878a63…1a05`.

## 12. Jakie pozostały blockery LIVE?

1. **BLOCKED_AUTH** — brak sesji CF EU20 (i pluginu MultiApps w tym środowisku).
2. **R-1** — brak odczytu kontrolera/deskryptora/bindingów targetu.
3. **HANA** — CURRENT G1, HDI deploy tabeli STOR-04, NEW G1 16/5/6/16, pomiary HANA (409006, BLOB stream, restart).
4. **Release `srv`+AppRouter+UI** — pełne MTA z kodem BETA-01 i buildem beta (dziś `mta.yaml` pakuje domyślny UI).
5. **Zdjęcie blokad chmurowych** — `CloudHanaReadinessGuard` (storage/download) i guard INBOUND dla importu:
   globalny budżet API między JVM, kontrola lease przed HTTP, decyzja o wywołującym (akcja z audytem vs zadanie operatora).
6. **Jeden rzeczywisty dokument** — test filtra `ksefNumber`/okna dat w KSeF TEST (one-shot), import, Inbox, download.
7. **Security sign-off LIVE** — CAP-07E, XSUAA A/B, logout/expiry, nagłówki po AppRouterze.

## 13. Jakie dokładnie kolejne zgody musi wydać właściciel?

Każda osobno, z dokładnym zakresem (SHA, polecenie, czas):

1. Odnowienie sesji CF EU20 przez właściciela + ponowny **read-only PRECHECK** (zakres jak dotąd) i decyzja R-1.
2. **CURRENT G1** — jeden task SELECT `cap-hana-02-hdi-catalog-readonly` (runtime `6ebdfd14…`, 4071).
3. Merge tego PR → czysty artefakt z dokładnego SHA `main` (`build:btp:hana`, `verify:hana`, `verify:stor04-release`).
4. **HDI deploy** tabeli STOR-04 (`-m ainvoicer-db-deployer --retries 0` po zamknięciu R-1) → **NEW G1**.
5. Decyzja architektoniczna: zdjęcie blokady `CloudHanaReadinessGuard` dla storage/download na CF i włączenie flag.
6. **Deployment pełnego MTA** (srv + AppRouter + UI z `build:cf:inbox-beta`, bez `--with-xml-download` lub z nim — decyzja).
7. Kontrakt otwarcia guardu INBOUND dla **jednego** manual importu (wywołujący, audyt, budżet, lease) i zgoda na
   **jeden** import KSeF TEST (NIP, numer, budżet żądań ≤ auth + 2).
8. LIVE smoke: Inbox, download, restart CAP, A/B w BTP — dopiero wtedy decyzja USER-READY.

## 14. Czy Beta 0.1 jest READY FOR LIVE ACCEPTANCE?

**NIE.** Brak PRECHECK EU20, R-1, HDI, deploymentu i otwarcia guardów. Kod jest technicznym kandydatem offline.

## 15. Czy Beta 0.1 jest już USER-READY?

**NIE.** Brak rzeczywistego deploymentu, trwałości danych na HANA i testów security w środowisku docelowym.

## KSEF-IN-04 — wynik LIVE (OWNER-REPORTED)

Przekazany przez właściciela; agent **nie** powtarzał wywołania KSeF. XAdES business status 200, JWT uzyskany
(nieujawniany), GET faktury HTTP 200, XML 4688 bajtów, `FA3_BASIC_PASS`, 6 z 7 dozwolonych żądań HTTP, JUnit 1 wykonany /
0 pominiętych, bez ponowień. SHA-256: `b94b1eee7ca6e73f8353fe9b4c0a1b204bc49bc9df15977b4387b6156ba1b3bd`.
Dokument **nie** został zapisany do storage/stagingu ani Inboxa — to był izolowany odczyt.

## Granica LIVE w BETA-01

Wykonano wyłącznie lokalne, read-only polecenia CF CLI (`cf version`, `cf plugins`, `cf target`, `cf orgs`) wobec
istniejącej lokalnej sesji US10 — bez zmiany API/targetu i bez logowania. Nie wykonano: HANA SQL/G1/HDI, `cf deploy`/
`push`/restart/task, KSeF auth/GET/SEND, SAP H01, service keys, IAM/XSUAA, schedulera, aktywacji INBOUND, SEND.
