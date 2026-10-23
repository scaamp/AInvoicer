" Fasada integracji KSeF; składa usługi i udostępnia ich operacje konsumentom.
CLASS zcl_ksef_client DEFINITION
  PUBLIC
  FINAL
  CREATE PUBLIC.

  PUBLIC SECTION.
    METHODS constructor
      " Zależności są podawane z zewnątrz zgodnie z zasadą Dependency Injection.
      IMPORTING
        io_http   TYPE REF TO zif_ksef_http
        io_crypto TYPE REF TO zif_ksef_crypto.

    METHODS authenticate
      " Przekazuje dane uwierzytelniające do usługi AUTH.
      IMPORTING
        iv_auth_data          TYPE string
      RETURNING
        VALUE(rv_access_token) TYPE string
      RAISING
        zcx_ksef_api.

    METHODS refresh_token
      " Przekazuje token odświeżający do usługi AUTH.
      IMPORTING
        iv_refresh_token       TYPE string
      RETURNING
        VALUE(rv_access_token) TYPE string
      RAISING
        zcx_ksef_api.

    METHODS open_online_session
      " Otwiera sesję online przez usługę SESSION.
      RETURNING
        VALUE(rv_session_id) TYPE string
      RAISING
        zcx_ksef_api.

    METHODS open_batch_session
      " Otwiera sesję wsadową przez usługę SESSION.
      RETURNING
        VALUE(rv_session_id) TYPE string
      RAISING
        zcx_ksef_api.

    METHODS close_session
      " Zamyka sesję przez usługę SESSION.
      IMPORTING
        iv_session_id TYPE string
      RAISING
        zcx_ksef_api.

    METHODS submit_invoice
      " Przekazuje fakturę do usługi INVOICE.
      IMPORTING
        iv_session_id          TYPE string
        iv_invoice_payload     TYPE string
      RETURNING
        VALUE(rv_reference_number) TYPE string
      RAISING
        zcx_ksef_api.

    METHODS get_invoice_status
      " Zwraca status faktury z usługi INVOICE.
      IMPORTING
        iv_reference_number TYPE string
      RETURNING
        VALUE(rv_status)    TYPE string
      RAISING
        zcx_ksef_api.

    METHODS get_invoice_upo
      " Zwraca UPO pobrane przez usługę INVOICE.
      IMPORTING
        iv_reference_number TYPE string
      RETURNING
        VALUE(rv_upo)       TYPE string
      RAISING
        zcx_ksef_api.

    METHODS get_invoice
      " Zwraca treść faktury pobraną przez usługę INVOICE.
      IMPORTING
        iv_reference_number    TYPE string
      RETURNING
        VALUE(rv_invoice_payload) TYPE string
      RAISING
        zcx_ksef_api.

  PRIVATE SECTION.
    DATA mo_auth TYPE REF TO zcl_ksef_auth.
    DATA mo_session TYPE REF TO zcl_ksef_session.
    DATA mo_invoice TYPE REF TO zcl_ksef_invoice.
ENDCLASS.

CLASS zcl_ksef_client IMPLEMENTATION.
  METHOD constructor.
    mo_auth = NEW zcl_ksef_auth( io_http = io_http ).
    mo_session = NEW zcl_ksef_session( io_http = io_http ).
    mo_invoice = NEW zcl_ksef_invoice(
      io_http = io_http
      io_crypto = io_crypto ).
  ENDMETHOD.

  METHOD authenticate.
    rv_access_token = mo_auth->authenticate( iv_auth_data ).
  ENDMETHOD.

  METHOD refresh_token.
    rv_access_token = mo_auth->refresh_token( iv_refresh_token ).
  ENDMETHOD.

  METHOD open_online_session.
    rv_session_id = mo_session->open_online( ).
  ENDMETHOD.

  METHOD open_batch_session.
    rv_session_id = mo_session->open_batch( ).
  ENDMETHOD.

  METHOD close_session.
    mo_session->close_session( iv_session_id ).
  ENDMETHOD.

  METHOD submit_invoice.
    rv_reference_number = mo_invoice->submit(
      iv_session_id = iv_session_id
      iv_invoice_payload = iv_invoice_payload ).
  ENDMETHOD.

  METHOD get_invoice_status.
    rv_status = mo_invoice->get_status( iv_reference_number ).
  ENDMETHOD.

  METHOD get_invoice_upo.
    rv_upo = mo_invoice->get_upo( iv_reference_number ).
  ENDMETHOD.

  METHOD get_invoice.
    rv_invoice_payload = mo_invoice->get_invoice( iv_reference_number ).
  ENDMETHOD.
ENDCLASS.
