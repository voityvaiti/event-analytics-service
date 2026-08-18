package dev.rymarovych.event_analytics.web;

import static dev.rymarovych.event_analytics.DevKeyTokens.bearerTokenFor;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
 * End-to-end tests that time buckets follow the tenant's own calendar, driving the real controller
 * → service → repository → Postgres path with committed rows (no {@code @Transactional}), so {@code
 * date_trunc}'s zone argument is exercised by the database production uses.
 *
 * <p>Every test seeds the same two events and asks over the same window. The only thing that
 * differs is a row in {@code tenants}, so a difference in the figures can have come from nothing
 * else.
 *
 * <p>The events straddle Tokyo's day boundary and not UTC's: Tokyo is UTC+9 with no DST, so its day
 * begins at 15:00Z the day before. 10:00Z and 16:00Z are therefore one UTC day and two Tokyo days.
 * A Tokyo bucket consequently starts earlier than {@code from} — which is correct, because the
 * window selects the events that are counted, not the boundaries they are counted into.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TenantBucketingZoneIntegrationTest {

  private static final String EVENT_COUNTS = "/api/v1/stats/event-counts";

  private static final String ACTIVE_USERS = "/api/v1/stats/active-users";

  private static final String TENANT_IN_TOKYO = "tenant-in-tokyo";

  private static final String TENANT_WITHOUT_SETTINGS = "tenant-without-settings";

  private static final String TENANT_WITH_UNREADABLE_ZONE = "tenant-with-unreadable-zone";

  private static final String FROM = "2026-05-24T00:00:00Z";

  private static final String TO = "2026-05-25T00:00:00Z";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcClient jdbcClient;

  @AfterEach
  void cleanUp() {
    jdbcClient.sql("DELETE FROM events").update();
    jdbcClient.sql("DELETE FROM tenants").update();
  }

  @Test
  void bucketsEventCountsAtTheTenantsOwnDayBoundary() throws Exception {
    seedTwoEventsStraddlingTokyosDayBoundary(TENANT_IN_TOKYO);
    storeZone(TENANT_IN_TOKYO, "Asia/Tokyo");

    mockMvc
        .perform(dailyEventCounts(TENANT_IN_TOKYO))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timezone").value("Asia/Tokyo"))
        .andExpect(jsonPath("$.buckets.length()").value(2))
        .andExpect(jsonPath("$.buckets[0].bucket").value("2026-05-23T15:00:00Z"))
        .andExpect(jsonPath("$.buckets[0].count").value(1))
        .andExpect(jsonPath("$.buckets[1].bucket").value("2026-05-24T15:00:00Z"))
        .andExpect(jsonPath("$.buckets[1].count").value(1));
  }

  /** Same events, same window, one settings row fewer — and one bucket instead of two. */
  @Test
  void bucketsInUtcWhenTheTenantHasNoSettingsRow() throws Exception {
    seedTwoEventsStraddlingTokyosDayBoundary(TENANT_WITHOUT_SETTINGS);

    mockMvc
        .perform(dailyEventCounts(TENANT_WITHOUT_SETTINGS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timezone").value("UTC"))
        .andExpect(jsonPath("$.buckets.length()").value(1))
        .andExpect(jsonPath("$.buckets[0].bucket").value("2026-05-24T00:00:00Z"))
        .andExpect(jsonPath("$.buckets[0].count").value(2));
  }

  @Test
  void bucketsActiveUsersAtTheTenantsOwnDayBoundary() throws Exception {
    seedTwoEventsStraddlingTokyosDayBoundary(TENANT_IN_TOKYO);
    storeZone(TENANT_IN_TOKYO, "Asia/Tokyo");

    mockMvc
        .perform(dailyActiveUsers(TENANT_IN_TOKYO))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timezone").value("Asia/Tokyo"))
        .andExpect(jsonPath("$.buckets.length()").value(2))
        .andExpect(jsonPath("$.buckets[0].active_users").value(1))
        .andExpect(jsonPath("$.buckets[1].active_users").value(1));
  }

  @Test
  void bucketsActiveUsersInUtcWhenTheTenantHasNoSettingsRow() throws Exception {
    seedTwoEventsStraddlingTokyosDayBoundary(TENANT_WITHOUT_SETTINGS);

    mockMvc
        .perform(dailyActiveUsers(TENANT_WITHOUT_SETTINGS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timezone").value("UTC"))
        .andExpect(jsonPath("$.buckets.length()").value(1))
        .andExpect(jsonPath("$.buckets[0].active_users").value(2));
  }

  @Test
  void statesNoZoneWhenTheGroupingHasNoTimeBuckets() throws Exception {
    seedTwoEventsStraddlingTokyosDayBoundary(TENANT_IN_TOKYO);
    storeZone(TENANT_IN_TOKYO, "Asia/Tokyo");

    mockMvc
        .perform(eventCounts(TENANT_IN_TOKYO).param("groupBy", "type"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timezone").doesNotExist())
        .andExpect(jsonPath("$.buckets.length()").value(1))
        .andExpect(jsonPath("$.buckets[0].count").value(2));
  }

  /**
   * A row that arrived some way other than {@code scripts/actions/set-tenant-zone}, whose upsert
   * takes the name from {@code pg_timezone_names} and so could never have stored this. Bucketing in
   * UTC instead would be the dangerous answer: plausible figures for a tenant whose settings say
   * they should have been computed somewhere else.
   */
  @Test
  void refusesToBucketWhenTheStoredZoneCannotBeRead() throws Exception {
    seedTwoEventsStraddlingTokyosDayBoundary(TENANT_WITH_UNREADABLE_ZONE);
    storeZone(TENANT_WITH_UNREADABLE_ZONE, "Mars/Olympus_Mons");

    mockMvc
        .perform(dailyEventCounts(TENANT_WITH_UNREADABLE_ZONE))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(jsonPath("$.instance").value(EVENT_COUNTS));
  }

  /**
   * The zone stored above fails every shape that reads it, so answering this one is the assertion
   * that grouping by type reads no settings at all rather than reading them and ignoring the
   * result.
   */
  @Test
  void readsNoSettingsWhenGroupingByType() throws Exception {
    seedTwoEventsStraddlingTokyosDayBoundary(TENANT_WITH_UNREADABLE_ZONE);
    storeZone(TENANT_WITH_UNREADABLE_ZONE, "Mars/Olympus_Mons");

    mockMvc
        .perform(eventCounts(TENANT_WITH_UNREADABLE_ZONE).param("groupBy", "type"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.buckets[0].count").value(2));
  }

  private static MockHttpServletRequestBuilder eventCounts(String tenant) {
    return get(EVENT_COUNTS).with(bearerTokenFor(tenant)).param("from", FROM).param("to", TO);
  }

  private static MockHttpServletRequestBuilder dailyEventCounts(String tenant) {
    return eventCounts(tenant).param("groupBy", "day");
  }

  private static MockHttpServletRequestBuilder dailyActiveUsers(String tenant) {
    return get(ACTIVE_USERS)
        .with(bearerTokenFor(tenant))
        .param("from", FROM)
        .param("to", TO)
        .param("groupBy", "day");
  }

  private void seedTwoEventsStraddlingTokyosDayBoundary(String source) {
    insert(source, "evt_tokyo_24th", "user_1", Instant.parse("2026-05-24T10:00:00Z"));
    insert(source, "evt_tokyo_25th", "user_2", Instant.parse("2026-05-24T16:00:00Z"));
  }

  private void insert(String source, String eventId, String userId, Instant occurredAt) {
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

  private void storeZone(String tenant, String timezone) {
    jdbcClient
        .sql("INSERT INTO tenants (name, timezone) VALUES (:name, :timezone)")
        .param("name", tenant)
        .param("timezone", timezone)
        .update();
  }
}
