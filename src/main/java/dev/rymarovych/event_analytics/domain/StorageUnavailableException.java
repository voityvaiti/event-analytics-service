package dev.rymarovych.event_analytics.domain;

import java.io.Serial;

/** The database could not store the events this time, for reasons not in the events. */
public class StorageUnavailableException extends RecoverableException {

  @Serial private static final long serialVersionUID = 1L;

  public StorageUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
