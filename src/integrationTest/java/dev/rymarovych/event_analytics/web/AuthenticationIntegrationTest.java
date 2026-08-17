package dev.rymarovych.event_analytics.web;

import static dev.rymarovych.event_analytics.DevKeyTokens.bearerToken;
import static dev.rymarovych.event_analytics.DevKeyTokens.bearerTokenFor;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jwt.JWTClaimsSet;
import dev.rymarovych.event_analytics.DevKeyTokens;
import dev.rymarovych.event_analytics.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Proves what the security chain accepts and rejects, driving the real {@code JwtDecoder} with
 * really-signed tokens rather than a pre-built authentication.
 *
 * <p>Several of these pin autoconfiguration rather than our own code — that Boot installs the
 * {@code OAuth2TokenValidator} bean onto a public-key decoder, and that it verifies RS256 against
 * the configured key. Nothing in {@code SecurityConfig} shows either, so nothing but a test would
 * notice them breaking.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthenticationIntegrationTest {

  private static final String PROTECTED_PATH = "/api/v1/stats/event-counts";

  @Autowired private MockMvc mockMvc;

  @Test
  void tokenlessRequestIsUnauthorizedProblemDetail() throws Exception {
    mockMvc
        .perform(windowQuery())
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(header().string("WWW-Authenticate", "Bearer"))
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.detail").exists())
        .andExpect(jsonPath("$.instance").value(PROTECTED_PATH));
  }

  @Test
  void validTokenIsAccepted() throws Exception {
    mockMvc.perform(windowQuery().with(bearerTokenFor("acme"))).andExpect(status().isOk());
  }

  @Test
  void malformedTokenIsUnauthorized() throws Exception {
    mockMvc
        .perform(windowQuery().with(bearerToken("not-a-jwt")))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  /**
   * A well-formed RS256 token is still worthless if this service does not hold the signer's key.
   */
  @Test
  void tokenSignedByAnUnknownKeyIsUnauthorized() throws Exception {
    var stranger = DevKeyTokens.unknownKeyPair();
    var token =
        DevKeyTokens.sign(
            new JWTClaimsSet.Builder().claim("tenant", "acme").build(), stranger.getPrivate());

    mockMvc
        .perform(windowQuery().with(bearerToken(token)))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  /**
   * Without the tenant claim there is nothing to scope the request to, so it fails verification
   * rather than reaching a controller that would have to invent a fallback.
   */
  @Test
  void tokenWithoutTenantClaimIsUnauthorized() throws Exception {
    var token =
        DevKeyTokens.sign(
            new JWTClaimsSet.Builder().subject("acme").build(),
            DevKeyTokens.developmentPrivateKey());

    mockMvc
        .perform(windowQuery().with(bearerToken(token)))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void blankTenantClaimIsUnauthorized() throws Exception {
    mockMvc.perform(windowQuery().with(bearerTokenFor("   "))).andExpect(status().isUnauthorized());
  }

  /**
   * Well past the timestamp validator's default clock skew, so this fails on expiry rather than on
   * tolerance. The perf suite mints tokens without an {@code exp} precisely to stay clear of this.
   */
  @Test
  void expiredTokenIsUnauthorized() throws Exception {
    var expired =
        DevKeyTokens.sign(
            new JWTClaimsSet.Builder()
                .claim("tenant", "acme")
                .expirationTime(Date.from(Instant.now().minus(Duration.ofHours(1))))
                .build(),
            DevKeyTokens.developmentPrivateKey());

    mockMvc.perform(windowQuery().with(bearerToken(expired))).andExpect(status().isUnauthorized());
  }

  /**
   * The perf harness reads the pool size from actuator to stamp every journal row and gates its
   * runs on health, both without a token. This is the test that fails if that ever stops being
   * true.
   */
  @Test
  void actuatorNeedsNoToken() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mockMvc.perform(get("/actuator/metrics/hikaricp.connections.max")).andExpect(status().isOk());
  }

  private static MockHttpServletRequestBuilder windowQuery() {
    return get(PROTECTED_PATH)
        .param("from", "2026-05-24T00:00:00Z")
        .param("to", "2026-05-25T00:00:00Z");
  }
}
