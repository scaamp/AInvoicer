---
title: "ADR-0007 — Trwałość CAP na Cloud Foundry: SAP HANA Cloud (HDI) i fail-closed bez bazy"
date: 2026-11-08
type: adr
status: proponowany
tags: [ainvoicer, architektura, cap-java, hana, hdi, cloud-foundry, persistence, cap-07]
---

# ADR-0007 — Trwałość CAP na Cloud Foundry

**Status: proponowany** (CAP-07.1, `feature/cap-07-1-deployment-readiness`). Zatwierdza właściciel przy review PR. Uzupełnia [ADR-0006](ADR-0006-btp-identity-xsuaa.md); nie zmienia [ADR-0005](ADR-0005-rap-business-status-cap-integration-status.md).

[Runbook wdrożenia](../guides/BTP_FIRST_DEPLOYMENT_RUNBOOK.md) · [Dziennik CAP-07.1](../journal/2026-11-08-cap-07-1-deployment-readiness.md)

## Kontekst

CAP jest właścicielem historii procesu KSeF: `KsefOperations`, `KsefSendReservations` (idempotencja `(InvoiceUUID, TEST)`), `KsefSubmissions`/historia, `SapResultWrites`/historia, `KsefInvoiceSnapshots` i tabela outbox CAP. Utrata tych danych po restarcie oznacza brak wiedzy, czy faktura trafiła do KSeF (ryzyko duplikatu lub zgubionego numeru) — sprzeczne z AGENTS.md §4–5.

Zweryfikowane fakty (2026-11-08):

- Profil `cf` przed zmianą nie miał bazy. Start wykonywalnego JAR-a z syntetycznym bindingiem XSUAA i bez HANA: Hikari utworzył `jdbc:h2:mem:<uuid>` — **cicha baza w pamięci** (H2 jest zależnością runtime dla developmentu).
- `cds add hana` (cds-dk 10.2.0) dla tego projektu generuje `cds-starter-cloudfoundry`, `.cdsrc.json` `requires.[production].db=hana` i deployer HDI (`@sap/hdi-deploy` 5, `hdb` 2). `cds add mta` dodaje moduł `type: hdb` i zasób `com.sap.xs.hdi-container` (`hana`/`hdi-shared`).
- `cds build --for hana --production` kompiluje cały model: 9 tabel (w tym `cds.outbox.Messages`), 4 indeksy unikalności (m.in. `KsefOperations.idempotency`), 3 widoki usługi; bez tabel zdalnego H01.
- Kod Java używa CQN (`lock()`, `Update` z CAS) i `UniqueConstraintException` — bez natywnego SQL H2. Wyjątek: mapowanie timeoutu blokady obejmuje SQLState H2 `HYT00`; odpowiednik HANA niezweryfikowany.
- Dostępność HANA Cloud/HDI w subaccount: **NOT VERIFIED** (brak CLI/cockpitu).

## Decyzja

1. **SAP HANA Cloud + kontener HDI `hdi-shared`** jako jedyna trwała baza CAP na CF. Zależność **`cds-feature-hana`** (runtime) zamiast `cds-starter-cloudfoundry` — zgodnie z ADR-0006 bez multitenancy. DataSource tworzy auto-konfiguracja CAP z bindingu `hana` (wyłączona tylko w profilu `default`).
2. **`CloudPersistenceGuard`**: w środowisku chmurowym (warunek jak `CloudIdentityGuard`) produkt JDBC musi być HANA (`HDB`); inaczej start jest przerywany, bez URL/credentiali w komunikacie. Guard zależy od `CloudIdentityGuard` (najpierw tożsamość).
3. **Tryb `ainv.deployment.identity-smoke`** — wyłącznie jawny opt-in, osobna zgoda właściciela, nieobecny w deskryptorach: baza nietrwała dozwolona tylko przy `ainv.ksef.enabled=false`, `ainv.sap-write.enabled=false`, `ainv.sap-write.sweeper.enabled=false`; `IdentitySmokeReadOnlyHandler` (po autoryzacji CAP) odrzuca `requestSend` i zapisy `KsefOperations` kodem `AINV_IDENTITY_SMOKE_READ_ONLY` (409).
4. **MTA**: zasób `ainvoicer-db` + moduł `ainvoicer-db-deployer` (build z przypiętym `cds-dk` projektu zamiast `npx`, lockfile deployera w repo). Deklaracja, bez deployu.
5. H2 pozostaje bazą lokalną i testową. Brak nowej technologii bazodanowej.

## Konsekwencje

**Aktualizacja INT-03, 2026-11-11:** istnieje HANA Free Tier, ale HDI/binding/schemat CAP nadal nie.
Wariant generowany wymaga istniejącego `ainvoicer-db` i nie tworzy HANA/HDI ani usług tożsamości.
`CloudPersistenceGuard` wymaga teraz również dokładnie jednego bindingu `hana/hdi-shared`, przed
weryfikacją produktu JDBC. Jawny readiness wyłącza oba rodzaje outbox, transporty i startup wake;
wariant smoke zachowuje dotychczasowe reguły. [Runbook i bramki](../guides/HANA_CLOUD_CAP_DEPLOYMENT.md).
Możliwość BLOB DEV/TEST jest odrębną, niezatwierdzoną [propozycją ADR-0008](ADR-0008-document-storage-dev-test-proposal.md).

- Pozytywne: restart nie usuwa historii KSeF; brak cichego trybu w pamięci; jawny, testowany wyjątek dla smoke tożsamości.
- Koszt: HANA Cloud wymaga entitlementu i instancji (największy koszt landscape); bez niej MTA się nie wdroży.
- Do potwierdzenia na HANA (CAP-07B): migracja HDI, `lock()`/CAS i mapowanie konfliktów/timeoutów (np. timeout blokady HANA może trafić jako 500 zamiast 409), outbox po restarcie, wydajność indeksów.
- Rollback schematu HDI działa do przodu — starszy MTAR może nie wdrożyć się na nowszy schemat.

## Alternatywy odrzucone

- **H2 plikowe na CF** — lokalny dysk CF jest efemeryczny (AGENTS.md §4).
- **PostgreSQL (BTP hyperscaler option)** — nowa technologia bez potwierdzonego entitlementu i bez decyzji; CAP Java wspiera, ale wymaga osobnego ADR.
- **`cds-starter-cloudfoundry`** — dociąga multitenancy (ADR-0006).
- **Identity-smoke jako domyślny tryb CF** — ukrywałby brak trwałości.
