package dev.rymarovych.event_analytics.consumer;

/** Where the persistence consumer is, read from its container rather than from the last command. */
public enum PersistenceConsumerStatus {
  RUNNING,
  STOPPING,
  STOPPED
}
