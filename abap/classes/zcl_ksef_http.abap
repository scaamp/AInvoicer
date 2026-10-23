" Adapter transportu oparty na released API IF_WEB_HTTP_CLIENT.
CLASS zcl_ksef_http DEFINITION
  PUBLIC
  FINAL
  CREATE PUBLIC.

  PUBLIC SECTION.
    METHODS constructor
      " Klient HTTP jest wstrzykiwany, aby konfiguracja destination nie była ukryta.
      IMPORTING
        io_http_client TYPE REF TO if_web_http_client.

    INTERFACES zif_ksef_http.

  PRIVATE SECTION.
    DATA mo_http_client TYPE REF TO if_web_http_client.
ENDCLASS.

CLASS zcl_ksef_http IMPLEMENTATION.
  METHOD constructor.
    mo_http_client = io_http_client.
  ENDMETHOD.

  METHOD zif_ksef_http~send.
    " Implementacja żądania zostanie dodana po ustaleniu konfiguracji destination.
    " Typ klienta pochodzi z released API ABAP Cloud; tu nie wykonujemy requestu.
    RAISE EXCEPTION TYPE zcx_ksef_api.
  ENDMETHOD.
ENDCLASS.
