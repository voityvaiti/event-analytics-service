package dev.rymarovych.event_analytics.domain;

import java.io.Serial;

/**
 * Signals database cancellation of an analytics query for exceeding statement_timeout. Persistence
 * translates the vendor exception into this domain type; the web layer maps it to a response.
 */
public class AnalyticsQueryTimeoutException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public AnalyticsQueryTimeoutException(String message, Throwable cause) {
    super(message, cause);
  }
}
