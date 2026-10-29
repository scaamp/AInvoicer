using { customer.cap_java as db } from '../db/schema';

@requires: 'authenticated-user'
service KsefService {
  @(restrict: [
    { grant: 'CREATE', to: 'KsefOperator', where: (taxpayerNip = $user.taxpayerNips) },
    { grant: 'READ', to: 'KsefOperator', where: (taxpayerNip = $user.taxpayerNips) }
  ])
  entity KsefOperations as projection on db.KsefOperations excluding { invoice, snapshot, confirmedXmlSha256, submission, writeback };

  @(restrict: [
    { grant: 'READ', to: 'KsefOperator', where: (taxpayerNip = $user.taxpayerNips) }
  ])
  @readonly entity KsefInvoices as projection on db.KsefInvoices excluding { xmlStorageRef, upoStorageRef };

  /**
   * Read-only stan integracji per faktura (ADR-0005, domeny B i C). Bez danych finansowych SAP,
   * bez XML/UPO, JWT i referencji magazynu. UI odpytuje wyłącznie UUID widocznej strony
   * (`invoiceUUID eq ... or ...`); limit chroni przed pobraniem całej historii.
   */
  @(restrict: [
    { grant: 'READ', to: 'KsefOperator', where: (taxpayerNip = $user.taxpayerNips) }
  ])
  @readonly
  @cds.query.limit: { default: 100, max: 200 }
  entity InvoiceIntegrationStatus as projection on db.KsefOperations {
    key ID                        as operationId,
        sapDocumentRef            as invoiceUUID,
        taxpayerNip,
        direction,
        environment,
        operationType,
        status                    as integrationStatus,
        referenceNumber           as ksefReference,
        ksefNumber,
        errorCode,
        submission.state          as submissionState,
        case when submission.upoStorageRef is null then false else true end as upoAvailable : Boolean,
        writeback.state           as writebackState,
        writeback.outcome         as writebackOutcome,
        writeback.stateChangedAt  as writebackChangedAt,
        createdAt,
        modifiedAt
  };

  type SendRequestResult {
    operationId : UUID;
    // Stan zlecenia (QUEUED). To NIE jest przyjęcie faktury przez KSeF.
    status      : String(40);
    attempt     : Integer;
  }

  /**
   * Jedyny publiczny sposób zlecenia wysyłki faktury SAP do KSeF TEST. Wejście to wyłącznie
   * InvoiceUUID; NIP, CompanyCode, kwoty, XML i klucz idempotencji CAP wyznacza sam z RAP.
   */
  @(requires: 'KsefOperator')
  action requestSend(invoiceUUID : UUID) returns SendRequestResult;
}

// Status i dane zwracane przez KSeF może zmieniać wyłącznie logika serwerowa.
annotate KsefService.KsefOperations with {
  environment     @readonly;
  status          @readonly;
  referenceNumber @readonly;
  ksefNumber      @readonly;
  errorCode       @readonly;
  errorMessage    @readonly;
};

// Stan biznesowy i numer KSeF ustawia wyłącznie zaufana logika backendu.
annotate KsefService.KsefInvoices with {
  businessStatus @readonly;
  ksefNumber     @readonly;
};
