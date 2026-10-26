@Metadata.allowExtensions: true
@Metadata.ignorePropagatedAnnotations: true
@EndUserText.label: 'AInvoicer Invoice Reference Projection'
@AccessControl.authorizationCheck: #MANDATORY
define view entity ZC_AINV_INV_REF
  as projection on ZR_AINV_INV_REF
{
  key RefUUID,
      InvoiceUUID,
      SourceSystem,
      SourceModule,
      ReferenceRole,
      SourceDocNo,
      SourceFiscalYear,
      SourceItemNo,
      LocalCreatedBy,
      LocalCreatedAt,
      LocalLastChangedBy,
      LocalLastChangedAt,
      LastChangedAt,
      _Parent : redirected to parent ZC_AINV_INV_H
}
