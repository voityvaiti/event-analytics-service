package dev.rymarovych.event_analytics.web;

import static dev.rymarovych.event_analytics.DevKeyTokens.bearerTokenFor;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end tests for the active-users read endpoint, driving the real controller → service →
 * repository → Postgres path with committed rows (no {@code @Transactional}), so the SQL
 * aggregation runs against the database production uses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ActiveUsersStatsIntegrationTest {

  private static final String ACTIVE_USERS = "/api/v1/stats/active-users";

  private static final String TENANT = "web";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcClient jdbcClient;

  @AfterEach
  void cleanUp() {
    jdbcClient.sql("DELETE FROM events").update();
  }

  @Test
  void countsEachUserOncePerDayBucket() throws Exception {
    seedThreeUsersOverTwoDays();

    mockMvc
        .perform(
            activeUsers()
                .param("from", "2026-05-24T00:00:00Z")
                .param("to", "2026-05-26T00:00:00Z")
                .param("groupBy", "day"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.group_by").value("day"))
        .andExpect(jsonPath("$.timezone").value("UTC"))
        .andExpect(jsonPath("$.buckets.length()").value(2))
        .andExpect(jsonPath("$.buckets[0].bucket").value("2026-05-24T00:00:00Z"))
        .andExpect(jsonPath("$.buckets[0].active_users").value(2))
        .andExpect(jsonPath("$.buckets[1].bucket").value("2026-05-25T00:00:00Z"))
        .andExpect(jsonPath("$.buckets[1].active_users").value(2));
  }

  @Test
  void groupsByHour() throws Exception {
    seedThreeUsersOverTwoDays();

    mockMvc
        .perform(
            activeUsers()
                .param("from", "2026-05-24T00:00:00Z")
                .param("to", "2026-05-25T00:00:00Z")
                .param("groupBy", "hour"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.buckets.length()").value(2))
        .andExpect(jsonPath("$.buckets[0].bucket").value("2026-05-24T10:00:00Z"))
        .andExpect(jsonPath("$.buckets[0].active_users").value(2))
        .andExpect(jsonPath("$.buckets[1].bucket").value("2026-05-24T11:00:00Z"))
        .andExpect(jsonPath("$.buckets[1].active_users").value(1));
  }

  @Test
  void defaultsToDayGrouping() throws Exception {
    seedThreeUsersOverTwoDays();

    mockMvc
        .perform(
            activeUsers().param("from", "2026-05-24T00:00:00Z").param("to", "2026-05-26T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.group_by").value("day"));
  }

  @Test
  void rejectsNonTimeGrouping() throws Exception {
    mockMvc
        .perform(
            activeUsers()
                .param("from", "2026-05-24T00:00:00Z")
                .param("to", "2026-05-26T00:00:00Z")
                .param("groupBy", "type"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsMissingWindowBound() throws Exception {
    mockMvc
        .perform(activeUsers().param("to", "2026-05-26T00:00:00Z"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsInvertedWindow() throws Exception {
    mockMvc
        .perform(
            activeUsers().param("from", "2026-05-26T00:00:00Z").param("to", "2026-05-24T00:00:00Z"))
        .andExpect(status().isBadRequest());
  }

  private static MockHttpServletRequestBuilder activeUsers() {
    return get(ACTIVE_USERS).with(bearerTokenFor(TENANT));
  }

  /**
   * A distinct-user count is where a leak would be easiest to miss: another tenant's user inflates
   * the number without adding a bucket, so nothing about the shape of the answer looks wrong.
   */
  @Test
  void countsOnlyTheAuthenticatedTenantsUsers() throws Exception {
    seedThreeUsersOverTwoDays();
    insertForTenant("other", "evt_other_1", "user_99", Instant.parse("2026-05-24T10:30:00Z"));

    mockMvc
        .perform(
            activeUsers()
                .param("from", "2026-05-24T00:00:00Z")
                .param("to", "2026-05-26T00:00:00Z")
                .param("groupBy", "day"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.buckets.length()").value(2))
        .andExpect(jsonPath("$.buckets[0].active_users").value(2))
        .andExpect(jsonPath("$.buckets[1].active_users").value(2));
  }

  private void seedThreeUsersOverTwoDays() {
    insert("evt_1", "user_1", Instant.parse("2026-05-24T10:15:00Z"));
    insert("evt_2", "user_1", Instant.parse("2026-05-24T11:05:00Z"));
    insert("evt_3", "user_2", Instant.parse("2026-05-24T10:40:00Z"));
    insert("evt_4", "user_1", Instant.parse("2026-05-25T09:00:00Z"));
    insert("evt_5", "user_3", Instant.parse("2026-05-25T12:00:00Z"));
  }

  private void insert(String eventId, String userId, Instant occurredAt) {
    insertForTenant(TENANT, eventId, userId, occurredAt);
  }

  private void insertForTenant(String source, String eventId, String userId, Instant occurredAt) {
    jdbcClient
        .sql(
            """
            INSERT INTO events (event_id, tenant_name, user_id, event_type, occurred_at, properties)
            VALUES (:id, :tenantName, :user, 'page_view', :at, '{}'::JSONB)
            """)
        .param("id", eventId)
        .param("tenantName", source)
        .param("user", userId)
        .param("at", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
        .update();
  }
}
