package dev.rymarovych.event_analytics.web;

import static dev.rymarovych.event_analytics.DevKeyTokens.bearerTokenFor;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.rymarovych.event_analytics.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Off by default: even a control token finds nothing there. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PersistenceConsumerControlDisabledIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void theServiceEndpointsAreAbsentUnlessEnabled() throws Exception {
    var controlToken = bearerTokenFor("perf", "persistence:control");

    mockMvc
        .perform(get("/internal/persistence-consumer").with(controlToken))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(post("/internal/persistence-consumer/stop").with(controlToken))
        .andExpect(status().isNotFound());
  }
}
