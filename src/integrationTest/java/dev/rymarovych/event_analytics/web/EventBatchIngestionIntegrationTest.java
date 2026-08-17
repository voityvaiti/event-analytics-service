package dev.rymarovych.event_analytics.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
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
 * End-to-end batch ingestion tests driving the real controller → service → repository → Postgres
 * path.
 *
 * <p>Deliberately NOT {@code @Transactional}, for the same reason the single-event tests are not: a
 * batch's own transaction is the thing under test here, and a surrounding rollback would hide it.
 * Isolation comes from deleting the rows after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EventBatchIngestionIntegrationTest {

  private static final String BATCH_PATH = "/api/v1/events/batch";

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
          "source": "web",
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
   * The batch commits once or not at all, and this is what says so rather than the driver's
   * protocol. A NUL byte is the failure to provoke it with: {@code @NotBlank} sees a perfectly good
   * non-blank string, and Postgres refuses it in a {@code TEXT} column — so the batch fails at its
   * second event, after the first has already been sent.
   *
   * <p>It passes with the service's {@code @Transactional} removed as well, which is recorded
   * there: the batch is already atomic today, and the annotation says so rather than making it so.
   */
  @Test
  void midBatchDatabaseFailureLeavesNothingWritten() {
    var withNulByte =
        """
        {
          "source": "web",
          "event_id": "evt_2",
          "user_id": "user_\\u0000_42",
          "event_type": "page_view",
          "timestamp": "2026-05-24T10:15:30Z"
        }
        """;

    var thrown =
        catchThrowable(
            () -> postBatch(batchOf(eventJson("evt_1"), withNulByte, eventJson("evt_3"))));

    assertThat(thrown).as("Postgres must refuse a NUL byte in a TEXT column").isNotNull();
    assertThat(countAll()).isZero();
  }

  private ResultActions postBatch(String body) throws Exception {
    return mockMvc.perform(post(BATCH_PATH).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private static String batchOf(String... events) {
    return "{\"events\": [" + String.join(",", events) + "]}";
  }

  private static String eventJson(String eventId) {
    return """
        {
          "source": "web",
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
