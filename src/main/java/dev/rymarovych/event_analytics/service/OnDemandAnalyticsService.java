package dev.rymarovych.event_analytics.service;

import dev.rymarovych.event_analytics.domain.ActiveUsersReport;
import dev.rymarovych.event_analytics.domain.EventCountGrouping;
import dev.rymarovych.event_analytics.domain.EventCountReport;
import dev.rymarovych.event_analytics.domain.TimeGrouping;
import dev.rymarovych.event_analytics.domain.TopPagesReport;
import dev.rymarovych.event_analytics.persistence.EventStatsRepository;
import dev.rymarovych.event_analytics.persistence.TenantSettingsRepository;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AnalyticsService} that computes each answer on demand with a fresh SQL aggregation over
 * the raw event log — no caching or rollups yet; correctness over latency while the data set is
 * small. The cached/rollup-backed variant arrives as a separate implementation once load tests show
 * the on-the-fly query hurts.
 *
 * <p>Owns the bucketing time-zone policy: a time-bucketed aggregation runs in the tenant's own
 * zone, and in {@link #DEFAULT_BUCKETING_ZONE} when the tenant has none stored. Absence is an
 * answer, not a gap — a tenant reporting in UTC needs no settings row — so a token is all it takes
 * to get correct figures. The resolved zone travels with the result, so the caller can report which
 * zone the buckets used.
 *
 * <p>Grouping by event type reads no settings, because it produces no buckets for a zone to place.
 * That also leaves the cheapest query in the system paying for nothing it uses.
 *
 * <p>Where a zone is resolved the read is transactional and read-only — not for atomicity, which a
 * single-statement read does not need, but so the settings lookup and the aggregation ride one
 * pooled connection. Taking a connection twice means queueing for one twice, and the pool is the
 * only place a request waits.
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
      String source, Instant from, Instant to, EventCountGrouping grouping) {
    return switch (grouping) {
      case TYPE -> new EventCountReport(null, statsRepository.countEventsByType(source, from, to));
      case HOUR -> countEventsPerTimeBucket(source, from, to, TimeGrouping.HOUR);
      case DAY -> countEventsPerTimeBucket(source, from, to, TimeGrouping.DAY);
    };
  }

  @Override
  @Transactional(readOnly = true)
  public ActiveUsersReport countActiveUsers(
      String source, Instant from, Instant to, TimeGrouping grouping) {
    var zone = bucketingZone(source);
    return new ActiveUsersReport(
        zone, statsRepository.countActiveUsers(source, from, to, grouping, zone));
  }

  @Override
  public TopPagesReport topPages(String source, Instant from, Instant to, int limit) {
    return statsRepository.topPages(source, from, to, limit);
  }

  private EventCountReport countEventsPerTimeBucket(
      String source, Instant from, Instant to, TimeGrouping grouping) {
    var zone = bucketingZone(source);
    return new EventCountReport(
        zone, statsRepository.countEventsByTimeBucket(source, from, to, grouping, zone));
  }

  private ZoneId bucketingZone(String source) {
    return tenantSettingsRepository.findBucketingZone(source).orElse(DEFAULT_BUCKETING_ZONE);
  }
}
