package dev.rymarovych.event_analytics.messaging;

import dev.rymarovych.event_analytics.domain.MalformedMessageException;
import dev.rymarovych.event_analytics.domain.NewEvent;
import dev.rymarovych.event_analytics.domain.TenantName;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Encodes one request's events as an events.raw record and back, with the application's mapper. */
@Component
public class EventsMessageCodec {

  private final JsonMapper jsonMapper;

  EventsMessageCodec(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  /** Every event comes from one request, so they share the tenant the message carries once. */
  public byte[] encode(List<NewEvent> events) {
    TenantName tenant = events.getFirst().tenant();
    if (events.stream().anyMatch(event -> !event.tenant().equals(tenant))) {
      throw new IllegalArgumentException("A message carries the events of one tenant");
    }
    return jsonMapper.writeValueAsBytes(
        new EventsMessage(
            EventsMessage.VERSION,
            tenant.value(),
            events.stream()
                .map(
                    event ->
                        new EventsMessage.Event(
                            event.eventId(),
                            event.userId(),
                            event.eventType(),
                            event.occurredAt(),
                            event.properties()))
                .toList()));
  }

  /** Anything that cannot be read, a record without a value included, is malformed. */
  public List<NewEvent> decode(byte[] payload) {
    if (payload == null || payload.length == 0) {
      throw new MalformedMessageException("The record has no value");
    }
    EventsMessage message;
    try {
      message = jsonMapper.readValue(payload, EventsMessage.class);
    } catch (RuntimeException unreadable) {
      throw new MalformedMessageException("The record is not an events message", unreadable);
    }
    if (message == null) {
      throw new MalformedMessageException("The record holds no events message");
    }
    if (message.version() != EventsMessage.VERSION) {
      throw new MalformedMessageException("Unknown events message version " + message.version());
    }
    TenantName tenant;
    try {
      tenant = new TenantName(present(message.tenant(), "tenant"));
    } catch (IllegalArgumentException invalid) {
      throw new MalformedMessageException("The message names an invalid tenant", invalid);
    }
    List<EventsMessage.Event> events = present(message.events(), "events");
    if (events.isEmpty()) {
      throw new MalformedMessageException("The message carries no events");
    }
    return events.stream()
        .map(
            event ->
                new NewEvent(
                    tenant,
                    present(event.eventId(), "event_id"),
                    present(event.userId(), "user_id"),
                    present(event.eventType(), "event_type"),
                    present(event.occurredAt(), "occurred_at"),
                    present(event.properties(), "properties")))
        .toList();
  }

  private static <T> T present(@Nullable T value, String field) {
    if (value == null) {
      throw new MalformedMessageException("The message is missing " + field);
    }
    return value;
  }
}
