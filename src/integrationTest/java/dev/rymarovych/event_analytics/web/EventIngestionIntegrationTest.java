package dev.rymarovych.event_analytics.web;

import static dev.rymarovych.event_analytics.DevKeyTokens.bearerTokenFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.rymarovych.event_analytics.PersistenceBacklog;
import dev.rymarovych.event_analytics.TestcontainersConfiguration;
import dev.rymarovych.event_analytics.domain.TenantName;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
  @Autowired private KafkaAdmin kafkaAdmin;

  @AfterEach
  void cleanUp() {
    PersistenceBacklog.awaitEmpty(kafkaAdmin);
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
    PersistenceBacklog.awaitEmpty(kafkaAdmin);

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
    PersistenceBacklog.awaitEmpty(kafkaAdmin);

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
    PersistenceBacklog.awaitEmpty(kafkaAdmin);

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
    PersistenceBacklog.awaitEmpty(kafkaAdmin);

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

  /** Random three-byte characters exercise index size limits without relying on compression. */
  @Test
  void storesTheWidestValuesValidationAccepts() throws Exception {
    var eventId = randomThreeByteText(EventRequest.MAX_EVENT_ID_LENGTH);
    var widest =
        """
        {
          "event_id": "%s",
          "user_id": "%s",
          "event_type": "%s",
          "timestamp": "9999-12-31T23:59:59.999999999Z"
        }
        """
            .formatted(
                eventId,
                randomThreeByteText(EventRequest.MAX_USER_ID_LENGTH),
                randomThreeByteText(EventRequest.MAX_EVENT_TYPE_LENGTH));

    mockMvc
        .perform(
            post("/api/v1/events")
                .with(bearerTokenFor(randomThreeByteText(TenantName.MAX_LENGTH)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(widest))
        .andExpect(status().isAccepted());
    PersistenceBacklog.awaitEmpty(kafkaAdmin);

    assertThat(countByEventId(eventId)).isEqualTo(1L);
  }

  private static String randomThreeByteText(int length) {
    return new Random(length)
        .ints(length, 0x4E00, 0xA000)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
  }

  /**
   * Pins the HTTP mapper's decimal configuration against the value actually stored in PostgreSQL.
   */
  @Test
  void storesNumbersExactlyUpToTheirDigitLimits() throws Exception {
    var precise =
        """
        {
          "event_id": "evt_precise",
          "user_id": "user_42",
          "event_type": "purchase",
          "timestamp": "2026-05-24T10:15:30Z",
          "properties": {"amount": 0.1234567890123456789, "largest": 1E+999}
        }
        """;

    mockMvc
        .perform(
            post("/api/v1/events")
                .with(bearerTokenFor(TENANT))
                .contentType(MediaType.APPLICATION_JSON)
                .content(precise))
        .andExpect(status().isAccepted());
    PersistenceBacklog.awaitEmpty(kafkaAdmin);

    assertThat(
            jdbcClient
                .sql("SELECT properties ->> 'amount' FROM events WHERE event_id = :eventId")
                .param("eventId", "evt_precise")
                .query(String.class)
                .single())
        .isEqualTo("0.1234567890123456789");
  }

  @Test
  void rejectsANumberLiteralTooLongForTheParser() throws Exception {
    var tooLong =
        """
        {
          "event_id": "evt_long_number",
          "user_id": "user_42",
          "event_type": "purchase",
          "timestamp": "2026-05-24T10:15:30Z",
          "properties": {"amount": %s}
        }
        """
            .formatted("1".repeat(1001));

    mockMvc
        .perform(
            post("/api/v1/events")
                .with(bearerTokenFor(TENANT))
                .contentType(MediaType.APPLICATION_JSON)
                .content(tooLong))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    assertThat(countByEventId("evt_long_number")).isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "1E+2147483647",
        "10E+2147483647",
        "100E+2147483647",
        "0E+2147483647",
        "-0E+2147483647",
        "0E-20000"
      })
  void rejectsExtremeExponentsWithoutAServerError(String number) throws Exception {
    var request =
        """
        {
          "event_id": "evt_extreme_exponent",
          "user_id": "user_42",
          "event_type": "purchase",
          "timestamp": "2026-05-24T10:15:30Z",
          "properties": {"amounts": [1, {"value": %s}]}
        }
        """
            .formatted(number);

    mockMvc
        .perform(
            post("/api/v1/events")
                .with(bearerTokenFor(TENANT))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    assertThat(countByEventId("evt_extreme_exponent")).isZero();
  }

  @Test
  void acceptsABodyJustUnderTheLimit() throws Exception {
    mockMvc
        .perform(ingest(eventWithLabelPaddedTo("evt_at_body_limit", 512 * 1024 - 1)))
        .andExpect(status().isAccepted());
    PersistenceBacklog.awaitEmpty(kafkaAdmin);

    assertThat(countByEventId("evt_at_body_limit")).isEqualTo(1L);
  }

  @Test
  void refusesABodyOverTheLimitAsTooLarge() throws Exception {
    mockMvc
        .perform(ingest(eventWithLabelPaddedTo("evt_over_body_limit", 512 * 1024 + 1)))
        .andExpect(status().isContentTooLarge())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    PersistenceBacklog.awaitEmpty(kafkaAdmin);

    assertThat(countByEventId("evt_over_body_limit")).isZero();
  }

  private MockHttpServletRequestBuilder ingest(String body) {
    return post("/api/v1/events")
        .with(bearerTokenFor(TENANT))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private static String eventWithLabelPaddedTo(String eventId, int bodyBytes) {
    var template =
        """
        {"event_id": "%s", "user_id": "user_42", "event_type": "page_view",         "timestamp": "2026-05-24T10:15:30Z", "properties": {"label": "%s"}}"""
            .formatted(eventId, "%s");
    return template.formatted("x".repeat(bodyBytes - (template.length() - 2)));
  }

  private long countByEventId(String eventId) {
    return jdbcClient
        .sql("SELECT COUNT(*) FROM events WHERE event_id = :id")
        .param("id", eventId)
        .query(Long.class)
        .single();
  }
}
