package dev.rymarovych.event_analytics.web;

import dev.rymarovych.event_analytics.domain.EventCountGrouping;
import dev.rymarovych.event_analytics.domain.TimeGrouping;
import dev.rymarovych.event_analytics.service.AnalyticsService;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read API for aggregated event analytics. */
@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

  private final AnalyticsService analyticsService;
  private final StatsMapper statsMapper;

  public StatsController(AnalyticsService analyticsService, StatsMapper statsMapper) {
    this.analyticsService = analyticsService;
    this.statsMapper = statsMapper;
  }

  @GetMapping("/event-counts")
  public EventCountsResponse eventCounts(
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(defaultValue = "type") EventCountGrouping groupBy) {
    var report = analyticsService.countEvents(from, to, groupBy);
    return statsMapper.toEventCountsResponse(groupBy, from, to, report);
  }

  @GetMapping("/active-users")
  public ActiveUsersResponse activeUsers(
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(defaultValue = "day") TimeGrouping groupBy) {
    var report = analyticsService.countActiveUsers(from, to, groupBy);
    return statsMapper.toActiveUsersResponse(groupBy, from, to, report);
  }
}
