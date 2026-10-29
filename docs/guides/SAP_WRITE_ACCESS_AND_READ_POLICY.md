---
title: "SAP — dostęp zapisu communication user, polityka odczytu DCL i plan integralności XML"
date: 2026-10-29
type: guide
status: propozycja
tags: [ainvoicer, abap, rap, authorization, dcl, communication-user, integrity]
---

# Dostęp zapisu, polityka odczytu DCL i integralność dokumentu

[Project Home](../README.md) · [STATUS](../STATUS.md) · [Dziennik CAP-04 Hardening](../journal/2026-10-29-cap-04-hardening.md) · [ADR-0002](../decisions/ADR-0002-sap-ksef-result-write-contract.md) · [ADR-0003](../decisions/ADR-0003-cap-sap-result-writeback.md)

Ten dokument jest **propozycją i instrukcją dla właściciela SAP**. Nic w nim nie zostało wykonane w H01: w sesji hardening MCP ABAP FS nie nawiązał połączenia (`ECONNREFUSED`), więc stan IAM, scenariusza komunikacyjnego i DCL jest **NOT VERIFIED**.

## 1. Minimalna konfiguracja principala komunikacyjnego do zapisu (ACTVT 16)

Cel: CAP wywołuje wyłącznie `ApplyKsefResult`, bez zwykłego CRUD, wyłącznie dla uzgodnionego NIP i CompanyCode.

1. **Scenariusz komunikacyjny** (własny, np. `Z_AINV_KSF_WRITE`) z jedną usługą inbound OData V4 opartą o binding `ZAPI_AINV_INV_H_O4` i autoryzacją **Z_AINV_KSF**. W istniejącym stanie projektu otwarty/zablokowany scenariusz komunikacyjny pochodzi od innej zmiany i nie był tu modyfikowany.
2. **Wartości autoryzacji w scenariuszu** (nie w roli biznesowej): `ACTVT = 16`; `ZAINV_NIP = <jeden NIP podatnika>`; `ZAINV_BUK = <jedna spółka>`. **Bez** `01`, `02`, `06`, `03`, bez gwiazdek.
3. **Communication arrangement** dla tego scenariusza z użytkownikiem komunikacyjnym (osobnym od użytkownika odczytu CAP-03). Sekrety wyłącznie w BTP Destination / zarządzanym sekrecie, nie w repo i nie w logach.
4. **Destination `AINV_H01` dla zapisu** — osobna od destination odczytu, jeśli odczyt nie ma pozostać na tym samym principalu. CAP nie zawiera ręcznego Basic Auth; mechanizm to Destination Service.
5. Włączenie transportu w CAP: `ainv.sap-write.enabled=true` dopiero po pozytywnym wyniku testów negatywnych z pkt 2.

Zakres odpowiedzialności: nadanie praw i arrangement wykonuje właściciel SAP. Agent ich nie zmienia.

## 2. Testy rzeczywistym principalem (po nadaniu)

Dane testowe: **dedykowana syntetyczna faktura OUTBOUND bez numeru KSeF** (UUID i numer faktury podane jawnie). Realizuje je `SapKsefResultWriteLiveWriteTest` (`@Tag("live-sap-write")`, wymaga `AINV_H01_LIVE_WRITE=true`, UUID, numeru faktury i `AINV_H01_LIVE_WRITE_CONSENT=APPLY_KSEF_RESULT:<UUID>`; domyślnie wyłączony i **nie uruchomiony** w hardeningu).

| # | Przypadek | Oczekiwanie | Test |
| --- | --- | --- | --- |
| P1 | Akcja z właściwym NIP/CompanyCode, wersją z odczytu | `APPLIED`; niezależny GET pokazuje 4 pola | live |
| P2 | Identyczne ponowienie (ten sam request ID) | `ALREADY_APPLIED`, ETag bez zmian | live |
| P3 | Nowy request ID, ten sam numer | `ALREADY_APPLIED`, pierwotny request ID | live |
| P4 | Ten sam request ID, inny numer | `IDEMPOTENCY_CONFLICT` | live |
| P5 | Nowy request ID, inny numer | `RESULT_CONFLICT`, brak nadpisania | live |
| P6 | Przestarzała wersja (dziewicza faktura STALE) | `VERSION_CONFLICT`, brak zapisu | live (opcjonalna faktura) |
| P7 | Dwa równoległe wywołania (dziewicza faktura RACE) | dokładnie jeden `APPLIED` | live (opcjonalna faktura) |
| N1 | Obcy NIP w parametrach | odmowa (4xx), brak zapisu | live |
| N2 | Obca spółka w parametrach | odmowa (4xx), brak zapisu | live |
| N3 | Faktura INBOUND albo innego podatnika | odmowa; wymaga osobnej faktury testowej | **nie ma jeszcze testu** |
| N4 | Zwykły UPDATE/DELETE/CBA tym principalem | odmowa (`AINV_H01_LIVE_WRITE_CRUD_DENIAL=true`; UPDATE) | live; DELETE i CBA — brak |
| N5 | Principal z ACTVT 16 próbuje odczytu spoza zakresu | zależy od polityki DCL z pkt 3 | po decyzji |

Jeżeli któregoś z negatywnych testów nie da się wykonać, wynik pozostaje `NOT VERIFIED` — nie `PASS`.

## 3. Polityka odczytu DCL — propozycja do akceptacji (implementacja wstrzymana)

**Stan.** Root `ZR_AINV_INV_H` ma `grant select ... where TRUE`; projekcja i dzieci dziedziczą ten warunek. Każdy principal z dostępem do usługi OData widzi wszystkie faktury, w tym principal komunikacyjny CAP-03 (odczyt 4/9/6). Obiekt `Z_AINV_KSF` zawiera aktywność `03`, ale nigdzie nie jest używana ani nadawana.

**Dlaczego nie zmieniono.** Zmiana na `ACTVT 03 + NIP + CompanyCode` natychmiast odcięłaby obecny odczyt CAP-03 (principal nie ma `03`) i nie wiadomo, którzy użytkownicy biznesowi korzystają z ekranów UI5/Fiori. Model uprawnień zapisu nie określa jednoznacznie zakresów odczytu, więc wymagana jest decyzja.

**Proponowana polityka (wariant A, rekomendowany).**

1. Odczyt wymaga `Z_AINV_KSF` z `ACTVT = 03` oraz `ZAINV_NIP` i `ZAINV_BUK` dopasowanymi do wiersza.
2. NIP wiersza zależy od kierunku: `OUTBOUND → SellerNip`, `INBOUND → BuyerNip`. W DCL wyrażone dwoma alternatywnymi warunkami `Direction = 'OUTBOUND' AND (SellerNip, CompanyCode) = aspect pfcg_auth(...)` oraz analogicznie dla `INBOUND` (składnia do sprawdzenia w H01 przed aktywacją).
3. Dzieci (`_Items`, `_References`) dziedziczą warunek rodzica — istniejący `INHERITING CONDITIONS` pozostaje; sprawdzić bezpośredni odczyt dzieci, odczyt po kluczu i `$expand`.
4. Principal komunikacyjny **odczytu** dla CAP: ACTVT `03` i zakres obejmujący NIP/spółki obsługiwane przez CAP (jawna lista, bez gwiazdek). Principal **zapisu** (ACTVT 16) nie dostaje `03`, chyba że CAP ma czytać tym samym kontem.
5. Użytkownicy biznesowi: role IAM z `03` i właściwym zakresem; do ustalenia, którzy użytkownicy dziś korzystają z usługi.

**Kolejność wdrożenia (po akceptacji).** Najpierw nadanie `03` istniejącemu principalowi odczytu CAP w jego scenariuszu → dopiero potem aktywacja DCL → test: odczyt CAP-03 nadal 4/9/6, principal bez `03` widzi 0 wierszy, obcy NIP niewidoczny po kluczu i przez `$expand`. Rollback: przywrócenie `where TRUE`.

**Decyzje wymagane od właściciela:** (a) czy wariant A jest właściwy; (b) lista NIP/CompanyCode dla principala odczytu CAP; (c) lista użytkowników biznesowych z dostępem; (d) czy principal zapisu ma osobne konto od odczytu.

## 4. Integralność wysłanego dokumentu — kontrakt i plan

**Wdrożone w CAP (bez KSeF, bez generatora XML):**

- `KsefInvoiceSnapshots` — niezmienny (tylko INSERT) rekord: `invoiceUUID`, NIP, kierunek, środowisko, spółka, `schemaVersion` (`FA(3)`), `sapHeaderVersion` (100 ns), `itemsFingerprint`, `xmlSha256` (hex SHA-256 dokładnych bajtów XML), `xmlByteLength`. Unikalność `(invoiceUUID, environment, taxpayerNip, xmlSha256)` czyni rejestrację idempotentną.
- `KsefOperations.snapshot` i `confirmedXmlSha256`: operacja musi wskazywać migawkę, a skrót potwierdzony przy akceptacji musi być identyczny ze skrótem migawki — inaczej `enqueue` odmawia (`CONFIRMED_DOCUMENT_HASH_MISMATCH`). Spółka i oczekiwana wersja dla SAP pochodzą **wyłącznie** z migawki, nie z argumentów wywołującego.
- `KsefInvoiceSnapshotRegistry.detectDrift` rozróżnia zmianę nagłówka, pozycji lub obu względem chwili przygotowania XML.

**Ważny wniosek dla SAP (NOT VERIFIED w runtime).** BDEF root ma `etag master LocalLastChangedAt`, a pozycje i referencje mają **własny** `etag master`. Zmiana pozycji nie musi więc zmieniać `LocalLastChangedAt` nagłówka, więc CAS `ExpectedLocalLastChangedAt` chroni treść nagłówka, ale nie dowodzi niezmienności pozycji. Rekomendacja do osobnej zmiany ABAP (wymaga aktywacji, ATC, EML): `total etag` na polu zmienianym przy każdej zmianie kompozycji i użycie go jako wersji CAS, albo świadome pozostanie przy kontroli drift po stronie CAP tuż przed wysyłką. Obecnie CAP wykrywa drift pozycji tylko wtedy, gdy przyszły producent wywoła `detectDrift` na świeżym odczycie przed wysyłką — **nie jest to jeszcze podłączone**.

**Plan integracji z KSeF SDK (nie wykonany):** generator FA(3) zwraca bajty → `register(...)` w tej samej transakcji, co zapis zadania wysyłki → wysyłka dokładnie tych bajtów → przy statusie końcowym zapis `confirmedXmlSha256` z odpowiedzi KSeF → `enqueue`. Format skrótu w odpowiedzi KSeF (Base64 vs hex) trzeba zweryfikować w SDK/OpenAPI i znormalizować przed porównaniem — **NOT VERIFIED**.

**Unikalność numeru KSeF.** Prosty UNIQUE na `KsefNumber` w całej aplikacji byłby błędny: ten sam numer legalnie występuje jako dokument sprzedawcy (OUTBOUND) i nabywcy (INBOUND), także w jednym CAP/SAP przy rozliczeniach wewnątrzgrupowych; numery korekt są odrębne, ale odwołują się do pierwotnego; różne systemy SAP mogą odwoływać się do tego samego numeru. Wdrożono jedynie unikalność `(environment, ksefNumber)` w `SapResultWrites`, bo ta tabela dotyczy wyłącznie zapisu wyniku w fakturze sprzedażowej SAP — numer KSeF może trafić do jednej takiej faktury na środowisko. Dla INBOUND i korekt unikalność należy zaprojektować osobno (np. `(environment, taxpayerNip, ksefNumber, sapSystem)`).
