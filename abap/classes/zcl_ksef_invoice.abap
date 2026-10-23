" Usługa wysyłania faktur oraz odczytu ich statusu, UPO i treści.
CLASS zcl_ksef_invoice DEFINITION
  PUBLIC
  FINAL
  CREATE PUBLIC.

  PUBLIC SECTION.
    METHODS constructor
      " Transport i kryptografia są jawnie wstrzykiwanymi zależnościami.
      IMPORTING
        io_http   TYPE REF TO zif_ksef_http
        io_crypto TYPE REF TO zif_ksef_crypto.

    METHODS submit
      " Przyjmuje fakturę i zwraca identyfikator nadany przez KSeF.
      IMPORTING
        iv_session_id       TYPE string
        iv_invoice_payload  TYPE string
      RETURNING
        VALUE(rv_reference_number) TYPE string
      RAISING
        zcx_ksef_api.

    METHODS get_status
      " Pobiera status przetwarzania faktury.
      IMPORTING
        iv_reference_number TYPE string
      RETURNING
        VALUE(rv_status)    TYPE string
      RAISING
        zcx_ksef_api.

    METHODS get_upo
      " Pobiera urzędowe poświadczenie odbioru faktury.
      IMPORTING
        iv_reference_number TYPE string
      RETURNING
        VALUE(rv_upo)       TYPE string
      RAISING
        zcx_ksef_api.

    METHODS get_invoice
      " Pobiera fakturę wskazaną numerem referencyjnym.
      IMPORTING
        iv_reference_number TYPE string
      RETURNING
        VALUE(rv_invoice_payload) TYPE string
      RAISING
        zcx_ksef_api.

  PRIVATE SECTION.
    DATA mo_http TYPE REF TO zif_ksef_http.
    DATA mo_crypto TYPE REF TO zif_ksef_crypto.
ENDCLASS.

CLASS zcl_ksef_invoice IMPLEMENTATION.
  METHOD constructor.
    mo_http = io_http.
    mo_crypto = io_crypto.
  ENDMETHOD.

  METHOD submit.
    " Szyfrowanie i wysłanie faktury zostaną dodane w kolejnych etapach.
    RAISE EXCEPTION TYPE zcx_ksef_api.
  ENDMETHOD.

  METHOD get_status.
    " Odczyt statusu po numerze referencyjnym nie jest jeszcze implementowany.
    RAISE EXCEPTION TYPE zcx_ksef_api.
  ENDMETHOD.

  METHOD get_upo.
    " Pobieranie UPO nie jest jeszcze implementowane.
    RAISE EXCEPTION TYPE zcx_ksef_api.
  ENDMETHOD.

  METHOD get_invoice.
    " Pobieranie faktury nie jest jeszcze implementowane.
    RAISE EXCEPTION TYPE zcx_ksef_api.
  ENDMETHOD.
ENDCLASS.
