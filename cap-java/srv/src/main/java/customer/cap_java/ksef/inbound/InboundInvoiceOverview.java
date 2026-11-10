package customer.cap_java.ksef.inbound;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Wewnętrzny kontrakt INT-02; brak XML ref, grantu i danych uwierzytelniających. Kwota to plain decimal. */
public record InboundInvoiceOverview(UUID id, String environment, String ksefNumber, String invoiceNumber,
        LocalDate issueDate, Instant acquisitionDate, String sellerNip, String sellerName,
        String buyerIdentifierType, String buyerIdentifierValue, String currency, String grossAmount,
        String buyerName, String invoiceType, String formCode,
        String ingestionStatus, String parseStatus, String integrityStatus, String sapPostingStatus,
        boolean xmlAvailable) {
}
