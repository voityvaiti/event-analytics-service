package dev.rymarovych.event_analytics.persistence;

import dev.rymarovych.event_analytics.domain.InvalidTenantZoneException;
import dev.rymarovych.event_analytics.domain.TenantName;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Look up tenant reporting settings with JdbcClient and parse the zone at the persistence boundary.
 * Reject invalid values, including manually inserted names or PostgreSQL zones absent from the
 * JVM's tzdb.
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
