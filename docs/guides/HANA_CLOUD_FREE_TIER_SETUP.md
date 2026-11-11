---
title: "SAP HANA Cloud free tier — decyzja, utworzenie instancji i eksploatacja"
date: 2026-11-11
type: guide
status: aktywny
tags: [ainvoicer, btp, hana, hana-cloud, free-tier, cloud-foundry, persistence, costs]
---

# SAP HANA Cloud free tier dla AInvoicer

[Dziennik sesji](../journal/2026-11-11-hana-cloud-free-tier.md) · [ADR-0007 — trwałość CAP na CF](../decisions/ADR-0007-cap-persistence-hana-cloud.md) ·
[Runbook wdrożenia BTP](BTP_FIRST_DEPLOYMENT_RUNBOOK.md) · [KSEF-STOR-01 — magazyn XML/UPO](KSEF_DURABLE_STORAGE.md)
(plik z gałęzi `feature/ksef-stor-01-durable-storage`; link działa po jej merge)

> **Stan 2026-11-11:** instancja **`ainvoicer-hana-dev`** (SAP HANA Cloud, SAP HANA Database, **free tier**)
> istnieje w CF org `National Center for Cryptography_dev-v2xzij9s`, space `dev`, i odpowiada na SQL
> (`DBADMIN`, wersja `4.00.000.00.1793723943`). **CAP nie jest jeszcze do niej podłączony**: brak entitlementu
> `hdi-shared`, kontenera HDI i redeployu. Płatne plany HANA zostały usunięte z subaccount.
> Wszystkie kroki w BTP wykonał właściciel; agent prowadził i weryfikował na podstawie zrzutów ekranu.

## 1. Po co i dlaczego właśnie tak

### Problem
CAP na Cloud Foundry działa dziś w trybie identity-smoke na H2 w pamięci: restart kasuje historię operacji
KSeF, rezerwacje idempotencji, outbox i staging INBOUND. [ADR-0007](../decisions/ADR-0007-cap-persistence-hana-cloud.md)
wybrał SAP HANA Cloud + HDI, ale inventory z 2026-11-09 nie znalazło planu bezpłatnego, więc trwałość była
zablokowana kosztem. Właściciel nie akceptuje żadnych opłat.

Osobno (KSEF-STOR-01) potrzebny jest trwały magazyn **dokładnych bajtów** XML FA(3), UPO i XML faktur
zakupowych. Analogia z ECC: to rola **content repository / ArchiveLink** (magazyn + tabela linków), a nie DMS
(CV01N z wersjami i statusami).

### Rozważone opcje (debata 2026-11-11)

| Opcja | Koszt dla właściciela | Wnioski |
| --- | --- | --- |
| SAP BTP Object Store, plan `standard` (na eu20 = Azure Blob + SAS) | **płatny**; rozliczenie w blokach 100 GB, w dokumentacji brak planu free | adapter gotowy w KSEF-STOR-01, ale aktywacja zablokowana kosztem |
| PostgreSQL na prywatnym VPS | 0 w BTP (VPS już opłacany) | technicznie możliwe w CAP Java; wymaga nowego ADR, zmiany `CloudPersistenceGuard`, TLS, firewalla, backupów po stronie właściciela — **rezerwa** |
| **SAP HANA Cloud free tier (`hana-free`)** | **0** | zgodne z ADR-0007 bez przebudowy; ograniczenia: nocne zatrzymanie, usunięcie po 30 dniach bez startu — **wybrane** |
| XML/UPO jako BLOB w HANA zamiast Object Store | 0 | do decyzji w osobnym ADR (AGENTS.md §4 wymaga dziś osobnego magazynu); adapter STOR-01 pozwala podmienić warstwę bez zmian logiki KSeF |

### Korekta wcześniejszego wniosku
„HANA tylko płatna” (inventory 2026-11-09) było błędnym uogólnieniem. `cf marketplace` pokazuje wyłącznie plany
**przypisane** do subaccount. Plan `hana-free` był dostępny w global account, ale nie został wcześniej przypisany.

## 2. Stan przed zmianą (zrzuty właściciela)

W *Global Account → Entitlements → Entity Assignments* subaccount **dev** miał przypisane (wszystkie
„Assigned (unlimited units)”):

| Usługa | Technical name | Plan |
| --- | --- | --- |
| SAP HANA Cloud | `hana-cloud` | `hana` (płatny), `relational-data-lake` (płatny), `hana-cloud-connection` |
| SAP HANA Cloud | `hana-cloud-tools` | `tools` (Application) |

Brak `hana-free`. Instancji HANA nie było. Samo przypisanie planu nie generuje opłat — koszt powstaje dopiero
przy utworzeniu instancji. Ryzyko: płatny `hana` z nielimitowaną quotą pozwalał utworzyć płatną bazę jednym kliknięciem.

## 3. Procedura krok po kroku (wykonana 2026-11-11)

### 3.1 Przypisanie planu `hana-free`
1. BTP cockpit → *Global Account* → **Entitlements → Entity Assignments**.
2. Pole **Subaccounts/Directories** → wybór `dev`. Ekran startuje pusty — subaccount trzeba wskazać ręcznie.
3. **Edit → Add Service Plans** → wyszukanie `hana` → usługa **SAP HANA Cloud**.
4. Zaznaczony **wyłącznie** `hana-free` (etykieta *Free Tier*). Opis planu w cockpicie:
   *„Creates a free and limited SAP HANA Cloud, SAP HANA database instance. This free instance is stopped on a
   nightly basis and must be restarted within 30 days, or it will be deleted. Please note, only community support
   is available for free service plans, and these are not subject to SLAs.”*
5. **Add 1 Service Plan → Save**. Wynik: `hana-cloud / hana-free — Assigned (2 units)`.

### 3.2 Usunięcie płatnych planów (zabezpieczenie kosztowe)
W tej samej tabeli usunięto `hana` i `relational-data-lake` (Actions ✕ → Save). Pozostały: `tools`, `hana-free`,
`hana-cloud-connection` (opis SAP: łączy bazę z data lake „without additional costs”). Od tej chwili kreator nie
oferuje płatnej bazy.

### 3.3 Subskrypcja HANA Cloud Central
Subaccount `dev` → **Instances and Subscriptions → Create** → usługa *SAP HANA Cloud* → plan **`tools`**
(sekcja *Subscriptions*) → Create. Dostępne plany w tym oknie: `hana-cloud-connection`, `hana-free | Free Tier`, `tools`.
Bazy nie tworzono tym oknem (wymagałoby ręcznego JSON), tylko kreatorem HANA Cloud Central.

### 3.4 Uprawnienie administratora
Subaccount → **Security → Users** → użytkownik właściciela → zakładka *Role Collections* → **Assign Role Collection**
→ **SAP HANA Cloud Administrator** → ponowne zalogowanie.
Pułapka: pole wyszukiwania na zakładce filtruje tylko **już przypisane** role, więc „hana” zwraca pustą listę —
role do nadania są w oknie *Assign Role Collection*. Role HANA istnieją dopiero po zakończeniu subskrypcji `tools`.

### 3.5 Kreator instancji (HANA Cloud Central → Create Instance)

| Krok | Ustawienie | Wartość | Uzasadnienie |
| --- | --- | --- | --- |
| 1 Type | typ | SAP HANA Cloud, SAP HANA Database | bez Data Lake |
| 2 General | Runtime Environment | **Cloud Foundry**, org `National Center for Cryptography_dev-v2xzij9s`, space **`dev`** | nieodwracalne; kontener HDI w tym samym space widzi bazę bez mapowania. Zakładka CF była nieaktywna do kliknięcia *Sign in to the Cloud Foundry Environment* |
| 2 General | Instance Name | `ainvoicer-hana-dev` | nieodwracalne |
| 2 General | Description | `AInvoicer CAP TEST` | |
| 2 General | Administrator | `DBADMIN`, hasło tylko w menedżerze haseł właściciela | **wygasa po 180 dniach** |
| — | Total Estimate | **Free Tier**: 16 GB in-memory, 80 GB disk, 0 GB data lake, 1 vCPU | potwierdzenie braku kosztu |
| 3 Size & Availability | Coordinator / Workers | 16 GB, 1 vCPU, 80 GB / brak | rozmiar stały free tier |
| 3 Size & Availability | Auto-Upsize Storage | **Disabled** | dysk nie rośnie automatycznie |
| 3 Size & Availability | Availability Zone / Replicas | Assign automatically (nieodwracalne) / 0 | |
| 4 Connections | Allowed Connections | **Allow only IP addresses from Cloud Foundry in this BTP region** | brak dostępu z całego internetu |
| 4 Connections | Instance-Specific DNS, Cloud Connector | Disabled | |
| 5 Advanced | Version | 2026.28 (QRC 3/2026), maintained until 2027-04-15 | |
| 5 Advanced | Additional Features | Natural Language Processing and Time Series Forecasting; Predictive Analysis Library | decyzja właściciela do własnych eksperymentów; zużywają te same 16 GB; nie są częścią architektury AInvoicer |
| 5 Advanced | Instance Mapping | brak | zbędne przy instancji utworzonej w CF space |

Na ekranie podsumowania (*Review New Instances*) parametry były zgodne z tabelą → **Create Instance**.
Ostrzeżenie kreatora „Additional features … may increase your licensing cost” dotyczy instancji płatnych;
free tier ma stały rozmiar, a subaccount nie ma już płatnego planu.

### 3.6 Weryfikacja działania
HANA Cloud Central → **SQL Console** → instancja `ainvoicer-hana-dev` (schemat bieżący `DBADMIN`):

```sql
SELECT CURRENT_USER, CURRENT_TIMESTAMP, (SELECT VERSION FROM M_DATABASE) AS HANA_VERSION
FROM DUMMY;
```

Wynik (zrzut właściciela): `DBADMIN` · `2026-11-11 08:57:28.089` · `4.00.000.00.1793723943`.
`CURRENT_TIMESTAMP` zwrócił czas UTC (w Warszawie była 09:57) — istotne przy porównywaniu znaczników czasu z CAP.
Zapytania o pamięć/dysk (aneks A) przygotowano, wyniku nie raportowano.

## 4. Eksploatacja — o czym pamiętać

| Temat | Zasada |
| --- | --- |
| Nocne zatrzymanie | baza zatrzymuje się co noc; przed pracą: HANA Cloud Central → Instances → **Actions → Start**. Do startu CAP na CF nie ma bazy (outbox/retry przetrwają, UI zwróci błędy) |
| **30 dni** | brak uruchomienia przez 30 dni = **usunięcie instancji z danymi**; ustaw cotygodniowe przypomnienie |
| Hasło `DBADMIN` | wygasa po 180 dniach (ok. 2027-05-10); tylko administracja, nigdy dla aplikacji |
| Użytkownik aplikacji | CAP łączy się technicznym użytkownikiem kontenera HDI z bindingu, nie `DBADMIN` |
| Limit | jedna instancja SAP HANA Database free tier na global account na landscape |
| Wsparcie | tylko community, bez SLA; brak replik i backupu jak w planie płatnym — dane traktuj jako TEST |
| Koszty | kontrola w *Global Account → Costs and Usage*; nie przywracaj planu `hana` bez świadomej decyzji |
| Dane | wyłącznie syntetyczne/TEST; PROD wymaga osobnej decyzji o planie i ochronie danych |

## 5. Bezpieczeństwo
- Hasło `DBADMIN` nie trafia do repo, czatu, logów ani `cf create-service -c`.
- Połączenia tylko z IP Cloud Foundry regionu eu20; brak publicznego dostępu z internetu.
- Szczegóły bindingu (`cf env`, service key) zawierają credentiale — agent ich nie odczytuje.
- W dokumentacji nie zapisujemy loginu użytkownika BTP.

## 6. Następne kroki (nie wykonane)

Aktualizacja INT-03: sesja CF działa; read-only inventory potwierdza HANA free i brak oferty `hana`/HDI.
Przygotowano wariant wymagający istniejącego kontenera zamiast automatycznego provisioningu pełnym MTA.
Poniższa lista zachowuje plan z konfiguracji właściciela; aktualna procedura/bramki:
[HANA CAP deployment](HANA_CLOUD_CAP_DEPLOYMENT.md). BLOB pozostaje propozycją, nie zatwierdzoną zmianą AGENTS.md.
1. **Entitlement HDI:** *SAP HANA Schemas & HDI Containers* (technical name `hana`), plan **`hdi-shared`** —
   przed przypisaniem potwierdzić w Add Service Plans/Discovery Center, że nie nalicza opłat.
2. Odczyt `cf marketplace -e hana-cloud` / `-e hana` po `cf login --sso` (token CLI wygasł 2026-11-11).
3. Wdrożenie pełnego MTA z zasobem `ainvoicer-db` (`com.sap.xs.hdi-container`, `hana`/`hdi-shared`) zamiast
   identity-smoke — **osobna zgoda właściciela**, koordynacja z CAP-07D (Codex, `deploy/`, guardy).
4. Testy z ADR-0007 na HANA: migracja HDI, `lock()`/CAS, mapowanie timeoutów/konfliktów, outbox po restarcie.
5. ADR: XML/UPO w HANA (BLOB) zamiast Object Store — zmiana zasady AGENTS.md §4, koszt 0.
6. Aktualizacja STATUS, ROADMAP, README i runbooka BTP (wpis „brak `hana-free`” z 2026-11-09 jest nieaktualny) w zadaniu integracyjnym.

## Aneks A — zapytania kontrolne

```sql
-- Pamięć RAM używana przez usługi bazy
SELECT ROUND(SUM(TOTAL_MEMORY_USED_SIZE) / 1024 / 1024 / 1024, 2) AS MEMORY_USED_GB FROM M_SERVICE_MEMORY;

-- Dysk według typu użycia
SELECT USAGE_TYPE, ROUND(SUM(USED_SIZE) / 1024 / 1024 / 1024, 2) AS DISK_USED_GB
FROM M_DISK_USAGE GROUP BY USAGE_TYPE ORDER BY DISK_USED_GB DESC;

-- Rozmiar tabel column store według schematu
SELECT SCHEMA_NAME, ROUND(SUM(MEMORY_SIZE_IN_TOTAL) / 1024 / 1024, 2) AS MEMORY_MB, SUM(RECORD_COUNT) AS ROWS_TOTAL
FROM M_CS_TABLES GROUP BY SCHEMA_NAME ORDER BY MEMORY_MB DESC;
```

Zapytania przygotowane na podstawie widoków systemowych HANA; poza pierwszym (§3.6) nie zostały potwierdzone
na tej instancji. W HANA Cloud część widoków może wymagać dodatkowych uprawnień.

## Źródła
- Opisy planów i parametry: zrzuty BTP cockpit i HANA Cloud Central właściciela (2026-11-11).
- [SAP Help — SAP BTP Entitlements and Roles for SAP HANA Cloud](https://help.sap.com/docs/hana-cloud/sap-hana-cloud-getting-started-guide/sap-hana-cloud-and-sap-business-technology-platform-sap-btp)
- [SAP Help — SAP HANA Database License](https://help.sap.com/docs/hana-cloud/sap-hana-cloud-administration-guide/sap-hana-database-license?q=free+tier)
- [SAP Tutorial — HANA Cloud free tier w koncie trial/produktywnym](https://developers.sap.com/tutorials/hana-cloud-mission-trial-1..html)
- [SAP Discovery Center — SAP HANA Cloud, service plans](https://discovery-center.cloud.sap/serviceCatalog/sap-hana-cloud?region=all&tab=service_plan)
- [SAP-docs/btp-object-store](https://github.com/SAP-docs/btp-object-store) — porównanie z Object Store (KSEF-STOR-01)
