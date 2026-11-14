---
title: "BLOB-LAB-01 — OData Media, testy lokalne i wdrożenie"
date: 2026-11-13
type: journal
status: zakonczony
tags: [ainvoicer, blob-lab-01, cap-java, hana, hdi, odata, deployment]
---

# BLOB-LAB-01 — przebieg i wyjaśnienie kroków

[Project Home](../README.md) · [STATUS](../STATUS.md) · [Roadmapa](../ROADMAP.md) ·
[Zasady LAB](../guides/BLOB_LAB_01_OWNER_LED_HDI.md)

## Zakres i zgody

Właściciel rozpoczął pracę w trybie mentorskim i sam wykonywał kroki CDS, kompilację oraz pierwsze
żądania HTTP. Następnie zlecił agentowi dokończenie walidacji, lokalne testowanie, a na końcu
wyraźnie: „Wdrażaj i na koniec zdaj mi raport co wykonałeś, opisz krok po kroku tak abym mógł
zrozumieć, z wyjaśnieniami”. To zakresowa zgoda na przygotowanie i wdrożenie tego laboratorium
w istniejącym DEV, stanowiąca odstępstwo od domyślnego owner-led. Nie rozszerza zakresu na KSeF,
SAP WRITE, nowe usługi BTP ani commit/push.

## Punkt wyjścia

Branch `main`, HEAD `4d4dbfc`; model `documents.cds` był zmieniony przez właściciela,
a `blob-service.cds` i `BlobDocumentsHandler.java` były untracked. Istniejąca tabela HANA
pochodzi z poprzedniej sesji właściciela; nie zakładano, że lokalny upload do H2 jest uploadem do HANA.

## Krok 1 — encja i usługa

`LargeBinary` opisuje dane binarne, a `@Core.MediaType: mimeType` wystawia je jako `Edm.Stream`.
OData przechowuje metadane dokumentu i pozwala osobno wysyłać/pobierać `content`.
`Core.ContentDisposition` jest w EDMX rekordem z właściwością `Filename`, dlatego szukanie
dosłownego ciągu `ContentDisposition.Filename` w XML dało fałszywe `False`.

Właściciel zdecydował o wykorzystaniu istniejącej roli `KsefOperator`. Dodatki `BlobOperator`
usunięto z lokalnego deskryptora XSUAA. `createdBy = $user` ogranicza READ/UPDATE do własnego dokumentu.
`createdBy`, `createdAt` i `sha256` są tylko do odczytu dla klienta. DELETE nie został przyznany.

## Krok 2 — logika Java

Handler `@Before CREATE` waliduje nazwę, dozwolone pary rozszerzenie/MIME i brak danych binarnych
w POST metadanych. Handler `@Before UPDATE` ogranicza plik do 1 MiB, wylicza SHA-256 po stronie
serwera i przekazuje CAP nowy strumień tych samych bajtów. Pierwotnego strumienia nie można
ponownie odczytać po jego zużyciu; dlatego odtwarzamy go z ograniczonego bufora.

Przed wdrożeniem dodano: odczyt istniejących metadanych wyłącznie właściciela, odmowę zbiorczego
UPDATE i zmian nazw/MIME, parser poprawności XML z wyłączonym DTD/encjami zewnętrznymi,
limitem głębokości/zdarzeń oraz podstawowe sprawdzenie `%PDF-` / `%%EOF`.
Sprawdzenie sygnatury PDF nie jest pełną walidacją PDF ani skanowaniem antywirusowym.

## Krok 3 — lokalny runtime i test ręczny

Właściciel kompilował `mvn -pl srv -am -DskipTests compile` — BUILD SUCCESS.
Początkowo aplikacja miała pustą H2; sama zmienna `SPRING_SQL_INIT_MODE` nie rozwiązała problemu.
Skuteczne uruchomienie zawierało jawne argumenty `--spring.sql.init.mode=always`,
`--spring.sql.init.platform=h2` i `--spring.sql.init.schema-locations=classpath:schema-h2.sql`.

Profil `test` był aktywny, ale log potwierdził `No mock users found in configuration`.
Użytkownika laboratoryjnego z rolą przekazano jawnie jako argumenty lokalnego procesu.
Nie przenoszono tych danych do konfiguracji CF.

PowerShell 5 nie obsługuje `Invoke-WebRequest -Authentication`; Basic Auth budowano w pamięci.
Nagłówek `Prefer: return=representation` był właściwy dla POST, ale odrzucany przy GET.
`Invoke-WebRequest -OutFile` nie dostarczył oczekiwanego obiektu nagłówków, co spowodowało błędny
raport z pustych/starych zmiennych. Agent przejął testowanie przez .NET HttpClient.

Lokalny dokument `71880b2b-402d-43c5-9029-a9389d332cd4`: POST 201, PUT 204, GET 200,
44 bajty XML, `application/xml`, `attachment; filename="TEST-001.xml"`, właściciel `operator-a`.
Hash wejścia, pobrania i metadanych serwera:
`2c9cfd48b45472fb275ec64f04ae5a98a35516be74d9d002d8eee9366cac921c`.
Niepoprawny MIME i PUT >1 MiB: 400, treść i hash po odmowie niezmienione.
Anonimowe GET metadanych i pliku: 401. Są to wyniki lokalnej H2, nie LIVE HANA.

## Krok 4 — routing i regresja

Dodano `/odata/v4/blob/…` w istniejącym AppRouterze, do Destination `ainvoicer-cap`.
XSUAA sprawdza tożsamość, przekazany token pozwala CAP sprawdzić rolę i właściciela,
a CSRF pozostaje włączony. Nie tworzono nowej roli ani nowego mikroserwisu.

Nowy `BlobServiceODataTest`: 3 testy HTTP H2 — PASS. Obejmują XML, SHA, nagłówki, drugiego
operatora, anonimowy dostęp, złe metadane, pusty/za duży strumień, błędny XML i DTD,
niepoprawne MIME przy PUT, próbę zmiany nazwy, rollback i podstawową sygnaturę PDF.
Pierwsza próba Maven zatrzymała się na kontroli wersji CDS; ponowiono z istniejącą w workflow
wydania flagą `-Dcds.cds.skipVersionCheck=true`, bez zmiany wersji zależności.

## Krok 5 — przygotowanie wydania

Odczyt CF potwierdził EU20, właściwe org/space `dev`, CAP/AppRouter 1/1, istniejący HDI związany
z CAP i deployerem, MTA 0.7.3 i brak aktywnych operacji. Sesja CF wymagała ponownego logowania,
które wykonał właściciel. Przed dalszymi komendami wyłączono diagnostyczne `DEBUG`/`CF_TRACE`.

Zachowano poprzednie lokalne MTAR i evidence w ignorowanym `deploy/build/blob-lab-01-backup/`.
Wariant HANA wymaga wyłącznie istniejących usług. Flagi SEND/INBOUND/SAP WRITE/sweeper pozostają OFF,
mock na CF OFF, SQL init `never`, outbox persistent i in-memory OFF.

## Zmienione pliki

- `cap-java/db/documents.cds` — adnotacje mediów dodane przez właściciela przed wdrożeniem.
- `cap-java/srv/blob-service.cds` — projekcja OData i uprawnienia z istniejącą rolą.
- `cap-java/srv/src/main/java/customer/cap_java/BlobDocumentsHandler.java` — walidacja i hash.
- `cap-java/srv/src/test/java/customer/cap_java/BlobServiceODataTest.java` — regresja HTTP H2.
- `approuter/xs-app.json` — chroniona trasa BlobService.
- `approuter/test/xs-app.test.mjs` — test granic trasy.
- `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/README.md` i niniejszy dziennik — stan i wyjaśnienia.

Podczas wdrożenia nie wykonano commita ani push. Build zawiera świadomie uwzględnione lokalne zmiany i pliki untracked;
evidence zapisuje dirty working tree oraz hash rzeczywistych wejść.

### Publikacja źródeł — 14.11.2026

Właściciel zlecił commit i push. Przed publikacją wykryto nowsze commity na `origin/main`
(ORCH-06, CAP-07F i uzgodnienie katalogu HDI). Źródła LAB integrujemy z tymi zmianami,
zachowując historię wdrożenia opisaną niżej. Publikacja Git nie oznacza ponownego deploymentu:
MTAR wdrożony 13.11 pozostaje dokładnie artefaktem o zapisanym SHA-256.
Po integracji: `BlobServiceODataTest` + `XsSecurityDescriptorTest` — 4 testy PASS, 0 skipped;
`npm run test:config --prefix approuter` — 11/11 PASS. Konflikty dotyczyły wyłącznie
README/STATUS/ROADMAP; zachowano informacje obu prac. Kontrakt G1 wymaga osobnego
uzgodnienia dodatkowego widoku LAB, bez cichej zmiany oczekiwań katalogu.

## Wynik wdrożenia i dalsze kroki

Pełny build `npm run build:btp:smoke --prefix deploy` zakończony PASS:
Maven 479 testów / 0 failures / 0 errors / 6 skipped; executable CF JAR 10/10;
UI5 CF 13/13; AppRouter 11/11. `npm test --prefix deploy`: 51 PASS, 1 skipped (lokalnie brak Go).
`npm run build:btp:hana --prefix deploy` oraz `npm run verify:hana --prefix deploy`: PASS.

Porównanie HDI z poprzednim lokalnym MTAR: dodany wyłącznie `src/gen/BlobService.Documents.hdbview`;
zero usuniętych i zero zmienionych istniejących tabel/widoków/indeksów.
MTAR `deploy/build/ainvoicer_0.7.3.mtar`, SHA-256:
`b30117cf2ef0d212e0c001d4eda765f919632eeac726089d776405ef1cbf947d`.

Polecenie wykonane po zgodzie właściciela:

```powershell
cf deploy .\deploy\build\ainvoicer_0.7.3.mtar -e .\deploy\btp-dev-eu20-hana-first.mtaext --retries 0
```

Operacja `de6d13db-df89-11f1-a97a-eeee0a983f89` zakończyła się **Process finished / exit 0**.
HDI task **#4 SUCCEEDED** (2026-11-13 17:45:47 UTC). Po zakończeniu około 18:54 CET:
CAP i AppRouter **started 1/1**, deployer STOPPED (oczekiwany), brak aktywnych operacji MTA.
Log nowego CAP potwierdza rejestrację BlobService i BlobDocumentsHandler, start puli Hikari
i `Started Application`; brak `Application run failed`. Identity provider korzysta z XSUAA.
Ostrzeżenia o handlerach outbox są oczekiwane przy obu outbox OFF, zgodnie z wcześniejszą diagnozą.

Odczyt dozwolonej listy env po wdrożeniu potwierdził profil `cf`, HANA readiness ON,
identity smoke OFF, KSeF/INBOUND/SAP WRITE/sweeper OFF, storage provider off, SQL init `never`.

**LIVE HTTP — 9 sond PASS:**

- Bezpośredni CAP: anonimowe Blob `$metadata`, lista, content i istniejące Ksef `$metadata` → 401.
- CAP z lokalnym mock loginem → 401 (nie można użyć lokalnego konta testowego na CF).
- AppRouter: anonimowy XHR do Blob `$metadata`, listy, content → 401.
- AppRouter nawigacja przeglądarki → 200 z poprawnie sklasyfikowanym skryptowym przekierowaniem
  do XSUAA, nie z metadanymi. Sam kod 200 nie jest tutaj zaliczonym odczytem OData.

Adres usługi przez istniejący AppRouter:
`https://ainvoicer-v2xzij9s-approuter.cfapps.eu20-001.hana.ondemand.com/odata/v4/blob/`.
Wymaga sesji business usera z istniejącą rolą KsefOperator.

**PENDING:** autoryzowany LIVE POST/PUT/GET do HANA, potwierdzenie SHA-256 i rekordu HDI,
a następnie test pobrania po restarcie CF. Lokalny rekord H2 nie jest rekordem HANA;
wdrożenie i task HDI nie są dowodem trwałości binarnego pliku. Nie pobierano tokenu business usera,
nie generowano service keys i nie wykonywano DML dokumentów w HANA.

Incydent diagnostyczny: pierwsze wywołanie multiapps miało aktywny trace HTTP i wypisało
nagłówek autoryzacji w wyjściu narzędzia. Dalsze komendy uruchamiano z usuniętymi DEBUG/CF_TRACE;
nagłówka nie zapisano w repo ani w raporcie. Surowego wyjścia nie należy udostępniać;
ujawnioną sesję CF należy traktować jako wymagającą unieważnienia.
