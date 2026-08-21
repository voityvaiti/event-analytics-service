package dev.rymarovych.event_analytics.web;

import dev.rymarovych.event_analytics.domain.EventCountGrouping;
import dev.rymarovych.event_analytics.domain.TenantName;
import dev.rymarovych.event_analytics.domain.TimeGrouping;
import dev.rymarovych.event_analytics.service.AnalyticsService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.security.Principal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read API for aggregated event analytics.
 *
 * <p>Every answer is scoped to the caller's own tenant, taken from {@link Principal#getName()} —
 * the security configuration resolves the principal from the token's tenant claim. A tenant cannot
 * ask about anyone else's events, because there is no parameter with which to name one.
 */
@RestController
@RequestMapping("/api/v1/stats")
@Tag(
    name = "Analytics",
    description =
        """
        Aggregates over the calling tenant's events. Every window is `[from, to)` — the start \
        instant counts, the end instant does not — and time buckets fall on the boundaries of the \
        tenant's own reporting zone, which the answer names.\
        """)
@ProblemResponses
@ApiResponse(
    responseCode = "503",
    description = "The query hit the read path's statement timeout; retry over a narrower window",
    content =
        @Content(
            mediaType = "application/problem+json",
            schema = @Schema(implementation = ProblemDetail.class)))
public class StatsController {

  private final AnalyticsService analyticsService;
  private final StatsMapper statsMapper;

  public StatsController(AnalyticsService analyticsService, StatsMapper statsMapper) {
    this.analyticsService = analyticsService;
    this.statsMapper = statsMapper;
  }

  @ApiResponse(
      responseCode = "200",
      description = "Event counts for the window, bucketed by the requested dimension",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = EventCountsResponse.class)))
  @GetMapping("/event-counts")
  public EventCountsResponse eventCounts(
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(defaultValue = "type") EventCountGrouping groupBy,
      Principal principal) {
    requireOrderedWindow(from, to);
    var report = analyticsService.countEvents(tenantOf(principal), from, to, groupBy);
    return statsMapper.toEventCountsResponse(groupBy, from, to, report);
  }

  @ApiResponse(
      responseCode = "200",
      description = "Distinct users per time bucket, in the tenant's reporting zone",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ActiveUsersResponse.class)))
  @GetMapping("/active-users")
  public ActiveUsersResponse activeUsers(
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(defaultValue = "day") TimeGrouping groupBy,
      Principal principal) {
    requireOrderedWindow(from, to);
    var report = analyticsService.countActiveUsers(tenantOf(principal), from, to, groupBy);
    return statsMapper.toActiveUsersResponse(groupBy, from, to, report);
  }

  @ApiResponse(
      responseCode = "200",
      description = "The most-referenced pages, ranked, with a truncation flag",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = TopPagesResponse.class)))
  @GetMapping("/top-pages")
  public TopPagesResponse topPages(
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit,
      Principal principal) {
    requireOrderedWindow(from, to);
    var report = analyticsService.topPages(tenantOf(principal), from, to, limit);
    return statsMapper.toTopPagesResponse(from, to, limit, report);
  }

  private static TenantName tenantOf(Principal principal) {
    return new TenantName(principal.getName());
  }

  private static void requireOrderedWindow(Instant from, Instant to) {
    if (!from.isBefore(to)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be strictly before to");
    }
  }
}
