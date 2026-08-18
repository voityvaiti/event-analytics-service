package dev.rymarovych.event_analytics.persistence;

import dev.rymarovych.event_analytics.domain.NewEvent;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link JdbcClient}-backed {@link EventRepository}.
 *
 * <p>Inserts are idempotent on {@code event_id}: a re-delivered event is silently skipped via
 * {@code ON CONFLICT DO NOTHING}. {@code created_at} is left to the database default.
 *
 * <p>One statement serves both methods, so there is a single INSERT and a single conflict clause to
 * keep in step with the schema. The batch runs through {@link NamedParameterJdbcTemplate} rather
 * than the client because {@link JdbcClient} exposes no batch API — the template is what the client
 * is built on, so this is the same statement over the same datasource, not a second access style.
 */
@Repository
class JdbcEventRepository implements EventRepository {

  private static final String PARAM_EVENT_ID = "eventId";
  private static final String PARAM_SOURCE = "source";
  private static final String PARAM_USER_ID = "userId";
  private static final String PARAM_EVENT_TYPE = "eventType";
  private static final String PARAM_OCCURRED_AT = "occurredAt";
  private static final String PARAM_PROPERTIES = "properties";

  private static final String INSERT =
      """
      INSERT INTO events (event_id, source, user_id, event_type, occurred_at, properties)
      VALUES (:eventId, :source, :userId, :eventType, :occurredAt, CAST(:properties AS JSONB))
      ON CONFLICT (event_id) DO NOTHING
      """;

  private final JdbcClient jdbcClient;
  private final NamedParameterJdbcTemplate jdbcTemplate;

  JdbcEventRepository(JdbcClient jdbcClient, NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcClient = jdbcClient;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void save(NewEvent event) {
    jdbcClient.sql(INSERT).paramSource(parameters(event)).update();
  }

  @Override
  public void saveAll(List<NewEvent> events) {
    jdbcTemplate.batchUpdate(
        INSERT,
        events.stream().map(JdbcEventRepository::parameters).toArray(SqlParameterSource[]::new));
  }

  private static SqlParameterSource parameters(NewEvent event) {
    return new MapSqlParameterSource()
        .addValue(PARAM_EVENT_ID, event.eventId())
        .addValue(PARAM_SOURCE, event.tenant().value())
        .addValue(PARAM_USER_ID, event.userId())
        .addValue(PARAM_EVENT_TYPE, event.eventType())
        .addValue(PARAM_OCCURRED_AT, OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
        .addValue(PARAM_PROPERTIES, event.properties().toString());
  }
}
