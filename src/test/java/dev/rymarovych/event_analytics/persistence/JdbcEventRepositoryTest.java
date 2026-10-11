package dev.rymarovych.event_analytics.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.rymarovych.event_analytics.domain.NewEvent;
import dev.rymarovych.event_analytics.domain.StorageUnavailableException;
import dev.rymarovych.event_analytics.domain.TenantName;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import tools.jackson.databind.node.JsonNodeFactory;

/** A failure carrying no SQLSTATE cannot blame the rows, so it stays recoverable. */
class JdbcEventRepositoryTest {

  private final NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
  private final JdbcEventRepository repository = new JdbcEventRepository(jdbcTemplate);

  @Test
  void treatsAFailureWithoutSqlStateAsRecoverable() {
    when(jdbcTemplate.batchUpdate(anyString(), any(SqlParameterSource[].class)))
        .thenThrow(new DataAccessResourceFailureException("connection lost"));

    assertThatThrownBy(() -> repository.saveAll(List.of(event())))
        .isInstanceOf(StorageUnavailableException.class);
  }

  private static NewEvent event() {
    return new NewEvent(
        new TenantName("web"),
        "evt_1",
        "user_42",
        "page_view",
        Instant.parse("2026-05-24T10:15:30Z"),
        JsonNodeFactory.instance.objectNode());
  }
}
