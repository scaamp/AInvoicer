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

Opis poniżej wynika z plików `abap/src/` w commicie `39ab869` (`abapGit init`, 2026-10-24). To inspekcja eksportu Git, a nie odczyt aktywnych obiektów SAP.

## Tabele aplikacyjne

| Tabela | Odpowiedzialność i pola widoczne w eksporcie |
| --- | --- |
| `ZAINV_INV_H` | Nagłówek faktury: UUID, numer, `OriginProcess`, `Direction`, `InvoiceKind`, `InvoiceStatus`, kod spółki, daty, NIP/nazwy stron, sumy netto/podatku/brutto i waluta; zawiera też lokalne pola audytowe RAP. |
| `ZAINV_INV_I` | Pozycje: UUID pozycji i UUID faktury, numer pozycji, materiał/opis, ilość/jednostka, cena jednostkowa, kwoty netto/podatku/brutto, stawka/kategoria VAT, waluta i pola audytowe RAP. |
| `ZAINV_INV_REF` | Powiązanie do źródła symulacji: UUID referencji i faktury, system/moduł źródłowy, rola referencji, numer dokumentu źródłowego, rok obrachunkowy, numer pozycji i pola audytowe RAP. |

Pliki tabel wskazują `SYSUUID_X16` jako typ kluczy UUID. Klucz nagłówka to `INVOICE_UUID`; klucz pozycji to `ITEM_UUID`; klucz referencji to `REF_UUID`. W tabelach pozycji i referencji `INVOICE_UUID` jest polem wymaganym, co wskazuje na logiczne powiązanie do nagłówka. Sam eksport tabeli nie dowodzi istnienia ani egzekwowania fizycznego klucza obcego.

## Warstwy RAP w eksporcie

1. **Persistence:** root CDS `ZR_AINV_INV_H` czyta z tabeli `ZAINV_INV_H`.
2. **Root entity:** `ZR_AINV_INV_H` udostępnia pola nagłówka, UUID jako klucz oraz semantykę kwot, waluty i pól audytowych.
3. **Projection:** `ZC_AINV_INV_H` projektuje root i deklaruje `_BaseEntity` do bazowej encji. W eksporcie nie ma asocjacji do item/reference.
4. **Behavior:** managed behavior root w `ZR_AINV_INV_H` mapuje tabelę nagłówka, używa UUID managed numbering, ETag i global authorization; deklaruje create/update/delete. Projekcja używa tych operacji. Klasy implementacji to `ZBP_R_AINV_INV_H` i `ZBP_C_AINV_INV_H`.
5. **DCL:** istnieją role `ZR_AINV_INV_H` i `ZC_AINV_INV_H`; root przyznaje select dla `TRUE`, projekcja dziedziczy warunki root przez `_BaseEntity`. Wymaga to osobnej weryfikacji polityki dostępu w systemie.
6. **Usługa:** `ZAPI_AINV_INV_H_O4` wystawia `ZC_AINV_INV_H` jako OData V4 Web API; wyeksportowany service binding ma nazwę `ZAPI_AINV_INV_H_O4`.

## Zakres i ograniczenia potwierdzone w repo

- W commicie `39ab869` znajdują się definicje trzech tabel, ale root CDS, projection, behavior, DCL i service definition dotyczą wyłącznie nagłówka.
- Nie znaleziono CDS root/projection dla pozycji lub referencji ani behavior dla tych encji. Nie ma zadeklarowanych compositions do `ZAINV_INV_I` lub `ZAINV_INV_REF`, ani potwierdzonej nawigacji OData do tych danych.
- Nie twórz nazw entity setów, URI ani kontraktu OData na podstawie nazw obiektów źródłowych. Wymagają odczytu aktywnego bindingu i `$metadata` w docelowym SAP.
- Metadane serializowanego bindingu zawierają `PUBLISHED=true`, ale wpis usługi wskazuje `RELEASE_STATE=NOT_RELEASED`. To rozbieżność eksportu, nie dowód publikacji lub dostępności runtime. Sprawdź rzeczywisty status w ADT.
- Eksport nie potwierdza autoryzowanego odczytu danych ani stanu aktywacji. DCL root `where TRUE` również nie jest dowodem docelowej izolacji tenantów/użytkowników.
- `ZCL_KSEF_HTTP` jest eksperymentalną pozostałością starszego podejścia ABAP → KSeF; to legacy, poza architekturą docelową. Nie traktuj jej jako produkcyjnego klienta KSeF ani nie usuwaj bez osobnego zadania.

## Scenariusze symulacyjne

Model ma wspierać wartości procesu źródłowego `FI_AP`, `FI_AR`, `MM_IV` i `SD_BILL`. To kody scenariuszy, a nie dowód istnienia podłączonych modułów lub rzeczywistych dokumentów FI/MM/SD. Szczegóły słowników dozwolonych wartości i walidacji należy potwierdzić w kodzie aktywnym; sama definicja tabel nie ustanawia wszystkich reguł biznesowych.

## Granica ABAP i CAP Java

ABAP przechowuje symulacyjne dane nagłówków, pozycji i referencji oraz udostępnia je przez API RAP, gdy odpowiednie encje zostaną zaimplementowane i opublikowane. CAP Java ma konsumować zweryfikowany, autoryzowany Remote OData V4 przez CAP Java Remote Services/Destination. CAP pozostaje właścicielem operacji KSeF, statusów procesu, idempotencji, korelacji, audytu i integracji z oficjalnym Java SDK MF.

Nie używaj bezpośredniego JDBC/SQL do bazy ABAP i nie zakładaj, że encje zdalne łączą się lokalnymi SQL JOIN-ami z CAP CDS.

## Wymagane weryfikacje przed integracją CAP

1. W SAP ADT sprawdź aktywne wersje tabel, CDS, behavior, DCL, service definition i binding.
2. Ustal rzeczywisty status publikacji/release, adres usługi i wymagany sposób uwierzytelniania.
3. Pobierz `$metadata`; potwierdź entity sets, klucze, typy, nullable, pola, nawigacje i operacje.
4. Ustal, czy pozycje i referencje mają być wystawione; jeśli tak, zaprojektuj ich CDS/behavior/composition i autoryzację przed deklarowaniem kontraktu.
5. Wykonaj autoryzowany odczyt syntetycznych danych, a dopiero potem skonfiguruj CAP Remote Service. Wyniki inspekcji repo, mocków i testu live zapisuj osobno.

## Powiązane źródła

- [Eksport abapGit i workflow](ABAPGIT_WORKFLOW.md)
- [ROADMAP — Phase 3](../ROADMAP.md#phase-3--sap-remote-integration)
- [Dziennik pierwszego eksportu](../journal/2026-10-24-abapgit-rap-initial-export.md)
