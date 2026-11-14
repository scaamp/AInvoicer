---
title: "KSEF-IN-04 — pierwszy odczyt XML: przygotowanie i testy offline"
date: 2026-11-14
type: journal
status: zakonczony
tags: [ainvoicer, journal, ksef, inbound, auth, live-approval]
---

# KSEF-IN-04 — kontrolowany one-shot TEST

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) ·
[Runbook i preflight](../guides/KSEF_IN_04_ONE_SHOT_LIVE_READ.md)

## Date

14.11.2026, Europe/Warsaw.

## Branch

`feature/ksef-in-04-first-live-read`, osobny worktree `D:\AInvoicer-ksef-in-04`.
Baza po `git fetch origin main`: `c5041bdd934638b99587c757588d706868e16633`.
Główny checkout `D:\AInvoicer` pozostał na `7a47ac9`, status początkowy czysty; nie aktualizowano go.

## Goal

Preflight publicznego API i istniejącego INBOUND, izolowany test one-shot, testy offline, runbook,
PR do main i weryfikacja CI. Użytkownik zlecił publikację PR, bez merge. Osobna zgoda na konkretne
auth/READ nie została udzielona: kończymy na **READY FOR LIVE APPROVAL**.
Bez KSeF auth/GET faktur, SEND, SAP WRITE, HANA DDL/DML, BTP/CF, schedulerów i pracy Claude.

## Initial State

Przeczytano AGENTS, CLAUDE, STATUS, ROADMAP, najnowszy dostępny dziennik ORCH-06 w głównym
checkoutcie i IN-03B w aktualnym main, ADR-0001 oraz wymagane przewodniki TEST/INBOUND/persistence/readiness.
Zastosowano skill `ainvoicer-ksef`; odczytano także granice CAP Java. Nie implementowano ponownie INBOUND.

Potwierdzono obecność auth, klienta/retrieval/parsera, sinka, synchronizacji, runnera i dwóch
historycznych testów LIVE. `KsefTestApiLiveTest` robi rzeczywisty auth/redeem/JWT/listę sesji/refresh,
ale nie robi GET XML i nie dowodzi InvoiceRead. Historyczne LIVE PASS nie jest wynikiem tej sesji.

Wersje: Microsoft JDK 25.0.4.1, Maven 3.9.16, Node 24.13.0; CAP Java 5.2.0, Boot 4.1.1,
SDK MF 3.0.29, commit upstream `3c3a899541074aa4bd944636b25659aef7d23da7`. Bez aktualizacji SDK/POM.

## Implementation

- Nowe klasy wyłącznie w test classpath: `KsefInboundOneShotLiveTest`, `KsefInboundOneShot`,
  `OneShotHttpClient`, `KsefInboundOneShotTest`. Harness bez Spring, repozytorium/DB i persystencji XML.
- NUMBER: jeden GET; BUYER: pageOffset=0/pageSize=10, Subject2, PermanentStorage, jawne okno ≤7 dni,
  pierwszy dokument i najwyżej jeden GET. Bez paginacji, poszerzania zakresu i fallbacku kontekstu.
- Istniejący `KsefInboundClient`, retrieval noRetry/hash/crossCheck i parser FA(3); porównanie brutto,
  podstawowych danych i sprzedawcy z numerem KSeF. Nieobsługiwany schemat → BLOCKED z hashem/rozmiarem.
- Dwie jawne zgody opt-in, osobny approved NIP i TEST/metoda/scenariusz/limit przed SDK i crypto.
  Transport rezerwuje każdą próbę auth/certyfikaty/READ, maksymalnie 9; status auth ≤3, redeem×1,
  metadata×1/GET×1. Pierwszy non-2xx/I/O zatrzymuje dalszy ruch. Refresh i SEND poza allowlistą.
- JDK25 HTTP/1.1/Redirect.NEVER, retrylimit=1 i disableRetryConnect przy starcie JVM LIVE;
  ochrona przed wire logging i wyciszenie logów SDK. Raport bez wiadomości i przyczyn wyjątków,
  numerów faktury/NIP/kwot/XML/JWT. Tylko techniczne statusy, UUID, liczniki, rozmiar, SHA-256.
- Minimalna zmiana produkcyjna: package-private overload fabryki SDK z wstrzykiwanym HttpClient.
  Dotychczasowa fabryka zachowuje wszystkie ustawienia i używa tego samego mappera/SDK.
- Publiczny OpenAPI TEST pobrano bez credentiali. Osiem operacji auth/refresh/READ porównano z
  upstream MF `ce3670ea042de380039de4c5849fd6455811b292`: MATCH dla wszystkich.
  SHA-256 OpenAPI: `57e3e0ff8dc1f1aa590dc323e5f2d3151fc06ebfdbbdb5b74d139459b7cdd8ac`.
  Sygnatury i zachowanie zweryfikowano także w lokalnym sources.jar SDK; źródła podlinkowane w runbooku.

## Changed Files

- `cap-java/srv/src/main/java/customer/cap_java/ksef/KsefSdkConfiguration.java`: overload transportu.
- Cztery nowe pliki testowe w tym samym pakiecie `ksef` (wyżej).
- `docs/guides/KSEF_IN_04_ONE_SHOT_LIVE_READ.md`: warunki, limity, PowerShell, bezpieczne przekazanie
  tokena, przykładowy raport, PASS/EMPTY/FAIL/BLOCKED i procedura STOP.
- Ten dziennik oraz STATUS/ROADMAP/README. Skorygowano zastaną notatkę o otwartym PR #19:
  aktualny origin/main zawiera już jego merge `c5041bd`.
- Bez zmian CDS, HANA, BlobService, storage, schedulera, MTA, UI5, SAP, Node/Python aplikacji.

## Commands Executed

```text
git fetch origin main
git branch --show-current / git status --short / git worktree list / git log -1
git worktree add -b feature/ksef-in-04-first-live-read D:/AInvoicer-ksef-in-04 origin/main
mvn -version
mvn -B test -Dtest=KsefInboundOneShotTest -Dsurefire.failIfNoSpecifiedTests=false -Dcds.cds.skipVersionCheck=true
mvn -B clean verify -Dcds.cds.skipVersionCheck=true
mvn -B test -Dtest=KsefInboundOneShotLiveTest -Dsurefire.failIfNoSpecifiedTests=false -Dainv.excludedGroups=live-sap,live-sap-write -Dcds.cds.skipVersionCheck=true
npm ci --prefix deploy --ignore-scripts
node deploy/scripts/check-secrets.mjs
git diff --check
```

Wszystkie parametry `-D` w PowerShell cytowane. Publiczne dokumenty pobierano przez web/urllib
wyłącznie z oficjalnych MF GitHub i publicznego `/docs/v2/openapi.json`; bez endpointów auth/biznesowych.
Nie odczytywano lokalnych credentiali ani historycznego XML. Logi builda są ignorowane przez Git.

## Tests & Results

- Nowy harness: **25 testów / 0 failures / 0 errors**, lokalny SDK/HTTP i mock, bez zewnętrznego API.
  W tym istniejący XAdES → auth200 → pojedynczy redeem → token w pamięci → GET/XML/hash/parser
  na loopback (5 żądań), BUYER/hash/crossCheck, brak dokumentów, brak zgód i błędny zakres,
  401/403/404/429+Retry-After, timeout, empty/oversize/hash/schema, blokada SEND/refresh/originu,
  globalny limit i limit statusów/redeem oraz STOP po niejednoznacznym I/O.
- Pełne `mvn -B clean verify`: **522 run / 0 failures / 0 errors / 6 skipped**, BUILD SUCCESS,
  2:25 min, JAR i exec JAR utworzone lokalnie. Sześć skipów dotyczy kontraktu Azurite;
  emulator nie był uruchamiany lokalnie w IN-04 (standardowe CI wykonuje go osobno).
  W tym stare INBOUND: HTTP14, retrieval11, parser18, CQN/H2 23, recovery dwóch JVM1,
  sync runner7, planner9, dedup7, primitives11, budget39, scheduler4, config11 — bez regresji.
- Skan wzorców sekretów i `git diff --check`: PASS (skan heurystyczny, nie audyt credentiali).
- Osobna weryfikacja bramki: nowy test LIVE wybrany jawnie przy zniesieniu wykluczenia tagu,
  lecz bez flagi READ → **1 test / 1 SKIP**, bez inicjalizacji SDK/auth/HTTP. To kontrola OFF,
  nie test LIVE i nie potwierdzenie InvoiceRead. Przed komendą sprawdzono brak flagi opt-in.
- KSeF auth, InvoiceRead i rzeczywisty XML: **NIE WYKONANO / NOT VERIFIED**.
  Publiczny OpenAPI jest dowodem preflight kontraktu, nie testem autoryzowanego API.

## Problems Encountered

Pierwsze wykonanie nowych testów: 21 PASS/1 ERROR, błąd ponownego stubowania Mockito metodą
`when` po `thenThrow` (wyjątek syntetycznego timeoutu podczas konfiguracji testu). Poprawiono na
`doReturn`; kolejne 22/22 i finalne rozszerzone 25/25 PASS. Nie był to błąd runtime INBOUND.
Skan sekretów w świeżym worktree wymagał uprzedniej instalacji zależności `deploy` (`yaml`).
Instalacja lockfile z `--ignore-scripts`, bez zmian zależności i bez BTP/CF.

## Decisions Made

One-shot używa istniejącej części pipeline bez utrwalania; sink/runner uruchomiłyby zapisy, których
użytkownik zabronił. Brak nowego ADR: architektura i własność danych pozostają bez zmian.
Lokalny budżet testu nie zastępuje globalnego limitera IN-03A ani nie odblokowuje CF/IN-03B.
Parser raportuje podstawową weryfikację FA(3); nie przedstawiamy jej jako pełnej walidacji XSD.

## Current State

Implementacja, preflight i lokalna pełna regresja gotowe. **READY FOR LIVE APPROVAL** po review
otwartego PR i weryfikacji CI dokładnego HEAD. Publikacja i CI są raportowane oddzielnie poniżej.
Auth/InvoiceRead bieżącego principal oraz dostęp do historycznej faktury niepotwierdzone.
Nie ma technicznego dowodu braku InvoiceRead — nie wykonano żadnego testu tego uprawnienia LIVE.

## Next Steps

1. Review opublikowanego PR i weryfikacja CI dokładnego HEAD; bez merge w tym zadaniu.
2. Właściciel: osobna zgoda na dokładny SHA/TEST/NIP/metodę/scenariusz i żądania według runbooka.
3. Dopiero po niej wykonać jeden zatwierdzony przebieg i dopisać zredagowany wynik API/SHA/rozmiar/XML.
