package customer.cap_java.ksef.storage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import customer.cap_java.ksef.KsefFailure;
import customer.cap_java.ksef.inbound.DurableInboundDocumentStore;
import customer.cap_java.sap.KsefInvoiceSnapshotRegistry;

/**
 * Trwały, content-addressed magazyn dokładnych bajtów XML FA(3)/UPO nad {@link ObjectStorageClient}.
 *
 * <p>Kontrakt (zgodny z {@code KsefDocumentStore}): {@code put} tych samych bajtów w tym samym scope
 * zwraca tę samą referencję; zwrot następuje dopiero po potwierdzonym zapisie i odczycie kontrolnym
 * (długość + SHA-256); {@code get} zwraca identyczne bajty albo kończy się błędem.
 * Obiekty są tylko tworzone — nigdy nadpisywane ani usuwane przez aplikację.
 *
 * <p>Błędy są {@link KsefFailure} z kodem bez danych wrażliwych. Tylko UNAVAILABLE jest
 * {@code retryable}; ponowienie {@code put} po timeout jest bezpieczne, bo zapis jest create-only
 * pod adresem wyliczonym z treści (brak niejednoznacznego efektu ubocznego).
 */
public final class ContentAddressedDocumentStore {
    /** Wersja formatu metadanych obiektu (nagłówki x-ms-meta-*). */
    static final String FORMAT = "ksefdoc-v1";

    private final ObjectStorageClient client;
    private final ObjectStorageSettings settings;
    private final AzureObjectStoreBinding.Access access;
    private final Clock clock;
    private final Sleeper sleeper;

    /** Pauza między próbami; w testach podmieniana, aby nie spowalniać i nie zależeć od czasu. */
    @FunctionalInterface
    public interface Sleeper { void sleep(Duration duration) throws InterruptedException; }

    public ContentAddressedDocumentStore(ObjectStorageClient client, ObjectStorageSettings settings,
            AzureObjectStoreBinding.Access access, Clock clock, Sleeper sleeper) {
        this.client = java.util.Objects.requireNonNull(client);
        this.settings = java.util.Objects.requireNonNull(settings);
        this.access = java.util.Objects.requireNonNull(access);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.sleeper = java.util.Objects.requireNonNull(sleeper);
    }

    /**
     * Fabryka dla Cloud Foundry: credentiale wyłącznie z VCAP_SERVICES, konfiguracja jawna.
     * Nie rejestruje beana i niczego nie włącza; podłączenie do profilu CF należy do INT-03.
     */
    public static ContentAddressedDocumentStore fromVcapServices(String vcapServices, String instanceName,
            ObjectStorageSettings settings) {
        var binding = AzureObjectStoreBinding.fromVcapServices(vcapServices, instanceName);
        return new ContentAddressedDocumentStore(new AzureBlobSasClient(binding, settings), settings,
                binding.access(), Clock.systemUTC(), duration -> Thread.sleep(duration));
    }

    /**
     * Widok magazynu związany z jednym kontekstem dostępu; implementuje istniejący kontrakt
     * {@code put(byte[])}/{@code get(String)}, więc sink INBOUND i worker OUTBOUND nie wymagają zmian.
     */
    public DurableInboundDocumentStore forScope(DocumentScope scope) {
        java.util.Objects.requireNonNull(scope);
        if (!settings.allowedEnvironments().contains(scope.environment())) {
            throw new KsefFailure("KSEF_DOCUMENT_SCOPE_FORBIDDEN", false, false, null);
        }
        return new Scoped(scope);
    }

    /** Zapis idempotentny: create-only → odczyt kontrolny → referencja. */
    String put(DocumentScope scope, byte[] bytes) {
        if (access != AzureObjectStoreBinding.Access.READ_WRITE) { throw failure("KSEF_DOCUMENT_STORE_READ_ONLY"); }
        if (bytes == null || bytes.length == 0 || bytes.length > settings.maxDocumentBytes()) {
            throw failure("KSEF_DOCUMENT_SIZE_INVALID");
        }
        byte[] content = bytes.clone(); // wywołujący nie może zmienić bajtów pomiędzy hash a zapisem
        var reference = new DocumentReference(scope, KsefInvoiceSnapshotRegistry.sha256Hex(content));
        String key = reference.objectKey(settings.keyPrefix());
        Map<String, String> metadata = Map.of("ainvsha256", reference.sha256(),
                "ainvlength", Integer.toString(content.length), "ainvformat", FORMAT);
        withRetry(() -> client.createIfAbsent(key, content, metadata));
        // Zarówno CREATED, jak i ALREADY_EXISTS: cudzy/uszkodzony obiekt pod adresem treści = INTEGRITY.
        verify(reference, readExisting(key), content.length);
        return reference.value();
    }

    /** Odczyt z kontrolą scope, długości i SHA-256; brak rozróżnienia "obcy" vs "nie istnieje" dla obcych referencji. */
    byte[] get(DocumentScope scope, String value) {
        var reference = DocumentReference.parse(value);
        if (reference == null) { throw failure("KSEF_DOCUMENT_REFERENCE_INVALID"); }
        if (!reference.scope().equals(scope)) { throw failure("KSEF_DOCUMENT_ACCESS_DENIED"); }
        var stored = readExisting(reference.objectKey(settings.keyPrefix()));
        return verify(reference, stored, -1);
    }

    /**
     * Raport osieroconych obiektów partycji (zapis do storage bez finalizacji CAP, np. crash przed commit).
     * Tryb wyłącznie do odczytu: aplikacja nie ma operacji delete. Obiekty młodsze niż {@code minimumAge}
     * są pomijane, bo mogą należeć do trwającej finalizacji. Klucze nierozpoznane w partycji są liczone
     * osobno i wymagają ręcznej analizy (nie powstają w tym adapterze).
     */
    public OrphanReport orphanReport(DocumentScope scope, java.util.Set<String> referenced, Duration minimumAge) {
        if (!settings.allowedEnvironments().contains(scope.environment())) { throw failure("KSEF_DOCUMENT_SCOPE_FORBIDDEN"); }
        Instant cutoff = clock.instant().minus(minimumAge);
        String prefix = DocumentReference.partitionPrefix(settings.keyPrefix(), scope);
        var orphans = new java.util.ArrayList<String>();
        int scanned = 0, recent = 0, unknown = 0;
        String marker = null;
        do {
            String current = marker;
            var page = withRetry(() -> client.list(prefix, current, 1000));
            for (var object : page.objects()) {
                scanned++;
                var reference = DocumentReference.fromObjectKey(settings.keyPrefix(), scope, object.key());
                if (reference == null) {
                    unknown++;
                } else if (object.lastModified() == null || object.lastModified().isAfter(cutoff)) {
                    recent++;
                } else if (!referenced.contains(reference.value())) {
                    orphans.add(reference.value());
                }
            }
            marker = page.nextMarker();
        } while (marker != null);
        return new OrphanReport(scanned, recent, unknown, orphans);
    }

    /** Wynik audytu; referencje są wewnętrzne i nie mogą trafić do OData/UI. */
    public record OrphanReport(int scanned, int withinGracePeriod, int unrecognizedKeys, java.util.List<String> orphanReferences) {
        public OrphanReport { orphanReferences = java.util.List.copyOf(orphanReferences); }
    }

    private ObjectStorageClient.StoredObject readExisting(String key) {
        return withRetry(() -> client.read(key, settings.maxDocumentBytes()))
                .orElseThrow(() -> failure("KSEF_DOCUMENT_NOT_FOUND"));
    }

    private byte[] verify(DocumentReference reference, ObjectStorageClient.StoredObject stored, int expectedLength) {
        byte[] content = stored.content();
        String declaredHash = stored.metadata().get("ainvsha256");
        String declaredLength = stored.metadata().get("ainvlength");
        boolean ok = content.length > 0
                && (expectedLength < 0 || content.length == expectedLength)
                && reference.sha256().equals(KsefInvoiceSnapshotRegistry.sha256Hex(content))
                && (declaredHash == null || declaredHash.equals(reference.sha256()))
                && (declaredLength == null || declaredLength.equals(Integer.toString(content.length)));
        if (!ok) { throw failure("KSEF_DOCUMENT_INTEGRITY_FAILED"); }
        return content;
    }

    /** Ograniczone ponowienia wyłącznie dla UNAVAILABLE; pozostałe kategorie od razu jako błąd końcowy. */
    private <T> T withRetry(Supplier<T> operation) {
        for (int attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (ObjectStorageException error) {
                if (error.category() != ObjectStorageException.Category.UNAVAILABLE) { throw map(error); }
                long retryAfter = error.retryAfterSeconds();
                if (attempt >= settings.maxAttempts() || retryAfter > 2) {
                    Instant notBefore = retryAfter > 0 ? clock.instant().plusSeconds(retryAfter) : null;
                    throw new KsefFailure("KSEF_DOCUMENT_STORE_UNAVAILABLE", false, true, notBefore);
                }
                Duration pause = retryAfter > 0 ? Duration.ofSeconds(retryAfter)
                        : Duration.ofMillis((100L << (attempt - 1)) + ThreadLocalRandom.current().nextLong(50));
                try {
                    sleeper.sleep(pause);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new KsefFailure("KSEF_DOCUMENT_STORE_UNAVAILABLE", false, true, null);
                }
            }
        }
    }

    private static KsefFailure map(ObjectStorageException error) {
        return switch (error.category()) {
            case ACCESS_DENIED -> failure("KSEF_DOCUMENT_ACCESS_DENIED");
            case TOO_LARGE -> failure("KSEF_DOCUMENT_SIZE_INVALID");
            case CORRUPTED -> failure("KSEF_DOCUMENT_INTEGRITY_FAILED");
            case REJECTED -> failure("KSEF_DOCUMENT_STORE_REJECTED");
            case UNAVAILABLE -> new KsefFailure("KSEF_DOCUMENT_STORE_UNAVAILABLE", false, true, null);
        };
    }

    private static KsefFailure failure(String code) { return new KsefFailure(code, false, false, null); }

    /** Widok per scope; referencje innych scope są odrzucane przed jakimkolwiek wywołaniem sieci. */
    private final class Scoped implements DurableInboundDocumentStore {
        private final DocumentScope scope;

        Scoped(DocumentScope scope) { this.scope = scope; }

        @Override public String put(byte[] bytes) { return ContentAddressedDocumentStore.this.put(scope, bytes); }

        @Override public byte[] get(String reference) { return ContentAddressedDocumentStore.this.get(scope, reference); }

        @Override public String toString() { return "ScopedDocumentStore[" + scope.environment() + "/" + scope.channel() + "]"; }
    }
}
