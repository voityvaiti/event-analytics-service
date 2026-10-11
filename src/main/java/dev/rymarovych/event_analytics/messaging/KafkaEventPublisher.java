package dev.rymarovych.event_analytics.messaging;

import dev.rymarovych.event_analytics.domain.MessageTooLargeException;
import dev.rymarovych.event_analytics.domain.NewEvent;
import dev.rymarovych.event_analytics.domain.PublicationFailedException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes to events.raw within one budget that starts before {@code send()}, which itself blocks
 * on metadata and buffer space up to {@code max.block.ms}.
 */
@Component
class KafkaEventPublisher implements EventPublisher {

  static final int MAX_MESSAGE_BYTES = 768 * 1024;

  private final KafkaTemplate<String, byte[]> kafkaTemplate;
  private final EventsMessageCodec codec;
  private final Duration publicationTimeout;

  KafkaEventPublisher(
      KafkaTemplate<String, byte[]> kafkaTemplate,
      EventsMessageCodec codec,
      @Value("${app.ingestion.publication-timeout}") Duration publicationTimeout) {
    this.kafkaTemplate = kafkaTemplate;
    this.codec = codec;
    this.publicationTimeout = publicationTimeout;
  }

  @Override
  public void publish(List<NewEvent> events) {
    long deadline = System.nanoTime() + publicationTimeout.toNanos();
    byte[] payload = codec.encode(events);
    if (payload.length > MAX_MESSAGE_BYTES) {
      throw new MessageTooLargeException(
          "The events encode to %d bytes, over the %d-byte message limit"
              .formatted(payload.length, MAX_MESSAGE_BYTES));
    }
    try {
      kafkaTemplate
          .send(EventTopics.EVENTS, payload)
          .get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new PublicationFailedException("Interrupted while publishing", interrupted);
    } catch (ExecutionException | TimeoutException | RuntimeException unconfirmed) {
      throw new PublicationFailedException(
          "The broker did not confirm the events within " + publicationTimeout, unconfirmed);
    }
  }
}
