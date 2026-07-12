package dev.rymarovych.event_analytics.persistence;

import dev.rymarovych.event_analytics.domain.EventCount;
import dev.rymarovych.event_analytics.domain.EventCountGrouping;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/** Read-side aggregation queries over the raw event log. */
public interface EventStatsRepository {

  /**
   * Counts events occurring in the half-open interval {@code [from, to)}, grouped by the given
   * dimension. Time buckets are truncated at {@code zone}'s calendar boundaries; {@code zone} is
   * not consulted for non-time groupings. Buckets with no events are absent from the result.
   */
  List<EventCount> countEvents(Instant from, Instant to, EventCountGrouping grouping, ZoneId zone);
}
