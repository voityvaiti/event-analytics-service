package dev.rymarovych.event_analytics.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.rymarovych.event_analytics.TestcontainersConfiguration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Verify the shipped json-logging profile's ECS fields so Boot upgrades cannot silently break
 * collectors.
 *
 * <p>Changing console format sets a system property for the rest of this JVM. Other tests must
 * match log content rather than require human-readable line formatting.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("json-logging")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class StructuredLogFormatIntegrationTest {

  private static final String REQUEST_ID = "structured-6b41de";

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Test
  void writesAFailureAsOneJsonDocumentCarryingTheRequestId(CapturedOutput output) throws Exception {
    mockMvc
        .perform(get("/api/v1/stats/event-counts").header(RequestIdFilter.HEADER, REQUEST_ID))
        .andExpect(status().isUnauthorized());

    var document = objectMapper.readTree(lineCarryingTheRequestId(output));

    assertThat(document.path("requestId").asString()).isEqualTo(REQUEST_ID);
    assertThat(document.path("@timestamp").asString()).isNotBlank();
    assertThat(document.path("log").path("level").asString()).isEqualTo("WARN");
    assertThat(document.path("service").path("name").asString()).isEqualTo("event-analytics");
    assertThat(document.path("message").asString()).contains("401");
  }

  private static String lineCarryingTheRequestId(CapturedOutput output) {
    return Arrays.stream(output.getAll().split("\n"))
        .filter(line -> line.startsWith("{") && line.contains(REQUEST_ID))
        .reduce((first, last) -> last)
        .orElseThrow(() -> new AssertionError("No JSON line carried the request id"));
  }
}
