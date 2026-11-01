package dev.rymarovych.event_analytics.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rymarovych.event_analytics.InjectedDatabaseFailure;
import dev.rymarovych.event_analytics.TestcontainersConfiguration;
import dev.rymarovych.event_analytics.domain.TenantName;
import dev.rymarovych.event_analytics.domain.ingestion.EventsRefusedByStorageException;
import dev.rymarovych.event_analytics.domain.ingestion.NewEvent;
import dev.rymarovych.event_analytics.domain.ingestion.PermanentException;
import dev.rymarovych.event_analytics.domain.ingestion.RecoverableException;
import dev.rymarovych.event_analytics.domain.ingestion.StorageUnavailableException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.node.JsonNodeFactory;

/** Pins how many events count as new, and which failures may be dead-lettered or retried. */
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
  void countsOnlyEventsNotStoredBeforeAsNew() {
    assertThat(repository.saveAll(List.of(event("evt_1"), event("evt_2")))).isEqualTo(2);
    assertThat(repository.saveAll(List.of(event("evt_2"), event("evt_3")))).isEqualTo(1);
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
