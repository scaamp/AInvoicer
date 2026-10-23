" Kontrakt kryptograficzny; pozwala podmienić implementację bez zmian klienta.
INTERFACE zif_ksef_crypto PUBLIC.
  METHODS encrypt
    " Przygotowuje dane do szyfrowania zgodnie z wymaganiami KSeF.
    IMPORTING
      iv_payload       TYPE string
    RETURNING
      VALUE(rv_result) TYPE string
    RAISING
      zcx_ksef_api.
ENDINTERFACE.
