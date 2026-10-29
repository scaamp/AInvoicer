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

// Niezmienny dowód tego, co przygotowano do wysyłki do KSeF (wyłącznie INSERT; bez UPDATE/DELETE).
// Nie jest dowodem wysyłki ani akceptacji. Pełny XML pozostaje w przyszłym trwałym magazynie.
entity KsefInvoiceSnapshots : cuid, managed {
  invoiceUUID        : UUID not null;
  taxpayerNip        : String(10) not null;
  direction          : KsefDirection not null;
  environment        : String(4) not null;
  companyCode        : String(4) not null;
  schemaVersion      : String(20) not null;
  // Wersja nagłówka SAP (LocalLastChangedAt, tekst ISO z 100 ns) z chwili przygotowania XML.
  sapHeaderVersion   : String(40) not null;
  // SHA-256 (hex) kanonicznej listy pozycji SAP: ItemUUID + LocalLastChangedAt.
  itemsFingerprint   : String(64) not null;
  // SHA-256 (hex) dokładnych bajtów XML FA(3) przekazanych do wysyłki.
  xmlSha256          : String(64) not null;
  xmlByteLength      : Integer not null;
  // Pochodzenie danych migawki. SAP_RAP = CAP sam odczytał fakturę z RAP Remote OData (requestSend);
  // UNVERIFIED = dane dostarczone inną drogą (np. syntetyczny fixture SDK). Writeback do RAP
  // jest dozwolony wyłącznie dla SAP_RAP (ADR-0005).
  source             : String(20) not null default 'UNVERIFIED';
}

annotate KsefInvoiceSnapshots with @assert.unique.document: [invoiceUUID, environment, taxpayerNip, xmlSha256];

// Atomowa rezerwacja wysyłki: klucz główny (invoiceUUID, environment) gwarantuje w bazie co najwyżej
// jedną aktywną próbę SEND na fakturę i środowisko (H2 i HANA, bez SQL specyficznego dla dialektu).
// Kolejna próba po potwierdzonym odrzuceniu przepina wiersz warunkowym UPDATE (CAS po operationId
// i attempt). Wiersz nigdy nie jest usuwany, więc restart ani ponowienie HTTP jej nie omija.
entity KsefSendReservations : managed {
  key invoiceUUID : UUID;
  key environment : String(4);
      taxpayerNip : String(10) not null;
      companyCode : String(4) not null;
      operationId : UUID not null;
      attempt     : Integer not null default 1;
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
  // Migawka dokumentu faktycznie przygotowanego do wysyłki oraz skrót SHA-256 (hex) potwierdzony
  // przez KSeF przy akceptacji. Ustawia wyłącznie zaufany producent; brak publikacji w OData.
  snapshot         : Association to one KsefInvoiceSnapshots;
  confirmedXmlSha256 : String(64);
  // Nawigacje tylko do odczytu (bez kolumn FK): zlecenie KSeF i writeback SAP mają ID = ID operacji.
  // Publikowane wyłącznie przez read-only projekcję KsefService.InvoiceIntegrationStatus.
  submission       : Association to one KsefSubmissions on submission.ID = $self.ID;
  writeback        : Association to one SapResultWrites on writeback.ID = $self.ID;
}

annotate KsefOperations with @assert.unique.idempotency: [taxpayerNip, idempotencyKey];

// Wewnętrzne zlecenie KSeF TEST. ID = istniejąca operacja; XML/UPO pozostają w magazynie.
// Znacznik etapu jest commitowany PRZED każdym efektem. Restart w OPENING/SENDING/CLOSING
// wymaga odczytowej rekoncyliacji; klucze AES i JWT nigdy nie trafiają do persystencji.
entity KsefSubmissions : managed {
  key ID             : UUID;
  snapshotId         : UUID not null;
  taxpayerNip        : String(10) not null;
  state              : String(40) not null default 'PREPARED';
  xmlStorageRef      : String(500) not null;
  xmlSha256          : String(64) not null;
  sessionReference   : String(100);
  invoiceReference   : String(100);
  ksefNumber         : String(50);
  upoStorageRef      : String(500);
  closeAttempted     : Boolean not null default false;
  closeConfirmed     : Boolean not null default false;
  claimToken         : UUID;
  leaseUntil         : Timestamp;
  readAttempts       : Integer not null default 0;
  retryNotBefore     : Timestamp;
  lastCode           : String(100);
}

// Jedna migawka nie może być wysłana ponownie pod nowym kluczem operacji.
annotate KsefSubmissions with @assert.unique.snapshot: [snapshotId];

entity KsefSubmissionHistory : cuid {
  submissionId : UUID not null;
  occurredAt   : Timestamp not null;
  state        : String(40) not null;
  code         : String(100);
}

// Wyłącznie wewnętrzna persystencja writeback; brak projekcji w publicznym OData.
// ID = UUID potwierdzonej operacji, zarazem stabilny RAP WriteRequestId.
// Stany: PENDING (czeka na dostarczenie), PROCESSING (przejęte, trwa HTTP; ważne do leaseUntil),
// SUCCEEDED, RECONCILIATION_REQUIRED (odpowiedź SAP wymaga decyzji) i RETRY_EXHAUSTED
// (automatyczne ponowienia wyczerpane; wznowienie wyłącznie kontrolowane).
entity SapResultWrites : managed {
  key ID                     : UUID;
      invoiceUUID            : UUID not null;
      snapshotId             : UUID not null;
      taxpayerNip            : String(10) not null;
      companyCode            : String(4) not null;
      ksefNumber             : String(50) not null;
      environment            : String(4) not null default 'TEST';
      // Tekst ISO zachowuje 100 ns bez zależności od precyzji kolumny timestamp w DB.
      expectedVersion        : String(40) not null;
      state                  : String(32) not null default 'PENDING';
      // Monotoniczna rewizja przejęcia/wznowienia; chroni także przejście PENDING -> PENDING.
      revision               : Integer64 not null default 0;
      // Ostatni bezpieczny kod wyniku lub błędu (Outcome albo SAP_HTTP_503 itp.).
      outcome                : String(40);
      sapRecordedAt          : Timestamp;
      sapRecordedRequestId   : UUID;
      retryNotBefore         : Timestamp;
      // Wszystkie rozpoczęte próby HTTP oraz próby od ostatniego wznowienia (budżet ponowień).
      attempts               : Integer not null default 0;
      cycleAttempts          : Integer not null default 0;
      lastAttemptAt          : Timestamp;
      // Chwila ostatniej zmiany stanu; podstawa wykrywania osieroconych poleceń.
      stateChangedAt         : Timestamp not null;
      // Dzierżawa przejęcia: finalizacja jest możliwa wyłącznie z pasującym tokenem.
      claimToken             : UUID;
      leaseUntil             : Timestamp;
}

// Numer KSeF nie jest globalnie unikalny w aplikacji (ten sam numer widzą sprzedawca i nabywca),
// lecz może być zapisany w SAP tylko jednej fakturze sprzedażowej na środowisko.
annotate SapResultWrites with @assert.unique.ksefNumber: [environment, ksefNumber];

entity SapResultWriteHistory : cuid {
  writeId     : UUID not null;
  occurredAt  : Timestamp not null;
  outcome     : String(40) not null;
  // Numer próby HTTP (0 dla zdarzeń niezwiązanych z próbą, np. QUEUED, RESUMED).
  attempt     : Integer not null default 0;
}
