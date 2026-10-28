CLASS lhc_probe DEFINITION INHERITING FROM cl_abap_behavior_handler.
  PRIVATE SECTION.
    METHODS read FOR READ IMPORTING keys FOR READ Probe RESULT result.
    METHODS lock FOR LOCK IMPORTING keys FOR LOCK Probe.
    METHODS authorize FOR GLOBAL AUTHORIZATION
      IMPORTING REQUEST requested_authorizations FOR Probe RESULT result.
    METHODS direct FOR MODIFY IMPORTING keys FOR ACTION Probe~Direct.
    METHODS via_static FOR MODIFY IMPORTING keys FOR ACTION Probe~ViaStatic.
    METHODS inner FOR MODIFY IMPORTING keys FOR ACTION Probe~Inner.
ENDCLASS.

CLASS lhc_probe IMPLEMENTATION.
  METHOD read.
    " Prototyp odczytuje wyłącznie klucz i wersję; nigdy nie zmienia tabeli.
    IF keys IS INITIAL.
      RETURN.
    ENDIF.
    SELECT FROM zr_ainv_lock_probe
      FIELDS InvoiceUUID, LocalLastChangedAt
      FOR ALL ENTRIES IN @keys
      WHERE InvoiceUUID = @keys-InvoiceUUID
      INTO CORRESPONDING FIELDS OF TABLE @result.
  ENDMETHOD.

  METHOD lock.
    " Licznik wykrywa wejście do orkiestracji LOCK, nie udaje realnego enqueue.
    zbp_ainv_lock_probe=>lock_calls += 1.
    IF zbp_ainv_lock_probe=>reject_lock = abap_true.
      LOOP AT keys INTO DATA(key).
        APPEND VALUE #( InvoiceUUID = key-InvoiceUUID
                        %fail-cause = if_abap_behv=>cause-locked ) TO failed-probe.
        APPEND VALUE #( InvoiceUUID = key-InvoiceUUID
          %msg = new_message_with_text( severity = if_abap_behv_message=>severity-error
                                       text = 'LOCK_PROBE_REJECTED' ) ) TO reported-probe.
      ENDLOOP.
    ENDIF.
  ENDMETHOD.

  METHOD authorize.
    " Wyłącznie bramka testu: domyślnie odmowa, brak publicznej usługi i zapisu.
    zbp_ainv_lock_probe=>authorization_calls += 1.
    DATA(permission) = COND #( WHEN zbp_ainv_lock_probe=>enabled = abap_true
                              THEN if_abap_behv=>auth-allowed
                              ELSE if_abap_behv=>auth-unauthorized ).
    result-%action-Direct = permission.
    result-%action-ViaStatic = permission.
  ENDMETHOD.

  METHOD direct.
    zbp_ainv_lock_probe=>direct_calls += lines( keys ).
  ENDMETHOD.

  METHOD via_static.
    " Kontrola poprzedza LOCAL MODE; badamy wyłącznie orkiestrację blokowania.
    IF zbp_ainv_lock_probe=>enabled <> abap_true.
      failed-probe = VALUE #( FOR key IN keys
        ( %cid = key-%cid %fail-cause = if_abap_behv=>cause-unauthorized ) ).
      RETURN.
    ENDIF.
    MODIFY ENTITIES OF zr_ainv_lock_probe IN LOCAL MODE
      ENTITY Probe EXECUTE Inner FROM VALUE #(
        ( InvoiceUUID = '56B09C124EE51FD1B4AC56570F4D34C8' ) )
      FAILED DATA(inner_failed) REPORTED DATA(inner_reported).
    IF inner_failed-probe IS NOT INITIAL.
      failed-probe = VALUE #( FOR key IN keys
        ( %cid = key-%cid %fail-cause = if_abap_behv=>cause-unspecific ) ).
    ENDIF.
  ENDMETHOD.

  METHOD inner.
    zbp_ainv_lock_probe=>inner_calls += lines( keys ).
    zbp_ainv_lock_probe=>locks_seen_by_inner = zbp_ainv_lock_probe=>lock_calls.
  ENDMETHOD.
ENDCLASS.

CLASS lsc_probe DEFINITION INHERITING FROM cl_abap_behavior_saver.
  PROTECTED SECTION.
    METHODS finalize REDEFINITION.
    METHODS check_before_save REDEFINITION.
    METHODS save REDEFINITION.
    METHODS cleanup REDEFINITION.
    METHODS cleanup_finalize REDEFINITION.
ENDCLASS.

CLASS lsc_probe IMPLEMENTATION.
  METHOD finalize.
  ENDMETHOD.
  METHOD check_before_save.
  ENDMETHOD.
  METHOD save.
    " Brak persystencji w prototypie.
  ENDMETHOD.
  METHOD cleanup.
  ENDMETHOD.
  METHOD cleanup_finalize.
  ENDMETHOD.
ENDCLASS.


