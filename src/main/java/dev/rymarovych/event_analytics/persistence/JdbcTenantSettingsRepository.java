package dev.rymarovych.event_analytics.persistence;

import dev.rymarovych.event_analytics.domain.InvalidTenantZoneException;
import dev.rymarovych.event_analytics.domain.TenantName;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link JdbcClient}-backed {@link TenantSettingsRepository}: one primary-key lookup on a table
 * with a row per tenant that reports outside UTC.
 *
 * <p>The stored text is parsed here rather than passed up as a string, so a value that is not a
 * zone is caught at the edge of the system that produced it instead of somewhere downstream. The
 * zone written by {@code scripts/actions/set-tenant-zone} is one Postgres knows; this parse is what
 * catches a row that arrived some other way, or a name Postgres knows and the JVM's tzdb does not.
 */
@Repository
class JdbcTenantSettingsRepository implements TenantSettingsRepository {

  private static final String SELECT_BUCKETING_ZONE =
      """
      SELECT timezone
      FROM tenants
      WHERE name = :name
      """;

  private final JdbcClient jdbcClient;

  JdbcTenantSettingsRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public Optional<ZoneId> findBucketingZone(TenantName tenant) {
    return jdbcClient
        .sql(SELECT_BUCKETING_ZONE)
        .param("name", tenant.value())
        .query(String.class)
        .optional()
        .map(timezone -> parseZone(tenant, timezone));
  }

  private static ZoneId parseZone(TenantName tenant, String timezone) {
    try {
      return ZoneId.of(timezone);
    } catch (DateTimeException ex) {
      throw new InvalidTenantZoneException(tenant, timezone, ex);
    }
  }
}
