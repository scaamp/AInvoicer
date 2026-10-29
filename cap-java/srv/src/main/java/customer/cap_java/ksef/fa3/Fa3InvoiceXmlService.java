package customer.cap_java.ksef.fa3;

import org.springframework.stereotype.Component;

/**
 * Publiczne API komponentu FA(3) dla integracji KSeF (kontrakt dla workera wysyłki):
 * <ol>
 * <li>{@link #prepare(Fa3Invoice)} — walidacja domenowa → generowanie XML → walidacja XSD; zwraca
 * {@link Fa3Document} z dokładnymi bajtami i SHA-256.</li>
 * <li>{@link #verify(byte[])} — ponowna walidacja XSD bajtów już przygotowanych (np. odczytanych
 * z trwałego magazynu przed wysyłką); nie zmienia ani nie re-serializuje bajtów.</li>
 * </ol>
 * Komponent nie wysyła niczego do KSeF, nie zapisuje do bazy i nie tworzy potwierdzeń. Rejestracja
 * migawki ({@code KsefInvoiceSnapshotRegistry.register}) i wysyłka SDK należą do wywołującego —
 * obie operacje muszą użyć tych samych bajtów {@link Fa3Document#xml()}.
 */
@Component
public class Fa3InvoiceXmlService {
    private final Fa3DomainValidator domainValidator;
    private final Fa3XmlWriter writer;
    private final Fa3SchemaValidator schemaValidator;

    /** Schemat XSD jest kompilowany raz; instancja jest bezpieczna wątkowo. */
    public Fa3InvoiceXmlService() {
        this(new Fa3DomainValidator(), new Fa3XmlWriter(), new Fa3SchemaValidator());
    }

    Fa3InvoiceXmlService(Fa3DomainValidator domainValidator, Fa3XmlWriter writer, Fa3SchemaValidator schemaValidator) {
        this.domainValidator = domainValidator;
        this.writer = writer;
        this.schemaValidator = schemaValidator;
    }

    /**
     * @throws Fa3ValidationException z naruszeniami domenowymi (XML nie jest wtedy generowany)
     *         albo z kodami XSD_INVALID, gdy wygenerowany dokument nie spełnia oficjalnego XSD
     */
    public Fa3Document prepare(Fa3Invoice invoice) {
        domainValidator.requireValid(invoice);
        byte[] xml = writer.write(invoice);
        schemaValidator.requireValid(xml);
        return new Fa3Document(xml);
    }

    /** Waliduje istniejące bajty XSD FA(3) i opakowuje je bez zmian (te same bajty, ten sam SHA-256). */
    public Fa3Document verify(byte[] xml) {
        schemaValidator.requireValid(xml);
        return new Fa3Document(xml);
    }
}
