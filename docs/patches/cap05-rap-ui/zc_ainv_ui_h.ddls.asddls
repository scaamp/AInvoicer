@EndUserText.label: 'AInvoicer read-only UI invoices'
@AccessControl.authorizationCheck: #MANDATORY
@Metadata.ignorePropagatedAnnotations: true
define view entity ZC_AINV_UI_H
  as select from ZR_AINV_INV_H
  association [0..*] to ZC_AINV_UI_I as _Items
    on $projection.InvoiceUUID = _Items.InvoiceUUID
  association [0..*] to ZC_AINV_UI_REF as _References
    on $projection.InvoiceUUID = _References.InvoiceUUID
{
  key InvoiceUUID,
      InvoiceNumber, OriginProcess, Direction, InvoiceKind, InvoiceStatus,
      KsefNumber, KsefEnvironment, KsefRecordedAt, KsefWriteRequestId,
      CompanyCode, IssueDate, PostingDate, DueDate,
      SellerNip, SellerName, BuyerNip, BuyerName,
      @Semantics.amount.currencyCode: 'CurrencyCode'
      NetAmount,
      @Semantics.amount.currencyCode: 'CurrencyCode'
      TaxAmount,
      @Semantics.amount.currencyCode: 'CurrencyCode'
      GrossAmount,
      CurrencyCode, LocalCreatedBy, LocalCreatedAt,
      LocalLastChangedBy, LocalLastChangedAt, LastChangedAt,
      _Items, _References
}
