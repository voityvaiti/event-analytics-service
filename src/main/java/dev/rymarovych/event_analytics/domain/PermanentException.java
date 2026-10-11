package dev.rymarovych.event_analytics.domain;

import java.io.Serial;

/** A failure the same work will meet however often it is tried. */
public abstract class PermanentException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  protected PermanentException(String message, Throwable cause) {
    super(message, cause);
  }
}
