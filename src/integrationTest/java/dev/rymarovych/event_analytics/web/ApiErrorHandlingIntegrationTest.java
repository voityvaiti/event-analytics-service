package dev.rymarovych.event_analytics.web;

import static dev.rymarovych.event_analytics.DevKeyTokens.bearerTokenFor;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.rymarovych.event_analytics.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the shared {@link ApiExceptionHandler} contract: request-handling failures come back as
 * RFC 9457 {@code application/problem+json}, carry the request path in {@code instance}, and expose
 * field-level detail for bean-validation failures.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiErrorHandlingIntegrationTest {

  private static final String TENANT = "web";

  @Autowired private MockMvc mockMvc;

  @Test
  void invertedWindowIsProblemDetailWithInstance() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/stats/top-pages")
                .with(bearerTokenFor(TENANT))
                .param("from", "2026-05-25T00:00:00Z")
                .param("to", "2026-05-24T00:00:00Z"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.detail").value("from must be strictly before to"))
        .andExpect(jsonPath("$.instance").value("/api/v1/stats/top-pages"));
  }

  @Test
  void outOfRangeLimitReportsTheOffendingParameter() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/stats/top-pages")
                .with(bearerTokenFor(TENANT))
                .param("from", "2026-05-24T00:00:00Z")
                .param("to", "2026-05-25T00:00:00Z")
                .param("limit", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.errors[0].field").value("limit"));
  }

  @Test
  void unparseableTimestampParameterIsProblemDetail() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/stats/event-counts")
                .with(bearerTokenFor(TENANT))
                .param("from", "notadate")
                .param("to", "notadate"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void bodyValidationListsOffendingFields() throws Exception {
    var missingEventIdAndUserId =
        """
        {
          "event_type": "page_view",
          "timestamp": "2026-05-24T10:15:30Z"
        }
        """;

    mockMvc
        .perform(
            post("/api/v1/events")
                .with(bearerTokenFor(TENANT))
                .contentType(MediaType.APPLICATION_JSON)
                .content(missingEventIdAndUserId))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.errors[*].field", hasItem("eventId")))
        .andExpect(jsonPath("$.errors[*].field", hasItem("userId")));
  }
}
