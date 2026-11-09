---
title: "Referencyjne źródła UI DCL (poza eksportem abapGit)"
date: 2026-11-08
type: reference
status: aktywny
tags: [ainvoicer, abap, dcl, abapgit, security]
---

# Referencyjne źródła UI DCL

**Kontrola CAP-06, 2026-11-08:** aktywne źródła trzech ról odczytano przez ADT, zgodne z kopiami poniżej; UI READ4/9/6. abapGit linked `main`,0 staged/6 unstaged plików tych trzech DCLS. `diff` zwrócił local="" dla wszystkich źródeł, ale tym razem remote było niepuste; Git `origin/main=141bda4` nadal nie zawiera tych plików. Etykiet backendowego diffu nie traktujemy jako dowodu kompletności Git. Nie stage'owano pustych źródeł i nie zmieniano/reaktywowano obiektów SAP (sprint READ-ONLY). Przyczyna serializacji nadal BLOCKED.

**Deploy gate — przygotowany, nieprzetestowany na czystym systemie:** (1) instalacja CDS bez publikacji i bez nadań; (2) utworzenie i aktywacja odpowiadających DCL z aktualnego, recenzowanego źródła; (3) kontrola active/inactive oraz skutecznej odmowy READ/kluczy/dzieci/expand poza zakresem; dopiero (4) publikacja usługi i ograniczona rola pozytywna. Runtime odmowy można sprawdzić w kontrolowanej sesji developerskiej przed udostępnieniem endpointu business userom. Import do nowego systemu pozostaje zablokowany bez spełnienia bramki.

Korekta interpretacji: według [SAP AccessControl Annotations](https://help.sap.com/docs/ABAP_PLATFORM_NEW/cc0c305d2fab47bd808adcad3ca7ee9d/cf2989f6f5944305a0c8bcc0b0080c7c.html) `#MANDATORY` wymaga istnienia access control podczas runtime; nie należy utożsamiać jego zachowania z `#CHECK`. Historyczne stwierdzenie, że import bez DCL automatycznie daje nieograniczony odczyt, skorygowano poniżej. W tej sesji nie usuwano DCL ani nie testowano systemu bez ról, więc nie deklarujemy wyniku takiego importu. W każdym przypadku brak źródeł DCL blokuje bezpieczny deploy.

> **OSTRZEŻENIE BEZPIECZEŃSTWA.** Eksport abapGit w `abap/src/` zawiera widoki UI `ZC_AINV_INV_H_UI`, `ZC_AINV_INV_I_UI` i `ZC_AINV_INV_REF_UI` z `@AccessControl.authorizationCheck: #MANDATORY`, ale **bez ich DCL**. abapGit w H01 serializuje te trzy role jako puste: diff MCP daje `local=""`, a historyczny `prepare_commit` zwracał `NO_CHANGES`. Starsze DCL (`ZC_AINV_INV_H` itd.) serializują się poprawnie. Taki eksport jest niekompletny do odtworzenia bezpieczeństwa w nowym systemie. Po każdym imporcie najpierw utwórz i aktywuj właściwe role z poniższych źródeł oraz potwierdź odmowy, a dopiero potem udostępniaj binding `ZUI_AINV_INV_H_O4` i role pozytywne.

Pliki to aktywne źródła odczytane z H01 przez ADT (`/sap/bc/adt/acm/dcl/sources/<name>/source/main`) 2026-11-07. Są aktywne i zweryfikowane LIVE: użytkownik bez roli widzi 0/0/0, a z rolą `ZBR_AINV_READ_1000` widzi 4/9/6.

| Plik | Reguła |
| --- | --- |
| [zc_ainv_inv_h_ui.dcls.asdcls](zc_ainv_inv_h_ui.dcls.asdcls) | OUTBOUND: `SellerNip`+`CompanyCode`; INBOUND: `BuyerNip`+`CompanyCode`; `pfcg_auth(Z_AINV_KSF, ZAINV_NIP, ZAINV_BUK, ACTVT='03')` |
| [zc_ainv_inv_i_ui.dcls.asdcls](zc_ainv_inv_i_ui.dcls.asdcls) | dziedziczenie z nagłówka przez `_Parent` |
| [zc_ainv_inv_ref_ui.dcls.asdcls](zc_ainv_inv_ref_ui.dcls.asdcls) | dziedziczenie z nagłówka przez `_Parent` |

Te pliki leżą celowo **poza** `abap/src/`. abapGit nie może ich traktować jako swoich, bo przy pustej serializacji SAP kolejne `Stage` pokazałoby je do „usunięcia” lub nadpisania pustą treścią. Po zmianie DCL w SAP zaktualizuj te kopie ręcznie, porównując je ze źródłem aktywnym. Diagnoza przyczyny pustej serializacji pozostaje otwarta (kandydat: zgłoszenie do SAP lub próba re-save/aktywacji w ADT bez zmiany treści).
