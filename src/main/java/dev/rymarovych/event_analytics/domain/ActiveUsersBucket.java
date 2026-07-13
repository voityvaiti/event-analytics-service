package dev.rymarovych.event_analytics.domain;

import java.time.Instant;

/**
 * One time bucket of an active-users aggregation: how many distinct users produced at least one
 * event in the interval starting at {@code start}. The key is a typed instant, not a formatted
 * string: rendering to text is a boundary concern.
 */
public record ActiveUsersBucket(Instant start, long activeUsers) {}
