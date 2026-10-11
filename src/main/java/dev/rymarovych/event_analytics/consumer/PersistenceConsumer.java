package dev.rymarovych.event_analytics.consumer;

/** Starts and stops the persistence consumer; a stop lets the records in hand finish first. */
public interface PersistenceConsumer {

  PersistenceConsumerStatus start();

  PersistenceConsumerStatus stop();

  PersistenceConsumerStatus status();
}
