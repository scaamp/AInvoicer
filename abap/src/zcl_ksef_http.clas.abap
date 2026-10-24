CLASS zcl_ksef_http DEFINITION
  PUBLIC
FINAL
CREATE PUBLIC .

  PUBLIC SECTION.
    INTERFACES if_oo_adt_classrun.

  PROTECTED SECTION.
  PRIVATE SECTION.
ENDCLASS.



CLASS zcl_ksef_http IMPLEMENTATION.

  METHOD if_oo_adt_classrun~main.
    " W obecnym teście C# pobiera challenge i zapisuje właściwą wartość w podpisanym XML.
    " Oddzielny challenge pobrany przez ABAP nie unieważnia challenge z C#; należy pilnować aktualności podpisanego XML.
    " POMINIĘTE: typy i zmienne służyły etapom pobierania challenge i certyfikatów; w tym teście podpisany XML dostarcza C#.
    "     " Struktura odpowiedzi z POST /auth/challenge.
    "     TYPES:
    "       BEGIN OF ty_challenge,
    "         challenge    TYPE string,
    "         timestamp    TYPE string,
    "         timestamp_ms TYPE int8,
    "         client_ip    TYPE string,
    "       END OF ty_challenge.
    "
    "     " Każdy certyfikat może mieć listę zastosowań.
    "     TYPES ty_usages TYPE STANDARD TABLE OF string
    "       WITH EMPTY KEY.
    "
    "     TYPES:
    "       BEGIN OF ty_certificate,
    "         certificate    TYPE string,
    "         certificate_id TYPE string,
    "         public_key_id  TYPE string,
    "         valid_from     TYPE string,
    "         valid_to       TYPE string,
    "         usage          TYPE ty_usages,
    "       END OF ty_certificate.
    "
    "     TYPES ty_certificates TYPE STANDARD TABLE OF ty_certificate
    "       WITH EMPTY KEY.
    "
    "     DATA ls_challenge    TYPE ty_challenge.
    "     DATA lt_certificates TYPE ty_certificates.
    "
    " POMINIĘTE: typy i zmienne wykorzystywane wyłącznie przez automatyczne pobranie challenge i certyfikatów.
    "     TYPES:
    "       BEGIN OF ty_challenge,
    "         challenge    TYPE string,
    "         timestamp    TYPE string,
    "         timestamp_ms TYPE int8,
    "         client_ip    TYPE string,
    "       END OF ty_challenge.
    "
    "     " Każdy certyfikat może mieć listę zastosowań.
    "     TYPES ty_usages TYPE STANDARD TABLE OF string
    "       WITH EMPTY KEY.
    "
    "     TYPES:
    "       BEGIN OF ty_certificate,
    "         certificate    TYPE string,
    "         certificate_id TYPE string,
    "         public_key_id  TYPE string,
    "         valid_from     TYPE string,
    "         valid_to       TYPE string,
    "         usage          TYPE ty_usages,
    "       END OF ty_certificate.
    "
    "     TYPES ty_certificates TYPE STANDARD TABLE OF ty_certificate
    "       WITH EMPTY KEY.
    "
    "     DATA ls_challenge    TYPE ty_challenge.
    "     DATA lt_certificates TYPE ty_certificates.
    "
    " TRY. Zewnętrzny blok był zbędny; obsługa cx_root znajduje się przy wysyłce.

    " POMINIĘTE: początkowy klient HTTP był używany wyłącznie przez nieaktywne kroki przygotowawcze.
    "         " ==================================================
    "     " POMINIĘTE: osobny klient nie jest potrzebny, bo aktywny przebieg wykonuje POST poniżej.
    "     "         " 1. Utworzenie klienta HTTP
    "     "         " ==================================================
    "     "
    "     "         DATA(lo_destination) =
    "     "           cl_http_destination_provider=>create_by_url(
    "     "             i_url = 'https://api-test.ksef.mf.gov.pl'
    "     "           ).
    "     "
    "     "         DATA(lo_client) =
    "     "           cl_web_http_client_manager=>create_by_http_destination(
    "     "             i_destination = lo_destination
    "     "           ).
    "     "
    "     "         DATA(lo_request) = lo_client->get_http_request( ).
    "     "
    "     "         lo_request->set_header_field(
    "     "           i_name  = 'Accept'
    "     "           i_value = 'application/json'
    "     "         ).
    "     "
    "     "         " ==================================================
    "         " ==================================================
    "
    "         DATA(lo_destination) =
    "           cl_http_destination_provider=>create_by_url(
    "             i_url = 'https://api-test.ksef.mf.gov.pl'
    "           ).
    "
    "         DATA(lo_client) =
    "           cl_web_http_client_manager=>create_by_http_destination(
    "             i_destination = lo_destination
    "           ).
    "
    "         DATA(lo_request) = lo_client->get_http_request( ).
    "
    "         lo_request->set_header_field(
    "           i_name  = 'Accept'
    "           i_value = 'application/json'
    "         ).
    "
    " POMINIĘTE: challenge pobiera aplikacja C# i umieszcza jego właściwą wartość w podpisanym XML. Osobny challenge pobrany przez ABAP sam w sobie go nie unieważnia; 21111 może wynikać z nieaktualnego podpisanego XML.
    "         " ==================================================
    "     " POMINIĘTE: w teście challenge pobiera C# i umieszcza jego wartość w podpisanym XML. Osobny challenge pobrany przez ABAP sam w sobie go nie unieważnia; problemem może być nieaktualny podpisany XML (np. HTTP 400 / 21111).
    "     "         " 2. Pobranie challenge
    "     "         " ==================================================
    "     "         " To publiczny endpoint inicjujący uwierzytelnienie.
    "     "         " Challenge jest krótkotrwały i nie powinien być
    "     "         " traktowany jako trwały identyfikator sesji.
    "     "
    "     "         lo_request->set_uri_path(
    "     "           i_uri_path = '/v2/auth/challenge'
    "     "         ).
    "     "
    "     "         DATA(lo_response) = lo_client->execute(
    "     "           i_method = if_web_http_client=>post
    "     "         ).
    "     "
    "     "         DATA(ls_status) = lo_response->get_status( ).
    "     "
    "     "         IF ls_status-code <> 200.
    "     "           out->write( |Challenge HTTP: { ls_status-code }| ).
    "     "           out->write( lo_response->get_text( ) ).
    "     "           lo_client->close( ).
    "     "           RETURN.
    "     "         ENDIF.
    "     "
    "     "         DATA(lv_json) = lo_response->get_text( ).
    "     "
    "     "         " Mapujemy nazwy camelCase w JSON na pola ABAP.
    "     "         xco_cp_json=>data->from_string( lv_json )->apply( VALUE #(
    "     "             ( xco_cp_json=>transformation->camel_case_to_underscore )
    "     "           ) )->write_to( REF #( ls_challenge ) ).
    "     "
    "     "         out->write( |Challenge: { ls_challenge-challenge }| ).
    "     "         out->write( |Timestamp MS: { ls_challenge-timestamp_ms }| ).
    "     "
    "     "         " ==================================================
    "         " ==================================================
    "         " To publiczny endpoint inicjujący uwierzytelnienie.
    "         " Challenge jest krótkotrwały i nie powinien być
    "         " traktowany jako trwały identyfikator sesji.
    "
    "         lo_request->set_uri_path(
    "           i_uri_path = '/v2/auth/challenge'
    "         ).
    "
    "         DATA(lo_response) = lo_client->execute(
    "           i_method = if_web_http_client=>post
    "         ).
    "
    "         DATA(ls_status) = lo_response->get_status( ).
    "
    "         IF ls_status-code <> 200.
    "           out->write( |Challenge HTTP: { ls_status-code }| ).
    "           out->write( lo_response->get_text( ) ).
    "           lo_client->close( ).
    "           RETURN.
    "         ENDIF.
    "
    "         DATA(lv_json) = lo_response->get_text( ).
    "
    "         " Mapujemy nazwy camelCase w JSON na pola ABAP.
    "         xco_cp_json=>data->from_string( lv_json )->apply( VALUE #(
    "             ( xco_cp_json=>transformation->camel_case_to_underscore )
    "           ) )->write_to( REF #( ls_challenge ) ).
    "
    "         out->write( |Challenge: { ls_challenge-challenge }| ).
    "         out->write( |Timestamp MS: { ls_challenge-timestamp_ms }| ).
    "
    "         " ==================================================
    " POMINIĘTE: pobieranie certyfikatów MF nie jest potrzebne do wysłania gotowego XML podpisanego przez aplikację C#.
    "     " POMINIĘTE: certyfikaty MF nie są potrzebne do wysłania gotowego XML podpisanego przez zewnętrzną aplikację.
    "     "         " 3. Pobranie publicznych certyfikatów MF
    "     "         " ==================================================
    "     "         " Ten endpoint nie wymaga accessToken JWT.
    "     "         "
    "     "         " Nie są to certyfikaty naszego podatnika.
    "     "         " Zawierają klucze publiczne MF do szyfrowania.
    "     "
    "     "         lo_request->set_uri_path(
    "     "           i_uri_path = '/v2/security/public-key-certificates'
    "     "         ).
    "     "
    "     "         lo_response = lo_client->execute(
    "     "           i_method = if_web_http_client=>get
    "     "         ).
    "     "
    "     "         ls_status = lo_response->get_status( ).
    "     "
    "     "         IF ls_status-code <> 200.
    "     "           out->write( |Certificates HTTP: { ls_status-code }| ).
    "     "           out->write( lo_response->get_text( ) ).
    "     "           lo_client->close( ).
    "     "           RETURN.
    "     "         ENDIF.
    "     "
    "     "         lv_json = lo_response->get_text( ).
    "     "
    "     "         " Tym razem odpowiedź JSON jest tablicą,
    "     "         " dlatego deserializujemy ją do tabeli ABAP.
    "     "
    "     "         xco_cp_json=>data->from_string( lv_json )->apply(
    "     "           VALUE #(
    "     "             ( xco_cp_json=>transformation->camel_case_to_underscore )
    "     "           )
    "     "         )->write_to( REF #( lt_certificates ) ).
    "     "
    "     "         " Wyświetlamy tylko metadane certyfikatów.
    "     "         " Sam certyfikat Base64 może mieć kilka KB.
    "     "
    "     "         LOOP AT lt_certificates INTO DATA(ls_certificate).
    "     "
    "     "           out->write( |Public Key ID: { ls_certificate-public_key_id }| ).
    "     "           out->write( |Valid To: { ls_certificate-valid_to }| ).
    "     "
    "     "           LOOP AT ls_certificate-usage INTO DATA(lv_usage).
    "     "             out->write( |Usage: { lv_usage }| ).
    "     "           ENDLOOP.
    "     "
    "     "           out->write( '-----------------------------' ).
    "     "
    "     "         ENDLOOP.
    "     "
    "     "         " ======================================================
    "         " ==================================================
    "         " Ten endpoint nie wymaga accessToken JWT.
    "         "
    "         " Nie są to certyfikaty naszego podatnika.
    "         " Zawierają klucze publiczne MF do szyfrowania.
    "
    "         lo_request->set_uri_path(
    "           i_uri_path = '/v2/security/public-key-certificates'
    "         ).
    "
    "         lo_response = lo_client->execute(
    "           i_method = if_web_http_client=>get
    "         ).
    "
    "         ls_status = lo_response->get_status( ).
    "
    "         IF ls_status-code <> 200.
    "           out->write( |Certificates HTTP: { ls_status-code }| ).
    "           out->write( lo_response->get_text( ) ).
    "           lo_client->close( ).
    "           RETURN.
    "         ENDIF.
    "
    "         lv_json = lo_response->get_text( ).
    "
    "         " Tym razem odpowiedź JSON jest tablicą,
    "         " dlatego deserializujemy ją do tabeli ABAP.
    "
    "         xco_cp_json=>data->from_string( lv_json )->apply(
    "           VALUE #(
    "             ( xco_cp_json=>transformation->camel_case_to_underscore )
    "           )
    "         )->write_to( REF #( lt_certificates ) ).
    "
    "         " Wyświetlamy tylko metadane certyfikatów.
    "         " Sam certyfikat Base64 może mieć kilka KB.
    "
    "         LOOP AT lt_certificates INTO DATA(ls_certificate).
    "
    "           out->write( |Public Key ID: { ls_certificate-public_key_id }| ).
    "           out->write( |Valid To: { ls_certificate-valid_to }| ).
    "
    "           LOOP AT ls_certificate-usage INTO DATA(lv_usage).
    "             out->write( |Usage: { lv_usage }| ).
    "           ENDLOOP.
    "
    "           out->write( '-----------------------------' ).
    "
    "         ENDLOOP.
    "
    "         " ======================================================
    "     " POMINIĘTE: nie tworzymy niepodpisanego AuthTokenRequest, bo do API wysyłany jest gotowy, podpisany XML z C#.
    " POMINIĘTE: nie tworzymy niepodpisanego AuthTokenRequest, ponieważ wysyłany jest gotowy, podpisany XML.
    "     "         " 4. Przygotowanie żądania uwierzytelniającego XAdES
    "     "         " ======================================================
    "     "         "
    "     "         " AuthTokenRequest to XML identyfikujący podmiot
    "     "         " i kontekst uwierzytelnienia.
    "     "         "
    "     "         " Nie jest to faktura FA(3).
    "     "         " Nie jest to również podpisany dokument.
    "     "         "
    "     "         " Do podpisu potrzebujemy własnego klucza prywatnego
    "     "         " powiązanego z certyfikatem testowym.
    "     "
    "     "         DATA(lv_test_nip) = '1234563218'.
    "     "
    "     "         DATA(lv_auth_xml) =
    "     "           |<?xml version="1.0" encoding="utf-8"?>| &&
    "     "           |<AuthTokenRequest xmlns="http://ksef.mf.gov.pl/auth/token/2.0">| &&
    "     "           |<Challenge>{ ls_challenge-challenge }</Challenge>| &&
    "     "           |<ContextIdentifier>| &&
    "     "           |<Nip>{ lv_test_nip }</Nip>| &&
    "     "           |</ContextIdentifier>| &&
    "     "           |<SubjectIdentifierType>certificateSubject</SubjectIdentifierType>| &&
    "     "           |</AuthTokenRequest>|.
    "     "
    "     "         out->write( 'Unsigned AuthTokenRequest:' ).
    "     "         out->write( lv_auth_xml ).
    "     "
    "         " ======================================================
    "         "
    "         " AuthTokenRequest to XML identyfikujący podmiot
    "         " i kontekst uwierzytelnienia.
    "         "
    "         " Nie jest to faktura FA(3).
    "         " Nie jest to również podpisany dokument.
    "         "
    "         " Do podpisu potrzebujemy własnego klucza prywatnego
    "         " powiązanego z certyfikatem testowym.
    "
    "         DATA(lv_test_nip) = '1234563218'.
    "
    "         DATA(lv_auth_xml) =
    "           |<?xml version="1.0" encoding="utf-8"?>| &&
    "           |<AuthTokenRequest xmlns="http://ksef.mf.gov.pl/auth/token/2.0">| &&
    "           |<Challenge>{ ls_challenge-challenge }</Challenge>| &&
    "           |<ContextIdentifier>| &&
    "           |<Nip>{ lv_test_nip }</Nip>| &&
    "           |</ContextIdentifier>| &&
    "           |<SubjectIdentifierType>certificateSubject</SubjectIdentifierType>| &&
    "           |</AuthTokenRequest>|.
    "
    "         out->write( 'Unsigned AuthTokenRequest:' ).
    "         out->write( lv_auth_xml ).
    "
    DATA(lv_xml_base64) =
      '77u/PD94bWwgdmVyc2lvbj0iMS4wIiBlbmNvZGluZz0idXRmLTgiPz4NCjxBdXRoVG9rZW5SZXF1ZXN0IHhtbG5zOnhzaT0iaHR0cDovL3d3dy53My5vcmcvMjAwMS9YTUxTY2hlbWEtaW5zdGFuY2UiIHhtbG5z' &&
      'OnhzZD0iaHR0cDovL3d3dy53My5vcmcvMjAwMS9YTUxTY2hlbWEiIHhtbG5zPSJodHRwOi8va3NlZi5tZi5nb3YucGwvYXV0aC90b2tlbi8yLjAiPg0KICA8Q2hhbGxlbmdlPjIwMjYxMDIyLUNSLTM3MEE0QUIw' &&
      'MDAtODJCODAwMEY2NS02RTwvQ2hhbGxlbmdlPg0KICA8Q29udGV4dElkZW50aWZpZXI+DQogICAgPE5pcD4xMjM0NTYzMjE4PC9OaXA+DQogIDwvQ29udGV4dElkZW50aWZpZXI+DQogIDxTdWJqZWN0SWRlbnRp' &&
      'ZmllclR5cGU+Y2VydGlmaWNhdGVTdWJqZWN0PC9TdWJqZWN0SWRlbnRpZmllclR5cGU+DQo8U2lnbmF0dXJlIElkPSJTaWduYXR1cmUiIHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8yMDAwLzA5L3htbGRzaWcj' &&
      'Ij48U2lnbmVkSW5mbz48Q2Fub25pY2FsaXphdGlvbk1ldGhvZCBBbGdvcml0aG09Imh0dHA6Ly93d3cudzMub3JnL1RSLzIwMDEvUkVDLXhtbC1jMTRuLTIwMDEwMzE1IiAvPjxTaWduYXR1cmVNZXRob2QgQWxn' &&
      'b3JpdGhtPSJodHRwOi8vd3d3LnczLm9yZy8yMDAxLzA0L3htbGRzaWctbW9yZSNyc2Etc2hhMjU2IiAvPjxSZWZlcmVuY2UgVVJJPSIiPjxUcmFuc2Zvcm1zPjxUcmFuc2Zvcm0gQWxnb3JpdGhtPSJodHRwOi8v' &&
      'd3d3LnczLm9yZy8yMDAwLzA5L3htbGRzaWcjZW52ZWxvcGVkLXNpZ25hdHVyZSIgLz48VHJhbnNmb3JtIEFsZ29yaXRobT0iaHR0cDovL3d3dy53My5vcmcvMjAwMS8xMC94bWwtZXhjLWMxNG4jIiAvPjwvVHJh' &&
      'bnNmb3Jtcz48RGlnZXN0TWV0aG9kIEFsZ29yaXRobT0iaHR0cDovL3d3dy53My5vcmcvMjAwMS8wNC94bWxlbmMjc2hhMjU2IiAvPjxEaWdlc3RWYWx1ZT5vS28vVUoxWFRxNlhRdlFoWEhqY2JSMzZKNFg5UlZw' &&
      'YXc3ZnlEajRNcXRvPTwvRGlnZXN0VmFsdWU+PC9SZWZlcmVuY2U+PFJlZmVyZW5jZSBVUkk9IiNTaWduZWRQcm9wZXJ0aWVzIiBUeXBlPSJodHRwOi8vdXJpLmV0c2kub3JnLzAxOTAzI1NpZ25lZFByb3BlcnRp' &&
      'ZXMiPjxUcmFuc2Zvcm1zPjxUcmFuc2Zvcm0gQWxnb3JpdGhtPSJodHRwOi8vd3d3LnczLm9yZy8yMDAxLzEwL3htbC1leGMtYzE0biMiIC8+PC9UcmFuc2Zvcm1zPjxEaWdlc3RNZXRob2QgQWxnb3JpdGhtPSJo' &&
      'dHRwOi8vd3d3LnczLm9yZy8yMDAxLzA0L3htbGVuYyNzaGEyNTYiIC8+PERpZ2VzdFZhbHVlPkpFQmMwR1ZvL2g3S0JZSkJUbE9GUld4emJFajRFRXRUUFd2USsxczlhWWs9PC9EaWdlc3RWYWx1ZT48L1JlZmVy' &&
      'ZW5jZT48L1NpZ25lZEluZm8+PFNpZ25hdHVyZVZhbHVlPmltVXhtNjdhWFhMNjNldFhETndweGxxby94c3Z6c3daYUN3QWs2d2ZkWXI4T3dhR24zUzlUNy9wOWMwMnR4UnpSM3JBdXc4YVVtMjNNejVFT21SOWtl' &&
      'L1JSYVVEWmFZbXNOMmhmeFAwZm5sVjBBLzd3NjVnb0hkZzN3dDVBaWg2UDY3Zm9hYkNBQXV1YXlSRkJIMzd1YUR0VHJYYUUySXYrdCtrdTB3Q2xxa1Z6eWJJVHpLaWpRMlJNU0JSa0loRy93ZmZCTTdBc050dVRh' &&
      'QTA4UXIrZmw2QVJmdVJoRElxSU0xR2Z3czkwdmVhT1pLZjV0eWxhZ1FDT1ptckgxcnBJV3paTVpTeVJxd2NhYzhVazE1WXhoNkpJYkZFSjYxQXRFV1B0UUYreUM0VHJHSjkrSHVhM1pVck9WSll6VDhQR1gzNWtT' &&
      'NDM0d1I2WUZqNzFHVERRdz09PC9TaWduYXR1cmVWYWx1ZT48S2V5SW5mbz48WDUwOURhdGE+PFg1MDlDZXJ0aWZpY2F0ZT5NSUlEZkRDQ0FqU2dBd0lCQWdJSUVibSttRlBNei9Jd1BRWUpLb1pJaHZjTkFRRUtN' &&
      'RENnRFRBTEJnbGdoa2dCWlFNRUFnR2hHakFZQmdrcWhraUc5dzBCQVFnd0N3WUpZSVpJQVdVREJBSUJvZ01DQVNBd1RqRUxNQWtHQTFVRUJoTUNVRXd4RERBS0JnTlZCQU1UQTBFZ1VqRVpNQmNHQTFVRUJSTVFW' &&
      'RWxPVUV3dE1USXpORFUyTXpJeE9ERUtNQWdHQTFVRUJCTUJVakVLTUFnR0ExVUVLaE1CUVRBZUZ3MHlOakV3TWpJeE5UQXdOVE5hRncweU9ERXdNakl4TmpBeE5UTmFNRTR4Q3pBSkJnTlZCQVlUQWxCTU1Rd3dD' &&
      'Z1lEVlFRREV3TkJJRkl4R1RBWEJnTlZCQVVURUZSSlRsQk1MVEV5TXpRMU5qTXlNVGd4Q2pBSUJnTlZCQVFUQVZJeENqQUlCZ05WQkNvVEFVRXdnZ0VpTUEwR0NTcUdTSWIzRFFFQkFRVUFBNElCRHdBd2dnRUtB' &&
      'b0lCQVFES2pHNkMxc1drM0N1b2RUSVQvL1hlOUJRVG41Mmk5WEUzdGtqYVVMeW41cFVsY3RIR2Zwdk5nayswV2JTSGZNMEpGVDNlc1h0bW1qSmpxczBlZXhsRnc2S1d1QnBteDE5VnJ0QVdKYlhPQ0t4aE1TaWtp' &&
      'TmFjRS80ZnBhUWloanU2Tlp1ODJRWmJSNGlMdUZ4Y01jak0zSmkwSUNKbHF1Mko5b0ZCckJRZVZqNWhrRVYzQ3ZnamNvRnUzV1gwd3ZQVVFEd1B1NDZhaXJFeHY1Z1VMVW1lNy9hVzI5a0N5Q1lUVzA1akx4ZVRE' &&
      'K0E3ODZYRExMVDNORkRwajE5cEp0MkFZYVN0eGdHYktSdU80YVcrN3R1dXNXdkpEeXhieUM2NjgwTnZFSXpSVnZGeGg2N21vYzhKZXdDNTdCT2RrQW5UYmpiTWcvbUdRQ3U0OWI5SHMxWDlBZ01CQUFFd1BRWUpL' &&
      'b1pJaHZjTkFRRUtNRENnRFRBTEJnbGdoa2dCWlFNRUFnR2hHakFZQmdrcWhraUc5dzBCQVFnd0N3WUpZSVpJQVdVREJBSUJvZ01DQVNBRGdnRUJBQUZLK2dhT0pqODdsZ09BNW9DWkgybWJMRGhlWmdqRzU3NS8w' &&
      'S3J1dHVDL1lJRkc2THgvTGJVVDZEeWxBNW9wdHpEaVlGK1U0M2ZzTHdlRXB4Rlo0QnVOdG0rVzFtTWFUTWROSEJjRDk5cWllT2RNR1lKUUlXbmZGaU0ycXBMdmkxY1ZvalhWOEp5ZEtmTkFBdWFwVkx1eVVYUiti' &&
      'bWlBU3J5TDBVcW5neHIyZW1mYktJdmhySzRoeGdOdTV4L045YTJVTGJiS09FMndmcUY0ZTlxU1dxY24yRmgyeWUxeFFsYjZvTXlvRGZtSjlrSnY0WGZiTUUzbzZOeUYxMUp0SC9YQ29JbXowaWk1eGJWWC8rak1m' &&
      'b2tCNGxZRUtZREdjWVU0VkY2NzVwb25kdzRlWDE3eDAzN0Z6VEtMWVBBelc1RmlGbDVpTVlJdkZ3VEJIZGlJWnJzPTwvWDUwOUNlcnRpZmljYXRlPjwvWDUwOURhdGE+PC9LZXlJbmZvPjxPYmplY3Q+PHhhZGVz' &&
      'OlF1YWxpZnlpbmdQcm9wZXJ0aWVzIFRhcmdldD0iI1NpZ25hdHVyZSIgeG1sbnM6eGFkZXM9Imh0dHA6Ly91cmkuZXRzaS5vcmcvMDE5MDMvdjEuMy4yIyIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAv' &&
      'MDkveG1sZHNpZyMiPjx4YWRlczpTaWduZWRQcm9wZXJ0aWVzIElkPSJTaWduZWRQcm9wZXJ0aWVzIj48eGFkZXM6U2lnbmVkU2lnbmF0dXJlUHJvcGVydGllcz48eGFkZXM6U2lnbmluZ1RpbWU+MjAyNi0xMC0y' &&
      'MlQxNjowMDo1My4zMzYzNTM2KzAwOjAwPC94YWRlczpTaWduaW5nVGltZT48eGFkZXM6U2lnbmluZ0NlcnRpZmljYXRlPjx4YWRlczpDZXJ0Pjx4YWRlczpDZXJ0RGlnZXN0PjxEaWdlc3RNZXRob2QgQWxnb3Jp' &&
      'dGhtPSJodHRwOi8vd3d3LnczLm9yZy8yMDAxLzA0L3htbGVuYyNzaGEyNTYiIC8+PERpZ2VzdFZhbHVlPkhZSE04ZHFjaW4wS29WV1VtOG5Pb3NrYlJWelNWNDRWaWVZQytXeHZNWkk9PC9EaWdlc3RWYWx1ZT48' &&
      'L3hhZGVzOkNlcnREaWdlc3Q+PHhhZGVzOklzc3VlclNlcmlhbD48WDUwOUlzc3Vlck5hbWU+Rz1BLCBTTj1SLCBTRVJJQUxOVU1CRVI9VElOUEwtMTIzNDU2MzIxOCwgQ049QSBSLCBDPVBMPC9YNTA5SXNzdWVy' &&
      'TmFtZT48WDUwOVNlcmlhbE51bWJlcj4xMjc3MjYxNTMwNzg2NDg0MjEwPC9YNTA5U2VyaWFsTnVtYmVyPjwveGFkZXM6SXNzdWVyU2VyaWFsPjwveGFkZXM6Q2VydD48L3hhZGVzOlNpZ25pbmdDZXJ0aWZpY2F0' &&
      'ZT48L3hhZGVzOlNpZ25lZFNpZ25hdHVyZVByb3BlcnRpZXM+PC94YWRlczpTaWduZWRQcm9wZXJ0aWVzPjwveGFkZXM6UXVhbGlmeWluZ1Byb3BlcnRpZXM+PC9PYmplY3Q+PC9TaWduYXR1cmU+PC9BdXRoVG9r' &&
      'ZW5SZXF1ZXN0Pg=='.

    " Odpowiedź z POST /auth/xades-signature
    TYPES:
      BEGIN OF ty_auth_token,
        token       TYPE string,
        valid_until TYPE string,
      END OF ty_auth_token,

      BEGIN OF ty_auth_response,
        reference_number     TYPE string,
        authentication_token TYPE ty_auth_token,
      END OF ty_auth_response.

    DATA ls_auth TYPE ty_auth_response.

    TRY.
        " Odtwarzamy oryginalny, podpisany XML
        DATA(lv_signed_xml_bytes) =
        cl_web_http_utility=>decode_x_base64(
        encoded = lv_xml_base64
        ).

        DATA(lo_sign_dest) =
        cl_http_destination_provider=>create_by_url(
        i_url = 'https://api-test.ksef.mf.gov.pl'
        ).

        DATA(lo_sign_client) =
        cl_web_http_client_manager=>create_by_http_destination(
        i_destination = lo_sign_dest
        ).

        DATA(lo_sign_request) =
        lo_sign_client->get_http_request( ).

        lo_sign_request->set_uri_path(
        i_uri_path = '/v2/auth/xades-signature'
        ).

        " W TEST dopuszczamy certyfikat self-signed
        lo_sign_request->set_query(
        query = 'verifyCertificateChain=false'
        ).

        lo_sign_request->set_header_field(
        i_name  = 'Content-Type'
        i_value = 'application/xml'
        ).

        lo_sign_request->set_header_field(
        i_name  = 'Accept'
        i_value = 'application/json'
        ).

        " Wysyłamy XML jako bajty, nie JSON
        lo_sign_request->set_binary(
        i_data = lv_signed_xml_bytes
        ).

        " Diagnostyka pokazuje wyłącznie challenge z wysyłanego XML oraz informację o podpisanym dokumencie.
        DATA(lv_signed_xml_text) =
          cl_abap_conv_codepage=>create_in( )->convert(
            source = lv_signed_xml_bytes
          ).

        DATA lv_signed_challenge TYPE string.
        FIND REGEX '<Challenge>([^<]+)</Challenge>'
          IN lv_signed_xml_text
          SUBMATCHES lv_signed_challenge.

        IF sy-subrc = 0.
          out->write( |Challenge w podpisanym XML: { lv_signed_challenge }| ).
        ELSE.
          out->write( 'Wysyłany jest dokument podpisany; nie znaleziono elementu Challenge.' ).
        ENDIF.
        out->write( 'Wysyłany jest dokument podpisany.' ).

        DATA(lo_sign_response) = lo_sign_client->execute(
        i_method = if_web_http_client=>post
        ).


        DATA(ls_sign_status) = lo_sign_response->get_status( ).

        IF ls_sign_status-code <> 202.
          out->write( |XAdES HTTP: { ls_sign_status-code }| ).
          out->write( lo_sign_response->get_text( ) ).
          lo_sign_client->close( ).
          RETURN.
        ENDIF.

        " Dopiero od tego miejsca odczytujemy referenceNumber
        " i authenticationToken z odpowiedzi HTTP 202.

        ls_sign_status = lo_sign_response->get_status( ).
        DATA(lv_sign_json) = lo_sign_response->get_text( ).

        out->write( |HTTP: { ls_sign_status-code }| ).

        IF ls_sign_status-code = 202.

          xco_cp_json=>data->from_string( lv_sign_json )->apply(
          VALUE #(
          ( xco_cp_json=>transformation->camel_case_to_underscore )
          )
          )->write_to( REF #( ls_auth ) ).

          out->write( |Reference: { ls_auth-reference_number }| ).

          IF ls_auth-authentication_token-token IS NOT INITIAL.
            out->write( 'Authentication Token otrzymany.' ).
          ENDIF.

        ELSE.
          out->write( lv_sign_json ).
        ENDIF.

        lo_sign_request->set_uri_path(
          i_uri_path = |/v2/auth/{ ls_auth-reference_number }|
        ).

        lo_sign_request->set_query( query = '' ).

        lo_sign_request->set_header_field(
          i_name  = 'Authorization'
          i_value = |Bearer { ls_auth-authentication_token-token }|
        ).

        DATA(lo_status_response) = lo_sign_client->execute(
          i_method = if_web_http_client=>get
        ).

        out->write(
          |Status HTTP: { lo_status_response->get_status( )-code }|
        ).

        out->write( lo_status_response->get_text( ) ).

        lo_sign_client->close( ).

      CATCH cx_root INTO DATA(lx_error).
        out->write( lx_error->get_text( ) ).
    ENDTRY.

    " ENDTRY. Zamknięcie zakomentowanego, zbędnego zewnętrznego TRY.

  ENDMETHOD.
ENDCLASS.
