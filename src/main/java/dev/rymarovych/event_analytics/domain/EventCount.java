package dev.rymarovych.event_analytics.domain;

/** One row of an event-count aggregation: how many events fall in a {@link EventCountBucket}. */
public record EventCount(EventCountBucket bucket, long count) {}
