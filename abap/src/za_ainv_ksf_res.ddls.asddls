@EndUserText.label: 'Apply confirmed KSeF result outcome'
define abstract entity ZA_AINV_KSF_RES {
  Outcome : abap.char(24);
  InvoiceUUID : sysuuid_x16;
  KsefNumber : abap.char(50);
  KsefEnvironment : abap.char(4);
  KsefRecordedAt : abp_creation_tstmpl;
  KsefWriteRequestId : sysuuid_x16;
}
