---
title: "ABAP-05 — reuse aktywnej usługi RAP UI i krok ADT"
date: 2026-10-29
type: guide
status: zablokowany
tags: [ainvoicer, abap, rap, dcl, iam, cap-05, abap-05]
---

# RAP UI — stan SAP i źródła do review

[Dziennik ABAP-05](../../journal/2026-10-29-abap-05-real-rap-ui.md) · [Połączenie LIVE i dokładne kroki ADT/IAM](../../guides/RAP_UI_LIVE_CONNECTION.md) · [Historyczny audyt CAP-05](../../audits/2026-10-29-cap-05-implementation-review.md)

**Aktualizacja po inspekcji H01:** trzy widoki `ZC_AINV_INV_H_UI`, `ZC_AINV_INV_I_UI`, `ZC_AINV_INV_REF_UI`, odpowiadające im DCLS oraz SRVD `ZUI_AINV_INV_H_O4` już istnieją i są aktywne. Były zastane; nie utworzono ani nie aktywowano ich w tym sprincie. To stan nowszy od snapshotu `abap/src/` i wcześniejszego opisu patcha. Wszystkie siedem przeszło ATC ABAP_CLOUD_DEVELOPMENT_DEFAULT bez ustaleń.

**BLOCKED:** SRVB `ZUI_AINV_INV_H_O4` zwraca 404. MCP 2.13.3 oferuje dla tworzenia bindingu wyłącznie V2; wymagany V4 UI należy utworzyć w ADT. Nie utworzono V2 jako obejścia. Nie ma URI UI ani runtime testów IAM/DCL dwoma business userami; `dataMode` pozostaje `mock`. Web API `ZAPI_AINV_INV_H_O4` READ HTTP200, 4 nagłówki / 9 pozycji / 6 referencji, nie jest usługą UI.

## Których źródeł używać

- **Reuse aktywnego zestawu `ZC_AINV_INV_*_UI`**: zwykłe SELECT views, `#MANDATORY`, bez BDEF; dzieci dziedziczą DCL nagłówka przez `_Parent`. Nagłówek używa Z_AINV_KSF/ACTVT03 i pary NIP/CompanyCode (SellerNip OUTBOUND, BuyerNip INBOUND). Nie dziedziczy otwartego root `where TRUE`.
- Sześć plików `zc_ainv_ui_*.ddls.asddls` / `*.dcls.asdcls` w tym katalogu to **historyczny, alternatywny projekt, NOT SAP VERIFIED**. Nie wdrażać ich obok istniejących obiektów ani nie traktować jako eksportu H01. Pozostają do porównania z poprzednim audytem.
- `zui_ainv_inv_h_o4.srvd.srvdsrv` dostosowano do rzeczywistych widoków i provider contract `odata_v4_ui`. To **patch do review w ADT, nie eksport abapGit**. W aktywnym SAP aliasy dzieci to InvoiceItems/InvoiceReferences; patch zmienia je na wymagane Items/References. Nie zastosowano tej zmiany w SAP i nie deklarujemy jej Syntax Check/aktywacji.
- `document-mutation-tests.patch` jest historycznym, lokalnym patchem ABAP Unit, sprawdzonym wcześniej wyłącznie przez git apply --check. Nie uruchomiono go w H01. Nie dotyczy bramki publikacji read-only UI i nie upoważnia do testowych zmian faktur w tym sprincie.

## Kolejność odblokowania

1. ADT: porównać bieżące active/inactive i blokady istniejących obiektów, bez nadpisywania pracy użytkownika. Pakiet ZAINVOICER miał recordChanges=false; zweryfikować ponownie przed zmianą. Sprawdzić/uzgodnić aliasy SRVD i aktywować wyłącznie uzgodnioną zmianę.
2. Utworzyć binding **OData V4 - UI** dla istniejącego SRVD, aktywować i opublikować; skopiować rzeczywisty Service URL. Nie zmieniać Web API CAP ani ręcznie metadanych SRVB XML.
3. Administrator: skonfigurować IAM app/catalog/role oraz konkretne zakresy ZAINV_NIP/ZAINV_BUK i ACTVT03 dla dwóch business userów; bez wildcard i bez ACTVT16 dla UI. Nazwy nadań i użytkownicy wymagają ustalenia. Żadnego nadania nie wykonano.
4. Zaliczyć pełną [macierz READ](../../guides/RAP_UI_LIVE_CONNECTION.md#test-read--macierz-do-wykonania-po-publikacji), w tym bezpośredni odczyt obcych dzieci, metadata bez mutujących akcji, filtry/sort/count/paging/expand. Nie uruchamiać testowych HTTP mutacji.
5. Dopiero potem podłączyć `/rap-ui/` w osobnej konfiguracji Fiori tools lub approutera z tożsamością business usera. Szablon: `sapui5/ainvoicer/ui5-rap.yaml.example`. Weryfikację READ prowadzić z transportami KSeF/SAP write wyłączonymi.
6. Przygotować kontrolowany eksport aktywnych obiektów przez abapGit po ich pełnej weryfikacji. Autoryzacja Git w tym sprincie dotyczy własnego brancha feature, nie push SAP na java-cap-ksef; nie wykonano eksportu ani importu SAP.

## Ochrona dokumentu i writeback

Root BDEF nadal dopuszcza update/delete; inspekcja z poprzedniego audytu nie wykazała blokady po KsefNumber. Nie zmieniano tej polityki. Samo readonly czterech pól wyniku nie blokuje zmian treści, a wersja nagłówka nie gwarantuje wersji całej kompozycji. To osobna decyzja biznesowa/testy, bez obejścia CAS.

VERSION_CONFLICT pozostawia ACCEPTED/numer/WriteRequestId i writeback RECONCILIATION_REQUIRED. SapKsefResultWriteRecovery.diagnose(id) jest tylko odczytem i nie zmienia ExpectedVersion. UPO pozostaje niezależne. **Nowego KSeF SEND i H01 HTTP WRITE nie wykonano.**
