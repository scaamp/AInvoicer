@EndUserText.label: 'AInvoicer Invoice Item UI Read Projection'
@AccessControl.authorizationCheck: #MANDATORY
@Metadata.ignorePropagatedAnnotations: true
// Read-only item projection for the SAPUI5 UI service; access inherited from the header UI DCL.
define view entity ZC_AINV_INV_I_UI
  as select from ZR_AINV_INV_I
  association [1..1] to ZC_AINV_INV_H_UI as _Parent on $projection.InvoiceUUID = _Parent.InvoiceUUID
{
  key ItemUUID,
      InvoiceUUID,
      ItemNo,
      MaterialId,
      Description,
      @Semantics.quantity.unitOfMeasure: 'UnitCode'
      Quantity,
      UnitCode,
      @Semantics.amount.currencyCode: 'CurrencyCode'
      UnitPrice,
      @Semantics.amount.currencyCode: 'CurrencyCode'
      NetAmount,
      TaxRate,
      TaxCategory,
      @Semantics.amount.currencyCode: 'CurrencyCode'
      TaxAmount,
      @Semantics.amount.currencyCode: 'CurrencyCode'
      GrossAmount,
      CurrencyCode,
      LocalLastChangedAt,
      _Parent
}
