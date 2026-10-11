package dev.rymarovych.event_analytics.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rymarovych.event_analytics.TestcontainersConfiguration;
import dev.rymarovych.event_analytics.TopicRecords;
import dev.rymarovych.event_analytics.domain.MessageTooLargeException;
import dev.rymarovych.event_analytics.domain.NewEvent;
import dev.rymarovych.event_analytics.domain.TenantName;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaAdmin;
import tools.jackson.databind.node.JsonNodeFactory;

/** Runs with listeners stopped, so what is published stays on the topic to be read back. */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Import(TestcontainersConfiguration.class)
class KafkaEventPublisherIntegrationTest {

  @Autowired private EventPublisher publisher;
  @Autowired private EventsMessageCodec codec;
  @Autowired private KafkaAdmin kafkaAdmin;

  @Test
  void publishesOneRequestAsOneRecord() {
    var events = List.of(event("evt_published_1", "{}"), event("evt_published_2", "{}"));

    publisher.publish(events);

    assertThat(TopicRecords.read(kafkaAdmin, EventTopics.EVENTS))
        .extracting(record -> codec.decode(record.value()))
        .contains(events);
  }

  @Test
  void refusesAMessageOverTheLimitBeforeSendingIt() {
    int before = TopicRecords.read(kafkaAdmin, EventTopics.EVENTS).size();
    var oversized =
        List.of(event("evt_oversized", "x".repeat(KafkaEventPublisher.MAX_MESSAGE_BYTES)));

    assertThatThrownBy(() -> publisher.publish(oversized))
        .isInstanceOf(MessageTooLargeException.class);
    assertThat(TopicRecords.read(kafkaAdmin, EventTopics.EVENTS)).hasSize(before);
  }

  private static NewEvent event(String eventId, String label) {
    return new NewEvent(
        new TenantName("web"),
        eventId,
        "user_42",
        "page_view",
        Instant.parse("2026-05-24T10:15:30Z"),
        JsonNodeFactory.instance.objectNode().put("label", label));
  }
}
