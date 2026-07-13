package dev.rymarovych.event_analytics.service;

import dev.rymarovych.event_analytics.domain.ActiveUsersReport;
import dev.rymarovych.event_analytics.domain.EventCountGrouping;
import dev.rymarovych.event_analytics.domain.EventCountReport;
import dev.rymarovych.event_analytics.domain.TimeGrouping;
import java.time.Instant;

/** Answers analytics questions over the stored event stream. */
public interface AnalyticsService {

  /**
   * Counts events in the half-open interval {@code [from, to)}, grouped by the given dimension. The
   * returned {@link EventCountReport} carries the time zone its time buckets were computed in.
   */
  EventCountReport countEvents(Instant from, Instant to, EventCountGrouping grouping);

  /**
   * Counts distinct active users in the half-open interval {@code [from, to)}, grouped into time
   * buckets. The returned {@link ActiveUsersReport} carries the time zone its buckets were computed
   * in.
   */
  ActiveUsersReport countActiveUsers(Instant from, Instant to, TimeGrouping grouping);
}
