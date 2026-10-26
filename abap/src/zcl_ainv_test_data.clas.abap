CLASS zcl_ainv_test_data DEFINITION
  PUBLIC
  FINAL
  CREATE PUBLIC.

  PUBLIC SECTION.
    INTERFACES if_oo_adt_classrun.
ENDCLASS.

CLASS zcl_ainv_test_data IMPLEMENTATION.

  METHOD if_oo_adt_classrun~main.
    DATA:
      gc_fi_ap_number TYPE c LENGTH 35,
      gc_fi_ar_number TYPE c LENGTH 35,
      gc_mm_iv_number TYPE c LENGTH 35,
      gc_sd_number    TYPE c LENGTH 35.

    gc_fi_ap_number = 'AINV-TEST-SEED-2026-FI-AP'.
    gc_fi_ar_number = 'AINV-TEST-SEED-2026-FI-AR'.
    gc_mm_iv_number = 'AINV-TEST-SEED-2026-MM-IV'.
    gc_sd_number = 'AINV-TEST-SEED-2026-SD-BILL'.

    SELECT FROM zr_ainv_inv_h
      FIELDS InvoiceUUID, InvoiceNumber
      WHERE InvoiceNumber = @gc_fi_ap_number
         OR InvoiceNumber = @gc_fi_ar_number
         OR InvoiceNumber = @gc_mm_iv_number
         OR InvoiceNumber = @gc_sd_number
      INTO TABLE @DATA(existing_invoices).

    IF existing_invoices IS NOT INITIAL.
      out->write( |Seed przerwany: znaleziono { lines( existing_invoices ) } deterministycznych numerów.| ).
      LOOP AT existing_invoices ASSIGNING FIELD-SYMBOL(<existing>).
        out->write( |Pominięto { <existing>-InvoiceNumber }, UUID { <existing>-InvoiceUUID }.| ).
      ENDLOOP.
      out->write( 'Nie utworzono żadnej faktury; przy częściowym zbiorze wymagane jest ręczne uzgodnienie.' ).
      RETURN.
    ENDIF.

    out->write( 'Brak wcześniejszych numerów seed; przygotowuję 4 faktury syntetyczne.' ).
    out->write( 'Uwaga: SELECT nie blokuje równoległego uruchomienia; InvoiceNumber nie ma aktywnego ograniczenia UNIQUE.' ).

    " Jedna operacja EML tworzy nagłówki oraz oba rodzaje dzieci w tym samym buforze RAP.
    MODIFY ENTITIES OF ZR_AINV_INV_H
      ENTITY ZrAinvInvH
        CREATE FIELDS (
          InvoiceNumber OriginProcess Direction InvoiceKind InvoiceStatus
          CompanyCode IssueDate PostingDate DueDate SellerNip SellerName
          BuyerNip BuyerName NetAmount TaxAmount GrossAmount CurrencyCode
        ) WITH VALUE #(
          ( %cid = 'H_FI_AP'
            InvoiceNumber = gc_fi_ap_number OriginProcess = 'FI_AP'
            Direction = 'INBOUND' InvoiceKind = 'STANDARD' InvoiceStatus = 'NEW'
            CompanyCode = '1000' IssueDate = '20261001' PostingDate = '20261002'
            DueDate = '20261031' SellerNip = '5250001009' SellerName = 'Test Dostawca Alfa Sp z o o'
            BuyerNip = '5260002008' BuyerName = 'AInvoicer Test Buyer FI'
            NetAmount = '1500.00' TaxAmount = '345.00' GrossAmount = '1845.00' CurrencyCode = 'PLN' )
          ( %cid = 'H_FI_AR'
            InvoiceNumber = gc_fi_ar_number OriginProcess = 'FI_AR'
            Direction = 'OUTBOUND' InvoiceKind = 'STANDARD' InvoiceStatus = 'NEW'
            CompanyCode = '1000' IssueDate = '20261003' PostingDate = '20261003'
            DueDate = '20261102' SellerNip = '5260002008' SellerName = 'AInvoicer Test Seller FI'
            BuyerNip = '5210003008' BuyerName = 'Test Odbiorca Beta Sp z o o'
            NetAmount = '2200.00' TaxAmount = '506.00' GrossAmount = '2706.00' CurrencyCode = 'PLN' )
          ( %cid = 'H_MM_IV'
            InvoiceNumber = gc_mm_iv_number OriginProcess = 'MM_IV'
            Direction = 'INBOUND' InvoiceKind = 'STANDARD' InvoiceStatus = 'NEW'
            CompanyCode = '1000' IssueDate = '20261005' PostingDate = '20261006'
            DueDate = '20261104' SellerNip = '5250001009' SellerName = 'Test Dostawca Alfa Sp z o o'
            BuyerNip = '5260002008' BuyerName = 'AInvoicer Test Buyer MM'
            NetAmount = '4250.00' TaxAmount = '977.50' GrossAmount = '5227.50' CurrencyCode = 'PLN' )
          ( %cid = 'H_SD_BILL'
            InvoiceNumber = gc_sd_number OriginProcess = 'SD_BILL'
            Direction = 'OUTBOUND' InvoiceKind = 'STANDARD' InvoiceStatus = 'NEW'
            CompanyCode = '1000' IssueDate = '20261007' PostingDate = '20261007'
            DueDate = '20261106' SellerNip = '5260002008' SellerName = 'AInvoicer Test Seller SD'
            BuyerNip = '5210003008' BuyerName = 'Test Odbiorca Beta Sp z o o'
            NetAmount = '2400.00' TaxAmount = '552.00' GrossAmount = '2952.00' CurrencyCode = 'PLN' )
        )
        CREATE BY \_Items FIELDS (
          ItemNo MaterialId Description Quantity UnitCode UnitPrice
          NetAmount TaxRate TaxCategory TaxAmount GrossAmount CurrencyCode
        ) WITH VALUE #(
          ( %cid_ref = 'H_FI_AP'
            %target = VALUE #(
              ( %cid = 'I_FI_AP_10' ItemNo = '000010' MaterialId = 'TEST-SVC-01'
                Description = 'Syntetyczna usługa księgowa' Quantity = '2.000' UnitCode = 'EA'
                UnitPrice = '500.00' NetAmount = '1000.00' TaxRate = '23.00'
                TaxCategory = 'VAT23' TaxAmount = '230.00' GrossAmount = '1230.00' CurrencyCode = 'PLN' )
              ( %cid = 'I_FI_AP_20' ItemNo = '000020' MaterialId = 'TEST-SVC-02'
                Description = 'Syntetyczne wsparcie techniczne' Quantity = '1.000' UnitCode = 'EA'
                UnitPrice = '500.00' NetAmount = '500.00' TaxRate = '23.00'
                TaxCategory = 'VAT23' TaxAmount = '115.00' GrossAmount = '615.00' CurrencyCode = 'PLN' )
            ) )
          ( %cid_ref = 'H_FI_AR'
            %target = VALUE #(
              ( %cid = 'I_FI_AR_10' ItemNo = '000010' MaterialId = 'TEST-GOODS-01'
                Description = 'Syntetyczny produkt Alfa' Quantity = '3.000' UnitCode = 'EA'
                UnitPrice = '400.00' NetAmount = '1200.00' TaxRate = '23.00'
                TaxCategory = 'VAT23' TaxAmount = '276.00' GrossAmount = '1476.00' CurrencyCode = 'PLN' )
              ( %cid = 'I_FI_AR_20' ItemNo = '000020' MaterialId = 'TEST-GOODS-02'
                Description = 'Syntetyczny produkt Beta' Quantity = '2.000' UnitCode = 'EA'
                UnitPrice = '400.00' NetAmount = '800.00' TaxRate = '23.00'
                TaxCategory = 'VAT23' TaxAmount = '184.00' GrossAmount = '984.00' CurrencyCode = 'PLN' )
              ( %cid = 'I_FI_AR_30' ItemNo = '000030' MaterialId = 'TEST-SVC-03'
                Description = 'Syntetyczna usługa dodatkowa' Quantity = '1.000' UnitCode = 'EA'
                UnitPrice = '200.00' NetAmount = '200.00' TaxRate = '23.00'
                TaxCategory = 'VAT23' TaxAmount = '46.00' GrossAmount = '246.00' CurrencyCode = 'PLN' )
            ) )
          ( %cid_ref = 'H_MM_IV'
            %target = VALUE #(
              ( %cid = 'I_MM_IV_10' ItemNo = '000010' MaterialId = 'TEST-MAT-01'
                Description = 'Syntetyczny materiał montażowy' Quantity = '10.000' UnitCode = 'EA'
                UnitPrice = '350.00' NetAmount = '3500.00' TaxRate = '23.00'
                TaxCategory = 'VAT23' TaxAmount = '805.00' GrossAmount = '4305.00' CurrencyCode = 'PLN' )
              ( %cid = 'I_MM_IV_20' ItemNo = '000020' MaterialId = 'TEST-MAT-02'
                Description = 'Syntetyczny materiał eksploatacyjny' Quantity = '3.000' UnitCode = 'EA'
                UnitPrice = '250.00' NetAmount = '750.00' TaxRate = '23.00'
                TaxCategory = 'VAT23' TaxAmount = '172.50' GrossAmount = '922.50' CurrencyCode = 'PLN' )
            ) )
          ( %cid_ref = 'H_SD_BILL'
            %target = VALUE #(
              ( %cid = 'I_SD_BILL_10' ItemNo = '000010' MaterialId = 'TEST-SD-01'
                Description = 'Syntetyczna usługa wdrożeniowa' Quantity = '2.000' UnitCode = 'EA'
                UnitPrice = '450.00' NetAmount = '900.00' TaxRate = '23.00'
                TaxCategory = 'VAT23' TaxAmount = '207.00' GrossAmount = '1107.00' CurrencyCode = 'PLN' )
              ( %cid = 'I_SD_BILL_20' ItemNo = '000020' MaterialId = 'TEST-SD-02'
                Description = 'Syntetyczny pakiet serwisowy' Quantity = '3.000' UnitCode = 'EA'
                UnitPrice = '500.00' NetAmount = '1500.00' TaxRate = '23.00'
                TaxCategory = 'VAT23' TaxAmount = '345.00' GrossAmount = '1845.00' CurrencyCode = 'PLN' )
            ) )
        )
        CREATE BY \_References FIELDS (
          SourceSystem SourceModule ReferenceRole SourceDocNo SourceFiscalYear SourceItemNo
        ) WITH VALUE #(
          ( %cid_ref = 'H_FI_AP'
            %target = VALUE #(
              ( %cid = 'R_FI_AP_10' SourceSystem = 'AInvoicer-SIM' SourceModule = 'FI'
                ReferenceRole = 'ACCOUNTING_DOC' SourceDocNo = 'SIM-FI-AP-2026-001'
                SourceFiscalYear = '2026' SourceItemNo = '000010' )
            ) )
          ( %cid_ref = 'H_FI_AR'
            %target = VALUE #(
              ( %cid = 'R_FI_AR_10' SourceSystem = 'AInvoicer-SIM' SourceModule = 'FI'
                ReferenceRole = 'ACCOUNTING_DOC' SourceDocNo = 'SIM-FI-AR-2026-001'
                SourceFiscalYear = '2026' SourceItemNo = '000010' )
            ) )
          ( %cid_ref = 'H_MM_IV'
            %target = VALUE #(
              ( %cid = 'R_MM_IV_PO' SourceSystem = 'AInvoicer-SIM' SourceModule = 'MM'
                ReferenceRole = 'PURCHASE_ORDER' SourceDocNo = 'SIM-MM-PO-2026-001'
                SourceFiscalYear = '2026' SourceItemNo = '000010' )
              ( %cid = 'R_MM_IV_GR' SourceSystem = 'AInvoicer-SIM' SourceModule = 'MM'
                ReferenceRole = 'GOODS_RECEIPT' SourceDocNo = 'SIM-MM-GR-2026-001'
                SourceFiscalYear = '2026' SourceItemNo = '000010' )
            ) )
          ( %cid_ref = 'H_SD_BILL'
            %target = VALUE #(
              ( %cid = 'R_SD_BILL_SO' SourceSystem = 'AInvoicer-SIM' SourceModule = 'SD'
                ReferenceRole = 'SALES_ORDER' SourceDocNo = 'SIM-SD-SO-2026-001'
                SourceFiscalYear = '2026' SourceItemNo = '000010' )
              ( %cid = 'R_SD_BILL_DL' SourceSystem = 'AInvoicer-SIM' SourceModule = 'SD'
                ReferenceRole = 'DELIVERY' SourceDocNo = 'SIM-SD-DL-2026-001'
                SourceFiscalYear = '2026' SourceItemNo = '000010' )
            ) )
        )
      MAPPED DATA(mapped)
      FAILED DATA(failed)
      REPORTED DATA(reported).

    IF failed IS NOT INITIAL OR mapped IS INITIAL.
      out->write( 'Błąd walidacji/utworzenia EML; wycofuję cały bufor RAP.' ).
      IF failed IS NOT INITIAL.
        out->write( data = failed name = 'FAILED (MODIFY ENTITIES)' ).
      ENDIF.
      IF reported IS NOT INITIAL.
        out->write( data = reported name = 'REPORTED (MODIFY ENTITIES)' ).
      ENDIF.
      ROLLBACK ENTITIES.
      RETURN.
    ENDIF.

    IF reported IS NOT INITIAL.
      out->write( data = reported name = 'REPORTED (MODIFY ENTITIES)' ).
    ENDIF.
    out->write( data = mapped name = 'MAPPED (CID → managed UUID)' ).

    COMMIT ENTITIES RESPONSE OF ZR_AINV_INV_H
      FAILED DATA(failed_commit)
      REPORTED DATA(reported_commit).

    IF sy-subrc <> 0 OR failed_commit IS NOT INITIAL.
      out->write( |Błąd fazy zapisu RAP, sy-subrc={ sy-subrc }; wycofuję bufor.| ).
      IF failed_commit IS NOT INITIAL.
        out->write( data = failed_commit name = 'FAILED (COMMIT ENTITIES)' ).
      ENDIF.
      IF reported_commit IS NOT INITIAL.
        out->write( data = reported_commit name = 'REPORTED (COMMIT ENTITIES)' ).
      ENDIF.
      ROLLBACK ENTITIES.
      RETURN.
    ENDIF.

    IF reported_commit IS NOT INITIAL.
      out->write( data = reported_commit name = 'REPORTED (COMMIT ENTITIES)' ).
    ENDIF.
    out->write( 'Zapis zakończony: 4 faktury, 9 pozycji, 6 referencji.' ).
    out->write( data = mapped name = 'Utworzone UUID z MAPPED' ).
  ENDMETHOD.

ENDCLASS.

