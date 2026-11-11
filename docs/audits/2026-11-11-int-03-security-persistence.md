---
title: "INT-03 — audyt integracji bezpieczeństwa i trwałości"
date: 2026-11-11
type: audit
status: offline-pass-live-blocked
tags: [ainvoicer, security, xsuaa, storage, hana, int-03]
---

# INT-03 — zakres i granice dowodu

Połączono CAP-07D `2455c3f`, KSEF-STOR-01 `3502d00` i dokumentację HANA `e99254e` z bazą
`origin/main=f093af5` w osobnym worktree. Trzy merge commity bez konfliktów; nie nadpisano źródeł LIVE.
Nie wykonano deployu/restage/restartu, zmian BTP/SAP, tworzenia HDI ani zapisów do prawdziwej HANA.
Źródła historyczne pozostają dowodem wcześniejszego stanu, nie obecnej gotowości CAP na HANA.

## CAP security — luka usunięta w kodzie

CAP-07D charakteryzował HTTP200 starszych projekcji dla tokena obcego tenanta przy zaufanym podpisie
i własnym audience. Dotychczasowy guard INT-02 sprawdzał wyłącznie Inbox.
`DedicatedTenantPolicy` dekoruje każdy bean `JwtDecoder` po automatycznej konfiguracji SAP.
Najpierw działa oryginalny decoder; dopiero potem porównujemy zweryfikowane claims z jednym bindingiem
XSUAA. Nie parsujemy tokena z przeglądarki, nie piszemy kryptografii, nie podmieniamy filtrów CAP.

Na podstawie API/bytecode przypiętego `java-security` **4.1.2** `XsuaaToken.getAppTid/getZoneId` odczytuje
`zid`; ogólny `Token` stosuje inną ścieżkę aliasów. Polityka wymaga `zid`; opcjonalne `app_tid` i `zone_uuid`
muszą być zgodne. `identityzone` to nazwa, nie ID. Binding `tenantid`/`identityzoneid`/`zone_uuid` musi
zawierać co najmniej jeden niepusty ID, a obecne aliasy muszą być identyczne. Jawny `tenantmode=shared`
odrzucamy; XSUAA dedicated pozostaje wymaganiem deskryptora/as-built.

Źródła oficjalne, sprawdzone 11 listopada:
[SAP Security 4.1.2 auto-config](https://github.com/SAP/cloud-security-services-integration-library/blob/4.1.2/spring-security/src/main/java/com/sap/cloud/security/spring/autoconfig/HybridIdentityServicesAutoConfiguration.java),
[CAP Java security](https://cap.cloud.sap/docs/java/security).

| Scenariusz | Oczekiwany kontrakt |
| --- | --- |
| Brak tokena, zły podpis/czas/audience | 401 przez oficjalny stos |
| Obcy tenant przy własnym audience/podpisie | 401 przed principal CAP, metadata i obsługą batch |
| Niespójne aliasy strefy, próba spoofingu `xs.user.attributes.tenant` | 401 |
| Właściwy tenant bez operatora | 403 dla danych/akcji; uwierzytelnione metadata pozostaje zgodne z CAP |
| Brak NIP lub spółki, niejednoznaczne wiele×wiele | Fail closed; lista/count0, obcy key404, action403 |
| Właściwy operator | CAP `@restrict` nadal ogranicza NIP/CompanyCode |
| `$count`, key, filter/select/expand, batch | Tenant gate przed odczytem; filtry CAP obejmują wewnętrzne requesty |
| INBOUND | Grant BUYER/TEST przed count/paging, dokładnie jeden NIP/spółka; synchronizacja OFF |

INBOUND nie posiada autorytatywnego przypisania faktury do CompanyCode: spółka jest kontekstem,
a grant NIP/BUYER/TEST nadaje widoczność. Nie raportujemy tego jako izolacji dokumentu po spółce.
LIVE security A/B, principal H01, skuteczne role i odświeżony deployment nadal wymagają osobnego testu.
Historyczny test issuer potwierdza model zaufania SAP XSUAA, nie walidację literalnego `iss`.

## KSEF-STOR-01 — zgodność i hardening

| Kontrola | Wynik inspekcji / testów offline |
| --- | --- |
| Interfejs `KsefDocumentStore`, `LocalTestDocumentStore` | Zachowany put/get, lokalny store i wcześniejsze testy bez migracji |
| Sink INBOUND i worker OUTBOUND | Scope-bound adapter zgodny z istniejącymi konstruktorami; testy pipeline/recovery |
| Scope | OUTBOUND środowisko/NIP; INBOUND współdzielone bajty środowiska + granty w CAP |
| Integralność | SHA-256 dokładnych bajtów + długość, MD5 transportu; uszkodzenie/podmiana odrzucane |
| Idempotencja | Content addressing, create-only `If-None-Match:*`, konflikt → odczyt kontrolny; recovery nowej JVM |
| Referencje | Bez URL/SAS, walidacja formatu/scope przed siecią; hash nie daje uprawnień |
| 429/5xx | Ograniczone retry, backoff/jitter, odroczenie dla długiego numeric Retry-After; bez retry401/403 |
| Timeout | INT-03 dodaje deadline całej odpowiedzi, cancellation i bounded body; test stalled body po nagłówkach |
| Sekrety | SAS z bindingu, maskowanie `toString`, brak redirectów; brak JWT w kontrakcie storage |
| Brak konfiguracji | Provider nie jest beanem; brak automatycznego odczytu VCAP/provisioningu |
| Publiczne XML | Brak endpointu/encja publiczna bez referencji XML/UPO; same bytes nigdy w publicznym OData |

Provider Azure pozostaje **OFF**. Ani podstawowy MTA, ani wariant HANA nie otrzymują zasobu Object Store.
Opcjonalny adapter wymaga świadomego późniejszego wiring i zgody kosztowej. REST adapter obsługuje
numeric Retry-After; format daty HTTP i provider-specific milisekundy nie są obecnie odrębną polityką
planowania — brak dodatniego numeric header daje ograniczony lokalny backoff, nie agresywną pętlę.

Oficjalny Azurite **3.37.0** + SDK Azure Blob **12.29.1** działa wyłącznie na loopback, z losowym
kontem/kluczem/SAS w pamięci procesu. Sześć testów protokołu PASS; nie jest to LIVE Azure/BTP Object Store.
CI uruchamia ten sam emulator, bez prawdziwych credentiali lub płatnych usług.

## HANA readiness i pozostałe ryzyka

**HANA Free Tier LIVE / HDI NOT CONFIGURED / CAP HANA NOT CONNECTED.** Własny read-only inventory
potwierdza brak oferty `hana`, kontenera i bindingu. SQL wersji/DBADMIN pochodzi od właściciela.
Nie utożsamiamy jednego service instance CF z pełną listą baz dostępnych brokerowi.

Wariant generatora wymaga trzech existing-service, nie tworzy drugiej HANA ani HDI. HDI deployer
otrzymuje tylko `ainvoicer-db`, bez CSV/demo i auto-undeploy. Guardy wymagają XSUAA i realnego HDI/HDB;
wszystkie transporty i oba rodzaje outbox są OFF. Lazy dependencies workerów nie otwierają delivery.
`HanaReadinessConfigurationTest` sprawdza rzeczywisty wiring CAP na H2 — **nie** akceptację HANA.

Harness `HanaPersistenceIT` ma osobny profil Failsafe oraz bramki zgody, run UUID, syntetycznego scope,
bindingu i zakazu DBADMIN/CI przed DataSource. Został skompilowany offline; żadna faza HANA nie została wykonana.
Obejmuje CQN, unique/SQLState, typy, rollback, CAS/HWM, lease/fencing, locks i rekord outbox po restarcie.
Rzeczywiste timeout/deadlock SQLState, E2E dostawa do bezpiecznego consumera i recovery dwóch instancji
po outage pozostają do potwierdzenia w CAP-HANA-01. Bez dowodu nie rozszerzamy mapowania błędów H2.

Final review wykrył osobny commit checkpointu po seedzie oraz cleanup lease bez dowodu runu.
Seed jest teraz atomowy z rezerwacją checkpointu; cleanup sprawdza pod blokadą niezmieniony checkpoint,
markery własnych rekordów i znacznik właściciela lease. Lokalna regresja H2 obejmuje konflikt rezerwacji
z pełnym rollbackiem, obcy lease i zmianę checkpointu. Wyniki bieżącego HEAD w dzienniku i CI PR #8.

HANA Free Tier ma ograniczenia retencji i ochrony danych, opisane w runbooku; BLOB nie daje backupu.
[Proponowany ADR-0008](../decisions/ADR-0008-document-storage-dev-test-proposal.md) rekomenduje mały,
limitowany adapter relacyjny tylko dla odtwarzalnych DEV/TEST po osobnej akceptacji. Nie został zaimplementowany.

## Wyniki i decyzja końcowa

Końcowe liczniki regresji, wersje, artefakty SHA, commit/PR i rzeczywisty wynik CI są w
[dzienniku INT-03](../journal/2026-11-11-int-03-orchestration.md).
Pierwszy przebieg: Maven425 (419 PASS/6 skipped), Azurite6/6, QUnit112/OPA21/Inbox16, CF JAR10 i oba MTAR PASS.
Po final review/hardening harnessu lokalnie Maven429 (423 PASS/6 skipped),4 nowe regresje H2;
[CI43052235875](https://github.com/scaamp/AInvoicer/actions/runs/43052235875) HEAD368314c SUCCESS we wszystkich krokach.
[PR #8](https://github.com/scaamp/AInvoicer/pull/8) **MERGED** do main jako
`e2696ab2bee91399423ce9565a7f467194894215`, z expected head i bez obejścia ochrony.
PR #7 GitHub oznaczył merged przez ancestry; nie wykonywano niezależnego merge.
Aktualne przygotowanie, checksumy i blockery: [CAP-HANA-01](../journal/2026-11-11-cap-hana-01-first-persistence.md).
Warunki pozostające poza gotowością offline: bezpłatny entitlement HDI, jawny Database ID/mapowanie,
zgoda na kontener/deploy, realne testy CAP/HANA i LIVE SSO A/B. Nie wolno uruchamiać procesu KSeF na
podstawie samego zielonego builda ani na HANA bez zatwierdzonego trwałego magazynu XML/UPO.
