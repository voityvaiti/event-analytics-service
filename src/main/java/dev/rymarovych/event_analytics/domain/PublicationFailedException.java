package dev.rymarovych.event_analytics.domain;

import java.io.Serial;

/** The broker did not confirm the events in time, so whether they were accepted is unknown. */
public class PublicationFailedException extends RecoverableException {

  @Serial private static final long serialVersionUID = 1L;

  public PublicationFailedException(String message, Throwable cause) {
    super(message, cause);
  }
}
