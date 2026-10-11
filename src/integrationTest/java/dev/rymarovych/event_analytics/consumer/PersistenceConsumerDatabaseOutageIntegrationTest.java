package dev.rymarovych.event_analytics.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rymarovych.event_analytics.TestcontainersConfiguration;
import dev.rymarovych.event_analytics.TopicRecords;
import dev.rymarovych.event_analytics.domain.NewEvent;
import dev.rymarovych.event_analytics.domain.TenantName;
import dev.rymarovych.event_analytics.messaging.EventPublisher;
import dev.rymarovych.event_analytics.messaging.EventTopics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaAdmin;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.node.JsonNodeFactory;

/** Pauses its own Postgres; short timeouts make the outage fail fast instead of hanging. */
@SpringBootTest(
    properties = {
      "spring.datasource.hikari.connection-timeout=1000",
      "spring.datasource.hikari.data-source-properties.socketTimeout=2"
    })
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class PersistenceConsumerDatabaseOutageIntegrationTest {

  @Autowired private EventPublisher publisher;
  @Autowired private PostgreSQLContainer postgres;
  @Autowired private KafkaAdmin kafkaAdmin;
  @Autowired private JdbcClient jdbcClient;

  @Test
  void storesWhatArrivedDuringAnOutageOnceTheDatabaseIsBack(CapturedOutput output) {
    int deadLettersBefore = TopicRecords.read(kafkaAdmin, EventTopics.DEAD_LETTERS).size();
    var docker = DockerClientFactory.instance().client();

    docker.pauseContainerCmd(postgres.getContainerId()).exec();
    try {
      publisher.publish(List.of(event("evt_during_outage")));
      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(() -> assertThat(output.getAll()).contains("Retrying from events.raw"));
    } finally {
      docker.unpauseContainerCmd(postgres.getContainerId()).exec();
    }

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(jdbcClient.sql("SELECT event_id FROM events").query(String.class).list())
                    .containsExactly("evt_during_outage"));
    assertThat(TopicRecords.read(kafkaAdmin, EventTopics.DEAD_LETTERS)).hasSize(deadLettersBefore);
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
