---
title: "AInvoicer — roadmapa produktowa Beta"
date: 2026-11-24
type: roadmap
status: aktywny
tags: [ainvoicer, roadmap, beta, inbound, outbound, ksef]
---

# Roadmapa produktowa — od Beta 0.1 do Production

[Project Home](README.md) · [STATUS](STATUS.md) · [ROADMAP (historia etapów)](ROADMAP.md) ·
[Raport BETA-01](guides/BETA_01_INBOUND_READINESS.md) · [BETA-02 LIVE readiness](guides/BETA_02_LIVE_RELEASE_READINESS.md) ·
[BETA-02 UAT](guides/BETA_02_MANUAL_UAT.md)

Zwięzły widok **produktowy**. Szczegółowa historia etapów technicznych pozostaje w [ROADMAP.md](ROADMAP.md);
ten plik jest źródłem aktualnego statusu dla wersji Beta. Stan na **24.11.2026** (BETA-02; PR #29 scalony jako `aa30143`).

## Legenda statusów

| Status | Znaczenie |
| --- | --- |
| **IMPLEMENTED** | Kod/konfiguracja istnieje w gałęzi (BETA-01) lub w `main`; nie oznacza testu. |
| **OFFLINE VERIFIED** | Testy lokalne/CI (H2, mock SDK, lokalny CAP HTTP, build artefaktu) PASS. Nie dowodzi HANA/CF/KSeF. |
| **LIVE VERIFIED** | Potwierdzone w rzeczywistym środowisku docelowym (BTP/HANA/KSeF TEST), z dowodem. |
| **OWNER-REPORTED** | Wynik LIVE przekazany przez właściciela; agent nie powtarzał pomiaru. |
| **BLOCKED** | Wymaga zgody, uwierzytelnienia lub decyzji, której nie ma. |
| **NOT STARTED** | Brak implementacji. |

## Beta 0.1 — INBOUND (faktury zakupowe z KSeF TEST)

Cel: użytkownik loguje się przez BTP, widzi w Inboxie faktury pobrane z KSeF TEST, przegląda metadane/szczegóły,
pobiera oryginalny XML (z uprawnieniem), dane przetrwają restart, a izolacja podatnika jest zachowana.

| Pozycja | Stan | Dowód / bloker |
| --- | --- | --- |
| HANA release (tabela `KsefStoredDocuments`, STOR-04R) | IMPLEMENTED · OFFLINE VERIFIED · **BLOCKED** (LIVE) | MTAR 0.7.4 +1/0/0; CURRENT G1 runtime `6ebdfd14…` / 4071 zgodny na `main`. BETA-02: CF PRECHECK **BLOCKED_ENV** (agent nie widzi sesji EU20, brak MultiApps), R-1 **BLOCKED**; CURRENT G1 / HDI deploy / NEW G1 wymagają osobnych zgód. |
| Kompletny Beta MTAR (srv + AppRouter/UI5 Inbox Beta + HDI) | IMPLEMENTED · OFFLINE VERIFIED (BETA-02) | `build:btp:beta` → 0.7.5 (read-only) / 0.7.6 (+XML UI); srv i HDI bajt w bajt z 0.7.4; CI buduje i weryfikuje oba. Deploy = osobna zgoda. |
| Lokalny UAT właściciela | READY (BETA-02) | `npm run start:beta-uat --prefix sapui5/ainvoicer`; smoke 10/10 w CI. Dowód LOCAL, nie LIVE. |
| Private BLOB (adapter `HanaKsefDocumentStore` + wiring) | IMPLEMENTED · OFFLINE VERIFIED | KSEF-IN-05: bean `InboundDocumentStorage` za flagą OFF, fail-closed bez HANA i w chmurze na H2. HANA LIVE niezweryfikowane (tabela niewdrożona). |
| INBOUND pipeline (XML → SHA-256 → FA(3) → storage → metadane → grant → checkpoint) | IMPLEMENTED · OFFLINE VERIFIED · **BLOCKED** (CF) | Test offline H2 z restartem/deduplikacją/recovery. Guard `KSEF_INBOUND_CLOUD_ACTIVATION_NOT_READY` celowo zachowany. |
| Manual import jednej faktury | OFFLINE VERIFIED · **BLOCKED** (LIVE) | BETA-02: twardy budżet ≤ 9 fizycznych żądań (`BudgetedKsefTransport`, replay JDK zablokowany — dowód TLS loopback), fencing lease przed każdym HTTP. Wywołujący na CF niezaimplementowany (kontrakt w BETA-02 §5.1). `NOT_FOUND` dotyczy sprawdzonego okna PermanentStorage w TEST/NIP/BUYER. Guard CF blokuje import do czasu pozostałych bramek. [Kontrakt](guides/KSEF_IN_05_PRIVATE_INBOUND_STORAGE.md#5-b3--manual-import-jednej-faktury-implemented-behind-flag). |
| UI5 Inbox CF (opt-in build beta) | IMPLEMENTED · OFFLINE VERIFIED | CI wymaga builda i walidacji `build:cf` oraz obu odmian `build:cf:inbox-beta`. Domyślnie Inbox disabled. [Przewodnik UI5](guides/BETA_01_UI5_INBOX_CF.md). |
| XML Download | IMPLEMENTED (flaga OFF) · OFFLINE VERIFIED | `GET …/InboundInvoiceXml(<id>)/content`, grant przed BLOB, ścisła zgodność długości i SHA-256, `no-store`, `attachment`. Aktywacja na CF wymaga osobnej decyzji. |
| CompanyCode | Jeden zatwierdzony kontekst NIP/CompanyCode · **MULTI-COMPANY NO-GO** | CompanyCode jest kontekstem sesji. Widoczność dokumentu wynika z grantu BUYER/TEST dla NIP. Zatwierdzona para wymaga statycznej roli i weryfikacji efektywnych atrybutów XSUAA. |
| SSO / security (XSUAA, A/B, 401/403, tenant) | OFFLINE VERIFIED · **BLOCKED** (LIVE) | Testy A/B, NIP, CompanyCode, tenant, `$batch`, mutacje, logi — lokalny CAP HTTP. CAP-07E sign-off LIVE nadal PENDING. |
| Jeden rzeczywisty dokument KSeF TEST | OWNER-REPORTED (odczyt) · NOT STARTED (w Beta) | KSEF-IN-04: XAdES 200, GET 200, 4688 B, FA3_BASIC_PASS, SHA-256 `b94b1eee…b3bd`, 6/7 żądań, bez retry — **bez zapisu** do storage/Inbox. Import do Bety wymaga zgód. |

**Beta 0.1: READY FOR LIVE ACCEPTANCE — NIE.** Technicznie kandydat offline (BETA-02: artefakty Beta i lokalny UAT
gotowe); brak LIVE PRECHECK, HDI, deploymentu i aktywacji. **USER-READY (LIVE) — NIE; LOCAL UAT — TAK.**

## Beta 0.2 — OUTBOUND (wysyłka faktur sprzedażowych z SAP H01)

| Pozycja | Stan | Dowód / bloker |
| --- | --- | --- |
| ADR-0010 (approved snapshot) | IMPLEMENTED (projekt) · **BLOCKED** | PROPOSED; decyzja właściciela. |
| E2E-01C approval | NOT STARTED (implementacja) | Projekt gotowy; brak zgody na implementację. |
| SAP composition/version (atomowy READ kompozycji) | NOT STARTED | Brak CAS dzieci i wersji kompozycji w RAP. |
| Pierwszy SEND z H01 | **BLOCKED** | `KsefInitialSendGuard` bezwarunkowo: `KSEF_SNAPSHOT_POLICY_NOT_APPROVED`. Bez zmian w BETA-01. |
| UPO | IMPLEMENTED · OFFLINE VERIFIED | Ścieżka w workerze; LIVE tylko historycznie na gałęzi eksperymentu. |
| SAP writeback (`ApplyKsefResult`) | IMPLEMENTED · OFFLINE VERIFIED · **BLOCKED** | `ainv.sap-write.enabled=false`; CAP-04D osobno. |

## Production

| Pozycja | Stan |
| --- | --- |
| KSeF PROD | NOT STARTED (kod blokuje środowiska inne niż TEST) |
| Credentials (certyfikat/token produkcyjny, managed secrets) | NOT STARTED |
| Retencja i backup (HANA płatna lub archiwum, WORM) | NOT STARTED — Free Tier bez backupu ([ADR-0008](decisions/ADR-0008-document-storage-dev-test-proposal.md)) |
| Monitoring i alerting | NOT STARTED |
| Recovery / rekoncyliacja operatorska | IMPLEMENTED częściowo (stany RECONCILIATION_REQUIRED, orphan audit) · LIVE niezweryfikowane |
| Multi-NIP | NOT STARTED (V1: dokładnie 1 NIP + 1 CompanyCode) |
| Automatyczny scheduler + globalny API budget między JVM | IMPLEMENTED częściowo (scheduler lokalny OFF, budżet in-memory) · **BLOCKED** na CF |
| Korekty, FI/MM posting | NOT STARTED |

## Kolejność dojścia do Beta 0.1 LIVE (każdy krok = osobna zgoda)

1. Sesja CF EU20 widoczna dla agenta (właściwy `CF_HOME`/CLI + plugin MultiApps) → read-only PRECHECK i zamknięcie R-1.
   (BETA-02: sesja odnowiona przez właściciela, ale niewidoczna dla procesu agenta — BLOCKED_ENV.)
2. CURRENT G1 (1 task SELECT) → MATCH 15/5/6/15.
3. Artefakt z czystego `main` po merge BETA-02 → HDI deploy wyłącznie tabeli STOR-04 → NEW G1 16/5/6/16.
4. Decyzja: pełny deploy Beta MTAR (`build:btp:beta` 0.7.5 lub 0.7.6) — osobny smoke i rollback.
5. Decyzja bezpieczeństwa: włączenie storage/download na CF (zmiana `CloudHanaReadinessGuard` + flagi) i kontrakt
   otwarcia guardu INBOUND dla jednego importu.
6. Jeden zatwierdzony import faktury KSeF TEST → Inbox → download → restart → test A/B w BTP.
