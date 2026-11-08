package customer.cap_java.security;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Fail-fast trwałości na chmurze (CAP-07.1).
 *
 * <p>CAP przechowuje historię procesu KSeF: operacje, rezerwacje (idempotencja), zgłoszenia, outbox
 * i zlecenia writebacku SAP. Utrata tych danych po restarcie oznacza brak wiedzy, czy faktura już
 * trafiła do KSeF — ryzyko duplikatu albo zgubionego numeru KSeF. Na Cloud Foundry bez bindingu bazy
 * Spring Boot po cichu utworzyłby wbudowaną H2 w pamięci (sterownik H2 jest w runtime dla developmentu).
 *
 * <p>Dlatego w środowisku chmurowym (ten sam warunek co {@link CloudIdentityGuard}) baza musi być
 * SAP HANA Cloud (HDI, auto-konfiguracja CAP z bindingu {@code hana}). Jedyny wyjątek to jawnie włączony
 * tryb {@code ainv.deployment.identity-smoke=true}: dopuszcza bazę nietrwałą wyłącznie do testu
 * logowania/ról, wymaga wyłączonego transportu KSeF, writebacku SAP i sweepera, a
 * {@link IdentitySmokeReadOnlyHandler} odrzuca każdą operację zapisującą historię. Tryb wymaga
 * osobnej zgody właściciela i nie jest ustawiony w żadnym deskryptorze.
 *
 * <p>Zależność od {@link CloudIdentityGuard} w konstruktorze ustala kolejność: najpierw tożsamość.
 */
@Configuration(proxyBeanMethods = false)
public class CloudPersistenceGuard {

    private static final Logger log = LoggerFactory.getLogger(CloudPersistenceGuard.class);

    public CloudPersistenceGuard(CloudIdentityGuard identityChecked, Environment environment, DataSource dataSource,
            @Value("${ainv.deployment.identity-smoke:false}") boolean identitySmoke,
            @Value("${ainv.ksef.enabled:false}") boolean ksefEnabled,
            @Value("${ainv.sap-write.enabled:false}") boolean sapWriteEnabled,
            @Value("${ainv.sap-write.sweeper.enabled:true}") boolean sweeperEnabled) {
        if (!CloudIdentityGuard.isCloudRuntime(environment)) {
            return;
        }
        verify(databaseProduct(dataSource), identitySmoke, ksefEnabled, sapWriteEnabled, sweeperEnabled);
        if (identitySmoke) {
            log.warn("IDENTITY SMOKE MODE: non-durable database; KSeF send, SAP writeback and operation writes are disabled");
        }
    }

    /**
     * Czysta reguła decyzji (testowalna bez bazy). HANA zgłasza produkt {@code HDB}.
     * Komunikaty bez URL/credentiali źródła danych.
     */
    static void verify(String product, boolean identitySmoke, boolean ksefEnabled, boolean sapWriteEnabled,
            boolean sweeperEnabled) {
        if (isHana(product)) {
            return;
        }
        if (!identitySmoke) {
            throw new IllegalStateException("Cloud runtime requires durable SAP HANA Cloud persistence (found database '"
                    + product + "'); embedded or in-memory databases lose KSeF operation history on restart");
        }
        if (ksefEnabled || sapWriteEnabled || sweeperEnabled) {
            throw new IllegalStateException("Identity smoke mode requires ainv.ksef.enabled=false, "
                    + "ainv.sap-write.enabled=false and ainv.sap-write.sweeper.enabled=false");
        }
    }

    static boolean isHana(String product) {
        return product != null && (product.equalsIgnoreCase("HDB") || product.toUpperCase().contains("HANA"));
    }

    private static String databaseProduct(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName();
        } catch (SQLException unavailable) {
            // Bez szczegółów połączenia; brak bazy na chmurze także zatrzymuje start.
            throw new IllegalStateException("Cloud runtime database is not reachable (SQLState "
                    + unavailable.getSQLState() + ")");
        }
    }
}
