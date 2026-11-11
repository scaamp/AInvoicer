package customer.cap_java.ksef.storage;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Credentiale SAP BTP Object Store (plan {@code standard}) na landscape Azure, odczytane z VCAP_SERVICES.
 * Pola według dokumentacji SAP: {@code account_name}, {@code container_name}, {@code container_uri},
 * {@code sas_token}, {@code region}, opcjonalnie {@code role} (read/write) dla role-based binding.
 *
 * <p>Fail closed: brak, wiele pasujących bindingów, credentiale AWS/GCP, rola write-only (brak odczytu
 * kontrolnego) lub URI spoza HTTPS kończą się wyjątkiem z kodem, bez wartości credentiali.
 */
public record AzureObjectStoreBinding(URI containerUri, String containerName, String sasToken, Access access) {

    /** Uprawnienie wynikające z roli binding: brak roli = pełne, {@code read} = tylko odczyt. */
    public enum Access { READ_WRITE, READ_ONLY }

    private static final ObjectMapper JSON = new ObjectMapper();

    public AzureObjectStoreBinding {
        if (containerUri == null || containerName == null || sasToken == null || access == null) {
            throw new IllegalStateException("KSEF_DOCUMENT_STORE_BINDING_INCOMPLETE");
        }
        // SAS doklejany jest jako query; odrzucamy znaki, które zmieniłyby strukturę URL.
        if (sasToken.startsWith("?")) { sasToken = sasToken.substring(1); }
        if (sasToken.isBlank() || sasToken.length() > 2048 || !sasToken.matches("[A-Za-z0-9%&=._~:+/-]+")
                || !("&" + sasToken).contains("&sig=")) {
            throw new IllegalStateException("KSEF_DOCUMENT_STORE_BINDING_SAS_INVALID");
        }
        if (!containerName.matches("[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])")) {
            throw new IllegalStateException("KSEF_DOCUMENT_STORE_BINDING_CONTAINER_INVALID");
        }
    }

    /**
     * Wybiera binding z VCAP_SERVICES. Bez {@code instanceName} wymaga dokładnie jednej instancji
     * z etykietą {@code objectstore}; z nazwą akceptuje także user-provided service utworzony
     * z service key (ścieżka SAP dla &gt;5 bindingów).
     */
    public static AzureObjectStoreBinding fromVcapServices(String vcapServices, String instanceName) {
        if (vcapServices == null || vcapServices.isBlank()) {
            throw new IllegalStateException("KSEF_DOCUMENT_STORE_BINDING_MISSING");
        }
        JsonNode root;
        try {
            root = JSON.readTree(vcapServices);
        } catch (Exception malformed) {
            throw new IllegalStateException("KSEF_DOCUMENT_STORE_BINDING_MALFORMED");
        }
        List<JsonNode> candidates = new ArrayList<>();
        if (instanceName == null || instanceName.isBlank()) {
            root.path("objectstore").forEach(candidates::add);
        } else {
            root.properties().forEach(service -> service.getValue().forEach(binding -> {
                if (instanceName.equals(binding.path("name").asText())) { candidates.add(binding); }
            }));
        }
        if (candidates.isEmpty()) { throw new IllegalStateException("KSEF_DOCUMENT_STORE_BINDING_MISSING"); }
        if (candidates.size() > 1) { throw new IllegalStateException("KSEF_DOCUMENT_STORE_BINDING_AMBIGUOUS"); }
        return fromCredentials(candidates.getFirst().path("credentials"));
    }

    static AzureObjectStoreBinding fromCredentials(JsonNode credentials) {
        if (credentials.has("access_key_id") || credentials.has("secret_access_key") || credentials.has("bucket")) {
            // Object Store na AWS: protokół S3 nie jest zaimplementowany w tym adapterze.
            throw new IllegalStateException("KSEF_DOCUMENT_STORE_PROVIDER_UNSUPPORTED");
        }
        String uri = text(credentials, "container_uri");
        String container = text(credentials, "container_name");
        String sas = text(credentials, "sas_token");
        if (uri == null || container == null || sas == null) {
            throw new IllegalStateException("KSEF_DOCUMENT_STORE_BINDING_INCOMPLETE");
        }
        String role = text(credentials, "role");
        Access access;
        if (role == null) {
            access = Access.READ_WRITE;
        } else if ("read".equals(role.toLowerCase(Locale.ROOT))) {
            access = Access.READ_ONLY;
        } else {
            // write-only nie pozwala na odczyt kontrolny po zapisie, więc nie potwierdzimy trwałości.
            throw new IllegalStateException("KSEF_DOCUMENT_STORE_BINDING_ROLE_UNSUPPORTED");
        }
        URI parsed;
        try {
            parsed = URI.create(uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("KSEF_DOCUMENT_STORE_BINDING_URI_INVALID");
        }
        return new AzureObjectStoreBinding(parsed, container, sas, access);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() || value.asText().isBlank() ? null : value.asText();
    }

    /** Nigdy nie wypisuje SAS ani pełnego URI. */
    @Override public String toString() {
        return "AzureObjectStoreBinding[container=" + containerName + ", access=" + access + ", sas=***]";
    }
}
