@EndUserText.label: 'AInvoicer read-only UI invoice items'
@AccessControl.authorizationCheck: #MANDATORY
@Metadata.ignorePropagatedAnnotations: true
define view entity ZC_AINV_UI_I
  as select from ZR_AINV_INV_I
  association [1..1] to ZC_AINV_UI_H as _Header
    on $projection.InvoiceUUID = _Header.InvoiceUUID
{
  key ItemUUID,
      InvoiceUUID, ItemNo, MaterialId, Description,
      @Semantics.quantity.unitOfMeasure: 'UnitCode'
      Quantity,
      UnitCode,
      @Semantics.amount.currencyCode: 'CurrencyCode'
      UnitPrice,
      @Semantics.amount.currencyCode: 'CurrencyCode'
      NetAmount,
      TaxRate, TaxCategory,
      @Semantics.amount.currencyCode: 'CurrencyCode'
      TaxAmount,
      @Semantics.amount.currencyCode: 'CurrencyCode'
      GrossAmount,
      CurrencyCode, LocalCreatedBy, LocalCreatedAt,
      LocalLastChangedBy, LocalLastChangedAt, LastChangedAt,
      _Header
}
