package dev.rymarovych.event_analytics.web;

import dev.rymarovych.event_analytics.domain.ActiveUsersBucket;
import dev.rymarovych.event_analytics.domain.ActiveUsersReport;
import dev.rymarovych.event_analytics.domain.EventCount;
import dev.rymarovych.event_analytics.domain.EventCountBucket;
import dev.rymarovych.event_analytics.domain.EventCountGrouping;
import dev.rymarovych.event_analytics.domain.EventCountReport;
import dev.rymarovych.event_analytics.domain.PageCount;
import dev.rymarovych.event_analytics.domain.TimeGrouping;
import dev.rymarovych.event_analytics.domain.TopPagesReport;
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

  /**
   * The report always carries a zone, but only a time grouping computed anything in it, so the
   * response states one only then. See {@link EventCountsResponse}.
   */
  default EventCountsResponse toEventCountsResponse(
      EventCountGrouping grouping, Instant from, Instant to, EventCountReport report) {
    return new EventCountsResponse(
        grouping.name().toLowerCase(Locale.ROOT),
        from,
        to,
        grouping == EventCountGrouping.TYPE ? null : report.zone().getId(),
        toBuckets(report.buckets()));
  }

  default ActiveUsersResponse.Bucket toActiveUsersBucket(ActiveUsersBucket bucket) {
    return new ActiveUsersResponse.Bucket(
        DateTimeFormatter.ISO_INSTANT.format(bucket.start()), bucket.activeUsers());
  }

  List<ActiveUsersResponse.Bucket> toActiveUsersBuckets(List<ActiveUsersBucket> buckets);

  default ActiveUsersResponse toActiveUsersResponse(
      TimeGrouping grouping, Instant from, Instant to, ActiveUsersReport report) {
    return new ActiveUsersResponse(
        grouping.name().toLowerCase(Locale.ROOT),
        from,
        to,
        report.zone().getId(),
        toActiveUsersBuckets(report.buckets()));
  }

  TopPagesResponse.Page toPage(PageCount page);

  List<TopPagesResponse.Page> toPages(List<PageCount> pages);

  default TopPagesResponse toTopPagesResponse(
      Instant from, Instant to, int limit, TopPagesReport report) {
    return new TopPagesResponse(from, to, limit, report.hasMore(), toPages(report.pages()));
  }
}
