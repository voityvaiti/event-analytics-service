package dev.rymarovych.event_analytics.persistence;

import dev.rymarovych.event_analytics.domain.ActiveUsersBucket;
import dev.rymarovych.event_analytics.domain.AnalyticsQueryTimeoutException;
import dev.rymarovych.event_analytics.domain.EventCount;
import dev.rymarovych.event_analytics.domain.EventCountBucket;
import dev.rymarovych.event_analytics.domain.PageCount;
import dev.rymarovych.event_analytics.domain.TenantName;
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
 * Tenant-scoped JDBC analytics. date_trunc(unit, ts, zone) uses the requested calendar
 * independently of the session zone.
 *
 * <p>The (tenant_name, occurred_at, event_type, user_id) index covers counts and active-users.
 * top-pages still fetches properties from the heap.
 *
 * <p>The pool sets statement_timeout once per connection. Translate cancellation SQL state 57014
 * into AnalyticsQueryTimeoutException for the layers above.
 */
@Repository
class JdbcEventStatsRepository implements EventStatsRepository {

  private static final String QUERY_CANCELED = "57014";

  private static final String COUNT_BY_TYPE =
      """
      SELECT event_type AS bucket, COUNT(*) AS count
      FROM events
      WHERE tenant_name = :tenantName AND occurred_at >= :from AND occurred_at < :to
      GROUP BY event_type
      ORDER BY count DESC, event_type
      """;

  private static final String COUNT_BY_TIME_BUCKET =
      """
      SELECT date_trunc(:unit, occurred_at, :zone) AS bucket_start, COUNT(*) AS count
      FROM events
      WHERE tenant_name = :tenantName AND occurred_at >= :from AND occurred_at < :to
      GROUP BY bucket_start
      ORDER BY bucket_start
      """;

  private static final String ACTIVE_USERS_BY_TIME_BUCKET =
      """
      SELECT date_trunc(:unit, occurred_at, :zone) AS bucket_start,
             COUNT(DISTINCT user_id) AS active_users
      FROM events
      WHERE tenant_name = :tenantName AND occurred_at >= :from AND occurred_at < :to
      GROUP BY bucket_start
      ORDER BY bucket_start
      """;

  private static final String TOP_PAGES =
      """
      SELECT properties->>'page_url' AS page_url, COUNT(*) AS count
      FROM events
      WHERE tenant_name = :tenantName AND occurred_at >= :from AND occurred_at < :to
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
  public List<EventCount> countEventsByType(TenantName tenant, Instant from, Instant to) {
    return translatingCancellation(
        () ->
            jdbcClient
                .sql(COUNT_BY_TYPE)
                .param("tenantName", tenant.value())
                .param("from", getUtc(from))
                .param("to", getUtc(to))
                .query(
                    (rs, rowNum) ->
                        new EventCount(
                            new EventCountBucket.OfType(rs.getString("bucket")),
                            rs.getLong("count")))
                .list());
  }

  @Override
  public List<EventCount> countEventsByTimeBucket(
      TenantName tenant, Instant from, Instant to, TimeGrouping grouping, ZoneId zone) {
    return translatingCancellation(
        () ->
            jdbcClient
                .sql(COUNT_BY_TIME_BUCKET)
                .param("tenantName", tenant.value())
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
                .list());
  }

  @Override
  public List<ActiveUsersBucket> countActiveUsers(
      TenantName tenant, Instant from, Instant to, TimeGrouping grouping, ZoneId zone) {
    return translatingCancellation(
        () ->
            jdbcClient
                .sql(ACTIVE_USERS_BY_TIME_BUCKET)
                .param("tenantName", tenant.value())
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
  }

  /** Probes one row beyond the requested limit to learn whether the ranking was truncated. */
  @Override
  public TopPagesReport topPages(TenantName tenant, Instant from, Instant to, int limit) {
    var pages =
        translatingCancellation(
            () ->
                jdbcClient
                    .sql(TOP_PAGES)
                    .param("tenantName", tenant.value())
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
