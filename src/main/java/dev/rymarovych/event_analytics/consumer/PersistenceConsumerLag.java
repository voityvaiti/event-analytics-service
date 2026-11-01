package dev.rymarovych.event_analytics.consumer;

import dev.rymarovych.event_analytics.messaging.EventTopics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * Publishes the persistence group's lag from the offsets it committed on the broker, so the figure
 * stays current while the consumer is stopped, when the Kafka client's own lag metrics are gone.
 */
@Component
class PersistenceConsumerLag implements AutoCloseable {

  static final Duration QUERY_TIMEOUT = Duration.ofMillis(500);

  private final Admin admin;

  PersistenceConsumerLag(KafkaAdmin kafkaAdmin, MeterRegistry meterRegistry) {
    this.admin = Admin.create(kafkaAdmin.getConfigurationProperties());
    Gauge.builder("persistence.consumer.lag", this, PersistenceConsumerLag::records)
        .description("Records on events.raw the persistence group has not committed")
        .baseUnit("records")
        .register(meterRegistry);
  }

  /**
   * A partition without a committed offset counts from its log start, where the group would begin.
   * NaN when the broker does not answer in time, so an outage never reads as an empty backlog.
   */
  double records() {
    try {
      Map<TopicPartition, Long> positions = committedPositions();
      List<TopicPartition> partitions = partitions();
      List<TopicPartition> uncommitted =
          partitions.stream().filter(partition -> !positions.containsKey(partition)).toList();
      if (!uncommitted.isEmpty()) {
        offsets(uncommitted, OffsetSpec.earliest())
            .forEach((partition, start) -> positions.put(partition, start.offset()));
      }
      long lag = 0;
      for (var end : offsets(partitions, OffsetSpec.latest()).entrySet()) {
        lag += Math.max(0, end.getValue().offset() - positions.getOrDefault(end.getKey(), 0L));
      }
      return lag;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return Double.NaN;
    } catch (ExecutionException unanswered) {
      return Double.NaN;
    }
  }

  private Map<TopicPartition, Long> committedPositions()
      throws InterruptedException, ExecutionException {
    Map<TopicPartition, OffsetAndMetadata> committed =
        admin
            .listConsumerGroupOffsets(
                EventsRecordListener.LISTENER_ID,
                new ListConsumerGroupOffsetsOptions().timeoutMs(timeoutMs()))
            .partitionsToOffsetAndMetadata()
            .get();
    Map<TopicPartition, Long> positions = new HashMap<>();
    committed.forEach(
        (partition, commit) -> {
          if (commit != null) {
            positions.put(partition, commit.offset());
          }
        });
    return positions;
  }

  private List<TopicPartition> partitions() throws InterruptedException, ExecutionException {
    return admin
        .describeTopics(
            List.of(EventTopics.EVENTS), new DescribeTopicsOptions().timeoutMs(timeoutMs()))
        .allTopicNames()
        .get()
        .values()
        .stream()
        .flatMap(
            topic ->
                topic.partitions().stream()
                    .map(partition -> new TopicPartition(topic.name(), partition.partition())))
        .toList();
  }

  private Map<TopicPartition, ListOffsetsResultInfo> offsets(
      List<TopicPartition> partitions, OffsetSpec spec)
      throws InterruptedException, ExecutionException {
    return admin
        .listOffsets(
            partitions.stream().collect(Collectors.toMap(Function.identity(), partition -> spec)),
            new ListOffsetsOptions().timeoutMs(timeoutMs()))
        .all()
        .get();
  }

  private static int timeoutMs() {
    return (int) QUERY_TIMEOUT.toMillis();
  }

  @Override
  public void close() {
    admin.close(QUERY_TIMEOUT);
  }
}
