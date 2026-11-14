package customer.cap_java.ksef;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import customer.cap_java.ksef.inbound.sync.InboundSynchronization;
import customer.cap_java.ksef.inbound.sync.InboundSyncScheduler;

/** Dwa jawne opt-in; zależność od synchronizacji zachowuje jej bezwarunkowy guard CF. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = { "ainv.ksef.inbound.enabled", "ainv.ksef.inbound.scheduler.enabled" }, havingValue = "true")
public class KsefInboundSchedulerConfiguration {
    @Bean InboundSyncScheduler inboundSyncScheduler(InboundSynchronization synchronization) {
        return new InboundSyncScheduler(synchronization);
    }
}
