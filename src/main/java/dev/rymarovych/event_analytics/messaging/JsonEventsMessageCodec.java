package dev.rymarovych.event_analytics.messaging;

import dev.rymarovych.event_analytics.domain.TenantName;
import dev.rymarovych.event_analytics.domain.ingestion.MalformedMessageException;
import dev.rymarovych.event_analytics.domain.ingestion.NewEvent;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** {@link EventsMessageCodec} through the application's JsonMapper, so exact decimals survive. */
@Component
class JsonEventsMessageCodec implements EventsMessageCodec {

  private final JsonMapper jsonMapper;

  JsonEventsMessageCodec(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  @Override
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

  @Override
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
