package dev.rymarovych.event_analytics.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Counts what the persistence group has finished with: committed events and dead letters. */
@Component
class PersistenceConsumerMetrics {

  private final Counter newEvents;
  private final Counter duplicateEvents;
  private final Counter deadLetters;

  PersistenceConsumerMetrics(MeterRegistry meterRegistry) {
    this.newEvents = persistedCounter(meterRegistry, "inserted");
    this.duplicateEvents = persistedCounter(meterRegistry, "duplicate");
    this.deadLetters =
        Counter.builder("persistence.consumer.dead.letters")
            .description("Records set aside on the dead-letter topic")
            .register(meterRegistry);
  }

  private static Counter persistedCounter(MeterRegistry meterRegistry, String outcome) {
    return Counter.builder("events.persisted")
        .description("Events in committed transactions, by whether the row was new")
        .tag("outcome", outcome)
        .register(meterRegistry);
  }

  void countStored(int events, int inserted) {
    newEvents.increment(inserted);
    duplicateEvents.increment(events - inserted);
  }

  void countDeadLetter() {
    deadLetters.increment();
  }
}
