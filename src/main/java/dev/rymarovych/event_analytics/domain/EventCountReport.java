package dev.rymarovych.event_analytics.domain;

import java.time.ZoneId;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Event counts and their reporting zone. zone is null for type grouping because no time buckets
 * were computed; callers must not substitute UTC.
 */
public record EventCountReport(@Nullable ZoneId zone, List<EventCount> buckets) {}
