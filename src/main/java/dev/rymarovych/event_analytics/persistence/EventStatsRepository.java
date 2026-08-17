package dev.rymarovych.event_analytics.persistence;

import dev.rymarovych.event_analytics.domain.ActiveUsersReport;
import dev.rymarovych.event_analytics.domain.EventCountGrouping;
import dev.rymarovych.event_analytics.domain.EventCountReport;
import dev.rymarovych.event_analytics.domain.TimeGrouping;
import dev.rymarovych.event_analytics.domain.TopPagesReport;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Read-side aggregation queries over the raw event log.
 *
 * <p>Every method is scoped to a single {@code source} — the tenant key — so a caller can only be
 * answered about its own events. The scope is a required argument rather than an optional filter,
 * which is what makes an unscoped query impossible to write by omission.
 */
public interface EventStatsRepository {

  /**
   * Counts {@code source}'s events occurring in the half-open interval {@code [from, to)}, grouped
   * by the given dimension. Time buckets are truncated at {@code zone}'s calendar boundaries;
   * {@code zone} is not consulted for non-time groupings. Buckets with no events are absent from
   * the result.
   */
  EventCountReport countEvents(
      String source, Instant from, Instant to, EventCountGrouping grouping, ZoneId zone);

  /**
   * Counts {@code source}'s distinct active users per time bucket over the half-open interval
   * {@code [from, to)}. Buckets are truncated at {@code zone}'s calendar boundaries; buckets with
   * no events are absent from the result.
   */
  ActiveUsersReport countActiveUsers(
      String source, Instant from, Instant to, TimeGrouping grouping, ZoneId zone);

  /**
   * Ranks pages by how many of {@code source}'s events in the half-open interval {@code [from, to)}
   * reference them via a {@code page_url} property, most-referenced first, ties broken by URL.
   * Returns at most {@code limit} pages and whether more ranked pages existed beyond them.
   */
  TopPagesReport topPages(String source, Instant from, Instant to, int limit);
}
