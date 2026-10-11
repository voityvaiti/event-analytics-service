package dev.rymarovych.event_analytics.domain;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** A failure the same work will meet however often it is tried. */
public abstract class PermanentException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  protected PermanentException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
