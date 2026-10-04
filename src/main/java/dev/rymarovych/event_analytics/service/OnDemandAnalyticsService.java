package dev.rymarovych.event_analytics.service;

import dev.rymarovych.event_analytics.domain.ActiveUsersReport;
import dev.rymarovych.event_analytics.domain.EventCountGrouping;
import dev.rymarovych.event_analytics.domain.EventCountReport;
import dev.rymarovych.event_analytics.domain.TenantName;
import dev.rymarovych.event_analytics.domain.TimeGrouping;
import dev.rymarovych.event_analytics.domain.TopPagesReport;
import dev.rymarovych.event_analytics.persistence.EventStatsRepository;
import dev.rymarovych.event_analytics.persistence.TenantSettingsRepository;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compute analytics directly from raw events; caching and rollups can use a separate implementation
 * when measurements justify them.
 *
 * <p>Resolve the tenant's reporting zone, defaulting to DEFAULT_BUCKETING_ZONE, and include it in
 * bucketed results. Type grouping reads no settings.
 *
 * <p>Read-only transactions let zone lookup and aggregation share one pooled connection, avoiding a
 * second connection wait.
 */
@Service
class OnDemandAnalyticsService implements AnalyticsService {

  private static final ZoneId DEFAULT_BUCKETING_ZONE = ZoneId.of("UTC");

  private final EventStatsRepository statsRepository;
  private final TenantSettingsRepository tenantSettingsRepository;

  OnDemandAnalyticsService(
      EventStatsRepository statsRepository, TenantSettingsRepository tenantSettingsRepository) {
    this.statsRepository = statsRepository;
    this.tenantSettingsRepository = tenantSettingsRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public EventCountReport countEvents(
      TenantName tenant, Instant from, Instant to, EventCountGrouping grouping) {
    return switch (grouping) {
      case TYPE -> new EventCountReport(null, statsRepository.countEventsByType(tenant, from, to));
      case HOUR -> countEventsPerTimeBucket(tenant, from, to, TimeGrouping.HOUR);
      case DAY -> countEventsPerTimeBucket(tenant, from, to, TimeGrouping.DAY);
    };
  }

  @Override
  @Transactional(readOnly = true)
  public ActiveUsersReport countActiveUsers(
      TenantName tenant, Instant from, Instant to, TimeGrouping grouping) {
    var zone = bucketingZone(tenant);
    return new ActiveUsersReport(
        zone, statsRepository.countActiveUsers(tenant, from, to, grouping, zone));
  }

  @Override
  public TopPagesReport topPages(TenantName tenant, Instant from, Instant to, int limit) {
    return statsRepository.topPages(tenant, from, to, limit);
  }

  private EventCountReport countEventsPerTimeBucket(
      TenantName tenant, Instant from, Instant to, TimeGrouping grouping) {
    var zone = bucketingZone(tenant);
    return new EventCountReport(
        zone, statsRepository.countEventsByTimeBucket(tenant, from, to, grouping, zone));
  }

  private ZoneId bucketingZone(TenantName tenant) {
    return tenantSettingsRepository.findBucketingZone(tenant).orElse(DEFAULT_BUCKETING_ZONE);
  }
}
