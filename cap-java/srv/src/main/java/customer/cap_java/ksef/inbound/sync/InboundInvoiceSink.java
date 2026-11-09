package customer.cap_java.ksef.inbound.sync;

import customer.cap_java.ksef.inbound.KsefInvoiceMetadata;

/**
 * Odbiorca dokumentów okna synchronizacji (KSEF-IN-02: pobranie XML, zapis bajtów w {@code KsefDocumentStore},
 * rekord stagingu, decyzja {@link InvoiceDeduplicationPolicy}).
 *
 * <p>Kontrakt: metoda musi być <b>idempotentna</b> (to samo okno może zostać przetworzone ponownie po błędzie
 * albo po obcięciu wyniku) i może zakończyć się sukcesem tylko wtedy, gdy dane dokumentu są już
 * <b>trwale utrwalone</b>. Wyjątek przerywa okno i blokuje przesunięcie checkpointu.
 */
@FunctionalInterface
public interface InboundInvoiceSink {
    void accept(KsefInvoiceMetadata metadata);
}
