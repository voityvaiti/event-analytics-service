package dev.rymarovych.event_analytics.persistence;

import dev.rymarovych.event_analytics.domain.EventCount;
import dev.rymarovych.event_analytics.domain.EventCountBucket;
import dev.rymarovych.event_analytics.domain.EventCountGrouping;
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
 * the requested zone's calendar, independent of the session time zone. Both queries are driven by
 * the {@code (occurred_at, event_type)} index.
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

  private final JdbcClient jdbcClient;

  JdbcEventStatsRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public List<EventCount> countEvents(
      Instant from, Instant to, EventCountGrouping grouping, ZoneId zone) {
    var fromValue = OffsetDateTime.ofInstant(from, ZoneOffset.UTC);
    var toValue = OffsetDateTime.ofInstant(to, ZoneOffset.UTC);

    if (grouping == EventCountGrouping.TYPE) {
      return jdbcClient
          .sql(COUNT_BY_TYPE)
          .param("from", fromValue)
          .param("to", toValue)
          .query(
              (rs, rowNum) ->
                  new EventCount(
                      new EventCountBucket.OfType(rs.getString("bucket")), rs.getLong("count")))
          .list();
    }

    return jdbcClient
        .sql(COUNT_BY_TIME_BUCKET)
        .param("from", fromValue)
        .param("to", toValue)
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

  private static String truncationUnit(EventCountGrouping grouping) {
    return switch (grouping) {
      case HOUR -> "hour";
      case DAY -> "day";
      case TYPE -> throw new IllegalArgumentException("TYPE is not a time-bucket grouping");
    };
  }
}
