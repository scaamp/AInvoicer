namespace customer.cap_java;

using { cuid, managed } from '@sap/cds/common';

type KsefDirection : String enum {
  INBOUND;
  OUTBOUND;
}

entity KsefInvoices : cuid, managed {
  taxpayerNip    : String(10) not null;
  direction      : KsefDirection not null;
  sapDocumentRef : String(120);
  invoiceNumber  : String(100);
  issueDate      : Date;
  sellerNip      : String(10);
  buyerNip       : String(10);
  ksefNumber     : String(50);
  businessStatus : String(40) not null default 'UNCONFIRMED';
  // To tylko referencje do przyszłego trwałego magazynu, bez zawartości XML/UPO.
  xmlStorageRef  : String(500);
  upoStorageRef  : String(500);
}

entity KsefOperations : cuid, managed {
  sapDocumentRef  : String(120);
  taxpayerNip     : String(10) not null;
  direction       : KsefDirection not null;
  operationType   : String(40) not null;
  status          : String(40) not null default 'NEW';
  idempotencyKey  : String(128) not null;
  referenceNumber : String(100);
  ksefNumber      : String(50);
  errorCode       : String(100);
  // Zapisuj wyłącznie bezpieczny, zredagowany opis; bez tokenów i danych wrażliwych.
  errorMessage    : String(1000);
  invoice          : Association to one KsefInvoices;
}

annotate KsefOperations with @assert.unique.idempotency: [taxpayerNip, idempotencyKey];
