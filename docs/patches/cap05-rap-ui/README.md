---
title: "CAP-05 — przygotowanie usługi RAP UI i testów ochrony dokumentu"
date: 2026-10-29
type: guide
status: zablokowany
tags: [ainvoicer, abap, rap, dcl, iam, cap-05]
---

# RAP UI — patch do przeglądu w ADT

**BLOCKED / NOT SAP VERIFIED.** To źródła do kontrolowanego wdrożenia, nie eksport aktywnych obiektów. Nie zmieniono H01 ani `abap/src/`. [Raport audytu](../../audits/2026-10-29-cap-05-implementation-review.md).

## Ustalenia LIVE READ

ABAP FS MCP 2.13.3, `h01`, pakiet `ZAINVOICER`, `recordChanges=false`. Wyszukiwanie bindingów `Z*AINV*` zwróciło tylko `ZAPI_AINV_INV_H_O4`; resolve i `$metadata` HTTP 200, published=true, wersja 0001. READ `$expand=_Items,_References`: 4 nagłówki / 9 pozycji / 6 referencji, bez dalszych stron.

Aktywne źródła root BDEF i implementation: update/delete oraz autoryzacja 02/06 bez warunku `KsefNumber`. Root DCL: `where TRUE`. Dostęp deweloperski OAuth nie dowodzi praw business usera ani communication usera.

Schemat `abapfs_create_object` pozwala na `DDLS/DF`, `DCLS/DL`, `SRVD/SRV`, lecz dla `SRVB/SVB` dopuszcza tylko `bindingType=ODATA` oraz kategorie OData **V2**. Nie ma opcji V4. Nie próbowano tworzyć bindingu V2 jako obejścia. Narzędzia nie zapewniają kompletnej ścieżki nadania i weryfikacji business catalog/role z ograniczeniami użytkownika. Potrzebne ADT i administrator IAM.

## Przygotowany wariant

Siedem źródeł w tym katalogu:

- `ZC_AINV_UI_H/I/REF`: zwykłe CDS read-only, bez BDEF, z `_Items`, `_References` i `_Header`.
- Trzy DCL: root sprawdza ACTVT03 oraz parę NIP/CompanyCode; OUTBOUND używa SellerNip, INBOUND BuyerNip. Dzieci dziedziczą ten sam warunek przez `_Header`, również przy bezpośrednim odczycie.
- `ZUI_AINV_INV_H_O4`: service definition z aliasami `Invoices`, `Items`, `References`. Bez `ApplyKsefResult` i bez projekcji behavior.

Osobny zestaw jest potrzebny, ponieważ istniejąca projekcja transakcyjna udostępnia akcję i CRUD. Brak BDEF w nowym zestawie odcina je od usługi UI. Nie modyfikujemy Web API ani jego dotychczasowej polityki odczytu. Składnię DCL porównano z [dokumentacją SAP dziedziczenia warunków](https://help.sap.com/docs/abap-cloud/abap-keywords/cds-dcl-define-role-replacement-step?locale=en-US); wymagane syntax check, ATC i weryfikacja runtime w H01.

## Czynności ADT / IAM

1. Porównać bieżące active/inactive obiekty i nazwy, potwierdzić pakiet `ZAINVOICER` oraz brak blokad. Utworzyć nowe trzy DDLS i trzy DCLS, aktywować razem ze względu na skojarzenia cykliczne, uruchomić syntax check/ATC. Następnie aktywować SRVD. Bez transportu przy potwierdzonym `recordChanges=false`.
2. W ADT utworzyć binding **OData V4 UI** dla tej definicji, sprawdzić aktywację i publikację. Nazwa planowana: `ZUI_AINV_INV_H_O4`. URL uzyskać z rzeczywistego bindingu; nie wyprowadzać go z nazwy.
3. Administrator: utworzyć właściwą IAM app dla bindingu, business catalog i business role, z `Z_AINV_KSF`, ACTVT03 oraz uzgodnionym ograniczeniem `ZAINV_NIP` / `ZAINV_BUK`. Nie nadawać ACTVT16 ani credentiali communication usera do przeglądarki. Nazwy aplikacji/katalogu/roli i użytkownicy nie są jeszcze uzgodnione; nie tworzyć globalnego nadania `*`.
4. Testować faktycznym business userem: `$metadata` bez akcji/CRUD; `Invoices?$filter=...&$orderby=...&$top=...&$skip=...`; szczegóły po kluczu i `$expand`. Drugi principal nie może widzieć obcego NIP/spółki ani dzieci, także bezpośrednio. Sprawdzić osobno regresję Web API CAP.
5. Dopiero po powyższych testach skonfigurować trasę aplikacji `/rap-ui/` do rzeczywistego runtime i uwierzytelnienie business usera. Trasa w UI5 jest logiczną trasą approutera/mockservera, **nie zgadniętym adresem SAP**. Dopiero wtedy ustawić `dataMode: "rap"`. Nie wykonano deploymentu ani eksportu/push.

## Ochrona dokumentu po numerze KSeF

`document-mutation-tests.patch` dodaje dwa testy charakteryzacyjne do istniejącego include testclasses. Pokazują oczekiwany obecnie brak blokady UPDATE kwoty i DELETE po `ApplyKsefResult`, z autoryzacją testową i rollbackiem. Patch sprawdzono `git apply --check`; **ABAP Unit nie wykonano, wynik zachowania nie jest LIVE PASS**. Przed użyciem porównać include z H01; nie nadpisywać jego nowszej wersji.

Rekomendowana osobna decyzja: precheck update/delete nagłówka i dzieci przy zapisanym numerze KSeF, z oceną korekt, zmian technicznych i istniejących konsumentów. Sam readonly pól wyniku nie blokuje zmiany treści. Dodatkowo CAS nagłówka nie gwarantuje wersji pozycji; rozważyć total version kompozycji. W tym sprincie nie dodano ograniczeń księgowych ani obejścia CAS.

`VERSION_CONFLICT`: zachować ACCEPTED/numer/WriteRequestId, pozostawić writeback w RECONCILIATION_REQUIRED. `SapKsefResultWriteRecovery.diagnose(id)` czyta bieżący RAP i porównuje wersję nagłówka/odcisk pozycji. Nie nadpisuje ExpectedVersion i nie wznawia konfliktu. Uzgodnienie biznesowe wymaga osobnej decyzji; nie wolno wysłać faktury ponownie do KSeF.
