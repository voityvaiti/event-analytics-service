package dev.rymarovych.event_analytics.persistence;

import dev.rymarovych.event_analytics.domain.ActiveUsersReport;
import dev.rymarovych.event_analytics.domain.EventCountGrouping;
import dev.rymarovych.event_analytics.domain.EventCountReport;
import dev.rymarovych.event_analytics.domain.TimeGrouping;
import dev.rymarovych.event_analytics.domain.TopPagesReport;
import java.time.Instant;
import java.time.ZoneId;

/** Read-side aggregation queries over the raw event log. */
public interface EventStatsRepository {

  /**
   * Counts events occurring in the half-open interval {@code [from, to)}, grouped by the given
   * dimension. Time buckets are truncated at {@code zone}'s calendar boundaries; {@code zone} is
   * not consulted for non-time groupings. Buckets with no events are absent from the result.
   */
  EventCountReport countEvents(Instant from, Instant to, EventCountGrouping grouping, ZoneId zone);

  /**
   * Counts distinct active users per time bucket over the half-open interval {@code [from, to)}.
   * Buckets are truncated at {@code zone}'s calendar boundaries; buckets with no events are absent
   * from the result.
   */
  ActiveUsersReport countActiveUsers(Instant from, Instant to, TimeGrouping grouping, ZoneId zone);

  /**
   * Ranks pages by how many events in the half-open interval {@code [from, to)} reference them via
   * a {@code page_url} property, most-referenced first, ties broken by URL. Returns at most {@code
   * limit} pages and whether more ranked pages existed beyond them.
   */
  TopPagesReport topPages(Instant from, Instant to, int limit);
}
