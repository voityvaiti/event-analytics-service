package dev.rymarovych.event_analytics.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p>The log line under each rejection is the deliberate opposite: it names the check that failed,
 * because the operator reading it is not the caller. The exception's own message is left out of it
 * — the type already says which check, and a decoder's message can quote the token it choked on.
 * Without it a rejected request leaves no trace at all — these failures never reach {@code
 * ApiExceptionHandler}, which logs every other one — and a caller reporting that its token stopped
 * working could be answered only by guesswork.
 */
@Component
class ProblemDetailAuthenticationHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

  private static final Logger log =
      LoggerFactory.getLogger(ProblemDetailAuthenticationHandler.class);

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
    logRejection(request, HttpStatus.UNAUTHORIZED, exception);
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
    logRejection(request, HttpStatus.FORBIDDEN, exception);
    write(
        request,
        response,
        HttpStatus.FORBIDDEN,
        "The presented token is not permitted to access this resource.");
  }

  private static void logRejection(
      HttpServletRequest request, HttpStatus status, RuntimeException exception) {
    var principal = request.getUserPrincipal();
    log.warn(
        "{} {} {} tenant={} {}",
        status.value(),
        request.getMethod(),
        request.getRequestURI(),
        principal == null ? "-" : principal.getName(),
        exception.getClass().getSimpleName());
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
