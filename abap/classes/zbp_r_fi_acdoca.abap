CLASS lhc_zr_fi_acdoca DEFINITION INHERITING FROM cl_abap_behavior_handler.
  PRIVATE SECTION.
    METHODS:
      get_global_authorizations FOR GLOBAL AUTHORIZATION
        IMPORTING
        REQUEST requested_authorizations FOR ZrFiAcdoca
        RESULT result,
      copy FOR MODIFY
        IMPORTING keys FOR ACTION ZrFiAcdoca~copy,
      calculateAmountLocal FOR DETERMINE ON MODIFY
        IMPORTING keys FOR ZrFiAcdoca~calculateAmountLocal,
      get_nbp_rate
        IMPORTING iv_currency    TYPE zr_fi_acdoca-currencycode
        RETURNING VALUE(rv_rate) TYPE decfloat34,
      parse_nbp_response
        IMPORTING iv_json_response TYPE string
        RETURNING VALUE(rv_rate)   TYPE decfloat34.
ENDCLASS.

CLASS lhc_zr_fi_acdoca IMPLEMENTATION.
  METHOD get_global_authorizations.
  ENDMETHOD.

  METHOD copy.
    DATA: lt_target TYPE TABLE FOR CREATE zr_fi_acdoca.

    READ ENTITIES OF zr_fi_acdoca IN LOCAL MODE
           ENTITY ZrFiAcdoca
           ALL FIELDS WITH CORRESPONDING #( keys )
           RESULT DATA(lt_source) FAILED failed.

    TRY.
        DATA(lv_cid) = cl_system_uuid=>create_uuid_x16_static( ).
      CATCH cx_uuid_error.
        "handle exception
    ENDTRY.

    TRY.
        APPEND CORRESPONDING #( lt_source[ 1 ] ) TO lt_target.
        LOOP AT lt_target ASSIGNING FIELD-SYMBOL(<fs_target>).
          <fs_target>-LineItem += 1.
          <fs_target>-%cid = lv_cid.
        ENDLOOP.
      CATCH cx_sy_itab_line_not_found.
        "handle exception
    ENDTRY.

    MODIFY ENTITIES OF zr_fi_acdoca IN LOCAL MODE
      ENTITY ZrFiAcdoca
      CREATE FIELDS (
        CompanyCode
        FiscalYear
        DocumentNo
        LineItem
        PostingDate
        DocumentDate
        EntryDate
        DocumentType
        Reference
        DebitCredit
        Account
        Vendor
        Customer
        CostCenter
        ProfitCenter
        Segment
        CurrencyCode
        AmountDocument
        AmountLocal
        PaymentTerms
        PaymentMethod
        DueDate
        ClearingDoc
        ClearingDate
        CreatedBy
        CreatedAt
        ChangedBy
        ChangedAt
      ) WITH lt_target
      MAPPED DATA(mapped_create).

    mapped-zrfiacdoca = mapped_create-zrfiacdoca.
  ENDMETHOD.

  METHOD calculateAmountLocal.
    " Read current data
    READ ENTITIES OF zr_fi_acdoca IN LOCAL MODE
      ENTITY ZrFiAcdoca
        FIELDS ( AmountDocument CurrencyCode AmountLocal )
        WITH CORRESPONDING #( keys )
      RESULT DATA(documents).

    LOOP AT documents INTO DATA(document).
      DATA(lv_amount_local) = document-AmountDocument.

      " If currency is not PLN, convert using NBP API
      IF document-CurrencyCode <> 'PLN' AND document-CurrencyCode IS NOT INITIAL.
        DATA(lv_exchange_rate) = get_nbp_rate( document-CurrencyCode ).

        IF lv_exchange_rate > 0.
          lv_amount_local = document-AmountDocument * lv_exchange_rate.
        ENDIF.
      ENDIF.

      " Update AmountLocal field
      MODIFY ENTITIES OF zr_fi_acdoca IN LOCAL MODE
        ENTITY ZrFiAcdoca
          UPDATE FIELDS ( AmountLocal )
          WITH VALUE #( ( %tky = document-%tky
                         AmountLocal = lv_amount_local ) ).
    ENDLOOP.
  ENDMETHOD.


   METHOD get_nbp_rate.
    DATA: lv_currency TYPE string VALUE 'EUR',
          lv_url      TYPE string,
          lo_client   TYPE REF TO if_web_http_client,
          lv_response TYPE string.

    lv_url = |http://api.nbp.pl/api/exchangerates/rates/A/{ iv_currency }/?format=json|.
    " Create HTTP client using the destination provider
    TRY.
        DATA(lo_dest) = cl_http_destination_provider=>create_by_url( lv_url ).
      CATCH cx_http_dest_provider_error INTO DATA(lx_dest_provider_err).
        " Handle error if destination creation fails
        DATA(lv_error_text) = lx_dest_provider_err->get_text( ).
        RETURN.
    ENDTRY.

    TRY.
        lo_client = cl_web_http_client_manager=>create_by_http_destination( lo_dest ).
        DATA(req) = lo_client->get_http_request(  ).
        DATA(lv_result) = lo_client->execute( if_web_http_client=>get )->get_text( ).
        rv_rate = parse_nbp_response( iv_json_response = lv_result ).
        lo_client->close( ).
      CATCH cx_web_http_client_error INTO DATA(lx_web_http_client_err).
        lv_error_text = lx_web_http_client_err->get_text( ).
    ENDTRY.
  ENDMETHOD.

  METHOD parse_nbp_response.
    " This method would parse the JSON response from NBP API
    " For now, simplified implementation
    DATA: lv_response TYPE string,
          lv_rate_str TYPE string,
          lv_rate     TYPE decfloat34.

    lv_response = iv_json_response.

    " Simple JSON parsing - in production use proper JSON parser
    " Looking for "mid":value pattern
    FIND REGEX '"mid":([0-9.]+)' IN lv_response SUBMATCHES lv_rate_str.

    IF sy-subrc = 0.
      lv_rate = lv_rate_str.
      rv_rate = lv_rate.
    ELSE.
      rv_rate = 0.
    ENDIF.
  ENDMETHOD.



ENDCLASS.