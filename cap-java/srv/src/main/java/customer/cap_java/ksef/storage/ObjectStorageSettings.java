package customer.cap_java.ksef.storage;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import customer.cap_java.ksef.inbound.KsefEnvironment;

/**
 * Konfiguracja magazynu bez credentiali. Credentiale pochodzą wyłącznie z service binding
 * ({@link AzureObjectStoreBinding}); nic tutaj nie jest sekretem.
 *
 * @param keyPrefix           izolacja wdrożenia w kontenerze, np. {@code ainvoicer-dev}; [a-z0-9-], bez '/'
 * @param allowedEnvironments środowiska KSeF dopuszczone w tym wdrożeniu (TEST-deployment nie zapisze PROD)
 * @param maxDocumentBytes    górny limit pojedynczego XML/UPO, w obie strony
 * @param maxAttempts         próby dla błędów UNAVAILABLE (operacje są idempotentne)
 * @param allowedHostSuffixes dozwolone sufiksy hosta Blob; chroni SAS przed wysłaniem na obcy host
 * @param allowLoopbackHttp   wyłącznie testy: http dozwolone tylko dla 127.0.0.1/localhost
 */
public record ObjectStorageSettings(String keyPrefix, Set<KsefEnvironment> allowedEnvironments, int maxDocumentBytes,
        Duration connectTimeout, Duration requestTimeout, int maxAttempts, List<String> allowedHostSuffixes,
        boolean allowLoopbackHttp) {

    /** Zgodny z LocalTestDocumentStore; KSeF ogranicza fakturę mocniej (3 MiB w kliencie INBOUND). */
    public static final int DEFAULT_MAX_DOCUMENT_BYTES = 10 * 1024 * 1024;
    /** Formaty endpointu Blob z credentiali SAP Object Store na Azure (stary i nowy DNS zone). */
    public static final List<String> AZURE_PUBLIC_SUFFIXES = List.of(".blob.core.windows.net", ".blob.storage.azure.net");

    public ObjectStorageSettings {
        Objects.requireNonNull(keyPrefix, "keyPrefix");
        if (!keyPrefix.matches("[a-z0-9][a-z0-9-]{1,62}")) {
            throw new IllegalArgumentException("KSEF_DOCUMENT_STORE_PREFIX_INVALID");
        }
        if (allowedEnvironments == null || allowedEnvironments.isEmpty()) {
            throw new IllegalArgumentException("KSEF_DOCUMENT_STORE_ENVIRONMENTS_REQUIRED");
        }
        allowedEnvironments = Set.copyOf(EnumSet.copyOf(allowedEnvironments));
        if (maxDocumentBytes < 1 || maxDocumentBytes > 64 * 1024 * 1024) {
            throw new IllegalArgumentException("KSEF_DOCUMENT_STORE_LIMIT_INVALID");
        }
        requirePositive(connectTimeout);
        requirePositive(requestTimeout);
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("KSEF_DOCUMENT_STORE_ATTEMPTS_INVALID");
        }
        allowedHostSuffixes = List.copyOf(allowedHostSuffixes);
        if (allowedHostSuffixes.isEmpty() || allowedHostSuffixes.stream().anyMatch(s -> !s.startsWith("."))) {
            throw new IllegalArgumentException("KSEF_DOCUMENT_STORE_HOSTS_INVALID");
        }
    }

    /** Bezpieczne wartości domyślne: krótkie timeouty, 3 próby, wyłącznie HTTPS do publicznego Azure Blob. */
    public static ObjectStorageSettings defaults(String keyPrefix, Set<KsefEnvironment> allowedEnvironments) {
        return new ObjectStorageSettings(keyPrefix, allowedEnvironments, DEFAULT_MAX_DOCUMENT_BYTES,
                Duration.ofSeconds(5), Duration.ofSeconds(20), 3, AZURE_PUBLIC_SUFFIXES, false);
    }

    public ObjectStorageSettings withLoopbackHttpForTests(Duration requestTimeout) {
        return new ObjectStorageSettings(keyPrefix, allowedEnvironments, maxDocumentBytes, Duration.ofSeconds(2),
                requestTimeout, maxAttempts, allowedHostSuffixes, true);
    }

    public ObjectStorageSettings withMaxDocumentBytes(int limit) {
        return new ObjectStorageSettings(keyPrefix, allowedEnvironments, limit, connectTimeout, requestTimeout,
                maxAttempts, allowedHostSuffixes, allowLoopbackHttp);
    }

    public ObjectStorageSettings withMaxAttempts(int attempts) {
        return new ObjectStorageSettings(keyPrefix, allowedEnvironments, maxDocumentBytes, connectTimeout, requestTimeout,
                attempts, allowedHostSuffixes, allowLoopbackHttp);
    }

    private static void requirePositive(Duration value) {
        if (value == null || value.isNegative() || value.isZero() || value.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("KSEF_DOCUMENT_STORE_TIMEOUT_INVALID");
        }
    }
}
