package dev.rymarovych.event_analytics.web;

import static dev.rymarovych.event_analytics.DevKeyTokens.bearerTokenFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.rymarovych.event_analytics.TestcontainersConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

/** Pauses its own broker; under a one-second budget both outage shapes answer 503 promptly. */
@SpringBootTest(
    properties = {
      "app.ingestion.publication-timeout=1s",
      "spring.kafka.producer.properties.max.block.ms=1000",
      "spring.kafka.producer.properties.delivery.timeout.ms=1000",
      "spring.kafka.producer.properties.request.timeout.ms=900"
    })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BrokerOutageIntegrationTest {

  private static final Duration WITHIN_BUDGET = Duration.ofSeconds(3);

  @Autowired private MockMvc mockMvc;
  @Autowired private KafkaContainer kafka;
  @Autowired private ProducerFactory<?, ?> producerFactory;

  @Test
  void answers503WhenTheBrokerIsDownBeforeTheFirstSend() throws Exception {
    producerFactory.reset();

    assertUnconfirmedWithinBudget("evt_broker_down_from_start");
  }

  @Test
  void answers503WhenTheBrokerGoesDownAfterMetadataIsCached() throws Exception {
    postEvent("evt_before_outage").andExpect(status().isAccepted());

    assertUnconfirmedWithinBudget("evt_broker_lost");
  }

  private void assertUnconfirmedWithinBudget(String eventId) throws Exception {
    var docker = DockerClientFactory.instance().client();
    docker.pauseContainerCmd(kafka.getContainerId()).exec();
    try {
      long start = System.nanoTime();
      postEvent(eventId)
          .andExpect(status().isServiceUnavailable())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(WITHIN_BUDGET);
    } finally {
      docker.unpauseContainerCmd(kafka.getContainerId()).exec();
    }
  }

  private ResultActions postEvent(String eventId) throws Exception {
    return mockMvc.perform(
        post("/api/v1/events")
            .with(bearerTokenFor("web"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "event_id": "%s",
                  "user_id": "user_42",
                  "event_type": "page_view",
                  "timestamp": "2026-05-24T10:15:30Z"
                }
                """
                    .formatted(eventId)));
  }
}
