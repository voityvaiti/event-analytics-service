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
   * this method rather than of the driver. pgjdbc sends a whole batch followed by one {@code Sync},
   * and Postgres treats everything between two of those as an implicit transaction block, so a
   * batch already commits once today: {@code
   * EventBatchIngestionIntegrationTest.midBatchDatabaseFailureLeavesNothingWritten} passes with
   * this annotation removed. It changes no outcome, then. What it changes is where the guarantee
   * lives — declared by the method the endpoint's contract rests on, rather than inherited from a
   * driver detail that no test here pins and a version bump could take away.
   *
   * <p>The cost is constant in batch size: one commit round trip and one WAL flush per request,
   * against a batch that does a hundred inserts.
   */
  @Override
  @Transactional
  public void ingestBatch(List<NewEvent> events) {
    repository.saveAll(events);
  }
}
