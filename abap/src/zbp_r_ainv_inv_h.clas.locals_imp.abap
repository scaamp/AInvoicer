CLASS lcl_ainv_auth DEFINITION FINAL.
  PUBLIC SECTION.
    CLASS-METHODS allowed IMPORTING activity TYPE c
      nip TYPE zainv_nip OPTIONAL company TYPE zainv_buk OPTIONAL
      RETURNING VALUE(is_allowed) TYPE abap_bool.
ENDCLASS.

CLASS lcl_ainv_auth IMPLEMENTATION.
  METHOD allowed.
    " SAP AUTHORITY-CHECK: globalnie sprawdza czynność, instancyjnie NIP i spółkę.
    " Seam istnieje wyłącznie dla ABAP Unit; brak konfiguracji domyślnie odmawia.
    TEST-SEAM ainv_authority.
      IF nip IS INITIAL AND company IS INITIAL.
        AUTHORITY-CHECK OBJECT 'Z_AINV_KSF'
          ID 'ACTVT' FIELD activity ID 'ZAINV_NIP' DUMMY ID 'ZAINV_BUK' DUMMY.
      ELSEIF nip IS NOT INITIAL AND company IS NOT INITIAL.
        AUTHORITY-CHECK OBJECT 'Z_AINV_KSF'
          ID 'ACTVT' FIELD activity ID 'ZAINV_NIP' FIELD nip ID 'ZAINV_BUK' FIELD company.
      ELSE.
        is_allowed = abap_false.
        RETURN.
      ENDIF.
      is_allowed = xsdbool( sy-subrc = 0 ).
    END-TEST-SEAM.
  ENDMETHOD.
ENDCLASS.

CLASS lhc_zr_ainv_inv_h DEFINITION INHERITING FROM cl_abap_behavior_handler.
  PRIVATE SECTION.
    METHODS get_global_authorizations FOR GLOBAL AUTHORIZATION
      IMPORTING REQUEST requested_authorizations FOR ZrAinvInvH RESULT result.
    METHODS get_instance_authorizations FOR INSTANCE AUTHORIZATION
      IMPORTING keys REQUEST requested_authorizations FOR ZrAinvInvH RESULT result.
    METHODS precheck_create FOR PRECHECK IMPORTING entities FOR CREATE ZrAinvInvH.
    METHODS precheck_update FOR PRECHECK IMPORTING entities FOR UPDATE ZrAinvInvH.
    METHODS apply_result FOR MODIFY
      IMPORTING keys FOR ACTION ZrAinvInvH~ApplyKsefResult RESULT result.
    METHODS apply_locked FOR MODIFY
      IMPORTING keys FOR ACTION ZrAinvInvH~ApplyKsefLocked RESULT result.
ENDCLASS.

CLASS lhc_zr_ainv_inv_h IMPLEMENTATION.
  METHOD get_global_authorizations.
    " Czynność 16 nie daje prawa do zwykłego CREATE/UPDATE/DELETE ani dzieci.
    result-%create = COND #( WHEN lcl_ainv_auth=>allowed( '01' )
      THEN if_abap_behv=>auth-allowed ELSE if_abap_behv=>auth-unauthorized ).
    result-%update = COND #( WHEN lcl_ainv_auth=>allowed( '02' )
      THEN if_abap_behv=>auth-allowed ELSE if_abap_behv=>auth-unauthorized ).
    result-%delete = COND #( WHEN lcl_ainv_auth=>allowed( '06' )
      THEN if_abap_behv=>auth-allowed ELSE if_abap_behv=>auth-unauthorized ).
    result-%action-ApplyKsefResult = COND #( WHEN lcl_ainv_auth=>allowed( '16' )
      THEN if_abap_behv=>auth-allowed ELSE if_abap_behv=>auth-unauthorized ).
  ENDMETHOD.

  METHOD get_instance_authorizations.
    " Odczyt przedobrazu służy tylko kontroli praw; nie zwraca danych konsumentowi.
    " Przed LOCAL MODE jawnie sprawdzamy posiadanie jakiegokolwiek prawa CRUD.
    IF lcl_ainv_auth=>allowed( '01' ) = abap_false
       AND lcl_ainv_auth=>allowed( '02' ) = abap_false
       AND lcl_ainv_auth=>allowed( '06' ) = abap_false.
      result = VALUE #( FOR key IN keys ( %tky = key-%tky
        %update = if_abap_behv=>auth-unauthorized
        %delete = if_abap_behv=>auth-unauthorized ) ).
      RETURN.
    ENDIF.
    READ ENTITIES OF zr_ainv_inv_h IN LOCAL MODE ENTITY ZrAinvInvH
      FIELDS ( SellerNip BuyerNip CompanyCode Direction )
      WITH CORRESPONDING #( keys ) RESULT DATA(headers).
    LOOP AT headers INTO DATA(header).
      DATA(nip) = CONV zainv_nip( COND #( WHEN header-Direction = 'OUTBOUND'
        THEN header-SellerNip WHEN header-Direction = 'INBOUND' THEN header-BuyerNip ) ).
      APPEND VALUE #( %tky = header-%tky
        %update = COND #( WHEN lcl_ainv_auth=>allowed(
          activity = '02' nip = nip company = header-CompanyCode )
          THEN if_abap_behv=>auth-allowed ELSE if_abap_behv=>auth-unauthorized )
        %delete = COND #( WHEN lcl_ainv_auth=>allowed(
          activity = '06' nip = nip company = header-CompanyCode )
          THEN if_abap_behv=>auth-allowed ELSE if_abap_behv=>auth-unauthorized )
        ) TO result.
    ENDLOOP.
  ENDMETHOD.

  METHOD precheck_create.
    " Autoryzujemy wartości wejściowe CREATE, których nie zna kontrola globalna.
    LOOP AT entities INTO DATA(entity).
      DATA(nip) = CONV zainv_nip( COND #( WHEN entity-Direction = 'OUTBOUND'
        THEN entity-SellerNip WHEN entity-Direction = 'INBOUND' THEN entity-BuyerNip ) ).
      IF lcl_ainv_auth=>allowed( activity = '01' nip = nip
          company = entity-CompanyCode ) = abap_false.
        APPEND VALUE #( %cid = entity-%cid %fail-cause = if_abap_behv=>cause-unauthorized )
          TO failed-zrainvinvh.
        APPEND VALUE #( %cid = entity-%cid
          %msg = new_message_with_text( severity = if_abap_behv_message=>severity-error
                                       text = 'INVOICE_SCOPE_DENIED' ) ) TO reported-zrainvinvh.
      ENDIF.
    ENDLOOP.
  ENDMETHOD.

  METHOD precheck_update.
    " UPDATE nie może przenieść faktury do nieautoryzowanego NIP/spółki.
    IF lcl_ainv_auth=>allowed( '02' ) = abap_false.
      failed-zrainvinvh = VALUE #( FOR requested IN entities
        ( %tky = requested-%tky %fail-cause = if_abap_behv=>cause-unauthorized ) ).
      RETURN.
    ENDIF.
    READ ENTITIES OF zr_ainv_inv_h IN LOCAL MODE ENTITY ZrAinvInvH
      ALL FIELDS WITH CORRESPONDING #( entities ) RESULT DATA(headers).
    LOOP AT entities INTO DATA(entity).
      READ TABLE headers INTO DATA(header) WITH TABLE KEY id COMPONENTS InvoiceUUID = entity-InvoiceUUID.
      IF sy-subrc <> 0.
        CONTINUE.
      ENDIF.
      DATA(old_nip) = CONV zainv_nip( COND #( WHEN header-Direction = 'OUTBOUND'
        THEN header-SellerNip WHEN header-Direction = 'INBOUND' THEN header-BuyerNip ) ).
      DATA(old_company) = header-CompanyCode.
      IF entity-%control-CompanyCode = if_abap_behv=>mk-on.
        header-CompanyCode = entity-CompanyCode.
      ENDIF.
      IF entity-%control-Direction = if_abap_behv=>mk-on.
        header-Direction = entity-Direction.
      ENDIF.
      IF entity-%control-SellerNip = if_abap_behv=>mk-on.
        header-SellerNip = entity-SellerNip.
      ENDIF.
      IF entity-%control-BuyerNip = if_abap_behv=>mk-on.
        header-BuyerNip = entity-BuyerNip.
      ENDIF.
      DATA(new_nip) = CONV zainv_nip( COND #( WHEN header-Direction = 'OUTBOUND'
        THEN header-SellerNip WHEN header-Direction = 'INBOUND' THEN header-BuyerNip ) ).
      IF lcl_ainv_auth=>allowed( activity = '02' nip = old_nip company = old_company ) = abap_false
        OR lcl_ainv_auth=>allowed( activity = '02' nip = new_nip company = header-CompanyCode ) = abap_false.
        APPEND VALUE #( %tky = entity-%tky %fail-cause = if_abap_behv=>cause-unauthorized )
          TO failed-zrainvinvh.
        APPEND VALUE #( %tky = entity-%tky
          %msg = new_message_with_text( severity = if_abap_behv_message=>severity-error
                                       text = 'INVOICE_SCOPE_DENIED' ) ) TO reported-zrainvinvh.
      ENDIF.
    ENDLOOP.
  ENDMETHOD.

  METHOD apply_result.
    " Wejście publiczne: konkretne prawa sprawdzamy przed wywołaniem LOCAL MODE.
    " Pierwszy kontrakt obsługuje jedno polecenie na wywołanie; bez masowego EXECUTE.
    IF lines( keys ) <> 1.
      failed-zrainvinvh = VALUE #( FOR item IN keys
        ( %cid = item-%cid %fail-cause = if_abap_behv=>cause-unspecific ) ).
      RETURN.
    ENDIF.
    DATA(key) = keys[ 1 ].
      DATA(request) = key-%param.
      IF request-InvoiceUUID IS INITIAL OR request-WriteRequestId IS INITIAL
        OR NOT matches( val = request-KsefNumber pcre = '[0-9A-Za-z-]{1,50}' )
        OR request-Environment <> 'TEST' OR request-ExpectedLocalLastChangedAt IS INITIAL
        OR request-CompanyCode IS INITIAL
        OR NOT matches( val = request-TaxpayerNip pcre = '[0-9]{10}' ).
        APPEND VALUE #( %cid = key-%cid %fail-cause = if_abap_behv=>cause-unspecific )
          TO failed-zrainvinvh.
        APPEND VALUE #( %cid = key-%cid
          %msg = new_message_with_text( severity = if_abap_behv_message=>severity-error
                                       text = 'INVALID_KSEF_RESULT_REQUEST' ) ) TO reported-zrainvinvh.
        RETURN.
      ENDIF.
      IF lcl_ainv_auth=>allowed( activity = '16' nip = request-TaxpayerNip
          company = request-CompanyCode ) = abap_false.
        APPEND VALUE #( %cid = key-%cid %fail-cause = if_abap_behv=>cause-unauthorized )
          TO failed-zrainvinvh.
        APPEND VALUE #( %cid = key-%cid
          %msg = new_message_with_text( severity = if_abap_behv_message=>severity-error
                                       text = 'KSEF_RESULT_NOT_AUTHORIZED' ) ) TO reported-zrainvinvh.
        RETURN.
      ENDIF.
      MODIFY ENTITIES OF zr_ainv_inv_h IN LOCAL MODE ENTITY ZrAinvInvH
        EXECUTE ApplyKsefLocked FROM VALUE #(
          ( InvoiceUUID = request-InvoiceUUID %param = request ) )
        RESULT DATA(inner_result) FAILED DATA(inner_failed) REPORTED DATA(inner_reported).
      IF inner_failed-zrainvinvh IS NOT INITIAL OR inner_result IS INITIAL.
        APPEND VALUE #( %cid = key-%cid %fail-cause = if_abap_behv=>cause-unspecific )
          TO failed-zrainvinvh.
        APPEND VALUE #( %cid = key-%cid
          %msg = new_message_with_text( severity = if_abap_behv_message=>severity-error
                                       text = 'KSEF_RESULT_NOT_APPLIED' ) ) TO reported-zrainvinvh.
        RETURN.
      ENDIF.
      APPEND VALUE #( %cid = key-%cid %param = inner_result[ 1 ]-%param ) TO result.
  ENDMETHOD.

  METHOD apply_locked.
    " Domyślna blokada akcji instancyjnej poprzedza handler (prototyp H01).
    " CAS i idempotencja są wykonywane pod tą blokadą w tej samej RAP LUW.
    " Pierwszy kontrakt obsługuje jedno polecenie na wywołanie; bez masowego EXECUTE.
    IF lines( keys ) <> 1.
      failed-zrainvinvh = VALUE #( FOR item IN keys
        ( %tky = item-%tky %fail-cause = if_abap_behv=>cause-unspecific ) ).
      RETURN.
    ENDIF.
    DATA(key) = keys[ 1 ].
      DATA(request) = key-%param.
      IF lcl_ainv_auth=>allowed( activity = '16' nip = request-TaxpayerNip
          company = request-CompanyCode ) = abap_false.
        APPEND VALUE #( %tky = key-%tky %fail-cause = if_abap_behv=>cause-unauthorized )
          TO failed-zrainvinvh.
        RETURN.
      ENDIF.
      READ ENTITIES OF zr_ainv_inv_h IN LOCAL MODE ENTITY ZrAinvInvH
        ALL FIELDS WITH VALUE #( ( %tky = key-%tky ) )
        RESULT DATA(headers).
      IF headers IS INITIAL.
        APPEND VALUE #( %tky = key-%tky %fail-cause = if_abap_behv=>cause-not_found )
          TO failed-zrainvinvh.
        RETURN.
      ENDIF.
      DATA(header) = headers[ 1 ].
      IF header-Direction <> 'OUTBOUND' OR header-CompanyCode <> request-CompanyCode
        OR header-SellerNip <> request-TaxpayerNip
        OR request-InvoiceUUID <> header-InvoiceUUID OR request-Environment <> 'TEST'.
        APPEND VALUE #( %tky = key-%tky %fail-cause = if_abap_behv=>cause-unauthorized )
          TO failed-zrainvinvh.
        RETURN.
      ENDIF.
      DATA(outcome) = VALUE za_ainv_ksf_res(
        InvoiceUUID = header-InvoiceUUID KsefNumber = header-KsefNumber
        KsefEnvironment = header-KsefEnvironment KsefRecordedAt = header-KsefRecordedAt
        KsefWriteRequestId = header-KsefWriteRequestId ).
      IF header-KsefNumber IS NOT INITIAL.
        IF header-KsefWriteRequestId = request-WriteRequestId
          AND ( header-KsefNumber <> request-KsefNumber
             OR header-KsefEnvironment <> request-Environment ).
          outcome-Outcome = 'IDEMPOTENCY_CONFLICT'.
        ELSEIF header-KsefNumber = request-KsefNumber
          AND header-KsefEnvironment = request-Environment.
          outcome-Outcome = 'ALREADY_APPLIED'.
        ELSE.
          outcome-Outcome = 'RESULT_CONFLICT'.
        ENDIF.
      ELSEIF header-KsefEnvironment IS NOT INITIAL OR header-KsefRecordedAt IS NOT INITIAL
          OR header-KsefWriteRequestId IS NOT INITIAL.
        outcome-Outcome = 'RESULT_CONFLICT'.
      ELSEIF header-LocalLastChangedAt <> request-ExpectedLocalLastChangedAt.
        outcome-Outcome = 'VERSION_CONFLICT'.
      ELSE.
        GET TIME STAMP FIELD outcome-KsefRecordedAt.
        outcome-KsefNumber = request-KsefNumber.
        outcome-KsefEnvironment = request-Environment.
        outcome-KsefWriteRequestId = request-WriteRequestId.
        MODIFY ENTITIES OF zr_ainv_inv_h IN LOCAL MODE ENTITY ZrAinvInvH
          UPDATE FIELDS ( KsefNumber KsefEnvironment KsefRecordedAt KsefWriteRequestId )
          WITH VALUE #( ( %tky = key-%tky KsefNumber = outcome-KsefNumber
            KsefEnvironment = outcome-KsefEnvironment KsefRecordedAt = outcome-KsefRecordedAt
            KsefWriteRequestId = outcome-KsefWriteRequestId ) )
          FAILED DATA(update_failed) REPORTED DATA(update_reported).
        IF update_failed-zrainvinvh IS NOT INITIAL.
          APPEND VALUE #( %tky = key-%tky %fail-cause = if_abap_behv=>cause-unspecific )
            TO failed-zrainvinvh.
          RETURN.
        ENDIF.
        outcome-Outcome = 'APPLIED'.
      ENDIF.
      " Konflikty są jawnym wynikiem domenowym bez mutacji; CAP musi sprawdzić Outcome.
      APPEND VALUE #( %tky = key-%tky %param = outcome ) TO result.
  ENDMETHOD.
ENDCLASS.

