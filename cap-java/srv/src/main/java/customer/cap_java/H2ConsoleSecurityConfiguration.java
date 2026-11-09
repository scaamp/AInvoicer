package customer.cap_java;

import org.springframework.boot.security.autoconfigure.web.servlet.PathRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/** Jawny wyjątek developerski H2; błędne włączenie zatrzymuje start przed udostępnieniem HTTP. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.h2.console.enabled", havingValue = "true")
public class H2ConsoleSecurityConfiguration {

	public H2ConsoleSecurityConfiguration(Environment environment) {
		// Konsola bez dodatkowego loginu aplikacji jest dopuszczalna tylko w izolowanym procesie lokalnym.
		// Nie ufamy nagłówkom forwarded ani samemu profilowi default (może współistnieć z cf).
		String address = environment.getProperty("server.address", "");
		if (!environment.acceptsProfiles(Profiles.of("h2-console-local"))
				|| environment.acceptsProfiles(Profiles.of("cf", "cloud", "prod", "production"))
				|| environment.containsProperty("VCAP_APPLICATION")
				|| environment.containsProperty("VCAP_SERVICES")
				|| !("127.0.0.1".equals(address) || "::1".equals(address))
				|| !"none".equalsIgnoreCase(environment.getProperty("server.forward-headers-strategy", "none"))
				|| environment.getProperty("spring.h2.console.settings.web-allow-others", Boolean.class, false)) {
			throw new IllegalStateException("H2 console requires isolated h2-console-local, loopback binding and no cloud/proxy configuration");
		}
	}

	/** Wyjątek CSRF/ramek dotyczy tylko konsoli; dostęp sprawdzamy po adresie połączenia, nie X-Forwarded-For. */
	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	SecurityFilterChain h2ConsoleSecurityFilterChain(HttpSecurity http) throws Exception {
		http.securityMatcher(PathRequest.toH2Console())
			.authorizeHttpRequests(authorize -> authorize.anyRequest().access((authentication, context) ->
				new AuthorizationDecision(new IpAddressMatcher("127.0.0.1").matches(context.getRequest())
					|| new IpAddressMatcher("::1").matches(context.getRequest()))))
			.csrf(AbstractHttpConfigurer::disable)
			.headers(headers -> headers.frameOptions(frameOptions -> frameOptions.sameOrigin()));
		return http.build();
	}
}
