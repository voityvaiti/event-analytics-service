package dev.rymarovych.event_analytics.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Event counts with the effective grouping and [from, to) window. Include timezone only for time
 * buckets; omit it for type grouping. The envelope allows future metadata without changing the
 * response shape.
 */
public record EventCountsResponse(
    String groupBy,
    Instant from,
    Instant to,
    @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String timezone,
    List<Bucket> buckets) {

  /**
   * Bucket key (event type or RFC 3339 UTC interval start) and event count. Give the schema a
   * distinct name to avoid colliding with ActiveUsersResponse.Bucket in OpenAPI's flat namespace.
   */
  @Schema(name = "EventCountBucket")
  public record Bucket(String bucket, long count) {}
}
