CLASS ltc_ksef_result DEFINITION FINAL FOR TESTING
  DURATION SHORT RISK LEVEL HARMLESS.
  PRIVATE SECTION.
    DATA invoice_uuid TYPE sysuuid_x16.
    DATA item_uuid TYPE sysuuid_x16.
    DATA ref_uuid TYPE sysuuid_x16.
    DATA request TYPE za_ainv_ksf_req.
    METHODS setup.
    METHODS teardown.
    METHODS read_header RETURNING VALUE(header) TYPE zr_ainv_inv_h.
    METHODS apply IMPORTING command TYPE za_ainv_ksf_req
      RETURNING VALUE(outcome) TYPE za_ainv_ksf_res.
    METHODS first_apply FOR TESTING.
    METHODS same_request_is_noop FOR TESTING.
    METHODS same_result_new_request FOR TESTING.
    METHODS changed_request_conflicts FOR TESTING.
    METHODS different_result_conflicts FOR TESTING.
    METHODS stale_version FOR TESTING.
    METHODS nip_denied FOR TESTING.
    METHODS company_denied FOR TESTING.
    METHODS actual_scope_denied FOR TESTING.
    METHODS inbound_denied FOR TESTING.
    METHODS environment_denied FOR TESTING.
    METHODS readonly_result_fields FOR TESTING.
    METHODS action_right_denies_crud FOR TESTING.
    METHODS action_right_denies_children FOR TESTING.
    METHODS no_right_denies_action FOR TESTING.
    METHODS missing_version_denied FOR TESTING.
    METHODS changed_scope_denied FOR TESTING.
    METHODS child_changes_denied FOR TESTING.
ENDCLASS.

CLASS ltc_ksef_result IMPLEMENTATION.
  METHOD setup.
    ROLLBACK ENTITIES.
    " Testowa polityka zastępuje wyłącznie AUTHORITY-CHECK, nie logikę RAP.
    TEST-INJECTION ainv_authority.
      is_allowed = xsdbool( ( nip IS INITIAL AND company IS INITIAL )
        OR ( nip = '5260250995' AND company = 'TST1' ) ).
    END-TEST-INJECTION.
    MODIFY ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvH
      CREATE FIELDS ( InvoiceNumber OriginProcess Direction InvoiceKind
        InvoiceStatus CompanyCode SellerNip BuyerNip CurrencyCode )
      WITH VALUE #( ( %cid = 'CAP04B' InvoiceNumber = 'AINV-CAP04B-ROLLBACK'
        OriginProcess = 'FI_AR' Direction = 'OUTBOUND' InvoiceKind = 'VAT'
        InvoiceStatus = 'NEW' CompanyCode = 'TST1' SellerNip = '5260250995'
        BuyerNip = '1234567890' CurrencyCode = 'PLN' ) )
      MAPPED DATA(mapped) FAILED DATA(failure).
    cl_abap_unit_assert=>assert_initial( failure-zrainvinvh ).
    invoice_uuid = mapped-zrainvinvh[ 1 ]-InvoiceUUID.
    MODIFY ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvH
      CREATE BY \_Items FIELDS ( ItemNo Description CurrencyCode )
      WITH VALUE #( ( InvoiceUUID = invoice_uuid %target = VALUE #(
        ( %cid = 'FIXTURE_ITEM' ItemNo = '000010' Description = 'Fixture'
          CurrencyCode = 'PLN' ) ) ) )
      CREATE BY \_References FIELDS ( SourceSystem SourceModule SourceDocNo )
      WITH VALUE #( ( InvoiceUUID = invoice_uuid %target = VALUE #(
        ( %cid = 'FIXTURE_REF' SourceSystem = 'CAP04B' SourceModule = 'FI'
          SourceDocNo = 'ROLLBACK' ) ) ) )
      MAPPED DATA(child_mapped) FAILED DATA(child_failure).
    cl_abap_unit_assert=>assert_initial( child_failure ).
    item_uuid = child_mapped-zrainvinvi[ 1 ]-ItemUUID.
    ref_uuid = child_mapped-zrainvinvref[ 1 ]-RefUUID.
    DATA(header) = read_header( ).
    IF header-LocalLastChangedAt IS INITIAL.
      " Nowy rekord w buforze nie przeszedł save; nadajemy testową wersję po kontroli praw.
      cl_abap_unit_assert=>assert_true( lcl_ainv_auth=>allowed(
        activity = '02' nip = '5260250995' company = 'TST1' ) ).
      GET TIME STAMP FIELD header-LocalLastChangedAt.
      MODIFY ENTITIES OF zr_ainv_inv_h IN LOCAL MODE ENTITY ZrAinvInvH
        UPDATE FIELDS ( LocalLastChangedAt )
        WITH VALUE #( ( InvoiceUUID = invoice_uuid
                        LocalLastChangedAt = header-LocalLastChangedAt ) )
        FAILED DATA(version_failure).
      cl_abap_unit_assert=>assert_initial( version_failure ).
      header = read_header( ).
    ENDIF.
    request = VALUE #( InvoiceUUID = invoice_uuid
      WriteRequestId = '11111111111141118111111111111111'
      KsefNumber = '5260250995-20261028-AAAAAAAAAAAA-00'
      Environment = 'TEST' TaxpayerNip = '5260250995' CompanyCode = 'TST1'
      ExpectedLocalLastChangedAt = header-LocalLastChangedAt ).
  ENDMETHOD.

  METHOD teardown.
    " Nie utrwalamy faktury ani wyniku; test nie zawiera COMMIT ENTITIES.
    ROLLBACK ENTITIES.
  ENDMETHOD.

  METHOD read_header.
    READ ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvH
      ALL FIELDS WITH VALUE #( ( InvoiceUUID = invoice_uuid ) )
      RESULT DATA(rows) FAILED DATA(failure).
    cl_abap_unit_assert=>assert_initial( failure-zrainvinvh ).
    cl_abap_unit_assert=>assert_equals( act = lines( rows ) exp = 1 ).
    header = CORRESPONDING #( rows[ 1 ] ).
  ENDMETHOD.

  METHOD apply.
    MODIFY ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvH
      EXECUTE ApplyKsefResult FROM VALUE #( ( %cid = 'WRITE' %param = command ) )
      RESULT DATA(results) FAILED DATA(failure).
    cl_abap_unit_assert=>assert_initial( failure-zrainvinvh ).
    cl_abap_unit_assert=>assert_equals( act = lines( results ) exp = 1 ).
    outcome = results[ 1 ]-%param.
  ENDMETHOD.

  METHOD first_apply.
    DATA(outcome) = apply( request ).
    cl_abap_unit_assert=>assert_equals( act = outcome-Outcome exp = 'APPLIED' ).
    DATA(header) = read_header( ).
    cl_abap_unit_assert=>assert_equals( act = header-KsefNumber exp = request-KsefNumber ).
    cl_abap_unit_assert=>assert_equals( act = header-KsefEnvironment exp = 'TEST' ).
    cl_abap_unit_assert=>assert_equals( act = header-KsefWriteRequestId exp = request-WriteRequestId ).
    cl_abap_unit_assert=>assert_not_initial( header-KsefRecordedAt ).
    cl_abap_unit_assert=>assert_equals( act = header-InvoiceStatus exp = 'NEW' ).
  ENDMETHOD.

  METHOD same_request_is_noop.
    apply( request ).
    DATA(before) = read_header( ).
    DATA(outcome) = apply( request ).
    DATA(after) = read_header( ).
    cl_abap_unit_assert=>assert_equals( act = outcome-Outcome exp = 'ALREADY_APPLIED' ).
    cl_abap_unit_assert=>assert_equals( act = after exp = before ).
  ENDMETHOD.

  METHOD same_result_new_request.
    apply( request ).
    DATA(before) = read_header( ).
    request-WriteRequestId = '22222222222242228222222222222222'.
    DATA(outcome) = apply( request ).
    cl_abap_unit_assert=>assert_equals( act = outcome-Outcome exp = 'ALREADY_APPLIED' ).
    cl_abap_unit_assert=>assert_equals( act = read_header( ) exp = before ).
  ENDMETHOD.

  METHOD changed_request_conflicts.
    apply( request ).
    DATA(before) = read_header( ).
    request-KsefNumber = '5260250995-20261028-BBBBBBBBBBBB-00'.
    DATA(outcome) = apply( request ).
    cl_abap_unit_assert=>assert_equals( act = outcome-Outcome exp = 'IDEMPOTENCY_CONFLICT' ).
    cl_abap_unit_assert=>assert_equals( act = read_header( ) exp = before ).
  ENDMETHOD.

  METHOD different_result_conflicts.
    apply( request ).
    DATA(before) = read_header( ).
    request-WriteRequestId = '22222222222242228222222222222222'.
    request-KsefNumber = '5260250995-20261028-BBBBBBBBBBBB-00'.
    DATA(outcome) = apply( request ).
    cl_abap_unit_assert=>assert_equals( act = outcome-Outcome exp = 'RESULT_CONFLICT' ).
    cl_abap_unit_assert=>assert_equals( act = read_header( ) exp = before ).
  ENDMETHOD.

  METHOD stale_version.
    DATA(before) = read_header( ).
    request-ExpectedLocalLastChangedAt = '20000101000000.0000000'.
    DATA(outcome) = apply( request ).
    cl_abap_unit_assert=>assert_equals( act = outcome-Outcome exp = 'VERSION_CONFLICT' ).
    cl_abap_unit_assert=>assert_equals( act = read_header( ) exp = before ).
  ENDMETHOD.

  METHOD nip_denied.
    request-TaxpayerNip = '1234567890'.
    MODIFY ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvH
      EXECUTE ApplyKsefResult FROM VALUE #( ( %cid = 'DENY' %param = request ) )
      FAILED DATA(failure).
    cl_abap_unit_assert=>assert_not_initial( failure-zrainvinvh ).
    cl_abap_unit_assert=>assert_initial( read_header( )-KsefNumber ).
  ENDMETHOD.

  METHOD company_denied.
    request-CompanyCode = 'TST2'.
    MODIFY ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvH
      EXECUTE ApplyKsefResult FROM VALUE #( ( %cid = 'DENY' %param = request ) )
      FAILED DATA(failure).
    cl_abap_unit_assert=>assert_not_initial( failure-zrainvinvh ).
    cl_abap_unit_assert=>assert_initial( read_header( )-KsefNumber ).
  ENDMETHOD.

  METHOD actual_scope_denied.
    " Nawet szeroka polityka nie pozwala przypisać wyniku do innego NIP faktury.
    TEST-INJECTION ainv_authority.
      is_allowed = abap_true.
    END-TEST-INJECTION.
    request-TaxpayerNip = '1234567890'.
    MODIFY ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvH
      EXECUTE ApplyKsefResult FROM VALUE #( ( %cid = 'DENY' %param = request ) )
      FAILED DATA(failure).
    cl_abap_unit_assert=>assert_not_initial( failure-zrainvinvh ).
    cl_abap_unit_assert=>assert_initial( read_header( )-KsefNumber ).
  ENDMETHOD.

  METHOD inbound_denied.
    MODIFY ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvH
      UPDATE FIELDS ( Direction BuyerNip )
      WITH VALUE #( ( InvoiceUUID = invoice_uuid Direction = 'INBOUND'
                      BuyerNip = '5260250995' ) ) FAILED DATA(update_failure).
    cl_abap_unit_assert=>assert_initial( update_failure-zrainvinvh ).
    MODIFY ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvH
      EXECUTE ApplyKsefResult FROM VALUE #( ( %cid = 'DENY' %param = request ) )
      FAILED DATA(failure).
    cl_abap_unit_assert=>assert_not_initial( failure-zrainvinvh ).
    cl_abap_unit_assert=>assert_initial( read_header( )-KsefNumber ).
  ENDMETHOD.

  METHOD environment_denied.
    request-Environment = 'PROD'.
    MODIFY ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvH
      EXECUTE ApplyKsefResult FROM VALUE #( ( %cid = 'DENY' %param = request ) )
      FAILED DATA(failure).
    cl_abap_unit_assert=>assert_not_initial( failure-zrainvinvh ).
    cl_abap_unit_assert=>assert_initial( read_header( )-KsefNumber ).
  ENDMETHOD.

  METHOD readonly_result_fields.
    " Naruszenie dynamicznym UPDATE potwierdzono osobno dumpem READONLY_FIELD.
    " Regresja sprawdza cztery pola bez celowego przerywania całego zestawu.
    DATA permissions_request TYPE STRUCTURE FOR PERMISSIONS REQUEST zr_ainv_inv_h.
    permissions_request = VALUE #(
      %field-KsefNumber = if_abap_behv=>mk-on
      %field-KsefEnvironment = if_abap_behv=>mk-on
      %field-KsefRecordedAt = if_abap_behv=>mk-on
      %field-KsefWriteRequestId = if_abap_behv=>mk-on ).
    GET PERMISSIONS ONLY GLOBAL FEATURES ENTITY zr_ainv_inv_h
      REQUEST permissions_request RESULT DATA(permissions).
    cl_abap_unit_assert=>assert_equals(
      act = permissions-global-%field-KsefNumber exp = if_abap_behv=>perm-f-read_only ).
    cl_abap_unit_assert=>assert_equals(
      act = permissions-global-%field-KsefEnvironment exp = if_abap_behv=>perm-f-read_only ).
    cl_abap_unit_assert=>assert_equals(
      act = permissions-global-%field-KsefRecordedAt exp = if_abap_behv=>perm-f-read_only ).
    cl_abap_unit_assert=>assert_equals(
      act = permissions-global-%field-KsefWriteRequestId exp = if_abap_behv=>perm-f-read_only ).
  ENDMETHOD.

  METHOD action_right_denies_crud.
    TEST-INJECTION ainv_authority.
      is_allowed = xsdbool( activity = '16' ).
    END-TEST-INJECTION.
    MODIFY ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvH
      UPDATE FIELDS ( InvoiceStatus )
      WITH VALUE #( ( InvoiceUUID = invoice_uuid InvoiceStatus = 'CHANGED' ) )
      FAILED DATA(update_failure).
    cl_abap_unit_assert=>assert_not_initial( update_failure-zrainvinvh ).
    MODIFY ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvH
      DELETE FROM VALUE #( ( InvoiceUUID = invoice_uuid ) )
      FAILED DATA(delete_failure).
    cl_abap_unit_assert=>assert_not_initial( delete_failure-zrainvinvh ).
    MODIFY ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvH
      CREATE FIELDS ( InvoiceNumber CompanyCode SellerNip Direction )
      WITH VALUE #( ( %cid = 'DENY' InvoiceNumber = 'DENIED'
        CompanyCode = 'TST1' SellerNip = '5260250995' Direction = 'OUTBOUND' ) )
      FAILED DATA(create_failure).
    cl_abap_unit_assert=>assert_not_initial( create_failure-zrainvinvh ).
  ENDMETHOD.

  METHOD action_right_denies_children.
    TEST-INJECTION ainv_authority.
      is_allowed = xsdbool( activity = '16' ).
    END-TEST-INJECTION.
    MODIFY ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvH
      CREATE BY \_Items FIELDS ( ItemNo Description CurrencyCode )
      WITH VALUE #( ( InvoiceUUID = invoice_uuid %target = VALUE #(
        ( %cid = 'DENIED_ITEM' ItemNo = '000010' Description = 'Denied'
          CurrencyCode = 'PLN' ) ) ) )
      FAILED DATA(failure).
    cl_abap_unit_assert=>assert_not_initial( failure ).
  ENDMETHOD.

  METHOD no_right_denies_action.
    TEST-INJECTION ainv_authority.
      is_allowed = abap_false.
    END-TEST-INJECTION.
    MODIFY ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvH
      EXECUTE ApplyKsefResult FROM VALUE #( ( %cid = 'DENY' %param = request ) )
      FAILED DATA(failure).
    cl_abap_unit_assert=>assert_not_initial( failure-zrainvinvh ).
  ENDMETHOD.
  METHOD missing_version_denied.
    CLEAR request-ExpectedLocalLastChangedAt.
    MODIFY ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvH
      EXECUTE ApplyKsefResult FROM VALUE #( ( %cid = 'DENY' %param = request ) )
      FAILED DATA(failure).
    cl_abap_unit_assert=>assert_not_initial( failure-zrainvinvh ).
    cl_abap_unit_assert=>assert_initial( read_header( )-KsefNumber ).
  ENDMETHOD.

  METHOD changed_scope_denied.
    MODIFY ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvH
      UPDATE FIELDS ( CompanyCode )
      WITH VALUE #( ( InvoiceUUID = invoice_uuid CompanyCode = 'TST2' ) )
      FAILED DATA(failure).
    cl_abap_unit_assert=>assert_not_initial( failure-zrainvinvh ).
    cl_abap_unit_assert=>assert_equals( act = read_header( )-CompanyCode exp = 'TST1' ).
  ENDMETHOD.

  METHOD child_changes_denied.
    TEST-INJECTION ainv_authority.
      is_allowed = xsdbool( activity = '16' ).
    END-TEST-INJECTION.
    MODIFY ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvI
      UPDATE FIELDS ( Description )
      WITH VALUE #( ( ItemUUID = item_uuid Description = 'Denied' ) )
      FAILED DATA(item_update).
    cl_abap_unit_assert=>assert_not_initial( item_update ).
    MODIFY ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvI
      DELETE FROM VALUE #( ( ItemUUID = item_uuid ) ) FAILED DATA(item_delete).
    cl_abap_unit_assert=>assert_not_initial( item_delete ).
    MODIFY ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvRef
      UPDATE FIELDS ( SourceDocNo )
      WITH VALUE #( ( RefUUID = ref_uuid SourceDocNo = 'Denied' ) )
      FAILED DATA(ref_update).
    cl_abap_unit_assert=>assert_not_initial( ref_update ).
    MODIFY ENTITIES OF zr_ainv_inv_h ENTITY ZrAinvInvRef
      DELETE FROM VALUE #( ( RefUUID = ref_uuid ) ) FAILED DATA(ref_delete).
    cl_abap_unit_assert=>assert_not_initial( ref_delete ).
  ENDMETHOD.
ENDCLASS.



