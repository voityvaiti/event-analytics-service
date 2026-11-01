package dev.rymarovych.event_analytics.service;

/** Returns dead-lettered records to storage once whatever refused them has been fixed. */
public interface DeadLetterRedriveService {

  /**
   * Sends every dead letter not redriven before back to the persistence consumer.
   *
   * @return how many records were sent back; a batch request is one record
   */
  int redrive();
}
