---
title: "INT-03 — integracja security/storage i gotowość HANA offline"
date: 2026-11-11
type: journal
status: offline-pass
tags: [ainvoicer, int-03, cap-java, security, storage, hana]
---

# INT-03 — integracja i dowody

## Date / Branch / Goal

2026-11-11, Europe/Warsaw. Worktree `D:\AInvoicer-int-03`, branch `feature/int-03-security-storage-hana`.
Integracja trzech wyników pracy, zamknięcie luki tenantów i przygotowanie HANA offline bez zmian środowiska.
[Audyt](../audits/2026-11-11-int-03-security-persistence.md) · [Runbook HANA](../guides/HANA_CLOUD_CAP_DEPLOYMENT.md).

## Initial State / Git integration

Główny worktree `D:\AInvoicer` na czystym `main=f093af5`; niezmieniony. Wykonano fetch/prune,
worktree list, branch -a, graph log i merge-base. Wszystkie trzy branche mają merge-base `f093af5`:
CAP-07D `2455c3f`, storage `3502d00`, HANA docs `e99254e`; nowszych commitów nie było.
PR #7 OPEN, mergeable i historyczny CI końcowego HEAD SUCCESS potwierdzone przez GitHub connector.

Merge bez konfliktów, bez ours/theirs i bez squash:

- `8b11529` — CAP-07D;
- `7d00c08` — KSEF-STOR-01;
- `fa7ca9d` — as-built HANA Free Tier.

PR INT-03 zawiera pełną historię PR #7; nie zamykamy ani nie mergujemy #7.
Jeśli #7 zostanie wcześniej scalony do main, wykonać fetch + merge aktualnego origin/main do INT-03,
review diffu i ponowną adekwatną regresję. Git rozpozna wspólne commity; bez cherry-pick, force push lub rebase historii agentów.

Commity implementacji: `0ab7c2a` (tenant isolation), `1d54adb` (storage body deadline),
`68595ee` (HANA readiness/harness/CI). Branch wypchnięto bez force push.
[PR #8 do main](https://github.com/scaamp/AInvoicer/pull/8) zawiera całą integrację;
[CI konkretnego HEAD](https://github.com/scaamp/AInvoicer/pull/8/checks) jest osobnym dowodem względem lokalnego PASS.
Końcowy odczyt statusu/run SHA jest podawany w opisie PR; link checks pokazuje aktualny stan także po kolejnych zmianach.

## Implementation / Changed Files

- `security/DedicatedTenantPolicy`, `CloudIdentityGuard`, `InboundOverviewHandler`, JWT tests:
  jeden tenant gate w dekoratorze oficjalnego decodera SAP, bez osobnej polityki Inbox.
- `CloudPersistenceGuard`, nowy `CloudHanaReadinessGuard`, lazy outbox w worker/writeback:
  wymagany HDI/HDB, readiness bez fallbacku H2 i bez outbox delivery/wake.
- Azure client + testy HTTP: bounded full-body deadline/cancellation i test zatrzymanego body.
  Pozostały kontrakt/scope/storage bez aktywacji.
- `deploy/scripts/hana-readiness.mjs` + testy: wariant existing-service z jednego MTA,
  kolejność deployer/CAP/approuter, walidacja archiwum i SHA evidence.
- Oddzielny `hana-integration`/`HanaPersistenceIT`, launcher i testy bramek: kompilacja offline,
  realna HANA wymaga nowego zatwierdzonego uruchomienia.
- `deploy/storage/` + lock: Azurite/SDK Azure do loopback; CI dodaje emulator i HANA MTAR offline.
- STATUS/ROADMAP/README, runbook, audyt, niniejszy dziennik, ADR-0006/0007 aktualizacje i propozycja ADR-0008.
  Bez zmian AGENTS.md, SAP, CDS modelu danych i źródłowego wariantu identity-smoke.

## Commands Executed / Tests & Results

Narzędzia lokalne: Microsoft OpenJDK **25.0.4.1**, Maven **3.9.16**, host Node **24.13.0**,
Node CDS Maven plugin **24.22.0**, CAP Java **5.2.0**, Spring Boot **4.1.1**, SAP security **4.1.2**.
SDK KSeF **3.0.29** zachowane, bez zmiany protokołu lub wywołań MF.

Wykonane odczyty CF portable8.19.0: `target`, `services`, `marketplace -e hana`,
`marketplace -e hana-cloud`, `apps`. Właściwe org/space, jedna widoczna `ainvoicer-hana-dev/hana-free`,
brak `hana/hdi-shared`, kontenera i bindingu CAP. Nie wykonano `cf env`, nie pobrano credentiali.
As-built SQL/parametry bazy pochodzą od właściciela; nie powtarzano SQL.

Testy przyrostowe: JWT tenant + scope, guardy startup, H2 wiring bez outbox, storage pipeline/recovery,
stalled response body, generator/walidator MTA, bramki HANA consent. Pierwsza próba QUnit wystartowała
przed gotowością serwera → ENVIRONMENT; powtórzona po `Server started` PASS.

| Weryfikacja | Wynik lokalny |
| --- | --- |
| `mvn -f cap-java/pom.xml clean verify -Dcds.cds.skipVersionCheck=true` | **425: 419 PASS, 6 skipped, 0 failures/errors** |
| JWT/OData w pełnej regresji | **37/37**, w tym tenant gate i rzeczywisty batch własny/obcy key |
| `npm ci/build/build:cf/test:cf-build` UI5 | PASS; CF artefakt **13/13** |
| UI5 QUnit / OPA (`serve-mock`, `npm test`) | **112/112 (659 asercji)** / **21/21 (150)** |
| UI5 `test:runner` / `test:inbox:e2e` | **4/4** / **16/16 (73)**, rzeczywiste lokalne HTTP, A/B i restart |
| AppRouter `npm ci`, `test:config` | **10/10** |
| Deploy `npm ci`, `npm test` | **27/27** |
| CF executable JAR `test:cf-runtime` | **10/10** |
| Azurite `npm ci --prefix deploy/storage --ignore-scripts`, `npm test --prefix deploy/storage` | **6/6**, osobny run; te same testy skipped w zwykłym verify |
| `npm run build --prefix cap-java/db` | PASS, artefakty HANA wygenerowane offline |
| Sekrety / whitespace | **PASS**, scan2634 śledzonych plików po staging wszystkich zmian; `git diff --check`/cached PASS |
| Smoke / HANA MTAR build + verify | **PASS OFFLINE**, MBT1.2.49, 2/3 moduły, HANA14 tabel/6 indeksów/4 widoki |
| PR / CI | [PR #8 CREATED](https://github.com/scaamp/AInvoicer/pull/8); rzeczywisty wynik konkretnego HEAD w [checks](https://github.com/scaamp/AInvoicer/pull/8/checks) i opisie PR |

### Artefakty lokalne i SHA-256

| Artefakt (w `deploy/build/`) | Rozmiar | SHA-256 |
| --- | --- | --- |
| `ainvoicer_0.7.2.mtar` | 67 551 888 B | `eedd6a311ffaaa97b83706a74bf42271fcc0813eb5abb6d5ea1462cf3579325c` |
| `ainvoicer_0.7.3.mtar` | 67 563 214 B | `30a0865d065ac33955365f4a0a952eb396274a6e14d3650a50abed715f9572b9` |

Wspólny CAP JAR: `e25828dd8b98e87182d3a9967ab4a7c05f702747796402ee195b68af14f24073`.
HDI payload: `f6d4dffbf8f367cbbc2beed732407b93122f697abb3e53517c36df612599dd83`.
Manifesty: `release-evidence.json`, `hana-release-evidence.json`; deskryptory `identity-smoke.mtad.yaml`,
`hana-readiness.mtad.yaml`. Wspólny hash wejść:
`efc8ad1dd316150aa1dd94cb71c822ec4d65d9b13d1bb57a3a73b41a2f7573a3`.

Lokalne paczki powstały przed commitami implementacji: manifest poprawnie wskazuje `fa7ca9d` +
`workingTreeDirty=true`, a nie nieprawdziwy czysty commit. Hash obejmuje rzeczywiste nowe/zmienione źródła.
Po commitach do `68595ee` ponownie wykonano `verify:release` i `verify:hana`: **PASS**, wejścia i payloady
identyczne. Zmiany dokumentacyjne nie należą do hashy runtime. CI buduje własne artefakty czystego HEAD;
ich timestampy i SHA mogą się różnić. Niczego nie wdrożono.

Nie wykonywano profilu `hana-integration`; IT jest wyłącznie skompilowany. Lokalne H2/mock/HTTP i emulator
nie są dowodem LIVE HANA/BTP/SAP/KSeF.
`npm ci` zgłosiło zastane 74 advisories toolingu UI5 i 4 moderate w izolowanym testowym stosie Azurite;
AppRouter/deploy: 0. Nie wykonywano szerokiego `npm audit fix --force` ani nie zmieniano runtime UI5.

## Problems Encountered / Decisions Made

Test storage wykazał, że timeout `HttpRequest`/stream nie obejmował zatrzymanego body.
Nowy test najpierw FAIL, po pełnym deadline/cancel PASS; asercji nie osłabiano.
Test braku HANA zaktualizowano do wcześniejszej odmowy braku bindingu HDI; reguła odrzucania H2 nadal testowana.
Skorygowano format multipart fixture batch, zachowując oczekiwania 200 własnego/404 obcego rekordu.
Przejściowe błędy kompilacji rozszerzanego harness naprawiono przed regresją, bez uruchomienia HANA.
Pierwsze pakowanie smoke po zakończonej regresji Maven425/CF10 zatrzymał Windows EPERM: serwer UI5
trzymał natywny moduł Zowe podczas `npm ci`. Zatrzymano wyłącznie własny serwer lokalny i ponowiono build;
nie zmieniano asercji i nie dotykano uruchomionych aplikacji CF.

Wariant HANA celowo wymaga już istniejącego HDI, zamiast tworzyć go w MTA; provisioning pozostaje po
bramce kosztowej i zgodzie właściciela. `deployed-after` chroni start CAP przed niegotowym schematem.
Propozycja BLOB ma osobny niezatwierdzony ADR; nie zmienia właściciela danych ani AGENTS.md.

## Current State / Next Steps

**HANA Free Tier LIVE / HDI NOT CONFIGURED / CAP HANA NOT CONNECTED.**
LIVE HANA CAP, schema deployment, HDI creation, CF deploy/restart, SAP WRITE, SEND i INBOUND LIVE:
**NOT EXECUTED**. Object Store provider OFF. Nie aktywowano żadnej płatnej usługi.

Następny najmniejszy krok: właściciel potwierdza bezpłatny `hdi-shared` i Database ID/mapowanie,
następnie osobno zatwierdza CAP-HANA-01 (dedykowany HDI + konkretny MTAR/SHA + syntetyczny harness).
LIVE principal/DCL/A/B i decyzja ADR-0008 pozostają odrębnymi bramkami przed workflow KSeF.
