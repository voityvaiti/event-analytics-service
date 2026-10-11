package dev.rymarovych.event_analytics.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rymarovych.event_analytics.InjectedDatabaseFailure;
import dev.rymarovych.event_analytics.TestcontainersConfiguration;
import dev.rymarovych.event_analytics.domain.EventsRefusedByStorageException;
import dev.rymarovych.event_analytics.domain.NewEvent;
import dev.rymarovych.event_analytics.domain.PermanentException;
import dev.rymarovych.event_analytics.domain.RecoverableException;
import dev.rymarovych.event_analytics.domain.StorageUnavailableException;
import dev.rymarovych.event_analytics.domain.TenantName;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.node.JsonNodeFactory;

/** Pins which database failures the consumer may dead-letter and which it must retry. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcEventRepositoryIntegrationTest {

  @Autowired private EventRepository repository;
  @Autowired private JdbcClient jdbcClient;

  @AfterEach
  void cleanUp() {
    InjectedDatabaseFailure.remove(jdbcClient);
    jdbcClient.sql("DELETE FROM events").update();
  }

  @Test
  void reportsARefusedEventAsPermanent() {
    InjectedDatabaseFailure.install(jdbcClient);

    assertThatThrownBy(
            () -> repository.saveAll(List.of(event(InjectedDatabaseFailure.FAILING_EVENT_ID))))
        .isInstanceOf(EventsRefusedByStorageException.class)
        .isInstanceOf(PermanentException.class);
    assertThatThrownBy(
            () ->
                repository.saveAll(
                    List.of(event("evt_1"), event(InjectedDatabaseFailure.FAILING_EVENT_ID))))
        .isInstanceOf(EventsRefusedByStorageException.class);
  }

  @Test
  void reportsAFailureThatDoesNotBlameTheEventAsRecoverable() {
    InjectedDatabaseFailure.install(jdbcClient, "57014");

    assertThatThrownBy(
            () -> repository.saveAll(List.of(event(InjectedDatabaseFailure.FAILING_EVENT_ID))))
        .isInstanceOf(StorageUnavailableException.class)
        .isInstanceOf(RecoverableException.class);
    assertThatThrownBy(
            () ->
                repository.saveAll(
                    List.of(event("evt_1"), event(InjectedDatabaseFailure.FAILING_EVENT_ID))))
        .isInstanceOf(StorageUnavailableException.class);
  }

  private static NewEvent event(String eventId) {
    return new NewEvent(
        new TenantName("web"),
        eventId,
        "user_42",
        "page_view",
        Instant.parse("2026-05-24T10:15:30Z"),
        JsonNodeFactory.instance.objectNode());
  }
}
