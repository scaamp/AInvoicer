---
title: "ORCH-08 — review i integracja INBOUND, storage i SAP FA(3)"
date: 2026-11-15
type: journal
status: zakonczony
tags: [ainvoicer, orch-08, integration, ksef, hana, security]
---

# ORCH-08 — niezależny review i integracja offline

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md)

## Date / Branch / Goal

2026-11-15, Europe/Warsaw. `review/orch-08-ksef-inbound-storage-e2e`, worktree `D:\AInvoicer-orch-08`.
Review PR #21/#22/#23, wspólna regresja, warunkowy kontrolowany merge, dokumentacja i trzy osobne plany.
Bez KSeF/SAP/CF/HANA LIVE. Produkcyjny SEND i owner-led BLOB-LAB-01 nie są zmieniane.

## Initial State

Własne `git fetch origin` potwierdziło `main=c5041bdd934638b99587c757588d706868e16633`.
Główny checkout był czysty; nie zmieniano go ani worktree innych autorów.
Konektor GitHub potwierdził otwarte PR-y, dokładne HEAD i ich zakończone CI SUCCESS:

| PR | HEAD review | CI wejściowe | Wynik review |
| --- | --- | --- | --- |
| [#21](https://github.com/scaamp/AInvoicer/pull/21) | `110d6315932ba32d571deed2d85251e1cacba880` | [43635909480](https://github.com/scaamp/AInvoicer/actions/runs/43635909480) | PASS WITH NOTES |
| [#22](https://github.com/scaamp/AInvoicer/pull/22) | `5a2c2108fff9afe8fe12365c4226b93733fed9e6` | [43698656934](https://github.com/scaamp/AInvoicer/actions/runs/43698656934) | PASS WITH NOTES |
| [#23](https://github.com/scaamp/AInvoicer/pull/23) | `1de63760ace1749cbcf12d5eb75660698ca79da9` | [43702661219](https://github.com/scaamp/AInvoicer/actions/runs/43702661219) | PASS WITH NOTES |

Wejściowe CI nie zastępuje CI wspólnego drzewa. Po potwierdzonym merge #22 zamknięto Draft #20 jako superseded.

## Code review #21 — one-shot

Harness/settings/transport wyłącznie w test classpath, bez Spring/DB/sinka/workerów/schedulera.
Produkcja zmienia tylko package-private overload fabryki przyjmujący HttpClient.
Dwa opt-in, TEST, poprawny NIP, jawna metoda auth/scenariusz i budżet 1..9 są sprawdzane przed SDK.
NUMBER waliduje numer i GET dokładnie tego numeru. BUYER to jeden Subject2/Asc/offset0/page10,
okno ≤7 dni, bez paginacji/poszerzenia; wybór pierwszego wyniku i najwyżej jeden GET.
Allowlista metod/origin/path obejmuje auth/challenge/certyfikaty/status/redeem/metadata/GET;
limity per operacja, 3 statusy auth, zakaz redirectów i obu sendAsync, STOP po błędzie.
TOKEN nie przełącza się na XAdES, 403 zatrzymuje, 429 raportuje Retry-After bez nowej próby.
Hash z oryginalnych bajtów; nieobsługiwany schemat = BLOCKED, bez kolejnej faktury.
Raport nie zawiera XML/JWT/NIP/numerów. Numerowy GET nie potwierdza niezależnego hasha metadanych.

**Dodatkowy dowód ORCH-08:** `OneShotWireRetryTest` uruchamia prawdziwy SDK3.0.29/JDK25
w osobnych JVM i surowy serwer 127.0.0.1, który odbiera pełne żądanie i zamyka połączenie bez odpowiedzi.
Bez flag JDK dekorator liczy 1, serwer widzi ≥2; z flagami runbooka oba widzą dokładnie 1.
Oba testy PASS. Sam dekorator nie ogranicza retransmisji wewnątrz JDK.
Flagi `disableRetryConnect=true` i `redirects.retrylimit=1` muszą obowiązywać od startu nowej JVM.
Nie zmieniano produkcyjnego HttpClient ani SEND. Dowód dotyczy przypiętego JDK/SDK i HTTP/1.1;
ponowić go po aktualizacji runtime. InvoiceRead rzeczywistego principal nadal NOT VERIFIED.

Notes: SDK buforuje body przed limitem 3 MiB; to ograniczenie opisane w IN-04, a nie pełny limit
transferu/pamięci. Parser FA3_BASIC_PASS nie jest pełną walidacją XSD.

## Code review #22 — storage

Prywatna `KsefStoredDocuments`, brak projekcji OData/autoexpose i brak dostępu przez BlobService.
Brak zależności od createdBy; złożony PK environment/channel/ownerSegment/sha256 jest adresem
fizycznej idempotencji. INSERT-only, brak UPDATE/DELETE. 409006 obsłużony po zakończeniu
nieudanej transakcji, następnie osobny SELECT i kontrolny odczyt długości/SHA także po ALREADY_EXISTS.
System user i dedykowane ChangeSety izolują zapis od rollback wywołującego; test H2 potwierdza orphan.
Niepewny zapis można odtworzyć przez ten sam adres, bez nadpisania. Orphan listing nie usuwa dokumentów.
Strumień LargeBinary odczytywany w transakcji, rozmiar przed BLOB, readNBytes(size+1), hashowanie po odczycie.
Scope TEST/DEMO/PROD i INBOUND/OUTBOUND oraz NIP OUTBOUND sprawdzany przed odczytem;
INBOUND jest współdzielony, grant CAP pozostaje obowiązkową granicą przyszłego publicznego pobrania.
Referencje pozostają wewnętrzne. Brak rejestracji beana/wiringu; CF guard i scheduler OFF.

Notes: budżet semafora ogranicza operacje adaptera, nie całkowity heap, kopie SPI/parsera i liczbę
wywołujących. Potrzebny limit równoległości przyszłego workera. Gdy wiersz znika między SELECT
metadanych i SELECT BLOB, adapter zwraca brak (KSEF_DOCUMENT_NOT_FOUND, bez checkpointu), nie fałszywy PASS.
Awaria DB daje retryable UNAVAILABLE, zła długość/hash/mime daje integrity/size failure.
H2 nie dowodzi semantyki HANA: realne 409006, stream, uncertain commit, równoległość i restart są release gates.
LAB model/usługa/handler nie zmienione. D-1 rozwiązany strukturalnie; deployment i wiring nadal PENDING.

## Code review #23 — preflight i odziedziczone luki

Cała zmiana Java w src/test, bez endpointu. KsefOperator/auth i zatwierdzony UUID/NIP/CompanyCode,
bez niejednoznacznego iloczynu scope. Odczyt przez istniejący RAP Web API adapter, missing = odmowa.
NEW, pusty KsefNumber, OUTBOUND/STANDARD/PLN (mapper), rodzic dzieci, waluta i brutto pozycji,
arytmetyka, zaufany PartyData, headerVersion/itemsFingerprint, domena/XSD i drugi pełny READ.
SELECT rezerwacji/historii przed i po próbie; brak operacji/outbox/storage/SAP WRITE/SEND.

**A — luki odziedziczone:** handler nie egzekwuje InvoiceStatus i wszystkich pól pozycji; worker
wysyła utrwalony XML bez ponownego porównania kompozycji SAP. Header ETag nie gwarantuje wersji dzieci.
Drugi READ nie zamraża SAP do SEND; timestamp generowania FA(3) zmienia hash późniejszych bajtów.
Te luki blokują rzeczywisty SEND, nie merge testowego harnessu.
**B — regresje PR #23:** nie znaleziono; brak zmian w produkcyjnym handlerze/workerze/adapterze/mapperze.
Brak bieżącego H01 READ, zatwierdzonego UUID i prawidłowych danych runtime/PartyData — SEND NO-GO.

## Implementation / Changed Files

Lokalne merge zachowują historię PR-ów w kolejności #21, #23, #22. Konflikty tylko docs/README,
ROADMAP, STATUS; zachowano fakty wszystkich workstreamów i ich dzienniki. Wspólny PR integracyjny
jest wybraną ścieżką publikacji konfliktów, bez przepisywania branchy autorów i bez push do main.

Nowe pliki ORCH-08: OneShotWireRetryTest (2 próby rzeczywistego SDK/JDK),
manifest `deploy/contracts/hdi-catalog-stor04.v1.json`, test `stor04-release-contract.test.mjs`,
ten dziennik oraz trzy plany. STATUS/ROADMAP/README otrzymują aktualny zbiorczy stan.
Wykonywalny G1 bez zmian 15/5/6/15. Niewykonywalny manifest przyszłego katalogu 16/5/6/16
jest sprawdzany z kompilacją CDS i hashem DDL. Nie akceptuje dowolnych dodatkowych tabel.

## Commands Executed / Tests & Results

Narzędzia: JDK Microsoft25.0.4.1, Maven3.9.16, Node24.13.0/npm11.6.2, SDK3.0.29,
CAP Java5.2.0/Boot4.1.1, cds-dk10.2.0. `git fetch`, worktree add, merge --no-ff,
instalacje npm ci z lockfile; bez aktualizacji zależności.

| Zakres | Rzeczywisty wynik |
| --- | --- |
| Pierwszy wspólny `mvn -B clean verify '-Dcds.cds.skipVersionCheck=true'` | 583 / 0 failures / 0 errors / 6 skipped Azurite; PASS przed dodaniem testu wire |
| Nowy focused `OneShotWireRetryTest` | 2/2 PASS |
| Manifest przyszłego katalogu/kompilacja CDS | 2/2 PASS |
| Deployment tests przed manifestem | 105 PASS / 1 Go SKIP |
| Browser Security Runner | 8/8 PASS, rzeczywisty lokalny Chrome |
| Pełny `mvn clean verify` w finalnym buildzie smoke i ponownie R2 | **585 / 0 failures / 0 errors / 6 skipped**; BUILD SUCCESS; nowe testy wire włączone |
| G1 | **63/63 PASS**, runtime kontraktu bez zmian |
| Deployment tests z manifestem | **107 PASS / 1 Go SKIP** lokalnie; kontrola Go uruchomiona w CI SUCCESS |
| CF JAR security/startup | **10/10 PASS** |
| AppRouter / UI5 CF build security | **11/11 / 13/13 PASS** |
| QUnit / OPA | **112 testów, 659 asercji / 21 testów, 150 asercji PASS** |
| Inbox / rzeczywisty lokalny CAP HTTP | **16 testów / 73 asercje PASS**; A/B, granty, 401/403/404, outage i restart |
| Azurite | **6/6 PASS** osobno, oficjalny emulator wyłącznie loopback |
| R2 build/test | **R2_BUILD_PASS; 5/5 PASS**, bez HANA/CF |
| MTAR identity-smoke i HANA / verify:hana | **PASS**, niewdrożone, deploymentApproved=false |
| HDI niezależna kompilacja i porównanie | **26 → 27**, +1 tabela, 0 zmian/usunięć istniejących artefaktów |
| Zawartość gotowego MTAR | **16 hdbtable / 5 hdbview / 6 hdbindex**, hash DDL storage zgodny z kompilacją; harnessy nieobecne w payload CAP |
| Secret scanner / diff --check | **2713 plików PASS / PASS**, heurystyka nie jest audytem credentiali |
| CI wspólnego HEAD `2a2ddecfa2741ec22f328d383850bb300ffbc219` | [43705887480](https://github.com/scaamp/AInvoicer/actions/runs/43705887480) **SUCCESS**, wszystkie 24 kroki SUCCESS |
| LIVE | NIE WYKONANO |

## Problems Encountered

Pierwszy test deploymentu uruchomiony równolegle z Maven npm-ci nie znalazł tymczasowo modułu CDS.
Po zakończeniu instalacji ponowny run PASS 105/1. Nie był to defekt PR. Nie uruchamiać kontroli
kompilatora podczas modyfikacji node_modules przez Maven.
Nowy test manifestu początkowo nie normalizował niecytowanych nazw DDL do uppercase; poprawiono parser
z zachowaniem case cytowanych identyfikatorów; ponownie 2/2 PASS. G1 runtime nie zmieniono.

Pierwszy smoke build zatrzymał się po zielonym Maven585 i CF JAR10 na Windows EPERM podczas npm ci:
lokalny serwer UI5 trzymał natywny moduł keyring. Zatrzymano wyłącznie własny proces serwera,
powtórzono pełny build, wynik PASS. Próba inspekcji JAR podczas clean nie była testem PASS;
ostatecznie sprawdzono gotowy payload CAP w MTAR, bez zależności od ponownie czyszczonego target.
Kod SDK/storage/SEND nie wymagał korekty. Bez force fix lub aktualizacji zależności.

## Decisions Made / Current State

Kod offline scalony po komplecie bramek przez PR #24. READY FOR LIVE APPROVAL IN-04;
STOR-04 OFFLINE READY / HANA DEPLOY PENDING; E2E-01A OFFLINE PASS / SEND NO-GO.
INBOUND CF OFF, scheduler OFF, SAP WRITE OFF, KSeF SEND OFF. G1 LIVE niewykonane.

[Plan GET](../guides/ORCH_08_IN_04_LIVE_READ_PLAN.md) ·
[Plan HANA](../guides/ORCH_08_STOR_04_HANA_RELEASE_PLAN.md) ·
[Backlog hardeningu](../guides/ORCH_08_E2E_01B_HARDENING_BACKLOG.md).

Źródła sprawdzone: [CAP ChangeSet](https://cap.cloud.sap/docs/java/event-handlers/changeset-contexts),
[Persistence](https://cap.cloud.sap/docs/java/cqn-services/persistence-services),
[JDK25 retry properties](https://docs.oracle.com/en/java/javase/25/core/java-networking.html),
przypięte SDK faktycznie wykonane offline. Bez odczytu endpointów biznesowych MF.

## Publikacja / Finalny main

Wspólny [PR #24](https://github.com/scaamp/AInvoicer/pull/24) scala historię wszystkich trzech PR-ów,
bez squash/rebase branchy autorów. Przed merge ponownie sprawdzono dokładne SHA i wejściowe CI,
CI wspólnego HEAD, czyste drzewo, brak automatycznego deploymentu i brak zmian main.
Rules API: 0 aktywnych reguł dla main; klasyczne branch protection API: 404. Nie zmieniano ochrony,
nie użyto bypass/force-push ani bezpośredniego push main. Reviews COMMENT opublikowano przy #21/#22/#23.

| PR | Potwierdzony wynik GitHub | Commit zachowany w historii main |
| --- | --- | --- |
| #21 | MERGED przez #24 | `45da7ff53e8a027e2f2b32d0ea515540992bf7df` |
| #23 | MERGED przez #24 | `f64913c1ade9fb63da05ae8a2eb4a53d9ab58626` |
| #22 | MERGED przez #24 | `9e97063dc5108da1cf947744ed441ff3c3872276` |
| #24 | MERGED | `6911ce851253f20839e2260595d92ff7fe2c6e64` |
| #20 | CLOSED / superseded po merge #22, NOT MERGED | HEAD #20 nie jest przodkiem main; dokumentacja historyczna zachowana |

**Main po integracji kodu:** `6911ce851253f20839e2260595d92ff7fe2c6e64`.
[Workflow push dokładnie tego SHA](https://github.com/scaamp/AInvoicer/actions/runs/43706732579).
Końcowy wynik workflow jest sprawdzany w GitHub przed zakończeniem ORCH-08; powyższy run jest dowodem
tego konkretnego SHA, nie innej gałęzi. Późniejszy PR domykający ten dziennik zmienia wyłącznie docs/;
jego merge nadaje nowy SHA dokumentacyjny bez zmiany kodu. Najnowszy SHA main i jego osobny workflow
są podane w końcowym raporcie sesji i komentarzu pod #24. Nie zapisujemy własnego przyszłego SHA w treści commita.

Artefakty lokalne (ignorowane deploy/build, **NIE są zgodą na deployment**):

| Dowód | SHA-256 |
| --- | --- |
| sourceFilesSha256 | `3b83d19d7b315da89f46330835a33900f6b0da792216283ab184d143f67f77dd` |
| MTAR smoke 0.7.2 | `24d8d8ac2d35b9e63fa18c74703b2983fc173949eec2021cb09c49f51d429ebd` |
| MTAR HANA 0.7.3 | `eac3e538477a61ad50fe786a5b79ea71d0e16b0814a3e22f8f33d817be663324` |
| Storage hdbtable | `27cac1a20896f4e7971198c72e2024d48fa3e7f1e5f3d93c2e7ed2a77304de6a` |

Zachowano bieżący runtime według wcześniejszej dokumentacji: G1 oczekuje 15/5/6/15; nie pobierano
katalogu HANA ani stanu CF w tej sesji. Repo/pakiet projektuje 16/5/6/16 po przyszłym release.
KSeF auth/InvoiceRead i SAP H01 E2E niezweryfikowane LIVE; żadnych operacji biznesowych ani deploymentu.

| Osobna bramka | Decyzja ORCH-08 |
| --- | --- |
| Pierwszy IN-04 GET | GO do osobnego zatwierdzenia planu; wykonanie NO-GO bez zgody na SHA/kontekst/7 żądań |
| STOR-04 HANA deployment | GO do przygotowania release; wykonanie NO-GO bez zgód i wersjonowanego wykonywalnego G1 |
| E2E-01B hardening | GO do osobnego zadania z backlogu; nie zaimplementowano w ORCH-08 |
| Pierwszy H01 → KSeF TEST SEND | NO-GO: luki produkcyjne, snapshot/freeze, zatwierdzony UUID, runtime/storage/security i zgoda |

## Next Steps

1. Końcowy odczyt CI dokładnego main jest częścią raportu sesji; brak wyniku nigdy nie jest PASS.
2. Rekomendowany osobny krok właściciela: zatwierdzić jeden IN-04 NUMBER GET według planu,
   po akceptacji SHA/principal/TEST/7 żądań. HANA release i SEND pozostają niezależnymi bramkami.
