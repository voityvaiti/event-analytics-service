package dev.rymarovych.event_analytics.service;

import dev.rymarovych.event_analytics.domain.ActiveUsersReport;
import dev.rymarovych.event_analytics.domain.EventCountGrouping;
import dev.rymarovych.event_analytics.domain.EventCountReport;
import dev.rymarovych.event_analytics.domain.TenantName;
import dev.rymarovych.event_analytics.domain.TimeGrouping;
import dev.rymarovych.event_analytics.domain.TopPagesReport;
import java.time.Instant;

/**
 * Answers analytics questions over the stored event stream, always about one tenant.
 *
 * <p>The tenant is the one asking, not a filter it chose, so no caller can widen its own scope. It
 * is also what the reporting zone is resolved from, so two tenants asking the same question over
 * the same window can legitimately get different bucket boundaries.
 */
public interface AnalyticsService {

  /**
   * Counts {@code tenant}'s events in the half-open interval {@code [from, to)}, grouped by the
   * given dimension. The returned {@link EventCountReport} carries the time zone its time buckets
   * were computed in.
   */
  EventCountReport countEvents(
      TenantName tenant, Instant from, Instant to, EventCountGrouping grouping);

  /**
   * Counts {@code tenant}'s distinct active users in the half-open interval {@code [from, to)},
   * grouped into time buckets. The returned {@link ActiveUsersReport} carries the time zone its
   * buckets were computed in.
   */
  ActiveUsersReport countActiveUsers(
      TenantName tenant, Instant from, Instant to, TimeGrouping grouping);

  /**
   * Ranks the pages most referenced by {@code tenant}'s events in the half-open interval {@code
   * [from, to)}, returning at most {@code limit} pages and whether the ranking was truncated.
   */
  TopPagesReport topPages(TenantName tenant, Instant from, Instant to, int limit);
}
