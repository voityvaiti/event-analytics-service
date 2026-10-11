package dev.rymarovych.event_analytics.messaging;

import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Declares the event topics; KafkaAdmin creates them on startup with the broker's replication. */
@Configuration(proxyBeanMethods = false)
class KafkaTopicConfiguration {

  @Bean
  NewTopic eventsTopic() {
    return TopicBuilder.name(EventTopics.EVENTS)
        .partitions(3)
        .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(Duration.ofDays(7).toMillis()))
        .build();
  }

  /** Kept until removed by hand, so an unresolved message cannot expire unnoticed. */
  @Bean
  NewTopic deadLetterTopic() {
    return TopicBuilder.name(EventTopics.DEAD_LETTERS)
        .partitions(1)
        .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
        .build();
  }
}
