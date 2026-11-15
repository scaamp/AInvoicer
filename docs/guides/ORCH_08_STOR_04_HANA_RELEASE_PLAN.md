---
title: "ORCH-08 — osobny release HANA STOR-04"
date: 2026-11-15
type: guide
status: offline-plan-live-pending
tags: [ainvoicer, orch-08, hana, storage, g1, release]
---

# HANA STOR-04 — plan release bez deploymentu

[Raport](../journal/2026-11-15-orch-08-ksef-inbound-storage-e2e-integration.md) · [Adapter/model](KSEF_STOR_04_PRIVATE_HANA_STORAGE.md).

Merge nie zmienia HANA. Runtime G1 pozostaje **15/5/6/15**. Osobny
[kontrakt release v1](../../deploy/contracts/hdi-catalog-stor04.v1.json) opisuje dokładnie
**16 tabel / 5 widoków / 6 UNIQUE / 16 PK**, pełne nazwy, klucze i 8 kolumn storage.
Jest niewykonywalny. Dwa testy offline porównują go z G1 i kompilacją CDS.
Nie dopuszczamy „15 albo 16”, wildcardów ani MISSING=PASS. Historyczna sugestia opcjonalnego
MISSING w przewodniku STOR-04 nie jest wybranym planem ORCH-08.

1. Osobna zgoda na konkretne operacje LIVE: target dev, istniejący HDI, SHA/MTAR/hash,
   wersja aplikacji, zakres testów i plan przerwania.
2. Przed deployem pomiar starego ścisłego G1 15/5/6/15 (osobna zgoda SELECT).
   Brak MATCH, niepełna widoczność lub inny binding = STOP. Bez service keys/grantów.
3. Osobny recenzowany PR wykonywalnego walidatora release 16/5/6/16 według manifestu:
   pełne PK, typy/nullability, kwalifikacja kolumn LAB/storage w SQL, exact allowlista,
   negatywne testy braków/nadmiaru/obcego schematu, CLI/import i spakowany runtime.
   Stary walidator zachować jako wersjonowany artefakt poprzedniego wdrożenia.
   Przeliczyć SHA i limit taska 4096 znaków; przekroczenie = STOP.
4. Review MTAR i wszystkich artefaktów: +1 CUSTOMER_CAP_JAVA_KSEFSTOREDDOCUMENTS,
   0 usunięć i 0 zmian istniejących. Sprawdzić również zmiany aplikacji od rzeczywiście
   wdrożonej wersji. Pełny MTAR nie jest automatycznie deployem wyłącznie nowej tabeli;
   jeśli zakres wykracza poza zgodę, przygotować zatwierdzony zakres modułu deployera.
5. Przy OFF worker/SEND/scheduler/SAP WRITE wykonać zatwierdzony HDI deploy.
   Po potwierdzonym sukcesie użyć przypiętego G1 16/5/6/16. Stary G1 ma odrzucać nowy katalog,
   nowy G1 ma odrzucać katalog sprzed deployu. Przełączenie jest jawnym etapem release,
   nigdy okresem automatycznej podwójnej akceptacji.
6. Osobna bramka DML: dwa równoległe INSERT, odrębne transakcje/połączenia,
   jeden wiersz, rzeczywisty CAP 409006, read-after-conflict po rollback.
   Harness HANA trzeba osobno przygotować i zreviewować; H2 nie dostarcza tego dowodu.
7. BLOB write/read/SHA oryginału (BOM/CRLF), 3 MiB/limit+1, cross-scope,
   timeout po commicie (ponowne put nie dubluje), caller rollback/orphan recovery,
   utrata wiersza między SELECT metadanych i BLOB, błąd DB vs integralność.
8. Osobno zatwierdzony restart aplikacji i ponowny odczyt tego samego scope/hash.
   BlobService list/metadata/key/batch nie ujawniają worker BLOB niezależnie od principala.
   To akceptacja izolacji STOR-04, nie dokończenie owner-led BLOB-LAB-01.
9. Wiring Spring/CF, global budget/lease INBOUND i aktywacja pozostają osobnymi zadaniami.
   Storage PASS nie włącza schedulera ani SEND. Brak publicznego xmlStorageRef/Download XML.

Rollback aplikacji: przywrócenie zatwierdzonego artefaktu z transportami OFF i zachowaniem tabeli/danych.
Walidator nadal odpowiada fizycznemu 16/5/6/16. Bez DROP/DELETE/migracji i starego HDI payloadu
z automatycznym undeploy. Niezgodność katalogu = STOP, zachowanie danych/dowodów, decyzja właściciela.

GO do osobnego przygotowania release; **HDI deploy, G1 LIVE, DML i restart NO-GO bez zgód**.
