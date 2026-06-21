package dev.rymarovych.event_analytics.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Inbound payload for a single event ingestion request.
 *
 * <p>{@code source} identifies the originating tenant/application. It is client-supplied for now;
 * once JWT authentication lands it will be derived from the authenticated principal instead.
 *
 * <p>{@code properties} is arbitrary semi-structured context stored verbatim as {@code jsonb}; an
 * absent value, or an explicit JSON {@code null} (which Jackson binds to a {@code NullNode}), is
 * normalized to an empty object.
 */
public record EventRequest(
    @NotBlank String source,
    @NotBlank String eventId,
    @NotBlank String userId,
    @NotBlank String eventType,
    @JsonProperty("timestamp") @NotNull Instant occurredAt,
    JsonNode properties) {

  public EventRequest {
    if (properties == null || properties.isNull()) {
      properties = JsonNodeFactory.instance.objectNode();
    }
  }
}
