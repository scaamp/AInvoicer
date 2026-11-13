---
title: "CAP-HANA-02G1 — naprawa startu Chrome w CI PR #17"
date: 2026-11-13
type: journal
status: zakonczony
tags: [ainvoicer, journal, cap-hana-02g1, ci, security, chrome]
---

# CAP-HANA-02G1 — CI recovery

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) ·
[PR #17](https://github.com/scaamp/AInvoicer/pull/17) ·
[Kontrakt katalogu](../guides/CAP_HANA_02G1_CATALOG_RECONCILIATION.md)

## Cel i stan wejściowy

13.11.2026, Europe/Warsaw. Zlecenie: naprawić istniejący CI, commit/push na ten sam branch,
potwierdzić dokładny HEAD i pozostawić PR otwarty do niezależnego review. Bez merge i LIVE.
`git fetch origin main feature/cap-hana-02g1-catalog-reconciliation` potwierdził
`main=4d4dbfca48f654f74cef787e6a680f8a79607512`, HEAD PR
`36477e3b5f7864bd9045dd51ca81df7b3e75705f`. Worktree `D:\AInvoicer-cap-hana-02g1` był czysty.
Główny checkout i worktree CAP-07F pozostawiono bez zmian.

## Diagnoza i granice dowodów

- Przeczytano pełne logi obu prób runu
  [43449559487](https://github.com/scaamp/AInvoicer/actions/runs/43449559487):
  job `131967837135` (attempt 1), `131973977509` (attempt 2).
- Oba rzeczywiste testy przeglądarki kończyły się **przed asercjami**, w discovery CDP;
  Maven/katalog/MTAR w tych próbach były skipped. To awaria bootstrapu narzędzia testowego,
  a nie zaobserwowane naruszenie CSP lub wyciek Authorization.
- Obraz `ubuntu-24.04`, wersja `20261111.370.1`, Node `22.23.4`, Chrome z obrazu
  `157.0.8103.50`. Runner używa bezpośrednio `child_process.spawn`, HTTP `/json/list`
  i wbudowanego WebSocket Node; nie używa Playwright, Puppeteer ani ChromeDriver.
- Stary bootstrap losował port z przedziału 9300–9799, wykonywał 150 prób co 200ms
  i ignorował wszystkie błędy HTTP/JSON. Nie odróżniał braku listenera od braku strony.
  W attempt 1 testy trwały około 30.6s, bez stderr Chrome. W attempt 2 około 33.5s;
  Chrome faktycznie ogłosił DevTools na portach 9682 i 9600, pod koniec okna startu.
  Komunikat „endpoint not reachable” nie dowodził więc braku procesu/listenera.
- Przyczyna w kodzie runnera: niedeterministyczny handshake zimnego startu z krótkim
  budżetem i utratą diagnostyki. **Dokładnej przyczyny opóźnienia wewnątrz Chrome nie da się
  ustalić ze starych logów**. Ostrzeżenia D-Bus nie są dowodem awarii sandboxa.
  Nie wykazano kolizji portów; eliminacja losowania usuwa tę dodatkową możliwość.

## Zmiana

Commit kodu `6f67b4a297df3505c3a7be3b3d9441762396660e`:

- `browser-test.mjs`: Chrome wybiera wolny port (`--remote-debugging-port=0`), nadal używa
  osobnego profilu; runner loguje czas gotowości i korzysta z nowego helpera.
- `cdp-startup.mjs`: odczyt portu z `DevToolsActivePort` własnego profilu, limit 90s
  obejmujący gotowość strony, limit 1s na odczyt HTTP, natychmiastowa odmowa po błędzie
  spawn/exit/signal, zachowany ostatni błąd HTTP/JSON lub stan „page not ready”.
- `cdp-startup.test.mjs`: deterministyczny zegar testuje gotowość po 35s, końcowy timeout,
  diagnostykę HTTP503 i zakończenie procesu. To testy helpera, nie substytut testów bezpieczeństwa.
- `package.json`: do istniejącego `test:runner` dodano regresję helpera.

Nie zmieniono testów CSP/Basic Auth, ich asercji, workflow, flag bezpieczeństwa ani zależności.
Nie dodano `--no-sandbox`, `--disable-web-security`, retry runu, SKIP ani continue-on-error.
Kontrakt katalogu, `documents.cds`, BLOB-LAB-01, CAP-07F i produkcyjny CAP są niezmienione.

Źródło kontraktu discovery:
[CDP HTTP endpoints / DevToolsActivePort](https://chromedevtools.github.io/devtools-protocol/).

## Polecenia i walidacja

- Node 24.13.0: `npm run test:runner --prefix sapui5/ainvoicer` — **8/8 PASS**;
  dwa testy korzystają z rzeczywistego Chrome 157.0.8103.78 i serwerów loopback.
- Node 22.23.4 (oficjalne archiwum Node, SHA256 porównany z SHASUMS256.txt): te same
  trzy pliki testowe przez `node --test` — **8/8 PASS**; katalog — **48/48 PASS**.
- `node --test deploy/test/hdi-catalog-readonly.test.mjs` — **48/48 PASS**, CORE14 + LAB offline.
- `npm test --prefix deploy` — **90 PASS / 1 skipped** (brak lokalnego Go; wymagany w CI).
- `npm run validate:smoke --prefix deploy`, `node deploy/scripts/check-secrets.mjs`,
  `git diff --check` — **PASS** (skaner heurystyczny, nie audyt credentiali).
- Pierwsze `npm run build:btp:smoke --prefix deploy`: Maven **476 run / 0 failures / 0 errors /
  6 skipped**, JAR CF **10/10**, UI5 CF **13/13**, AppRouter **10/10**, składanie i walidacja
  MTAR PASS. Końcowy zapis release evidence **FAIL**: skrypt runnera został zmieniony po
  zapisaniu wejściowego hasha builda. Strażnika nie zmieniono; uruchomiono ponownie pełny build
  na niezmiennych źródłach.
- Powtórzenie `npm run build:btp:smoke --prefix deploy` — **PASS**, ponownie Maven
  **476 run / 0 failures / 0 errors / 6 skipped**, CF runtime **10/10**, UI5 CF **13/13**,
  AppRouter **10/10**, walidacja MTAR i zapis evidence PASS.
- `npm run build:btp:hana --prefix deploy` i `npm run verify:hana --prefix deploy` — **PASS**,
  niewdrożony MTAR 0.7.3 i jego hashe zweryfikowane offline. Żaden build nie uruchamia HDI make w HANA.
- [CI kodu 6f67b4a](https://github.com/scaamp/AInvoicer/actions/runs/43458383822):
  **pełny pipeline SUCCESS**, preflight **8/8 PASS** (16:01:08–16:01:31 UTC),
  oba rzeczywiste testy CSP POST i Basic Authentication wykonane.
  Uruchomienie wynikało z konkretnej poprawki, bez ręcznego ponawiania starego runu.
- CI: deploy **91/91 PASS** (także niezależny Go ZIP reader), Maven **476 run / 0 failures /
  0 errors / 6 skipped**, oba MTAR i HANA evidence PASS, R2 **5/5**, emulator Azurite **6/6**
  bez skipped. QUnit **112/112**, OPA **21/21**, rzeczywisty lokalny CAP/UI5 Inbox
  **16 testów / 73 asercje** (A/B, granty, odmowy, outage i restart) PASS. Bez wywołań LIVE.
- Końcowa aktualizacja dokumentacji nie zmienia kodu `6f67b4a`. Wynik CI jej dokładnego SHA
  jest raportowany w [checks PR #17](https://github.com/scaamp/AInvoicer/pull/17/checks).

## Zmienione pliki

Cztery pliki runnera wymienione wyżej oraz `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/README.md`,
ten dziennik i doprecyzowanie historycznego zakresu w przewodniku katalogu. Pierwotne pliki
walidatora i jego testów są niezmienione względem `36477e3`. Artefakty builda pozostają ignorowane.

## Następny krok

PR pozostaje otwarty do niezależnego review po zielonych checks końcowego HEAD.
Review/merge oraz ewentualne read-only G1 wymagają osobnych decyzji. Żaden task G1,
CF/HANA/SAP/KSeF LIVE, provisioning, binding, deployment ani DDL/DML HANA nie został wykonany.
