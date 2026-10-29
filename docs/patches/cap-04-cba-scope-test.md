---
title: "CAP-04 — test EML CBA poza zakresem uprawnień"
date: 2026-10-29
type: patch
status: niezweryfikowany
tags: [ainvoicer, abap, rap, authorization, test]
---

# Test do dodania w H01 po odblokowaniu odczytu include

**NOT VERIFIED / BLOCKED.** To propozycja dodatku do `ZBP_R_AINV_INV_H` → test include `ltc_ksef_result`, nie aktywny kod SAP. Odczyt include przez MCP został odrzucony automatyczną kontrolą uprawnień. Nie zapisano obiektów SAP ani lokalnego eksportu `abap/src/`. Najpierw porównać active/inactive test include i jego fixture z Git. Nie importować automatycznie.

Obecny fixture tworzy nagłówek i po jednym dziecku w buforze RAP, z testową polityką `ainv_authority`; teardown wykonuje `ROLLBACK ENTITIES`. Poniższy test zastępuje wyłącznie AUTHORITY-CHECK: globalne `02` jest dozwolone, zakres faktycznego rodzica jest zabroniony. Sprawdza obie asocjacje. Nie zawiera `COMMIT ENTITIES`; pozytywną ścieżkę CBA pokrywa setup istniejącej klasy. Należy potwierdzić, że RAP uruchamia autoryzację instancji także dla nowego rodzica w tej samej LUW; w przeciwnym razie użyć odrębnej, uzgodnionej istniejącej faktury syntetycznej, z rollback dzieci.

W sekcji definicji dodać `METHODS foreign_scope_cba_denied FOR TESTING.`; w implementacji:

```abap
  METHOD foreign_scope_cba_denied.
    TEST-INJECTION ainv_authority.
      is_allowed = xsdbool( activity = '02'
        AND nip IS INITIAL AND company IS INITIAL ).
    END-TEST-INJECTION.
    cl_abap_unit_assert=>assert_true( lcl_ainv_auth=>allowed( '02' ) ).
    cl_abap_unit_assert=>assert_false( lcl_ainv_auth=>allowed(
      activity = '02' nip = '5260250995' company = 'TST1' ) ).

    READ ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvH
      BY \_Items ALL FIELDS WITH VALUE #( ( InvoiceUUID = invoice_uuid ) )
        RESULT DATA(items_before) FAILED DATA(items_read_failure)
      BY \_References ALL FIELDS WITH VALUE #( ( InvoiceUUID = invoice_uuid ) )
        RESULT DATA(refs_before) FAILED DATA(refs_read_failure).
    cl_abap_unit_assert=>assert_initial( items_read_failure ).
    cl_abap_unit_assert=>assert_initial( refs_read_failure ).
    cl_abap_unit_assert=>assert_equals( act = lines( items_before ) exp = 1 ).
    cl_abap_unit_assert=>assert_equals( act = lines( refs_before ) exp = 1 ).

    MODIFY ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvH
      CREATE BY \_Items FIELDS ( ItemNo Description CurrencyCode )
      WITH VALUE #( ( InvoiceUUID = invoice_uuid %target = VALUE #(
        ( %cid = 'FOREIGN_ITEM' ItemNo = '000020' Description = 'Denied'
          CurrencyCode = 'PLN' ) ) ) )
      MAPPED DATA(item_mapped) FAILED DATA(item_failure).
    cl_abap_unit_assert=>assert_not_initial( item_failure ).
    cl_abap_unit_assert=>assert_true( xsdbool(
      line_exists( item_failure-zrainvinvh[
        %fail-cause = if_abap_behv=>cause-unauthorized ] )
      OR line_exists( item_failure-zrainvinvi[
        %fail-cause = if_abap_behv=>cause-unauthorized ] ) ) ).
    cl_abap_unit_assert=>assert_initial( item_mapped-zrainvinvi ).

    MODIFY ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvH
      CREATE BY \_References FIELDS ( SourceSystem SourceModule SourceDocNo )
      WITH VALUE #( ( InvoiceUUID = invoice_uuid %target = VALUE #(
        ( %cid = 'FOREIGN_REF' SourceSystem = 'CAP04' SourceModule = 'FI'
          SourceDocNo = 'DENIED' ) ) ) )
      MAPPED DATA(ref_mapped) FAILED DATA(ref_failure).
    cl_abap_unit_assert=>assert_not_initial( ref_failure ).
    cl_abap_unit_assert=>assert_true( xsdbool(
      line_exists( ref_failure-zrainvinvh[
        %fail-cause = if_abap_behv=>cause-unauthorized ] )
      OR line_exists( ref_failure-zrainvinvref[
        %fail-cause = if_abap_behv=>cause-unauthorized ] ) ) ).
    cl_abap_unit_assert=>assert_initial( ref_mapped-zrainvinvref ).

    READ ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvH
      BY \_Items ALL FIELDS WITH VALUE #( ( InvoiceUUID = invoice_uuid ) )
        RESULT DATA(items_after) FAILED DATA(items_after_failure)
      BY \_References ALL FIELDS WITH VALUE #( ( InvoiceUUID = invoice_uuid ) )
        RESULT DATA(refs_after) FAILED DATA(refs_after_failure).
    cl_abap_unit_assert=>assert_initial( items_after_failure ).
    cl_abap_unit_assert=>assert_initial( refs_after_failure ).
    cl_abap_unit_assert=>assert_equals( act = items_after exp = items_before ).
    cl_abap_unit_assert=>assert_equals( act = refs_after exp = refs_before ).
  ENDMETHOD.
```

Test wymaga `%fail-cause = if_abap_behv=>cause-unauthorized` w `FAILED` (root lub dziecko zależnie od fazy RAP); błąd walidacji nie wystarczy do PASS. Nazwy komponentów i składnię potwierdzić kompilacją w H01. Po aktywacji tylko test include: ABAP Unit całej klasy i ATC. IAM, DCL i scenariusze bez zmian.
