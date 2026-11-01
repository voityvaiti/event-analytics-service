package dev.rymarovych.event_analytics.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/** What the persistence group has finished with, and how long its records took to get there. */
@Component
class PersistenceConsumerMetrics {

  private final Counter newEvents;
  private final Counter duplicateEvents;
  private final Counter deadLetters;
  private final Timer visibilityDelay;

  PersistenceConsumerMetrics(MeterRegistry meterRegistry) {
    this.newEvents = persistedCounter(meterRegistry, "inserted");
    this.duplicateEvents = persistedCounter(meterRegistry, "duplicate");
    this.deadLetters =
        Counter.builder("persistence.consumer.dead.letters")
            .description("Records set aside on the dead-letter topic")
            .register(meterRegistry);
    this.visibilityDelay =
        Timer.builder("events.visibility.delay")
            .description("From publication to the commit that makes a record's events readable")
            .publishPercentileHistogram()
            .maximumExpectedValue(Duration.ofMinutes(5))
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

  /** Measured from the timestamp the producer stamped at publication, before the broker acked. */
  void timeVisibility(ConsumerRecord<String, byte[]> record) {
    visibilityDelay.record(System.currentTimeMillis() - record.timestamp(), TimeUnit.MILLISECONDS);
  }
}
