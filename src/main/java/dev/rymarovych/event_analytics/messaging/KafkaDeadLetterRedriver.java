package dev.rymarovych.event_analytics.messaging;

import dev.rymarovych.event_analytics.domain.ingestion.RedriveIncompleteException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link DeadLetterRedriver} reading the dead-letter topic as its own group, one call at a time.
 *
 * <p>The end offsets are fixed before anything is sent, so a record that bounces straight back is
 * left for the next call rather than chased. Offsets are committed only after events.raw has
 * acknowledged every record they cover; a failure in between resends those records next time, which
 * the idempotent sink absorbs.
 */
@Component
class KafkaDeadLetterRedriver implements DeadLetterRedriver {

  static final String GROUP_ID = "persistence-redrive";
  static final Duration BROKER_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);

  private final ConsumerFactory<String, byte[]> consumerFactory;
  private final KafkaTemplate<String, byte[]> kafkaTemplate;
  private final ReentrantLock oneAtATime = new ReentrantLock();

  KafkaDeadLetterRedriver(
      ConsumerFactory<String, byte[]> consumerFactory,
      KafkaTemplate<String, byte[]> kafkaTemplate) {
    this.consumerFactory = consumerFactory;
    this.kafkaTemplate = kafkaTemplate;
  }

  @Override
  public int redrive() {
    oneAtATime.lock();
    try (Consumer<String, byte[]> consumer =
        consumerFactory.createConsumer(GROUP_ID, GROUP_ID, null, withoutAutoCommit())) {
      return redrive(consumer);
    } catch (KafkaException | org.springframework.kafka.KafkaException unanswered) {
      throw new RedriveIncompleteException("The broker stopped answering", unanswered);
    } finally {
      oneAtATime.unlock();
    }
  }

  private int redrive(Consumer<String, byte[]> consumer) {
    List<TopicPartition> partitions =
        consumer.partitionsFor(EventTopics.DEAD_LETTERS, BROKER_TIMEOUT).stream()
            .map(info -> new TopicPartition(info.topic(), info.partition()))
            .toList();
    consumer.assign(partitions);
    Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, BROKER_TIMEOUT);
    Map<TopicPartition, OffsetAndMetadata> committed =
        consumer.committed(Set.copyOf(partitions), BROKER_TIMEOUT);
    for (TopicPartition partition : partitions) {
      OffsetAndMetadata start = committed.get(partition);
      if (start == null) {
        consumer.seekToBeginning(List.of(partition));
      } else {
        consumer.seek(partition, start.offset());
      }
    }

    int redriven = 0;
    long lastProgress = System.nanoTime();
    while (!reachedEnds(consumer, ends)) {
      ConsumerRecords<String, byte[]> records = consumer.poll(POLL_TIMEOUT);
      if (records.isEmpty()) {
        if (System.nanoTime() - lastProgress > BROKER_TIMEOUT.toNanos()) {
          throw new RedriveIncompleteException(
              "The dead-letter topic returned nothing for " + BROKER_TIMEOUT, null);
        }
        continue;
      }
      Republished republished = republish(records, ends);
      if (!republished.offsets().isEmpty()) {
        consumer.commitSync(republished.offsets(), BROKER_TIMEOUT);
      }
      redriven += republished.records();
      lastProgress = System.nanoTime();
    }
    return redriven;
  }

  private static boolean reachedEnds(
      Consumer<String, byte[]> consumer, Map<TopicPartition, Long> ends) {
    return ends.entrySet().stream()
        .allMatch(end -> consumer.position(end.getKey(), BROKER_TIMEOUT) >= end.getValue());
  }

  /** Sends the records before each partition's end and waits until events.raw holds them all. */
  private Republished republish(
      ConsumerRecords<String, byte[]> records, Map<TopicPartition, Long> ends) {
    Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
    List<CompletableFuture<?>> sends = new ArrayList<>();
    for (TopicPartition partition : records.partitions()) {
      long end = ends.getOrDefault(partition, 0L);
      for (ConsumerRecord<String, byte[]> record : records.records(partition)) {
        if (record.offset() < end) {
          sends.add(kafkaTemplate.send(EventTopics.EVENTS, record.key(), record.value()));
          offsets.put(partition, new OffsetAndMetadata(record.offset() + 1));
        }
      }
    }
    try {
      CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
          .get(BROKER_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new RedriveIncompleteException("Interrupted while redriving", interrupted);
    } catch (ExecutionException | TimeoutException unconfirmed) {
      throw new RedriveIncompleteException("events.raw did not confirm", unconfirmed);
    }
    return new Republished(offsets, sends.size());
  }

  private static Properties withoutAutoCommit() {
    Properties properties = new Properties();
    properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    return properties;
  }

  private record Republished(Map<TopicPartition, OffsetAndMetadata> offsets, int records) {}
}
