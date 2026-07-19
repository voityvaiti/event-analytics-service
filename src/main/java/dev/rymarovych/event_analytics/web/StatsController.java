package dev.rymarovych.event_analytics.web;

import dev.rymarovych.event_analytics.domain.EventCountGrouping;
import dev.rymarovych.event_analytics.domain.TimeGrouping;
import dev.rymarovych.event_analytics.service.AnalyticsService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    requireOrderedWindow(from, to);
    var report = analyticsService.countEvents(from, to, groupBy);
    return statsMapper.toEventCountsResponse(groupBy, from, to, report);
  }

  @GetMapping("/active-users")
  public ActiveUsersResponse activeUsers(
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(defaultValue = "day") TimeGrouping groupBy) {
    requireOrderedWindow(from, to);
    var report = analyticsService.countActiveUsers(from, to, groupBy);
    return statsMapper.toActiveUsersResponse(groupBy, from, to, report);
  }

  @GetMapping("/top-pages")
  public TopPagesResponse topPages(
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
    requireOrderedWindow(from, to);
    var report = analyticsService.topPages(from, to, limit);
    return statsMapper.toTopPagesResponse(from, to, limit, report);
  }

  private static void requireOrderedWindow(Instant from, Instant to) {
    if (!from.isBefore(to)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be strictly before to");
    }
  }
}
