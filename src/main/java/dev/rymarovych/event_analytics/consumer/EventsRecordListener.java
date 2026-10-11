package dev.rymarovych.event_analytics.consumer;

import dev.rymarovych.event_analytics.domain.MalformedMessageException;
import dev.rymarovych.event_analytics.domain.NewEvent;
import dev.rymarovych.event_analytics.domain.PermanentException;
import dev.rymarovych.event_analytics.messaging.DeadLetterPublisher;
import dev.rymarovych.event_analytics.messaging.EventTopics;
import dev.rymarovych.event_analytics.messaging.EventsMessageCodec;
import dev.rymarovych.event_analytics.service.EventPersistenceService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * The persistence group. Stores whole records in shared transactions; when the database refuses a
 * group, retries its records one by one and dead-letters only those refused alone. Any other
 * failure is retried from the first record not yet handled, so offsets never pass an unstored one.
 */
@Component
class EventsRecordListener {

  static final String LISTENER_ID = "persistence";
  static final int MAX_EVENTS_PER_TRANSACTION = 1000;
  static final int MAX_BYTES_PER_TRANSACTION = 1024 * 1024;
  static final Duration RETRY_INTERVAL = Duration.ofSeconds(1);

  private static final Logger log = LoggerFactory.getLogger(EventsRecordListener.class);

  private final EventPersistenceService persistence;
  private final EventsMessageCodec codec;
  private final DeadLetterPublisher deadLetters;

  EventsRecordListener(
      EventPersistenceService persistence,
      EventsMessageCodec codec,
      DeadLetterPublisher deadLetters) {
    this.persistence = persistence;
    this.codec = codec;
    this.deadLetters = deadLetters;
  }

  /**
   * Stores a Kafka batch in input order and manually acknowledges the records handled successfully.
   * Readable records are grouped by {@link #groups(List)} and stored in a shared transaction. If
   * the database rejects a group with {@link PermanentException}, its records are tried
   * individually, so only records rejected on their own are sent to the dead-letter topic.
   * Unreadable records go directly to that topic.
   *
   * <p>A record counts as handled only after persistence succeeds or the dead-letter topic
   * acknowledges publication. Thus {@code handled} is both the number of records in the completed
   * prefix and the index of the first unhandled record in the original batch. It counts Kafka
   * records, not events or offsets.
   *
   * <p>Once all records are handled, {@link Acknowledgment#acknowledge()} confirms the entire
   * batch. An unhandled runtime failure, including failed dead-letter publication, instead invokes
   * {@link Acknowledgment#nack(int, Duration)}: offsets for the completed prefix are committed, and
   * the remaining records are redelivered after the retry interval.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>If storing {@code [A, B, C]} as a group is permanently rejected, each record is tried
   *       alone. If A and C succeed and B is permanently rejected, B is dead-lettered and the batch
   *       is acknowledged once all three are handled.
   *   <li>For {@code [A, unreadable, B]}, if A is stored but dead-letter publication fails, {@code
   *       nack(1, RETRY_INTERVAL)} confirms A and requests redelivery of the unreadable record and
   *       B.
   *   <li>If a shared transaction for {@code [A, B]} fails with a transient error, neither record
   *       is counted as handled; retry starts at A.
   * </ul>
   */
  @KafkaListener(id = LISTENER_ID, groupId = LISTENER_ID, topics = EventTopics.EVENTS)
  void onRecords(List<ConsumerRecord<String, byte[]>> records, Acknowledgment acknowledgment) {
    int handled = 0;
    try {
      for (List<Decoded> group : groups(records.stream().map(this::decode).toList())) {
        boolean storedAsGroup = tryStoreGroup(group);
        if (storedAsGroup) {
          handled += group.size();
        } else {
          for (Decoded record : group) {
            storeOrDeadLetter(record);
            handled++;
          }
        }
      }
      acknowledgment.acknowledge();
    } catch (RuntimeException unstored) {
      logRetry(records.get(handled), unstored);
      acknowledgment.nack(handled, RETRY_INTERVAL);
    }
  }

  /**
   * Splits decoded Kafka records into consecutive groups for persistence, preserving input order.
   * Each record stays whole and appears in exactly one group; one record can contain several
   * events.
   *
   * <p>A readable record joins the current readable group only if the combined event count is at
   * most {@value #MAX_EVENTS_PER_TRANSACTION} and the combined Kafka value size is at most {@value
   * #MAX_BYTES_PER_TRANSACTION} bytes. Otherwise it starts a new group. These limits apply when
   * joining a group: a single oversized record is retained as a group of its own, without splitting
   * or rejecting it here.
   *
   * <p>Every unreadable record forms its own group, including consecutive unreadable records. The
   * next readable record starts a new group rather than joining an earlier one. This keeps
   * processing in input order, so {@link #onRecords(List, Acknowledgment)} can use the handled
   * record count as the index from which to retry.
   *
   * <p>Examples (assuming the byte limit allows the readable groups shown):
   *
   * <pre>
   * Input                                      Groups
   * A(600 events), B(300), C(200)                [A, B], [C]
   * A, unreadable1, unreadable2, B, C            [A], [unreadable1], [unreadable2], [B, C]
   * A(1200 events), B(100)                      [A], [B]
   * </pre>
   *
   * @param records decoded records in their original batch order
   * @return ordered, nonempty groups, or an empty list if there are no records
   */
  private static List<List<Decoded>> groups(List<Decoded> records) {
    List<List<Decoded>> groups = new ArrayList<>();
    List<Decoded> group = List.of();
    int events = 0;
    int bytes = 0;
    for (Decoded record : records) {
      boolean canJoinCurrentGroup =
          !group.isEmpty()
              && record instanceof ReadableRecord
              && group.getLast() instanceof ReadableRecord
              && events + record.eventCount() <= MAX_EVENTS_PER_TRANSACTION
              && bytes + record.byteCount() <= MAX_BYTES_PER_TRANSACTION;
      if (!canJoinCurrentGroup) {
        group = new ArrayList<>();
        groups.add(group);
        events = 0;
        bytes = 0;
      }
      group.add(record);
      events += record.eventCount();
      bytes += record.byteCount();
    }
    return groups;
  }

  /** False if the group holds an unreadable record or the database refuses it as a whole. */
  private boolean tryStoreGroup(List<Decoded> group) {
    List<NewEvent> events = new ArrayList<>();
    for (Decoded record : group) {
      if (!(record instanceof ReadableRecord readable)) {
        return false;
      }
      events.addAll(readable.events());
    }
    try {
      persistence.persist(events);
      return true;
    } catch (PermanentException refused) {
      return false;
    }
  }

  private void storeOrDeadLetter(Decoded record) {
    switch (record) {
      case UnreadableRecord unreadable ->
          publishToDeadLetter(unreadable.record(), unreadable.reason());
      case ReadableRecord readable -> {
        try {
          persistence.persist(readable.events());
        } catch (PermanentException refused) {
          publishToDeadLetter(readable.record(), refused);
        }
      }
    }
  }

  private void publishToDeadLetter(
      ConsumerRecord<String, byte[]> record, PermanentException reason) {
    deadLetters.publish(record, reason);
    log.error(
        "Dead-lettered {}-{}@{}: {}",
        record.topic(),
        record.partition(),
        record.offset(),
        reason.toString());
  }

  private static void logRetry(ConsumerRecord<String, byte[]> first, RuntimeException unstored) {
    log.warn(
        "Retrying from {}-{}@{} in {}: {}",
        first.topic(),
        first.partition(),
        first.offset(),
        RETRY_INTERVAL,
        unstored.toString());
  }

  private Decoded decode(ConsumerRecord<String, byte[]> record) {
    try {
      return new ReadableRecord(record, codec.decode(record.value()));
    } catch (MalformedMessageException malformed) {
      return new UnreadableRecord(record, malformed);
    }
  }

  private sealed interface Decoded {

    ConsumerRecord<String, byte[]> record();

    int eventCount();

    default int byteCount() {
      byte[] value = record().value();
      return value == null ? 0 : value.length;
    }
  }

  private record ReadableRecord(ConsumerRecord<String, byte[]> record, List<NewEvent> events)
      implements Decoded {

    @Override
    public int eventCount() {
      return events.size();
    }
  }

  private record UnreadableRecord(
      ConsumerRecord<String, byte[]> record, MalformedMessageException reason) implements Decoded {

    @Override
    public int eventCount() {
      return 0;
    }
  }
}
