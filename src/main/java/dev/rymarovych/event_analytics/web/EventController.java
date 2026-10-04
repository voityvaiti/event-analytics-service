package dev.rymarovych.event_analytics.web;

import dev.rymarovych.event_analytics.domain.TenantName;
import dev.rymarovych.event_analytics.service.EventIngestionService;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingest events for Principal.getName(), which security resolves from the tenant claim. Using
 * Principal keeps claim naming and security-specific types out of this layer.
 */
@RestController
@RequestMapping("/api/v1/events")
@Tag(
    name = "Ingestion",
    description =
        """
        Accepting raw events. An event is identified by the `event_id` its sender chooses, and \
        re-sending one is a no-op — so any ingest request is safe to retry.\
        """)
@ProblemResponses
public class EventController {

  private final EventIngestionService ingestionService;
  private final EventMapper eventMapper;

  public EventController(EventIngestionService ingestionService, EventMapper eventMapper) {
    this.ingestionService = ingestionService;
    this.eventMapper = eventMapper;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  @ApiResponse(
      responseCode = "202",
      description = "Stored, or already present — a duplicate is a no-op")
  public void ingest(@Valid @RequestBody EventRequest request, Principal principal) {
    ingestionService.ingest(eventMapper.toNewEvent(request, tenantOf(principal)));
  }

  /**
   * Accepts a batch as one unit: any invalid event rejects the whole request, so the response is
   * the same empty {@code 202} the single-event path returns or a {@code 400} naming the offending
   * events. Nothing partial, and nothing per-event to report — duplicates are no-ops.
   */
  @PostMapping("/batch")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @ApiResponse(responseCode = "202", description = "Every event stored, or already present")
  public void ingestBatch(@Valid @RequestBody EventBatchRequest request, Principal principal) {
    ingestionService.ingestBatch(eventMapper.toNewEvents(request.events(), tenantOf(principal)));
  }

  private static TenantName tenantOf(Principal principal) {
    return new TenantName(principal.getName());
  }
}
