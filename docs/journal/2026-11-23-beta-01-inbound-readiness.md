---
title: "Dziennik — BETA-01: kandydat Beta 0.1 KSeF INBOUND"
date: 2026-11-23
type: journal
status: zakonczony
tags: [ainvoicer, journal, beta, inbound, hana, sapui5, security]
---

# Dziennik — BETA-01 (KSeF INBOUND Beta 0.1)

[Project Home](../README.md) · [STATUS](../STATUS.md) · [BETA_ROADMAP](../BETA_ROADMAP.md) ·
[Raport BETA-01](../guides/BETA_01_INBOUND_READINESS.md)

## Date

2026-11-23 (Europe/Warsaw), wykonanie rzeczywiste tego dnia.

## Branch

`feature/beta-01-inbound-readiness` (worktree `C:\Users\jkupi\Documents\AInvoicer-beta01`) z `origin/main=603e171`.
Workstreamy: `beta-01/in-05-storage` (Agent B, worktree `…-beta01-b`), `beta-01/ui5-inbox` (Agent C, `…-beta01-c`);
scalone `--no-ff` po review. Checkout właściciela (`feature/abap-08-live-integration`, czysty) nietknięty.

## Goal

Z prototypu do technicznego kandydata **Beta 0.1 — KSeF INBOUND**: read-only CF PRECHECK (lub BLOCKED_AUTH), R-1,
G1 offline, wiring prywatnego storage, pipeline offline, minimalny manual import, opt-in build Inboxa na CF, bezpieczny
download XML, testy A/B, pełna regresja, `BETA_ROADMAP`, jeden PR bez merge. Zakaz LIVE poza odczytem metadanych CF.

## Initial State

`git fetch`: `origin/main` nadal `603e171b5467f6d1600193eed666a91e9297b3a9`; CI run 43739912629 tego SHA
sprawdzony przez API GitHub — **SUCCESS** (niezależnie od relacji właściciela). Przeczytano AGENTS/CLAUDE, STATUS,
ROADMAP, przewodniki STOR-04/STOR-04R, INBOUND, Inbox UI5, INT-02, IN-03B, CAP-07E, dziennik ORCH-09 i kod
(`KsefInboundConfiguration`, `LocalTestDocumentStore`, `HanaKsefDocumentStore`, sink, synchronizacja, handler Overview,
`build-cf.mjs`, `xs-app.json`). Potwierdzono: HANA store bez beana, guard CF bezwarunkowy, UI CF wymusza Inbox `disabled`.
Brak `gh` CLI w środowisku. KSEF-IN-04 LIVE PASS — **OWNER-REPORTED** (szczegóły w raporcie), nie powtarzano.

## Implementation

- **A (orkiestrator):** CF — `cf version/plugins/target/orgs` wyłącznie na istniejącej sesji lokalnej: CLI 7.8.0, API US10,
  brak org/space, brak pluginu MultiApps → **BLOCKED_AUTH** (bez `cf login`, `cf api`, `cf env`). R-1 → **R1_BLOCKED**.
  G1: `npm ci --prefix deploy` i `--prefix cap-java` w izolowanym worktree; stary G1 63/63, STOR-04 86/86, task JSON
  generowany (runtime `6ebdfd14…`, 4071), **niewykonany**.
- **B (KSEF-IN-05):** opt-in wiring `InboundDocumentStorage`, manual import za flagą, download XML jako media stream
  CAP za flagą, wspólny `InboundGrantScope`, testy pipeline/security. [Przewodnik](../guides/KSEF_IN_05_PRIVATE_INBOUND_STORAGE.md).
- **C (UI5):** `build:cf:inbox-beta` (+ `--with-xml-download`), weryfikator artefaktu beta, stany UX 401/403/5xx/login,
  przycisk Download, testy XSS. [Przewodnik](../guides/BETA_01_UI5_INBOX_CF.md).
- **Review/integracja:** przegląd diffów B i C (kontrakt download, kolejność kontroli, brak zmian G1/LAB/OUTBOUND,
  domyślny `build:cf` bez zmian); dodany test łączony `InboundPipelineToODataTest` (pipeline → bean storage → OData).
  Pierwsza wersja testu odtwarzała okno z metadanymi sprzed HWM — walidacja zakresu poprawnie zwróciła
  `KSEF_INBOUND_CORRUPT_RESPONSE_OUT_OF_RANGE`; scenariusz zmieniono na utratę checkpointu (najgorszy przypadek).

## Changed Files

Kod: patrz przewodniki B/C oraz `cap-java/srv/src/test/java/customer/cap_java/InboundPipelineToODataTest.java`.
Dokumentacja: `docs/BETA_ROADMAP.md`, `docs/guides/BETA_01_INBOUND_READINESS.md`, ten dziennik, nowe wpisy na górze
`docs/STATUS.md`, `docs/ROADMAP.md`, `docs/README.md` (historia zachowana). Bez zmian: `db/*.cds`, LAB, `approuter/`,
`mta.yaml`, `deploy/`, OUTBOUND. Wygenerowane `node_modules/`, `target/`, `dist-*`, MTAR — ignorowane, niecommitowane.

## Commands Executed

Bez `CF_TRACE`/`DEBUG`. Logi w katalogu tymczasowym sesji (poza Git). Skrypt regresji w kolejności CI:
`npm ci` (UI5, storage), `validate:smoke`, `check-secrets.mjs`, `build:btp:smoke`, `build:btp:hana`, `verify:hana`,
`verify:stor04-release`, `hana-it-runner.mjs build|test`, `runner.jar --self-test`, `npm test --prefix deploy/storage`,
UI5 `build`, `test:cf-build`, `test:runner`, `build:cf:inbox-beta`, `serve-mock` + `npm test`, `test:inbox:e2e`,
`npm test --prefix deploy`, `git diff --check`.

## Tests & Results

Pełna tabela: [raport §11](../guides/BETA_01_INBOUND_READINESS.md#11-czy-wykonano-wszystkie-testy). Skrót:
Maven **707/0/0/6**, JAR security 10/10, deploy **199 + 1 SKIP Go** (G1 63, STOR-04 86), QUnit **121/732**, OPA **24/193**,
Inbox E2E **16/77**, cf-build 23/23, runner 8/8, R2 6/6 + self-test, Azurite 6/6, oba MTAR, stor04 +1/0/0, secrets PASS,
diff-check PASS. Wszystko offline. **LIVE: NIE WYKONANO.**

## Problems Encountered

- G1 61/63 z poprzedniego preflightu: przyczyna potwierdzona — brak `deploy/node_modules` (`terser`) oraz
  `cap-java/node_modules` (test niezależnej kompilacji CDS). Po `npm ci` w izolowanym worktree 63/63.
- Pierwszy bieg `mvn verify` w tle został przerwany przed testami — nie liczony; wynik pochodzi z `build:btp:smoke`.
- `test:cf-build` w regresji: 1 skip, bo artefakt beta budowano po tym kroku; po buildzie 23/23.
- Środowisko nie ma `gh` CLI — PR [#29](https://github.com/scaamp/AInvoicer/pull/29) utworzony przez GitHub API.
- **CI PR #29, HEAD `41d40d9`, run 45187654430: FAILURE** (krok „identity-smoke MTAR”, Maven): test B
  `offlineH2SynchronizationKeepsBomCrlfAndIsIdempotentAcrossRestart` zakładał CRLF w fixture; lokalnie Windows
  `autocrlf=true` dawał CRLF, checkout CI ma LF. Poprawka `a7a2b09`: payload BOM+CRLF budowany jawnie. Odtworzenie
  lokalne z fixture LF: 89/0/0/0 testów inbound. **CI `a7a2b09`, run 45189036841: SUCCESS, 24/24 kroków.**
  Pozostałe nowe testy używają wyłącznie jawnych literałów `\r\n` (multipart) — niezależne od platformy.

## Decisions Made

- Download jako encja `@cds.persistence.skip` (brak widoku HDI → G1 bez zmian), nie `@RestController`.
- Storage/download zablokowane na CF przez `CloudHanaReadinessGuard` do osobnej decyzji; guard INBOUND bez zmian.
- Manual import bez publicznej akcji; filtr `ksefNumber` wymaga potwierdzenia LIVE.
- Beta UI jako osobny opt-in build; `mta.yaml` nadal pakuje domyślny `dist-cf`.

## Current State

Techniczny kandydat Beta 0.1 **OFFLINE**. CF PRECHECK **BLOCKED_AUTH**, R-1 **R1_BLOCKED**, CURRENT G1 gotowy do zgody.
**Nie READY FOR LIVE ACCEPTANCE, nie USER-READY.** PR i CI dokładnego HEAD — w raporcie sesji/komentarzu PR.

## Next Steps

1. Review i merge PR przez właściciela (bez auto-merge).
2. Właściciel odnawia sesję CF EU20 → read-only PRECHECK i decyzja R-1.
3. Kolejne zgody wg [raportu §13](../guides/BETA_01_INBOUND_READINESS.md#13-jakie-dokładnie-kolejne-zgody-musi-wydać-właściciel).

## BETA-01.1 — final hardening

23.11.2026, osobny czysty worktree `D:/AInvoicer-beta-01-1`. Początkowy HEAD PR #29 `c6d0232698daf3a4590d38815d873e815be293f0`
zawierał bieżący `origin/main=603e171b5467f6d1600193eed666a91e9297b3a9`. GitHub potwierdził SUCCESS obu SHA.

- **CI:** trzy kolejne obowiązkowe buildy CF. Każde uruchomienie egzekwuje komplet plików, konfigurację z obu manifestów,
  reguły capabilities/TEST, brak fixture i wzorce sekretów. Oba warianty beta są sprawdzane przed nadpisaniem katalogu.
  Test AppRouter potwierdza zachowanie ścieżki OData, XSUAA i CSRF. [Checki PR #29](https://github.com/scaamp/AInvoicer/pull/29/checks).
- **XML:** dostęp przez BUYER/TEST przed BLOB. Odbiorca dostaje oryginalne bajty, jeśli rozmiar `Integer64` jest dodatni,
  zgodny z długością i SHA-256 się zgadza. Błąd daje ogólne 500 bez szczegółów i bez naprawy BLOB. `$batch` odmawia XML,
  a odczyt Overview w `$batch` egzekwuje ten sam grant. Odczyt w UI5 używa ścieżki same-origin i nie przechodzi na mock.
- **CompanyCode:** prawidłowa, pojedyncza inna spółka tego samego NIP otrzymuje te same dokumenty przez Overview i XML.
  Operacyjna kontrola jednej zatwierdzonej pary wymaga statycznej roli i potwierdzenia efektywnych atrybutów XSUAA.
  Multi-company dla jednego NIP jest NO-GO. [Kontrakt](../guides/KSEF_IN_05_PRIVATE_INBOUND_STORAGE.md#6-b4--kontrakt-pobrania-xml-backend).
- **Manual import:** `NOT_FOUND` jest wynikiem sprawdzenia ograniczonego okna, bez zapisu negatywnego i bez przesunięcia HWM.
  Późniejszy jawny import jest możliwy. Auth może pobrać certyfikaty, wykonać polling i refresh. Test `OneShotWireRetryTest`
  potwierdza możliwy replay pod granicą SDK w domyślnym JDK. Twardy budżet HTTP pozostaje bramką LIVE. Cloud, H2,
  TEST-only, scheduler, OUTBOUND SEND i sposób naprawy lease zachowują istniejące reguły. [Analiza i patch transportowy](../guides/KSEF_IN_05_PRIVATE_INBOUND_STORAGE.md#5-b3--manual-import-jednej-faktury-implemented-behind-flag).

### Wyniki BETA-01.1

Windows, JDK 25.0.4.1, Maven 3.9.16, Node 24.13.0, CAP 5.2.0, SDK MF 3.0.29 (`3c3a899`), Go 1.26.10.

| Weryfikacja | Wynik offline |
| --- | --- |
| Maven `clean verify`, testy skupione | 35/35 PASS: XML HTTP 20, manual import 13, wire retry loopback 2 |
| `build:btp:smoke` (pełne Maven `clean verify`, JAR security, MTAR) | 715 uruchomień, 0 błędów, 6 pominiętych testów Azurite wykonanych osobno. JAR security 10/10 i MTAR PASS |
| `npm test --prefix deploy/storage` | Azurite na loopback 6/6 PASS |
| `test:cf-build` i `test:runner` | 23/23 i 8/8 PASS |
| UI5 `build`, `build:cf`, `build:cf:inbox-beta`, `build:cf:inbox-beta -- --with-xml-download` | PASS, każdy artefakt CF miał 71 plików |
| QUnit i OPA przez `serve-mock` | 121 testów / 732 asercje i 24 testy / 193 asercje PASS |
| `test:inbox:e2e` | 16 testów / 77 asercji PASS (lokalny CAP/H2 HTTP i restart, UI5) |
| `npm test --prefix deploy` | 201/201 PASS z Go, po zbudowaniu MTAR. G1 15/5/6/15 63/63 i G1 STOR-04 16/5/6/16 86/86 PASS |
| `build:btp:hana`, `verify:hana`, `verify:stor04-release` | PASS, `STOR04_RELEASE_ARTIFACT=PASS hdiAdded=1 hdiChanged=0 hdiRemoved=0` |
| `hana-it-runner.mjs build` i `test`, `runner.jar --self-test` | R2 build PASS, 6/6 PASS, `R2_OFFLINE_SELF_TEST=PASS` |
| Sekrety i whitespace | `check-secrets.mjs`, `git diff --check` PASS |

Lokalne MTAR powstały z roboczego drzewa. Dowód z czystego checkoutu pochodzi z CI. Instalacja zależności
zgłosiła istniejące ustalenia `npm audit` w narzędziach UI5, MBT i Azurite. BETA-01.1 używa tych samych wersji.

Pierwszy bieg CI dla `96c0672` zatrzymał się podczas konfiguracji `demo-web-app`, gdy Gradle Plugin Portal
nie rozwiązał pluginu GraalVM 0.10.2. Instalator uruchamia przypięte `:ksef-client:publishToMavenLocal`
z `--configure-on-demand`, dzięki czemu do budowy biblioteki potrzebna jest konfiguracja modułu i projektu
głównego. Zweryfikowano na świeżej kopii SDK, Gradle 8.8 i JDK 21.0.12 (`BUILD SUCCESSFUL`, JAR 3.0.29
w izolowanym repozytorium Maven). Pin commita SDK i wymagane kroki CI zachowują swoje reguły.
[Pierwszy bieg CI](https://github.com/scaamp/AInvoicer/actions/runs/45234057359).

**Merge readiness:** GO dla patcha offline po pełnej regresji i SUCCESS CI dokładnego HEAD na [PR #29](https://github.com/scaamp/AInvoicer/pull/29/checks).
**BETA LIVE NO-GO**. Obowiązują bramki CF EU20 `BLOCKED_AUTH`, R-1, STOR-04/CURRENT i NEW G1, budżet fizycznych prób
i sign-off XSUAA. Po ewentualnym merge następny krok to osobno zatwierdzony read-only PRECHECK EU20 po odnowieniu
sesji przez właściciela. BETA-01.1 nie uruchamiał operacji LIVE ani merge.
