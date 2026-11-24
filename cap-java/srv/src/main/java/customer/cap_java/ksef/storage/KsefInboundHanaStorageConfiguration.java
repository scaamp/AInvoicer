package customer.cap_java.ksef.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Locale;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.ksef.inbound.KsefEnvironment;

/**
 * KSEF-IN-05 (B1): jawny, domyślnie wyłączony wiring magazynu {@link HanaKsefDocumentStore} dla INBOUND.
 *
 * <p>Warunki (fail-closed przy starcie, kody bez danych połączenia):
 * <ul>
 *   <li>{@code ainv.ksef.inbound.storage.hana.enabled=true} — bez tej flagi bean nie istnieje;</li>
 *   <li>wyłącznie środowisko KSeF TEST ({@code ainv.ksef.inbound.environment}); scope {@code INBOUND/TEST},
 *       {@code allowedEnvironments = {TEST}} — DEMO/PROD odrzuca już fabryka;</li>
 *   <li>persystencja musi być rzeczywiście SAP HANA (produkt JDBC {@code HDB}); H2 dopuszczone wyłącznie
 *       lokalnie przy jawnym {@code ainv.ksef.inbound.storage.hana.allow-local-h2=true} i <b>nigdy</b> w chmurze
 *       ({@code VCAP_APPLICATION} albo profil {@code cf|cloud|prod|production}) — brak fallbacku na H2/dysk.</li>
 * </ul>
 *
 * <p>Konfiguracja niczego nie uruchamia: brak GET KSeF, schedulera, zapytań startowych poza odczytem nazwy
 * produktu DB oraz brak publicznego OData dla BLOB. Rejestruje wyłącznie {@link InboundDocumentStorage}
 * (typ, który nie jest {@code KsefDocumentStore}), więc OUTBOUND i {@code LocalTestDocumentStore} bez zmian.
 * Guard {@code KSEF_INBOUND_CLOUD_ACTIVATION_NOT_READY} w {@code KsefInboundConfiguration} pozostaje bezwarunkowy.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = KsefInboundHanaStorageConfiguration.ENABLED, havingValue = "true")
public class KsefInboundHanaStorageConfiguration {
    public static final String ENABLED = "ainv.ksef.inbound.storage.hana.enabled";
    public static final String ALLOW_LOCAL_H2 = "ainv.ksef.inbound.storage.hana.allow-local-h2";
    static final Profiles CLOUD_PROFILES = Profiles.of("cf", "cloud", "prod", "production");

    @Bean InboundDocumentStorage inboundDocumentStorage(Environment environment, ObjectProvider<DataSource> dataSource,
            PersistenceService db, CdsRuntime runtime,
            @Value("${ainv.ksef.inbound.environment:TEST}") String ksefEnvironment,
            @Value("${" + ALLOW_LOCAL_H2 + ":false}") boolean allowLocalH2) {
        boolean cloud = environment.acceptsProfiles(CLOUD_PROFILES) || environment.containsProperty("VCAP_APPLICATION");
        verify(cloud, databaseProduct(dataSource.getIfAvailable()), ksefEnvironment, allowLocalH2);
        var root = HanaKsefDocumentStore.create(db, runtime, EnumSet.of(KsefEnvironment.TEST));
        return new InboundDocumentStorage(root.forScope(DocumentScope.inbound(KsefEnvironment.TEST)), KsefEnvironment.TEST);
    }

    /**
     * Czysta reguła decyzji (testowalna bez DB). Kolejność: najpierw środowisko KSeF, potem rodzaj bazy.
     * W chmurze flaga H2 jest ignorowana — wyłącznie HANA.
     */
    static void verify(boolean cloud, String product, String ksefEnvironment, boolean allowLocalH2) {
        if (!"TEST".equals(ksefEnvironment)) {
            throw new IllegalStateException("KSEF_INBOUND_STORAGE_ENVIRONMENT_UNSUPPORTED");
        }
        if (isHana(product)) { return; }
        if (!cloud && allowLocalH2 && "H2".equalsIgnoreCase(product)) { return; }
        throw new IllegalStateException(cloud ? "KSEF_INBOUND_STORAGE_HANA_REQUIRED_ON_CLOUD"
                : "KSEF_INBOUND_STORAGE_HANA_REQUIRED");
    }

    /** HANA zgłasza produkt {@code HDB} (ta sama reguła co {@code CloudPersistenceGuard}). */
    static boolean isHana(String product) {
        return product != null && (product.equalsIgnoreCase("HDB") || product.toUpperCase(Locale.ROOT).contains("HANA"));
    }

    private static String databaseProduct(DataSource dataSource) {
        if (dataSource == null) { return null; }
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName();
        } catch (SQLException unavailable) {
            // Bez URL/credentiali; brak bazy = brak magazynu (fail closed).
            throw new IllegalStateException("KSEF_INBOUND_STORAGE_DATABASE_UNREACHABLE");
        }
    }
}
