package dev.rymarovych.event_analytics.service;

import dev.rymarovych.event_analytics.domain.NewEvent;
import dev.rymarovych.event_analytics.persistence.EventRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link EventPersistenceService} writing each call in one database transaction. */
@Service
class TransactionalEventPersistenceService implements EventPersistenceService {

  private final EventRepository repository;

  TransactionalEventPersistenceService(EventRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional
  public void persist(List<NewEvent> events) {
    repository.saveAll(events);
  }
}
