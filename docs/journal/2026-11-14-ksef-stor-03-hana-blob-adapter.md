---
title: "KSEF-STOR-03 — adapter KsefDocumentStore nad tabelą HANA KsefDocuments"
date: 2026-11-14
type: journal
status: zakonczony
tags: [ainvoicer, ksef, storage, hana, blob, cap-java, security, inbound]
---

# Dziennik — KSEF-STOR-03 HANA BLOB Document Store Adapter

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) ·
[Przewodnik KSEF-STOR-03](../guides/KSEF_STOR_03_HANA_BLOB_ADAPTER.md)

## Date

2026-11-14 (Europe/Warsaw).

## Branch

`feature/ksef-stor-03-hana-blob-adapter` w osobnym worktree `D:\AInvoicer-ksef-stor-03`, utworzony z `origin/main`
= `7a47ac92d07fa8b66868e7293eb4f4bc2a07a6e0` (zgodny z wejściem zadania).

## Goal

Zintegrować istniejącą tabelę `KsefDocuments` z kontraktem `KsefDocumentStore` przez CAP `PersistenceService`,
offline, bez publicznego `BlobService` jako transportu, bez zmian plików LAB, konfiguracji INBOUND, schedulera,
API Budget, G1, LIVE i XSUAA. Osobny PR do `main`, bez merge i bez operacji LIVE. Codex równolegle: KSEF-IN-03B.

## Initial State

Główny worktree czysty na `main`. Przeczytano AGENTS.md, CLAUDE.md, STATUS, ROADMAP, BLOB-LAB-01, dziennik LAB 13.11,
KSEF_DURABLE_STORAGE (STOR-01), ADR-0008 i kod: `documents.cds`, `blob-service.cds`, `BlobDocumentsHandler`,
`KsefDocumentStore`, `DurableInboundDocumentStore`, `ContentAddressedDocumentStore`, `ObjectStorageClient`,
`DocumentReference`, `DocumentScope`, `PersistentInboundInvoiceSink`, `InboundPersistence`, `KsefSubmissionWorker`.
Zadanie zostało wyraźnie zlecone przez właściciela; nie jest realizacją owner-led BLOB-LAB-01.

## Implementation

1. **Sonda CAP na H2** (tymczasowy test, usunięty przed commitem): POST `BlobService.Documents` z jawnym `ID` → 201 i ID
   zachowane; `createdBy` z payloadu ignorowany; jawny `createdBy` w `Insert` przez `PersistenceService` zachowany;
   `systemUser` = `system-internal`; duplikat PK → `ServiceException` 409006.
2. **Wniosek audytu:** kontrakt `KsefDocumentStore` da się zagwarantować bez migracji (deterministyczny PK, klucz w
   `fileName`, zarezerwowany principal, SHA-256), ale separacja od publicznej projekcji LAB nie jest strukturalna
   (luka D-1) → adapter offline, integracja NO-GO do decyzji.
3. `HanaBlobObjectStorageClient` — `ObjectStorageClient` nad CQN; create-only, odczyt po `(fileName, createdBy)`,
   fallback losowego ID po zajęciu PK, osobny RequestContext/ChangeSet, błędy bez szczegółów.
4. `HanaKsefDocumentStore` — fabryka zwracająca istniejący `ContentAddressedDocumentStore` (reużycie scope,
   referencji `ksefdoc:v1`, SHA-256 i retry z KSEF-STOR-01); limit 3 MiB, zakres 1 B–16 MiB; bez beana.
5. Testy adaptera i pipeline INBOUND; przewodnik; STATUS/ROADMAP/README.

## Changed Files

- `cap-java/srv/src/main/java/customer/cap_java/ksef/storage/HanaBlobObjectStorageClient.java` — nowy.
- `cap-java/srv/src/main/java/customer/cap_java/ksef/storage/HanaKsefDocumentStore.java` — nowy.
- `cap-java/srv/src/test/java/customer/cap_java/ksef/storage/HanaKsefDocumentStoreTest.java` — nowy (21).
- `cap-java/srv/src/test/java/customer/cap_java/ksef/storage/HanaDocumentStoreInboundPipelineTest.java` — nowy (4).
- `docs/guides/KSEF_STOR_03_HANA_BLOB_ADAPTER.md`, ten dziennik, `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/README.md`.

Bez zmian: `db/*.cds`, `srv/*.cds`, `BlobDocumentsHandler`, `KsefInboundConfiguration`, `InboundSynchronization`,
budżet API, walidator G1, `application*.yaml`, `mta.yaml`, `xs-security.json`, `deploy/`, `pom.xml`.

## Commands Executed

- `git fetch`, `git worktree add -b feature/ksef-stor-03-hana-blob-adapter ../AInvoicer-ksef-stor-03 origin/main`.
- `mvn -q -B test -Dtest=ZzProbeTest` (sonda, następnie plik usunięty).
- `mvn -q -B test -Dtest=HanaKsefDocumentStoreTest` oraz zestaw regresji storage/LAB.
- `mvn -B clean verify` w `cap-java/` (JDK 25.0.4.1, Maven 3.9.16).

## Tests & Results

Zakres: **test lokalny H2 + CAP CQN + HTTP OData w JVM**. Nie HANA LIVE, nie restart CF.

| Suite | Wynik |
| --- | --- |
| `HanaKsefDocumentStoreTest` | 21/21 PASS |
| `HanaDocumentStoreInboundPipelineTest` | 4/4 PASS |
| `BlobServiceODataTest` (regresja LAB) | 3/3 PASS |
| `ContentAddressedDocumentStoreTest`, `DurableStorageInboundPipelineTest` | 21/21, 8/8 PASS |
| Pełne `mvn clean verify` (`cap-java/`) | **BUILD SUCCESS: 514 testów, 0 failures, 0 errors, 6 skipped** |
| `npm test --prefix deploy` | 89 PASS / 1 skipped (Go) / 1 FAIL środowiskowy: `mtad-gen` wymaga binarki `mbt` z postinstall, a lokalnie użyto `npm ci --ignore-scripts`; `deploy/` niezmienione — rozstrzyga CI |
| `git diff --cached --check` | PASS |

`knownGapPrincipalNameCollision…` PASS oznacza **potwierdzenie luki** (użytkownik o nazwie principala widzi wiersz).

## Problems Encountered

- `?$count=true` w teście HTTP nie zwrócił `@odata.count` w oczekiwanej formie — asercje zmieniono na liczbę elementów `value`.
  Nie badano dalej; to nie dotyczy adaptera.

## Decisions Made

- Reużycie `ContentAddressedDocumentStore` przez SPI zamiast nowej, równoległej logiki referencji/scope/SHA.
- Deterministyczny PK jako UNIQUE + fallback po zajęciu ID; odczyt nigdy po samym ID.
- Własny ChangeSet zapisu (semantyka Object Store PUT); sink i tak działa poza transakcją.
- Limit 3 MiB (klient INBOUND) zamiast laboratoryjnego 1 MiB i proponowanego w ADR-0008 10 MiB.
- Brak ADR: decyzje D-1…D-6 opisano w przewodniku do akceptacji właściciela.

## Current State

Adapter **zaimplementowany i przetestowany offline**, niepodłączony. **Integracja NO-GO** do decyzji D-1
(rekomendacja: dedykowana encja bez projekcji, złożony PK, aktualizacja kontraktu G1 w koordynacji z Codexem).
Odczyt XML w Inbox — tylko propozycja kontraktu (D-5). Brak operacji LIVE.

## Next Steps

1. Właściciel: decyzja D-1/D-2 (dedykowana encja vs akceptacja ryzyka dla DEV/TEST).
2. Po D-1: zmiana mapowania w `HanaBlobObjectStorageClient`, odwrócenie testu luki, aktualizacja G1 (Codex).
3. Po KSEF-IN-03B: wiring warunkowy (`@ConditionalOnProperty`) i akceptacja HANA LIVE wg przewodnika §10.
