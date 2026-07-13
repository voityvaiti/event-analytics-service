package dev.rymarovych.event_analytics.web;

import java.time.Instant;
import java.util.List;

/**
 * Response envelope for an event-count query. Echoes the effective query so the result is
 * self-describing: {@code groupBy} (which reflects the applied default), the {@code from}/{@code
 * to} window, and the {@code timezone} the time buckets were computed in.
 *
 * <p>The envelope also leaves room to add fields (a staleness marker once caching lands, for one)
 * without a breaking change.
 */
public record EventCountsResponse(
    String groupBy, Instant from, Instant to, String timezone, List<Bucket> buckets) {

  /**
   * One bucket of the result: the aggregation key — the event type, or an interval start as an RFC
   * 3339 UTC timestamp — and how many events fell in it.
   */
  public record Bucket(String bucket, long count) {}
}
