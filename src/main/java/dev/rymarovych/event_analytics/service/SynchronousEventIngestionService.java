package dev.rymarovych.event_analytics.service;

import dev.rymarovych.event_analytics.domain.NewEvent;
import dev.rymarovych.event_analytics.persistence.EventRepository;
import org.springframework.stereotype.Service;

/**
 * Synchronous {@link EventIngestionService}: persists straight through the repository in the
 * request thread.
 *
 * <p>Idempotency is enforced at the database via the {@code event_id} unique constraint, so a
 * duplicate event is accepted as a no-op rather than reported as an error.
 */
@Service
class SynchronousEventIngestionService implements EventIngestionService {

  private final EventRepository repository;

  SynchronousEventIngestionService(EventRepository repository) {
    this.repository = repository;
  }

  @Override
  public void ingest(NewEvent event) {
    repository.save(event);
  }
}
