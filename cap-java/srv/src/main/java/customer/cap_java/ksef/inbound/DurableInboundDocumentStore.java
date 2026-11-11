package customer.cap_java.ksef.inbound;

import customer.cap_java.ksef.KsefDocumentStore;

/**
 * Kontrakt przyszłego providera CF: put tych samych bajtów musi zwracać tę samą referencję,
 * potwierdzenie oznacza trwały zapis, get zwraca identyczne bajty po restarcie/innej instancji.
 * Provider wymaga izolacji środowisk, szyfrowania i polityki retencji/GC osieroconych obiektów.
 * Brak implementacji produkcyjnej w repo; samo ustawienie property nie nadaje tej zdolności.
 * KSEF-STOR-01: adapter {@code customer.cap_java.ksef.storage.ContentAddressedDocumentStore#forScope}
 * (Azure Blob / SAP Object Store) jest przetestowany offline; LIVE i podłączenie w CF pozostają INT-03.
 */
public interface DurableInboundDocumentStore extends KsefDocumentStore {
}
