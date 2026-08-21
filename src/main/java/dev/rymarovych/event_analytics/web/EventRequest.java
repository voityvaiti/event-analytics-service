package dev.rymarovych.event_analytics.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Inbound payload for a single event ingestion request.
 *
 * <p>The tenant is deliberately absent: a row's {@code source} comes from the authenticated token's
 * tenant claim, so a client cannot write events attributed to anyone else. A {@code source} field
 * in the body is ignored rather than rejected — it carries no authority, so failing the request
 * over it would only break clients for no gain in safety.
 *
 * <p>{@code properties} is arbitrary semi-structured context stored verbatim as {@code jsonb}; an
 * absent value, or an explicit JSON {@code null} (which Jackson binds to a {@code NullNode}), is
 * normalized to an empty object. The API document declares it as unconstrained JSON rather than
 * letting a schema be generated for it: {@code JsonNode} introspects into two dozen accessor flags
 * that say nothing about what a caller may send.
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
