package dev.rymarovych.event_analytics.service;

import dev.rymarovych.event_analytics.domain.EventCountGrouping;
import dev.rymarovych.event_analytics.domain.EventCountReport;
import java.time.Instant;

/** Answers analytics questions over the stored event stream. */
public interface AnalyticsService {

  /**
   * Counts events in the half-open interval {@code [from, to)}, grouped by the given dimension. The
   * returned {@link EventCountReport} carries the time zone its time buckets were computed in.
   */
  EventCountReport countEvents(Instant from, Instant to, EventCountGrouping grouping);
}
