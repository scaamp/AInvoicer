@AccessControl.authorizationCheck: #MANDATORY
@Metadata.allowExtensions: true
@ObjectModel.sapObjectNodeType.name: 'ZAINV_INV_H'
@EndUserText.label: '###GENERATED Core Data Service Entity'
define root view entity ZR_AINV_INV_H
  as select from ZAINV_INV_H
{
  key invoice_uuid as InvoiceUUID,
  invoice_number as InvoiceNumber,
  origin_process as OriginProcess,
  direction as Direction,
  invoice_kind as InvoiceKind,
  invoice_status as InvoiceStatus,
  company_code as CompanyCode,
  issue_date as IssueDate,
  posting_date as PostingDate,
  due_date as DueDate,
  seller_nip as SellerNip,
  seller_name as SellerName,
  buyer_nip as BuyerNip,
  buyer_name as BuyerName,
  @Semantics.amount.currencyCode: 'CurrencyCode'
  net_amount as NetAmount,
  @Semantics.amount.currencyCode: 'CurrencyCode'
  tax_amount as TaxAmount,
  @Semantics.amount.currencyCode: 'CurrencyCode'
  gross_amount as GrossAmount,
  @Consumption.valueHelpDefinition: [ {
    entity.name: 'I_CurrencyStdVH', 
    entity.element: 'Currency', 
    useForValidation: true
  } ]
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
  last_changed_at as LastChangedAt
}
