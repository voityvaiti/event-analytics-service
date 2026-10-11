package dev.rymarovych.event_analytics.messaging;

/** Names of the topics accepted events travel through. */
public final class EventTopics {

  public static final String EVENTS = "events.raw";
  public static final String DEAD_LETTERS = "events.raw.dead-letter";

  private EventTopics() {}
}
