---
title: "Referencyjne źródła UI DCL (poza eksportem abapGit)"
date: 2026-11-07
type: reference
status: aktywny
tags: [ainvoicer, abap, dcl, abapgit, security]
---

# Referencyjne źródła UI DCL

> **OSTRZEŻENIE BEZPIECZEŃSTWA.** Eksport abapGit w `abap/src/` zawiera widoki UI `ZC_AINV_INV_H_UI`, `ZC_AINV_INV_I_UI` i `ZC_AINV_INV_REF_UI` z `@AccessControl.authorizationCheck: #MANDATORY`, ale **bez ich DCL**. abapGit w H01 serializuje te trzy role jako puste: diff MCP daje `local=""`, a `prepare_commit` zwraca `NO_CHANGES`. Starsze DCL (`ZC_AINV_INV_H` itd.) serializują się poprawnie. Import samego `abap/src/` do innego systemu utworzyłby widoki **bez ograniczeń dostępu**, bo brak roli CDS oznacza brak filtrowania. Po każdym imporcie najpierw utwórz i aktywuj te role z poniższych plików, a dopiero potem publikuj binding `ZUI_AINV_INV_H_O4`.

Pliki to aktywne źródła odczytane z H01 przez ADT (`/sap/bc/adt/acm/dcl/sources/<name>/source/main`) 2026-11-07. Są aktywne i zweryfikowane LIVE: użytkownik bez roli widzi 0/0/0, a z rolą `ZBR_AINV_READ_1000` widzi 4/9/6.

| Plik | Reguła |
| --- | --- |
| [zc_ainv_inv_h_ui.dcls.asdcls](zc_ainv_inv_h_ui.dcls.asdcls) | OUTBOUND: `SellerNip`+`CompanyCode`; INBOUND: `BuyerNip`+`CompanyCode`; `pfcg_auth(Z_AINV_KSF, ZAINV_NIP, ZAINV_BUK, ACTVT='03')` |
| [zc_ainv_inv_i_ui.dcls.asdcls](zc_ainv_inv_i_ui.dcls.asdcls) | dziedziczenie z nagłówka przez `_Parent` |
| [zc_ainv_inv_ref_ui.dcls.asdcls](zc_ainv_inv_ref_ui.dcls.asdcls) | dziedziczenie z nagłówka przez `_Parent` |

Te pliki leżą celowo **poza** `abap/src/`. abapGit nie może ich traktować jako swoich, bo przy pustej serializacji SAP kolejne `Stage` pokazałoby je do „usunięcia” lub nadpisania pustą treścią. Po zmianie DCL w SAP zaktualizuj te kopie ręcznie, porównując je ze źródłem aktywnym. Diagnoza przyczyny pustej serializacji pozostaje otwarta (kandydat: zgłoszenie do SAP lub próba re-save/aktywacji w ADT bez zmiany treści).
