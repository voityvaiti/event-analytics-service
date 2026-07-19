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
 * End-to-end tests for the top-pages read endpoint, driving the real controller → service →
 * repository → Postgres path with committed rows (no {@code @Transactional}), so the SQL
 * aggregation runs against the database production uses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TopPagesStatsIntegrationTest {

  private static final String TOP_PAGES = "/api/v1/stats/top-pages";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcClient jdbcClient;

  @AfterEach
  void cleanUp() {
    jdbcClient.sql("DELETE FROM events").update();
  }

  @Test
  void ranksPagesByCountThenUrl() throws Exception {
    seedFourPagesOnThe24th();

    mockMvc
        .perform(
            get(TOP_PAGES)
                .param("from", "2026-05-24T00:00:00Z")
                .param("to", "2026-05-25T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limit").value(10))
        .andExpect(jsonPath("$.has_more").value(false))
        .andExpect(jsonPath("$.pages.length()").value(4))
        .andExpect(jsonPath("$.pages[0].page_url").value("/home"))
        .andExpect(jsonPath("$.pages[0].count").value(3))
        .andExpect(jsonPath("$.pages[1].page_url").value("/pricing"))
        .andExpect(jsonPath("$.pages[1].count").value(2))
        .andExpect(jsonPath("$.pages[2].page_url").value("/about"))
        .andExpect(jsonPath("$.pages[2].count").value(1))
        .andExpect(jsonPath("$.pages[3].page_url").value("/contact"))
        .andExpect(jsonPath("$.pages[3].count").value(1));
  }

  @Test
  void truncatesAtLimitAndSignalsMore() throws Exception {
    seedFourPagesOnThe24th();

    mockMvc
        .perform(
            get(TOP_PAGES)
                .param("from", "2026-05-24T00:00:00Z")
                .param("to", "2026-05-25T00:00:00Z")
                .param("limit", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limit").value(2))
        .andExpect(jsonPath("$.has_more").value(true))
        .andExpect(jsonPath("$.pages.length()").value(2))
        .andExpect(jsonPath("$.pages[0].page_url").value("/home"))
        .andExpect(jsonPath("$.pages[1].page_url").value("/pricing"));
  }

  @Test
  void rejectsNonPositiveLimit() throws Exception {
    mockMvc
        .perform(
            get(TOP_PAGES)
                .param("from", "2026-05-24T00:00:00Z")
                .param("to", "2026-05-25T00:00:00Z")
                .param("limit", "0"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsLimitAboveMaximum() throws Exception {
    mockMvc
        .perform(
            get(TOP_PAGES)
                .param("from", "2026-05-24T00:00:00Z")
                .param("to", "2026-05-25T00:00:00Z")
                .param("limit", "101"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsMissingWindowBound() throws Exception {
    mockMvc
        .perform(get(TOP_PAGES).param("to", "2026-05-25T00:00:00Z"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsInvertedWindow() throws Exception {
    mockMvc
        .perform(
            get(TOP_PAGES)
                .param("from", "2026-05-25T00:00:00Z")
                .param("to", "2026-05-24T00:00:00Z"))
        .andExpect(status().isBadRequest());
  }

  /**
   * Four ranked pages inside the window, plus two events that must not surface: one without a
   * {@code page_url} property and one for {@code /home} the day before the window.
   */
  private void seedFourPagesOnThe24th() {
    insertPageView("evt_1", "/home", Instant.parse("2026-05-24T10:00:00Z"));
    insertPageView("evt_2", "/home", Instant.parse("2026-05-24T11:00:00Z"));
    insertPageView("evt_3", "/home", Instant.parse("2026-05-24T12:00:00Z"));
    insertPageView("evt_4", "/pricing", Instant.parse("2026-05-24T10:30:00Z"));
    insertPageView("evt_5", "/pricing", Instant.parse("2026-05-24T13:00:00Z"));
    insertPageView("evt_6", "/about", Instant.parse("2026-05-24T14:00:00Z"));
    insertPageView("evt_7", "/contact", Instant.parse("2026-05-24T15:00:00Z"));
    insert("evt_8", "{}", Instant.parse("2026-05-24T16:00:00Z"));
    insertPageView("evt_9", "/home", Instant.parse("2026-05-23T10:00:00Z"));
  }

  private void insertPageView(String eventId, String pageUrl, Instant occurredAt) {
    insert(eventId, "{\"page_url\": \"" + pageUrl + "\"}", occurredAt);
  }

  private void insert(String eventId, String propertiesJson, Instant occurredAt) {
    jdbcClient
        .sql(
            """
            INSERT INTO events (event_id, source, user_id, event_type, occurred_at, properties)
            VALUES (:id, 'web', 'user_1', 'page_view', :at, CAST(:props AS JSONB))
            """)
        .param("id", eventId)
        .param("at", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
        .param("props", propertiesJson)
        .update();
  }
}
