---
title: "Generowanie i walidacja XML FA(3)"
date: 2026-10-29
type: guide
status: aktywny
tags: [ainvoicer, ksef, fa3, xsd, cap-java]
---

# Generowanie i walidacja XML FA(3)

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Dziennik Agent B — FA(3)](../journal/2026-10-29-agent-b-fa3.md) · [Przewodnik: integralność XML](SAP_WRITE_ACCESS_AND_READ_POLICY.md)

Komponent `customer.cap_java.ksef.fa3` w `cap-java/srv` przygotowuje **podstawową krajową fakturę sprzedażową VAT** w strukturze FA(3), waliduje ją oficjalnym XSD MF i zwraca dokładne bajty UTF-8 z SHA-256. Nie wysyła niczego do KSeF, nie zapisuje do bazy i nie tworzy potwierdzeń. Uwierzytelnienie, sesja, szyfrowanie i wysyłka należą do integracji SDK KSeF (Agent A).

## 1. Schemat — źródło i wersja

| Element | Wartość |
| --- | --- |
| Wzór | FA(3), `kodSystemowy="FA (3)"`, `wersjaSchemy="1-0E"`, `WariantFormularza=3` |
| Namespace | `http://crd.gov.pl/wzor/2025/06/25/13775/` |
| Źródło główne | `https://crd.gov.pl/wzor/2025/06/25/13775/schemat.xsd` (pobrane 2026-10-29) |
| Drugie źródło | `CIRFMF/ksef-api`, `faktury/schemy/FA/schemat_FA(3)_v1-0E.xsd` — **bajtowo identyczny** |
| Importy | `StrukturyDanych_v10-0E.xsd` → `ElementarneTypyDanych_v10-0E.xsd` → `KodyKrajow_v10-0E.xsd` (CRD, namespace `…/2022/01/05/eD/DefinicjeTypy/`) |

Pliki leżą bez zmian w `cap-java/srv/src/main/resources/ksef/fa3/xsd/`. Kopie bazowe w `CIRFMF/ksef-api` różnią się tylko białymi znakami i względnym `schemaLocation`; po normalizacji są identyczne. Użyto oryginałów CRD.

| Plik | SHA-256 |
| --- | --- |
| `schemat_FA3_v1-0E.xsd` | `b646b6b525f51adf1bb2545f111fc8ca6e7aa6dd2f98948f1667d3695c06d958` |
| `StrukturyDanych_v10-0E.xsd` | `1137ce6e3c11c2b9ef3f05e4e72d6dcd6b4fa94908ea558f2ba15de0259bb2aa` |
| `ElementarneTypyDanych_v10-0E.xsd` | `8a531cb181d3e298d11b28766655ae91fee2d7851440095932ffc82137ed2be1` |
| `KodyKrajow_v10-0E.xsd` | `1d41a1b3184188f2d20a51d3afde26204dda182ec5dacf018204dcc9870dc644` |

`Fa3SchemaResourcesTest` przypina te skróty. Lokalny `.gitattributes` (`*.xsd -text`) chroni bajty przed konwersją końców linii przez `core.autocrlf`. FA(2) (namespace `…/2023/06/29/12648/`) nie jest obsługiwany i jest odrzucany przez walidator.

## 2. Minimalna faktura VAT — wymagania XSD

Kolejność elementów musi odpowiadać sekwencji XSD. Obowiązkowe dla scenariusza:

- `Naglowek`: `KodFormularza` (atrybuty stałe), `WariantFormularza=3`, `DataWytworzeniaFa` (`xsd:dateTime`, zakres 2025-09-01T00:00:00Z – 2050-01-01T23:59:59Z); `SystemInfo` opcjonalne.
- `Podmiot1`: `DaneIdentyfikacyjne/NIP` (wzorzec `[1-9]((\d[1-9])|([1-9]\d))\d{7}`), `Nazwa`; **`Adres` obowiązkowy** (`KodKraju`, `AdresL1`, opcjonalnie `AdresL2`).
- `Podmiot2`: `DaneIdentyfikacyjne` (wariant `NIP`), `Nazwa` opcjonalna w XSD; `Adres` opcjonalny; **`JST` i `GV` obowiązkowe** (1 = tak, 2 = nie).
- `Fa`: `KodWaluty`, `P_1` (data wystawienia), `P_2` (numer), pary `P_13_x`/`P_14_x` jako opcjonalne sekwencje (x = 1: 23/22%, 2: 8/7%, 3: 5%), `P_15`, `Adnotacje` (`P_16`, `P_17`, `P_18`, `P_18A`, `Zwolnienie`, `NoweSrodkiTransportu`, `P_23`, `PMarzy`), `RodzajFaktury`, `FaWiersz` (0..10000). `P_6` opcjonalne.
- Typy: kwoty `TKwotowy` (maks. 2 miejsca, bez wykładnika), ilość `TIlosci` (maks. 6), cena `TKwotowy2` (maks. 8), stawka `P_12` ze słownika (`23`, `8`, `5`, `zw`, `0 KR` …), daty `YYYY-MM-DD`, teksty `xsd:token` (np. `TZnakowy` 1–256, `TZnakowy512`).

## 3. Klasy i publiczne API

| Klasa | Rola |
| --- | --- |
| `Fa3Invoice` | Model wejściowy (rekordy: `Seller`, `Buyer`, `Address`, `Line`, `VatRateTotal`, enum `VatRate`). Bez wiedzy o XML. |
| `Fa3DomainValidator` | Reguły, których XSD nie sprawdza (sekcja 5). |
| `Fa3XmlWriter` | Deterministyczna serializacja StAX (JDK) do FA(3). |
| `Fa3SchemaValidator` | Walidacja oficjalnym XSD, offline, z ochroną XXE. |
| `Fa3InvoiceXmlService` | `@Component`, fasada: `prepare(Fa3Invoice)`, `verify(byte[])`. |
| `Fa3Document` | Niemutowalny wynik: `xml()` (kopia bajtów), `sha256Hex()`, `byteLength()`, `schemaVersion()` = `"FA(3)"`. |
| `Fa3ValidationException`, `Fa3Violation` | Lista naruszeń z kodem, ścieżką i opisem. |
| `SapInvoiceFa3Mapper` | Opcjonalny adapter B5: `SapInvoiceSnapshot` + `PartyData` → `Fa3Invoice`. |

Nie dodano zależności Maven ani zmian POM — tylko JDK (`javax.xml.stream`, `javax.xml.validation`).

## 4. Mapping `Fa3Invoice` → XML

| Model | XML FA(3) | Uwagi |
| --- | --- | --- |
| `createdAt` | `Naglowek/DataWytworzeniaFa` | `ISO_INSTANT`; podawany jawnie (determinizm) |
| `systemInfo` | `Naglowek/SystemInfo` | opcjonalne |
| `seller.nip/name/address` | `Podmiot1/DaneIdentyfikacyjne`, `Podmiot1/Adres` | adres wymagany |
| `buyer.nip/name/address` | `Podmiot2/...` | adres opcjonalny |
| `buyer.jstSubordinateUnit`, `buyer.vatGroupMember` | `Podmiot2/JST`, `Podmiot2/GV` | `true`→1, `false`→2, `null` = błąd |
| `currency` | `Fa/KodWaluty` | tylko `PLN` |
| `issueDate`, `issuePlace`, `invoiceNumber`, `saleDate` | `P_1`, `P_1M`, `P_2`, `P_6` | `P_6` tylko gdy różni się od `P_1` |
| `vatTotals[rate]` | `P_13_{group}`, `P_14_{group}` | sortowane po grupie XSD |
| `grossTotal` | `P_15` | |
| (stałe) | `Adnotacje`: 2/2/2/2, `P_19N=1`, `P_22N=1`, `P_23=2`, `P_PMarzyN=1`; `RodzajFaktury=VAT` | zwykła sprzedaż krajowa |
| `lines[i]` | `FaWiersz`: `NrWierszaFa=i+1`, `UU_ID`, `P_7`, `P_8A`, `P_8B`, `P_9A`, `P_11`, `P_12` | |

## 5. Walidacja — co sprawdza XSD, a co domena

`prepare()` wykonuje kolejno: walidację domenową → generowanie → walidację XSD. Błąd domenowy zatrzymuje proces przed wygenerowaniem XML.

**XSD (`Fa3SchemaValidator`) sprawdza:** strukturę, kolejność i obowiązkowość elementów, namespace, atrybuty stałe, słowniki (waluta, stawka, kraj), formaty i zakresy dat, wzorce kwot (separator, liczba miejsc), wzorzec NIP, długości tekstów.

**XSD nie sprawdza** (potwierdzone testem `xsdDoesNotCheckArithmeticOrNipChecksum` — dokumenty z błędną sumą `P_15`, błędnym `P_14_1` i NIP z błędną sumą kontrolną przechodzą XSD):

| Kod (`Fa3DomainValidator`) | Reguła |
| --- | --- |
| `REQUIRED` | brak pola obowiązkowego w modelu |
| `NIP_INVALID` | 10 cyfr + suma kontrolna (wagi 6,5,7,2,3,4,5,6,7, mod 11) |
| `LINE_NET_MISMATCH` | `P_11 = round(P_8B × P_9A, 2, HALF_UP)` |
| `VAT_NET_MISMATCH` | `P_13_x = Σ P_11` wierszy danej stawki |
| `VAT_AMOUNT_MISMATCH` | `P_14_x = round(P_13_x × stawka / 100, 2, HALF_UP)` |
| `VAT_TOTAL_MISSING`, `VAT_TOTAL_DUPLICATE` | podsumowanie dla każdej użytej stawki, bez duplikatów |
| `GROSS_MISMATCH` | `P_15 = Σ (P_13_x + P_14_x)` |
| `AMOUNT_SCALE`, `AMOUNT_NEGATIVE`, `AMOUNT_NOT_POSITIVE` | skala zgodna z typem XSD (bez zaokrąglania), brak ujemnych, ilość > 0 |
| `TEXT_NOT_NORMALIZED`, `TEXT_CONTROL_CHARACTER` | tekst, który `xsd:token` zmieniłby po cichu (spacje wiodące/końcowe/podwójne) lub znaki sterujące |
| `CURRENCY_UNSUPPORTED`, `LINES_EMPTY`, `LINES_TOO_MANY`, `SALE_DATE_EQUALS_ISSUE_DATE` | granice obsługiwanego scenariusza |

Reguła VAT liczy podatek od sumy netto stawki. Faktury liczone od pozycji z innym zaokrągleniem zostaną odrzucone (`VAT_AMOUNT_MISMATCH`) — to świadoma, ostrożna decyzja minimalnego komponentu, nie interpretacja prawna.

**Bezpieczeństwo walidatora:** importy XSD są mapowane wyłącznie na pliki z classpath (inny `schemaLocation` → błąd startu), `ACCESS_EXTERNAL_DTD/SCHEMA = ""`, parser SAX z `disallow-doctype-decl` (DOCTYPE/encje → `XSD_INVALID`), secure processing włączone.

**Odstępstwo od oryginalnego XSD:** Xerces w JDK rozwija grupę `maxOccurs=N` na N kopii w automacie treści. Dla FA(3) (`FaWiersz` 10000, `DaneFaKorygowanej` 50000 i inne) z secure processing kompilacja jest odrzucana (limit 5000), a bez limitu nie kończy się w rozsądnym czasie (zaobserwowane > 10 min CPU w `XSDFACM.buildDFA`). Dlatego **w pamięci, przed kompilacją**, 12 górnych limitów `maxOccurs ≥ 1000` (`WZ`, `NowySrodekTransportu`, `DaneFaKorygowanej`, `DodatkowyOpis`, `FaWiersz`, `NrPartiiTowaru`, `ZamowienieWiersz`, `BlokDanych`, `MetaDane`, `Tabela`, `TMetaDane`, `Wiersz`) zamieniamy na `unbounded`. Plik na dysku pozostaje oryginalny. Lista jest weryfikowana przy starcie i w teście. Skutek: walidator nie egzekwuje tych górnych limitów; limit 10000 wierszy egzekwuje `LINES_TOO_MANY`, a pozostałych elementów generator nie tworzy.

## 6. Bajty XML i SHA-256

- Deklaracja `<?xml version="1.0" encoding="UTF-8"?>`, UTF-8 **bez BOM**, separator linii `LF`, wcięcie 2 spacje, domyślny namespace FA(3) na `Faktura`, brak zbędnych prefiksów.
- Polskie znaki zapisane jako surowe bajty UTF-8 (bez encji `&#…;`); `&` i `<` escapowane przez StAX.
- Liczby: kwoty z dokładnie 2 miejscami (`setScale(2, UNNECESSARY)`), ilość bez zbędnych zer (`3.000` → `3`), cena z co najmniej 2 miejscami (`500` → `500.00`); zawsze `toPlainString()` (bez wykładnika).
- Brak zegara i losowości w generatorze: identyczny `Fa3Invoice` → identyczne bajty → identyczny SHA-256.
- `sha256Hex()` = SHA-256 dokładnych bajtów, hex małymi literami — ten sam format co `KsefInvoiceSnapshotRegistry.sha256Hex`. Dowolna zmiana bajtu (np. dodatkowa spacja) zmienia skrót, choć XML może pozostać poprawny.

Przykład (`Fa3Fixtures.validInvoice()`, dane syntetyczne, 3 wiersze, stawki 23% i 5%): **2463 bajty, SHA-256 `24fc8a1e26805ce08c32aed376fa7ef801d59c80e19af1718c551ab6af339afd`**. Test `goldenSampleHashIsStable` przypina tę wartość i zapisuje próbkę do `cap-java/srv/target/fa3-sample.xml`.

## 7. Kontrakt integracyjny dla wysyłki (B4)

```java
// W workerze (po commit zlecenia), nie w transakcji żądania OData:
Fa3Document doc = fa3InvoiceXmlService.prepare(invoice);           // 1-2: generuj + waliduj (domena + XSD)
byte[] xml = doc.xml();                                              // 3: dokładne bajty
String sha256 = doc.sha256Hex();                                     // 4: hash
UUID snapshotId = snapshotRegistry.register(sapSnapshot, taxpayerNip, // 5: migawka CAP (w transakcji)
        companyCode, "TEST", doc.schemaVersion(), xml);
// 6: SDK KSeF otrzymuje DOKŁADNIE `xml` — bez ponownej serializacji, formatowania i zmiany kodowania.
// Bajty odczytane później z magazynu można ponownie sprawdzić: fa3InvoiceXmlService.verify(bytes).
```

- `register` jest idempotentny dla tego samego dokumentu (ta sama faktura + środowisko + NIP + SHA-256 → to samo ID); inny XML tej samej faktury tworzy nową migawkę. Komponent FA(3) nie dodaje drugiego mechanizmu persystencji ani nie zmienia outboxa.
- Potwierdzenie KSeF (`confirmedXmlSha256`) musi pochodzić z prawdziwej odpowiedzi KSeF; komponent go nie tworzy.
- Pełny XML nie jest przechowywany w DB (zgodnie z AGENTS.md); trwały magazyn XML pozostaje decyzją otwartą.
- Dowód: `Fa3SnapshotContractIntegrationTest` (CAP + H2, stub wysyłki bez sieci).

## 8. Adapter SAP (B5)

`SapInvoiceFa3Mapper.map(SapInvoiceSnapshot, PartyData)` dla faktur `Direction=OUTBOUND`, `InvoiceKind=STANDARD`, `CurrencyCode=PLN`.

| SAP (`ZC_AINV_INV_H/I`) | FA(3) | Uwagi |
| --- | --- | --- |
| `InvoiceNumber`, `IssueDate` | `P_2`, `P_1` | |
| `SellerNip/Name`, `BuyerNip/Name` | `Podmiot1`, `Podmiot2` | |
| pozycje sortowane po `ItemNo` | `FaWiersz` | `ItemUUID` → `UU_ID` |
| `Description`, `Quantity`, `UnitPrice`, `NetAmount` | `P_7`, `P_8B`, `P_9A`, `P_11` | |
| `UnitCode` `EA`/`PC` | `P_8A = "szt."` | inne kody → `MAPPING_UNSUPPORTED` |
| `TaxRate` + `TaxCategory` (`23.00` + `VAT23` itd.) | `P_12` | inna para (np. `ZW`) → `MAPPING_UNSUPPORTED` |
| Σ pozycji `NetAmount`/`TaxAmount` per stawka | `P_13_x`/`P_14_x` | nagłówek `NetAmount`/`TaxAmount` musi się zgadzać (`SAP_HEADER_TOTAL_MISMATCH`) |
| `GrossAmount` | `P_15` | |

**Brakujący mapping (model SAP nie zawiera):** adres sprzedawcy (obowiązkowy w FA(3)), adres nabywcy, flagi `JST`/`GV`, `DataWytworzeniaFa`. Dostarcza je wywołujący w `PartyData`; `null` jest raportowany, nie zastępowany domyślną wartością. Brak też daty dostawy (`P_6`): `PostingDate` to data księgowania, więc nie jest mapowana. `DueDate` (termin płatności) nie jest jeszcze mapowany do `Platnosc`.

**Ustalenie dot. danych H01:** NIP-y seeda (`5260002008`, `5210003008`, `5250001009` z `ZCL_AINV_TEST_DATA`) **nie mają poprawnej sumy kontrolnej**. Faktura SD z seeda mapuje się strukturalnie, ale `prepare()` odrzuca ją z `NIP_INVALID` (test `h01SeedNipsFailChecksumSoSeedNeedsSyntheticFixture`). Do FA(3) używany jest osobny fixture `syntheticSdBill()` — te same kwoty i pozycje, NIP-y `1234567802`/`9876543210`. Nie zmieniano SAP. Czy KSeF TEST odrzuciłby NIP bez poprawnej sumy kontrolnej — NIE WERYFIKOWANO.

## 9. Ograniczenia

- Tylko: faktura VAT (bez korekt, zaliczek, rozliczeń, uproszczonych), PLN, stawki 23/8/5%, nabywca z polskim NIP, bez płatności, rachunków, `Podmiot3`, załączników i stopki; Adnotacje stałe (brak MPP, odwrotnego obciążenia, zwolnień, marży, samofakturowania, metody kasowej).
- Zgodność z XSD nie oznacza przyjęcia przez KSeF ani poprawności prawnej; KSeF TEST nie był wywoływany.
- Górne limity `maxOccurs ≥ 1000` nie są egzekwowane przez walidator (sekcja 5).
- `DataWytworzeniaFa` musi być ≥ 2025-09-01T00:00:00Z (sprawdza XSD, nie domena).

## 10. Uruchamianie testów

Z katalogu `cap-java/` (JDK 25, Maven ≥ 3.9.14; `-Dcds.cds.skipVersionCheck=true` obchodzi znany problem `spawn EPERM` kontroli wersji CDS):

```powershell
# Testy FA(3): generator, XSD, domena, mapper SAP, kontrakt snapshotu (H2)
mvn -B verify -Dcds.cds.skipVersionCheck=true "-Dtest=Fa3*Test,SapInvoiceFa3MapperTest" -Dsurefire.failIfNoSpecifiedTests=false

# Pełna regresja
mvn -B clean verify -Dcds.cds.skipVersionCheck=true
```

Wynik 2026-10-29: testy FA(3) 30/30, pełna regresja 112/112 PASS (szczegóły w [dzienniku](../journal/2026-10-29-agent-b-fa3.md)).
