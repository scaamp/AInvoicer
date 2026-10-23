" Usługa uwierzytelniania KSeF; nie przechowuje tokenów ani sekretów w tym szkielecie.
CLASS zcl_ksef_auth DEFINITION
  PUBLIC
  FINAL
  CREATE PUBLIC.

  PUBLIC SECTION.
    METHODS constructor
      " Transport jest zależnością, a nie globalnym singletonem.
      IMPORTING
        io_http TYPE REF TO zif_ksef_http.

    METHODS authenticate
      " Rozpoczyna proces uzyskania tokenu dostępowego.
      IMPORTING
        iv_auth_data       TYPE string
      RETURNING
        VALUE(rv_access_token) TYPE string
      RAISING
        zcx_ksef_api.

    METHODS refresh_token
      " Odświeża token dostępowy przy użyciu tokenu odświeżającego.
      IMPORTING
        iv_refresh_token  TYPE string
      RETURNING
        VALUE(rv_access_token) TYPE string
      RAISING
        zcx_ksef_api.

  PRIVATE SECTION.
    DATA mo_http TYPE REF TO zif_ksef_http.
ENDCLASS.

CLASS zcl_ksef_auth IMPLEMENTATION.
  METHOD constructor.
    mo_http = io_http.
  ENDMETHOD.

  METHOD authenticate.
    " Wymiana danych uwierzytelniających na token nie jest jeszcze implementowana.
    RAISE EXCEPTION TYPE zcx_ksef_api.
  ENDMETHOD.

  METHOD refresh_token.
    " Odświeżanie tokenu nie jest jeszcze implementowane.
    RAISE EXCEPTION TYPE zcx_ksef_api.
  ENDMETHOD.
ENDCLASS.
