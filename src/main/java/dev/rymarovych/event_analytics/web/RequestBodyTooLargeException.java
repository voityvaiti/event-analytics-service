package dev.rymarovych.event_analytics.web;

import java.io.IOException;
import java.io.Serial;

/** Thrown while reading an ingest request body that has passed its byte limit. */
class RequestBodyTooLargeException extends IOException {

  @Serial private static final long serialVersionUID = 1L;

  RequestBodyTooLargeException(long limit) {
    super("The request body is over the " + limit + "-byte limit");
  }
}
