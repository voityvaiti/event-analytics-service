package dev.rymarovych.event_analytics.domain;

import java.io.Serial;

/** The database refused the events themselves, so storing them again will fail again. */
public class EventsRefusedByStorageException extends PermanentException {

  @Serial private static final long serialVersionUID = 1L;

  public EventsRefusedByStorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
