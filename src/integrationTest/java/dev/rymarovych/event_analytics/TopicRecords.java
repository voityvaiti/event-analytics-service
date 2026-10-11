package dev.rymarovych.event_analytics;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.core.KafkaAdmin;

/** Reads a topic from its beginning up to its current end, outside any application group. */
public final class TopicRecords {

  private TopicRecords() {}

  public static List<ConsumerRecord<String, byte[]>> read(KafkaAdmin kafkaAdmin, String topic) {
    Map<String, Object> config =
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            kafkaAdmin.getConfigurationProperties().get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG),
            ConsumerConfig.GROUP_ID_CONFIG,
            "test-reader-" + UUID.randomUUID(),
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
            false);
    try (var consumer =
        new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer())) {
      List<TopicPartition> partitions =
          consumer.partitionsFor(topic).stream()
              .map(partition -> new TopicPartition(topic, partition.partition()))
              .toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
      List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
      while (partitions.stream()
          .anyMatch(partition -> consumer.position(partition) < ends.get(partition))) {
        consumer.poll(Duration.ofMillis(200)).forEach(records::add);
      }
      return records;
    }
  }
}
