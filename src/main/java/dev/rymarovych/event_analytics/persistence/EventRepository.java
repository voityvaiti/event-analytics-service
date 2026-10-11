package dev.rymarovych.event_analytics.persistence;

import dev.rymarovych.event_analytics.domain.NewEvent;
import java.util.List;

/** Append-only persistence for raw events; failures are recoverable unless the rows are refused. */
public interface EventRepository {

  /**
   * Persists every event in one batched statement; an event ID already stored is skipped. Whether
   * the batch lands as a unit is the caller's to decide: this method opens no transaction.
   */
  void saveAll(List<NewEvent> events);
}
