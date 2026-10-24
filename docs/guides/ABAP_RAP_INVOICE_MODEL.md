---
title: "ABAP RAP — model faktur AInvoicer"
date: 2026-10-24
type: guide
status: aktywny
tags: [ainvoicer, abap, rap, cds, odata]
---

# ABAP RAP — model faktur AInvoicer

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md) · [Workflow abapGit](ABAPGIT_WORKFLOW.md)

## Kontekst

ABAP Environment jest wykorzystywany jako środowisko symulacyjne. Nie zakładaj tabel standardowych S/4HANA takich jak `ACDOCA`, `BKPF`, `BSEG`, `RBKP` czy `VBRK` ani rzeczywistych dokumentów FI/MM/SD. Poprzedni model `ZFI_ACDOCA` został porzucony jako docelowe źródło danych dla integracji CAP. Aktualny eksport ma własne tabele aplikacyjne.

Opis bazowy wynika z plików `abap/src/` w commicie `39ab869` (`abapGit init`, 2026-10-24). Uzupełnienie kompozycji z 2026-10-24 jest lokalnym patchem źródeł. Żaden z tych stanów nie jest odczytem ani potwierdzeniem aktywności obiektów SAP.

## Tabele aplikacyjne

| Tabela | Odpowiedzialność i pola widoczne w eksporcie |
| --- | --- |
| `ZAINV_INV_H` | Nagłówek faktury: UUID, numer, `OriginProcess`, `Direction`, `InvoiceKind`, `InvoiceStatus`, kod spółki, daty, NIP/nazwy stron, sumy netto/podatku/brutto i waluta; zawiera też lokalne pola audytowe RAP. |
| `ZAINV_INV_I` | Pozycje: UUID pozycji i UUID faktury, numer pozycji, materiał/opis, ilość/jednostka, cena jednostkowa, kwoty netto/podatku/brutto, stawka/kategoria VAT, waluta i pola audytowe RAP. |
| `ZAINV_INV_REF` | Powiązanie do źródła symulacji: UUID referencji i faktury, system/moduł źródłowy, rola referencji, numer dokumentu źródłowego, rok obrachunkowy, numer pozycji i pola audytowe RAP. |

Pliki tabel wskazują `SYSUUID_X16` jako typ kluczy UUID. Klucz nagłówka to `INVOICE_UUID`; klucz pozycji to `ITEM_UUID`; klucz referencji to `REF_UUID`. W tabelach pozycji i referencji `INVOICE_UUID` jest polem wymaganym, co wskazuje na logiczne powiązanie do nagłówka. Sam eksport tabeli nie dowodzi istnienia ani egzekwowania fizycznego klucza obcego.

## Warstwy RAP w eksporcie

1. **Persistence:** root CDS `ZR_AINV_INV_H` czyta z tabeli `ZAINV_INV_H`.
2. **Root entity:** `ZR_AINV_INV_H` udostępnia pola nagłówka, UUID jako klucz, semantykę kwot/waluty i audytu oraz kompozycje `_Items` i `_References`.
3. **Child interface CDS:** `ZR_AINV_INV_I` i `ZR_AINV_INV_REF` czytają odpowiednio z tabel pozycji i referencji. Każdy ma `association to parent ZR_AINV_INV_H` po `InvoiceUUID`; ilość ma jednostkę `UnitCode`, a kwoty pozycji odnoszą się do `CurrencyCode`.
4. **Projection:** `ZC_AINV_INV_H` zachowuje `_BaseEntity` i przekierowuje `_Items` oraz `_References` do projekcji dzieci jako `composition child`. Projekcje dzieci przekierowują `_Parent` do `ZC_AINV_INV_H` jako `parent`.
5. **Behavior:** root pozostaje managed, z `InvoiceUUID` managed numbering, lock master i ETag `LocalLastChangedAt`. Root umożliwia create-by-association dla obu dzieci. Dzieci mapują tabele `ZAINV_INV_I`/`ZAINV_INV_REF`, używają managed UUID `ItemUUID`/`RefUUID`, lock/authorization/ETag dependent by `_Parent` i nie deklarują standardowego `create`. Dzieci mogą być aktualizowane i usuwane.
6. **DCL:** lokalny patch dodaje role dla obu encji interface i projekcji, dziedziczące warunki od rodzica przez `_Parent`. Nie zapewnia to izolacji, ponieważ istniejąca rola root `ZR_AINV_INV_H` ma `where TRUE`; także metoda global authorization w `ZBP_R_AINV_INV_H` jest pusta. Zewnętrzne użycie wymaga skutecznej polityki i testów autoryzacji.
7. **Usługa:** istniejące `ZAPI_AINV_INV_H_O4` pozostaje bez zmian jako binding. Lokalny patch rozszerza istniejący service definition o projekcje Header, Item i Reference; dokładne nawigacje, entity sets i kontrakt OData potwierdza dopiero aktywacja oraz `$metadata` live.

## Zakres i ograniczenia potwierdzone w repo

- Commit `39ab869` zawierał wyłącznie RAP nagłówka. Lokalny patch 2026-10-24 dodaje źródła CDS, BDEF, DCL i service definition dla kompozycji.
- Patch nie zawiera serializowanych sidecarów metadata dla nowych obiektów CDS/DCL i nie został zapisany przez ADT/MCP ani zaimportowany/aktywowany przez abapGit. W ADT należy utworzyć lub porównać te obiekty, uruchomić aktywację i pozwolić abapGit wyeksportować pełne metadata.
- Nie potwierdzono, że skorygowane nazwy i definicje pokrywają się z aktualnym stanem inactive/active w H01. Nie wolno nakładać patcha na nowsze źródła bez porównania w ADT.
- Nie twórz nazw entity setów, URI ani kontraktu OData na podstawie nazw obiektów źródłowych. Wymagają odczytu aktywnego bindingu i `$metadata` w docelowym SAP.
- Metadane serializowanego bindingu zawierają `PUBLISHED=true`, ale wpis usługi wskazuje `RELEASE_STATE=NOT_RELEASED`. To rozbieżność eksportu, nie dowód publikacji lub dostępności runtime. Sprawdź rzeczywisty status w ADT.
- Eksport ani lokalny patch nie potwierdzają autoryzowanego odczytu danych ani stanu aktywacji. DCL root `where TRUE` i pusta metoda global authorization nie są dowodem izolacji tenantów/użytkowników.
- `ZCL_KSEF_HTTP` jest eksperymentalną pozostałością starszego podejścia ABAP → KSeF; to legacy, poza architekturą docelową. Nie traktuj jej jako produkcyjnego klienta KSeF ani nie usuwaj bez osobnego zadania.

## Scenariusze symulacyjne

Model ma wspierać wartości procesu źródłowego `FI_AP`, `FI_AR`, `MM_IV` i `SD_BILL`. To kody scenariuszy, a nie dowód istnienia podłączonych modułów lub rzeczywistych dokumentów FI/MM/SD. Szczegóły słowników dozwolonych wartości i walidacji należy potwierdzić w kodzie aktywnym; sama definicja tabel nie ustanawia wszystkich reguł biznesowych.

## Granica ABAP i CAP Java

ABAP przechowuje symulacyjne dane nagłówków, pozycji i referencji oraz udostępnia je przez API RAP, gdy odpowiednie encje zostaną zaimplementowane i opublikowane. CAP Java ma konsumować zweryfikowany, autoryzowany Remote OData V4 przez CAP Java Remote Services/Destination. CAP pozostaje właścicielem operacji KSeF, statusów procesu, idempotencji, korelacji, audytu i integracji z oficjalnym Java SDK MF.

Nie używaj bezpośredniego JDBC/SQL do bazy ABAP i nie zakładaj, że encje zdalne łączą się lokalnymi SQL JOIN-ami z CAP CDS.

## Status patcha i wymagane weryfikacje

Patch jest propozycją do ręcznego zastosowania/review w Eclipse ADT. Nie jest gotowym, zweryfikowanym obiektem RAP. W kolejności zależności należy aktywować child interface CDS, root CDS, child projection CDS, root projection CDS, BDEF, DCL, service definition, a następnie sprawdzić istniejący binding. Jeśli wersja ABAP Cloud odrzuci składnię lub kontrakt ETag, poprawić ją na podstawie diagnostyki ADT przed publikacją.

Po aktywacji wykonaj syntetyczne testy EML: utworzenie root z 2 pozycjami i 1 referencją przez create-by-association, READ BY ASSOCIATION, update/delete dzieci oraz odrzucenie próby zmiany `InvoiceUUID` lub podpięcia do nieistniejącego rodzica. Wymuś test nieautoryzowanego dostępu; obecna polityka `where TRUE` nie spełnia tego kryterium dla produkcji.

W ADT sprawdź `$metadata` i `$expand` istniejącego bindingu OData V4; nie zakładaj URI ani nazw entity sets z lokalnych nazw CDS. Ustal też aktywny status, publikację/release oraz sposób uwierzytelniania.

## Wymagane weryfikacje przed integracją CAP

1. W SAP ADT sprawdź aktywne wersje tabel, CDS, behavior, DCL, service definition i binding.
2. Ustal rzeczywisty status publikacji/release, adres usługi i wymagany sposób uwierzytelniania.
3. Pobierz `$metadata`; potwierdź entity sets, klucze, typy, nullable, pola, nawigacje i operacje.
4. Porównaj lokalny patch dzieci z aktywnym i nieaktywnym stanem H01; nie nakładaj go na zmiany nowsze od eksportu `39ab869`.
5. Wykonaj autoryzowany odczyt syntetycznych danych, a dopiero potem skonfiguruj CAP Remote Service. Wyniki inspekcji repo, mocków i testu live zapisuj osobno.

## Powiązane źródła

- [Eksport abapGit i workflow](ABAPGIT_WORKFLOW.md)
- [ROADMAP — Phase 3](../ROADMAP.md#phase-3--sap-remote-integration)
- [Dziennik pierwszego eksportu](../journal/2026-10-24-abapgit-rap-initial-export.md)
