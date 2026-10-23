" Szkielet adaptera kryptograficznego zgodnego z kontraktem ZIF_KSEF_CRYPTO.
CLASS zcl_ksef_crypto DEFINITION
  PUBLIC
  FINAL
  CREATE PUBLIC.

  PUBLIC SECTION.
    INTERFACES zif_ksef_crypto.
ENDCLASS.

CLASS zcl_ksef_crypto IMPLEMENTATION.
  METHOD zif_ksef_crypto~encrypt.
    " Szyfrowanie zostanie zaimplementowane po wyborze obsługiwanych released API.
    RAISE EXCEPTION TYPE zcx_ksef_api.
  ENDMETHOD.
ENDCLASS.
