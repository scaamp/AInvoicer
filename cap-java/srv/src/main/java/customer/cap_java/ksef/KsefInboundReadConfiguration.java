package customer.cap_java.ksef;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.runtime.CdsRuntime;

import customer.cap_java.ksef.inbound.persistence.CqnInboundOverviewRepository;
import customer.cap_java.ksef.inbound.persistence.InboundPersistence;

/** Repozytorium stagingu działa bez opt-in synchronizacji; nie tworzy klientów/auth/schedulerów. */
@Configuration(proxyBeanMethods = false)
public class KsefInboundReadConfiguration {
    @Bean InboundPersistence inboundPersistence(PersistenceService db, CdsRuntime runtime) {
        return new InboundPersistence(db, runtime);
    }
    @Bean CqnInboundOverviewRepository inboundOverview(InboundPersistence persistence) {
        return new CqnInboundOverviewRepository(persistence);
    }
}
