package dev.rymarovych.event_analytics.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.rymarovych.event_analytics.domain.ingestion.RedriveIncompleteException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

class KafkaDeadLetterRedriverTest {

  private static final TopicPartition DEAD_LETTERS =
      new TopicPartition(EventTopics.DEAD_LETTERS, 0);

  @SuppressWarnings("unchecked")
  private final ConsumerFactory<String, byte[]> consumerFactory = mock(ConsumerFactory.class);

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, byte[]> kafkaTemplate = mock(KafkaTemplate.class);

  private final InspectableConsumer consumer = new InspectableConsumer();
  private final KafkaDeadLetterRedriver redriver =
      new KafkaDeadLetterRedriver(consumerFactory, kafkaTemplate);

  @BeforeEach
  void oneDeadLetterPartition() {
    when(consumerFactory.createConsumer(any(), any(), any(), any())).thenReturn(consumer);
    consumer.updatePartitions(
        EventTopics.DEAD_LETTERS,
        List.of(new PartitionInfo(EventTopics.DEAD_LETTERS, 0, null, null, null)));
    consumer.updateBeginningOffsets(Map.of(DEAD_LETTERS, 0L));
  }

  @Test
  void republishesEveryDeadLetterUnchangedAndRecordsWhereItGot() {
    deadLetters("first", "second");
    consumer.updateEndOffsets(Map.of(DEAD_LETTERS, 2L));
    eventsRawConfirms();

    assertThat(redriver.redrive()).isEqualTo(2);

    verify(kafkaTemplate).send(EventTopics.EVENTS, "key-0", bytes("first"));
    verify(kafkaTemplate).send(EventTopics.EVENTS, "key-1", bytes("second"));
    assertThat(committed()).containsEntry(DEAD_LETTERS, new OffsetAndMetadata(2));
  }

  @Test
  void leavesWhatArrivesDuringTheCallForTheNextOne() {
    deadLetters("before", "bounced back");
    consumer.updateEndOffsets(Map.of(DEAD_LETTERS, 1L));
    eventsRawConfirms();

    assertThat(redriver.redrive()).isEqualTo(1);

    verify(kafkaTemplate, never()).send(EventTopics.EVENTS, "key-1", bytes("bounced back"));
    assertThat(committed()).containsEntry(DEAD_LETTERS, new OffsetAndMetadata(1));
  }

  @Test
  void resumesAfterTheLastRedrivenRecord() {
    consumer.committedByAnEarlierCall = Map.of(DEAD_LETTERS, new OffsetAndMetadata(1));
    deadLetters("redriven before", "new");
    consumer.updateEndOffsets(Map.of(DEAD_LETTERS, 2L));
    eventsRawConfirms();

    assertThat(redriver.redrive()).isEqualTo(1);

    verify(kafkaTemplate).send(EventTopics.EVENTS, "key-1", bytes("new"));
    verify(kafkaTemplate, never()).send(EventTopics.EVENTS, "key-0", bytes("redriven before"));
    assertThat(committed()).containsEntry(DEAD_LETTERS, new OffsetAndMetadata(2));
  }

  @Test
  void recordsNothingWhenEventsRawDoesNotConfirm() {
    deadLetters("unconfirmed");
    consumer.updateEndOffsets(Map.of(DEAD_LETTERS, 1L));
    when(kafkaTemplate.send(eq(EventTopics.EVENTS), any(), any()))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

    assertThatThrownBy(redriver::redrive).isInstanceOf(RedriveIncompleteException.class);

    assertThat(committed()).isEmpty();
  }

  @Test
  void anEmptyTopicSendsNothing() {
    consumer.updateEndOffsets(Map.of(DEAD_LETTERS, 0L));

    assertThat(redriver.redrive()).isZero();

    verifyNoInteractions(kafkaTemplate);
  }

  /** Added on the first poll: a mock consumer takes records only once the partition is assigned. */
  private void deadLetters(String... values) {
    consumer.schedulePollTask(
        () -> {
          for (int offset = 0; offset < values.length; offset++) {
            consumer.addRecord(
                new ConsumerRecord<>(
                    EventTopics.DEAD_LETTERS, 0, offset, "key-" + offset, bytes(values[offset])));
          }
        });
  }

  private void eventsRawConfirms() {
    when(kafkaTemplate.send(eq(EventTopics.EVENTS), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(null));
  }

  private Map<TopicPartition, OffsetAndMetadata> committed() {
    return consumer.committed(Set.of(DEAD_LETTERS));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Stays readable after the redriver closes it, so the test can see what it committed. A mock
   * forgets commits on assign where the broker keeps them, so an earlier call's are restored there.
   */
  private static final class InspectableConsumer extends MockConsumer<String, byte[]> {

    Map<TopicPartition, OffsetAndMetadata> committedByAnEarlierCall = Map.of();

    InspectableConsumer() {
      super("earliest");
    }

    @Override
    public synchronized void assign(Collection<TopicPartition> partitions) {
      super.assign(partitions);
      if (!committedByAnEarlierCall.isEmpty()) {
        commitSync(committedByAnEarlierCall);
      }
    }

    @Override
    public void close() {}
  }
}
