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
 * Verify that Flyway migrations bypass the pool's statement timeout. A one-second test migration
 * must succeed while pooled connections have a 100ms bound.
 *
 * <p>Boot gives Flyway a separate SimpleDriverDataSource. This test catches configuration or
 * upgrade changes that route migrations through the timed pool.
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
