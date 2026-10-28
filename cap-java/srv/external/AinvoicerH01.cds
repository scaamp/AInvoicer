/* checksum : 42e5c2684238f85cfbfc0f67179c5513 */
namespace com.sap.gateway.srvd_a2x.zapi_ainv_inv_h_o4;

@cds.external : true
@CodeList.CurrencyCodes.Url : '../../../../default/iwbep/common/0001/$metadata'
@CodeList.CurrencyCodes.CollectionPath : 'Currencies'
@CodeList.UnitsOfMeasure.Url : '../../../../default/iwbep/common/0001/$metadata'
@CodeList.UnitsOfMeasure.CollectionPath : 'UnitsOfMeasure'
@Common.ApplyMultiUnitBehaviorForSortingAndFiltering : true
@Capabilities.FilterFunctions : [
  'eq',
  'ne',
  'gt',
  'ge',
  'lt',
  'le',
  'and',
  'or',
  'contains',
  'startswith',
  'endswith',
  'any',
  'all'
]
@SAP__support.TechnicalInfoLinks.Url : '../../../../default/iwbep/common/0001/$metadata'
@SAP__support.TechnicalInfoLinks.FunctionImport : 'GetTechnicalInfoLinks'
@Capabilities.SupportedFormats : [ 'application/json', 'application/pdf' ]
@PDF.Features.DocumentDescriptionReference : '../../../../default/iwbep/common/0001/$metadata'
@PDF.Features.DocumentDescriptionCollection : 'MyDocumentDescriptions'
@PDF.Features.ArchiveFormat : true
@PDF.Features.Border : true
@PDF.Features.CoverPage : true
@PDF.Features.FitToPage : true
@PDF.Features.FontName : true
@PDF.Features.FontSize : true
@PDF.Features.HeaderFooter : true
@PDF.Features.IANATimezoneFormat : true
@PDF.Features.Margin : true
@PDF.Features.Padding : true
@PDF.Features.ResultSizeDefault : 20000
@PDF.Features.ResultSizeMaximum : 20000
@PDF.Features.Signature : true
@PDF.Features.TextDirectionLayout : true
@PDF.Features.Treeview : true
@PDF.Features.UploadToFileShare : true
@Capabilities.BatchSupport.SupportedFormats : [ 'application/json', 'multipart/mixed' ]
@Capabilities.KeyAsSegmentSupported : true
@Capabilities.AsynchronousRequestsSupported : true
service v0001 {
  @cds.external : true
  type ZA_AINV_KSF_RES {
    @Common.Label : 'UUID'
    @Common.QuickInfo : '16 Byte UUID in 16 Bytes (Raw Format)'
    InvoiceUUID : UUID;
    KsefEnvironment : String(4) not null;
    KsefNumber : String(50) not null;
    @odata.Precision : 7
    @odata.Type : 'Edm.DateTimeOffset'
    @Common.Label : 'Created On'
    @Common.QuickInfo : 'Creation Date Time'
    @Common.DocumentationRef : 'urn:sap-com:documentation:key?=type=DE&id=ABP_CREATION_TSTMPL'
    KsefRecordedAt : Timestamp;
    @Common.Label : 'UUID'
    @Common.QuickInfo : '16 Byte UUID in 16 Bytes (Raw Format)'
    KsefWriteRequestId : UUID;
    Outcome : String(24) not null;
  };

  @cds.external : true
  type SAP__Message {
    code : String not null;
    message : String not null;
    target : String;
    additionalTargets : many String not null;
    transition : Boolean not null;
    @odata.Type : 'Edm.Byte'
    numericSeverity : Integer not null;
    longtextUrl : String;
  };

  @cds.external : true
  @cds.persistence.skip : true
  @Common.CreatedBy : LocalCreatedBy
  @Common.CreatedAt : LocalCreatedAt
  @Common.ChangedAt : LastChangedAt
  @Common.Label : '###GENERATED Core Data Service Entity'
  @Common.SAPObjectNodeType.Name : 'ZAINV_INV_H'
  @Common.Messages : SAP__Messages
  @Capabilities.NavigationRestrictions.RestrictedProperties : [
    { NavigationProperty: _Items, InsertRestrictions: { Insertable: true } },
    {
      NavigationProperty: _References,
      InsertRestrictions: { Insertable: true }
    }
  ]
  @Capabilities.SearchRestrictions.Searchable : false
  @Capabilities.UpdateRestrictions.DeltaUpdateSupported : true
  @Capabilities.UpdateRestrictions.NonUpdatableNavigationProperties : [ '_Items', '_References' ]
  @Capabilities.UpdateRestrictions.QueryOptions.SelectSupported : true
  @Capabilities.DeepUpdateSupport.ContentIDSupported : true
  @Core.OptimisticConcurrency : [ 'LocalLastChangedAt' ]
  @Capabilities.FilterRestrictions.FilterExpressionRestrictions : [
    { Property: NetAmount, AllowedExpressions: 'MultiValue' },
    { Property: TaxAmount, AllowedExpressions: 'MultiValue' },
    { Property: GrossAmount, AllowedExpressions: 'MultiValue' }
  ]
  entity ZC_AINV_INV_H {
    @Core.Computed : true
    @Common.Label : 'UUID'
    @Common.QuickInfo : '16 Byte UUID in 16 Bytes (Raw Format)'
    key InvoiceUUID : UUID not null;
    InvoiceNumber : String(35) not null;
    OriginProcess : String(10) not null;
    Direction : String(10) not null;
    InvoiceKind : String(12) not null;
    InvoiceStatus : String(12) not null;
    @Core.Computed : true
    KsefNumber : String(50) not null;
    @Core.Computed : true
    KsefEnvironment : String(4) not null;
    @odata.Precision : 7
    @odata.Type : 'Edm.DateTimeOffset'
    @Core.Computed : true
    @Common.Label : 'Created On'
    @Common.QuickInfo : 'Creation Date Time'
    @Common.DocumentationRef : 'urn:sap-com:documentation:key?=type=DE&id=ABP_CREATION_TSTMPL'
    KsefRecordedAt : Timestamp;
    @Core.Computed : true
    @Common.Label : 'UUID'
    @Common.QuickInfo : '16 Byte UUID in 16 Bytes (Raw Format)'
    KsefWriteRequestId : UUID;
    CompanyCode : String(4) not null;
    IssueDate : Date;
    PostingDate : Date;
    DueDate : Date;
    SellerNip : String(10) not null;
    SellerName : String(100) not null;
    BuyerNip : String(10) not null;
    BuyerName : String(100) not null;
    @Measures.ISOCurrency : CurrencyCode
    NetAmount : Decimal(precision: 17) not null;
    @Measures.ISOCurrency : CurrencyCode
    TaxAmount : Decimal(precision: 17) not null;
    @Measures.ISOCurrency : CurrencyCode
    GrossAmount : Decimal(precision: 17) not null;
    @Common.IsCurrency : true
    @Common.ValueListForValidation : true
    CurrencyCode : String(3) not null;
    @Core.Computed : true
    @Common.IsUpperCase : true
    @Common.Label : 'Created By'
    @Common.QuickInfo : 'Created By User'
    @Common.DocumentationRef : 'urn:sap-com:documentation:key?=type=DE&id=ABP_CREATION_USER'
    LocalCreatedBy : String(12) not null;
    @odata.Precision : 7
    @odata.Type : 'Edm.DateTimeOffset'
    @Core.Computed : true
    @Common.Label : 'Created On'
    @Common.QuickInfo : 'Creation Date Time'
    @Common.DocumentationRef : 'urn:sap-com:documentation:key?=type=DE&id=ABP_CREATION_TSTMPL'
    LocalCreatedAt : Timestamp;
    @Core.Computed : true
    @Common.IsUpperCase : true
    @Common.Label : 'Changed By'
    @Common.QuickInfo : 'Local Instance Last Changed By User'
    @Common.DocumentationRef : 'urn:sap-com:documentation:key?=type=DE&id=ABP_LOCINST_LASTCHANGE_USER'
    LocalLastChangedBy : String(12) not null;
    @odata.Precision : 7
    @odata.Type : 'Edm.DateTimeOffset'
    @Core.Computed : true
    @Common.Label : 'Changed On'
    @Common.QuickInfo : 'Local Instance Last Change Date Time'
    @Common.DocumentationRef : 'urn:sap-com:documentation:key?=type=DE&id=ABP_LOCINST_LASTCHANGE_TSTMPL'
    LocalLastChangedAt : Timestamp;
    @odata.Precision : 7
    @odata.Type : 'Edm.DateTimeOffset'
    @Core.Computed : true
    @Common.Label : 'Changed On'
    @Common.QuickInfo : 'Last Change Date Time'
    @Common.DocumentationRef : 'urn:sap-com:documentation:key?=type=DE&id=ABP_LASTCHANGE_TSTMPL'
    LastChangedAt : Timestamp;
    SAP__Messages : many SAP__Message not null;
    @Common.Composition : true
    _Items : Composition of many ZC_AINV_INV_I on _Items._Parent = $self;
    @Common.Composition : true
    _References : Composition of many ZC_AINV_INV_REF on _References._Parent = $self;
  } actions {
    action ApplyKsefResult(
      @Common.Label : 'UUID'
      @Common.QuickInfo : '16 Byte UUID in 16 Bytes (Raw Format)'
      InvoiceUUID : UUID,
      @Common.Label : 'UUID'
      @Common.QuickInfo : '16 Byte UUID in 16 Bytes (Raw Format)'
      WriteRequestId : UUID,
      KsefNumber : String(50) not null,
      Environment : String(4) not null,
      TaxpayerNip : String(10) not null,
      CompanyCode : String(4) not null,
      @odata.Precision : 7
      @odata.Type : 'Edm.DateTimeOffset'
      @Common.Label : 'Changed On'
      @Common.QuickInfo : 'Local Instance Last Change Date Time'
      @Common.DocumentationRef : 'urn:sap-com:documentation:key?=type=DE&id=ABP_LOCINST_LASTCHANGE_TSTMPL'
      ExpectedLocalLastChangedAt : Timestamp
    ) returns ZA_AINV_KSF_RES not null;
  };

  @cds.external : true
  @cds.persistence.skip : true
  @Common.Label : 'AInvoicer Invoice Item Projection'
  @Common.Messages : SAP__Messages
  @Capabilities.NavigationRestrictions.RestrictedProperties : [
    {
      NavigationProperty: _Parent,
      InsertRestrictions: { Insertable: false },
      DeepUpdateSupport: { Supported: false }
    }
  ]
  @Capabilities.SearchRestrictions.Searchable : false
  @Capabilities.UpdateRestrictions.DeltaUpdateSupported : true
  @Capabilities.UpdateRestrictions.NonUpdatableNavigationProperties : [ '_Parent' ]
  @Capabilities.UpdateRestrictions.QueryOptions.SelectSupported : true
  @Capabilities.DeepUpdateSupport.ContentIDSupported : true
  @Capabilities.InsertRestrictions.Insertable : false
  @Core.OptimisticConcurrency : [ 'LocalLastChangedAt' ]
  @Capabilities.FilterRestrictions.FilterExpressionRestrictions : [
    { Property: Quantity, AllowedExpressions: 'MultiValue' },
    { Property: UnitPrice, AllowedExpressions: 'MultiValue' },
    { Property: NetAmount, AllowedExpressions: 'MultiValue' },
    { Property: TaxAmount, AllowedExpressions: 'MultiValue' },
    { Property: GrossAmount, AllowedExpressions: 'MultiValue' }
  ]
  entity ZC_AINV_INV_I {
    @Core.Computed : true
    @Common.Label : 'UUID'
    @Common.QuickInfo : '16 Byte UUID in 16 Bytes (Raw Format)'
    key ItemUUID : UUID not null;
    @Core.Computed : true
    @Common.Label : 'UUID'
    @Common.QuickInfo : '16 Byte UUID in 16 Bytes (Raw Format)'
    InvoiceUUID : UUID not null;
    @Common.IsDigitSequence : true
    ItemNo : String(6) not null;
    MaterialId : String(40) not null;
    Description : String(255) not null;
    @Measures.Unit : UnitCode
    Quantity : Decimal(15, 3) not null;
    @Common.IsUnit : true
    UnitCode : String(3) not null;
    @Measures.ISOCurrency : CurrencyCode
    UnitPrice : Decimal(precision: 17) not null;
    @Measures.ISOCurrency : CurrencyCode
    NetAmount : Decimal(precision: 17) not null;
    TaxRate : Decimal(5, 2) not null;
    TaxCategory : String(8) not null;
    @Measures.ISOCurrency : CurrencyCode
    TaxAmount : Decimal(precision: 17) not null;
    @Measures.ISOCurrency : CurrencyCode
    GrossAmount : Decimal(precision: 17) not null;
    @Common.IsCurrency : true
    CurrencyCode : String(3) not null;
    @Core.Computed : true
    @Common.IsUpperCase : true
    @Common.Label : 'Created By'
    @Common.QuickInfo : 'Created By User'
    @Common.DocumentationRef : 'urn:sap-com:documentation:key?=type=DE&id=ABP_CREATION_USER'
    LocalCreatedBy : String(12) not null;
    @odata.Precision : 7
    @odata.Type : 'Edm.DateTimeOffset'
    @Core.Computed : true
    @Common.Label : 'Created On'
    @Common.QuickInfo : 'Creation Date Time'
    @Common.DocumentationRef : 'urn:sap-com:documentation:key?=type=DE&id=ABP_CREATION_TSTMPL'
    LocalCreatedAt : Timestamp;
    @Core.Computed : true
    @Common.IsUpperCase : true
    @Common.Label : 'Changed By'
    @Common.QuickInfo : 'Local Instance Last Changed By User'
    @Common.DocumentationRef : 'urn:sap-com:documentation:key?=type=DE&id=ABP_LOCINST_LASTCHANGE_USER'
    LocalLastChangedBy : String(12) not null;
    @odata.Precision : 7
    @odata.Type : 'Edm.DateTimeOffset'
    @Core.Computed : true
    @Common.Label : 'Changed On'
    @Common.QuickInfo : 'Local Instance Last Change Date Time'
    @Common.DocumentationRef : 'urn:sap-com:documentation:key?=type=DE&id=ABP_LOCINST_LASTCHANGE_TSTMPL'
    LocalLastChangedAt : Timestamp;
    @odata.Precision : 7
    @odata.Type : 'Edm.DateTimeOffset'
    @Core.Computed : true
    @Common.Label : 'Changed On'
    @Common.QuickInfo : 'Last Change Date Time'
    @Common.DocumentationRef : 'urn:sap-com:documentation:key?=type=DE&id=ABP_LASTCHANGE_TSTMPL'
    LastChangedAt : Timestamp;
    SAP__Messages : many SAP__Message not null;
    _Parent : Association to one ZC_AINV_INV_H on _Parent.InvoiceUUID = InvoiceUUID;
  };

  @cds.external : true
  @cds.persistence.skip : true
  @Common.Label : 'AInvoicer Invoice Reference Projection'
  @Common.Messages : SAP__Messages
  @Capabilities.NavigationRestrictions.RestrictedProperties : [
    {
      NavigationProperty: _Parent,
      InsertRestrictions: { Insertable: false },
      DeepUpdateSupport: { Supported: false }
    }
  ]
  @Capabilities.SearchRestrictions.Searchable : false
  @Capabilities.UpdateRestrictions.DeltaUpdateSupported : true
  @Capabilities.UpdateRestrictions.NonUpdatableNavigationProperties : [ '_Parent' ]
  @Capabilities.UpdateRestrictions.QueryOptions.SelectSupported : true
  @Capabilities.DeepUpdateSupport.ContentIDSupported : true
  @Capabilities.InsertRestrictions.Insertable : false
  @Core.OptimisticConcurrency : [ 'LocalLastChangedAt' ]
  entity ZC_AINV_INV_REF {
    @Core.Computed : true
    @Common.Label : 'UUID'
    @Common.QuickInfo : '16 Byte UUID in 16 Bytes (Raw Format)'
    key RefUUID : UUID not null;
    @Core.Computed : true
    @Common.Label : 'UUID'
    @Common.QuickInfo : '16 Byte UUID in 16 Bytes (Raw Format)'
    InvoiceUUID : UUID not null;
    SourceSystem : String(20) not null;
    SourceModule : String(5) not null;
    ReferenceRole : String(15) not null;
    SourceDocNo : String(35) not null;
    @Common.IsDigitSequence : true
    SourceFiscalYear : String(4) not null;
    @Common.IsDigitSequence : true
    SourceItemNo : String(6) not null;
    @Core.Computed : true
    @Common.IsUpperCase : true
    @Common.Label : 'Created By'
    @Common.QuickInfo : 'Created By User'
    @Common.DocumentationRef : 'urn:sap-com:documentation:key?=type=DE&id=ABP_CREATION_USER'
    LocalCreatedBy : String(12) not null;
    @odata.Precision : 7
    @odata.Type : 'Edm.DateTimeOffset'
    @Core.Computed : true
    @Common.Label : 'Created On'
    @Common.QuickInfo : 'Creation Date Time'
    @Common.DocumentationRef : 'urn:sap-com:documentation:key?=type=DE&id=ABP_CREATION_TSTMPL'
    LocalCreatedAt : Timestamp;
    @Core.Computed : true
    @Common.IsUpperCase : true
    @Common.Label : 'Changed By'
    @Common.QuickInfo : 'Local Instance Last Changed By User'
    @Common.DocumentationRef : 'urn:sap-com:documentation:key?=type=DE&id=ABP_LOCINST_LASTCHANGE_USER'
    LocalLastChangedBy : String(12) not null;
    @odata.Precision : 7
    @odata.Type : 'Edm.DateTimeOffset'
    @Core.Computed : true
    @Common.Label : 'Changed On'
    @Common.QuickInfo : 'Local Instance Last Change Date Time'
    @Common.DocumentationRef : 'urn:sap-com:documentation:key?=type=DE&id=ABP_LOCINST_LASTCHANGE_TSTMPL'
    LocalLastChangedAt : Timestamp;
    @odata.Precision : 7
    @odata.Type : 'Edm.DateTimeOffset'
    @Core.Computed : true
    @Common.Label : 'Changed On'
    @Common.QuickInfo : 'Last Change Date Time'
    @Common.DocumentationRef : 'urn:sap-com:documentation:key?=type=DE&id=ABP_LASTCHANGE_TSTMPL'
    LastChangedAt : Timestamp;
    SAP__Messages : many SAP__Message not null;
    _Parent : Association to one ZC_AINV_INV_H on _Parent.InvoiceUUID = InvoiceUUID;
  };
};

