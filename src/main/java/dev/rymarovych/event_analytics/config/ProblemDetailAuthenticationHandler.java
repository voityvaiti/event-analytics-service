package dev.rymarovych.event_analytics.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Answers authentication and authorization failures as RFC 9457 {@code application/problem+json}.
 *
 * <p>These failures happen inside the security filter chain, before the {@code DispatcherServlet},
 * so {@code ApiExceptionHandler} — which gives every other error in the API its problem body —
 * never sees them. Spring Security's default entry point answers a bare status with no body, which
 * would leave auth as the one error clients cannot parse the same way as the rest.
 *
 * <p>The detail messages deliberately do not distinguish a missing token from a malformed, expired,
 * or wrongly-signed one. All four are the same instruction to the client, and naming which check
 * failed tells an unauthenticated caller more about the token format than it tells a legitimate
 * one.
 */
@Component
class ProblemDetailAuthenticationHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

  private final ObjectMapper objectMapper;

  ProblemDetailAuthenticationHandler(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * {@code WWW-Authenticate} is set by hand because bypassing Spring Security's bearer entry point
   * means nothing else adds it, and RFC 6750 uses it to tell a client which scheme to present.
   */
  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    write(
        request,
        response,
        HttpStatus.UNAUTHORIZED,
        "A valid bearer token is required in the Authorization header.");
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
      throws IOException {
    write(
        request,
        response,
        HttpStatus.FORBIDDEN,
        "The presented token is not permitted to access this resource.");
  }

  private void write(
      HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail)
      throws IOException {
    var problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setInstance(URI.create(request.getRequestURI()));
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(response.getOutputStream(), problem);
  }
}
