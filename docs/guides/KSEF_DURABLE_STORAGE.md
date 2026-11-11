---
title: "KSEF-STOR-01 — trwały magazyn dokumentów XML/UPO KSeF"
date: 2026-11-11
type: guide
status: aktywny
tags: [ainvoicer, ksef, storage, object-store, azure-blob, cap-java, security, recovery]
---

# Trwały magazyn dokumentów KSeF (KSEF-STOR-01)

[Dziennik KSEF-STOR-01](../journal/2026-11-11-ksef-stor-01.md) · [Staging INBOUND](KSEF_INBOUND_PERSISTENCE.md) ·
[ADR-0007 — persystencja HANA Cloud](../decisions/ADR-0007-cap-persistence-hana-cloud.md)

> **Status 2026-11-11:** adapter zaimplementowany i przetestowany **OFFLINE** (emulator protokołu w JVM
> oraz oficjalny emulator Microsoft Azurite 3.37.0). **Nie** był uruchomiony przeciw SAP BTP Object Store
> ani Azure Storage. Nie jest podłączony do żadnego profilu Spring/CF. LIVE = **BLOCKED** (sekcja 9).

## 1. Problem

CAP przechowuje w DB wyłącznie referencję (`xmlStorageRef`, `upoStorageRef`, `String(500)`) do dokładnych
bajtów FA(3)/UPO. Do tej pory istniał tylko `LocalTestDocumentStore` (lokalny dysk, zabroniony na CF).
Cloud Foundry działa w trybie identity-smoke bez HANA; dysk kontenera CF jest efemeryczny.

## 2. Weryfikacja opcji storage (źródła, nie pamięć)

Źródła: Markdown dokumentacji SAP Help „Object Store on SAP BTP” z publicznego repo
[`SAP-docs/btp-object-store`](https://github.com/SAP-docs/btp-object-store) (commit `029a447`, 2025-10-28;
strony help.sap.com renderują treść w JS i nie dały się odczytać narzędziem) oraz dokumentacja REST
[Azure Blob Storage](https://learn.microsoft.com/en-us/rest/api/storageservices/) (Put Blob, Get Blob,
conditional headers, error codes).

| Aspekt | Fakt z dokumentacji | Znaczenie dla AInvoicer |
| --- | --- | --- |
| Usługa / plan | `objectstore`, plan **`standard`** (CF i Kyma); stare `azure-/s3-/gcs-standard` wycofane z Eligible Cloud Services od 2024-01-15 | jedyny plan do rozważenia |
| Darmowy plan | w dokumentacji Object Store **brak** wzmianki o free tier / trial | **NOT VERIFIED**; nie zakładamy darmowego planu |
| Koszt | rozliczenie w blokach 100 GB; liczba żądań (`api_calls`) miesięcznie ograniczona per blok 100 GB (Service Description Guide) | ceny **nie** podajemy; wymagają Discovery Center/umowy |
| Protokół zależny od IaaS | AWS → bucket S3 + klucze IAM; **Azure → kontener Blob + service SAS**; GCP → bucket GCS | adapter musi pasować do landscape |
| Landscape projektu | subaccount na `cf-eu20-001`; publiczne materiały SAP (learning, trust center) wiążą eu20 z Microsoft Azure (Holandia); przykład credentiali SAP zawiera `"region": "westeurope"` | **Azure Blob**; potwierdzić `cf marketplace -e objectstore` po entitlement |
| Credentials (Azure) | `account_name`, `container_name`, `container_uri`, `sas_token`, `region`; opcjonalnie `role` (`read`/`write`) | parser `AzureObjectStoreBinding` |
| Endpoint | `container_uri` może mieć format `<konto>.zNN.blob.storage.azure.net`; nie budować URL z `account_name` | adapter używa wyłącznie `container_uri` |
| Uprawnienia SAS | domyślny binding: list/delete/write/create/add/read/tagi; rola `read`: read/list/filter; rola `write`: m.in. create/write/delete/**set immutability policy**, bez read | `write`-only odrzucamy (brak odczytu kontrolnego) |
| Szyfrowanie at-rest | Azure Storage szyfruje AES-256 kluczami Microsoft-managed; SAP nie kontroluje mechanizmu; AWS: brak custom keys | bez własnej kryptografii |
| TLS | brak jawnego wymogu w dokumentacji SAP; przykładowe SAS zawierają `spr=https` | adapter wymusza HTTPS do dozwolonego hosta |
| Wersjonowanie | domyślnie **włączone**, wersje nieaktualne wygasają po **14 dniach**; zmiana tylko `cf update-service` | okno odzyskania nadpisanego obiektu = 14 dni |
| Ochrona instancji | `preventDeletion` domyślnie **false**; bez niego usunięcie instancji usuwa dane niezależnie od wersjonowania | INT-03: wymagać `preventDeletion: true` |
| Immutability/WORM | parametry SAP Object Store dla Azure: tylko `versioning`, `preventDeletion`, `autoExpiration`, `deleteAutoExpiration` | **brak udokumentowanej retencji WORM** przez broker |
| Limity | 5 bindingów + service keys łącznie na instancję (więcej: service key → user-provided service); Azure 5 PB/instancję; Put Blob ≤ 5000 MiB (x-ms-version ≥ 2019-12-12), SAP FAQ wspomina 256 MB dla starszych wersji | XML KSeF ≪ limity; adapter ma własny limit 10 MiB |
| Rotacja | SAP zaleca rotację credentiali ≤ 90 dni przez ponowne utworzenie binding/service key | procedura w INT-03 |
| Zalecany klient | „IaaS specific SDKs / APIs”; dla Azure Java `azure-storage-blob` ≥ 12.10.0 | wybrano REST API (uzasadnienie niżej) |

### Rozważone opcje

| Opcja | Ocena |
| --- | --- |
| **SAP BTP Object Store (Azure Blob) — wybrana** | najprostsza zarządzana usługa BTP dla blobów; binding bez własnych sekretów; szyfrowanie i wersjonowanie dostawcy. Wymaga płatnego planu `standard` (entitlement NOT VERIFIED). |
| BLOB/LargeBinary w HANA Cloud | brak entitlementu HANA (tylko płatny `hana-cloud`); AGENTS.md wymaga osobnego magazynu dla dużych XML. Odrzucone. |
| SAP Document Management Service (CMIS) | bogatszy model repozytorium, ale więcej konfiguracji i nie zweryfikowany w tej sesji. Odrzucone jako bardziej złożone. |
| Adapter S3 | pasuje tylko do landscape AWS; eu20 = Azure. SPI pozwala dodać go później bez zmiany logiki KSeF. |
| Dysk CF | efemeryczny, zabroniony (AGENTS.md §4). |

**Dlaczego REST zamiast `azure-storage-blob`:** z container SAS adapter nie podpisuje żądań (token jest gotowy),
potrzebuje trzech operacji REST i działa na `java.net.http.HttpClient` z JDK 25 — **zero nowych zależności**
(`srv/pom.xml` bez zmian). SDK Azure wnosi `azure-core` + transport Netty/Reactor i własny Jackson,
co zwiększa powierzchnię zależności przy Spring Boot 4.1. Kompromis: zgodność protokołu jest odpowiedzialnością
adaptera (pokryta testami z Azurite, sekcja 8). Jeżeli INT-03 zdecyduje inaczej, wymiana dotyczy tylko
`AzureBlobSasClient` (SPI `ObjectStorageClient`).

## 3. Architektura

```text
PersistentInboundInvoiceSink / KsefSubmissionWorker      (bez zmian kodu)
            │ KsefDocumentStore.put(byte[]) / get(String)
            ▼
ContentAddressedDocumentStore.forScope(DocumentScope)    → widok implementujący DurableInboundDocumentStore
  • scope: TEST/DEMO/PROD × OUTBOUND(NIP) | INBOUND
  • SHA-256 dokładnych bajtów, create-only, odczyt kontrolny, retry tylko UNAVAILABLE
            │ ObjectStorageClient (SPI: createIfAbsent / read / list — brak delete i overwrite)
            ▼
AzureBlobSasClient  ──HTTPS, SAS z VCAP_SERVICES──▶  SAP BTP Object Store (Azure Blob container)
```

Pakiet `customer.cap_java.ksef.storage`:

| Klasa | Odpowiedzialność |
| --- | --- |
| `DocumentScope` | kontekst dostępu: środowisko, kanał, NIP właściciela (OUTBOUND) |
| `DocumentReference` | ścisły format referencji i klucza obiektu, brak traversal |
| `ContentAddressedDocumentStore` | reguły integralności, idempotencji, scope, retry, audyt orphan |
| `ObjectStorageClient` / `ObjectStorageException` | SPI protokołu i bezpieczna klasyfikacja błędów |
| `AzureBlobSasClient` | Put Blob (`If-None-Match: *`, `Content-MD5`), Get Blob, List Blobs |
| `AzureObjectStoreBinding` | parser credentiali Object Store z VCAP_SERVICES, fail closed |
| `ObjectStorageSettings` | prefiks wdrożenia, dozwolone środowiska, limity, timeouty, dozwolone hosty |
| `CapDocumentReferences` | CQN: zbiór referencji z CAP dla audytu orphan |

### Referencja i klucz

- Referencja w CAP: `ksefdoc:v1:<ENV>:<OUTBOUND|INBOUND>:<NIP|->:<sha256>` (≈ 110 znaków ≤ 500).
  Nie zawiera konta, kontenera, URL ani SAS — przeniesienie instancji nie wymaga migracji DB.
- Klucz obiektu: `<keyPrefix>/v1/<env>/<channel>/<NIP|shared>/<sha256>.xml`, budowany wyłącznie
  ze zwalidowanych segmentów (`keyPrefix` = `[a-z0-9-]`, enumy, 10 cyfr, 64 hex).
- Stare referencje `LocalTestDocumentStore` (`<sha256>.xml`) nie są akceptowane przez nowy adapter
  (`KSEF_DOCUMENT_REFERENCE_INVALID`); migracja lokalnych danych TEST nie jest potrzebna.

### Model kontekstu dostępu

- **OUTBOUND:** partycja per NIP podatnika (`ainv.ksef.nip` workera). Referencja innego NIP lub kanału jest
  odrzucana **przed** wywołaniem sieci (`KSEF_DOCUMENT_ACCESS_DENIED`), bez rozróżnienia „obcy”/„nie istnieje”.
- **INBOUND:** `KsefInboundDocuments` jest unikalny per `(environment, ksefNumber)` i współdzielony przez wiele
  `KsefInboundAccessGrants`. Partycja per NIP pierwszego importera odcięłaby kolejne granty, dlatego partycja
  INBOUND jest per środowisko, a jedynym prawem odczytu pozostaje grant CAP sprawdzany przez read model
  przed odczytem referencji.
- Identyczny SHA-256 w innym kanale/NIP to **osobny obiekt i osobna referencja**; hash nigdy nie daje dostępu.
- Środowiska: `allowedEnvironments` w ustawieniach (np. wdrożenie TEST nie zapisze PROD) + `keyPrefix`
  per wdrożenie + zalecana osobna instancja Object Store per CF space.

## 4. Gwarancje i przypadki awarii

| Scenariusz | Zachowanie | Test |
| --- | --- | --- |
| Ten sam XML ponownie | ta sama referencja; 409 → odczyt kontrolny; 1 obiekt | `putIsIdempotent…`, `concurrentPuts…` |
| Awaria po PUT, przed commit CAP | obiekt = bezpieczny orphan; retry → 409 → weryfikacja → finalizacja; 1 obiekt | `crashAfterStoragePut…`, `DurableStorageProcessRecoveryTest` (2 JVM, `halt(23)`), OUTBOUND rollback |
| Timeout po trwałym zapisie | `UNAVAILABLE`, `retryable=true`, `ambiguous=false`; ponowienie bez drugiego efektu | `timeoutAfterDurableCommit…`, `exhaustedTimeout…` |
| Ta sama referencja, inna treść (podmiana) | nigdy overwrite; GET i PUT → `KSEF_DOCUMENT_INTEGRITY_FAILED` | `…ReplacedByThirdParty…`, Azurite `thirdPartyOverwrite…` |
| Błędny hash / nieznana referencja | `KSEF_DOCUMENT_NOT_FOUND`; zniekształcona → `KSEF_DOCUMENT_REFERENCE_INVALID` bez sieci | `malformedAndTraversal…`, `unknownHash…` |
| Uszkodzony obiekt | `Content-MD5` dostawcy lub SHA-256 → `INTEGRITY_FAILED`; worker: `RECONCILIATION_REQUIRED`, **brak send** | `corruptedBytes…`, `corruptedStoredXmlIsNeverSent…` |
| Niedostępność / 5xx / 429 | ≤ 3 próby, backoff + jitter; `Retry-After` > 2 s → natychmiast `retryNotBefore`; worker planuje odczyt, INBOUND nie przesuwa checkpointu | `transientUnavailability…`, `storageOutage…` (INBOUND i OUTBOUND) |
| Restart procesu | nowa instancja tylko z VCAP_SERVICES; GET zwraca identyczne bajty | wszystkie testy `freshStore()`, 2 JVM |
| Współbieżny zapis | `If-None-Match: *` → jeden 201, reszta 409; jedna referencja | emulator 12 wątków, Azurite 8 wątków |
| Brak konfiguracji | `KSEF_DOCUMENT_STORE_BINDING_MISSING/AMBIGUOUS/INCOMPLETE/PROVIDER_UNSUPPORTED` | `AzureObjectStoreBindingTest` |
| Brak uprawnienia (401/403, rola `read`) | `KSEF_DOCUMENT_ACCESS_DENIED` / `KSEF_DOCUMENT_STORE_READ_ONLY`, bez retry | `revokedOrInsufficient…`, Azurite `invalidSignature…` |
| Za duży dokument | `KSEF_DOCUMENT_SIZE_INVALID` przy zapisie i odczycie (Content-Length i strumień) | `sizeLimits…` |

Brak globalnego exactly-once: gwarancja to **at-least-once zapis + idempotencja treściowa**, a finalizację
wykonuje CAP. Trwałość potwierdza odpowiedź `201` usługi + odczyt kontrolny; nie twierdzimy więcej niż
dokumentacja dostawcy (ZRS na Azure).

## 5. Bezpieczeństwo

- Brak credentiali w kodzie i konfiguracji: SAS wyłącznie z VCAP_SERVICES; `toString()` maskuje SAS i host.
- Brak JWT KSeF w storage: magazyn dostaje tylko bajty XML/UPO; testy sprawdzają brak nagłówka `Authorization`.
- Brak publicznych URL do XML: SAS nie opuszcza backendu; brak endpointu HTTP; referencje wyłączone
  z projekcji OData (`ksef-service.cds` bez zmian); read model INBOUND zwraca tylko `xmlAvailable`.
- Brak logowania treści: adapter niczego nie loguje; wyjątki mają stały kod, bez `cause`, URL i treści.
- TLS: HTTPS bez portu niestandardowego, host z listy (`.blob.core.windows.net`, `.blob.storage.azure.net`),
  domyślna weryfikacja certyfikatu i hosta JDK; **przekierowania wyłączone** (SAS nie trafi na inny host).
  `http` wyłącznie przy jawnym `allowLoopbackHttp` i hoście 127.0.0.1/localhost (testy).
- Traversal: referencje i klucze z białej listy znaków; testy obejmują `../`, `\n`, wielkie litery, `v2`.
- XML listingu parsowany z `disallow-doctype-decl` i secure processing.
- Model uprawnień XSUAA bez zmian; uprawnienie użytkownika (scope/grant CAP) jest oddzielone od klucza obiektu.
- MD5 jest wyłącznie mechanizmem integralności transportu Azure; decyzje bezpieczeństwa opierają się na SHA-256.

## 6. Retencja, usuwanie, orphan

- **Aplikacja nie usuwa i nie nadpisuje dokumentów** (SPI nie ma `delete`; zapis tylko `If-None-Match: *`).
- Retencja ustawowa dokumentów podatkowych: wymaga decyzji właściciela/doradcy podatkowego; nie automatyzujemy
  usuwania. Nie włączać `autoExpiration` na aktualnych obiektach instancji z fakturami.
- Wersjonowanie SAP (domyślnie 14 dni dla wersji nieaktualnych) daje okno odzyskania po błędnym nadpisaniu
  przez stronę trzecią z uprawnieniem zapisu; adapter i tak wykrywa podmianę (SHA-256).
- `preventDeletion: true` — wymagane w INT-03, aby usunięcie instancji nie skasowało dokumentów.
- WORM/immutability: nieudokumentowane jako parametr brokera SAP → **luka**; ewentualne polityki
  niezmienności wymagają osobnej weryfikacji z SAP (ticket BC-CP-CF-OSAAS).
- **Orphan:** obiekt zapisany bez finalizacji CAP (crash, rollback). `ContentAddressedDocumentStore.orphanReport`
  + `CapDocumentReferences.collect` dają raport per partycja, z karencją (`minimumAge`) dla trwających finalizacji.
  Raport niczego nie usuwa; orphan jest nieszkodliwy (brak referencji = brak dostępu przez aplikację) i zostaje
  ponownie użyty przy retry tego samego XML. Usuwanie orphan — wyłącznie ręczna, zatwierdzona procedura poza aplikacją.
  Ograniczenie: zbiór referencji CAP w pamięci (wystarczy do raportu operacyjnego, nie do milionów wierszy).

## 7. Konfiguracja (bez aktywacji)

```java
var settings = ObjectStorageSettings.defaults("ainvoicer-dev", EnumSet.of(KsefEnvironment.TEST));
var root = ContentAddressedDocumentStore.fromVcapServices(System.getenv("VCAP_SERVICES"), null, settings);
KsefDocumentStore outbound = root.forScope(DocumentScope.outbound(KsefEnvironment.TEST, nip));
DurableInboundDocumentStore inbound = root.forScope(DocumentScope.inbound(KsefEnvironment.TEST));
```

Żaden bean Spring nie jest rejestrowany; `application.yaml`, `mta.yaml`, `deploy/` i profil `cf` bez zmian.

## 8. Testy (offline)

| Suite | Zakres |
| --- | --- |
| `ContentAddressedDocumentStoreTest` (20) | realny `AzureBlobSasClient` przez HTTP do emulatora protokołu: idempotencja, scope, prefiksy, traversal, podmiana, uszkodzenie, timeout po commit, 5xx/429/Retry-After, brak połączenia, 403/rola read, limity, 12 wątków, redirect, walidacja endpointu, listing/DOCTYPE, orphan, maskowanie |
| `AzureObjectStoreBindingTest` (4) | kształt VCAP_SERVICES wg SAP; role; brak/wiele/AWS/niepełne/zły SAS/kontener; user-provided |
| `DurableStorageInboundPipelineTest` (8) | CAP CQN/H2 + `PersistentInboundInvoiceSink` + runner/checkpoint: pełny przepływ, crash przed finalizacją, orphan, awaria i powrót storage, odwołany SAS, podmiana, wiele grantów |
| `DurableStorageProcessRecoveryTest` (1) | dwie JVM, plikowa H2, `halt(23)` po trwałym PUT, recovery bez drugiego obiektu |
| `KsefDurableStorageOutboundTest` (4) | niezmieniony `KsefSubmissionWorker` + outbox CAP: XML/UPO, rollback po PUT, uszkodzony XML bez send, awaria storage → zaplanowany odczyt |
| `AzuriteCompatibilityTest` (6, opcjonalny) | oficjalny emulator Microsoft Azurite: 201/409 create-only, metadane, `Md5Mismatch`, nadpisanie, zły podpis SAS, paginacja `NextMarker`, współbieżność |

Uruchomienie opcjonalnego testu Azurite (poza repo, katalog tymczasowy):

```bash
npm install --prefix <tmp>/azurite-pkg azurite@3.37.0 --ignore-scripts
<tmp>/azurite-pkg/node_modules/.bin/azurite-blob --blobHost 127.0.0.1 --blobPort 10070 --inMemoryPersistence
# utwórz kontener i service SAS (sr=c, sp=racwl, sv=2021-08-06) publicznym kluczem emulatora Azurite
AINV_AZURITE_CONTAINER_URI=http://127.0.0.1:10070/devstoreaccount1/ainv-azurite-zrs AINV_AZURITE_SAS=<sas> \
  mvn -f cap-java/pom.xml test -Dtest=AzuriteCompatibilityTest -Dsurefire.failIfNoSpecifiedTests=false
```

Emulatory nie dowodzą zachowania SAP BTP Object Store, sieci CF, TLS ani polityk konta Azure.

## 9. LIVE — BLOCKED / do weryfikacji

1. Entitlement `objectstore`/`standard` w subaccount i koszt (Discovery Center/umowa) — decyzja właściciela.
2. `cf marketplace -e objectstore` na `cf-eu20-001`: potwierdzenie Azure i planu.
3. Rzeczywisty format `sas_token` (enkodowanie, `spr`, `si`/`se`) i `container_uri` z bindingu.
4. 201/409/412 dla `If-None-Match: *`, `Content-MD5`, metadane, `NextMarker` na prawdziwej usłudze.
5. Uprawnienie List dla SAS z bindingu (raport community o odmowie operacji kontenera; dokumentacja SAP wymienia List).
6. TLS/łańcuch certyfikatów z kontenera CF (SapMachine 25) i limity czasu.
7. Wersjonowanie 14 dni i `preventDeletion` po `cf update-service`; brak WORM.
8. Rotacja credentiali (≤ 90 dni) bez przerwy (blue-green / dwa bindingi).

## 10. Plan integracji INT-03 (pliki poza zakresem STOR-01)

| Plik | Zmiana |
| --- | --- |
| `mta.yaml` | zasób `org.cloudfoundry.managed-service` `objectstore`/`standard`, `config: {preventDeletion: true}`, binding do `ainvoicer-srv`; osobna instancja per space |
| `deploy/` (skrypty/testy MTA) | kontrola obecności bindingu i braku sekretów; brak domyślnej aktywacji w identity-smoke |
| nowa `@Configuration` w `customer.cap_java.ksef` (lub `storage`) | `@ConditionalOnProperty(ainv.ksef.storage.provider=sap-objectstore-azure)`: `fromVcapServices`, bean `@Primary KsefDocumentStore` = `forScope(outbound(TEST, ainv.ksef.nip))` oraz bean INBOUND `forScope(inbound(TEST))` |
| `KsefInboundConfiguration` | przyjąć bean INBOUND (`DurableInboundDocumentStore`); blokada CF `KSEF_INBOUND_CLOUD_ACTIVATION_NOT_READY` zostaje do czasu limitera API |
| `LocalTestDocumentStore` | bez zmian; na CF profil nie może go aktywować (już fail closed) |
| `application.yaml` (profil `cf`) | `ainv.ksef.storage.key-prefix`, `allowed-environments: TEST`; provider OFF do zgody właściciela |
| guard startowy CF | brak/niejednoznaczny binding przy włączonym providerze = odmowa startu |
| STATUS/ROADMAP/README | scalenie stanu i podlinkowanie tego przewodnika |
| runbook | rotacja bindingu, `preventDeletion`, procedura raportu orphan, ticket SAP dla WORM |
