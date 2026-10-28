CLASS zcl_ainv_lock_task DEFINITION PUBLIC FINAL CREATE PUBLIC.
  PUBLIC SECTION.
    INTERFACES if_abap_parallel.
    DATA lock_rejected TYPE abap_bool.
    DATA lock_acquired TYPE abap_bool.
    DATA separate_context TYPE abap_bool.
ENDCLASS.

CLASS zcl_ainv_lock_task IMPLEMENTATION.
  METHOD if_abap_parallel~do.
    " Odrębna sesja sprawdza rzeczywisty enqueue istniejącego managed root.
    " Nie wykonuje CREATE/UPDATE/DELETE ani COMMIT; zwalnia wyłącznie własną LUW.
    separate_context = xsdbool( zbp_ainv_lock_probe=>enabled = abap_false ).
    SET LOCKS OF zr_ainv_inv_h ENTITY ZrAinvInvH
      FROM VALUE #( ( InvoiceUUID = '56B09C124EE51FD1B4AC56570F4D34C8' ) )
      FAILED DATA(failure) REPORTED DATA(messages).
    lock_acquired = xsdbool( failure-zrainvinvh IS INITIAL ).
    lock_rejected = xsdbool( line_exists(
      failure-zrainvinvh[ %fail-cause = if_abap_behv=>cause-locked ] ) ).
    ROLLBACK ENTITIES.
  ENDMETHOD.
ENDCLASS.

