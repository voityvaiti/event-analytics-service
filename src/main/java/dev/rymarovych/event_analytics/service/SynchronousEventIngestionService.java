package dev.rymarovych.event_analytics.service;

import dev.rymarovych.event_analytics.domain.NewEvent;
import dev.rymarovych.event_analytics.persistence.EventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
  private final Counter singleEventsIngested;
  private final Counter batchedEventsIngested;

  SynchronousEventIngestionService(EventRepository repository, MeterRegistry meterRegistry) {
    this.repository = repository;
    this.singleEventsIngested = ingestedCounter(meterRegistry, "single");
    this.batchedEventsIngested = ingestedCounter(meterRegistry, "batch");
  }

  /**
   * Count events by ingest path, since batch request rates understate event volume. Increment after
   * writing but before commit; commit failures can overcount and appear in error metrics.
   */
  private static Counter ingestedCounter(MeterRegistry meterRegistry, String path) {
    return Counter.builder("events.ingested")
        .description("Events accepted for storage")
        .tag("path", path)
        .register(meterRegistry);
  }

  @Override
  public void ingest(NewEvent event) {
    repository.save(event);
    singleEventsIngested.increment();
  }

  /**
   * Declare batch atomicity at the service boundary. pgjdbc currently makes a batch atomic through
   * its protocol Sync, so midBatchDatabaseFailureLeavesNothingWritten also passes without this
   * annotation. Keep the guarantee independent of that driver detail.
   *
   * <p>Commit cost is one round trip and WAL flush per request, independent of batch size.
   */
  @Override
  @Transactional
  public void ingestBatch(List<NewEvent> events) {
    repository.saveAll(events);
    batchedEventsIngested.increment(events.size());
  }
}
