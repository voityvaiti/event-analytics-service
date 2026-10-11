package dev.rymarovych.event_analytics.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rymarovych.event_analytics.InjectedDatabaseFailure;
import dev.rymarovych.event_analytics.TestcontainersConfiguration;
import dev.rymarovych.event_analytics.TopicRecords;
import dev.rymarovych.event_analytics.domain.MalformedMessageException;
import dev.rymarovych.event_analytics.domain.NewEvent;
import dev.rymarovych.event_analytics.domain.TenantName;
import dev.rymarovych.event_analytics.messaging.EventPublisher;
import dev.rymarovych.event_analytics.messaging.EventTopics;
import dev.rymarovych.event_analytics.messaging.EventsMessageCodec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.node.JsonNodeFactory;

/** Publishes to events.raw and watches what the persistence group stores and sets aside. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EventsRecordListenerIntegrationTest {

  @Autowired private EventPublisher publisher;
  @Autowired private KafkaTemplate<String, byte[]> kafkaTemplate;
  @Autowired private KafkaAdmin kafkaAdmin;
  @Autowired private EventsMessageCodec codec;
  @Autowired private JdbcClient jdbcClient;

  @AfterEach
  void cleanUp() {
    InjectedDatabaseFailure.remove(jdbcClient);
    jdbcClient.sql("DELETE FROM events").update();
  }

  @Test
  void storesEachRequestOnceEvenWhenDeliveredTwice() {
    var single = List.of(event("evt_single"));
    var batch = List.of(event("evt_batch_1"), event("evt_batch_2"));

    publisher.publish(single);
    publisher.publish(batch);
    publisher.publish(batch);

    await()
        .untilAsserted(
            () ->
                assertThat(storedEventIds())
                    .containsExactlyInAnyOrder("evt_single", "evt_batch_1", "evt_batch_2"));
  }

  @Test
  void deadLettersARefusedBatchWholeAndStoresWhatFollows() {
    InjectedDatabaseFailure.install(jdbcClient);
    var refused =
        List.of(
            event("evt_refused_1"),
            event(InjectedDatabaseFailure.FAILING_EVENT_ID),
            event("evt_refused_3"));

    publisher.publish(refused);
    publisher.publish(List.of(event("evt_after_refused")));

    await()
        .untilAsserted(
            () -> {
              assertThat(deadLetteredRequests()).contains(refused);
              assertThat(storedEventIds()).containsExactly("evt_after_refused");
            });
  }

  @Test
  void deadLettersAnUnreadableRecordAndStoresWhatFollows() throws Exception {
    var unreadable = "not an events message".getBytes(StandardCharsets.UTF_8);

    kafkaTemplate.send(EventTopics.EVENTS, unreadable).get();
    publisher.publish(List.of(event("evt_after_unreadable")));

    await()
        .untilAsserted(
            () -> {
              assertThat(TopicRecords.read(kafkaAdmin, EventTopics.DEAD_LETTERS))
                  .anySatisfy(record -> assertThat(record.value()).isEqualTo(unreadable));
              assertThat(storedEventIds()).containsExactly("evt_after_unreadable");
            });
  }

  @Test
  void deadLettersARecordWithoutAValueAndStoresWhatFollows() throws Exception {
    kafkaTemplate.send(EventTopics.EVENTS, null).get();
    publisher.publish(List.of(event("evt_after_empty")));

    await()
        .untilAsserted(
            () -> {
              assertThat(TopicRecords.read(kafkaAdmin, EventTopics.DEAD_LETTERS))
                  .anySatisfy(record -> assertThat(record.value()).isNull());
              assertThat(storedEventIds()).containsExactly("evt_after_empty");
            });
  }

  @Test
  void retriesAFailureThatDoesNotBlameTheEventsInsteadOfDeadLetteringThem() {
    int deadLettersBefore = TopicRecords.read(kafkaAdmin, EventTopics.DEAD_LETTERS).size();
    InjectedDatabaseFailure.install(jdbcClient, "57014");

    publisher.publish(List.of(event(InjectedDatabaseFailure.FAILING_EVENT_ID)));

    await()
        .during(Duration.ofSeconds(3))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(storedEventIds()).isEmpty());
    InjectedDatabaseFailure.remove(jdbcClient);
    await()
        .untilAsserted(
            () ->
                assertThat(storedEventIds())
                    .containsExactly(InjectedDatabaseFailure.FAILING_EVENT_ID));
    assertThat(TopicRecords.read(kafkaAdmin, EventTopics.DEAD_LETTERS)).hasSize(deadLettersBefore);
  }

  private List<String> storedEventIds() {
    return jdbcClient.sql("SELECT event_id FROM events").query(String.class).list();
  }

  private List<List<NewEvent>> deadLetteredRequests() {
    return TopicRecords.read(kafkaAdmin, EventTopics.DEAD_LETTERS).stream()
        .map(record -> record.value())
        .filter(this::isEventsMessage)
        .map(codec::decode)
        .toList();
  }

  private boolean isEventsMessage(byte[] value) {
    try {
      codec.decode(value);
      return true;
    } catch (MalformedMessageException unreadable) {
      return false;
    }
  }

  private static NewEvent event(String eventId) {
    return new NewEvent(
        new TenantName("web"),
        eventId,
        "user_42",
        "page_view",
        Instant.parse("2026-05-24T10:15:30Z"),
        JsonNodeFactory.instance.objectNode());
  }
}
