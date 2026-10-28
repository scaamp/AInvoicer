// Kontrakt importowany z rzeczywistego H01 $metadata (2026-10-28).
using { com.sap.gateway.srvd_a2x.zapi_ainv_inv_h_o4.v0001 as H01 } from './external/AinvoicerH01';

// Pełna przestrzeń nazw jest potrzebna do kwalifikowanego URI RAP action.
// Import nie otwiera dostępu do zewnętrznej usługi użytkownikom publicznego CAP API.
annotate H01 with @requires: 'internal-user';
