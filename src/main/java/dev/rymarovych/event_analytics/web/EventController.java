package dev.rymarovych.event_analytics.web;

import dev.rymarovych.event_analytics.service.EventIngestionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ingestion API for raw events. */
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
  public ResponseEntity<Void> ingest(@Valid @RequestBody EventRequest request) {
    ingestionService.ingest(eventMapper.toNewEvent(request));
    return ResponseEntity.accepted().build();
  }
}
