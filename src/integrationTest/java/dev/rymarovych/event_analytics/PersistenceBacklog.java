package dev.rymarovych.event_analytics;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaAdmin;

/** Waits until the persistence group has handled every record on events.raw. */
public final class PersistenceBacklog {

  private PersistenceBacklog() {}

  public static void awaitEmpty(KafkaAdmin kafkaAdmin) {
    try (var admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
      await().atMost(Duration.ofSeconds(30)).until(() -> isEmpty(admin));
    }
  }

  private static boolean isEmpty(Admin admin) throws Exception {
    Map<TopicPartition, OffsetAndMetadata> committed =
        admin.listConsumerGroupOffsets("persistence").partitionsToOffsetAndMetadata().get();
    var partitions =
        admin
            .describeTopics(java.util.List.of("events.raw"))
            .allTopicNames()
            .get()
            .get("events.raw")
            .partitions()
            .stream()
            .map(partition -> new TopicPartition("events.raw", partition.partition()))
            .collect(Collectors.toMap(partition -> partition, partition -> OffsetSpec.latest()));
    var ends = admin.listOffsets(partitions).all().get();
    return ends.entrySet().stream()
        .allMatch(
            end -> {
              OffsetAndMetadata offset = committed.get(end.getKey());
              long position = offset == null ? 0 : offset.offset();
              return position >= end.getValue().offset();
            });
  }
}
