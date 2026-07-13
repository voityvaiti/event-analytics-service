package dev.rymarovych.event_analytics.service;

import dev.rymarovych.event_analytics.domain.ActiveUsersReport;
import dev.rymarovych.event_analytics.domain.EventCountGrouping;
import dev.rymarovych.event_analytics.domain.EventCountReport;
import dev.rymarovych.event_analytics.domain.TimeGrouping;
import dev.rymarovych.event_analytics.domain.TopPagesReport;
import dev.rymarovych.event_analytics.persistence.EventStatsRepository;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.stereotype.Service;

/**
 * {@link AnalyticsService} that computes each answer on demand with a fresh SQL aggregation over
 * the raw event log — no caching or rollups yet; correctness over latency while the data set is
 * small. The cached/rollup-backed variant arrives as a separate implementation once load tests show
 * the on-the-fly query hurts.
 *
 * <p>Owns the bucketing time-zone policy. Every aggregation currently runs in {@link
 * #DEFAULT_BUCKETING_ZONE} (UTC); once tenants exist this default is replaced by the tenant's
 * configured zone, resolved here via the tenant service. The resolved zone travels with the result
 * so the caller can report which zone the buckets used.
 */
@Service
class OnDemandAnalyticsService implements AnalyticsService {

  private static final ZoneId DEFAULT_BUCKETING_ZONE = ZoneId.of("UTC");

  private final EventStatsRepository statsRepository;

  OnDemandAnalyticsService(EventStatsRepository statsRepository) {
    this.statsRepository = statsRepository;
  }

  @Override
  public EventCountReport countEvents(Instant from, Instant to, EventCountGrouping grouping) {
    return statsRepository.countEvents(from, to, grouping, DEFAULT_BUCKETING_ZONE);
  }

  @Override
  public ActiveUsersReport countActiveUsers(Instant from, Instant to, TimeGrouping grouping) {
    return statsRepository.countActiveUsers(from, to, grouping, DEFAULT_BUCKETING_ZONE);
  }

  @Override
  public TopPagesReport topPages(Instant from, Instant to, int limit) {
    return statsRepository.topPages(from, to, limit);
  }
}
