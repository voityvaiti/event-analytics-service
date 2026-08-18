package dev.rymarovych.event_analytics.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Response envelope for an event-count query. Echoes the effective query so the result is
 * self-describing: {@code groupBy} (which reflects the applied default), the {@code from}/{@code
 * to} window, and the {@code timezone} the time buckets were computed in.
 *
 * <p>{@code timezone} is present exactly when the result has time buckets, so it is absent for
 * {@code groupBy=type}. Naming a zone there would name one nothing was computed in, and a tenant
 * would see its own zone under one grouping and UTC under another — indistinguishable from a bug in
 * the resolution. {@code top-pages}, the other shape that buckets by nothing, has never carried the
 * field at all.
 *
 * <p>The envelope also leaves room to add fields (a staleness marker once caching lands, for one)
 * without a breaking change.
 */
public record EventCountsResponse(
    String groupBy,
    Instant from,
    Instant to,
    @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String timezone,
    List<Bucket> buckets) {

  /**
   * One bucket of the result: the aggregation key — the event type, or an interval start as an RFC
   * 3339 UTC timestamp — and how many events fell in it.
   */
  public record Bucket(String bucket, long count) {}
}
