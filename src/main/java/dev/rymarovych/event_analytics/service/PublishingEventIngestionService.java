package dev.rymarovych.event_analytics.service;

import dev.rymarovych.event_analytics.domain.NewEvent;
import dev.rymarovych.event_analytics.messaging.EventPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.stereotype.Service;

/** {@link EventIngestionService} that accepts once the broker holds the request as one message. */
@Service
class PublishingEventIngestionService implements EventIngestionService {

  private final EventPublisher publisher;
  private final Counter singleEventsIngested;
  private final Counter batchedEventsIngested;

  PublishingEventIngestionService(EventPublisher publisher, MeterRegistry meterRegistry) {
    this.publisher = publisher;
    this.singleEventsIngested = ingestedCounter(meterRegistry, "single");
    this.batchedEventsIngested = ingestedCounter(meterRegistry, "batch");
  }

  /** Counts accepted events by path; duplicates count too, since acceptance cannot tell them. */
  private static Counter ingestedCounter(MeterRegistry meterRegistry, String path) {
    return Counter.builder("events.ingested")
        .description("Events accepted for storage")
        .tag("path", path)
        .register(meterRegistry);
  }

  @Override
  public void ingest(NewEvent event) {
    publisher.publish(List.of(event));
    singleEventsIngested.increment();
  }

  @Override
  public void ingestBatch(List<NewEvent> events) {
    publisher.publish(events);
    batchedEventsIngested.increment(events.size());
  }
}
