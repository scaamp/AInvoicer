@AccessControl.authorizationCheck: #MANDATORY
@Metadata.allowExtensions: true
@EndUserText.label: 'AInvoicer Invoice Reference'
define view entity ZR_AINV_INV_REF
  as select from ZAINV_INV_REF
  association to parent ZR_AINV_INV_H as _Parent
    on $projection.InvoiceUUID = _Parent.InvoiceUUID
{
  key ref_uuid as RefUUID,
      invoice_uuid as InvoiceUUID,
      source_system as SourceSystem,
      source_module as SourceModule,
      reference_role as ReferenceRole,
      source_doc_no as SourceDocNo,
      source_fiscal_year as SourceFiscalYear,
      source_item_no as SourceItemNo,
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
