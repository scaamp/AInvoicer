package customer.cap_java;

import org.springframework.boot.security.autoconfigure.web.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/** Lokalna obsługa konsoli H2; ustawienia nie są aktywne poza profilem default. */
@Configuration(proxyBeanMethods = false)
@Profile("default")
public class H2ConsoleSecurityConfiguration {

	/** Konsola H2 używa ramek i formularza bez tokenu CSRF, więc wyjątek ograniczamy do jej ścieżki. */
	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	SecurityFilterChain h2ConsoleSecurityFilterChain(HttpSecurity http) throws Exception {
		http.securityMatcher(PathRequest.toH2Console())
			.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
			.csrf(AbstractHttpConfigurer::disable)
			.headers(headers -> headers.frameOptions(frameOptions -> frameOptions.sameOrigin()));
		return http.build();
	}
}
