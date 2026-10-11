package dev.rymarovych.event_analytics.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;

/** Sets aside an events.raw record the consumer cannot store, unchanged, with where and why. */
public interface DeadLetterPublisher {

  /**
   * Returns once the dead-letter topic has acknowledged; otherwise throws, so nothing is skipped.
   */
  void publish(ConsumerRecord<String, byte[]> record, Exception reason);
}
