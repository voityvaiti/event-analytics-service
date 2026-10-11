package dev.rymarovych.event_analytics.messaging;

import dev.rymarovych.event_analytics.domain.NewEvent;
import java.util.List;

/** Hands one request's events to the broker as a single message. */
public interface EventPublisher {

  /** Returns once the broker has acknowledged; an unconfirmed or oversized message throws. */
  void publish(List<NewEvent> events);
}
