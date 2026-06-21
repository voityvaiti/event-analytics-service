package dev.rymarovych.event_analytics.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Placeholder security configuration.
 *
 * <p>The service is stateless and currently permits all requests so the ingestion path works before
 * authentication exists. This is replaced by JWT (HS256) authentication later in Stage 1; until
 * then it only disables the default form-login/basic chain that the security starter would
 * otherwise impose. CSRF is disabled because the API is token-based, not cookie/session based.
 */
@Configuration
public class SecurityConfig {

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .csrf(AbstractHttpConfigurer::disable)
        .build();
  }
}
