" Usługa cyklu życia sesji online i wsadowych KSeF.
CLASS zcl_ksef_session DEFINITION
  PUBLIC
  FINAL
  CREATE PUBLIC.

  PUBLIC SECTION.
    METHODS constructor
      " Sesja korzysta z transportu przez kontrakt, co ułatwia testy i podmianę.
      IMPORTING
        io_http TYPE REF TO zif_ksef_http.

    METHODS open_online
      " Otwiera sesję przeznaczoną do wysyłki online.
      RETURNING
        VALUE(rv_session_id) TYPE string
      RAISING
        zcx_ksef_api.

    METHODS open_batch
      " Otwiera sesję przeznaczoną do wysyłki wsadowej.
      RETURNING
        VALUE(rv_session_id) TYPE string
      RAISING
        zcx_ksef_api.

    METHODS close_session
      " Kończy wskazaną sesję KSeF.
      IMPORTING
        iv_session_id TYPE string
      RAISING
        zcx_ksef_api.

  PRIVATE SECTION.
    DATA mo_http TYPE REF TO zif_ksef_http.
ENDCLASS.

CLASS zcl_ksef_session IMPLEMENTATION.
  METHOD constructor.
    mo_http = io_http.
  ENDMETHOD.

  METHOD open_online.
    " Wywołanie endpointu otwierającego sesję online nie jest jeszcze implementowane.
    RAISE EXCEPTION TYPE zcx_ksef_api.
  ENDMETHOD.

  METHOD open_batch.
    " Wywołanie endpointu otwierającego sesję wsadową nie jest jeszcze implementowane.
    RAISE EXCEPTION TYPE zcx_ksef_api.
  ENDMETHOD.

  METHOD close_session.
    " Wywołanie endpointu zamykającego sesję nie jest jeszcze implementowane.
    RAISE EXCEPTION TYPE zcx_ksef_api.
  ENDMETHOD.
ENDCLASS.
