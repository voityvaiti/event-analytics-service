package dev.rymarovych.event_analytics.service;

import dev.rymarovych.event_analytics.domain.NewEvent;
import java.util.List;

/** Accepts events for storage: a call returns once the broker holds them, storage follows. */
public interface EventIngestionService {

  /** Accepts a single event; re-sending its {@code event_id} later is a no-op. */
  void ingest(NewEvent event);

  /** Accepts a batch as one unit, stored whole or set aside whole; duplicates are no-ops. */
  void ingestBatch(List<NewEvent> events);
}
