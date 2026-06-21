package dev.rymarovych.event_analytics.service;

import dev.rymarovych.event_analytics.domain.NewEvent;

/** Ingests events into the append-only event log. */
public interface EventIngestionService {

  /**
   * Ingests a single event. Idempotent on {@code event_id}: a duplicate is accepted as a no-op
   * rather than reported as an error.
   */
  void ingest(NewEvent event);
}
