package dev.rymarovych.event_analytics.persistence;

import dev.rymarovych.event_analytics.domain.ActiveUsersBucket;
import dev.rymarovych.event_analytics.domain.ActiveUsersReport;
import dev.rymarovych.event_analytics.domain.EventCount;
import dev.rymarovych.event_analytics.domain.EventCountBucket;
import dev.rymarovych.event_analytics.domain.EventCountGrouping;
import dev.rymarovych.event_analytics.domain.EventCountReport;
import dev.rymarovych.event_analytics.domain.TimeGrouping;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link JdbcClient}-backed {@link EventStatsRepository}.
 *
 * <p>Time buckets use the three-argument {@code date_trunc(unit, ts, zone)} so the boundary follows
 * the requested zone's calendar, independent of the session time zone. All queries are driven by
 * the {@code (occurred_at, event_type)} index through their {@code occurred_at} range predicate.
 */
@Repository
class JdbcEventStatsRepository implements EventStatsRepository {

  private static final String COUNT_BY_TYPE =
      """
      SELECT event_type AS bucket, COUNT(*) AS count
      FROM events
      WHERE occurred_at >= :from AND occurred_at < :to
      GROUP BY event_type
      ORDER BY count DESC, event_type
      """;

  private static final String COUNT_BY_TIME_BUCKET =
      """
      SELECT date_trunc(:unit, occurred_at, :zone) AS bucket_start, COUNT(*) AS count
      FROM events
      WHERE occurred_at >= :from AND occurred_at < :to
      GROUP BY bucket_start
      ORDER BY bucket_start
      """;

  private static final String ACTIVE_USERS_BY_TIME_BUCKET =
      """
      SELECT date_trunc(:unit, occurred_at, :zone) AS bucket_start,
             COUNT(DISTINCT user_id) AS active_users
      FROM events
      WHERE occurred_at >= :from AND occurred_at < :to
      GROUP BY bucket_start
      ORDER BY bucket_start
      """;

  private final JdbcClient jdbcClient;

  JdbcEventStatsRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public EventCountReport countEvents(
      Instant from, Instant to, EventCountGrouping grouping, ZoneId zone) {
    var buckets =
        switch (grouping) {
          case TYPE -> countByType(from, to);
          case HOUR -> countByTimeBucket(from, to, TimeGrouping.HOUR, zone);
          case DAY -> countByTimeBucket(from, to, TimeGrouping.DAY, zone);
        };
    return new EventCountReport(zone, buckets);
  }

  @Override
  public ActiveUsersReport countActiveUsers(
      Instant from, Instant to, TimeGrouping grouping, ZoneId zone) {
    var buckets =
        jdbcClient
            .sql(ACTIVE_USERS_BY_TIME_BUCKET)
            .param("from", getUtc(from))
            .param("to", getUtc(to))
            .param("unit", truncationUnit(grouping))
            .param("zone", zone.getId())
            .query(
                (rs, rowNum) ->
                    new ActiveUsersBucket(
                        rs.getObject("bucket_start", OffsetDateTime.class).toInstant(),
                        rs.getLong("active_users")))
            .list();
    return new ActiveUsersReport(zone, buckets);
  }

  private List<EventCount> countByType(Instant from, Instant to) {
    return jdbcClient
        .sql(COUNT_BY_TYPE)
        .param("from", getUtc(from))
        .param("to", getUtc(to))
        .query(
            (rs, rowNum) ->
                new EventCount(
                    new EventCountBucket.OfType(rs.getString("bucket")), rs.getLong("count")))
        .list();
  }

  private List<EventCount> countByTimeBucket(
      Instant from, Instant to, TimeGrouping grouping, ZoneId zone) {
    return jdbcClient
        .sql(COUNT_BY_TIME_BUCKET)
        .param("from", getUtc(from))
        .param("to", getUtc(to))
        .param("unit", truncationUnit(grouping))
        .param("zone", zone.getId())
        .query(
            (rs, rowNum) ->
                new EventCount(
                    new EventCountBucket.OfInterval(
                        rs.getObject("bucket_start", OffsetDateTime.class).toInstant()),
                    rs.getLong("count")))
        .list();
  }

  private static String truncationUnit(TimeGrouping grouping) {
    return switch (grouping) {
      case HOUR -> "hour";
      case DAY -> "day";
    };
  }

  private static OffsetDateTime getUtc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
