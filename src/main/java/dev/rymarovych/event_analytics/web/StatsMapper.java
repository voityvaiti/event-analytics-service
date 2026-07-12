package dev.rymarovych.event_analytics.web;

import dev.rymarovych.event_analytics.domain.EventCount;
import dev.rymarovych.event_analytics.domain.EventCountBucket;
import dev.rymarovych.event_analytics.domain.EventCountGrouping;
import dev.rymarovych.event_analytics.domain.EventCountReport;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.mapstruct.Mapper;

/**
 * Maps analytics domain results to their outbound web representation at the web/service boundary.
 */
@Mapper(componentModel = "spring")
interface StatsMapper {

  EventCountsResponse.Bucket toBucket(EventCount count);

  List<EventCountsResponse.Bucket> toBuckets(List<EventCount> buckets);

  /**
   * Renders a bucket key as text: the event type verbatim, or an interval start as an RFC 3339 UTC
   * timestamp via {@link DateTimeFormatter#ISO_INSTANT} — the same format {@link
   * InstantParameterConverter} parses on input.
   */
  default String label(EventCountBucket bucket) {
    return switch (bucket) {
      case EventCountBucket.OfType type -> type.eventType();
      case EventCountBucket.OfInterval interval ->
          DateTimeFormatter.ISO_INSTANT.format(interval.start());
    };
  }

  default EventCountsResponse toEventCountsResponse(
      EventCountGrouping grouping, Instant from, Instant to, EventCountReport report) {
    var buckets = report.buckets();
    var totalEvents = buckets.stream().mapToLong(EventCount::count).sum();
    return new EventCountsResponse(
        grouping.name().toLowerCase(Locale.ROOT),
        from,
        to,
        report.zone().getId(),
        totalEvents,
        toBuckets(buckets));
  }
}
