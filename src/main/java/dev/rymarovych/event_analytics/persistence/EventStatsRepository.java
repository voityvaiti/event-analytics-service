package dev.rymarovych.event_analytics.persistence;

import dev.rymarovych.event_analytics.domain.ActiveUsersBucket;
import dev.rymarovych.event_analytics.domain.EventCount;
import dev.rymarovych.event_analytics.domain.TenantName;
import dev.rymarovych.event_analytics.domain.TimeGrouping;
import dev.rymarovych.event_analytics.domain.TopPagesReport;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * Tenant-scoped aggregations over raw events. TenantName is required, preventing accidental
 * unscoped queries. Only time-bucketed methods accept a zone. The service adds zone metadata to the
 * returned buckets.
 */
public interface EventStatsRepository {

  /**
   * Counts {@code tenant}'s events occurring in the half-open interval {@code [from, to)}, grouped
   * by event type, most frequent first. Types with no events are absent from the result.
   */
  List<EventCount> countEventsByType(TenantName tenant, Instant from, Instant to);

  /**
   * Counts {@code tenant}'s events occurring in the half-open interval {@code [from, to)} per time
   * bucket, truncated at {@code zone}'s calendar boundaries. Buckets with no events are absent from
   * the result.
   */
  List<EventCount> countEventsByTimeBucket(
      TenantName tenant, Instant from, Instant to, TimeGrouping grouping, ZoneId zone);

  /**
   * Counts {@code tenant}'s distinct active users per time bucket over the half-open interval
   * {@code [from, to)}. Buckets are truncated at {@code zone}'s calendar boundaries; buckets with
   * no events are absent from the result.
   */
  List<ActiveUsersBucket> countActiveUsers(
      TenantName tenant, Instant from, Instant to, TimeGrouping grouping, ZoneId zone);

  /**
   * Rank pages referenced by the tenant's events in [from, to), by count descending and URL for
   * ties. Return at most limit pages and hasMore, determined by probing one extra row rather than
   * counting the ranking twice.
   */
  TopPagesReport topPages(TenantName tenant, Instant from, Instant to, int limit);
}
