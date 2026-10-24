package customer.cap_java;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.ServiceName;

import cds.gen.ksefservice.KsefOperations;

/** Waliduje minimalny kontrakt wejściowy CREATE operacji KSeF. */
@Component
@ServiceName("KsefService")
public class KsefOperationsHandler implements EventHandler {

	private static final Set<String> OPERATION_TYPES = Set.of(
		"SEND_INVOICE", "CHECK_STATUS", "RECEIVE_INVOICE");
	private static final int[] NIP_WEIGHTS = { 6, 5, 7, 2, 3, 4, 5, 6, 7 };

	/** Sprawdza dane CREATE; tożsamość i zakres NIP egzekwuje CDS @restrict. */
	@Before(event = CqnService.EVENT_CREATE, entity = "KsefService.KsefOperations")
	public void validateCreate(List<KsefOperations> operations) {
		for (KsefOperations operation : operations) {
			if (!isValidNip(operation.getTaxpayerNip())) {
				throw new ServiceException(ErrorStatuses.BAD_REQUEST,
					"taxpayerNip must contain 10 digits and a valid checksum");
			}

			String idempotencyKey = operation.getIdempotencyKey();
			if (idempotencyKey == null || idempotencyKey.isBlank()) {
				throw new ServiceException(ErrorStatuses.BAD_REQUEST, "idempotencyKey is required");
			}
			operation.setIdempotencyKey(idempotencyKey.trim());

			if (!"INBOUND".equals(operation.getDirection()) && !"OUTBOUND".equals(operation.getDirection())) {
				throw new ServiceException(ErrorStatuses.BAD_REQUEST,
					"direction must be INBOUND or OUTBOUND");
			}
			String operationType = operation.getOperationType();
			if (operationType == null || !OPERATION_TYPES.contains(operationType)) {
				throw new ServiceException(ErrorStatuses.BAD_REQUEST,
					"operationType is not supported");
			}

			// Status jest polem tylko do odczytu; stan początkowy nadaje wyłącznie serwer.
			operation.setStatus("NEW");
		}
	}

	private boolean isValidNip(String nip) {
		if (nip == null || !nip.matches("[0-9]{10}")) {
			return false;
		}

		int sum = 0;
		for (int i = 0; i < NIP_WEIGHTS.length; i++) {
			sum += Character.digit(nip.charAt(i), 10) * NIP_WEIGHTS[i];
		}
		int checksum = sum % 11;
		return checksum != 10 && checksum == Character.digit(nip.charAt(9), 10);
	}
}
