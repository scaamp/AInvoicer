---
title: "E2E-01B — OUTBOUND hardening offline"
date: 2026-11-15
type: journal
status: zakonczony
tags: [ainvoicer, e2e-01b, outbound, snapshot, rap, security, recovery]
---

# E2E-01B — walidacja i blokada niezatwierdzonego SEND

[Przewodnik / audyt](../guides/E2E_01B_OUTBOUND_SEND_HARDENING.md) ·
[ADR-0010 PROPOSED](../decisions/ADR-0010-outbound-approved-snapshot-proposal.md)

## Date / Branch / Goal

2026-11-15, Europe/Warsaw. `feature/e2e-01b-outbound-send-hardening`,
osobny worktree `D:\AInvoicer-e2e-01b`. Produkcyjne walidacje SAP przed zapisem,
analiza spójności kompozycji i fail-closed przed pierwszym auth/open/send. Testy wyłącznie offline.

## Initial State

`git rev-parse HEAD`, fetch i `origin/main`: `744836c9dbb5851e5d14b5f23eadd21af31fc897`.
Checkout właściciela czysty na main. Utworzono nowy worktree z origin/main; plików właściciela
ani worktree STOR-04R nie edytowano. Przeczytano AGENTS/CLAUDE, STATUS/ROADMAP,
E2E-01A/E2E-01, backlog ORCH-08, jego dziennik, FA(3), ADR-0001/0005 i dokumentację writebacku.
Użyto skills `ainvoicer-cap-java` i `ainvoicer-ksef`.

Owner-reported IN-04: XAdES business200/JWT, GET200, 4688 bajtów, FA3_BASIC_PASS,
6/7 żądań, bez retry; SHA `b94b1eee7ca6e73f8353fe9b4c0a1b204bc49bc9df15977b4387b6156ba1b3bd`.
To relacja właściciela, **nie powtórzono testu ani nie odtworzono dowodu LIVE**.

Wersje: Microsoft JDK25.0.4.1, Maven3.9.16; projekt CAP5.2.0, Boot4.1.1,
SDK MF3.0.29 (pin `3c3a899541074aa4bd944636b25659aef7d23da7`), lokalny Node24/npm11.
Oficjalne dokumentacje CAP Event Queues/Remote Services i repo SDK sprawdzono przez web,
bez żadnych biznesowych requestów MF/SAP. Zależności i wersje nie zmienione.

## Implementation / Changed Files

- `KsefOutboundInvoiceValidator`: wspólne kontrole NEW, wariantu, UUID, wersji, powiązań dzieci,
  duplikatów, waluty, jednostek, podatku i brutto pozycji, sum; jawna polityka zaokrągleń bez korekt.
- `KsefCompositionEvidence`: pełna obserwacja wartości SAP/PartyData w pamięci. Stary zapisany
  `itemsFingerprint` i `detectDrift` pozostają bez zmiany semantyki.
- `KsefSendRequestHandler`: walidacja przed pierwszym INSERT, drugi READ i porównanie przed
  rezerwacją, kanoniczny niezerowy UUID. Bez zmian publicznego kontraktu akcji.
- `KsefInitialSendGuard` + `KsefSubmissionWorker`: kontrola metadanych/powiązań/rezerwacji oraz
  bezwarunkowe `KSEF_SNAPSHOT_POLICY_NOT_APPROVED` przed auth/open/send; zachowanie snapshotu i klucza.
- `SapFa3Preflight`: używa tego samego walidatora, zachowując wcześniejsze diagnostyczne kontrole harnessu.
- `KsefSendRequestTest`, `KsefCompositionEvidenceTest`: nowe przypadki odmów, driftu, braku skutków,
  rzeczywista bramka CAP/H2, istniejące POLLING → ACCEPTED/UPO/writeback bez pierwszego SEND.
- Cztery istniejące test suites `KsefSubmissionWorkerTest`, `KsefAcceptedWritebackTest`,
  `KsefDurableStorageOutboundTest`, `KsefFa3IntegrationTest`: mock wyłącznie nowej bramki pozwala
  nadal testować mechanikę niższych etapów z mockowanym SDK. Nie jest to dowód przejścia bramki
  produkcyjnej. Rzeczywista bramka nie jest mockowana w KsefSendRequestTest.
- Własny przewodnik, ten dziennik, ADR-0010 jako PROPOSED.

Zgodnie z bieżącym zleceniem **nie aktualizowano STATUS/ROADMAP/README**, mimo domyślnego workflow
AGENTS/skills. Nowe dokumenty linkują się wzajemnie. Nie zmieniano ksef-storage.cds,
HanaKsefDocumentStore/HanaBlobObjectStorageClient, G1/release scripts, LAB, RAP, XSUAA/Destination/MTA.

## Commands Executed / Tests & Results

`git fetch origin main`, `git worktree add -b ... origin/main`, inspekcja kodu/testów/kontraktów,
`java -version`, `mvn -version`. `gh` nie jest zainstalowany; publikacja używa Git i konektora GitHub.

| Kontrola offline | Wynik |
| --- | --- |
| Pierwszy `mvn -B clean verify -Dcds.cds.skipVersionCheck=true` | BUILD SUCCESS przed rozszerzeniem testów |
| Focused `mvn -B verify ... -Dtest=KsefSendRequestTest,KsefCompositionEvidenceTest,SapFa3Preflight*Test` | **129/0/0/0 PASS** (74 requestSend + 25 composition + 30 E2E-01A) |
| Finalny `mvn -B clean verify -Dcds.cds.skipVersionCheck=true` | **BUILD SUCCESS; 662 tests / 0 failures / 0 errors / 6 skipped** (Azurite bez emulatora); sumy niezależnie potwierdzone z XML Surefire |
| `npm test --prefix deploy` po instalacji MBT | **107 PASS, 1 Go SKIP**, 0 failure; w tym G1 63/63 i niezależna kompilacja HANA w pamięci, bez DB |
| `npm run test:runner --prefix sapui5/ainvoicer` | **8/8 PASS**, lokalny Chrome |
| `npm run build:cf` i `npm run test:cf-build --prefix sapui5/ainvoicer` | **BUILD PASS, 13/13 PASS**; wcześniejszy run przed build miał 12 PASS/1 SKIP |
| `npm run test:cf-runtime --prefix deploy` | **10/10 PASS**; lokalny gotowy JAR, syntetyczne bindingi |
| `node deploy/scripts/check-secrets.mjs` po stage nowych plików | **2720 plików PASS**, heurystyka |
| `git diff --check` | PASS |
| GitHub Actions dokładnego HEAD | Wynik w PR checks i raporcie publikacji; nie jest zastępowany lokalnym PASS |
| SAP/KSeF/CF/HANA LIVE | **NIE WYKONANO** |

Nowe negatywne testy sprawdzają status, kind, UUID, wersję, NIP, walutę, sumy nagłówka,
częściowy wynik SAP, rodzica/ID/numer/wersję pozycji, stawkę/kategorię/jednostkę, ilość/cenę/kwoty/skale,
duplikaty i referencje; brak rezerwacji/snapshotu/operacji/outboxa/storage. Sprawdzono READ unavailable
i missing, obserwowany drift nagłówka/pozycji bez timestampu, add/delete/reference/status/PartyData,
brak approval po snapshot commit, duplikat/restart dispatch i uszkodzone bindingi workera.
Istniejące testy pokrywają dwóch równoległych operatorów, rollback przed commit, nieznany SEND,
timeout możliwego efektu, hash reconciliation, ACCEPTED bez writebacku, sweeper i opóźnione UPO.
Restart/awarie w nowych testach są symulacją checkpointów/ponownej dostawy na H2; nie dowodzą HANA/CF.

## Problems Encountered

Pierwsze `npm ci --prefix deploy --ignore-scripts` nie instalowało binarium MBT. Test `mtad-gen`
nie utworzył mtad.yaml: 106 PASS/1 FAIL/1 SKIP. `npm rebuild --prefix deploy mbt` przywróciło
binarium przypiętej wersji; rerun 107 PASS/1 SKIP. Bez zmian źródeł/testów ani release scripts.
Skan sekretów jest heurystyką, nie audytem credentiali; logi buildów i wygenerowane artefakty poza Git.
Pierwszy test gotowego JAR-a uruchomiono przed zakończeniem `clean verify`: brak gotowego artefaktu
spowodował FAIL. Tego wyniku nie zaliczono; ponowiono test dopiero po BUILD SUCCESS. Bez zmian kodu.
Komentarze wstrzyknięte przez PowerShell5/Python miały uszkodzone znaki polskie; poprawiono UTF-8
bez zmiany wykonywalnej logiki po końcowym Maven.

## Decisions Made / Current State

**Implementacja nie akceptuje polityki C.** ADR-0010 rekomenduje ją warunkowo: operator zatwierdza
konkretne utrwalone bajty i obserwację SAP. To nie gwarantuje atomowej ani aktualnej kompozycji SAP
w chwili SEND. Jeśli potrzebna jest taka gwarancja, wymagane A/B lub atomowy eksport wersji.

Nie ma zmian CDS/HANA ani RAP w tym sprincie. Minimalna propozycja CDS/approval i skutki writebacku
są w ADR. Header CAS nie wystarcza dla dzieci; nie obchodzimy go i nie podmieniamy ExpectedVersion.
Istniejące snapshoty bez zgody pozostają zablokowane. Brak cichej regeneracji lub automatycznej migracji.
Retry JDK/SDK produkcyjnego SEND i zachowanie lease po długim zatrzymaniu JVM pozostają release gates.

**H01 preflight:** offline kod gotowy do osobnego zatwierdzonego READ; brak runtime dowodu/principala/UUID.
**Pierwszy KSeF TEST SEND:** NO-GO, także po samym ustawieniu enabled=true.

## Next Steps

1. Właściciel wybiera semantykę wersji SAP i writebacku oraz zatwierdza/odrzuca ADR-0010.
2. Osobne zlecenie implementacji approval/CDS/UI albo A/B w RAP, review i offline/CI.
3. Osobno autoryzowany H01 preflight, potem osobna zgoda na dokładny snapshot i pojedynczy SEND.
