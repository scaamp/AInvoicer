@EndUserText.label: 'AInvoicer read-only UI invoice references'
@AccessControl.authorizationCheck: #MANDATORY
@Metadata.ignorePropagatedAnnotations: true
define view entity ZC_AINV_UI_REF
  as select from ZR_AINV_INV_REF
  association [1..1] to ZC_AINV_UI_H as _Header
    on $projection.InvoiceUUID = _Header.InvoiceUUID
{
  key RefUUID,
      InvoiceUUID, SourceSystem, SourceModule, ReferenceRole,
      SourceDocNo, SourceFiscalYear, SourceItemNo,
      LocalCreatedBy, LocalCreatedAt, LocalLastChangedBy, LocalLastChangedAt,
      LastChangedAt, _Header
}
