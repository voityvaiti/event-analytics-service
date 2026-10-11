package dev.rymarovych.event_analytics.config;

import dev.rymarovych.event_analytics.domain.TenantName;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless bearer authentication using Boot's RSA public-key decoder. Verification is offline and
 * needs no tenant database lookup.
 *
 * <p>Actuator health, metrics, and Prometheus endpoints remain public for perf and CI. Restrict
 * deployment access at the network edge. OpenAPI and Swagger UI are also public; API calls still
 * require tokens.
 *
 * <p>All remaining requests require authentication, including new endpoints. CSRF is disabled
 * because browsers do not attach Authorization headers automatically. Moving tokens into cookies
 * would require revisiting this.
 */
@Configuration
class SecurityConfig {

  /**
   * Use one tenant claim name for validation and principal conversion. Separate settings could
   * reject valid tokens or silently scope requests to the wrong tenant.
   */
  private static final String TENANT_CLAIM = "tenant";

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, ProblemDetailAuthenticationHandler authenticationHandler)
      throws Exception {
    return http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/**")
                    .permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers("/internal/persistence-consumer/**")
                    .hasAuthority("SCOPE_persistence:control")
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(tenantPrincipalConverter()))
                    .authenticationEntryPoint(authenticationHandler)
                    .accessDeniedHandler(authenticationHandler))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(authenticationHandler)
                    .accessDeniedHandler(authenticationHandler))
        .csrf(AbstractHttpConfigurer::disable)
        .build();
  }

  /**
   * Reject missing or invalid tenant names with 401. Boot adds this validator alongside its default
   * JWT validators; integration tests cover that wiring.
   */
  @Bean
  OAuth2TokenValidator<Jwt> tenantClaimValidator() {
    return new JwtClaimValidator<String>(TENANT_CLAIM, SecurityConfig::isTenantName);
  }

  private static boolean isTenantName(@Nullable String tenant) {
    if (tenant == null) {
      return false;
    }
    try {
      new TenantName(tenant);
      return true;
    } catch (IllegalArgumentException notATenantName) {
      return false;
    }
  }

  private static JwtAuthenticationConverter tenantPrincipalConverter() {
    var converter = new JwtAuthenticationConverter();
    converter.setPrincipalClaimName(TENANT_CLAIM);
    return converter;
  }
}
