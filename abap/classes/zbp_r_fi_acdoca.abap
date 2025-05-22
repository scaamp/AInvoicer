CLASS lhc_zr_fi_acdoca DEFINITION INHERITING FROM cl_abap_behavior_handler.
  PRIVATE SECTION.
    METHODS:
      get_global_authorizations FOR GLOBAL AUTHORIZATION
        IMPORTING
        REQUEST requested_authorizations FOR ZrFiAcdoca
        RESULT result,
      copy FOR MODIFY
        IMPORTING keys FOR ACTION ZrFiAcdoca~copy.
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
        append CORRESPONDING #( lt_source[ 1 ] ) to lt_target.
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

ENDCLASS.