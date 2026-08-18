package dev.rymarovych.event_analytics.domain;

import java.time.ZoneId;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Result of an event-count aggregation: one {@link EventCount} per bucket, and the zone the buckets
 * were computed in.
 *
 * <p>{@code zone} is {@code null} when the grouping produced no time buckets, which is the honest
 * answer for a count by event type: there is no boundary a zone could have moved. Absent rather
 * than defaulted, so nothing downstream has to know which groupings bucket by time in order to
 * report the zone correctly.
 */
public record EventCountReport(@Nullable ZoneId zone, List<EventCount> buckets) {}
