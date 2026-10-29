package customer.cap_java.sap;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Zegar jest wstrzykiwany, aby dzierżawy i terminy Retry-After były testowalne bez czekania. */
@Configuration(proxyBeanMethods = false)
class SapWritebackConfiguration {

    @Bean
    Clock writebackClock() {
        return Clock.systemUTC();
    }
}
