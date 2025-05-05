CLASS zcl_openai_client DEFINITION
  PUBLIC
  FINAL
  CREATE PUBLIC.

  PUBLIC SECTION.
    INTERFACES if_oo_adt_classrun.
    CONSTANTS:
      base_url      TYPE string VALUE 'https://api.openai.com/v1/chat/completions',

      content_type  TYPE string VALUE 'Content-type',
      json_content  TYPE string VALUE 'application/json',
      content_type1 TYPE string VALUE 'Authorization',
      json_content1 TYPE string VALUE ''. " secret key here.

    TYPES:
      ty_headers TYPE tihttpnvp,

      BEGIN OF ty_message,
        role    TYPE string,
        content TYPE string,
      END OF ty_message,

      tt_message TYPE TABLE OF ty_message WITH EMPTY KEY,

      BEGIN OF post_without_id_s,
        model    TYPE string,
        messages TYPE tt_message,
      END OF post_without_id_s.

    METHODS:
      create_client
        IMPORTING url           TYPE string
        RETURNING VALUE(result) TYPE REF TO if_web_http_client
        RAISING   cx_static_check,


      create_post
        IMPORTING
                  iv_role       TYPE string
                  iv_content    TYPE string
                  iv_model      TYPE string
        RETURNING VALUE(result) TYPE string
        RAISING   cx_static_check.

  PRIVATE SECTION.
ENDCLASS.

CLASS zcl_openai_client IMPLEMENTATION.

  METHOD create_client.
    DATA(dest) = cl_http_destination_provider=>create_by_url( url ).
    result = cl_web_http_client_manager=>create_by_http_destination( dest ).
  ENDMETHOD.

  METHOD create_post.
    DATA: lt_headers TYPE tihttpnvp,
          ls_header  TYPE LINE OF tihttpnvp,
          json_post  TYPE string
          .

    ls_header-name = content_type.
    ls_header-value = json_content.

    ls_header-name = content_type1.
    ls_header-value = json_content1.

    APPEND ls_header TO lt_headers.

    " Send JSON of post to server and return the response
    DATA(url) = |{ base_url }|.
    DATA(client) = create_client( url ).
    DATA(req) = client->get_http_request(  ).


    json_post = '{ "model": "iv_model", "messages": [ { "role": "iv_role", "content": "iv_content" } ] }'.

    REPLACE ALL OCCURRENCES OF 'iv_role' IN json_post WITH iv_role.
    REPLACE ALL OCCURRENCES OF 'iv_content' IN json_post WITH iv_content.
    REPLACE ALL OCCURRENCES OF 'iv_model' IN json_post WITH iv_model.

    req->set_text( json_post ).
    req->set_header_field( i_name = content_type i_value = json_content ).
    req->set_header_fields( lt_headers ).
    result = client->execute( if_web_http_client=>post )->get_text(  ).
    client->close(  ).
  ENDMETHOD.

  METHOD if_oo_adt_classrun~main.
    TRY.
        DATA: lmsg_data     TYPE REF TO data,
              lo_pos_struct TYPE REF TO cl_abap_structdescr.

        FIELD-SYMBOLS: <fs_result>    TYPE any,
                       <ls_result>    TYPE any,
                       <l_msg_data>   TYPE any,
                       <l_postab_ref> TYPE any,
                       <lt_postab>    TYPE ANY TABLE,
                       <l_pos>        TYPE any,
                       <l_fvalue>     TYPE any,
                       <l_choices>    TYPE any,
                       <l_message>    TYPE any,
                       <l_content>    TYPE any
                       .

        " Create
        DATA(create_response) = create_post( iv_model = 'gpt-4o' iv_role = 'user' iv_content = 'Podaj mi przepis na szparagi ;)' ).

        lmsg_data = /ui2/cl_json=>generate( json = create_response ).
        ASSIGN lmsg_data->* TO <l_msg_data>.

        " Mapujemy choices do dynamicznej struktury
        CALL METHOD /ui2/cl_json=>deserialize
          EXPORTING
            json        = create_response
            pretty_name = /ui2/cl_json=>pretty_mode-camel_case
          CHANGING
            data        = <l_msg_data>.

        " Teraz wybieramy content z choices[0]
        ASSIGN COMPONENT 'CHOICES' OF STRUCTURE <l_msg_data> TO <l_postab_ref>.
        ASSIGN <l_postab_ref>->* TO <lt_postab>.

        LOOP AT <lt_postab> ASSIGNING FIELD-SYMBOL(<l_posref>).

          ASSIGN <l_posref>->* TO <l_pos>.
          ASSIGN COMPONENT 'MESSAGE' OF STRUCTURE <l_pos> TO <l_postab_ref>.

          ASSIGN <l_postab_ref>->* TO <l_message>.

          ASSIGN COMPONENT 'MESSAGE' OF STRUCTURE <l_message> TO <l_message>.

          ASSIGN COMPONENT 'CONTENT' OF STRUCTURE <l_message> TO <l_postab_ref>.

          ASSIGN <l_postab_ref>->* TO <l_content>.
        ENDLOOP.

        out->write( <l_content> ).


      CATCH cx_root INTO DATA(exc).
        out->write( exc->get_text(  ) ).
    ENDTRY.
  ENDMETHOD.

ENDCLASS.
