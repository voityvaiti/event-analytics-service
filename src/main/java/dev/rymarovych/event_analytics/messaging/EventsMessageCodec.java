package dev.rymarovych.event_analytics.messaging;

import dev.rymarovych.event_analytics.domain.ingestion.NewEvent;
import java.util.List;

/** Encodes one request's events as an events.raw record and back. */
public interface EventsMessageCodec {

  /** Every event comes from one request, so they share the tenant the message carries once. */
  byte[] encode(List<NewEvent> events);

  /** Anything that cannot be read, a record without a value included, is malformed. */
  List<NewEvent> decode(byte[] payload);
}
