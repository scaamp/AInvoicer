@EndUserText.label: 'AInvoicer Invoice Header UI Read Projection'
@AccessControl.authorizationCheck: #MANDATORY
@Metadata.ignorePropagatedAnnotations: true
// Read-only view for the SAPUI5 business-user UI service (CAP-05, ADR-0005).
// Plain select view (not a transactional projection): no behavior, so no create/update/delete
// and no ApplyKsefResult; and it may carry its own DCL (Z_AINV_KSF, ACTVT 03, NIP + CompanyCode),
// which ABAP Cloud forbids for transactional projections of a root with "where TRUE".
define view entity ZC_AINV_INV_H_UI
  as select from ZR_AINV_INV_H
  association [0..*] to ZC_AINV_INV_I_UI   as _Items      on $projection.InvoiceUUID = _Items.InvoiceUUID
  association [0..*] to ZC_AINV_INV_REF_UI as _References on $projection.InvoiceUUID = _References.InvoiceUUID
{
  key InvoiceUUID,
      InvoiceNumber,
      OriginProcess,
      Direction,
      InvoiceKind,
      InvoiceStatus,
      KsefNumber,
      KsefEnvironment,
      KsefRecordedAt,
      KsefWriteRequestId,
      CompanyCode,
      IssueDate,
      PostingDate,
      DueDate,
      SellerNip,
      SellerName,
      BuyerNip,
      BuyerName,
      @Semantics.amount.currencyCode: 'CurrencyCode'
      NetAmount,
      @Semantics.amount.currencyCode: 'CurrencyCode'
      TaxAmount,
      @Semantics.amount.currencyCode: 'CurrencyCode'
      GrossAmount,
      CurrencyCode,
      LocalLastChangedAt,
      LastChangedAt,
      _Items,
      _References
}
