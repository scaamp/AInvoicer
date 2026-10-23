" Kontrakt transportu HTTP: oddziela logikę KSeF od szczegółów komunikacji.
INTERFACE zif_ksef_http PUBLIC.
  METHODS send
    " Wysyła żądanie do wskazanej ścieżki; szczegóły transportu pozostają w adapterze.
    IMPORTING
      iv_method       TYPE string
      iv_path         TYPE string
      iv_body         TYPE string OPTIONAL
    RETURNING
      VALUE(rv_body)  TYPE string
    RAISING
      zcx_ksef_api.
ENDINTERFACE.
