package dev.rymarovych.event_analytics.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Single-event payload. Tenant identity comes only from the authenticated token; a body-supplied
 * tenant/source field is ignored.
 *
 * <p>Store properties as arbitrary JSON, normalizing missing or explicit null to an empty object.
 * Describe it as unconstrained JSON in OpenAPI to avoid exposing JsonNode accessor flags as a
 * schema.
 */
public record EventRequest(
    @NotBlank String eventId,
    @NotBlank String userId,
    @NotBlank String eventType,
    @JsonProperty("timestamp") @NotNull Instant occurredAt,
    @Schema(
            implementation = Object.class,
            description = "Arbitrary JSON; absent or null becomes an empty object")
        JsonNode properties) {

  public EventRequest {
    if (properties == null || properties.isNull()) {
      properties = JsonNodeFactory.instance.objectNode();
    }
  }
}
