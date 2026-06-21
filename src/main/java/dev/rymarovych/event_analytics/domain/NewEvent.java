package dev.rymarovych.event_analytics.domain;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * Command describing an event to be persisted, decoupled from any layer's own shape.
 *
 * <p>Lives in a neutral package that both {@code service} and {@code persistence} depend on, so the
 * cross-layer dependency arrows point only inward and neither the controller's DTO nor any
 * persistence type leaks across a boundary.
 */
public record NewEvent(
    String source,
    String eventId,
    String userId,
    String eventType,
    Instant occurredAt,
    JsonNode properties) {}
