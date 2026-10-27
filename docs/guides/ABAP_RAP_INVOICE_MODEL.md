---
title: "ABAP RAP — model faktur AInvoicer"
date: 2026-10-25
type: guide
status: aktywny
tags: [ainvoicer, abap, rap, cds, odata]
---

# ABAP RAP — model faktur AInvoicer

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Workflow abapGit](ABAPGIT_WORKFLOW.md)

## Kontekst

ABAP Environment jest wykorzystywany jako środowisko symulacyjne. Nie zakładaj tabel standardowych S/4HANA takich jak `ACDOCA`, `BKPF`, `BSEG`, `RBKP` czy `VBRK` ani rzeczywistych dokumentów FI/MM/SD. Poprzedni model `ZFI_ACDOCA` został porzucony jako docelowe źródło danych dla integracji CAP. Aktualny eksport ma własne tabele aplikacyjne.

Opis bazowy wynika z plików `abap/src/` w commicie `39ab869` (`abapGit init`, 2026-10-24), a patch kompozycji z `d3dedf4`. **2026-10-25 potwierdzono live aktywację 6 CDS, obu BDEF oraz SRVD w H01 przez MCP.** Pełny wynik runtime pozostaje niepotwierdzony: testy EML przerwał brak aktywnego DCL, a DCL użytkownik odłożył. [Dowody sesji i porównanie](../journal/2026-10-24-abap-rap-composition.md#kontynuacja-live-przez-poprawione-mcp--2026-10-25).

## Tabele aplikacyjne

| Tabela | Odpowiedzialność i pola widoczne w eksporcie |
| --- | --- |
| `ZAINV_INV_H` | Nagłówek faktury: UUID, numer, `OriginProcess`, `Direction`, `InvoiceKind`, `InvoiceStatus`, kod spółki, daty, NIP/nazwy stron, sumy netto/podatku/brutto i waluta; zawiera też lokalne pola audytowe RAP. |
| `ZAINV_INV_I` | Pozycje: UUID pozycji i UUID faktury, numer pozycji, materiał/opis, ilość/jednostka, cena jednostkowa, kwoty netto/podatku/brutto, stawka/kategoria VAT, waluta i pola audytowe RAP. |
| `ZAINV_INV_REF` | Powiązanie do źródła symulacji: UUID referencji i faktury, system/moduł źródłowy, rola referencji, numer dokumentu źródłowego, rok obrachunkowy, numer pozycji i pola audytowe RAP. |

Pliki tabel wskazują `SYSUUID_X16` jako typ kluczy UUID. Klucz nagłówka to `INVOICE_UUID`; klucz pozycji to `ITEM_UUID`; klucz referencji to `REF_UUID`. W tabelach pozycji i referencji `INVOICE_UUID` jest polem wymaganym, co wskazuje na logiczne powiązanie do nagłówka. Sam eksport tabeli nie dowodzi istnienia ani egzekwowania fizycznego klucza obcego.

## Warstwy RAP — stan live H01

1. **Persistence:** root CDS `ZR_AINV_INV_H` czyta z tabeli `ZAINV_INV_H`.
2. **Root entity:** `ZR_AINV_INV_H` udostępnia pola nagłówka, UUID jako klucz, semantykę kwot/waluty i audytu oraz kompozycje `_Items` i `_References`.
3. **Child interface CDS:** `ZR_AINV_INV_I` i `ZR_AINV_INV_REF` czytają odpowiednio z tabel pozycji i referencji. Każdy ma `association to parent ZR_AINV_INV_H` po `InvoiceUUID`; ilość ma jednostkę `UnitCode`, a kwoty pozycji odnoszą się do `CurrencyCode`.
4. **Projection:** `ZC_AINV_INV_H` zachowuje `_BaseEntity` i przekierowuje `_Items` oraz `_References` do projekcji dzieci jako `composition child`. Projekcje dzieci przekierowują `_Parent` do `ZC_AINV_INV_H` jako `parent`.
5. **Behavior:** root pozostaje managed, z `InvoiceUUID` managed numbering, lock master i ETag `LocalLastChangedAt`. Root umożliwia create-by-association dla obu dzieci. Dzieci mapują tabele `ZAINV_INV_I`/`ZAINV_INV_REF`, używają managed UUID `ItemUUID`/`RefUUID`, lock/authorization dependent by `_Parent` i nie deklarują standardowego `create`. Każde dziecko ma własny `etag master LocalLastChangedAt`: H01 odrzucił ETag dependent przy identycznej nazwie pola audytowego jak w root. Dzieci mogą być aktualizowane i usuwane. Nie dodano draft.
6. **DCL:** lokalny patch dodaje role dla obu encji interface i projekcji, dziedziczące warunki od rodzica przez `_Parent`. Nie zapewnia to izolacji, ponieważ istniejąca rola root `ZR_AINV_INV_H` ma `where TRUE`; także metoda global authorization w `ZBP_R_AINV_INV_H` jest pusta. Zewnętrzne użycie wymaga skutecznej polityki i testów autoryzacji.
7. **Usługa:** zachowano binding `ZAPI_AINV_INV_H_O4`; runtime MCP read-only 2026-10-27 potwierdził aktywną publikację, wersję `0001`, protokół OData V4, `$metadata` HTTP 200, entity sets trzech projekcji i nawigacje `_Items` / `_References`. Odczyt autoryzowany z `$expand` potwierdził 4 nagłówki, 9 pozycji, 6 referencji i UUID zgodne z seedem. Dostęp działał przez istniejący same-origin OAuth kontekst ABAP FS; nie ustalono hosta ani konfiguracji BTP Destination/Communication Arrangement CAP.

Projekcje dzieci dziedziczą provider contract od root; jawne `provider contract TRANSACTIONAL_QUERY` w child z `redirected to parent` jest niedozwolone. Widoki pozycji zachowują `Semantics.quantity.unitOfMeasure: 'UnitCode'`, ale usunięto niedozwolone w view entities `Semantics.unitOfMeasure: true`. Reguła dziedziczenia kontraktu: [SAP ABAP Keyword Documentation](https://help.sap.com/docs/abap-cloud/abap-keywords/cds-ddl-provider-contract).

## Zakres i ograniczenia potwierdzone w repo

- Commit `39ab869` zawierał wyłącznie RAP nagłówka. Lokalny patch 2026-10-24 dodaje źródła CDS, BDEF, DCL i service definition dla kompozycji.
- Patch nie zawiera serializowanych sidecarów metadata nowych CDS/DCL. Zmiany live wykonano przez MCP, bez eksportu abapGit, commita i push. `abap/src/` nadal zawiera starsze wersje czterech źródeł: `ZR_AINV_INV_I` DDLS, `ZC_AINV_INV_I` DDLS, `ZC_AINV_INV_REF` DDLS i `ZR_AINV_INV_H` BDEF; klasa testowa istnieje wyłącznie w SAP. Po walidacji potrzebny kontrolowany eksport, po osobnym zleceniu.
- Ponowny odczyt active potwierdził osiem źródeł CDS/BDEF; cztery pozostałe odpowiadają lokalnym źródłom po pominięciu whitespace i wielkości liter. Nie importować lokalnego patcha na nowszy stan SAP.
- Nie twórz nazw entity setów, URI ani kontraktu OData na podstawie nazw obiektów źródłowych. Wymagają odczytu aktywnego bindingu i `$metadata` w docelowym SAP.
- Metadane serializowanego bindingu zawierają `PUBLISHED=true`, ale wpis usługi wskazuje `RELEASE_STATE=NOT_RELEASED`. To rozbieżność eksportu, nie dowód publikacji lub dostępności runtime. Sprawdź rzeczywisty status w ADT.
- Eksport ani lokalny patch nie potwierdzają autoryzowanego odczytu danych ani stanu aktywacji. DCL root `where TRUE` i pusta metoda global authorization nie są dowodem izolacji tenantów/użytkowników.
- `ZCL_KSEF_HTTP` jest eksperymentalną pozostałością starszego podejścia ABAP → KSeF; to legacy, poza architekturą docelową. Nie traktuj jej jako produkcyjnego klienta KSeF ani nie usuwaj bez osobnego zadania.

## Scenariusze symulacyjne

Model ma wspierać wartości procesu źródłowego `FI_AP`, `FI_AR`, `MM_IV` i `SD_BILL`. To kody scenariuszy, a nie dowód istnienia podłączonych modułów lub rzeczywistych dokumentów FI/MM/SD. Szczegóły słowników dozwolonych wartości i walidacji należy potwierdzić w kodzie aktywnym; sama definicja tabel nie ustanawia wszystkich reguł biznesowych.

## Granica ABAP i CAP Java

ABAP przechowuje symulacyjne dane nagłówków, pozycji i referencji oraz udostępnia je przez API RAP, gdy odpowiednie encje zostaną zaimplementowane i opublikowane. CAP Java ma konsumować zweryfikowany, autoryzowany Remote OData V4 przez CAP Java Remote Services/Destination. CAP pozostaje właścicielem operacji KSeF, statusów procesu, idempotencji, korelacji, audytu i integracji z oficjalnym Java SDK MF.

Nie używaj bezpośredniego JDBC/SQL do bazy ABAP i nie zakładaj, że encje zdalne łączą się lokalnymi SQL JOIN-ami z CAP CDS.

## Status aktywacji i wymagane weryfikacje

CDS/BDEF/SRVD są aktywne w H01. Utworzono i aktywowano `ZCL_AINV_RAP_COMP_TEST` z czterema testami: deep create/RBA, update/delete dzieci, odrzucenie CBA dla nieistniejącego rodzica i kasowanie kompozycji z root. Testy używają EML konsumenta bez `IN LOCAL MODE`, operują w buforze RAP i wykonują `ROLLBACK ENTITIES`, bez trwałego zapisu danych. Ich uruchomienie przerwał dump `ACM_UNEXPECTED_VALUE`: `ZR_AINV_INV_I`, `Access Control Existence: FALSE` przy `#MANDATORY`. Brak zaliczenia testów; błąd wrappera `toFixed` nie stanowi wyniku ABAP Unit.

DCL pozostawiono do późniejszego uzupełnienia zgodnie z decyzją użytkownika. Istnieją nieaktywne `ZR_AINV_INV_H`, `ZC_AINV_INV_H`, `ZR_AINV_INV_I`; brakuje `ZR_AINV_INV_REF`, `ZC_AINV_INV_I`, `ZC_AINV_INV_REF`. Próba utworzenia pierwszej brakującej roli zwróciła `Unsupported Media Type`; brak częściowego obiektu potwierdzono odczytem i listą inactive. Nie osłabiono `#MANDATORY` ani nie aktywowano zastanych DCL. ATC głównego BDEF w wariancie `ABAP_CLOUD_DEVELOPMENT_DEFAULT`: 0 ustaleń; nie jest to pełne ATC pakietu ani test bezpieczeństwa.

Po aktywacji wykonaj syntetyczne testy EML: utworzenie root z 2 pozycjami i 1 referencją przez create-by-association, READ BY ASSOCIATION, update/delete dzieci oraz odrzucenie próby zmiany `InvoiceUUID` lub podpięcia do nieistniejącego rodzica. Wymuś test nieautoryzowanego dostępu; obecna polityka `where TRUE` nie spełnia tego kryterium dla produkcji.

W ADT sprawdź `$metadata` i `$expand` istniejącego bindingu OData V4; nie zakładaj URI ani nazw entity sets z lokalnych nazw CDS. Ustal też aktywny status, publikację/release oraz sposób uwierzytelniania.

## Wymagane weryfikacje przed integracją CAP

1. W SAP ADT sprawdź aktywne wersje tabel, CDS, behavior, DCL, service definition i binding.
2. Ustal rzeczywisty status publikacji/release, adres usługi i wymagany sposób uwierzytelniania.
3. Pobierz `$metadata`; potwierdź entity sets, klucze, typy, nullable, pola, nawigacje i operacje. Runtime `$metadata` oraz nawigacje zostały potwierdzone przez MCP 2026-10-27; surowy EDMX nie został udostępniony.
4. Porównaj lokalny patch dzieci z aktywnym i nieaktywnym stanem H01; nie nakładaj go na zmiany nowsze od eksportu `39ab869`.
5. Autoryzowany read live 4/9/6 przez MCP wykonano 2026-10-27. Pozostaje osobny test odczytu przez CAP Remote Service po skonfigurowaniu BTP Destination/Communication Arrangement; mock CAP nie zastępuje tego testu.

## Powiązane źródła

- [Eksport abapGit i workflow](ABAPGIT_WORKFLOW.md)
- [ROADMAP — Phase 3](../ROADMAP.md#phase-3--sap-remote-integration)
- [Dziennik pierwszego eksportu](../journal/2026-10-24-abapgit-rap-initial-export.md)
