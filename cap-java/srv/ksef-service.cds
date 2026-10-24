using { customer.cap_java as db } from '../db/schema';

@requires: 'authenticated-user'
service KsefService {
  @(restrict: [
    { grant: 'CREATE', to: 'KsefOperator', where: (taxpayerNip = $user.taxpayerNips) },
    { grant: 'READ', to: 'KsefOperator', where: (taxpayerNip = $user.taxpayerNips) }
  ])
  entity KsefOperations as projection on db.KsefOperations;
}

// Status i dane zwracane przez KSeF może zmieniać wyłącznie logika serwerowa.
annotate KsefService.KsefOperations with {
  status          @readonly;
  referenceNumber @readonly;
  ksefNumber      @readonly;
  errorCode       @readonly;
  errorMessage    @readonly;
};
