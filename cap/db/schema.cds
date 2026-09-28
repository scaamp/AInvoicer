namespace ainvoicer;

using cuid from '@sap/cds/common';

entity Invoices : cuid {
  companyCode    : String(4);
  fiscalYear     : String(4);
  documentNo     : String(10);
  lineItem       : String(3);
  postingDate    : Date;
  documentDate   : Date;
  vendor         : String(10);
  currencyCode   : String(5);
  amountDocument : Decimal(13,2);
}
