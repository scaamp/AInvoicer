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
  environment     : String(4) not null default 'TEST';
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

// Wyłącznie wewnętrzna persystencja writeback; brak projekcji w publicznym OData.
// ID = UUID potwierdzonej operacji, zarazem stabilny RAP WriteRequestId.
entity SapResultWrites : managed {
  key ID                     : UUID;
      invoiceUUID            : UUID not null;
      taxpayerNip            : String(10) not null;
      companyCode            : String(4) not null;
      ksefNumber             : String(50) not null;
      environment            : String(4) not null default 'TEST';
      // Tekst ISO zachowuje 100 ns bez zależności od precyzji kolumny timestamp w DB.
      expectedVersion        : String(40) not null;
      state                  : String(32) not null default 'PENDING';
      outcome                : String(40);
      sapRecordedAt          : Timestamp;
      sapRecordedRequestId   : UUID;
      retryNotBefore         : Timestamp;
}

entity SapResultWriteHistory : cuid {
  writeId     : UUID not null;
  occurredAt  : Timestamp not null;
  outcome     : String(40) not null;
}
