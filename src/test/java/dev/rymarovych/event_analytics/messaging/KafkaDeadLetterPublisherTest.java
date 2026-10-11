package dev.rymarovych.event_analytics.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.rymarovych.event_analytics.domain.MalformedMessageException;
import dev.rymarovych.event_analytics.domain.PublicationFailedException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

class KafkaDeadLetterPublisherTest {

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, byte[]> kafkaTemplate = mock(KafkaTemplate.class);

  private final KafkaDeadLetterPublisher publisher = new KafkaDeadLetterPublisher(kafkaTemplate);
  private final byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);
  private final ConsumerRecord<String, byte[]> record =
      new ConsumerRecord<>(EventTopics.EVENTS, 2, 41, "key", payload);
  private final MalformedMessageException reason = new MalformedMessageException("unreadable");

  @Test
  @SuppressWarnings("unchecked")
  void sendsTheRecordUnchangedWithWhereItCameFromAndWhy() {
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(null));

    publisher.publish(record, reason);

    ArgumentCaptor<ProducerRecord<String, byte[]>> sent =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafkaTemplate).send(sent.capture());
    assertThat(sent.getValue().topic()).isEqualTo(EventTopics.DEAD_LETTERS);
    assertThat(sent.getValue().key()).isEqualTo("key");
    assertThat(sent.getValue().value()).isEqualTo(payload);
    assertThat(header(sent.getValue(), KafkaDeadLetterPublisher.ORIGINAL_TOPIC))
        .isEqualTo(EventTopics.EVENTS);
    assertThat(header(sent.getValue(), KafkaDeadLetterPublisher.ORIGINAL_PARTITION)).isEqualTo("2");
    assertThat(header(sent.getValue(), KafkaDeadLetterPublisher.ORIGINAL_OFFSET)).isEqualTo("41");
    assertThat(header(sent.getValue(), KafkaDeadLetterPublisher.FAILURE))
        .isEqualTo(MalformedMessageException.class.getName() + ": unreadable");
  }

  @Test
  @SuppressWarnings("unchecked")
  void throwsWhenTheDeadLetterTopicDoesNotConfirm() {
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

    assertThatThrownBy(() -> publisher.publish(record, reason))
        .isInstanceOf(PublicationFailedException.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void keepsTheInterruptWhenInterruptedWhileWaiting() {
    when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> publisher.publish(record, reason))
          .isInstanceOf(PublicationFailedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  private static String header(ProducerRecord<String, byte[]> record, String name) {
    return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
  }
}
