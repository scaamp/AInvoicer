package customer.cap_java.ksef.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import customer.cap_java.ksef.inbound.KsefEnvironment;

/** Kształt VCAP_SERVICES według dokumentacji SAP Object Store; wartości syntetyczne. */
class AzureObjectStoreBindingTest {
    static final String SAS = "sv=2021-08-06&spr=https&si=policy&sr=c&sig=c3ludGhldGlj";
    static final String CREDENTIALS = "\"account_name\":\"acc\",\"container_name\":\"hcp-abc-zrs\","
            + "\"container_uri\":\"https://acc.z17.blob.storage.azure.net/hcp-abc-zrs\",\"sas_token\":\"" + SAS
            + "\",\"region\":\"westeurope\"";

    static String vcap(String credentials) {
        return "{\"objectstore\":[{\"label\":\"objectstore\",\"name\":\"ainv-docs\",\"plan\":\"standard\",\"credentials\":{"
                + credentials + "}}]}";
    }

    static void rejected(String code, String vcap, String name) {
        var error = assertThrows(IllegalStateException.class, () -> AzureObjectStoreBinding.fromVcapServices(vcap, name));
        assertEquals(code, error.getMessage());
        assertFalse(error.getMessage().contains("sig="));
    }

    @Test void parsesDocumentedAzureCredentialsAndRedactsSas() {
        var binding = AzureObjectStoreBinding.fromVcapServices(vcap(CREDENTIALS), null);
        assertEquals("hcp-abc-zrs", binding.containerName());
        assertEquals(AzureObjectStoreBinding.Access.READ_WRITE, binding.access());
        assertEquals(SAS, binding.sasToken());
        assertFalse(binding.toString().contains("sig"));
        assertFalse(binding.toString().contains("azure.net"));
        var settings = ObjectStorageSettings.defaults("ainvoicer-dev", EnumSet.of(KsefEnvironment.TEST));
        assertDoesNotThrow(() -> new AzureBlobSasClient(binding, settings));
        var trailingSlash = AzureObjectStoreBinding.fromVcapServices(vcap(CREDENTIALS
                .replace("hcp-abc-zrs\",\"sas", "hcp-abc-zrs/\",\"sas").replace("\"" + SAS, "\"?" + SAS)), null);
        assertEquals(SAS, trailingSlash.sasToken());
        assertDoesNotThrow(() -> new AzureBlobSasClient(trailingSlash, settings));
    }

    @Test void roleBasedBindingsAreMappedFailClosed() {
        assertEquals(AzureObjectStoreBinding.Access.READ_ONLY,
                AzureObjectStoreBinding.fromVcapServices(vcap(CREDENTIALS + ",\"role\":\"read\""), null).access());
        rejected("KSEF_DOCUMENT_STORE_BINDING_ROLE_UNSUPPORTED", vcap(CREDENTIALS + ",\"role\":\"write\""), null);
        rejected("KSEF_DOCUMENT_STORE_BINDING_ROLE_UNSUPPORTED", vcap(CREDENTIALS + ",\"role\":\"admin\""), null);
    }

    @Test void missingAmbiguousMalformedOrForeignProviderBindingsFailClosed() {
        rejected("KSEF_DOCUMENT_STORE_BINDING_MISSING", null, null);
        rejected("KSEF_DOCUMENT_STORE_BINDING_MISSING", " ", null);
        rejected("KSEF_DOCUMENT_STORE_BINDING_MISSING", "{}", null);
        rejected("KSEF_DOCUMENT_STORE_BINDING_MISSING", "{\"xsuaa\":[{\"name\":\"ainvoicer-auth\"}]}", null);
        rejected("KSEF_DOCUMENT_STORE_BINDING_MALFORMED", "{not json", null);
        String one = "{\"label\":\"objectstore\",\"name\":\"a\",\"credentials\":{" + CREDENTIALS + "}}";
        rejected("KSEF_DOCUMENT_STORE_BINDING_AMBIGUOUS", "{\"objectstore\":[" + one + "," + one.replace("\"a\"", "\"b\"") + "]}", null);
        rejected("KSEF_DOCUMENT_STORE_PROVIDER_UNSUPPORTED", vcap("\"access_key_id\":\"x\",\"secret_access_key\":\"y\","
                + "\"bucket\":\"b\",\"host\":\"s3-eu-central-1.amazonaws.com\",\"region\":\"eu-central-1\""), null);
        rejected("KSEF_DOCUMENT_STORE_BINDING_INCOMPLETE", vcap(CREDENTIALS.replace("\"sas_token\"", "\"other\"")), null);
        rejected("KSEF_DOCUMENT_STORE_BINDING_INCOMPLETE", vcap(CREDENTIALS.replace("\"container_uri\"", "\"x\"")), null);
        rejected("KSEF_DOCUMENT_STORE_BINDING_SAS_INVALID", vcap(CREDENTIALS.replace("&sig=c3ludGhldGlj", "")), null);
        rejected("KSEF_DOCUMENT_STORE_BINDING_SAS_INVALID", vcap(CREDENTIALS.replace("sr=c", "sr=c#frag")), null);
        rejected("KSEF_DOCUMENT_STORE_BINDING_CONTAINER_INVALID", vcap(CREDENTIALS.replace("\"hcp-abc-zrs\"", "\"../x\"")), null);
    }

    @Test void namedUserProvidedServiceFromServiceKeyIsSupported() {
        String vcap = "{\"user-provided\":[{\"label\":\"user-provided\",\"name\":\"ainv-docs-key\",\"credentials\":{"
                + CREDENTIALS + "}}],\"objectstore\":[{\"name\":\"other\",\"credentials\":{" + CREDENTIALS + "}}]}";
        assertEquals("hcp-abc-zrs", AzureObjectStoreBinding.fromVcapServices(vcap, "ainv-docs-key").containerName());
        rejected("KSEF_DOCUMENT_STORE_BINDING_MISSING", vcap, "absent-instance");
        String duplicateNames = vcap.replace("\"other\"", "\"ainv-docs-key\"");
        rejected("KSEF_DOCUMENT_STORE_BINDING_AMBIGUOUS", duplicateNames, "ainv-docs-key");
    }
}
