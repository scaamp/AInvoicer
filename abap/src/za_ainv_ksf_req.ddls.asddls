@EndUserText.label: 'Apply confirmed KSeF result request'
define abstract entity ZA_AINV_KSF_REQ {
  InvoiceUUID : sysuuid_x16;
  WriteRequestId : sysuuid_x16;
  KsefNumber : abap.char(50);
  Environment : abap.char(4);
  TaxpayerNip : abap.char(10);
  CompanyCode : abap.char(4);
  ExpectedLocalLastChangedAt : abp_locinst_lastchange_tstmpl;
}
