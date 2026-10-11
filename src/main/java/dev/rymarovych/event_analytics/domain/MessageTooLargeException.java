package dev.rymarovych.event_analytics.domain;

import java.io.Serial;

/** The events encode to a message larger than the broker is configured to take. */
public class MessageTooLargeException extends PermanentException {

  @Serial private static final long serialVersionUID = 1L;

  public MessageTooLargeException(String message) {
    super(message, null);
  }

  public MessageTooLargeException(String message, Throwable cause) {
    super(message, cause);
  }
}
