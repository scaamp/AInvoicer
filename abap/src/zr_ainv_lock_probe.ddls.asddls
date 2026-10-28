@EndUserText.label: 'CAP-04B lock orchestration probe'
@AccessControl.authorizationCheck: #NOT_REQUIRED
define root view entity ZR_AINV_LOCK_PROBE
  as select from zainv_inv_h
{
  key invoice_uuid as InvoiceUUID,
      local_last_changed_at as LocalLastChangedAt
}
where direction = 'OUTBOUND' and company_code = '1000'
