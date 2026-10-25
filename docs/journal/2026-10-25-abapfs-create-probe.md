---
title: "Dziennik — próba tworzenia obiektów przez ABAP FS"
date: 2026-10-25
type: journal
status: zakonczony
tags: [ainvoicer, abap, abap-fs, diagnostyka]
---

# Próba tworzenia obiektów przez ABAP FS

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md)

## Cel i zakres

Użytkownik zlecił sprawdzenie tworzenia klasy, tabeli bazodanowej, CDS i data elementu przez MCP ABAP FS w pakiecie `ZAINVOICER_DEV`. Użyto unikalnych nazw `*_FS_PROBE`; obiekty nie zawierają logiki biznesowej. Po każdym wywołaniu sprawdzono obecność obiektu. Aktywacji, edycji, usuwania oraz zmian requestu nie zlecono ani nie wykonano.

## Stan początkowy

- Branch lokalny: `java-cap-ksef`.
- Przed zadaniem istniały lokalne, niezacommitowane zmiany w `docs/STATUS.md`, `docs/ROADMAP.md` i `docs/journal/2026-10-24-abap-rap-composition.md`; zachowano je.
- MCP udostępnił połączenie `H01`. Ustawienia klienta połączenia wskazują `100`, ale `get_sap_system_info` zwrócił `Unknown`, `N/A` i brak numeru klienta; wersji/backendowego klienta nie potwierdzono.
- Wyszukiwanie potwierdziło pakiet `ZAINVOICER_DEV` (opis `AInvoicer package`). Odczyt ADT: `recordChanges=true`, warstwa `ZH02`, software component `ZAINVOICER_SC`, `isAddingObjectsAllowed=false`, przy czym atrybut dopuszcza edycję. Nie zmieniano konfiguracji pakietu.
- Wyszukanie wszystkich czterech nazw przed próbą nie wykazało istniejących obiektów.

## Wykonanie i wyniki

Każde wywołanie `abapfs_create_object` zakończono przed następnym; obiekt sprawdzono wyszukiwaniem po nazwie i typie.

| Typ | Nazwa | Wynik create | Kontrola |
| --- | --- | --- | --- |
| `CLAS/OC` | `ZCL_AINV_FS_PROBE` | Sukces: „ABAP Object Created Successfully”; odpowiedź podała „local — no transport required” | Obiekt znaleziony; źródło zawiera pusty szkielet klasy (14 linii) |
| `TABL/DT` | `ZAINV_FSPROBE` | `CREATION_FAILED`, `Unsupported Media Type` | Brak obiektu |
| `DDLS/DF` | `ZI_AINV_FS_PROBE` | `CREATION_FAILED`, `Unsupported Media Type` | Brak obiektu |
| `DTEL/DE` | `ZDE_AINV_FS_PROBE` | `CREATION_FAILED`, `Unsupported Media Type` | Brak obiektu |

### Ważna rozbieżność transportowa

Read-only `get_object_transport_status` dla utworzonej klasy zwrócił `locked` i request `H01K900018`. Odczyt szczegółów i zawartości requestu potwierdził task `H01K900019` oraz łącznie 6 obiektów: pakiet `ZAINVOICER_DEV` i pięć klas, w tym `ZCL_AINV_FS_PROBE`. To przeczy odpowiedzi create „local — no transport required”. Nie ustalono, czy wrapper pominął automatyczne przypisanie, czy status wskazuje mechanizm inny niż wskazany komunikat. Request nie był tworzony przez tę sesję; nie zmieniano go ani nie zwalniano.

## Walidacja i ograniczenia

- PASS: klasa istnieje i jej ABAP źródło można odczytać.
- FAIL: próby tworzenia tabeli, CDS i data elementu przez MCP w tym systemie zwróciły `Unsupported Media Type`.
- PASS: kontrolne wyszukiwanie nie wykazało częściowych obiektów dla trzech nieudanych prób.
- NIE WYKONANO: edycja, składnia/diagnostyka, aktywacja, ATC, ABAP Unit, test tabeli/CDS ani OData.
- Wynik dotyczy dostępnego połączenia `H01` i pakietu `ZAINVOICER_DEV`; nie dowodzi możliwości wszystkich typów obiektów ani wszystkich systemów ABAP.
- Brak śladu HTTP/ADT uniemożliwia ustalenie, dlaczego trzy typy DDIC/CDS zwracają 415. Rozbieżność requestu wymaga osobnego wyjaśnienia przed dalszym tworzeniem w tym pakiecie.

## Użyte narzędzia

`get_configured_systems`, `get_sap_system_info`, `search_objects`, `get_object_info`, `get_object_by_uri`, `create_object`, `get_object_source`, `get_object_workspace_uri`, `get_object_transport_status`, `manage_transports(get_transport_details/get_transport_objects)`. Polecenia lokalne: odczyt instrukcji/skilla/dokumentacji, `git status --short --branch`, `rg`.

## Następne kroki

1. Wyjaśnić, dlaczego nowa klasa trafiła do `H01K900018` mimo odpowiedzi create o lokalnym zapisie; nie używać ani nie zwalniać requestu bez uzgodnienia.
2. Zebrać oczyszczony ślad nieudanego POST w ABAP FS i porównać z utworzeniem tego samego typu w ADT; nie ponawiać w ciemno.
3. Ustalić, czy pozostawić pustą klasę próbną w `ZAINVOICER_DEV`, czy objąć ją dalszymi pracami po uzgodnieniu transportu.
