package customer.cap_java.ksef;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import pl.akmf.ksef.sdk.api.DefaultKsefClient;
import pl.akmf.ksef.sdk.api.KsefApiProperties;
import pl.akmf.ksef.sdk.api.services.DefaultCryptographyService;
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient;
import pl.akmf.ksef.sdk.system.circuitbreaker.KsefCircuitBreakerOptions;
import pl.akmf.ksef.sdk.system.circuitbreaker.KsefCircuitBreakerProperties;

/** SDK MF działa w JVM CAP. Ta integracja jest celowo ograniczona do TEST. */
@Configuration(proxyBeanMethods = false)
public class KsefSdkConfiguration {
    public static final String TEST_ORIGIN = "https://api-test.ksef.mf.gov.pl";

    @Bean
    KSeFClient ksefClient() {
        return createClient(TEST_ORIGIN);
    }

    /** Własny Jackson 2 dla SDK; nie zastępuje mappera Spring Boot 4/Jackson 3. */
    static KSeFClient createClient(String origin) {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var properties = new KsefApiProperties() {
            public String getBaseUri() { return origin; }
            public String getSuffixUri() { return "v2"; }
            public String getQrUri() { return "https://qr-test.ksef.mf.gov.pl"; }
            public Duration getRequestTimeout() { return Duration.ofSeconds(20); }
            public Map<String, String> getDefaultHeaders() { return Map.of(); }
        };
        var breaker = new KsefCircuitBreakerProperties() {
            public KsefCircuitBreakerOptions getCircuitBreakerOptions() {
                var options = new KsefCircuitBreakerOptions();
                options.setEnabled(false);
                return options;
            }
        };
        // Bez redirectów i automatycznych ponowień POST; retry odczytów kontroluje workflow.
        return new DefaultKsefClient(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(), properties, breaker, mapper);
    }

    @Bean
    @Lazy
    DefaultCryptographyService ksefCryptography(KSeFClient client) {
        // SDK pobiera certyfikaty w konstruktorze; lazy chroni start CAP/testy offline przed HTTP.
        return new DefaultCryptographyService(client);
    }
}
