package dev.rymarovych.event_analytics.web;

import static dev.rymarovych.event_analytics.DevKeyTokens.bearerTokenFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.rymarovych.event_analytics.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Proves that a request's id reaches the lines the request produces, which is the whole point of
 * minting one.
 *
 * <p>Assertions are made against captured output as plain substrings rather than against a line
 * shape, because the same lines are rendered as JSON when a deployment asks for it and the shape
 * changes while the content does not.
 *
 * <p>The rejection case is load-bearing beyond what it says: a 401 is answered inside the security
 * chain, so it carries an id only while this filter is ordered ahead of that chain. It also holds
 * only under {@code @AutoConfigureMockMvc}'s default, which adds the context's filters; a test
 * written with {@code addFilters = false} would lose the id and not say so.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class RequestIdIntegrationTest {

  private static final String TENANT = "web";

  private static final String STATS_PATH = "/api/v1/stats/event-counts";

  @Autowired private MockMvc mockMvc;

  @Test
  void mintsAnIdentifierWhenTheRequestBringsNone() throws Exception {
    statsOverADay(null)
        .andExpect(status().isOk())
        .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern("[A-Za-z0-9_-]{8,64}")));
  }

  @Test
  void echoesAnIdentifierTheCallerAlreadyTracks() throws Exception {
    statsOverADay("checkout-7f3a91")
        .andExpect(status().isOk())
        .andExpect(header().string(RequestIdFilter.HEADER, "checkout-7f3a91"));
  }

  @Test
  void refusesAnIdentifierThatWouldForgeALogLine(CapturedOutput output) throws Exception {
    var forged = "scan1234\nWARN  nothing actually failed";

    statsOverADay(forged)
        .andExpect(status().isOk())
        .andExpect(header().string(RequestIdFilter.HEADER, not(forged)));

    assertThat(output.getAll()).doesNotContain("nothing actually failed");
  }

  @Test
  void carriesTheIdentifierOntoTheLineUnderARejectedRequest(CapturedOutput output)
      throws Exception {
    mockMvc
        .perform(get(STATS_PATH).header(RequestIdFilter.HEADER, "rejected-93a2f1"))
        .andExpect(status().isUnauthorized());

    assertThat(output.getAll()).contains("rejected-93a2f1").contains("401");
  }

  @Test
  void logsAMalformedRequestWithoutAStackTrace(CapturedOutput output) throws Exception {
    mockMvc
        .perform(
            get(STATS_PATH)
                .with(bearerTokenFor(TENANT))
                .header(RequestIdFilter.HEADER, "malformed-4c8e02")
                .param("from", "2026-05-25T00:00:00Z")
                .param("to", "2026-05-24T00:00:00Z")
                .param("groupBy", "day"))
        .andExpect(status().isBadRequest());

    assertThat(output.getAll())
        .contains("malformed-4c8e02")
        .contains("400")
        .doesNotContain("at dev.rymarovych.event_analytics.web.StatsController");
  }

  /**
   * A NUL byte is a value {@code @NotBlank} accepts and a {@code TEXT} column refuses, so it fails
   * in the repository — the one failure this API can provoke that no typed handler claims.
   */
  @Test
  void logsAFailureNothingClaimsWithItsStackTrace(CapturedOutput output) throws Exception {
    var withNulByte =
        """
        {
          "event_id": "evt_nul",
          "user_id": "user_\\u0000_42",
          "event_type": "page_view",
          "timestamp": "2026-05-24T10:15:30Z"
        }
        """;

    mockMvc
        .perform(
            post("/api/v1/events")
                .with(bearerTokenFor(TENANT))
                .header(RequestIdFilter.HEADER, "unclaimed-1b7de4")
                .contentType(MediaType.APPLICATION_JSON)
                .content(withNulByte))
        .andExpect(status().isInternalServerError());

    assertThat(output.getAll())
        .contains("unclaimed-1b7de4")
        .contains("500")
        .contains("at dev.rymarovych.event_analytics");
  }

  /**
   * The happy path stays silent on purpose: rate, latency and error share are on the dashboard, and
   * a line per request would be a write-path cost on a path measured at 125,290 events/s. This is
   * what fails if a well-meaning INFO line is ever added to it.
   */
  @Test
  void logsNothingForARequestThatSucceeds(CapturedOutput output) throws Exception {
    statsOverADay("warmed-0e21ac").andExpect(status().isOk());
    var quiet = output.getAll().length();

    statsOverADay("silent-55c9b8").andExpect(status().isOk());

    assertThat(output.getAll().substring(quiet)).doesNotContain("silent-55c9b8");
  }

  private ResultActions statsOverADay(String requestId) throws Exception {
    var request =
        get(STATS_PATH)
            .with(bearerTokenFor(TENANT))
            .param("from", "2026-05-24T00:00:00Z")
            .param("to", "2026-05-25T00:00:00Z")
            .param("groupBy", "day");
    return mockMvc.perform(
        requestId == null ? request : request.header(RequestIdFilter.HEADER, requestId));
  }
}
