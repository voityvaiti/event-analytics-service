package dev.rymarovych.event_analytics.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Response envelope for an active-users query. Echoes the effective query so the result is
 * self-describing: {@code groupBy} (which reflects the applied default), the {@code from}/{@code
 * to} window, and the {@code timezone} the time buckets were computed in.
 */
public record ActiveUsersResponse(
    String groupBy, Instant from, Instant to, String timezone, List<Bucket> buckets) {

  /**
   * One bucket of the result: the interval start as an RFC 3339 UTC timestamp, and how many
   * distinct users were active in it.
   *
   * <p>Named for the API document, for the reason {@code EventCountsResponse.Bucket} gives.
   */
  @Schema(name = "ActiveUsersBucket")
  public record Bucket(String bucket, long activeUsers) {}
}
