CLASS ltc_lock_probe DEFINITION FINAL FOR TESTING
  DURATION SHORT RISK LEVEL HARMLESS.
  PRIVATE SECTION.
    METHODS setup.
    METHODS teardown.
    METHODS seed_is_readable FOR TESTING.
    METHODS direct_takes_lock FOR TESTING.
    METHODS direct_lock_rejected FOR TESTING.
    METHODS static_inner_takes_lock FOR TESTING.
    METHODS static_inner_lock_rejected FOR TESTING.
    METHODS disabled_is_denied FOR TESTING.
    METHODS root_lock_cross_session FOR TESTING.
ENDCLASS.

CLASS ltc_lock_probe IMPLEMENTATION.
  METHOD setup.
    ROLLBACK ENTITIES.
    CLEAR: zbp_ainv_lock_probe=>reject_lock,
           zbp_ainv_lock_probe=>lock_calls,
           zbp_ainv_lock_probe=>direct_calls,
           zbp_ainv_lock_probe=>inner_calls,
           zbp_ainv_lock_probe=>locks_seen_by_inner,
           zbp_ainv_lock_probe=>authorization_calls.
    zbp_ainv_lock_probe=>enabled = abap_true.
  ENDMETHOD.

  METHOD teardown.
    ROLLBACK ENTITIES.
    zbp_ainv_lock_probe=>enabled = abap_false.
  ENDMETHOD.

  METHOD seed_is_readable.
    READ ENTITIES OF zr_ainv_lock_probe ENTITY Probe
      ALL FIELDS WITH VALUE #(
        ( InvoiceUUID = '56B09C124EE51FD1B4AC56570F4D34C8' ) )
      RESULT DATA(rows) FAILED DATA(failure).
    cl_abap_unit_assert=>assert_initial( failure-probe ).
    cl_abap_unit_assert=>assert_equals( act = lines( rows ) exp = 1 ).
  ENDMETHOD.

  METHOD direct_takes_lock.
    MODIFY ENTITIES OF zr_ainv_lock_probe ENTITY Probe
      EXECUTE Direct FROM VALUE #(
        ( InvoiceUUID = '56B09C124EE51FD1B4AC56570F4D34C8' ) )
      FAILED DATA(failure).
    cl_abap_unit_assert=>assert_initial( failure-probe ).
    cl_abap_unit_assert=>assert_equals( act = zbp_ainv_lock_probe=>lock_calls exp = 1 ).
    cl_abap_unit_assert=>assert_equals( act = zbp_ainv_lock_probe=>direct_calls exp = 1 ).
  ENDMETHOD.

  METHOD direct_lock_rejected.
    zbp_ainv_lock_probe=>reject_lock = abap_true.
    MODIFY ENTITIES OF zr_ainv_lock_probe ENTITY Probe
      EXECUTE Direct FROM VALUE #(
        ( InvoiceUUID = '56B09C124EE51FD1B4AC56570F4D34C8' ) )
      FAILED DATA(failure).
    cl_abap_unit_assert=>assert_not_initial( failure-probe ).
    cl_abap_unit_assert=>assert_equals( act = zbp_ainv_lock_probe=>direct_calls exp = 0 ).
  ENDMETHOD.

  METHOD static_inner_takes_lock.
    MODIFY ENTITIES OF zr_ainv_lock_probe ENTITY Probe
      EXECUTE ViaStatic FROM VALUE #( ( %cid = 'PROBE' ) )
      FAILED DATA(failure).
    cl_abap_unit_assert=>assert_initial( failure-probe ).
    cl_abap_unit_assert=>assert_equals(
      act = zbp_ainv_lock_probe=>locks_seen_by_inner exp = 1
      msg = 'Static -> internal must lock before inner handler' ).
    cl_abap_unit_assert=>assert_equals( act = zbp_ainv_lock_probe=>inner_calls exp = 1 ).
  ENDMETHOD.

  METHOD static_inner_lock_rejected.
    zbp_ainv_lock_probe=>reject_lock = abap_true.
    MODIFY ENTITIES OF zr_ainv_lock_probe ENTITY Probe
      EXECUTE ViaStatic FROM VALUE #( ( %cid = 'PROBE' ) )
      FAILED DATA(failure).
    cl_abap_unit_assert=>assert_not_initial(
      act = failure-probe msg = 'Rejected lock must reject static/internal path' ).
    cl_abap_unit_assert=>assert_equals( act = zbp_ainv_lock_probe=>inner_calls exp = 0 ).
  ENDMETHOD.

  METHOD disabled_is_denied.
    zbp_ainv_lock_probe=>enabled = abap_false.
    MODIFY ENTITIES OF zr_ainv_lock_probe ENTITY Probe
      EXECUTE ViaStatic FROM VALUE #( ( %cid = 'PROBE' ) )
      FAILED DATA(failure).
    cl_abap_unit_assert=>assert_not_initial( failure-probe ).
    cl_abap_unit_assert=>assert_equals( act = zbp_ainv_lock_probe=>inner_calls exp = 0 ).
  ENDMETHOD.

  METHOD root_lock_cross_session.
    " Pierwsza LUW trzyma rzeczywisty managed root; zadanie próbuje ten sam klucz.
    SET LOCKS OF zr_ainv_inv_h ENTITY ZrAinvInvH
      FROM VALUE #( ( InvoiceUUID = '56B09C124EE51FD1B4AC56570F4D34C8' ) )
      FAILED DATA(failure).
    cl_abap_unit_assert=>assert_initial( failure-zrainvinvh ).

    DATA(runner) = NEW cl_abap_parallel(
      p_num_tasks = 1 p_timeout = 15 p_local_server = abap_true ).
    DATA(tasks) = VALUE cl_abap_parallel=>t_in_inst_tab(
      ( NEW zcl_ainv_lock_task( ) ) ).
    runner->run_inst( EXPORTING p_in_tab = tasks p_debug = abap_false
                      IMPORTING p_out_tab = DATA(outputs) ).
    cl_abap_unit_assert=>assert_equals( act = lines( outputs ) exp = 1 ).
    cl_abap_unit_assert=>assert_initial( outputs[ 1 ]-message ).
    DATA(task) = CAST zcl_ainv_lock_task( outputs[ 1 ]-inst ).
    cl_abap_unit_assert=>assert_equals( act = task->separate_context exp = abap_true ).
    cl_abap_unit_assert=>assert_equals(
      act = task->lock_rejected exp = abap_true
      msg = 'Other session must reject held root lock' ).
    cl_abap_unit_assert=>assert_equals( act = task->lock_acquired exp = abap_false ).
    ROLLBACK ENTITIES.
    tasks = VALUE #( ( NEW zcl_ainv_lock_task( ) ) ).
    runner->run_inst( EXPORTING p_in_tab = tasks p_debug = abap_false
                      IMPORTING p_out_tab = outputs ).
    cl_abap_unit_assert=>assert_initial( outputs[ 1 ]-message ).
    task = CAST #( outputs[ 1 ]-inst ).
    cl_abap_unit_assert=>assert_equals(
      act = task->lock_acquired exp = abap_true
      msg = 'After rollback another session must acquire root lock' ).
  ENDMETHOD.
ENDCLASS.



