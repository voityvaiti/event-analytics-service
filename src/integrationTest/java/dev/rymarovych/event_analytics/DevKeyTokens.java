package dev.rymarovych.event_analytics;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Signs bearer tokens for the integration tests with the committed development key, so requests
 * travel the real filter chain: the real decoder verifies a real RS256 signature, and the token
 * validator and principal converter run as they do in production.
 *
 * <p>Deliberately not {@code SecurityMockMvcRequestPostProcessors.jwt()}. That injects a ready-made
 * authentication straight into the security context and never invokes the {@code JwtDecoder}, which
 * would leave the algorithm pinning and the required-tenant-claim validator untested — the two
 * things most worth testing — and cannot express a rejected token at all.
 *
 * <p>This is the second signer over the same key; {@code perf/lib/mint-token.mjs} is the first. The
 * duplication is intended: the perf suite runs from a node image with no JVM, and these tests run
 * with no node. So the claim's name lives in three places — {@code SecurityConfig} reads it, both
 * signers write it — and only the reader is load-bearing: a signer with the wrong name produces
 * tokens that fail verification immediately, while a reader with the wrong name would scope
 * requests to the wrong tenant in silence.
 */
public final class DevKeyTokens {

  private static final Path PRIVATE_KEY = Path.of("dev-keys/dev-only-unsafe-private-key.pem");

  private static final String TENANT_CLAIM = "tenant";

  private DevKeyTokens() {}

  /** A request post-processor presenting a valid token for {@code tenant}. */
  public static RequestPostProcessor bearerTokenFor(String tenant) {
    return bearerToken(signedFor(tenant));
  }

  /** A request post-processor presenting {@code token} verbatim, valid or not. */
  public static RequestPostProcessor bearerToken(String token) {
    return request -> {
      request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
      return request;
    };
  }

  public static String signedFor(String tenant) {
    return sign(
        new JWTClaimsSet.Builder().claim(TENANT_CLAIM, tenant).build(), developmentPrivateKey());
  }

  /**
   * The key the application's configured public key belongs to. Needed by tests that must fail a
   * token on something other than its signature — a missing claim, an expiry — where signing with a
   * stranger's key would make the assertion pass for the wrong reason.
   */
  public static PrivateKey developmentPrivateKey() {
    try {
      var base64 =
          Files.readString(PRIVATE_KEY)
              .replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replaceAll("\\s", "");
      var spec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64));
      return KeyFactory.getInstance("RSA").generatePrivate(spec);
    } catch (Exception ex) {
      throw new IllegalStateException(
          "Could not read " + PRIVATE_KEY + " — is it still there?", ex);
    }
  }

  public static String sign(JWTClaimsSet claims, PrivateKey key) {
    try {
      var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (Exception ex) {
      throw new IllegalStateException("Could not sign a test token", ex);
    }
  }

  /**
   * A key pair the application knows nothing about, for asserting that a well-formed token signed
   * by the wrong issuer is rejected. Generated per call rather than committed, so proving that case
   * needs no second key in the repository.
   */
  public static KeyPair unknownKeyPair() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception ex) {
      throw new IllegalStateException("Could not generate a throwaway key pair", ex);
    }
  }
}
