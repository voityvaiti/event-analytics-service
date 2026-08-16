package dev.rymarovych.event_analytics.web;

import static java.util.Objects.requireNonNullElse;

import dev.rymarovych.event_analytics.domain.AnalyticsQueryTimeoutException;
import java.net.URI;
import org.jspecify.annotations.Nullable;
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
 * Translates request-handling failures into RFC 9457 {@code application/problem+json} responses.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} means every Spring MVC exception — a
 * malformed or missing query parameter, an unreadable body, a bean-validation failure, and the
 * {@code ResponseStatusException} the controllers throw for bad time windows — already yields a
 * {@link ProblemDetail} body; this class only augments two of them. The auto-configured
 * problem-detail handler backs off once this advice is present, so no {@code
 * spring.mvc.problemdetails} property is needed.
 *
 * <p>Bean-validation failures carry an {@code errors} member listing the offending fields. The
 * field name is the Java property name (e.g. {@code eventId}), not the JSON name the client sent
 * (e.g. {@code event_id}); JSON-name fidelity is intentionally out of scope for now.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

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
    return super.handleExceptionInternal(ex, body, headers, statusCode, request);
  }

  /** One field-level constraint violation surfaced under the problem's {@code errors} member. */
  record FieldViolation(String field, String message) {}
}
