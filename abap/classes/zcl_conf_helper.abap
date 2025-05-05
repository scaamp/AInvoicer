CLASS zcl_conf_helper DEFINITION
  PUBLIC
  FINAL
  CREATE PUBLIC .

  PUBLIC SECTION.
    INTERFACES if_oo_adt_classrun.

  PROTECTED SECTION.
  PRIVATE SECTION.
    METHODS fill_zfi_acdoca.
ENDCLASS.



CLASS ZCL_CONF_HELPER IMPLEMENTATION.


  METHOD fill_zfi_acdoca.
    DATA: lt_data       TYPE STANDARD TABLE OF zfi_acdoca,
          lv_count      TYPE i,
          lv_created_at TYPE tzntstmpl.

    CONSTANTS: lc_total TYPE i VALUE 1000.

    LV_CREATED_AT = SY-uzeit.

    DO lc_total TIMES.

      DATA(lo_date_gen) = cl_abap_random_int=>create(
      seed = cl_abap_random=>seed( )
      min  = 0
      max  = 1000 ).

      DATA(lv_offset_days) = lo_date_gen->get_next( ).
      DATA(lv_date) = sy-datum - lv_offset_days.

      DATA(lo_year_gen) = cl_abap_random_int=>create(
      seed = cl_abap_random=>seed( )
      min  = 0
      max  = 4 ).

      DATA(lv_random_year_suffix) = lo_year_gen->get_next( ).
      DATA(fiscal_year) = |202{ lv_random_year_suffix }|.

      DATA(lo_acc_gen) = cl_abap_random_int=>create(
      seed = cl_abap_random=>seed( )
      min  = 300000
      max  = 400000 ).

      DATA(lv_account_number) = lo_acc_gen->get_next( ).
      DATA account TYPE char10.
      account = |{ lv_account_number }|.

      DATA(lo_vendor_gen)     = cl_abap_random_int=>create( seed = cl_abap_random=>seed( ) min = 1     max = 9999 ).
      DATA(lo_customer_gen)   = cl_abap_random_int=>create( seed = cl_abap_random=>seed( ) min = 1     max = 9999 ).
      DATA(lo_cc_gen)         = cl_abap_random_int=>create( seed = cl_abap_random=>seed( ) min = 100   max = 999 ).
      DATA(lo_pc_gen)         = cl_abap_random_int=>create( seed = cl_abap_random=>seed( ) min = 1     max = 99 ).
      DATA(lo_seg_gen)        = cl_abap_random_int=>create( seed = cl_abap_random=>seed( ) min = 1     max = 9 ).
      DATA(lo_amount_gen)     = cl_abap_random_int=>create( seed = cl_abap_random=>seed( ) min = 100   max = 99999 ).

      APPEND VALUE #(
        client          = sy-mandt
        company_code    = '7777'
        fiscal_year     = fiscal_year
        document_no     = sy-index
        line_item       = |001|
        posting_date    = lv_date
        document_date   = lv_date
        entry_date      = sy-datum
        document_type   = 'KR'
        reference       = |INV{ sy-index }|
        debit_credit    = COND #( WHEN sy-index MOD 2 = 0 THEN 'S' ELSE 'H' )
        account         = account
        vendor          = lo_vendor_gen->get_next( )
        customer        = lo_customer_gen->get_next( )
        cost_center     = lo_cc_gen->get_next( )
        profit_center   = lo_pc_gen->get_next( )
        segment         = lo_seg_gen->get_next( )
        currency_code   = 'PLN'
        amount_document = lo_amount_gen->get_next( )
        amount_local    = lo_amount_gen->get_next( )
        payment_terms   = 'ZB01'
        payment_method  = 'T'
        due_date        = lv_date + 30
        clearing_doc    = ''
        clearing_date   = '00000000'
        created_by      = sy-uname
        created_at      = '20250429153000.000000'

      ) TO lt_data.

      ADD 1 TO lv_count.


    ENDDO.

    INSERT zfi_acdoca FROM TABLE @lt_data.

*    WRITE: / 'All done. Total records:', lv_count.
  ENDMETHOD.


  METHOD if_oo_adt_classrun~main.
    DATA lo_sc4mp TYPE REF TO zcl_conf_helper.
    lo_sc4mp = NEW zcl_conf_helper( ).

*    delete from zfi_acdoca where company_code = '7777'.
    delete from zfi_acdoca_d where companycode <> '7777'.
*    lo_sc4mp->fill_zfi_acdoca(  ).
  ENDMETHOD.
ENDCLASS.