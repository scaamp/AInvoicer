---
title: "ORCH-08 — backlog E2E-01B OUTBOUND hardening"
date: 2026-11-15
type: guide
status: planned-not-implemented
tags: [ainvoicer, orch-08, outbound, rap, snapshot, security]
---

# E2E-01B — zakres przyszłej implementacji

[Raport](../journal/2026-11-15-orch-08-ksef-inbound-storage-e2e-integration.md) · [Preflight](E2E_01A_SAP_FA3_PREFLIGHT.md).

GO do osobnego zadania projektowego/implementacyjnego. **Nie zaimplementowano w ORCH-08. SEND NO-GO.**
Luki istniały przed PR #23. Harness nie zmienia produkcji ani nie blokuje dokumentu SAP.

| Priorytet | Zadanie | Kryterium offline |
| --- | --- | --- |
| P0 | Produkcyjna kontrola InvoiceStatus=NEW i pustych wyników KSeF | odmowa przed zapisem/enqueue |
| P0 | Pozycje: rodzic UUID, PLN, netto/VAT/brutto, ilości/stawki, sumy nagłówka | błędne dane nie tworzą operacji/snapshotu |
| P0 | Wersja całej kompozycji | headerVersion i fingerprint/wersje wszystkich dzieci; zmiana tylko pozycji wykrywana |
| P0 | Polityka drift po przygotowaniu | jawne freeze/version agreement; blokada wysyłki niezatwierdzonej wersji |
| P0 | Immutable snapshot CAP a SAP | zgoda dotyczy dokładnych bajtów/SHA/wersji/PartyData |
| P0 | Worker przed SEND | kontrola wymaganej gwarancji wersji/freeze; drift blokuje, bez cichej regeneracji XML |
| P0 | Idempotencja i reconciliation | rezerwacja pozostaje po UNKNOWN; brak nowego SEND/klucza |
| P1 | Testy wyścigów | edycja podczas READ, po snapshot commit, przed SEND; dwóch operatorów; crash/restart |
| P1 | Pierwszy zatwierdzony UUID H01 | osobno wskazany/utworzony dokument, zatwierdzony READ/PartyData/principal |

Drugi READ wykrywa obserwowany drift, nie gwarantuje atomowości do SEND. Header ETag nie obejmuje
automatycznie dzieci. Nowy DataWytworzeniaFa zmienia hash; preflight nie zatwierdza bajtów przyszłego SEND.
Zmiana po przygotowaniu zatrzymuje istniejącą operację do oceny zgodnie z etapem procesu;
nie tworzy automatycznie nowej wysyłki.

| Wariant decyzji architektonicznej | Gwarancja i kompromis | Wpływ na RAP |
| --- | --- | --- |
| Jawne freeze + wersja kompozycji | stabilne dane do SEND; potrzebna polityka recovery/unfreeze | nowa autoryzowana akcja/state i odmowa edycji dzieci; osobny SAP WRITE i ADR |
| Niezmienna wersja SAP | CAP wiąże snapshot z zaakceptowaną wersją biznesową | wersjonowanie/akceptacja kompozycji, większa zmiana modelu RAP |
| Zatwierdzony immutable snapshot CAP | operator akceptuje archiwalną wersję przy dalszej edycji SAP | osobna zgoda na semantykę i writeback; nie zapewnia „zawsze aktualny SAP” |

Nie dodawać automatycznie SAP WRITE/freeze. Ręczny zakaz edycji daje słabszą gwarancję i wymaga
jawnej akceptacji; nie przedstawiać go jako blokady atomowej.

Po implementacji: niezależny review, pełna regresja i zatwierdzony H01 READ/preflight.
Pierwszy SEND wymaga osobnej zgody na UUID, utrwalony SHA/wersje, runtime/storage,
credentiale i writeback/reconciliation/UPO. GO do hardeningu nie jest zgodą na SEND.
