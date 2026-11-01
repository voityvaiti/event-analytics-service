package dev.rymarovych.event_analytics.messaging;

import dev.rymarovych.event_analytics.domain.RedriveIncompleteException;

/** Returns dead-lettered records to events.raw once whatever refused them has been fixed. */
public interface DeadLetterRedriver {

  /**
   * Republishes, unchanged, every dead letter present when the call starts and not redriven before.
   * Each is recorded as redriven only once events.raw has acknowledged it. A record refused again
   * is dead-lettered anew and waits for the next call.
   *
   * @return how many records were republished; a batch request is one record
   * @throws RedriveIncompleteException if the broker stops answering partway
   */
  int redrive();
}
