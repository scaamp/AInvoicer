@Metadata.allowExtensions: true
@Metadata.ignorePropagatedAnnotations: true
@EndUserText.label: 'AInvoicer Invoice Item Projection'
@AccessControl.authorizationCheck: #MANDATORY
define view entity ZC_AINV_INV_I
  as projection on ZR_AINV_INV_I
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
      LocalCreatedBy,
      LocalCreatedAt,
      LocalLastChangedBy,
      LocalLastChangedAt,
      LastChangedAt,
      _Parent : redirected to parent ZC_AINV_INV_H
}
