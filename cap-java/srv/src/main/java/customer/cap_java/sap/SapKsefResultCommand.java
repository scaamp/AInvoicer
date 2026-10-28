package customer.cap_java.sap;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Niezmienny payload jednego zapisu OUTBOUND/TEST; ponowienie zachowuje wszystkie wartości. */
public record SapKsefResultCommand(UUID invoiceId, UUID requestId, String ksefNumber,
        String taxpayerNip, String companyCode, Instant expectedVersion) {

    public SapKsefResultCommand {
        Objects.requireNonNull(invoiceId, "InvoiceUUID required");
        Objects.requireNonNull(requestId, "WriteRequestId required");
        Objects.requireNonNull(expectedVersion, "ExpectedLocalLastChangedAt required");
        if (invoiceId.equals(new UUID(0, 0)) || requestId.equals(new UUID(0, 0))
                || ksefNumber == null || !ksefNumber.matches("[0-9A-Za-z-]{1,50}")
                || taxpayerNip == null || !taxpayerNip.matches("[0-9]{10}")
                || companyCode == null || companyCode.isBlank() || companyCode.length() > 4
                || !companyCode.equals(companyCode.trim()) || !expectedVersion.isAfter(Instant.EPOCH)
                || expectedVersion.getNano() % 100 != 0) {
            throw new IllegalArgumentException("INVALID_SAP_RESULT_COMMAND");
        }
    }
}
