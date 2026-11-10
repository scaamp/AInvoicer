package customer.cap_java.ksef.inbound;

import customer.cap_java.ksef.KsefDocumentStore;

/**
 * Kontrakt przyszłego providera CF: put tych samych bajtów musi zwracać tę samą referencję,
 * potwierdzenie oznacza trwały zapis, get zwraca identyczne bajty po restarcie/innej instancji.
 * Provider wymaga izolacji środowisk, szyfrowania i polityki retencji/GC osieroconych obiektów.
 * Brak implementacji produkcyjnej w repo; samo ustawienie property nie nadaje tej zdolności.
 */
public interface DurableInboundDocumentStore extends KsefDocumentStore {
}
