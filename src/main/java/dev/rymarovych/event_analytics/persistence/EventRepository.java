package dev.rymarovych.event_analytics.persistence;

import dev.rymarovych.event_analytics.domain.NewEvent;
import java.util.List;

/** Append-only persistence for raw events. */
public interface EventRepository {

  /**
   * Persists a new event. Idempotent on {@code event_id}: a re-delivered event is silently skipped,
   * so client retries never create duplicate rows.
   */
  void save(NewEvent event);

  /**
   * Persists every event in one batched statement, with the same per-event idempotency {@link
   * #save} has. Whether the batch lands as a unit is the caller's to decide: this method does not
   * open a transaction of its own.
   */
  void saveAll(List<NewEvent> events);
}
