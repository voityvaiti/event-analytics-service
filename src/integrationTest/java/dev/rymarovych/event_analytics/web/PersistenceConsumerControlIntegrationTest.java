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
import io.micrometer.core.instrument.MeterRegistry;
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

/** The stop-and-restart exercise and a redrive in miniature, with the service endpoints on. */
@SpringBootTest(properties = "app.persistence-consumer.control.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PersistenceConsumerControlIntegrationTest {

  private static final String CONTROL = "/internal/persistence-consumer";
  private static final String REDRIVE = CONTROL + "/dead-letters/redrive";
  private static final RequestPostProcessor CONTROL_TOKEN =
      bearerTokenFor("perf", "persistence:control");

  @Autowired private MockMvc mockMvc;
  @Autowired private EventPublisher publisher;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private MeterRegistry meterRegistry;

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
    mockMvc.perform(post(REDRIVE).with(bearerTokenFor("perf"))).andExpect(status().isForbidden());
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

  @Test
  void redrivesARecordRefusedUntilItsCauseIsFixed() throws Exception {
    double deadLettersBefore = deadLetters();
    jdbcClient
        .sql(
            "ALTER TABLE events ADD CONSTRAINT refuse_blocked CHECK (event_type <> 'blocked')"
                + " NOT VALID")
        .update();
    try {
      publisher.publish(List.of(event("evt_refused", "blocked")));
      await().untilAsserted(() -> assertThat(deadLetters()).isEqualTo(deadLettersBefore + 1));
    } finally {
      jdbcClient.sql("ALTER TABLE events DROP CONSTRAINT refuse_blocked").update();
    }

    try {
      mockMvc
          .perform(post(REDRIVE).with(CONTROL_TOKEN))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.redriven_records").value(1));
      await().untilAsserted(() -> assertThat(storedEventIds()).contains("evt_refused"));
      mockMvc
          .perform(post(REDRIVE).with(CONTROL_TOKEN))
          .andExpect(jsonPath("$.redriven_records").value(0));
    } finally {
      jdbcClient.sql("DELETE FROM events WHERE event_id = 'evt_refused'").update();
    }
  }

  private double deadLetters() {
    return meterRegistry.get("persistence.consumer.dead.letters").counter().count();
  }

  private List<String> storedEventIds() {
    return jdbcClient.sql("SELECT event_id FROM events").query(String.class).list();
  }

  private static NewEvent event(String eventId) {
    return event(eventId, "page_view");
  }

  private static NewEvent event(String eventId, String eventType) {
    return new NewEvent(
        new TenantName("perf"),
        eventId,
        "user_42",
        eventType,
        Instant.parse("2026-05-24T10:15:30Z"),
        JsonNodeFactory.instance.objectNode());
  }
}
