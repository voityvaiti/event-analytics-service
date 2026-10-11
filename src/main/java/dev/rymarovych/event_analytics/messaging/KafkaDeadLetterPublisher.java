package dev.rymarovych.event_analytics.messaging;

import dev.rymarovych.event_analytics.domain.PublicationFailedException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** {@link DeadLetterPublisher} to events.raw.dead-letter, keeping the original key and value. */
@Component
class KafkaDeadLetterPublisher implements DeadLetterPublisher {

  static final String ORIGINAL_TOPIC = "original-topic";
  static final String ORIGINAL_PARTITION = "original-partition";
  static final String ORIGINAL_OFFSET = "original-offset";
  static final String FAILURE = "failure";

  private final KafkaTemplate<String, byte[]> kafkaTemplate;

  KafkaDeadLetterPublisher(KafkaTemplate<String, byte[]> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  @Override
  public void publish(ConsumerRecord<String, byte[]> record, Exception reason) {
    var deadLetter = new ProducerRecord<>(EventTopics.DEAD_LETTERS, record.key(), record.value());
    header(deadLetter, ORIGINAL_TOPIC, record.topic());
    header(deadLetter, ORIGINAL_PARTITION, String.valueOf(record.partition()));
    header(deadLetter, ORIGINAL_OFFSET, String.valueOf(record.offset()));
    header(
        deadLetter,
        FAILURE,
        reason.getClass().getName() + ": " + Objects.requireNonNullElse(reason.getMessage(), ""));
    try {
      kafkaTemplate.send(deadLetter).get(10, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new PublicationFailedException("Interrupted while dead-lettering", interrupted);
    } catch (ExecutionException | TimeoutException | RuntimeException unconfirmed) {
      throw new PublicationFailedException("The dead-letter topic did not confirm", unconfirmed);
    }
  }

  private static void header(ProducerRecord<String, byte[]> record, String name, String value) {
    record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
  }
}
