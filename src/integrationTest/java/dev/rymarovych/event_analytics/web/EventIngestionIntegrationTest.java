package dev.rymarovych.event_analytics.web;

import static dev.rymarovych.event_analytics.DevKeyTokens.bearerTokenFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.rymarovych.event_analytics.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end ingestion tests driving the real controller → service → repository → Postgres path.
 *
 * <p>Deliberately NOT {@code @Transactional}: every request commits, so tests exercise real
 * persistence semantics (including idempotency across separate committed transactions). Isolation
 * is provided by truncating the table after each test rather than by rollback.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EventIngestionIntegrationTest {

  private static final String EVENT_JSON =
      """
      {
        "event_id": "evt_abc123",
        "user_id": "user_42",
        "event_type": "page_view",
        "timestamp": "2026-05-24T10:15:30Z",
        "properties": {"page_url": "/products/laptop-x1", "device": "mobile"}
      }
      """;

  private static final String TENANT = "web";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcClient jdbcClient;

  @AfterEach
  void cleanUp() {
    jdbcClient.sql("DELETE FROM events").update();
  }

  @Test
  void acceptsValidEventAndPersistsOneRow() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/events")
                .with(bearerTokenFor(TENANT))
                .contentType(MediaType.APPLICATION_JSON)
                .content(EVENT_JSON))
        .andExpect(status().isAccepted());

    assertThat(countByEventId("evt_abc123")).isEqualTo(1L);
  }

  @Test
  void retriedEventIsDeduplicatedAgainstACommittedRow() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/events")
                .with(bearerTokenFor(TENANT))
                .contentType(MediaType.APPLICATION_JSON)
                .content(EVENT_JSON))
        .andExpect(status().isAccepted());
    mockMvc
        .perform(
            post("/api/v1/events")
                .with(bearerTokenFor(TENANT))
                .contentType(MediaType.APPLICATION_JSON)
                .content(EVENT_JSON))
        .andExpect(status().isAccepted());

    assertThat(countByEventId("evt_abc123")).isEqualTo(1L);
  }

  /** The row's tenant is the token's, which is the whole point of taking it off the request. */
  @Test
  void writesTheEventUnderTheAuthenticatedTenant() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/events")
                .with(bearerTokenFor("acme"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(EVENT_JSON))
        .andExpect(status().isAccepted());

    assertThat(tenantNameOf("evt_abc123")).isEqualTo("acme");
  }

  /**
   * A tenant named in the body is ignored rather than honoured or rejected: honouring it would let
   * any client write as any tenant, and rejecting it would break callers over a field that no
   * longer carries any authority.
   */
  @Test
  void aTenantNamedInTheBodyIsIgnored() throws Exception {
    var claimingAnotherTenant =
        """
        {
          "tenant_name": "somebody-else",
          "event_id": "evt_abc123",
          "user_id": "user_42",
          "event_type": "page_view",
          "timestamp": "2026-05-24T10:15:30Z"
        }
        """;

    mockMvc
        .perform(
            post("/api/v1/events")
                .with(bearerTokenFor("acme"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(claimingAnotherTenant))
        .andExpect(status().isAccepted());

    assertThat(tenantNameOf("evt_abc123")).isEqualTo("acme");
  }

  @Test
  void rejectsRequestMissingRequiredField() throws Exception {
    var missingEventId =
        """
        {
          "user_id": "user_42",
          "event_type": "page_view",
          "timestamp": "2026-05-24T10:15:30Z"
        }
        """;

    mockMvc
        .perform(
            post("/api/v1/events")
                .with(bearerTokenFor(TENANT))
                .contentType(MediaType.APPLICATION_JSON)
                .content(missingEventId))
        .andExpect(status().isBadRequest());
  }

  private String tenantNameOf(String eventId) {
    return jdbcClient
        .sql("SELECT tenant_name FROM events WHERE event_id = :id")
        .param("id", eventId)
        .query(String.class)
        .single();
  }

  private long countByEventId(String eventId) {
    return jdbcClient
        .sql("SELECT COUNT(*) FROM events WHERE event_id = :id")
        .param("id", eventId)
        .query(Long.class)
        .single();
  }
}
