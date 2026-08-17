package dev.rymarovych.event_analytics.service;

import dev.rymarovych.event_analytics.domain.NewEvent;
import dev.rymarovych.event_analytics.persistence.EventRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

  /**
   * The only transaction in the codebase, and it is here so that "all or nothing" is a property of
   * this method rather than of the driver. A batched statement may well already commit once: the
   * extended protocol treats everything between two {@code Sync} messages as an implicit
   * transaction block, and pgjdbc sends a whole batch followed by one. But nothing in this project
   * pins that, a driver upgrade could change it, and the endpoint's contract is that a rejected
   * batch leaves no rows behind — so the boundary is declared rather than inherited.
   *
   * <p>Its cost is constant in batch size: one commit round trip and one WAL flush, against a batch
   * that does a hundred inserts.
   */
  @Override
  @Transactional
  public void ingestBatch(List<NewEvent> events) {
    repository.saveAll(events);
  }
}
