package customer.cap_java.ksef.inbound;

import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQuerySubjectType;

/**
 * Rola podmiotu kontekstu na fakturze — odpowiada filtrowi {@code subjectType} w
 * {@code POST /invoices/query/metadata} i {@code POST /invoices/exports} (Subject1/2/3/SubjectAuthorized).
 *
 * <p>Rola jest częścią zakresu synchronizacji i uprawnienia do dokumentu. Ta sama faktura może być widoczna
 * temu samemu podatnikowi w kilku rolach; każda rola to osobny checkpoint i osobny wpis dostępu
 * (patrz {@code sync.InvoiceDeduplicationPolicy}). Rola nie rozszerza autoryzacji na inne NIP.
 */
public enum KsefSubjectRole {
    /** Podmiot1 — sprzedawca; faktury wystawione (sprzedażowe, OUTGOING). */
    SELLER(InvoiceQuerySubjectType.SUBJECT1, Direction.OUTGOING),
    /** Podmiot2 — nabywca; faktury otrzymane (zakupowe, INCOMING). */
    BUYER(InvoiceQuerySubjectType.SUBJECT2, Direction.INCOMING),
    /** Podmiot3 — np. odbiorca, faktor, podmiot pierwotny; nie jest automatycznie fakturą zakupową. */
    THIRD_PARTY(InvoiceQuerySubjectType.SUBJECT3, Direction.OTHER),
    /** Podmiot upoważniony — np. organ egzekucyjny, przedstawiciel podatkowy. */
    AUTHORIZED(InvoiceQuerySubjectType.SUBJECTAUTHORIZED, Direction.OTHER);

    /** Kierunek z perspektywy podatnika kontekstu. OTHER nie jest ani sprzedażą, ani zakupem. */
    public enum Direction { OUTGOING, INCOMING, OTHER }

    private final InvoiceQuerySubjectType sdkType;
    private final Direction direction;

    KsefSubjectRole(InvoiceQuerySubjectType sdkType, Direction direction) {
        this.sdkType = sdkType;
        this.direction = direction;
    }

    InvoiceQuerySubjectType sdkType() {
        return sdkType;
    }

    public Direction direction() {
        return direction;
    }
}
