package dev.rymarovych.event_analytics.persistence;

import dev.rymarovych.event_analytics.domain.NewEvent;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link JdbcClient}-backed {@link EventRepository}.
 *
 * <p>Inserts are idempotent on {@code event_id}: a re-delivered event is silently skipped via
 * {@code ON CONFLICT DO NOTHING}. {@code created_at} is left to the database default.
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

  JdbcEventRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public void save(NewEvent event) {
    jdbcClient
        .sql(INSERT)
        .param(PARAM_EVENT_ID, event.eventId())
        .param(PARAM_SOURCE, event.source())
        .param(PARAM_USER_ID, event.userId())
        .param(PARAM_EVENT_TYPE, event.eventType())
        .param(PARAM_OCCURRED_AT, OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
        .param(PARAM_PROPERTIES, event.properties().toString())
        .update();
  }
}
