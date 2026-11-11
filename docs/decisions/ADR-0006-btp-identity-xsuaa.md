---
title: "ADR-0006 — Tożsamość BTP: XSUAA dla CAP Java, approuter, fail-closed zakresu NIP/CompanyCode"
date: 2026-11-08
type: adr
status: proponowany
tags: [ainvoicer, architektura, security, btp, xsuaa, ias, cap-java, approuter, cap-07]
---

# ADR-0006 — Tożsamość BTP dla CAP Java

**Status: proponowany** (CAP-07, `feature/cap-07-btp-identity`). Zatwierdza właściciel przy review PR. Nie zmienia [ADR-0005](ADR-0005-rap-business-status-cap-integration-status.md).

[Przewodnik konfiguracji](../guides/BTP_IDENTITY_SETUP.md) · [Dziennik CAP-07](../journal/2026-11-08-cap-07-btp-identity.md)

## Kontekst

`KsefService` wymaga `authenticated-user`, roli `KsefOperator` i ogranicza wiersze warunkiem `taxpayerNip = $user.taxpayerNips and companyCode = $user.companyCodes`. Do CAP-06.1 jedyną tożsamością były lokalne mock users. Potrzebny jest rzeczywisty business user na SAP BTP CF, bez Basic Auth, bez cichego fallbacku na mock i bez rozszerzania dostępu.

Zweryfikowane fakty (2026-11-08):

- CAP Java 5.2.0: dokumentacja [CAP Java Security](https://cap.cloud.sap/docs/java/security) wskazuje `cds-feature-identity` + `resourceserver-security-spring-boot-starter` (albo `cds-starter-cloudfoundry`). `cds-feature-xsuaa` kończy się na 2.10.7 (Maven Central, 2025-02) — nie dotyczy CAP 5.
- POM `cds-starter-cloudfoundry` 5.2.0 deklaruje `cds-feature-identity` 5.2.0, `resourceserver-security-spring-boot-starter` 4.1.2, `spring-boot-starter-security` 4.1.1, a także `cds-feature-mt` i `cds-feature-hana` (niepotrzebne w single-tenant bez HANA).
- Bytecode `cds-feature-identity` 5.2.0: role CAP = scope XSUAA bez prefiksu `xsappname.`; `$user.<attr>` = `xs.user.attributes.<attr>`. Konfiguracja Spring aktywuje się wyłącznie przy bindingu XSUAA/IAS.
- Dokumentacja CAP rekomenduje IAS dla nowych projektów, ale „IAS tokens only contain static user information and cannot directly provide CAP roles” — role/atrybuty wymagają AMS.
- Dostępność IAS/AMS/XSUAA w subaccount trial **nie została potwierdzona** (brak `cf`/`btp` CLI i dostępu do cockpitu w sesji).

## Decyzja

1. **XSUAA (plan `application`, `tenant-mode: dedicated`) jako jedyny wariant implementacji.** IAS może być IdP przez trust subaccountu (federacja), ale CAP waliduje token XSUAA. Uzasadnienie: istniejący model CDS (rola + dwa wartościowe atrybuty) mapuje się na XSUAA bez zmian i bez dodatkowej usługi; wariant IAS wymaga AMS (polityki, `cap-ams-support`), którego dostępności nie potwierdzono. Nie budujemy dwóch stosów.
2. **Zależności pojedyncze**, nie starter CF: `cds-feature-identity` (runtime) + `resourceserver-security-spring-boot-starter` (BOM `java-bom` 4.1.2). Brak własnej kryptografii i własnego `SecurityFilterChain` dla CAP.
3. **Deskryptor `cap-java/xs-security.json`** — nazwy scope/atrybutów zgodne z `cds compile srv --to xsuaa` (cds-dk 10.2.0), ale z korektą: atrybuty `valueRequired: true` i przypięte do szablonu `KsefOperator`; bez generowanego szablonu `userattributes` z rolą domyślną. Spójność z CSN pilnuje `XsSecurityDescriptorTest`.
4. **Fail-closed par NIP/CompanyCode** (`KsefScopeUserInfoProvider`): gdy użytkownik ma >1 NIP **i** >1 CompanyCode (np. dwie role scalone przez XSUAA), oba atrybuty są zerowane → brak danych i 403 dla `requestSend`. Iloczyn list równa się zbiorowi przyznanych par tylko wtedy, gdy jedna z list ma jedną wartość — ten przypadek pozostaje dozwolony.
5. **Fail-fast na chmurze** (`CloudIdentityGuard`): profil `cf|cloud|prod|production` lub `VCAP_APPLICATION` wymaga mock OFF, aktywnego uwierzytelnienia CAP i dokładnie jednego bindingu XSUAA; inaczej aplikacja nie startuje.
6. **Approuter** (`approuter/`, `@sap/approuter` 23.4.0): `/` → statyczne SAPUI5, `/odata/v4/KsefService/` → CAP z `forwardAuthToken`, `/rap-ui/` → Destination `AINV_H01_UI_USER` (propagacja business usera), `/resources/` → publiczny UI5 CDN. Trzy konteksty tożsamości pozostają rozdzielone: JWT XSUAA dla CAP, business user SAP dla RAP (DCL), communication user `AINV_H01` wyłącznie CAP → Web API.

## Konsekwencje

**Aktualizacja INT-03, 2026-11-11:** opisane niżej historyczne ryzyko dedicated tenant zostało zamknięte
w kodzie wspólnym dekoratorem oficjalnego `JwtDecoder` SAP. Po bazowej walidacji wymagamy zgodnego `zid`
z bindingiem; opcjonalne `app_tid`/`zone_uuid` nie mogą wskazywać innej strefy. Dotyczy także metadata,
batch i przyszłych endpointów korzystających ze stosu Spring Security. Nie zmieniono kryptografii,
audience ani SecurityFilterChain CAP. Szczegóły i ograniczenia LIVE:
[audyt INT-03](../audits/2026-11-11-int-03-security-persistence.md).

- Pozytywne: rzeczywista walidacja JWT (podpis z JWKS domeny bindingu, audience, ważność) przez oficjalny stos SAP; brak mock na CF; brak eskalacji przy wielu rolach.
- Model zaufania XSUAA (zweryfikowany w CAP-07.1 w źródle `java-security` 4.1.2): `JwtValidatorBuilder` dla `Service.XSUAA` składa `JwtTimestampValidator` + `XsuaaJwtSignatureValidator` + `JwtAudienceValidator`; `JwtIssuerValidator` jest dodawany **tylko dla IAS**, więc claim `iss` nie jest walidowany (test `xsuaaTrustAnchorIsBoundKeySetNotIssuerClaim`: obcy `iss` + poprawny podpis → 200). Nagłówek `jku` jest w produkcji **ignorowany**: klucze pochodzą z `<uaadomain bindingu>/token_keys?zid=<app_tid>` (nagłówek `X-zid`), wybór po `kid`. Kotwica zaufania = `uaadomain` z bindingu + podpis + audience (`clientid`/`xsappname` bindingu) + ważność. Test „obcy JKU → 401” dowodzi tego tylko pośrednio (testowa fabryka JKU z `java-security-test`, zaufana wyłącznie dla localhost). README 4.1.2 opisuje `XsuaaJkuValidator`, którego nie ma w łańcuchu domyślnym — dokumentacja nieaktualna.
- Zagrożenie rezydualne: token innej strefy XSUAA tej samej `uaadomain` (inny subaccount w regionie) ma ważny podpis kluczem swojej strefy; przed akceptacją chroni wyłącznie **audience** (identyfikator klienta naszej instancji, unikalny w landscape). Ewentualna dodatkowa kontrola `zid` == strefa bindingu jest możliwa przez oficjalny punkt rozszerzeń `JwtValidatorBuilder.with(...)`, ale nie jest wdrożona (decyzja przy CAP-07B; bez własnej kryptografii). Najnowsze wydanie biblioteki 4.3.1 (2026-11-06) nie zmienia walidacji issuer/jku; CAP 5.2.0 deklaruje 4.1.2 — bez aktualizacji.
- Ograniczenie modelu: operator z wieloma parami NIP×spółka w obu wymiarach nie ma dostępu, dopóki nie powstanie model tuple (np. jeden atrybut `ksefScopes` z wartością `NIP:BUK` i trwała kolumna zakresu). To osobna decyzja.
- Role collection z wartościami atrybutów tworzy administrator (deskryptor nie zawiera `role-collections`).
- Principal propagation approuter → ABAP Environment nie jest potwierdzona dla tego landscape (BLOCKED); bez niej trasa `/rap-ui/` na CF nie zadziała. Nie zastępujemy jej Basic Auth.
- Profil `cf` nie miał trwałej bazy — rozstrzygnięte w [ADR-0007](ADR-0007-cap-persistence-hana-cloud.md) (HANA Cloud/HDI, fail-closed, opcjonalny identity-smoke).
- CAP-07.1: `redirect-uris` XSUAA zawężone z `https://*.${default-domain}/**` do hosta approutera.

## Alternatywy odrzucone

- **IAS + AMS teraz:** zgodne z rekomendacją CAP, ale wymaga niepotwierdzonych usług i przebudowy autoryzacji atrybutowej. Do ponownej oceny przy produkcyjnym landscape.
- **`cds-starter-cloudfoundry`:** dociąga multitenancy i HANA bez potrzeby.
- **Wygenerowany deskryptor bez zmian:** atrybuty opcjonalne i oderwane od roli.
- **Kartezjańskie atrybuty bez guardu:** potencjalne rozszerzenie dostępu (NIP_A/2000).
