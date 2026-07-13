package dev.rymarovych.event_analytics.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.rymarovych.event_analytics.TestcontainersConfiguration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end tests for the event-count read endpoint, driving the real controller → service →
 * repository → Postgres path with committed rows (no {@code @Transactional}), so the SQL
 * aggregation runs against the database production uses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EventCountStatsIntegrationTest {

  private static final String EVENT_COUNTS = "/api/v1/stats/event-counts";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcClient jdbcClient;

  @AfterEach
  void cleanUp() {
    jdbcClient.sql("DELETE FROM events").update();
  }

  @Test
  void groupsByTypeOrderedByCountDescending() throws Exception {
    seedFourEventsOnThe24th();

    mockMvc
        .perform(
            get(EVENT_COUNTS)
                .param("from", "2026-05-24T00:00:00Z")
                .param("to", "2026-05-25T00:00:00Z")
                .param("groupBy", "type"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.group_by").value("type"))
        .andExpect(jsonPath("$.timezone").value("UTC"))
        .andExpect(jsonPath("$.buckets.length()").value(2))
        .andExpect(jsonPath("$.buckets[0].bucket").value("page_view"))
        .andExpect(jsonPath("$.buckets[0].count").value(3))
        .andExpect(jsonPath("$.buckets[1].bucket").value("purchase"))
        .andExpect(jsonPath("$.buckets[1].count").value(1));
  }

  @Test
  void groupsByHour() throws Exception {
    seedFourEventsOnThe24th();

    mockMvc
        .perform(
            get(EVENT_COUNTS)
                .param("from", "2026-05-24T00:00:00Z")
                .param("to", "2026-05-25T00:00:00Z")
                .param("groupBy", "hour"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.buckets.length()").value(2))
        .andExpect(jsonPath("$.buckets[0].bucket").value("2026-05-24T10:00:00Z"))
        .andExpect(jsonPath("$.buckets[0].count").value(3))
        .andExpect(jsonPath("$.buckets[1].bucket").value("2026-05-24T11:00:00Z"))
        .andExpect(jsonPath("$.buckets[1].count").value(1));
  }

  @Test
  void groupsByDay() throws Exception {
    seedFourEventsOnThe24th();

    mockMvc
        .perform(
            get(EVENT_COUNTS)
                .param("from", "2026-05-24T00:00:00Z")
                .param("to", "2026-05-25T00:00:00Z")
                .param("groupBy", "day"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.buckets.length()").value(1))
        .andExpect(jsonPath("$.buckets[0].bucket").value("2026-05-24T00:00:00Z"))
        .andExpect(jsonPath("$.buckets[0].count").value(4));
  }

  @Test
  void defaultsToTypeGrouping() throws Exception {
    seedFourEventsOnThe24th();

    mockMvc
        .perform(
            get(EVENT_COUNTS)
                .param("from", "2026-05-24T00:00:00Z")
                .param("to", "2026-05-25T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.group_by").value("type"));
  }

  @Test
  void appliesHalfOpenInterval() throws Exception {
    seedFourEventsOnThe24th();

    mockMvc
        .perform(
            get(EVENT_COUNTS)
                .param("from", "2026-05-24T10:15:00Z")
                .param("to", "2026-05-24T10:50:00Z")
                .param("groupBy", "type"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.buckets.length()").value(1))
        .andExpect(jsonPath("$.buckets[0].bucket").value("page_view"))
        .andExpect(jsonPath("$.buckets[0].count").value(2));
  }

  @Test
  void rejectsUnknownGrouping() throws Exception {
    mockMvc
        .perform(
            get(EVENT_COUNTS)
                .param("from", "2026-05-24T00:00:00Z")
                .param("to", "2026-05-25T00:00:00Z")
                .param("groupBy", "weekly"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsMissingWindowBound() throws Exception {
    mockMvc
        .perform(get(EVENT_COUNTS).param("to", "2026-05-25T00:00:00Z"))
        .andExpect(status().isBadRequest());
  }

  private void seedFourEventsOnThe24th() {
    insert("evt_1", "page_view", Instant.parse("2026-05-24T10:15:00Z"));
    insert("evt_2", "page_view", Instant.parse("2026-05-24T10:40:00Z"));
    insert("evt_3", "purchase", Instant.parse("2026-05-24T10:50:00Z"));
    insert("evt_4", "page_view", Instant.parse("2026-05-24T11:05:00Z"));
  }

  private void insert(String eventId, String eventType, Instant occurredAt) {
    jdbcClient
        .sql(
            """
            INSERT INTO events (event_id, source, user_id, event_type, occurred_at, properties)
            VALUES (:id, 'web', 'user_1', :type, :at, '{}'::JSONB)
            """)
        .param("id", eventId)
        .param("type", eventType)
        .param("at", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
        .update();
  }
}
