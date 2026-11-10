namespace customer.cap_java;

using { cuid, managed } from '@sap/cds/common';

// Staging KSeF jest niezależny od OUTBOUND i od przyszłego przypisania do spółki SAP.
entity KsefInboundDocuments : cuid, managed {
  environment           : String(4) not null;
  ksefNumber            : String(35) not null;
  invoiceNumber         : String(256) not null;
  issueDate             : Date;
  acquisitionDate       : Timestamp;
  permanentStorageDate  : Timestamp;
  sellerNip             : String(10) not null;
  sellerName            : String(512);
  buyerIdentifierType   : String(16) not null;
  buyerIdentifierValue  : String(256);
  currency              : String(3);
  // Kwota autorytatywna wyłącznie z poprawnie sparsowanego i zwalidowanego XML.
  grossAmount           : Decimal(18, 2);
  formSystemCode        : String(64);
  formSchemaVersion     : String(32);
  formValue             : String(32);
  invoiceType           : String(32);
  sha256                : String(64) not null;
  sizeBytes             : Integer64;
  xmlStorageRef         : String(500);
  ingestionStatus       : String(32) not null default 'PENDING';
  parseStatus           : String(32) not null default 'NOT_PARSED';
  integrityStatus       : String(32) not null default 'PENDING';
  sapPostingStatus      : String(32) not null default 'NOT_POSTED';
  lastCode              : String(64);
}
annotate KsefInboundDocuments with @assert.unique.identity: [environment, ksefNumber];

entity KsefInboundAccessGrants : cuid {
  documentId  : UUID not null;
  contextNip  : String(10) not null;
  subjectRole : String(16) not null;
  firstSeenAt : Timestamp not null;
  document    : Association to KsefInboundDocuments on document.ID = documentId;
}
annotate KsefInboundAccessGrants with @assert.unique.scope: [documentId, contextNip, subjectRole];

entity KsefInboundSyncCheckpoints {
  key environment      : String(4);
  key contextNip       : String(10);
  key subjectRole      : String(16);
      // UTC z dokładnie 9 cyframi ułamka: brak zaokrąglenia HWM przez H2/HANA Timestamp.
      continuationFrom : String(40) not null;
      version          : Integer64 not null;
      committedAt      : Timestamp not null;
}

// Historia append-only; nigdy nie nadpisujemy oczekiwanego hash ani oryginalnego XML.
entity KsefInboundIntegrityConflicts : cuid {
  documentId    : UUID not null;
  contextNip    : String(10) not null;
  subjectRole   : String(16) not null;
  occurredAt    : Timestamp not null;
  expectedHash  : String(64) not null;
  observedHash  : String(64) not null;
  reason        : String(64) not null;
}

// Dzierżawa i trwałe odroczenie Retry-After nie zmieniają zatwierdzonego HWM.
entity KsefInboundSyncLeases {
  key environment  : String(4);
  key contextNip   : String(10);
  key subjectRole  : String(16);
      claimToken   : UUID;
      leaseUntil   : Timestamp;
      retryNotBefore : Timestamp;
      attempts     : Integer64 not null default 0;
      lastCode     : String(64);
}
