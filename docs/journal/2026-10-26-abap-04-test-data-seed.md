---
title: "Dziennik — ABAP-04 seed danych testowych przez EML"
date: 2026-10-26
type: journal
status: w-trakcie
tags: [ainvoicer, abap, rap, eml, test-data]
---

# ABAP-04 — Seed danych testowych przez EML

[Project Home](../README.md) · [STATUS](../STATUS.md) · [ROADMAP](../ROADMAP.md)

## Cel i granice

Przygotować w SAP H01 klasę `ZCL_AINV_TEST_DATA` implementującą `IF_OO_ADT_CLASSRUN`, aby przez EML utworzyć syntetyczne faktury FI_AP, FI_AR, MM_IV i SD_BILL. Użytkownik wymagał potwierdzenia środowiska H01 Trial i osobnej zgody przed uruchomieniem trwałego zapisu.

## Stan początkowy

- Branch `java-cap-ksef`; katalog repozytorium był czysty przy kontroli.
- ABAP FS MCP: connection `H01`, client 100, wersja `2.12.4`, `development=false`; `ZAINVOICER` ma `recordChanges=false`.
- Odczyt aktywnych `ZR_AINV_INV_H`, root BDEF, tabel `ZAINV_INV_H/I/REF` oraz CDS dzieci potwierdził pola, asocjacje `_Items`/`_References` i managed numbering dla UUID. Klasa `ZCL_AINV_TEST_DATA` nie istniała.
- `abapfs_get_sap_system_info` zwrócił system type `Unknown`, release `N/A`; nie potwierdził samodzielnie etykiety Trial.

## Implementacja

Utworzono i aktywowano `ZCL_AINV_TEST_DATA` w pakiecie `ZAINVOICER`. Jedno `MODIFY ENTITIES OF ZR_AINV_INV_H` tworzy cztery nagłówki i dzieci przez `CREATE BY \_Items` oraz `CREATE BY \_References`, z CID/CID_REF. Kwoty pozycji sumują się do nagłówków; użyte są PLN, syntetyczne nazwy, daty 2026 oraz stałe numery seed.

Klasa loguje `MAPPED`, `FAILED`, `REPORTED` i odpowiedź fazy `COMMIT ENTITIES`, wywołuje `ROLLBACK ENTITIES` przy błędach oraz sprawdza cztery numery seed przed utworzeniem. Jeżeli istnieje choć jeden z nich, pomija cały zestaw i wypisuje istniejące UUID; częściowy zestaw wymaga ręcznego uzgodnienia.

Aktywny model nie ma unikalności `InvoiceNumber` ani dostępnej blokady globalnej dla nieistniejącego rekordu. Odczyt wstępny nie chroni przed równoległymi uruchomieniami; klasa wypisuje to ograniczenie. Nie zmieniano tabel, CDS, BDEF ani DCL.

## Wyniki

- Aktywacja klasy: PASS; aktywna wersja odczytana ponownie.
- Diagnostyka ABAP FS: 0 błędów, 0 ostrzeżeń.
- ATC `ABAP_CLOUD_DEVELOPMENT_DEFAULT`: 0 błędów, 0 ostrzeżeń, 35 informacji oznaczonych jako exempted (m.in. teksty seed bez text elements).
- Użytkownik potwierdził H01 Trial i zgodę na jednorazowy zapis, po czym sam uruchomił klasę w H01 i przekazał poniższy wynik. W logu widnieje „Zapis zakończony: 4 faktury, 9 pozycji, 6 referencji.” oraz wynik `MAPPED`; kod emituje ten komunikat po `COMMIT ENTITIES`, gdy `sy-subrc = 0` i tabela `FAILED` zapisu jest pusta. To ocena logu użytkownika i ścieżki kodu, nie niezależny odczyt bazy.
- Mapowania UUID z logu użytkownika:

| CID | UUID |
| --- | --- |
| H_FI_AP | `56B09C124EE51FD1B4AC56570F4D14C8` |
| H_FI_AR | `56B09C124EE51FD1B4AC56570F4D34C8` |
| H_MM_IV | `56B09C124EE51FD1B4AC56570F4D54C8` |
| H_SD_BILL | `56B09C124EE51FD1B4AC56570F4D74C8` |
| I_FI_AP_10 | `56B09C124EE51FD1B4AC56570F4D94C8` |
| I_FI_AP_20 | `56B09C124EE51FD1B4AC56570F4DB4C8` |
| I_FI_AR_10 | `56B09C124EE51FD1B4AC56570F4DD4C8` |
| I_FI_AR_20 | `56B09C124EE51FD1B4AC56570F4DF4C8` |
| I_FI_AR_30 | `56B09C124EE51FD1B4AC56570F4E14C8` |
| I_MM_IV_10 | `56B09C124EE51FD1B4AC56570F4E34C8` |
| I_MM_IV_20 | `56B09C124EE51FD1B4AC56570F4E54C8` |
| I_SD_BILL_10 | `56B09C124EE51FD1B4AC56570F4E74C8` |
| I_SD_BILL_20 | `56B09C124EE51FD1B4AC56570F4E94C8` |
| R_FI_AP_10 | `56B09C124EE51FD1B4AC56570F4EB4C8` |
| R_FI_AR_10 | `56B09C124EE51FD1B4AC56570F4ED4C8` |
| R_MM_IV_PO | `56B09C124EE51FD1B4AC56570F4EF4C8` |
| R_MM_IV_GR | `56B09C124EE51FD1B4AC56570F4F14C8` |
| R_SD_BILL_SO | `56B09C124EE51FD1B4AC56570F4F34C8` |
| R_SD_BILL_DL | `56B09C124EE51FD1B4AC56570F4F54C8` |

- Własny, tylko-odczytowy ABAP SQL SELECT przez MCP (z aktywnych CDS, bez bezpośredniego SQL do tabel) potwierdził 4 nagłówki z oczekiwanymi numerami, procesami, kierunkami, datami, syntetycznymi kontrahentami/NIP, UUID, walutą PLN i sumami. Odczyt `ZR_AINV_INV_I` po każdym UUID rodzica zwrócił odpowiednio 2/3/2/2 pozycje; wszystkie mają pasujący `InvoiceUUID`, PLN i kwoty zgodne z sumami nagłówków. Odczyt `ZR_AINV_INV_REF` po UUID rodzica zwrócił 1/1/2/2 referencje z oczekiwanymi modułami/rolami. To potwierdza zapis i relacje w modelu CDS.
- Powtórne uruchomienie i HTTP OData `$expand`: NIE WYKONANO. MCP w tej sesji nie udostępnia uwierzytelnionego klienta HTTP/OData runtime; potrzebny odczyt z rzeczywistego bindingu.
- Pierwsza kwerenda SQL użyła nazw pól tabeli (`invoice_uuid`) zamiast aliasów pól CDS (`InvoiceUUID`) i zwróciła błąd kolumny. Po korekcie nazwy pól odczyty zakończyły się poprawnie. Nie używano bezpośredniego SQL do tabel.
- Nie wykonywano push/commit Git ani eksportu abapGit.

## Następne kroki

1. Wykonać uwierzytelniony GET OData V4 pod adresem odczytanym z aktywnego bindingu, z `$expand=_Items,_References`, i zapisać wynik.
2. Po ponownym potwierdzeniu dostępu uruchomić klasę sekwencyjnie drugi raz i sprawdzić, że raportuje istniejące faktury bez ponownego COMMIT; nie uruchamiać równolegle.
