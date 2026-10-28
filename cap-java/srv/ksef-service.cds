using { customer.cap_java as db } from '../db/schema';

@requires: 'authenticated-user'
service KsefService {
  @(restrict: [
    { grant: 'CREATE', to: 'KsefOperator', where: (taxpayerNip = $user.taxpayerNips) },
    { grant: 'READ', to: 'KsefOperator', where: (taxpayerNip = $user.taxpayerNips) }
  ])
  entity KsefOperations as projection on db.KsefOperations excluding { invoice };

  @(restrict: [
    { grant: 'READ', to: 'KsefOperator', where: (taxpayerNip = $user.taxpayerNips) }
  ])
  @readonly entity KsefInvoices as projection on db.KsefInvoices excluding { xmlStorageRef, upoStorageRef };
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
