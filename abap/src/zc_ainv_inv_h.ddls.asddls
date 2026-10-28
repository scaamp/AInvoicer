@Metadata.allowExtensions: true
@Metadata.ignorePropagatedAnnotations: true
@Endusertext: {
  Label: '###GENERATED Core Data Service Entity'
}
@Objectmodel: {
  Sapobjectnodetype.Name: 'ZAINV_INV_H'
}
@AccessControl.authorizationCheck: #MANDATORY
define root view entity ZC_AINV_INV_H
  provider contract TRANSACTIONAL_QUERY
  as projection on ZR_AINV_INV_H
  association [1..1] to ZR_AINV_INV_H as _BaseEntity on $projection.INVOICEUUID = _BaseEntity.INVOICEUUID
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
  @Semantics: {
    Amount.Currencycode: 'CurrencyCode'
  }
  NetAmount,
  @Semantics: {
    Amount.Currencycode: 'CurrencyCode'
  }
  TaxAmount,
  @Semantics: {
    Amount.Currencycode: 'CurrencyCode'
  }
  GrossAmount,
  @Consumption: {
    Valuehelpdefinition: [ {
      Entity.Element: 'Currency', 
      Entity.Name: 'I_CurrencyStdVH', 
      Useforvalidation: true
    } ]
  }
  CurrencyCode,
  @Semantics: {
    User.Createdby: true
  }
  LocalCreatedBy,
  @Semantics: {
    Systemdatetime.Createdat: true
  }
  LocalCreatedAt,
  @Semantics: {
    User.Localinstancelastchangedby: true
  }
  LocalLastChangedBy,
  @Semantics: {
    Systemdatetime.Localinstancelastchangedat: true
  }
  LocalLastChangedAt,
  @Semantics: {
    Systemdatetime.Lastchangedat: true
  }
  LastChangedAt,
  _BaseEntity,
  _Items : redirected to composition child ZC_AINV_INV_I,
  _References : redirected to composition child ZC_AINV_INV_REF
}

