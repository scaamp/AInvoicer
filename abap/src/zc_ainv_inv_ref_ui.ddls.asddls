@EndUserText.label: 'AInvoicer Invoice Reference UI Read Projection'
@AccessControl.authorizationCheck: #MANDATORY
@Metadata.ignorePropagatedAnnotations: true
// Read-only reference projection for the SAPUI5 UI service; access inherited from the header UI DCL.
define view entity ZC_AINV_INV_REF_UI
  as select from ZR_AINV_INV_REF
  association [1..1] to ZC_AINV_INV_H_UI as _Parent on $projection.InvoiceUUID = _Parent.InvoiceUUID
{
  key RefUUID,
      InvoiceUUID,
      SourceSystem,
      SourceModule,
      ReferenceRole,
      SourceDocNo,
      SourceFiscalYear,
      SourceItemNo,
      _Parent
}
