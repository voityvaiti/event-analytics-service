package dev.rymarovych.event_analytics.web;

import static dev.rymarovych.event_analytics.DevKeyTokens.bearerTokenFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.rymarovych.event_analytics.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verify statement timeouts produce 503 problem details on every pooled connection. Hold ACCESS
 * EXCLUSIVE on events to block a read deterministically: statement_timeout includes lock waits.
 *
 * <p>Use two connections, one for the lock and one for the request, then inspect the entire pool.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.hikari.connection-init-sql=SET statement_timeout = '200ms'",
      "spring.datasource.hikari.maximum-pool-size=2"
    })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StatsQueryTimeoutIntegrationTest {

  private static final String EVENT_COUNTS = "/api/v1/stats/event-counts";

  private static final String TENANT = "web";

  @Autowired private MockMvc mockMvc;
  @Autowired private DataSource dataSource;

  @Test
  void cancelledQueryIsServiceUnavailableProblemDetail() throws Exception {
    try (var lockHolder = dataSource.getConnection()) {
      lockHolder.setAutoCommit(false);
      try (var statement = lockHolder.createStatement()) {
        statement.execute("LOCK TABLE events IN ACCESS EXCLUSIVE MODE");
      }

      try {
        mockMvc
            .perform(
                get(EVENT_COUNTS)
                    .with(bearerTokenFor(TENANT))
                    .param("from", "2026-05-24T00:00:00Z")
                    .param("to", "2026-05-25T00:00:00Z"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status").value(503))
            .andExpect(jsonPath("$.instance").value(EVENT_COUNTS));
      } finally {
        lockHolder.rollback();
      }
    }
  }

  /**
   * The bound is deliberately on the pool rather than on the read path, so it applies to whichever
   * connection any request happens to draw — including the ingest path's. This asserts the blast
   * radius is that wide on purpose, so narrowing it later is a decision rather than an accident.
   */
  @Test
  void everyPooledConnectionCarriesTheTimeout() throws Exception {
    try (var first = dataSource.getConnection();
        var second = dataSource.getConnection()) {
      assertThat(statementTimeoutOn(first)).isEqualTo("200ms");
      assertThat(statementTimeoutOn(second)).isEqualTo("200ms");
    }
  }

  private static String statementTimeoutOn(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("SHOW statement_timeout")) {
      rows.next();
      return rows.getString(1);
    }
  }
}
