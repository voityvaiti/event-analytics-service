package dev.rymarovych.event_analytics.persistence;

import dev.rymarovych.event_analytics.domain.EventsRefusedByStorageException;
import dev.rymarovych.event_analytics.domain.NewEvent;
import dev.rymarovych.event_analytics.domain.StorageUnavailableException;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JdbcClient-backed event persistence. One INSERT serves single and batch writes; ON CONFLICT DO
 * NOTHING skips duplicate event_id values and the database supplies created_at.
 *
 * <p>Use NamedParameterJdbcTemplate for batching because JdbcClient has no batch API; both share
 * the datasource and statement.
 */
@Repository
class JdbcEventRepository implements EventRepository {

  private static final String PARAM_EVENT_ID = "eventId";
  private static final String PARAM_TENANT_NAME = "tenantName";
  private static final String PARAM_USER_ID = "userId";
  private static final String PARAM_EVENT_TYPE = "eventType";
  private static final String PARAM_OCCURRED_AT = "occurredAt";
  private static final String PARAM_PROPERTIES = "properties";

  private static final String INSERT =
      """
      INSERT INTO events (event_id, tenant_name, user_id, event_type, occurred_at, properties)
      VALUES (:eventId, :tenantName, :userId, :eventType, :occurredAt, CAST(:properties AS JSONB))
      ON CONFLICT (event_id) DO NOTHING
      """;

  /**
   * Data exception (22), integrity constraint (23), program limit such as the B-tree entry size
   * (54), and an error raised by a trigger (P0).
   */
  private static final Set<String> REFUSED_EVENT_STATE_CLASSES = Set.of("22", "23", "54", "P0");

  private final JdbcClient jdbcClient;
  private final NamedParameterJdbcTemplate jdbcTemplate;

  JdbcEventRepository(JdbcClient jdbcClient, NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcClient = jdbcClient;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void save(NewEvent event) {
    try {
      jdbcClient.sql(INSERT).paramSource(parameters(event)).update();
    } catch (DataAccessException failure) {
      throw translated(failure);
    }
  }

  @Override
  public void saveAll(List<NewEvent> events) {
    try {
      jdbcTemplate.batchUpdate(
          INSERT,
          events.stream().map(JdbcEventRepository::parameters).toArray(SqlParameterSource[]::new));
    } catch (DataAccessException failure) {
      throw translated(failure);
    }
  }

  private static RuntimeException translated(DataAccessException failure) {
    return refusesTheEvents(failure)
        ? new EventsRefusedByStorageException("The database refused the events", failure)
        : new StorageUnavailableException("The database could not store the events", failure);
  }

  /**
   * Only SQLSTATE classes that blame the rows count as refusal; anything else, including a broken
   * statement after a bad deploy, is left recoverable so it stalls rather than discards events.
   */
  private static boolean refusesTheEvents(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = nextCause(cause)) {
      if (cause instanceof SQLException sql && sql.getSQLState() != null) {
        return REFUSED_EVENT_STATE_CLASSES.contains(sql.getSQLState().substring(0, 2));
      }
    }
    return false;
  }

  private static @Nullable Throwable nextCause(Throwable failure) {
    if (failure instanceof SQLException sql && sql.getNextException() != null) {
      return sql.getNextException();
    }
    return failure.getCause();
  }

  private static SqlParameterSource parameters(NewEvent event) {
    return new MapSqlParameterSource()
        .addValue(PARAM_EVENT_ID, event.eventId())
        .addValue(PARAM_TENANT_NAME, event.tenant().value())
        .addValue(PARAM_USER_ID, event.userId())
        .addValue(PARAM_EVENT_TYPE, event.eventType())
        .addValue(PARAM_OCCURRED_AT, OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
        .addValue(PARAM_PROPERTIES, event.properties().toString());
  }
}
