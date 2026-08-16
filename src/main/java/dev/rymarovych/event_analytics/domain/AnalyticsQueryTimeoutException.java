package dev.rymarovych.event_analytics.domain;

import java.io.Serial;

/**
 * Thrown when an analytics query was cancelled by the database for exceeding the read path's
 * statement timeout.
 *
 * <p>It lives in {@code domain} because it crosses the whole stack: the persistence layer raises it
 * in place of the driver's vendor exception, and the web layer maps it to a response. Nothing
 * between the two needs to know which database cancelled the query.
 */
public class AnalyticsQueryTimeoutException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public AnalyticsQueryTimeoutException(String message, Throwable cause) {
    super(message, cause);
  }
}
