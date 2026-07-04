package dev.rymarovych.event_analytics.service;

import dev.rymarovych.event_analytics.domain.NewEvent;
import dev.rymarovych.event_analytics.persistence.EventRepository;
import java.time.Duration;
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

  private static final Duration ARTIFICIAL_INGEST_DELAY = Duration.ofMillis(5);

  private final EventRepository repository;

  SynchronousEventIngestionService(EventRepository repository) {
    this.repository = repository;
  }

  @Override
  public void ingest(NewEvent event) {
    pause();
    repository.save(event);
  }

  /**
   * Deliberate per-request latency injected on the {@code test/ci-smoke} branch to exercise the
   * performance-comparison workflow: it should surface this branch as slower than {@code main}.
   * Remove before merging.
   */
  private void pause() {
    try {
      Thread.sleep(ARTIFICIAL_INGEST_DELAY);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
