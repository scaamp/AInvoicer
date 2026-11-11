---
title: "ADR-0008 — propozycja XML/UPO w relacyjnym BLOB dla DEV/TEST"
date: 2026-11-11
type: adr
status: proponowany-niezatwierdzony
tags: [ainvoicer, architektura, storage, hana, ksef, int-03]
---

# ADR-0008 — propozycja, bez implementacji

**Wymaga decyzji właściciela. Nie zmienia AGENTS.md ani przyjętych granic magazynu dokumentów.**
INT-03 nie dodaje encji z bajtami ani adaptera HANA BLOB. [Audyt INT-03](../audits/2026-11-11-int-03-security-persistence.md).

## Kontekst i wykonalność

HANA Free Tier już istnieje, a Object Store `standard` nie ma zgody kosztowej. Funkcja **HANA Document Store**
jest odrębna od relacyjnego typu BLOB: jej brak w Free Tier nie oznacza braku BLOB w tabelach SQL.
CAP mapuje `LargeBinary` na BLOB; CAP Java reprezentuje bajty jako `byte[]`, a przy `@Core.MediaType` jako
`InputStream`. HDI może generować taką tabelę. To wykonalność dokumentacyjna, **nie test adaptera na naszej HANA**.
Źródła: [typy CDS](https://cap.cloud.sap/docs/cds/types),
[mapowanie Java](https://cap.cloud.sap/docs/java/cds-data),
[ograniczenia Free Tier](https://help.sap.com/docs/hana-cloud/sap-hana-cloud-administration-guide/sap-hana-database-license).

## Warianty i rekomendacja

| Obszar | A — Azure/Object Store | B — relacyjny BLOB w istniejącej HANA |
| --- | --- | --- |
| Obecny stan | Adapter KSEF-STOR-01 + emulatory, bez rejestracji beana | Wyłącznie propozycja, bez kodu/modelu |
| Koszt | `standard` bez zgody; nie zamawiać | Bez nowej usługi przy pozostaniu w obecnym free tier; pojemność współdzielona |
| DEV/TEST | Zachować adapter OFF | Rekomendowany następny eksperyment po akceptacji ADR i CAP-HANA-01 |
| Produkcja | Preferowany odrębny trwały magazyn z polityką retencji/odtwarzania, po wyborze kosztów i providera | Osobna analiza rozmiarów, backupu, retencji i kosztu HANA; Free Tier nie wystarcza |
| Transakcje | PUT poza DB, możliwe orphan, recovery create-only | Możliwa wspólna transakcja bajtów/metadanych; zachować kontrakt izolacji i idempotencji |
| Awaria | Niezależny od DB; osobne dostępności | Wspólna awaria procesu i dokumentów przy niedostępnej DB |

Free Tier ma nocne zatrzymanie, brak backup/recovery i snapshotów oraz ryzyko usunięcia po długim braku startu.
Dlatego nawet trwały BLOB przetrwa restart procesu, lecz nie zapewni archiwum ani odzyskania po utracie bazy.
Wyłącznie odtwarzalne dane syntetyczne. Parametry as-built: 16 GB RAM/80 GB dysku, 1 vCPU; nie całość dostępna
dla dokumentów. Nie deklarujemy „kosztu 0” dla całego CF ani przyszłej produkcji.

## Proponowany kontrakt przyszłego sprintu KSEF-STOR-02

- Zachować `KsefDocumentStore.put/get`, `DocumentScope`, content addressing i sprawdzanie długości/SHA-256;
  referencja bez credentiali. Format nowego providera wymaga jawnej wersji/migracji, nie reinterpretacji `ksefdoc:v1`.
- Klucz środowisko/kierunek/owner/hash; OUTBOUND per NIP, INBOUND współdzielone bajty + autoryzacja grantów
  w CAP. Hash nie nadaje uprawnień. Bez publicznej projekcji encji BLOB i bez automatycznego endpointu media.
- Limit aplikacji 10 MiB na dokument jako punkt startowy, wspólny z obecnym adapterem; pomiary przed zwiększeniem.
  Dla orientacji 10 tys. dokumentów × 100 KiB ≈ 0,95 GiB samych bajtów, bez metadanych, indeksów i narzutu DB.
- Odczyt wyłącznie potrzebnej kolumny, krótkie transakcje, ograniczona równoległość i budżet JVM;
  strumień konsumowany/zamykany w poprawnym kontekście transakcji. Nie zakładać automatycznego streamingu `byte[]`.
- Retencja DEV/TEST z limitem pojemności i uzgodnionym cleanup; brak samoczynnego usuwania dowodów procesu.
  Backup/eksport poza bazą wymaga odrębnego bezpiecznego celu i zgody; dysk CF nie jest magazynem trwałym.
- Testy: unique/content mismatch, restart JVM/DB, równoległe put, granty INBOUND, exact bytes UPO,
  limit rozmiaru/pamięci, niedostępna HANA i atomowość rollback. LIVE dopiero po osobnej zgodzie na syntetyczny zapis.

## Decyzja do zatwierdzenia

Przyjąć opcję B tylko dla DEV/TEST w osobnym sprincie po wykonaniu kontrolowanego HDI deployment.
Opcję A zachować opcjonalną i OFF. W produkcji wrócić do odrębnego zarządzanego storage z ochroną danych;
wybór providera/planów nie został podjęty. Odrzucenie tej propozycji pozostawia obecny kontrakt i brak
trwałego magazynu dokumentów CF jako jawną blokadę workflow KSeF.
