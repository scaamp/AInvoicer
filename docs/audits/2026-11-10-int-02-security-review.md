---
title: "INT-02 — security review lokalnego KSeF Inbox"
date: 2026-11-10
type: audit
status: zakonczony
tags: [ainvoicer, security, inbound, odata, sapui5, xsuaa, int-02]
---

# INT-02 — security review

[Kontrakt i odtwarzanie](../guides/INT_02_INBOUND_FULLSTACK.md) · [Dziennik](../journal/2026-11-10-int-02-inbound-fullstack.md)

Zakres: nowy publiczny odczyt CAP oraz połączenie lokalnego UI5, regresja prywatnego stagingu
i OUTBOUND. Przegląd kodu i testy lokalne, bez pentestu BTP ani wywołań KSeF/SAP.

| Zagrożenie / kontrola | Implementacja i dowód |
| --- | --- |
| Anonimowy/nieważny JWT | 401 na prawdziwym HTTP; istniejący SAP Security/JWKS harness, bez service key |
| Brak KsefOperator | CDS READ restriction, HTTP 403; UI realnie pokazuje błąd |
| Obcy tenant | dedicated binding tenantid/identityzoneid vs zaufany atrybut tenant CAP (getAppTid/app_tid); test podpisanego obcego tenanta |
| Niejednoznaczny NIP/spółka | dokładnie 1×1, walidacja checksum/formatu; count=0/lista pusta, key404 |
| CompanyCode przypisany fikcyjnie do faktury | brak pola w kontrakcie i brak filtrowania dokumentu po spółce |
| Obcy/brakujący grant, rola SELLER | semijoin po zaufanym NIP/BUYER, środowisko TEST; A/B/shared/SELLER/no-grant |
| Wyciek przez key GET | ten sam handler i predykat co lista/count; 404 dla obcych UUID |
| Wyciek przez count/paging | predykat w SQL przed COUNT/LIMIT/OFFSET; test wielu grantów i 110 uprawnionych dokumentów |
| Wyciek przez OR/filter | scope AND całe wyrażenie użytkownika; test obcego NIP/UUID/DEMO i OR |
| select/expand/apply/search/sort | jawna projekcja bez nawigacji; whitelist CQN, odrzucenie nieobsługiwanych query |
| Publiczna modyfikacja | @readonly i tylko grant READ; POST/PATCH/DELETE/PUT odrzucone przez HTTP |
| Cała tabela, granty, checkpointy, lease, konflikty | brak encji publicznych i w metadata; próby GET404 |
| XML/hash/credentiale | brak xmlStorageRef/sha256/JWT w DTO i metadata; brak endpointu XML |
| Nieplanowane efekty | oddzielny read wiring; sync OFF; brak schedulera/startup HTTP; guardy storage/CF zachowane |
| Mieszanie sesji A/B | równoległe HTTP z osobnymi JWT; osobne przeglądarki/principale E2E |
| Utrata precyzji | Decimal(18,2), IEEE754Compatible; HTTP/UI zachowują 9999999999999999.99 |
| Błąd backendu i stare/mock dane | adapter nigdy nie przełącza na mock, UI czyści wiersze; realna odmowa/sieć/restart |
| Cross-origin credentiale | obecny runner CDP tylko odczyty dokładnego loopback origin; redirecty oczyszczane, regresja4/4 |
| CF omyłkowo aktywny | transformacja nadal Inbox disabled, capability OFF, brak fixture/testów; testy13/13 |
| Regresja transportów/OUTBOUND | pełna Maven + testy spakowanego CF JAR, KSeF/SAP WRITE/sweeper guardy |

Nie znaleziono nowego HIGH/BLOCKER w ocenianym endpoincie. W trakcie testów naprawiono
odrzucanie poprawnego `eq` (CAP IS/IS_NOT), niedozwoloną agregację zwracającą500 oraz
niespójne statusy/NULL ordering. Dodatkowo zawężono tenant do bindingu dla nowego endpointu;
nie zmieniono historycznej polityki pozostałych usług opisanej w ADR-0006.

Ograniczenia:

- Testy podpisu/JWT używają syntetycznego bindingu i klucza SAP java-security-test, nie LIVE XSUAA.
  Biblioteka testowa ma własną fabrykę JKU dla localhost; jej test nie stanowi dowodu produkcyjnej walidacji nagłówka JKU.
- Brak dowodu LIVE SSO, HANA/multi-instance i trwałego providera XML na CF. Aktywacja pozostaje osobną bramką.
- Harness E2E używa lokalnych mock principalów; testowy seed ma syntetyczne ref, bez rzeczywistego XML.
  Crash/recovery stagingu ma osobny test IN-02 (dwie JVM, plikowa H2), a restart UI dotyczy połączenia HTTP.
- `npm ci` wykazuje zastane **74 advisories** narzędzi UI5 (2 low/15 moderate/54 high/3 critical).
  Nie dodano zależności; są to zależności developerskie, niewłączane do statycznego artefaktu CF.
  Ten przegląd nie jest pełnym audytem ich osiągalności. Aktualizacja toolingu wymaga osobnego zakresu;
  serwer developerski nie jest środowiskiem produkcyjnym. AppRouter/deploy npm audit:0.
- Skan wzorców sekretów jest heurystyczny, nie potwierdza nieobecności wszystkich rodzajów credentiali w historii.

Końcowe liczby testów i GitHub CI są zapisane w dzienniku; nie traktować niewykonanej kontroli jako PASS.
