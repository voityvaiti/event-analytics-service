package dev.rymarovych.event_analytics.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rymarovych.event_analytics.TestcontainersConfiguration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Guards the one place the pool's {@code statement_timeout} would do damage: a migration is exactly
 * the kind of long statement the bound exists to kill, and building an index over millions of rows
 * takes minutes. This context migrates under a 100ms bound with a test-only migration that takes a
 * second, so a migration subject to the bound fails Flyway and the context never starts.
 *
 * <p>What keeps them out of its way is that Boot hands Flyway its own {@code
 * SimpleDriverDataSource} built from the connection details, so it never borrows a pooled
 * connection and never sees the init SQL. Nothing in the configuration says so, which is exactly
 * why this test exists: the day a Boot version or a datasource change puts Flyway back on the pool,
 * a migration slower than the bound is cancelled, Flyway fails, and the context does not start.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.hikari.connection-init-sql=SET statement_timeout = '100ms'",
      "spring.flyway.locations=classpath:db/migration,classpath:db/slow-migration"
    })
@Import(TestcontainersConfiguration.class)
class FlywayMigrationTimeoutIntegrationTest {

  @Autowired private JdbcClient jdbcClient;
  @Autowired private DataSource dataSource;

  @Test
  void migrationOutlivesThePoolsStatementTimeout() {
    var applied =
        jdbcClient
            .sql("SELECT success FROM flyway_schema_history WHERE version = '900'")
            .query(Boolean.class)
            .single();

    assertThat(applied).isTrue();
  }

  @Test
  void theApplicationsOwnConnectionsStillCarryTheBound() throws Exception {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SHOW statement_timeout")) {
      rows.next();

      assertThat(rows.getString(1)).isEqualTo("100ms");
    }
  }
}
