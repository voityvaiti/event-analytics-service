package dev.rymarovych.event_analytics.web;

import static java.util.Objects.requireNonNullElse;

import dev.rymarovych.event_analytics.domain.AnalyticsQueryTimeoutException;
import dev.rymarovych.event_analytics.domain.InvalidTenantZoneException;
import java.net.URI;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Map MVC failures to RFC 9457 problem bodies using ResponseEntityExceptionHandler. This advice
 * replaces Boot's default problem handler and catches otherwise unhandled servlet exceptions before
 * request logging context is cleared.
 *
 * <p>Log each failure once: 5xx with stack traces, 4xx without. application.yaml disables duplicate
 * framework logs. Validation errors name Java properties (eventId), not JSON names (event_id).
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cntrl}");

  /**
   * A query cancelled by the read path's statement timeout is a 503, not a 500: the request was
   * well formed and the same request may well succeed over a narrower window or against a less
   * loaded database, so the client is told to back off rather than that it made a mistake.
   */
  @ExceptionHandler(AnalyticsQueryTimeoutException.class)
  @Nullable ResponseEntity<Object> handleAnalyticsQueryTimeout(
      AnalyticsQueryTimeoutException ex, WebRequest request) {
    var body =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "The query took too long to run and was cancelled. Retry over a narrower time window.");
    return handleExceptionInternal(
        ex, body, new HttpHeaders(), HttpStatus.SERVICE_UNAVAILABLE, request);
  }

  /**
   * Return 500 for an invalid stored reporting zone: retrying cannot fix the setting. Include the
   * offending value in the problem detail so the tenant can correct it.
   */
  @ExceptionHandler(InvalidTenantZoneException.class)
  @Nullable ResponseEntity<Object> handleInvalidTenantZone(
      InvalidTenantZoneException ex, WebRequest request) {
    var body =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "The reporting time zone configured for this tenant, '%s', is not a time zone this service can use."
                .formatted(ex.zone()));
    return handleExceptionInternal(
        ex, body, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
  }

  /**
   * Handle remaining servlet failures before logging context is cleared, avoiding duplicate
   * container/error-dispatch logs. Keep internal exception details in server logs, not responses.
   *
   * <p>Specific handlers take precedence. If method security is added, let AccessDeniedException
   * reach security handling rather than converting its 403 to 500.
   */
  @ExceptionHandler(Exception.class)
  @Nullable ResponseEntity<Object> handleUnclaimedFailure(Exception ex, WebRequest request) {
    var body =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "The service failed to handle this request.");
    return handleExceptionInternal(
        ex, body, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
  }

  @Override
  protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    var body = ex.getBody();
    body.setProperty(
        "errors",
        ex.getBindingResult().getFieldErrors().stream()
            .map(
                error ->
                    new FieldViolation(
                        error.getField(), requireNonNullElse(error.getDefaultMessage(), "invalid")))
            .toList());
    return handleExceptionInternal(ex, body, headers, status, request);
  }

  @Override
  protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    var body = ex.getBody();
    body.setProperty(
        "errors",
        ex.getParameterValidationResults().stream()
            .flatMap(
                result ->
                    result.getResolvableErrors().stream()
                        .map(
                            error ->
                                new FieldViolation(
                                    requireNonNullElse(
                                        result.getMethodParameter().getParameterName(), "unknown"),
                                    requireNonNullElse(error.getDefaultMessage(), "invalid"))))
            .toList());
    return handleExceptionInternal(ex, body, headers, status, request);
  }

  @Override
  protected @Nullable ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      @Nullable Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    if (body instanceof ProblemDetail problem && request instanceof ServletWebRequest servlet) {
      problem.setInstance(URI.create(servlet.getRequest().getRequestURI()));
    }
    logFailure(ex, statusCode, request);
    return super.handleExceptionInternal(ex, body, headers, statusCode, request);
  }

  private static void logFailure(Exception ex, HttpStatusCode statusCode, WebRequest request) {
    var principal = request.getUserPrincipal();
    var tenant = principal == null ? "-" : principal.getName();
    var target =
        request instanceof ServletWebRequest servlet
            ? servlet.getRequest().getMethod() + " " + servlet.getRequest().getRequestURI()
            : request.getDescription(false);
    if (statusCode.is5xxServerError()) {
      log.error("{} {} tenant={}", statusCode.value(), target, tenant, ex);
    } else {
      log.warn(
          "{} {} tenant={} {}: {}",
          statusCode.value(),
          target,
          tenant,
          ex.getClass().getSimpleName(),
          singleLine(ex.getMessage()));
    }
  }

  /**
   * Sanitize caller-controlled 4xx messages before logging; embedded newlines could forge log
   * entries.
   */
  private static String singleLine(@Nullable String message) {
    return message == null ? "" : CONTROL_CHARACTERS.matcher(message).replaceAll(" ");
  }

  /** One field-level constraint violation surfaced under the problem's {@code errors} member. */
  record FieldViolation(String field, String message) {}
}
