package customer.cap_java.sap;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;

import com.sap.cloud.sdk.cloudplatform.connectivity.HttpClientAccessor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Zegar jest wstrzykiwany, aby dzierżawy i terminy Retry-After były testowalne bez czekania. */
@Configuration(proxyBeanMethods = false)
class SapWritebackConfiguration {

    @Bean
    SapHttpClientFactory sapHttpClientFactory(
            @Value("${ainv.sap-write.http.connect-timeout:PT5S}") Duration connect,
            @Value("${ainv.sap-write.http.read-timeout:PT20S}") Duration read,
            @Value("${ainv.sap-write.http.pool-timeout:PT5S}") Duration pool) {
        var factory = new SapHttpClientFactory(connect, read, pool);
        HttpClientAccessor.setHttpClientFactory(factory);
        return factory;
    }

    @Bean
    Clock writebackClock() {
        return Clock.systemUTC();
    }
}
