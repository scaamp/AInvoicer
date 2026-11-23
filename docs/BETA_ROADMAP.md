---
title: "AInvoicer — roadmapa produktowa Beta"
date: 2026-11-23
type: roadmap
status: aktywny
tags: [ainvoicer, roadmap, beta, inbound, outbound, ksef]
---

# Roadmapa produktowa — od Beta 0.1 do Production

[Project Home](README.md) · [STATUS](STATUS.md) · [ROADMAP (historia etapów)](ROADMAP.md) ·
[Raport BETA-01](guides/BETA_01_INBOUND_READINESS.md)

Zwięzły widok **produktowy**. Szczegółowa historia etapów technicznych pozostaje w [ROADMAP.md](ROADMAP.md);
ten plik jest źródłem aktualnego statusu dla wersji Beta. Stan na **23.11.2026** (BETA-01).

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
| HANA release (tabela `KsefStoredDocuments`, STOR-04R) | IMPLEMENTED · OFFLINE VERIFIED · **BLOCKED** (LIVE) | MTAR 0.7.4 +1/0/0 i G1 stary 63 / STOR-04 86 PASS offline. CF PRECHECK **BLOCKED_AUTH**, R-1 **BLOCKED**, CURRENT G1 / HDI deploy / NEW G1 wymagają osobnych zgód. |
| Private BLOB (adapter `HanaKsefDocumentStore` + wiring) | IMPLEMENTED · OFFLINE VERIFIED | KSEF-IN-05: bean `InboundDocumentStorage` za flagą OFF, fail-closed bez HANA i w chmurze na H2. HANA LIVE niezweryfikowane (tabela niewdrożona). |
| INBOUND pipeline (XML → SHA-256 → FA(3) → storage → metadane → grant → checkpoint) | IMPLEMENTED · OFFLINE VERIFIED · **BLOCKED** (CF) | Test offline H2 z restartem/deduplikacją/recovery. Guard `KSEF_INBOUND_CLOUD_ACTIVATION_NOT_READY` celowo zachowany. |
| Manual import jednej faktury | IMPLEMENTED (flaga OFF, bez API) · OFFLINE VERIFIED | `InboundManualImport` na fake kliencie; filtr `ksefNumber` i okno dat NIEZWERYFIKOWANE w KSeF TEST. Na CF zablokowany guardem. |
| UI5 Inbox CF (opt-in build beta) | IMPLEMENTED · OFFLINE VERIFIED | Opt-in wariant builda; domyślny `build:cf` bez zmian (Inbox disabled). Brak deploymentu. Szczegóły: [przewodnik UI5](guides/BETA_01_UI5_INBOX_CF.md). |
| XML Download | IMPLEMENTED (flaga OFF) · OFFLINE VERIFIED | `GET …/InboundInvoiceXml(<id>)/content`, grant przed BLOB, `no-store`, `attachment`. Na CF wymaga zdjęcia blokady w `CloudHanaReadinessGuard` i flagi — osobna decyzja. |
| SSO / security (XSUAA, A/B, 401/403, tenant) | OFFLINE VERIFIED · **BLOCKED** (LIVE) | Testy A/B, NIP, CompanyCode, tenant, `$batch`, mutacje, logi — lokalny CAP HTTP. CAP-07E sign-off LIVE nadal PENDING. |
| Jeden rzeczywisty dokument KSeF TEST | OWNER-REPORTED (odczyt) · NOT STARTED (w Beta) | KSEF-IN-04: XAdES 200, GET 200, 4688 B, FA3_BASIC_PASS, SHA-256 `b94b1eee…b3bd`, 6/7 żądań, bez retry — **bez zapisu** do storage/Inbox. Import do Bety wymaga zgód. |

**Beta 0.1: READY FOR LIVE ACCEPTANCE — NIE.** Technicznie kandydat offline; brak LIVE PRECHECK, HDI, deploymentu
i aktywacji. **USER-READY — NIE.**

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

1. Odnowiona sesja CF EU20 (właściciel) → read-only PRECHECK i zamknięcie R-1.
2. CURRENT G1 (1 task SELECT) → MATCH 15/5/6/15.
3. Artefakt z czystego `main` po merge BETA-01 → HDI deploy wyłącznie tabeli STOR-04 → NEW G1 16/5/6/16.
4. Decyzja: wdrożenie `srv`+AppRouter+UI (build beta) — pełne MTA, osobny smoke i rollback.
5. Decyzja bezpieczeństwa: włączenie storage/download na CF (zmiana `CloudHanaReadinessGuard` + flagi) i kontrakt
   otwarcia guardu INBOUND dla jednego importu.
6. Jeden zatwierdzony import faktury KSeF TEST → Inbox → download → restart → test A/B w BTP.
