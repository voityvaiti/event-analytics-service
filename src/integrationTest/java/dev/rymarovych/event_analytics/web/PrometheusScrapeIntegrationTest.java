package dev.rymarovych.event_analytics.web;

import static dev.rymarovych.event_analytics.DevKeyTokens.bearerTokenFor;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.rymarovych.event_analytics.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Pins the scrape a collector reads. Exposure is a property of configuration rather than of code,
 * so nothing in the application fails when an endpoint drops off the list or a meter stops being
 * published — the dashboards built on it simply go blank. These are the tests that fail instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PrometheusScrapeIntegrationTest {

  private static final String TENANT = "web";

  @Autowired private MockMvc mockMvc;

  /**
   * A collector presents no credentials, and the reason actuator is open at all is the same one
   * that keeps the perf harness working. This is the test that fails if that stops being true.
   */
  @Test
  void scrapeNeedsNoToken() throws Exception {
    scrape().andExpect(content().contentTypeCompatibleWith("text/plain"));
  }

  /**
   * The read spike resolved to time spent waiting for a connection rather than time spent running a
   * query, which makes the queue depth beside the pool's own gauges the series the saturation panel
   * is built on.
   */
  @Test
  void poolSaturationIsScrapeable() throws Exception {
    scrape()
        .andExpect(content().string(containsString("hikaricp_connections_active")))
        .andExpect(content().string(containsString("hikaricp_connections_pending")))
        .andExpect(content().string(containsString("hikaricp_connections_max")));
  }

  /**
   * Buckets, not a mean: a percentile the dashboard asks for over an arbitrary window can only be
   * recovered from bucket counts, and Micrometer publishes none unless asked. The meter appears
   * only once a request has been served, so one is served first.
   */
  @Test
  void requestLatencyIsScrapeableAsBuckets() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/stats/event-counts")
                .with(bearerTokenFor(TENANT))
                .param("from", "2026-05-24T00:00:00Z")
                .param("to", "2026-05-25T00:00:00Z")
                .param("groupBy", "day"))
        .andExpect(status().isOk());

    scrape()
        .andExpect(content().string(containsString("http_server_requests_seconds_bucket")))
        .andExpect(content().string(containsString("/api/v1/stats/event-counts")));
  }

  private ResultActions scrape() throws Exception {
    return mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
  }
}
