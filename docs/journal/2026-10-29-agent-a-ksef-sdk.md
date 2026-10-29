---
title: "Agent A — oficjalny SDK KSeF w CAP Java"
date: 2026-10-29
type: journal
status: w-trakcie
tags: [ainvoicer, cap-java, ksef, sdk, auth, outbox]
---

# Agent A — SDK KSeF

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md)
· [Integracja SDK](../guides/KSEF_JAVA_SDK_INTEGRATION.md)
· [Runbook](../guides/KSEF_TEST_RUNBOOK.md) · [ADR-0004](../decisions/ADR-0004-ksef-test-sdk-workflow.md)

## Date

2026-10-29 (Europe/Warsaw).

## Branch

`feature/ksef-auth`, początkowy HEAD `80aaac5`, czyste drzewo. Osobne worktree
`feature/ksef-fa3` należy do Agenta B. Bez przełączania głównego branchu i bez
zmian SAP H01, UI5, Node/Python lub deploymentu.

## Goal

Oficjalny SDK w jednej JVM CAP, prawdziwe auth TEST, trwała obsługa sesji i wysyłki,
integracja FA(3) Agenta B, maksymalnie jedna nowa syntetyczna faktura TEST z UPO.
Użytkownik jawnie dopuścił commit/push własnego branchu i integrację commitu B.

## Initial State

CAP 5.2.0, Boot 4.1.1, Microsoft JDK 25.0.4.1, Maven 3.9.16. Istnieją snapshoty,
idempotencja i outbox writeback SAP. Brak zależności KSeF, credentiali KSeF w env
i konfiguracji Destination w procesie tej sesji. Eksperyment
`origin/feature/ksef-java-e2e-runner` przeanalizowano przez `git show`, bez kopiowania
demo ani sekretów. Historyczny sukces tamtej gałęzi nie jest wynikiem CAP.

## Implementation

- Zweryfikowano tag SDK `v3.0.29`, commit `3c3a899541074aa4bd944636b25659aef7d23da7`;
  TEST OpenAPI zgłasza API 2.9.0 z buildem 28.10.2026.
- Sklonowano SDK do `D:/ksef-sdk-v3.0.29`, Gradle 8.8/JDK21 zbudował oficjalny
  moduł `ksef-client` i opublikował go lokalnie do Maven. Bez PAT i osobnego gatewaya.
- Konfiguracja klienta, auth/token/XAdES, polling/redeem/refresh, SDK crypto,
  sesje FA(3), hash, status, close, UPO i odczytowa rekoncyliacja.
- Wewnętrzne zlecenie i historia, natywny outbox, claim z dzierżawą, commitowane
  checkpointy efektów i wymaganie zgodnego SHA-256 przy ACCEPTED.
- Magazyn exact bytes: interfejs i jawny adapter lokalnego TEST, bez użycia dysku CF.

## Changed Files

`cap-java/{pom.xml,srv/pom.xml,db/schema.cds}`, konfiguracja aplikacji,
nowe klasy `srv/src/main/java/customer/cap_java/ksef/`, odpowiadające testy,
`tools/Install-KsefSdk.ps1`, przewodniki i ADR wymienione u góry.
Wygenerowane klasy CDS/SQL/JAR i lokalne SDK pozostają poza commitem.

## Commands Executed

Z repo: `git status --short`, `git branch --show-current`, `git worktree list`,
`git log`, `git ls-tree`, `git show` branchu referencyjnego, `git ls-remote --tags`
SDK. Odczyt AGENTS, Skills CAP/KSeF, STATUS, ROADMAP, dziennika i ADR.
Pobrano surowy TEST OpenAPI do pliku poza repo. `java -version`, `mvn -version`.

Z SDK przy `JAVA_HOME` JDK21:
`./gradlew.bat :ksef-client:publishToMavenLocal --no-daemon` — BUILD SUCCESSFUL.
Z `cap-java/` kompilacje, testy wybrane i `mvn clean verify -Dcds.cds.skipVersionCheck=true`.
Dokładna komenda auth i jawne flagi są w runbooku; tokenów nie przekazywano przez CLI.

## Tests & Results

| Próba | Wynik / zakres |
| --- | --- |
| SDK build ze źródeł | PASS, Gradle 8.8/JDK21, bez testów integracyjnych SDK |
| Auth TEST | **LIVE PASS** 17:35 Europe/Warsaw: challenge, XAdES, status biznesowy 200, redeem, chroniony GET aktywnych sesji, refresh; 1 test / 0 błędów |
| CAP/H2 workflow + auth + regresja OData | 37/37 PASS, SDK/auth mockowane |
| HTTP SDK, AES/SHA-256/exact bytes, storage, auth | 11/11 PASS, serwer loopback i kryptografia MF, bez KSeF |
| Pełny clean verify | **106/106 PASS**, 0 failures/errors, 49.463 s, 17:43 Europe/Warsaw; LIVE wykluczone |
| Nowa wysyłka / numer KSeF / UPO | Jeszcze NIE WYKONANO na tym etapie dziennika |
| SAP H01 READ/WRITE, CF/HANA | NIE WYKONANO w tej sesji |

Tokeny i klucze pozostawały w pamięci. Nie zapisano JWT ani odpowiedzi z sekretami.

## Problems Encountered

Pierwsze kompilacje ujawniły runtime scope JSR-310/JAXB z publikacji Gradle SDK;
dodano jawne zależności compile. Przeciążenie CAP `ChangeSetContextRunner.run`
wymagało bloku lambda z jawnym return. Poprawiono i uzyskano powyższe PASS.
Istniejące ostrzeżenia CDS metadanych zdalnych i Mockito/JDK nie są nowymi błędami.
`npm ci` nadal zgłasza 2 high severity z zastanego lockfile; nie wykonano
niepowiązanej migracji `npm audit fix --force`.

## Decisions Made

[ADR-0004](../decisions/ADR-0004-ksef-test-sdk-workflow.md): checkpointy przed
efektami i konserwatywna rekoncyliacja bez utrwalania sekretów. Storage lokalny
wyłącznie dla jawnego TEST; przed CF wymagany adapter zarządzanego repozytorium.

## Current State

Auth LIVE potwierdzone. A1–A4 zaimplementowane i lokalnie przetestowane.
Generator B nadal w swoim worktree, bez nowego commitu w chwili tej notatki.
Oczekuje integracja A5, jedna wysyłka A6 i wynik UPO. H01 WRITE pozostaje wyłączone.

## Next Steps

1. Sprawdzić i zintegrować commit B bez modyfikowania jego worktree.
2. Zwalidować dokładne bajty, uruchomić jedną syntetyczną fakturę przez CAP/SDK TEST.
3. Potwierdzić status/hash/numer/UPO, pełny verify i opublikować własny branch.

## Kontynuacja — LIVE E2E i restart (17:53–17:54)

Agent B nadal nie udostępnił commitu. Zgodnie z zadaniem wykonano niezależny E2E
na oficjalnym `invoice-template_v3.xml` z przypiętego SDK. Podstawiono syntetyczny
NIP, datę i unikalny numer, a kontakty skierowano do domeny `.invalid`.
To nie jest drugi generator domenowy. Syntetyczna lokalna migawka nie dowodzi
odczytu faktury H01 i nie jest używana do SAP WRITE.

Ścisła walidacja lxml 6.0.2: pełne oficjalne XSD CRD z 4 plikami, importy rozwiązane
lokalnie; 4688 bajtów XML, SHA-256
`b94b1eee7ca6e73f8353fe9b4c0a1b204bc49bc9df15977b4387b6156ba1b3bd`.
Pierwsza próba harnessu JAXP zatrzymała się PRZED markerem i HTTP na limicie 5000
węzłów modelu. Zrezygnowano z rozwijania dużego DFA JAXP: harness wymaga digestu
ścisłej walidacji lxml, sprawdza go i NIP przed siecią. Nie było wtedy wysyłki.

`KsefOnlineE2ELiveTest` (jawne flagi `AINV_KSEF_LIVE_SEND=true`, `SYNTHETIC=true`,
tryb SEND) wykonał **jedną nową wysyłkę**, przez ten sam worker i prawdziwą CAP/H2.
Collector wyłączony tylko w harnessie; harmonogram odczytów korzysta z trwałego
retryNotBefore. Natywny collector ma osobny test lokalny na plikowej H2.

| Dowód | Wynik |
| --- | --- |
| Operacja CAP | `b2d7ced6-1c2d-4f5a-8414-294dd3f67831`, COMPLETED |
| Sesja TEST | `20261029-SO-39FC120000-8C4FD7F900-FE` |
| Referencja faktury | `20261029-EE-39FC18C000-24B14A61D2-3B` |
| Końcowy wynik faktury | biznesowe 200 + zgodny hash + numer KSeF |
| Numer KSeF | **`1234563218-20261029-7DC8A1C00000-CD`** |
| Close / UPO | Close potwierdzone, sesja 200, gotowe strony UPO, pobrano **5528 bajtów** |
| UPO SHA-256 | `5f950d7d41d5e5f91f106254a199708e0aa553989f3433858e0d3a3146c9b86a` |
| Weryfikacja UPO | root Potwierdzenie, zawiera dokładny numer i Base64 SHA-256 XML; podpisu MF kryptograficznie nie weryfikowano |
| Maven LIVE SEND | 1/1 PASS, BUILD SUCCESS, 36.413 s, zakończono 17:53:30 |
| Maven RESUME po restarcie | 1/1 PASS, BUILD SUCCESS, 19.968 s, 17:54:28; ta sama operacja, bez nowej wysyłki |
| Ochrona fixture | skrypt odmówił przygotowania w katalogu istniejącego markera: EXISTING_ATTEMPT_USE_RESUME |

Artefakty poza Git: `D:/AInvoicer-ksef-test-20261029-agent-a/` — plikowa H2,
`send-attempt.json`, `result.json`, `invoice.xml`, XSD/manifest oraz dokumenty
o nazwach SHA-256 w `documents/`. Brak JWT/AES/kluczy prywatnych.

Nowy test wznowienia odczytów ujawnił błąd konfiguracji Mockito: `when(...)`
wywoływało wcześniejsze `thenThrow(429)` przy restubbingu. Zmieniono na `doReturn`;
wybrane 13/13 testów workera ponownie PASS. Pełny build sprzed tej poprawki miał
107 testów / 1 error w tym teście, nie awarię transportu. Końcowy verify będzie
ponowiony po komplecie zmian i opisany poniżej.

## Końcowa weryfikacja etapu A1–A4/A6 i dostępność B

`mvn clean verify -Dcds.cds.skipVersionCheck=true`: **107/107 PASS**, 0 failures,
0 errors, BUILD SUCCESS, 47.792 s, 17:57:11 Europe/Warsaw. LIVE tagi wykluczone.
Sprawdzono oba skrypty: installer SDK BUILD SUCCESSFUL (10 s), przygotowanie
oficjalnego fixture w osobnym katalogu tylko walidacyjnym PASS (bez wysyłki).

W trakcie końcowych sprawdzeń pojawił się commit B
`7075c0117ec828f92724b7eb087bafa8d4c584b9`. Integracja jest kontynuowana poniżej;
nie będzie drugiej wysyłki, bo limit jednej nowej faktury został wykorzystany.
