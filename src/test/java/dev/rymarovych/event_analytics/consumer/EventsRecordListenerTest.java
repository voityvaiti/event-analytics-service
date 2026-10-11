package dev.rymarovych.event_analytics.consumer;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.rymarovych.event_analytics.domain.EventsRefusedByStorageException;
import dev.rymarovych.event_analytics.domain.MalformedMessageException;
import dev.rymarovych.event_analytics.domain.NewEvent;
import dev.rymarovych.event_analytics.domain.StorageUnavailableException;
import dev.rymarovych.event_analytics.domain.TenantName;
import dev.rymarovych.event_analytics.messaging.DeadLetterPublisher;
import dev.rymarovych.event_analytics.messaging.EventTopics;
import dev.rymarovych.event_analytics.messaging.EventsMessageCodec;
import dev.rymarovych.event_analytics.service.EventPersistenceService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.node.JsonNodeFactory;

class EventsRecordListenerTest {

  private final EventPersistenceService persistence = mock(EventPersistenceService.class);
  private final EventsMessageCodec codec = mock(EventsMessageCodec.class);
  private final DeadLetterPublisher deadLetters = mock(DeadLetterPublisher.class);
  private final Acknowledgment acknowledgment = mock(Acknowledgment.class);
  private final EventsRecordListener listener =
      new EventsRecordListener(persistence, codec, deadLetters);

  @Test
  void keepsGroupingValidRecordsOnBothSidesOfAMalformedRecord() {
    var first = record(10);
    var second = record(11);
    var malformed = record(12);
    var fourth = record(13);
    var fifth = record(14);
    var failure = new MalformedMessageException("Unreadable message");
    when(codec.decode(malformed.value())).thenThrow(failure);

    listener.onRecords(List.of(first, second, malformed, fourth, fifth), acknowledgment);

    var order = inOrder(persistence, deadLetters, acknowledgment);
    order.verify(persistence).persist(List.of(event(10), event(11)));
    order.verify(deadLetters).publish(malformed, failure);
    order.verify(persistence).persist(List.of(event(13), event(14)));
    order.verify(acknowledgment).acknowledge();
    order.verifyNoMoreInteractions();
  }

  @Test
  void retriesFromTheFirstUnstoredRecordWhenStorageFailsDuringIndividualAttempts() {
    var first = record(10);
    var second = record(11);
    var third = record(12);
    refuseGroup();
    doThrow(new StorageUnavailableException("Database unavailable", new IllegalStateException()))
        .when(persistence)
        .persist(List.of(event(11)));

    listener.onRecords(List.of(first, second, third), acknowledgment);

    var order = inOrder(persistence, acknowledgment);
    order.verify(persistence).persist(List.of(event(10), event(11), event(12)));
    order.verify(persistence).persist(List.of(event(10)));
    order.verify(persistence).persist(List.of(event(11)));
    order.verify(acknowledgment).nack(1, EventsRecordListener.RETRY_INTERVAL);
    order.verifyNoMoreInteractions();
    verifyNoInteractions(deadLetters);
  }

  @Test
  void retriesARefusedRecordWhenItsDeadLetterPublicationFails() {
    var first = record(10);
    var second = record(11);
    var third = record(12);
    var refused = refuseGroup();
    doThrow(refused).when(persistence).persist(List.of(event(11)));
    doThrow(new IllegalStateException("Dead-letter publication failed"))
        .when(deadLetters)
        .publish(second, refused);

    listener.onRecords(List.of(first, second, third), acknowledgment);

    var order = inOrder(persistence, deadLetters, acknowledgment);
    order.verify(persistence).persist(List.of(event(10), event(11), event(12)));
    order.verify(persistence).persist(List.of(event(10)));
    order.verify(persistence).persist(List.of(event(11)));
    order.verify(deadLetters).publish(second, refused);
    order.verify(acknowledgment).nack(1, EventsRecordListener.RETRY_INTERVAL);
    order.verifyNoMoreInteractions();
  }

  private EventsRefusedByStorageException refuseGroup() {
    var refused =
        new EventsRefusedByStorageException("Refused events", new IllegalArgumentException());
    doThrow(refused).when(persistence).persist(List.of(event(10), event(11), event(12)));
    return refused;
  }

  private ConsumerRecord<String, byte[]> record(long offset) {
    byte[] payload = Long.toString(offset).getBytes(StandardCharsets.UTF_8);
    when(codec.decode(payload)).thenReturn(List.of(event(offset)));
    return new ConsumerRecord<>(EventTopics.EVENTS, 0, offset, null, payload);
  }

  private static NewEvent event(long offset) {
    return new NewEvent(
        new TenantName("web"),
        "evt_" + offset,
        "user_42",
        "page_view",
        Instant.parse("2026-05-24T10:15:30Z"),
        JsonNodeFactory.instance.objectNode());
  }
}
