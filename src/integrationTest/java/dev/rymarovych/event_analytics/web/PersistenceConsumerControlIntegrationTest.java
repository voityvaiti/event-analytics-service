package dev.rymarovych.event_analytics.web;

import static dev.rymarovych.event_analytics.DevKeyTokens.bearerTokenFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.rymarovych.event_analytics.TestcontainersConfiguration;
import dev.rymarovych.event_analytics.domain.NewEvent;
import dev.rymarovych.event_analytics.domain.TenantName;
import dev.rymarovych.event_analytics.messaging.EventPublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.node.JsonNodeFactory;

/** The stop-and-restart exercise in miniature, with the service endpoints switched on. */
@SpringBootTest(properties = "app.persistence-consumer.control.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PersistenceConsumerControlIntegrationTest {

  private static final String CONTROL = "/internal/persistence-consumer";
  private static final RequestPostProcessor CONTROL_TOKEN =
      bearerTokenFor("perf", "persistence:control");

  @Autowired private MockMvc mockMvc;
  @Autowired private EventPublisher publisher;
  @Autowired private JdbcClient jdbcClient;

  @AfterEach
  void restart() throws Exception {
    mockMvc.perform(post(CONTROL + "/start").with(CONTROL_TOKEN));
  }

  @Test
  void aTenantTokenCannotReachTheServiceEndpoints() throws Exception {
    mockMvc.perform(get(CONTROL).with(bearerTokenFor("perf"))).andExpect(status().isForbidden());
    mockMvc
        .perform(post(CONTROL + "/stop").with(bearerTokenFor("perf")))
        .andExpect(status().isForbidden());
  }

  @Test
  void holdsEventsWhileStoppedAndStoresThemOnceStarted() throws Exception {
    mockMvc
        .perform(get(CONTROL).with(CONTROL_TOKEN))
        .andExpect(jsonPath("$.status").value("RUNNING"));

    mockMvc.perform(post(CONTROL + "/stop").with(CONTROL_TOKEN)).andExpect(status().isOk());
    await()
        .untilAsserted(
            () ->
                mockMvc
                    .perform(get(CONTROL).with(CONTROL_TOKEN))
                    .andExpect(jsonPath("$.status").value("STOPPED")));
    publisher.publish(List.of(event("evt_while_stopped")));
    await()
        .during(Duration.ofSeconds(2))
        .atMost(Duration.ofSeconds(4))
        .untilAsserted(() -> assertThat(storedEventIds()).isEmpty());

    mockMvc
        .perform(post(CONTROL + "/start").with(CONTROL_TOKEN))
        .andExpect(jsonPath("$.status").value("RUNNING"));
    await().untilAsserted(() -> assertThat(storedEventIds()).containsExactly("evt_while_stopped"));
  }

  private List<String> storedEventIds() {
    return jdbcClient.sql("SELECT event_id FROM events").query(String.class).list();
  }

  private static NewEvent event(String eventId) {
    return new NewEvent(
        new TenantName("perf"),
        eventId,
        "user_42",
        "page_view",
        Instant.parse("2026-05-24T10:15:30Z"),
        JsonNodeFactory.instance.objectNode());
  }
}
