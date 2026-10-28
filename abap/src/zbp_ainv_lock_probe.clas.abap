CLASS zbp_ainv_lock_probe DEFINITION
  PUBLIC ABSTRACT FINAL FOR BEHAVIOR OF zr_ainv_lock_probe.
  PUBLIC SECTION.
    " Wyłącznie instrumentacja prototypu bez OData i operacji zapisu.
    CLASS-DATA enabled TYPE abap_bool.
    CLASS-DATA reject_lock TYPE abap_bool.
    CLASS-DATA lock_calls TYPE i.
    CLASS-DATA direct_calls TYPE i.
    CLASS-DATA inner_calls TYPE i.
    CLASS-DATA locks_seen_by_inner TYPE i.
    CLASS-DATA authorization_calls TYPE i.
ENDCLASS.

CLASS zbp_ainv_lock_probe IMPLEMENTATION.
ENDCLASS.

