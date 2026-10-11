package dev.rymarovych.event_analytics.domain;

import java.io.Serial;

/** An events.raw record that cannot be read back as an accepted request. */
public class MalformedMessageException extends PermanentException {

  @Serial private static final long serialVersionUID = 1L;

  public MalformedMessageException(String message) {
    super(message, null);
  }

  public MalformedMessageException(String message, Throwable cause) {
    super(message, cause);
  }
}
