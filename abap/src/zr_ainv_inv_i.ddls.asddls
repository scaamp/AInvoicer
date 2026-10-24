@AccessControl.authorizationCheck: #MANDATORY
@Metadata.allowExtensions: true
@EndUserText.label: 'AInvoicer Invoice Item'
define view entity ZR_AINV_INV_I
  as select from ZAINV_INV_I
  association to parent ZR_AINV_INV_H as _Parent
    on $projection.InvoiceUUID = _Parent.InvoiceUUID
{
  key item_uuid as ItemUUID,
      invoice_uuid as InvoiceUUID,
      item_no as ItemNo,
      material_id as MaterialId,
      description as Description,
      @Semantics.quantity.unitOfMeasure: 'UnitCode'
      quantity as Quantity,
      @Semantics.unitOfMeasure: true
      unit_code as UnitCode,
      @Semantics.amount.currencyCode: 'CurrencyCode'
      unit_price as UnitPrice,
      @Semantics.amount.currencyCode: 'CurrencyCode'
      net_amount as NetAmount,
      tax_rate as TaxRate,
      tax_category as TaxCategory,
      @Semantics.amount.currencyCode: 'CurrencyCode'
      tax_amount as TaxAmount,
      @Semantics.amount.currencyCode: 'CurrencyCode'
      gross_amount as GrossAmount,
      currency_code as CurrencyCode,
      @Semantics.user.createdBy: true
      local_created_by as LocalCreatedBy,
      @Semantics.systemDateTime.createdAt: true
      local_created_at as LocalCreatedAt,
      @Semantics.user.localInstanceLastChangedBy: true
      local_last_changed_by as LocalLastChangedBy,
      @Semantics.systemDateTime.localInstanceLastChangedAt: true
      local_last_changed_at as LocalLastChangedAt,
      @Semantics.systemDateTime.lastChangedAt: true
      last_changed_at as LastChangedAt,
      _Parent
}
