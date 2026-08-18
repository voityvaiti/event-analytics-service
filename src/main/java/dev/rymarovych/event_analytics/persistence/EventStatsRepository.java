package dev.rymarovych.event_analytics.persistence;

import dev.rymarovych.event_analytics.domain.ActiveUsersBucket;
import dev.rymarovych.event_analytics.domain.EventCount;
import dev.rymarovych.event_analytics.domain.TimeGrouping;
import dev.rymarovych.event_analytics.domain.TopPagesReport;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * Read-side aggregation queries over the raw event log.
 *
 * <p>Every method is scoped to a single {@code source} — the tenant key — so a caller can only be
 * answered about its own events. The scope is a required argument rather than an optional filter,
 * which is what makes an unscoped query impossible to write by omission.
 *
 * <p>A method takes a {@code zone} when, and only when, its query buckets by time. Which shapes
 * those are is a fact about the SQL below, so it is spelled out in these signatures rather than
 * left for a caller to know: asking for a count by type cannot be handed a zone, and asking for one
 * by time bucket cannot omit it.
 *
 * <p>Buckets are returned bare. The zone a result was computed in is the service's answer, not the
 * database's, so the reports that carry it are assembled a layer up.
 */
public interface EventStatsRepository {

  /**
   * Counts {@code source}'s events occurring in the half-open interval {@code [from, to)}, grouped
   * by event type, most frequent first. Types with no events are absent from the result.
   */
  List<EventCount> countEventsByType(String source, Instant from, Instant to);

  /**
   * Counts {@code source}'s events occurring in the half-open interval {@code [from, to)} per time
   * bucket, truncated at {@code zone}'s calendar boundaries. Buckets with no events are absent from
   * the result.
   */
  List<EventCount> countEventsByTimeBucket(
      String source, Instant from, Instant to, TimeGrouping grouping, ZoneId zone);

  /**
   * Counts {@code source}'s distinct active users per time bucket over the half-open interval
   * {@code [from, to)}. Buckets are truncated at {@code zone}'s calendar boundaries; buckets with
   * no events are absent from the result.
   */
  List<ActiveUsersBucket> countActiveUsers(
      String source, Instant from, Instant to, TimeGrouping grouping, ZoneId zone);

  /**
   * Ranks pages by how many of {@code source}'s events in the half-open interval {@code [from, to)}
   * reference them via a {@code page_url} property, most-referenced first, ties broken by URL.
   * Returns at most {@code limit} pages and whether more ranked pages existed beyond them.
   *
   * <p>The only method still returning a report, because {@code hasMore} is something only the
   * query knows: it probes one row past {@code limit} rather than counting the ranking twice.
   */
  TopPagesReport topPages(String source, Instant from, Instant to, int limit);
}
