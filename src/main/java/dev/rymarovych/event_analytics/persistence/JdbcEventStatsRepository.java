package dev.rymarovych.event_analytics.persistence;

import dev.rymarovych.event_analytics.domain.ActiveUsersBucket;
import dev.rymarovych.event_analytics.domain.ActiveUsersReport;
import dev.rymarovych.event_analytics.domain.AnalyticsQueryTimeoutException;
import dev.rymarovych.event_analytics.domain.EventCount;
import dev.rymarovych.event_analytics.domain.EventCountBucket;
import dev.rymarovych.event_analytics.domain.EventCountGrouping;
import dev.rymarovych.event_analytics.domain.EventCountReport;
import dev.rymarovych.event_analytics.domain.PageCount;
import dev.rymarovych.event_analytics.domain.TimeGrouping;
import dev.rymarovych.event_analytics.domain.TopPagesReport;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link JdbcClient}-backed {@link EventStatsRepository}.
 *
 * <p>Time buckets use the three-argument {@code date_trunc(unit, ts, zone)} so the boundary follows
 * the requested zone's calendar, independent of the session time zone.
 *
 * <p>Every query is scoped to one {@code source} — the tenant key — and narrowed by the {@code
 * occurred_at} range through the {@code (occurred_at, event_type)} index. {@code source} is not in
 * that index, so the range predicate still selects the rows but each one must be visited to check
 * its tenant; a count grouped by type can therefore no longer be answered from the index alone.
 * What that costs is measured rather than assumed — see the read cells under {@code perf/}.
 *
 * <p>Queries are bounded by the pool's {@code statement_timeout} (see {@code application.yaml}),
 * set once per connection rather than per request. A query the database cancels for exceeding it
 * arrives here as SQL state {@code 57014}, and this class is where that stops being a driver
 * detail: it is translated into {@link AnalyticsQueryTimeoutException} so the layers above answer
 * in their own vocabulary.
 */
@Repository
class JdbcEventStatsRepository implements EventStatsRepository {

  private static final String QUERY_CANCELED = "57014";

  private static final String COUNT_BY_TYPE =
      """
      SELECT event_type AS bucket, COUNT(*) AS count
      FROM events
      WHERE source = :source AND occurred_at >= :from AND occurred_at < :to
      GROUP BY event_type
      ORDER BY count DESC, event_type
      """;

  private static final String COUNT_BY_TIME_BUCKET =
      """
      SELECT date_trunc(:unit, occurred_at, :zone) AS bucket_start, COUNT(*) AS count
      FROM events
      WHERE source = :source AND occurred_at >= :from AND occurred_at < :to
      GROUP BY bucket_start
      ORDER BY bucket_start
      """;

  private static final String ACTIVE_USERS_BY_TIME_BUCKET =
      """
      SELECT date_trunc(:unit, occurred_at, :zone) AS bucket_start,
             COUNT(DISTINCT user_id) AS active_users
      FROM events
      WHERE source = :source AND occurred_at >= :from AND occurred_at < :to
      GROUP BY bucket_start
      ORDER BY bucket_start
      """;

  private static final String TOP_PAGES =
      """
      SELECT properties->>'page_url' AS page_url, COUNT(*) AS count
      FROM events
      WHERE source = :source AND occurred_at >= :from AND occurred_at < :to
        AND properties->>'page_url' IS NOT NULL
      GROUP BY page_url
      ORDER BY count DESC, page_url
      LIMIT :probe
      """;

  private final JdbcClient jdbcClient;

  JdbcEventStatsRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public EventCountReport countEvents(
      String source, Instant from, Instant to, EventCountGrouping grouping, ZoneId zone) {
    var buckets =
        translatingCancellation(
            () ->
                switch (grouping) {
                  case TYPE -> countByType(source, from, to);
                  case HOUR -> countByTimeBucket(source, from, to, TimeGrouping.HOUR, zone);
                  case DAY -> countByTimeBucket(source, from, to, TimeGrouping.DAY, zone);
                });
    return new EventCountReport(zone, buckets);
  }

  @Override
  public ActiveUsersReport countActiveUsers(
      String source, Instant from, Instant to, TimeGrouping grouping, ZoneId zone) {
    var buckets =
        translatingCancellation(
            () ->
                jdbcClient
                    .sql(ACTIVE_USERS_BY_TIME_BUCKET)
                    .param("source", source)
                    .param("from", getUtc(from))
                    .param("to", getUtc(to))
                    .param("unit", truncationUnit(grouping))
                    .param("zone", zone.getId())
                    .query(
                        (rs, rowNum) ->
                            new ActiveUsersBucket(
                                rs.getObject("bucket_start", OffsetDateTime.class).toInstant(),
                                rs.getLong("active_users")))
                    .list());
    return new ActiveUsersReport(zone, buckets);
  }

  /** Probes one row beyond the requested limit to learn whether the ranking was truncated. */
  @Override
  public TopPagesReport topPages(String source, Instant from, Instant to, int limit) {
    var pages =
        translatingCancellation(
            () ->
                jdbcClient
                    .sql(TOP_PAGES)
                    .param("source", source)
                    .param("from", getUtc(from))
                    .param("to", getUtc(to))
                    .param("probe", limit + 1)
                    .query(
                        (rs, rowNum) ->
                            new PageCount(rs.getString("page_url"), rs.getLong("count")))
                    .list());
    var hasMore = pages.size() > limit;
    var ranked = hasMore ? pages.subList(0, limit) : pages;
    return new TopPagesReport(List.copyOf(ranked), hasMore);
  }

  private static <T> T translatingCancellation(Supplier<T> query) {
    try {
      return query.get();
    } catch (DataAccessException ex) {
      if (wasCancelled(ex)) {
        throw new AnalyticsQueryTimeoutException(
            "Analytics query was cancelled for exceeding the statement timeout", ex);
      }
      throw ex;
    }
  }

  /**
   * Postgres reports a statement timeout and an operator-issued cancel under the same {@code 57014}
   * state. Only the former is expected here, because the timeout is set on this very transaction.
   */
  private static boolean wasCancelled(DataAccessException ex) {
    for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException
          && QUERY_CANCELED.equals(sqlException.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  private List<EventCount> countByType(String source, Instant from, Instant to) {
    return jdbcClient
        .sql(COUNT_BY_TYPE)
        .param("source", source)
        .param("from", getUtc(from))
        .param("to", getUtc(to))
        .query(
            (rs, rowNum) ->
                new EventCount(
                    new EventCountBucket.OfType(rs.getString("bucket")), rs.getLong("count")))
        .list();
  }

  private List<EventCount> countByTimeBucket(
      String source, Instant from, Instant to, TimeGrouping grouping, ZoneId zone) {
    return jdbcClient
        .sql(COUNT_BY_TIME_BUCKET)
        .param("source", source)
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
