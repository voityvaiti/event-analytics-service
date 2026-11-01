package dev.rymarovych.event_analytics.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rymarovych.event_analytics.PersistenceBacklog;
import dev.rymarovych.event_analytics.TestcontainersConfiguration;
import dev.rymarovych.event_analytics.domain.NewEvent;
import dev.rymarovych.event_analytics.domain.TenantName;
import dev.rymarovych.event_analytics.messaging.EventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaAdmin;
import tools.jackson.databind.node.JsonNodeFactory;

/** The lag the stop-and-restart exercise reads while the consumer is down, and as it drains. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PersistenceConsumerLagIntegrationTest {

  @Autowired private PersistenceConsumer consumer;
  @Autowired private EventPublisher publisher;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private KafkaAdmin kafkaAdmin;
  @Autowired private JdbcClient jdbcClient;

  @BeforeEach
  void startFromAnEmptyBacklog() {
    PersistenceBacklog.awaitEmpty(kafkaAdmin);
  }

  @AfterEach
  void restart() {
    consumer.start();
    PersistenceBacklog.awaitEmpty(kafkaAdmin);
    jdbcClient.sql("DELETE FROM events").update();
  }

  @Test
  void countsRecordsWhileTheConsumerIsStoppedAndDrainsToZero() {
    consumer.stop();
    await().until(() -> consumer.status() == PersistenceConsumerStatus.STOPPED);

    publisher.publish(List.of(event("evt_lag_1")));
    publisher.publish(List.of(event("evt_lag_2"), event("evt_lag_3")));

    await().untilAsserted(() -> assertThat(lag()).isEqualTo(2.0));

    consumer.start();

    await().untilAsserted(() -> assertThat(lag()).isZero());
  }

  private double lag() {
    return meterRegistry.get("persistence.consumer.lag").gauge().value();
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
