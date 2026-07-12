package dev.rymarovych.event_analytics.domain;

import java.time.ZoneId;
import java.util.List;

/**
 * Result of an event-count aggregation: the per-bucket {@link EventCount}s together with the time
 * zone their time buckets were computed in, so a caller can report which zone the boundaries used.
 */
public record EventCountReport(ZoneId zone, List<EventCount> buckets) {}
