package dev.rymarovych.event_analytics.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
 *
 * <p>Length limits count UTF-16 units, at most three UTF-8 bytes each, keeping the primary and
 * composite index entries within PostgreSQL's B-tree limit. See DESIGN for the input contract.
 */
public record EventRequest(
    @NotBlank @Size(max = EventRequest.MAX_EVENT_ID_LENGTH) @StorableText String eventId,
    @NotBlank @Size(max = EventRequest.MAX_USER_ID_LENGTH) @StorableText String userId,
    @NotBlank @Size(max = EventRequest.MAX_EVENT_TYPE_LENGTH) @StorableText String eventType,
    @JsonProperty("timestamp") @NotNull @StorableTimestamp Instant occurredAt,
    @Schema(
            implementation = Object.class,
            description = "Arbitrary JSON; absent or null becomes an empty object")
        @StorableJson
        JsonNode properties) {

  public static final int MAX_EVENT_ID_LENGTH = 256;
  public static final int MAX_USER_ID_LENGTH = 256;
  public static final int MAX_EVENT_TYPE_LENGTH = 128;

  public EventRequest {
    if (properties == null || properties.isNull()) {
      properties = JsonNodeFactory.instance.objectNode();
    }
  }
}
