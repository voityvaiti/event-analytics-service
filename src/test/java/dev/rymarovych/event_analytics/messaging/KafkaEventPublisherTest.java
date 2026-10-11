package dev.rymarovych.event_analytics.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.rymarovych.event_analytics.domain.NewEvent;
import dev.rymarovych.event_analytics.domain.PublicationFailedException;
import dev.rymarovych.event_analytics.domain.TenantName;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

class KafkaEventPublisherTest {

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, byte[]> kafkaTemplate = mock(KafkaTemplate.class);

  private final KafkaEventPublisher publisher =
      new KafkaEventPublisher(
          kafkaTemplate,
          new EventsMessageCodec(JsonMapper.builder().build()),
          Duration.ofMillis(50));

  @Test
  void failsWhenTheBrokerDoesNotAnswerWithinTheBudget() {
    when(kafkaTemplate.send(eq(EventTopics.EVENTS), any(byte[].class)))
        .thenReturn(new CompletableFuture<>());

    assertThatThrownBy(() -> publisher.publish(List.of(event())))
        .isInstanceOf(PublicationFailedException.class);
  }

  @Test
  void keepsTheInterruptWhenInterruptedWhileWaiting() {
    when(kafkaTemplate.send(eq(EventTopics.EVENTS), any(byte[].class)))
        .thenReturn(new CompletableFuture<>());

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> publisher.publish(List.of(event())))
          .isInstanceOf(PublicationFailedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  private static NewEvent event() {
    return new NewEvent(
        new TenantName("web"),
        "evt_1",
        "user_42",
        "page_view",
        Instant.parse("2026-05-24T10:15:30Z"),
        JsonNodeFactory.instance.objectNode());
  }
}
