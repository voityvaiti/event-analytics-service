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
 * Return RFC 9457 problem bodies for security failures, which occur before MVC advice can handle
 * them.
 *
 * <p>Clients receive the same detail for missing, malformed, expired, or invalidly signed tokens.
 * Logs identify the exception type but omit its message, which may contain token material.
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
