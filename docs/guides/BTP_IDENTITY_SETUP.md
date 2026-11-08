---
title: "BTP Identity — XSUAA, approuter i zakres NIP/CompanyCode (CAP-07)"
date: 2026-11-08
type: guide
status: przygotowany-niewdrozony
tags: [ainvoicer, btp, xsuaa, ias, approuter, cap-java, security, cap-07]
---

# BTP Identity — konfiguracja dla właściciela środowiska

[Project Home](../README.md) · [ADR-0006](../decisions/ADR-0006-btp-identity-xsuaa.md) · [Dziennik CAP-07](../journal/2026-11-08-cap-07-btp-identity.md) · [RAP UI LIVE](RAP_UI_LIVE_CONNECTION.md)

> **Stan:** artefakty przygotowane i przetestowane offline. **Nic nie wdrożono**, nie utworzono żadnej usługi, roli, Destination ani trustu. Dostępność usług w subaccount trial **nie została potwierdzona**. Poniższe kroki wykonuje administrator, a `cf deploy` wymaga osobnej zgody.

## 1. Wybrany model uwierzytelniania

**XSUAA** (plan `application`, `tenant-mode: dedicated`, single-tenant). IAS może być korporacyjnym IdP podpiętym przez trust subaccountu, ale CAP waliduje token **XSUAA**. Uzasadnienie i odrzucone warianty: [ADR-0006](../decisions/ADR-0006-btp-identity-xsuaa.md).

Trzy odrębne konteksty tożsamości — nie wolno ich łączyć:

| Kontekst | Kto | Mechanizm | Autoryzacja |
| --- | --- | --- | --- |
| SAPUI5 → CAP | business user | JWT XSUAA przekazany przez approuter (`forwardAuthToken`) | scope `KsefOperator` + `taxpayerNips`/`companyCodes` |
| SAPUI5 → RAP UI H01 | ten sam business user w H01 | Destination `AINV_H01_UI_USER` z propagacją użytkownika (do potwierdzenia, §8) | IAM H01 + DCL (`ZBR_AINV_READ_1000`) |
| CAP → Web API H01 | communication user | Destination `AINV_H01` (istniejąca, LIVE READ PASS lokalnie) | arrangement `ZCS_AINV_H01`, `Z_AINV_KSF` |

JWT wystawiony dla CAP **nie jest** akceptowany automatycznie przez H01. Communication user nigdy nie trafia do przeglądarki.

## 2. Wymagane entitlementy i instancje usług

| Usługa | Plan | Instancja (MTA) | Uwagi |
| --- | --- | --- | --- |
| Authorization and Trust Management (`xsuaa`) | `application` | `ainvoicer-auth` | z `cap-java/xs-security.json` |
| Destination (`destination`) | `lite` | `ainvoicer-destination` | `AINV_H01`, `AINV_H01_UI_USER` |
| Cloud Foundry runtime | — | `ainvoicer-srv`, `ainvoicer-approuter` | ~1,3 GB RAM łącznie |
| SAP HANA Cloud | — | **brak w tym sprincie** | bloker CAP-07B: profil `cf` nie ma bazy |

Administrator najpierw potwierdza w cockpit **Entitlements**, że `xsuaa/application` i `destination/lite` są dostępne. Nie zakładamy ich dostępności.

## 3. Security descriptor

Plik: [`cap-java/xs-security.json`](../../cap-java/xs-security.json). Scope `$XSAPPNAME.KsefOperator`; atrybuty `taxpayerNips`, `companyCodes` (`valueType: s`, `valueRequired: true`); szablon `KsefOperator` z oboma atrybutami. MTA nadpisuje `xsappname` na `ainvoicer-${org}-${space}` i dodaje `redirect-uris`.

Weryfikacja: `cds compile srv --to xsuaa` (cds-dk 10.2.0) generuje te same nazwy, ale atrybuty opcjonalne w osobnym szablonie z rolą domyślną — tego nie wdrażamy. `XsSecurityDescriptorTest` porównuje deskryptor z CSN po każdym buildzie. Format roli w tokenie: `<xsappname>.KsefOperator`; CAP usuwa prefiks `xsappname.` (potwierdzone w bytecode `cds-feature-identity` 5.2.0).

## 4. Rola KsefOperator

1. Cockpit → Security → Roles → aplikacja `ainvoicer-…` → template `KsefOperator` → **Create Role**.
2. Nazwa np. `KsefOperator_5260002008_1000`. Atrybuty: **Static**, `taxpayerNips` = `5260002008`, `companyCodes` = `1000`. Nie używać wartości z IdP ani pustych/unrestricted.
3. Role Collection np. `AInvoicer KSeF Operator 5260002008-1000` → dodać tylko tę rolę.
4. Przypisać role collection do konkretnego business usera (Security → Users, właściwy IdP).

## 5. Mapowanie NIP/CompanyCode — zasada jednej pary

XSUAA łączy wartości atrybutów z wielu ról w dwie niezależne listy. `@restrict` porównuje każdą listę osobno, więc NIP_A/1000 + NIP_B/2000 dałoby także NIP_A/2000. `KsefScopeUserInfoProvider` zamyka dostęp, gdy obie listy mają więcej niż jedną wartość.

| Przypisanie | Skutek |
| --- | --- |
| 1 NIP + 1 spółka | dostęp do tej pary |
| 1 NIP + N spółek (lub N NIP + 1 spółka) | dostęp do wszystkich par (iloczyn = przyznane pary) |
| ≥2 NIP **i** ≥2 spółki | **brak danych**, `requestSend` 403 (WARN w logu, bez wartości) |
| brak któregoś atrybutu | brak danych |

Użytkownik potrzebujący kilku par w obu wymiarach wymaga przyszłego modelu tuple (ADR-0006) — nie obchodzić tego wildcardem.

## 6. Approuter

Katalog [`approuter/`](../../approuter/): `@sap/approuter` **23.4.0** (Node `^22 || ^24`), [`xs-app.json`](../../approuter/xs-app.json):

| Ścieżka | Cel | Uwierzytelnienie |
| --- | --- | --- |
| `/odata/v4/KsefService/*` | Destination `ainvoicer-cap` (CAP, `forwardAuthToken: true`) | `xsuaa`, CSRF approutera |
| `/rap-ui/*` | `AINV_H01_UI_USER` + ścieżka bindingu `ZUI_AINV_INV_H_O4/0001` | `xsuaa`; CSRF obsługuje SAP |
| `/resources/*` | `https://ui5.sap.com` (biblioteki UI5) | brak — publiczne pliki |
| `/*` | statyczny build SAPUI5 (`resources/`) | `xsuaa` |

Sesja approutera: `SESSION_TIMEOUT=15` min, wylogowanie `/do/logout`. Legacy `/sap` i usługi AI Node/Python **nie są** routowane (osobne zadanie). Build MTA kopiuje `sapui5/ainvoicer/dist` do `approuter/resources` (katalog ignorowany). Uwaga: build UI5 zawiera katalogi `test/`; przed produkcją wyłączyć je z artefaktu.

## 7. Destinations

- `AINV_H01` (istniejąca koncepcja, CAP → Web API): Type HTTP, URL = origin API H01 (bez ścieżki usługi), ProxyType Internet, uwierzytelnienie communication usera z arrangement. Sekrety tylko w Destination Service.
- `AINV_H01_UI_USER` (approuter → RAP UI): URL = origin API H01, propagacja business usera — typ uwierzytelnienia **do potwierdzenia** (§8). Nie konfigurować Basic ani communication usera.
- `ainvoicer-cap` i `ui5` są zdefiniowane w `mta.yaml` (env `destinations` approutera).

## 8. Business-user SSO do H01 — BLOCKED

Oficjalnej procedury SAP Help dla principal propagation BTP CF → ABAP Environment **nie udało się odczytać w tej sesji** (help.sap.com zwracał pustą powłokę). Nie przyjmujemy więc konkretnego typu Destination (np. SAML/JWT bearer) ani nazwy scenariusza komunikacyjnego. Administrator ustala z dokumentacją SAP:

1. czy subaccount CF i H01 korzystają z tego samego tenantu IAS / IdP;
2. wspierany typ Destination i wymagany communication scenario/system w H01 dla propagacji użytkownika;
3. mapowanie użytkownika (e-mail lub login) do business usera H01 z `ZBR_AINV_READ_1000`.

Dopóki to nie jest potwierdzone, `/rap-ui/` na CF nie zadziała; lokalnie działa istniejący reentrance ticket (UI5-09).

## 9. Testy

**Offline (wykonane, PASS):** `mvn clean verify` — `XsuaaJwtODataContractTest` (profil `cf`, syntetyczny binding XSUAA, lokalny JWKS z `java-security-test`), `CloudIdentityGuardTest`, `KsefScopePairODataTest`, `KsefScopeUserInfoProviderTest`, `XsSecurityDescriptorTest`; `node --test approuter/test/xs-app.test.mjs`. To **nie** jest dowód LIVE XSUAA.

**LIVE po wdrożeniu (do wykonania, CAP-07B):**

| Scenariusz | Oczekiwane |
| --- | --- |
| `/odata/v4/KsefService/$metadata` bez sesji | redirect do logowania XSUAA |
| Bezpośrednio na URL CAP bez tokenu / z nieprawidłowym tokenem | 401 |
| Użytkownik bez role collection | 403 |
| Rola 5260002008/1000 | tylko własne operacje, poprawny `@odata.count` |
| Dwie role różnych par | 0 rekordów, 403 `requestSend` |
| `InvoiceIntegrationStatus(<obcy ID>)` | 404 |
| Start CAP bez bindingu XSUAA | aplikacja nie startuje (`CloudIdentityGuard`) |
| `/rap-ui/Invoices` | dane tylko z DCL business usera |

## 10. Czynności administratora (kolejno)

1. Potwierdzić entitlementy `xsuaa/application`, `destination/lite` i limit pamięci CF.
2. Zdecydować o bazie dla CAP-07B (HANA Cloud albo zatwierdzony wyjątek smoke).
3. Potwierdzić dostępność SapMachine 25 w `sap_java_buildpack_jakarta` (lub decyzję o JDK).
4. Po zgodzie na deploy: `mbt build` + `cf deploy` (tworzy `ainvoicer-auth`, `ainvoicer-destination`).
5. Utworzyć rolę i role collection z §4, przypisać jednego business usera; drugi użytkownik z inną parą do testu A/B.
6. Utworzyć Destination `AINV_H01` i — po §8 — `AINV_H01_UI_USER`.
7. Skonfigurować trust IdP (IAS) w subaccount, jeśli logowanie ma iść przez IAS.
8. Przekazać agentowi URL approutera do testów §9. Nie przekazywać tokenów ani service keys.
