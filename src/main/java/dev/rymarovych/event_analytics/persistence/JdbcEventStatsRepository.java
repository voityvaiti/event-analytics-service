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
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link JdbcClient}-backed {@link EventStatsRepository}.
 *
 * <p>Time buckets use the three-argument {@code date_trunc(unit, ts, zone)} so the boundary follows
 * the requested zone's calendar, independent of the session time zone. All queries are driven by
 * the {@code (occurred_at, event_type)} index through their {@code occurred_at} range predicate.
 *
 * <p>Every query runs in a read-only transaction that first bounds itself with {@code
 * statement_timeout}, so a query the database cannot finish in time is cancelled server-side rather
 * than holding a pooled connection the ingest path draws from as well. Scoping the setting to reads
 * without a second connection pool costs three extra statements per request — {@code BEGIN READ
 * ONLY}, the timeout, and the commit, which the driver appears to send in two flushes. What that is
 * worth in latency is the read load cells' answer, not this comment's.
 */
@Repository
class JdbcEventStatsRepository implements EventStatsRepository {

  private static final String APPLY_STATEMENT_TIMEOUT =
      "SELECT set_config('statement_timeout', :timeout, TRUE)";

  private static final String QUERY_CANCELED = "57014";

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

  private static final String TOP_PAGES =
      """
      SELECT properties->>'page_url' AS page_url, COUNT(*) AS count
      FROM events
      WHERE occurred_at >= :from AND occurred_at < :to
        AND properties->>'page_url' IS NOT NULL
      GROUP BY page_url
      ORDER BY count DESC, page_url
      LIMIT :probe
      """;

  private final JdbcClient jdbcClient;
  private final String statementTimeout;

  JdbcEventStatsRepository(JdbcClient jdbcClient, AnalyticsQueryProperties queryProperties) {
    this.jdbcClient = jdbcClient;
    this.statementTimeout = queryProperties.timeout().toMillis() + "ms";
  }

  @Override
  @Transactional(readOnly = true)
  public EventCountReport countEvents(
      Instant from, Instant to, EventCountGrouping grouping, ZoneId zone) {
    var buckets =
        withStatementTimeout(
            () ->
                switch (grouping) {
                  case TYPE -> countByType(from, to);
                  case HOUR -> countByTimeBucket(from, to, TimeGrouping.HOUR, zone);
                  case DAY -> countByTimeBucket(from, to, TimeGrouping.DAY, zone);
                });
    return new EventCountReport(zone, buckets);
  }

  @Override
  @Transactional(readOnly = true)
  public ActiveUsersReport countActiveUsers(
      Instant from, Instant to, TimeGrouping grouping, ZoneId zone) {
    var buckets =
        withStatementTimeout(
            () ->
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
                    .list());
    return new ActiveUsersReport(zone, buckets);
  }

  /** Probes one row beyond the requested limit to learn whether the ranking was truncated. */
  @Override
  @Transactional(readOnly = true)
  public TopPagesReport topPages(Instant from, Instant to, int limit) {
    var pages =
        withStatementTimeout(
            () ->
                jdbcClient
                    .sql(TOP_PAGES)
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

  private <T> T withStatementTimeout(Supplier<T> query) {
    jdbcClient
        .sql(APPLY_STATEMENT_TIMEOUT)
        .param("timeout", statementTimeout)
        .query(String.class)
        .single();
    try {
      return query.get();
    } catch (DataAccessException ex) {
      if (wasCancelled(ex)) {
        throw new AnalyticsQueryTimeoutException(
            "Analytics query exceeded the " + statementTimeout + " statement timeout", ex);
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
