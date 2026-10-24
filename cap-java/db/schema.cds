namespace customer.cap_java;

using { cuid, managed } from '@sap/cds/common';

type KsefOperationDirection : String enum {
  INBOUND;
  OUTBOUND;
}

entity KsefOperations : cuid, managed {
  sapDocumentRef  : String(120);
  taxpayerNip     : String(10) not null;
  direction       : KsefOperationDirection not null;
  operationType   : String(40) not null;
  status          : String(40) not null default 'NEW';
  idempotencyKey  : String(128) not null;
  referenceNumber : String(100);
  ksefNumber      : String(50);
  errorCode       : String(100);
  // Zapisuj wyłącznie bezpieczny, zredagowany opis; bez tokenów i danych wrażliwych.
  errorMessage    : String(1000);
}

annotate KsefOperations with @assert.unique.idempotency: [taxpayerNip, idempotencyKey];
