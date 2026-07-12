package dev.rymarovych.event_analytics.domain;

import java.time.Instant;

/**
 * Aggregation key of one {@link EventCount} row. Closed to exactly two shapes so callers can switch
 * over it exhaustively.
 *
 * <ul>
 *   <li>{@link OfType} — grouping by {@code event_type}; the key is the client-defined type name.
 *   <li>{@link OfInterval} — grouping by hour/day; the key is the truncated interval start as an
 *       instant. A typed instant, not a formatted string: rendering to text is a boundary concern.
 * </ul>
 */
public sealed interface EventCountBucket {

  record OfType(String eventType) implements EventCountBucket {}

  record OfInterval(Instant start) implements EventCountBucket {}
}
