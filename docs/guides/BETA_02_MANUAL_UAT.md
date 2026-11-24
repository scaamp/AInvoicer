---
title: "BETA-02 — ręczny UAT właściciela (LOCAL) i przyszła akceptacja LIVE"
date: 2026-11-24
type: guide
status: aktywny
tags: [ainvoicer, beta, uat, inbound, ui5, security]
---

# BETA-02 — ręczny UAT właściciela

[Project Home](../README.md) · [STATUS](../STATUS.md) · [Roadmapa Beta](../BETA_ROADMAP.md) ·
[Gotowość LIVE BETA-02](BETA_02_LIVE_RELEASE_READINESS.md) · [Raport BETA-01](BETA_01_INBOUND_READINESS.md)

Dokument ma dwie **rozdzielne** części:

| Część | Gdzie | Co dowodzi | Czego NIE dowodzi |
| --- | --- | --- | --- |
| **A. LOCAL UAT** | Twój laptop: CAP Java + H2 w pamięci, UI5 `fiori run`, użytkownicy mock (Basic) | Przepływ użytkownika w UI5 Inbox, filtry/sortowanie/paginacja, szczegóły, pobranie XML, izolacja A/B, 401/403/404, odporność UI na awarię i restart backendu | SSO XSUAA, HANA, CF, RAP H01, prawdziwej faktury KSeF, trwałości po restarcie (H2 jest w pamięci, dane są odtwarzane) |
| **B. LIVE E2E** (przyszły etap) | BTP CF EU20 + HANA Cloud + KSeF TEST | Cel BETA: prawdziwa faktura zakupowa z KSeF TEST widoczna i pobierana po SSO, trwała po restarcie, niedostępna dla obcego | — (wymaga osobnych zgód; patrz §B) |

Wynik części A **nie** jest dowodem LIVE. Zapisuj wyniki A jako `LOCAL PASS/FAIL`, a B jako `LIVE PASS/FAIL`.

## A. LOCAL UAT

### A.0. Uruchomienie

Jednorazowo (lub po zmianie kodu Java):

```powershell
# Maven/JDK według lokalnej instalacji (mvn nie musi być w PATH globalnie)
cd cap-java
mvn -B clean verify "-Dcds.cds.skipVersionCheck=true"
cd ..
npm ci --prefix sapui5/ainvoicer
```

Start (procesy działają do **Ctrl+C**):

```powershell
npm run start:beta-uat --prefix sapui5/ainvoicer
```

Launcher:

- używa istniejących `InboundLocalServer` + `InboundFixtures` (CAP HTTP/H2 na `127.0.0.1:8092`) i `ui5-inbox.yaml`
  (UI5 + proxy `/odata` na `localhost:8087`), z konfiguracją UI z tej samej transformacji co artefakt
  `build:cf:inbox-beta -- --with-xml-download` (różnica: `pageSize=2`, aby wymusić paginację);
- włącza **lokalnie** prawdziwy backend pobrania XML: adapter magazynu na H2 (`allow-local-h2`) + `xml-download`.
  XML jest syntetyczny i oznaczony `SYNTHETIC DOCUMENT, NOT FROM KSeF`;
- odmawia startu, gdy port 8087 lub 8092 jest zajęty (nie przejmuje cudzych procesów), oraz w środowisku CF;
- nie otwiera przeglądarki i nie łączy się z KSeF, SAP H01, BTP ani HANA; logi: `sapui5/ainvoicer/build/beta-uat/`.

Szybka samokontrola bez klikania (start → 10 kontroli HTTP przez proxy UI5 → stop):
`npm run start:beta-uat --prefix sapui5/ainvoicer -- --smoke`.

**Adresy:** KSeF Inbox `http://localhost:8087/index.html#/ksef-inbox` · Kokpit `http://localhost:8087/index.html`.

**Użytkownicy testowi** (okno logowania przeglądarki; każdy użytkownik w **osobnym oknie prywatnym** — przeglądarka
pamięta dane Basic do zamknięcia okna):

| Użytkownik / hasło | Rola i atrybuty | Oczekiwana zawartość Inbox |
| --- | --- | --- |
| `operator-a` / `test-a` | KsefOperator, NIP 1234567802, CompanyCode 1000 | 5 faktur (TEST, grant BUYER) |
| `operator-b` / `test-b` | KsefOperator, NIP 9876543210, CompanyCode 2000 | 2 faktury (`FV/SHARED`, `FOREIGN-B`) |
| `operator-other-company` / `test-cc` | ten sam NIP co A, CompanyCode 2000 | jak A (CompanyCode jest kontekstem sesji — znane ograniczenie, multi-company NO-GO) |
| `operator-without-taxpayer-attribute` / `test-no-nip` | KsefOperator bez NIP | pusta lista |
| `operator-cross-scope` / `test-cross` | 2 NIP + 2 spółki | pusta lista (zakres niejednoznaczny, fail closed) |
| `user-without-ksef-role` / `test-user` | bez roli KsefOperator | odmowa (403) |

Hasła są wyłącznie lokalnymi danymi testowymi z `application-test.yaml`, nie sekretami.

### A.1. Scenariusze UI5

Dla każdego wiersza zapisz `PASS/FAIL` i uwagę. „A” = `operator-a`.

| # | Scenariusz | Kroki | Oczekiwany wynik |
| --- | --- | --- | --- |
| U1 | Login | Otwórz adres Inbox w oknie prywatnym, zaloguj A | Okno logowania przeglądarki; po zalogowaniu widok Inbox, bez błędów w konsoli poza informacjami UI5 |
| U2 | Kokpit | Otwórz `index.html` | Kokpit ładuje się (dane RAP lokalnie z mock servera UI5 — nie H01); brak akcji wysyłki |
| U3 | KSeF Inbox | Przejdź do Inbox (link/trasa `#/ksef-inbox`) | Tytuł Inbox, środowisko TEST, licznik |
| U4 | Lista faktur | A: przejrzyj listę | 5 pozycji łącznie (przy `pageSize=2` → 3 strony); żadnej `FOREIGN-B`, `NO-GRANT`, `OTHER-ENV`, `SELLER-ONLY` |
| U5 | Filtry | Filtruj po numerze/sprzedawcy/statusie (dostępne pola) | Wynik zawężony tylko do faktur A; pusty wynik → stan „brak danych”, nie błąd |
| U6 | Sortowanie | Sortuj po dacie/kwocie | Kolejność zmienia się deterministycznie; kwota `9999999999999999.99` wyświetlana poprawnie |
| U7 | Paginacja | Następna/poprzednia strona | 2 + 2 + 1; brak duplikatów między stronami |
| U8 | Szczegóły | Otwórz `FV/O'Brien/INT-02` | Metadane faktury; apostrof i znaki specjalne wyświetlone jako tekst (bez wykonania HTML) |
| U9 | Statusy | Otwórz `FV/CONFLICT`, `FV/UNSUPPORTED`, `FV/UNKNOWN` | Statusy integralności/parsowania czytelne; dla CONFLICT/UNKNOWN brak dostępnego XML |
| U10 | XML Download | W szczegółach `FV/O'Brien/INT-02` kliknij pobranie XML | Plik `<numer KSeF>.xml`, zawiera `SYNTHETIC DOCUMENT, NOT FROM KSeF` i numer faktury |
| U11 | Pusty Inbox | Zaloguj `operator-without-taxpayer-attribute` | Pusta lista z komunikatem, bez błędu technicznego |
| U12 | Obsługa błędów | Zatrzymaj launcher (Ctrl+C) przy otwartym Inbox, odśwież listę | Komunikat o niedostępności usługi, bez stack trace/URL/sekretów |
| U13 | Odświeżanie | Uruchom launcher ponownie, w UI użyj odświeżenia | Lista wraca; brak zdublowanych pozycji |
| U14 | Praca po restarcie | Jak U13 | Dane są takie same, bo fixture są odtwarzane (H2 w pamięci) — **to nie jest dowód trwałości HANA** |
| U15 | Responsywność | DevTools → widok telefonu (np. 390 px) i tablet | Lista i szczegóły czytelne, bez poziomego przewijania strony |

### A.2. Scenariusze bezpieczeństwa (LOCAL)

Bezpośrednie wywołania wykonuj w przeglądarce zalogowanego użytkownika lub `curl` (Basic) na `http://localhost:8087`.
Identyfikatory: OWN `10000000-0000-4000-8000-000000000001` (A), FOREIGN `…-000000000003` (B).

| # | Scenariusz | Wywołanie | Oczekiwany wynik |
| --- | --- | --- | --- |
| S1 | Operator A | Lista + XML OWN | 200, pobranie bajtów XML, nagłówki `Cache-Control: no-store`, `Content-Disposition: attachment` |
| S2 | Operator B | Lista; XML OWN | Lista bez OWN; XML OWN → **404** (bez rozróżnienia „obcy”/„nie istnieje”) |
| S3 | Użytkownik bez roli | `GET /odata/v4/KsefService/InboundInvoiceOverview` | **403** |
| S4 | Brak grantu | A: XML `…-000000000004` (`NO-GRANT`) | **404** |
| S5 | Nieprawidłowy NIP / zakres | `operator-malformed-scope` / `test-malformed`, `operator-cross-scope` | Pusta lista, XML 404 |
| S6 | 401 | Wywołanie bez nagłówka Authorization (np. `curl` bez `-u`) | **401** |
| S7 | Bezpośredni GET dokumentu | A: `GET /odata/v4/KsefService/InboundInvoiceXml(<OWN>)` (bez `/content`) | **403**; lista/`$filter`/`$count` na `InboundInvoiceXml` → **400** |
| S8 | XML Download przez `$batch` | POST `$batch` z GET `/content` | odmowa (403) — XML wyłącznie bezpośrednim GET |
| S9 | Brak wycieku | Odpowiedzi listy/szczegółów i logi `build/beta-uat/cap.log` | Brak `xmlStorageRef`, `ksefdoc:`, SHA-256, treści XML; błędy bez stack trace. Jedyny znany wyjątek: CAP przy starcie loguje (INFO `MockUsersSecurityConfig`) lokalnych użytkowników mock z hasłami testowymi z `application-test.yaml` — to dane testowe, a na CF mock jest wyłączony przez guard |

Automatyczny podzbiór S1–S4, S6, S9 wykonuje `-- --smoke`. Pełną macierz (w tym tenant, `$batch`, mutacje) pokrywają
testy CI (`InboundInvoiceXmlODataTest`, `KsefScopePairODataTest`, `test:inbox:e2e`).

### A.3. Zatrzymanie

`Ctrl+C` w terminalu launchera zatrzymuje CAP i UI5. Jeśli terminal został zamknięty awaryjnie, zakończ procesy
`java … InboundLocalServer` i `node … fiori run` w Menedżerze zadań. Dane H2 znikają razem z procesem.

## B. LIVE acceptance — przyszły etap (NIE wykonywać bez zgód)

Warunki wejścia (każdy punkt to osobna, jawna zgoda z targetem, SHA artefaktu, zakresem, liczbą prób i STOP/recovery —
lista w [BETA_02_LIVE_RELEASE_READINESS.md](BETA_02_LIVE_RELEASE_READINESS.md#7-kolejka-zgód)):
CF PRECHECK EU20 PASS → CURRENT G1 MATCH → HDI deploy STOR-04 → NEW G1 MATCH → pełny deploy Beta MTAR →
decyzja o włączeniu storage/download → jeden import KSeF TEST.

| # | Scenariusz LIVE | Oczekiwany wynik | Dowód do zapisania |
| --- | --- | --- | --- |
| L1 | SSO XSUAA na BTP | Logowanie przez AppRouter (IdP), brak Basic | Zrzut ekranu bez tokenów; rola z Role Collection |
| L2 | RAP H01 | Kokpit czyta RAP UI przez Destination (business user) | Lista RAP, bez błędów 401/403 |
| L3 | CAP/HANA | `/odata/v4/KsefService/InboundInvoiceOverview` po SSO zwraca dane z HANA | Licznik; brak H2 w logach startu |
| L4 | Jedna faktura KSeF TEST | Jeden zatwierdzony import (budżet ≤ 9 fizycznych żądań, `BudgetedKsefTransport`) | Numer KSeF (zamaskowany), status STORED/VERIFIED, `requests ≤ 9` |
| L5 | SHA-256 | SHA-256 pobranego pliku = SHA-256 z metadanych KSeF | Oba hashe |
| L6 | Pobranie XML | Przycisk w szczegółach | Plik FA(3), rozmiar > 0 |
| L7 | Restart CAP | Zatwierdzony restart `ainvoicer-srv`, ponowne pobranie | Ten sam SHA-256 (trwałość HANA) |
| L8 | Obcy użytkownik | Użytkownik bez grantu / innego NIP | Lista bez faktury; XML 404; bez roli 403 |

Wynik LIVE wolno oznaczyć `LIVE PASS` tylko z rzeczywistą odpowiedzią systemu; brak kroku = `NOT EXECUTED`.
