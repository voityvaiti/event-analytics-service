package dev.rymarovych.event_analytics.web;

import static dev.rymarovych.event_analytics.DevKeyTokens.bearerTokenFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.rymarovych.event_analytics.TestcontainersConfiguration;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Test batch ingestion through the controller, service, repository, and PostgreSQL. Avoid a test
 * transaction: it would hide the batch's own commit behavior. Delete rows after each test for
 * isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EventBatchIngestionIntegrationTest {

  private static final String BATCH_PATH = "/api/v1/events/batch";

  private static final String TENANT = "web";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcClient jdbcClient;

  @AfterEach
  void cleanUp() {
    jdbcClient.sql("DELETE FROM events").update();
  }

  @Test
  void acceptsValidBatchAndPersistsEveryRow() throws Exception {
    postBatch(batchOf(eventJson("evt_1"), eventJson("evt_2"), eventJson("evt_3")))
        .andExpect(status().isAccepted());

    assertThat(countAll()).isEqualTo(3L);
  }

  @Test
  void retriedBatchIsDeduplicatedAgainstCommittedRows() throws Exception {
    var batch = batchOf(eventJson("evt_1"), eventJson("evt_2"));

    postBatch(batch).andExpect(status().isAccepted());
    postBatch(batch).andExpect(status().isAccepted());

    assertThat(countAll()).isEqualTo(2L);
  }

  @Test
  void duplicateWithinOneBatchCollapsesToOneRow() throws Exception {
    postBatch(batchOf(eventJson("evt_1"), eventJson("evt_1"))).andExpect(status().isAccepted());

    assertThat(countAll()).isEqualTo(1L);
  }

  @Test
  void rejectsWholeBatchWhenOneEventIsInvalid() throws Exception {
    var missingEventId =
        """
        {
          "user_id": "user_42",
          "event_type": "page_view",
          "timestamp": "2026-05-24T10:15:30Z"
        }
        """;

    postBatch(batchOf(eventJson("evt_1"), missingEventId, eventJson("evt_3")))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.errors[*].field", hasItem("events[1].eventId")));

    assertThat(countAll()).isZero();
  }

  @Test
  void rejectsEmptyBatch() throws Exception {
    postBatch("{\"events\": []}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[*].field", hasItem("events")));
  }

  @Test
  void rejectsBatchOverTheSizeCap() throws Exception {
    var overCap =
        IntStream.rangeClosed(0, EventBatchRequest.MAX_EVENTS)
            .mapToObj(index -> eventJson("evt_" + index))
            .collect(Collectors.joining(",", "{\"events\": [", "]}"));

    postBatch(overCap)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[*].field", hasItem("events")));

    assertThat(countAll()).isZero();
  }

  /**
   * A NUL byte passes NotBlank but PostgreSQL rejects it in TEXT, failing the second event after
   * the first was sent. Assert no rows remain and the error has a problem body.
   *
   * <p>This also passes without the service transaction today because of pgjdbc batching behavior;
   * the service annotation makes the guarantee explicit.
   */
  @Test
  void midBatchDatabaseFailureLeavesNothingWritten() throws Exception {
    var withNulByte =
        """
        {
          "event_id": "evt_2",
          "user_id": "user_\\u0000_42",
          "event_type": "page_view",
          "timestamp": "2026-05-24T10:15:30Z"
        }
        """;

    postBatch(batchOf(eventJson("evt_1"), withNulByte, eventJson("evt_3")))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    assertThat(countAll()).isZero();
  }

  private ResultActions postBatch(String body) throws Exception {
    return mockMvc.perform(
        post(BATCH_PATH)
            .with(bearerTokenFor(TENANT))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private static String batchOf(String... events) {
    return "{\"events\": [" + String.join(",", events) + "]}";
  }

  private static String eventJson(String eventId) {
    return """
        {
          "event_id": "%s",
          "user_id": "user_42",
          "event_type": "page_view",
          "timestamp": "2026-05-24T10:15:30Z",
          "properties": {"page_url": "/products/laptop-x1", "device": "mobile"}
        }
        """
        .formatted(eventId);
  }

  private long countAll() {
    return jdbcClient.sql("SELECT COUNT(*) FROM events").query(Long.class).single();
  }
}
