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
   * Ingests a batch of events as one unit: either all of them are persisted or none is, so a caller
   * whose batch was rejected can retry the whole thing.
   *
   * <p>Idempotent per event, exactly as {@link #ingest} is. A re-delivered batch, or the same
   * {@code event_id} twice inside one batch, is accepted as a no-op rather than reported as an
   * error, which is why the batch needs no per-event result.
   */
  void ingestBatch(List<NewEvent> events);
}
