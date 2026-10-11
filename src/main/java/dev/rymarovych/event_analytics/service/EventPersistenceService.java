package dev.rymarovych.event_analytics.service;

import dev.rymarovych.event_analytics.domain.NewEvent;
import java.util.List;

/** Stores accepted events, possibly from several requests, as one unit. */
public interface EventPersistenceService {

  /** Stores every event or none; an event ID already stored is a no-op. */
  void persist(List<NewEvent> events);
}
