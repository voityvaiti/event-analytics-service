package dev.rymarovych.event_analytics.domain;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** A failure the same work may get past if tried again later. */
public abstract class RecoverableException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  protected RecoverableException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
