package dev.rymarovych.event_analytics.service;

import dev.rymarovych.event_analytics.domain.NewEvent;
import java.util.List;

/** Ingests events into the append-only event log. */
public interface EventIngestionService {

  /**
   * Ingests a single event. Idempotent on {@code event_id}: a duplicate is accepted as a no-op
   * rather than reported as an error.
   */
  void ingest(NewEvent event);

  /**
   * Persist a batch atomically. Duplicate event_id values, including duplicates within the batch,
   * are skipped as in single-event ingestion. A caller can safely retry the whole batch without
   * per-event results.
   */
  void ingestBatch(List<NewEvent> events);
}
