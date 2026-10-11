package dev.rymarovych.event_analytics;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Rejects one event ID at the database, independently of request validation. */
public final class InjectedDatabaseFailure {

  public static final String FAILING_EVENT_ID = "evt_injected_failure";

  private InjectedDatabaseFailure() {}

  public static void install(JdbcClient jdbcClient) {
    jdbcClient
        .sql(
            """
            CREATE OR REPLACE FUNCTION fail_injected_event() RETURNS TRIGGER
            LANGUAGE plpgsql AS $$
            BEGIN
              RAISE EXCEPTION 'injected failure for event %', NEW.event_id;
            END
            $$
            """)
        .update();
    jdbcClient
        .sql(
            """
            CREATE TRIGGER fail_injected_event BEFORE INSERT ON events
            FOR EACH ROW WHEN (NEW.event_id = '%s')
            EXECUTE FUNCTION fail_injected_event()
            """
                .formatted(FAILING_EVENT_ID))
        .update();
  }

  public static void remove(JdbcClient jdbcClient) {
    jdbcClient.sql("DROP TRIGGER IF EXISTS fail_injected_event ON events").update();
    jdbcClient.sql("DROP FUNCTION IF EXISTS fail_injected_event()").update();
  }
}
