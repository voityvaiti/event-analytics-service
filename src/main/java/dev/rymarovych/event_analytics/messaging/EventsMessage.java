package dev.rymarovych.event_analytics.messaging;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** The JSON body of one events.raw record: one accepted request's tenant and events. */
record EventsMessage(int version, String tenant, List<Event> events) {

  static final int VERSION = 1;

  record Event(
      String eventId, String userId, String eventType, Instant occurredAt, JsonNode properties) {}
}
