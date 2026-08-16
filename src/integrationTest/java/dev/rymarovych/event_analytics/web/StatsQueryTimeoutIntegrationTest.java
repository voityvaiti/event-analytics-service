package dev.rymarovych.event_analytics.web;

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
 * Verifies that the read path's {@code statement_timeout} cancels a query it cannot finish, and
 * that it stays confined to the queries it was set for.
 *
 * <p>The slow query is produced by taking an {@code ACCESS EXCLUSIVE} lock on {@code events} from a
 * second connection: {@code statement_timeout} covers time spent waiting for a lock, so the read is
 * held past the timeout deterministically instead of racing a stopwatch against a query that is
 * normally milliseconds fast.
 *
 * <p>The pool is capped at two connections so both halves stay exact — one connection holds the
 * lock while the other serves the request, and the leak test can then inspect the entire pool.
 */
@SpringBootTest(
    properties = {"analytics.query.timeout=200ms", "spring.datasource.hikari.maximum-pool-size=2"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StatsQueryTimeoutIntegrationTest {

  private static final String EVENT_COUNTS = "/api/v1/stats/event-counts";

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

  @Test
  void timeoutDoesNotSurviveOnPooledConnections() throws Exception {
    mockMvc
        .perform(
            get(EVENT_COUNTS)
                .param("from", "2026-05-24T00:00:00Z")
                .param("to", "2026-05-25T00:00:00Z"))
        .andExpect(status().isOk());

    try (var first = dataSource.getConnection();
        var second = dataSource.getConnection()) {
      assertThat(statementTimeoutOn(first)).isEqualTo("0");
      assertThat(statementTimeoutOn(second)).isEqualTo("0");
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
