package dev.rymarovych.event_analytics.web;

import dev.rymarovych.event_analytics.service.EventIngestionService;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingestion API for raw events.
 *
 * <p>{@link Principal#getName()} is the tenant: the security configuration resolves the principal
 * from the token's tenant claim, so the name a request arrives under is the {@code source} its rows
 * are written with. Taking it as a {@link Principal} rather than reading the claim here keeps the
 * claim's name in one place and this package free of any security dependency.
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventController {

  private final EventIngestionService ingestionService;
  private final EventMapper eventMapper;

  public EventController(EventIngestionService ingestionService, EventMapper eventMapper) {
    this.ingestionService = ingestionService;
    this.eventMapper = eventMapper;
  }

  @PostMapping
  public ResponseEntity<Void> ingest(
      @Valid @RequestBody EventRequest request, Principal principal) {
    ingestionService.ingest(eventMapper.toNewEvent(request, principal.getName()));
    return ResponseEntity.accepted().build();
  }

  /**
   * Accepts a batch as one unit: any invalid event rejects the whole request, so the response is
   * the same empty {@code 202} the single-event path returns or a {@code 400} naming the offending
   * events. Nothing partial, and nothing per-event to report — duplicates are no-ops.
   */
  @PostMapping("/batch")
  public ResponseEntity<Void> ingestBatch(
      @Valid @RequestBody EventBatchRequest request, Principal principal) {
    ingestionService.ingestBatch(eventMapper.toNewEvents(request.events(), principal.getName()));
    return ResponseEntity.accepted().build();
  }
}
