package customer.cap_java.ksef.storage;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;

import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.ksef.inbound.KsefEnvironment;
import customer.cap_java.ksef.inbound.KsefInboundClient;

/**
 * KSEF-STOR-03: fabryka magazynu dokumentów KSeF w tabeli CAP {@code KsefDocuments} (HANA BLOB).
 *
 * <p>Zwraca istniejący {@link ContentAddressedDocumentStore}, więc kontrakt {@code KsefDocumentStore},
 * format referencji {@code ksefdoc:v1:…}, scope ({@link DocumentScope}) i weryfikacja SHA-256 są identyczne
 * jak dla adaptera Object Store. Zakres dostępu wiąże zaufany kod konfiguracji przez
 * {@code forScope(...)} — nigdy dane z OData.
 *
 * <p>Nie rejestruje beana Spring i niczego nie włącza: podłączenie do workera INBOUND/OUTBOUND i profilu CF
 * jest osobnym, zatwierdzanym krokiem (KSEF-IN-03B / integracja). Przed integracją produkcyjną wymagana jest
 * decyzja właściciela o rozdzieleniu tabeli od laboratorium (przewodnik KSEF-STOR-03, D-1).
 */
public final class HanaKsefDocumentStore {
    /**
     * Domyślny limit dokumentu = domyślny limit XML klienta INBOUND (3 MiB, KSeF: faktura z załącznikami).
     * Laboratoryjny limit 1 MiB {@code BlobService} celowo nie obowiązuje w tym adapterze.
     */
    public static final int DEFAULT_MAX_DOCUMENT_BYTES = KsefInboundClient.DEFAULT_MAX_INVOICE_BYTES;
    /** Twardy limit konfiguracji: XML/UPO czytany w całości do pamięci JVM; HANA BLOB pozwala więcej, JVM nie powinna. */
    public static final int HARD_MAX_DOCUMENT_BYTES = 16 * 1024 * 1024;

    private HanaKsefDocumentStore() { }

    /** Magazyn z domyślnym limitem; {@code keyPrefix} izoluje wdrożenie (np. {@code ainvoicer-dev}). */
    public static ContentAddressedDocumentStore create(PersistenceService db, CdsRuntime runtime, String keyPrefix,
            Set<KsefEnvironment> allowedEnvironments) {
        return create(db, runtime, keyPrefix, allowedEnvironments, DEFAULT_MAX_DOCUMENT_BYTES);
    }

    /**
     * Magazyn z jawnym limitem (1 B … 16 MiB). Limit musi być ≥ limitowi klienta INBOUND; mniejszy powoduje
     * kontrolowane {@code KSEF_DOCUMENT_SIZE_INVALID} → {@code STORAGE_FAILED}, bez przesunięcia checkpointu.
     */
    public static ContentAddressedDocumentStore create(PersistenceService db, CdsRuntime runtime, String keyPrefix,
            Set<KsefEnvironment> allowedEnvironments, int maxDocumentBytes) {
        return create(db, runtime, keyPrefix, allowedEnvironments, maxDocumentBytes, Clock.systemUTC(),
                duration -> Thread.sleep(duration));
    }

    /** Wariant testowy: zegar i pauza między ponowieniami wstrzykiwane, bez zależności od czasu rzeczywistego. */
    static ContentAddressedDocumentStore create(PersistenceService db, CdsRuntime runtime, String keyPrefix,
            Set<KsefEnvironment> allowedEnvironments, int maxDocumentBytes, Clock clock,
            ContentAddressedDocumentStore.Sleeper sleeper) {
        if (maxDocumentBytes < 1 || maxDocumentBytes > HARD_MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("KSEF_DOCUMENT_STORE_LIMIT_INVALID");
        }
        // Pola hostów/timeoutów HTTP ObjectStorageSettings nie są używane przez klienta CQN (brak sieci).
        var settings = new ObjectStorageSettings(keyPrefix, allowedEnvironments, maxDocumentBytes,
                Duration.ofSeconds(5), Duration.ofSeconds(20), 3, ObjectStorageSettings.AZURE_PUBLIC_SUFFIXES, false);
        return new ContentAddressedDocumentStore(new HanaBlobObjectStorageClient(db, runtime, maxDocumentBytes),
                settings, AzureObjectStoreBinding.Access.READ_WRITE, clock, sleeper);
    }
}
