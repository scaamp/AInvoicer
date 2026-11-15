package customer.cap_java.ksef.storage;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;

import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.ksef.inbound.KsefEnvironment;
import customer.cap_java.ksef.inbound.KsefInboundClient;

/**
 * KSEF-STOR-04: fabryka magazynu dokumentów KSeF w prywatnej tabeli CAP {@code KsefStoredDocuments} (HANA BLOB).
 *
 * <p>Zwraca istniejący {@link ContentAddressedDocumentStore}, więc kontrakt {@code KsefDocumentStore}, format
 * referencji {@code ksefdoc:v1:…}, scope ({@link DocumentScope}) i weryfikacja SHA-256 są identyczne jak dla
 * adaptera Object Store. Zakres dostępu wiąże zaufany kod konfiguracji przez {@code forScope(...)} — nigdy dane
 * z OData. Izolację wdrożeń zapewnia osobny kontener HDI, a środowisk TEST/DEMO/PROD — kolumna {@code environment}
 * w PK oraz {@code allowedEnvironments} instancji.
 *
 * <p>Nie rejestruje beana Spring i niczego nie włącza: podłączenie do workera INBOUND/OUTBOUND i profilu CF
 * jest osobnym, zatwierdzanym krokiem; guard {@code KSEF_INBOUND_CLOUD_ACTIVATION_NOT_READY} pozostaje bez zmian.
 */
public final class HanaKsefDocumentStore {
    /**
     * Domyślny limit dokumentu = domyślny limit XML klienta INBOUND (3 MiB, KSeF: faktura z załącznikami).
     * Laboratoryjny limit 1 MiB {@code BlobService} celowo nie obowiązuje w tym adapterze.
     */
    public static final int DEFAULT_MAX_DOCUMENT_BYTES = KsefInboundClient.DEFAULT_MAX_INVOICE_BYTES;
    /** Twardy limit konfiguracji = twardy limit klienta INBOUND; XML/UPO jest materializowany w pamięci JVM. */
    public static final int HARD_MAX_DOCUMENT_BYTES = 16 * 1024 * 1024;
    /** Domyślny budżet bajtów jednocześnie przetwarzanych przez adapter: 4 dokumenty o maksymalnym rozmiarze. */
    public static final int DEFAULT_IN_FLIGHT_DOCUMENTS = 4;
    /** Twardy górny limit budżetu pamięci instancji (niezależnie od konfiguracji). */
    public static final long HARD_MAX_IN_FLIGHT_BYTES = 64L * 1024 * 1024;
    /** Maksymalne czekanie na budżet pamięci, zanim operacja zwróci chwilowy błąd (ponawiany z backoffem). */
    static final Duration DEFAULT_MEMORY_WAIT = Duration.ofSeconds(10);

    private HanaKsefDocumentStore() { }

    /** Magazyn z domyślnym limitem dokumentu i budżetem pamięci. */
    public static ContentAddressedDocumentStore create(PersistenceService db, CdsRuntime runtime,
            Set<KsefEnvironment> allowedEnvironments) {
        return create(db, runtime, allowedEnvironments, DEFAULT_MAX_DOCUMENT_BYTES);
    }

    /**
     * Magazyn z jawnym limitem (1 B … 16 MiB). Limit musi być ≥ limitowi klienta INBOUND; mniejszy powoduje
     * kontrolowane {@code KSEF_DOCUMENT_SIZE_INVALID} → {@code STORAGE_FAILED}, bez przesunięcia checkpointu.
     */
    public static ContentAddressedDocumentStore create(PersistenceService db, CdsRuntime runtime,
            Set<KsefEnvironment> allowedEnvironments, int maxDocumentBytes) {
        return create(db, runtime, allowedEnvironments, maxDocumentBytes,
                Math.min(HARD_MAX_IN_FLIGHT_BYTES, (long) DEFAULT_IN_FLIGHT_DOCUMENTS * maxDocumentBytes),
                DEFAULT_MEMORY_WAIT, Clock.systemUTC(), duration -> Thread.sleep(duration));
    }

    /** Wariant testowy/konfiguracyjny: budżet pamięci, zegar i pauza między ponowieniami wstrzykiwane. */
    static ContentAddressedDocumentStore create(PersistenceService db, CdsRuntime runtime,
            Set<KsefEnvironment> allowedEnvironments, int maxDocumentBytes, long maxInFlightBytes, Duration memoryWait,
            Clock clock, ContentAddressedDocumentStore.Sleeper sleeper) {
        if (maxDocumentBytes < 1 || maxDocumentBytes > HARD_MAX_DOCUMENT_BYTES
                || maxInFlightBytes < maxDocumentBytes || maxInFlightBytes > HARD_MAX_IN_FLIGHT_BYTES) {
            throw new IllegalArgumentException("KSEF_DOCUMENT_STORE_LIMIT_INVALID");
        }
        // Pola hostów/timeoutów HTTP ObjectStorageSettings nie są używane przez klienta CQN (brak sieci).
        var settings = new ObjectStorageSettings(HanaBlobObjectStorageClient.KEY_NAMESPACE, allowedEnvironments,
                maxDocumentBytes, Duration.ofSeconds(5), Duration.ofSeconds(20), 3,
                ObjectStorageSettings.AZURE_PUBLIC_SUFFIXES, false);
        var client = new HanaBlobObjectStorageClient(db, runtime, maxDocumentBytes, maxInFlightBytes, memoryWait, clock);
        return new ContentAddressedDocumentStore(client, settings, AzureObjectStoreBinding.Access.READ_WRITE, clock, sleeper);
    }
}
