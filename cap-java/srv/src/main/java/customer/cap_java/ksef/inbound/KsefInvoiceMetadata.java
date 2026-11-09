package customer.cap_java.ksef.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Wewnętrzne, niezależne od SDK metadane faktury z {@code POST /invoices/query/metadata}
 * (model {@code InvoiceMetadata} OpenAPI KSeF 2.0) wraz z kontekstem synchronizacji.
 *
 * <p>Zasady: pola nieobecne w odpowiedzi pozostają {@code null} (bez wartości domyślnych);
 * daty to {@code OffsetDateTime} znormalizowane do UTC albo {@code LocalDate} dla {@code issueDate}.
 *
 * <p><b>Kwoty są informacyjne.</b> SDK 3.0.29 deserializuje {@code netAmount/grossAmount/vatAmount}
 * do {@code Double} (OpenAPI: {@code number/double}); konwersja {@code BigDecimal.valueOf(double)} odtwarza
 * najkrótszy zapis dziesiętny, ale precyzja jest ograniczona już na wejściu. Wartości autorytatywne
 * pochodzą z XML ({@code fa3.SecureFa3InvoiceParser}, {@code BigDecimal} z tekstu). Według OpenAPI
 * {@code vatAmount} jest wyrażony w PLN, a netto/brutto w walucie {@code currency}.
 *
 * @param environment          środowisko KSeF (część tożsamości dokumentu)
 * @param contextNip           NIP kontekstu, w którym odczytano metadane (zakres autoryzacji)
 * @param role                 rola kontekstu w zapytaniu (Subject1/2/3/Authorized)
 * @param ksefNumber           numer KSeF (zwalidowany: wzorzec + CRC-8)
 * @param invoiceNumber        numer faktury nadany przez wystawcę (P_2)
 * @param issueDate            data wystawienia (P_1)
 * @param invoicingDate        data przyjęcia w KSeF do przetwarzania (może być null)
 * @param acquisitionDate      data nadania numeru KSeF = data otrzymania faktury (może być null)
 * @param permanentStorageDate data trwałego zapisu — klucz synchronizacji przyrostowej (HWM)
 * @param sellerNip            NIP sprzedawcy
 * @param sellerName           nazwa sprzedawcy (opcjonalna)
 * @param buyerIdentifierType  typ identyfikatora nabywcy wg KSeF: Nip, VatUe, Other, None
 * @param buyerIdentifier      wartość identyfikatora nabywcy (null dla None)
 * @param buyerName            nazwa nabywcy (opcjonalna)
 * @param netAmount            łączna kwota netto (informacyjna)
 * @param grossAmount          łączna kwota brutto (informacyjna)
 * @param vatAmountPln         łączna kwota VAT w PLN (informacyjna)
 * @param currency             kod waluty ISO 4217
 * @param invoiceType          rodzaj faktury wg KSeF (np. Vat, Kor, Zal, VatPef, VatRr)
 * @param formCode             struktura dokumentu (systemCode/schemaVersion/value)
 * @param invoiceHashBase64    SHA-256 dokumentu XML w Base64 (44 znaki) — do weryfikacji pobranych bajtów
 * @param hasAttachment        czy faktura ma załącznik (null, gdy brak w odpowiedzi)
 * @param selfInvoicing        samofakturowanie (null, gdy brak w odpowiedzi)
 * @param invoicingMode        tryb wystawienia wg KSeF, np. Online/Offline (null, gdy brak)
 */
public record KsefInvoiceMetadata(
        KsefEnvironment environment,
        String contextNip,
        KsefSubjectRole role,
        String ksefNumber,
        String invoiceNumber,
        LocalDate issueDate,
        OffsetDateTime invoicingDate,
        OffsetDateTime acquisitionDate,
        OffsetDateTime permanentStorageDate,
        String sellerNip,
        String sellerName,
        String buyerIdentifierType,
        String buyerIdentifier,
        String buyerName,
        BigDecimal netAmount,
        BigDecimal grossAmount,
        BigDecimal vatAmountPln,
        String currency,
        String invoiceType,
        FormCode formCode,
        String invoiceHashBase64,
        Boolean hasAttachment,
        Boolean selfInvoicing,
        String invoicingMode) {

    /** Struktura logiczna dokumentu, np. {@code FA (3) / 1-0E / FA}. */
    public record FormCode(String systemCode, String schemaVersion, String value) {
        public boolean isFa3() {
            return "FA (3)".equals(systemCode) && "1-0E".equals(schemaVersion) && "FA".equals(value);
        }
    }

    public KsefSubjectRole.Direction direction() {
        return role.direction();
    }
}
