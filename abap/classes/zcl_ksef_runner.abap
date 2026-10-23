" Minimalny punkt uruchomieniowy ADT; nie wykonuje połączeń z KSeF.
CLASS zcl_ksef_runner DEFINITION
  PUBLIC
  FINAL
  CREATE PUBLIC.

  PUBLIC SECTION.
    INTERFACES if_oo_adt_classrun.
ENDCLASS.

CLASS zcl_ksef_runner IMPLEMENTATION.
  METHOD if_oo_adt_classrun~main.
    " Runner informuje o gotowości szkieletu bez wywoływania niegotowych usług.
    out->write( `Szkielet integracji KSeF jest gotowy do dalszej implementacji.` ).
  ENDMETHOD.
ENDCLASS.
