package dev.rymarovych.event_analytics.config;

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
 * Stateless bearer-token security for the API.
 *
 * <p>Tokens are verified against an RSA public key, configured in {@code application.yaml}; Boot's
 * resource-server autoconfiguration builds the decoder from it. Verification is therefore offline —
 * no key exchange, no lookup, nothing shared between instances — which is what lets the ingest path
 * treat a valid signature as proof of a valid tenant without touching the database.
 *
 * <p>{@code /actuator/**} stays unauthenticated. The perf suite reads the live pool size from
 * {@code /actuator/metrics/hikaricp.connections.max} to stamp every journal row and gates on {@code
 * /actuator/health}, and CI does the same; requiring a token there would break every measurement
 * the project compares against. Exposure is limited to {@code health,metrics}, and a deployment
 * restricts actuator at the network edge rather than by widening this chain.
 *
 * <p>Everything else needs a token. {@code anyRequest().authenticated()} rather than a path rule
 * plus a catch-all deny: one rule fewer, no dependence on which dispatcher types the authorization
 * filter covers, and a future endpoint is closed until someone opens it.
 *
 * <p>CSRF is disabled, and the reason is narrower than "the API is token-based": CSRF matters when
 * a browser attaches the credential itself, as it does a cookie. It never adds an {@code
 * Authorization} header on its own, so a forged cross-site request arrives with no token. Moving
 * the token into a cookie would bring CSRF straight back — being a JWT changes nothing, only who
 * attaches it does.
 */
@Configuration
class SecurityConfig {

  /**
   * The claim carrying the tenant, named in one place because two things read it: the principal
   * resolves to it, and a validator requires it. Expressing one of them as a {@code
   * principal-claim-name} property instead would let the two drift, and the two directions are not
   * equally safe — a validator pointed at the wrong claim rejects everything, loudly, while a
   * principal read from the wrong claim silently scopes requests to the wrong tenant.
   */
  private static final String TENANT_CLAIM = "tenant";

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, ProblemDetailAuthenticationHandler authenticationHandler)
      throws Exception {
    return http.authorizeHttpRequests(
            auth -> auth.requestMatchers("/actuator/**").permitAll().anyRequest().authenticated())
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
   * Rejects a token that carries no usable tenant, so the claim's absence is a verification failure
   * answered 401 rather than something every controller has to defend against.
   *
   * <p>Declared as a plain bean because Boot's {@code JwtDecoderConfiguration} collects every
   * {@code OAuth2TokenValidator<Jwt>} and installs them alongside its defaults — which is why no
   * hand-built {@code JwtDecoder} is needed here. That wiring is autoconfiguration behaviour rather
   * than anything visible in this class, so an integration test pins it.
   */
  @Bean
  OAuth2TokenValidator<Jwt> tenantClaimValidator() {
    return new JwtClaimValidator<String>(
        TENANT_CLAIM, tenant -> tenant != null && !tenant.isBlank());
  }

  private static JwtAuthenticationConverter tenantPrincipalConverter() {
    var converter = new JwtAuthenticationConverter();
    converter.setPrincipalClaimName(TENANT_CLAIM);
    return converter;
  }
}
