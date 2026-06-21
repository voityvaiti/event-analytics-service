package dev.rymarovych.event_analytics.persistence;

import dev.rymarovych.event_analytics.domain.NewEvent;

/** Append-only persistence for raw events. */
public interface EventRepository {

  /**
   * Persists a new event. Idempotent on {@code event_id}: a re-delivered event is silently skipped,
   * so client retries never create duplicate rows.
   */
  void save(NewEvent event);
}
