package dev.rymarovych.event_analytics.domain;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** The broker stopped answering partway through a redrive; what it confirmed stays recorded. */
public class RedriveIncompleteException extends RecoverableException {

  @Serial private static final long serialVersionUID = 1L;

  public RedriveIncompleteException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
